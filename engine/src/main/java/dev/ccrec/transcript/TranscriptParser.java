package dev.ccrec.transcript;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns one line of a Claude Code transcript (JSONL) into content blocks.
 *
 * <p>The transcript format is internal to Claude Code and changes between versions, so nothing here
 * fails on an unexpected shape: a record type this parser does not know, or a known one that does
 * not look the way it is read here, is kept whole as an {@code unknown} block, to be re-read once a
 * parser for it exists. Only a record with nothing in it to keep — an empty text, a thinking block
 * that carries a signature and no text — leaves no block.
 */
public final class TranscriptParser {

  public static final String USER_PROMPT = "user_prompt";
  public static final String USER_META = "user_meta";
  public static final String ASSISTANT_TEXT = "assistant_text";
  public static final String THINKING = "thinking";
  public static final String TOOL_USE = "tool_use";
  public static final String TOOL_RESULT = "tool_result";
  public static final String SYSTEM_PROMPT = "system_prompt";
  public static final String TOOL_DEFINITIONS = "tool_definitions";
  public static final String CONTEXT = "context";
  public static final String SYSTEM = "system";
  public static final String UNKNOWN = "unknown";

  /** Session bookkeeping that carries no conversation content. */
  private static final Set<String> BOOKKEEPING =
      Set.of(
          "mode",
          "permission-mode",
          "atis-latch",
          "last-prompt",
          "file-history-snapshot",
          "file-history-delta",
          "queue-operation",
          "pr-link",
          "agent-name",
          "cost-state");

  private static final ObjectMapper JSON = new ObjectMapper();

  public record Block(String kind, String subtype, String text, String toolName, String toolUseId) {}

  public record Usage(Long input, Long output, Long cacheRead, Long cacheCreation) {}

  public record Line(
      List<Block> blocks,
      Long ts,
      String uuid,
      String parentUuid,
      String messageId,
      String cwd,
      String gitBranch,
      String version,
      String model,
      String title,
      Usage usage) {}

  public Line parse(String raw) {
    JsonNode node;
    try {
      node = JSON.readTree(raw);
    } catch (JsonProcessingException e) {
      node = null;
    }
    if (node == null || !node.isObject()) {
      return bare(List.of(new Block(UNKNOWN, "unparsed", raw, null, null)));
    }
    String type = node.path("type").asText("");
    List<Block> blocks = new ArrayList<>();
    String messageId = null;
    String model = null;
    String title = null;
    Usage usage = null;
    switch (type) {
      case "user" -> {
        JsonNode content = node.path("message").path("content");
        if (content.isTextual() || content.isArray()) {
          userBlocks(node, content, blocks);
        } else {
          blocks.add(new Block(UNKNOWN, type, raw, null, null));
        }
      }
      case "assistant" -> {
        JsonNode message = node.path("message");
        messageId = textOrNull(message.path("id"));
        model = textOrNull(message.path("model"));
        usage = usage(message.path("usage"));
        if (message.path("content").isArray()) {
          assistantBlocks(message.path("content"), blocks);
        } else {
          blocks.add(new Block(UNKNOWN, type, raw, null, null));
        }
      }
      case "attachment" -> {
        if (node.path("attachment").isObject()) {
          attachmentBlocks(node.path("attachment"), blocks);
        } else {
          blocks.add(new Block(UNKNOWN, type, raw, null, null));
        }
      }
      case "system" -> {
        // Some subtypes (a turn's duration, a hook summary) say what they have to say in fields of
        // their own rather than in content: those are kept as the whole record.
        String content = stringify(node.path("content"));
        add(blocks, SYSTEM, textOrNull(node.path("subtype")), content != null ? content : raw, null, null);
      }
      case "ai-title" -> title = textOrNull(node.path("aiTitle"));
      default -> {
        if (!BOOKKEEPING.contains(type)) {
          blocks.add(new Block(UNKNOWN, type.isEmpty() ? null : type, raw, null, null));
        }
      }
    }
    return new Line(
        blocks,
        timestamp(node.path("timestamp")),
        textOrNull(node.path("uuid")),
        textOrNull(node.path("parentUuid")),
        messageId,
        textOrNull(node.path("cwd")),
        textOrNull(node.path("gitBranch")),
        textOrNull(node.path("version")),
        model,
        title,
        usage);
  }

  private static void userBlocks(JsonNode node, JsonNode content, List<Block> blocks) {
    String textKind = node.path("isMeta").asBoolean(false) ? USER_META : USER_PROMPT;
    if (content.isTextual()) {
      add(blocks, textKind, null, content.asText(), null, null);
      return;
    }
    for (JsonNode block : content) {
      String type = block.path("type").asText("");
      switch (type) {
        case "text" -> add(blocks, textKind, null, block.path("text").asText(), null, null);
        case "tool_result" ->
            add(
                blocks,
                TOOL_RESULT,
                block.path("is_error").asBoolean(false) ? "error" : null,
                flatten(block.path("content")),
                null,
                textOrNull(block.path("tool_use_id")));
        default -> add(blocks, "user_" + type, null, block.toString(), null, null);
      }
    }
  }

  private static void assistantBlocks(JsonNode content, List<Block> blocks) {
    for (JsonNode block : content) {
      String type = block.path("type").asText("");
      switch (type) {
        case "text" -> add(blocks, ASSISTANT_TEXT, null, block.path("text").asText(), null, null);
        case "thinking" -> add(blocks, THINKING, null, block.path("thinking").asText(), null, null);
        case "tool_use" ->
            add(
                blocks,
                TOOL_USE,
                null,
                block.path("input").toString(),
                textOrNull(block.path("name")),
                textOrNull(block.path("id")));
        default -> add(blocks, "assistant_" + type, null, block.toString(), null, null);
      }
    }
  }

  private static void attachmentBlocks(JsonNode attachment, List<Block> blocks) {
    String type = attachment.path("type").asText("");
    if (!type.equals("prompt_snapshot")) {
      add(blocks, CONTEXT, type.isEmpty() ? null : type, attachment.toString(), null, null);
      return;
    }
    JsonNode prompt = attachment.path("systemPrompt");
    if (prompt.isArray()) {
      List<String> parts = new ArrayList<>();
      prompt.forEach(part -> parts.add(part.isTextual() ? part.asText() : part.toString()));
      add(blocks, SYSTEM_PROMPT, null, String.join("\n", parts), null, null);
    } else {
      add(blocks, SYSTEM_PROMPT, null, stringify(prompt), null, null);
    }
    JsonNode tools = attachment.path("tools");
    if (!tools.isMissingNode() && !tools.isNull()) {
      add(blocks, TOOL_DEFINITIONS, null, tools.toString(), null, null);
    }
  }

  /** A tool result is a string or a list of content items; text items read as their text. */
  private static String flatten(JsonNode content) {
    if (content.isTextual()) {
      return content.asText();
    }
    if (!content.isArray()) {
      return stringify(content);
    }
    List<String> parts = new ArrayList<>();
    for (JsonNode item : content) {
      parts.add(item.path("type").asText("").equals("text") ? item.path("text").asText() : item.toString());
    }
    return String.join("\n", parts);
  }

  private static void add(
      List<Block> blocks, String kind, String subtype, String text, String toolName, String toolUseId) {
    if (text != null && !text.isEmpty()) {
      blocks.add(new Block(kind, subtype, text, toolName, toolUseId));
    }
  }

  private static Usage usage(JsonNode usage) {
    if (!usage.isObject()) {
      return null;
    }
    return new Usage(
        longOrNull(usage.path("input_tokens")),
        longOrNull(usage.path("output_tokens")),
        longOrNull(usage.path("cache_read_input_tokens")),
        longOrNull(usage.path("cache_creation_input_tokens")));
  }

  private static Long timestamp(JsonNode node) {
    if (!node.isTextual()) {
      return null;
    }
    try {
      return Instant.parse(node.asText()).toEpochMilli();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static String stringify(JsonNode node) {
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    return node.isTextual() ? node.asText() : node.toString();
  }

  private static String textOrNull(JsonNode node) {
    return node.isTextual() && !node.asText().isEmpty() ? node.asText() : null;
  }

  private static Long longOrNull(JsonNode node) {
    return node.isNumber() ? node.asLong() : null;
  }

  private static Line bare(List<Block> blocks) {
    return new Line(blocks, null, null, null, null, null, null, null, null, null, null);
  }
}
