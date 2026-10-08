package dev.ccrec.transcript;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  public static final String COST = "cost";
  public static final String PR_LINK = "pr_link";
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
          "agent-name");

  /** An attribute is a fact, not content: a longer string is cut to this many characters. */
  private static final int ATTRIBUTE_CHARS = 300;

  private static final ObjectMapper JSON = new ObjectMapper();

  public record Block(String kind, String subtype, String text, String toolName, String toolUseId) {}

  /** {@code thinking} is part of {@code output}; the two cache lifetimes are part of {@code cacheCreation}. */
  public record Usage(
      Long input,
      Long output,
      Long cacheRead,
      Long cacheCreation,
      Long thinking,
      Long cacheCreation5m,
      Long cacheCreation1h,
      Long webSearches,
      Long webFetches) {}

  /** The session's running totals as Claude Code writes them down now and then ({@code cost-state}). */
  public record Cost(Double usd, Long apiMillis, Long toolMillis, Long linesAdded, Long linesRemoved) {}

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
      Usage usage,
      String entrypoint,
      String attributes,
      Cost cost) {}

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
    Cost cost = null;
    ObjectNode attributes = JSON.createObjectNode();
    switch (type) {
      case "user" -> {
        userAttributes(node, attributes);
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
        assistantAttributes(node, attributes);
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
        // their own rather than in content, or in an empty one: those are kept as the whole record.
        String content = stringify(node.path("content"));
        boolean said = content != null && !content.isEmpty();
        add(blocks, SYSTEM, textOrNull(node.path("subtype")), said ? content : raw, null, null);
        systemAttributes(node, attributes);
      }
      case "cost-state" -> {
        blocks.add(new Block(COST, null, raw, null, null));
        cost =
            new Cost(
                node.path("totalCostUSD").isNumber() ? node.path("totalCostUSD").asDouble() : null,
                longOrNull(node.path("totalAPIDuration")),
                longOrNull(node.path("totalToolDuration")),
                longOrNull(node.path("totalLinesAdded")),
                longOrNull(node.path("totalLinesRemoved")));
        put(attributes, "cost_usd", node.path("totalCostUSD"));
        put(attributes, "api_ms", node.path("totalAPIDuration"));
        put(attributes, "api_ms_without_retries", node.path("totalAPIDurationWithoutRetries"));
        put(attributes, "tool_ms", node.path("totalToolDuration"));
        put(attributes, "duration_ms", node.path("totalDuration"));
        put(attributes, "lines_added", node.path("totalLinesAdded"));
        put(attributes, "lines_removed", node.path("totalLinesRemoved"));
        put(attributes, "unknown_model_cost", node.path("hasUnknownModelCost"));
      }
      case "pr-link" -> {
        String url = textOrNull(node.path("prUrl"));
        blocks.add(new Block(PR_LINK, null, url != null ? url : raw, null, null));
        put(attributes, "pr_number", node.path("prNumber"));
        put(attributes, "pr_repository", node.path("prRepository"));
        put(attributes, "pr_url", node.path("prUrl"));
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
        usage,
        textOrNull(node.path("entrypoint")),
        attributes.isEmpty() ? null : attributes.toString(),
        cost);
  }

  /** Why and how the model answered, and what the answer is attributed to. */
  private static void assistantAttributes(JsonNode node, ObjectNode attributes) {
    JsonNode message = node.path("message");
    put(attributes, "stop_reason", message.path("stop_reason"));
    put(attributes, "service_tier", message.path("usage").path("service_tier"));
    put(attributes, "speed", message.path("usage").path("speed"));
    put(attributes, "request_id", node.path("requestId"));
    put(attributes, "effort", node.path("effort"));
    put(attributes, "thinking_ms", node.path("thinkingDurationMs"));
    put(attributes, "api_error", node.path("error").isTextual() ? node.path("error") : node.path("apiError"));
    put(attributes, "api_error_status", node.path("apiErrorStatus"));
    put(attributes, "skill", node.path("attributionSkill"));
    put(attributes, "plugin", node.path("attributionPlugin"));
    put(attributes, "mcp_server", node.path("attributionMcpServer"));
    put(attributes, "mcp_tool", node.path("attributionMcpTool"));
  }

  /** The mode a prompt was sent in, what became of a tool call, and what a tool says it did. */
  private static void userAttributes(JsonNode node, ObjectNode attributes) {
    put(attributes, "permission_mode", node.path("permissionMode"));
    put(attributes, "prompt_source", node.path("promptSource"));
    put(attributes, "tool_denial", node.path("toolDenialKind"));
    flag(attributes, "interrupted", node.path("interruptedMessageId").isTextual());
    flag(attributes, "compact_summary", node.path("isCompactSummary").asBoolean(false));
    JsonNode result = node.path("toolUseResult");
    if (!result.isObject()) {
      return;
    }
    put(attributes, "file_path", result.path("filePath"));
    put(attributes, "status", result.path("status"));
    put(attributes, "agent_id", result.path("agentId"));
    put(attributes, "resolved_model", result.path("resolvedModel"));
    flag(attributes, "tool_interrupted", result.path("interrupted").asBoolean(false));
    if (result.path("structuredPatch").isArray() && !result.path("structuredPatch").isEmpty()) {
      long added = 0;
      long removed = 0;
      for (JsonNode hunk : result.path("structuredPatch")) {
        for (JsonNode line : hunk.path("lines")) {
          added += line.asText().startsWith("+") ? 1 : 0;
          removed += line.asText().startsWith("-") ? 1 : 0;
        }
      }
      attributes.put("lines_added", added);
      attributes.put("lines_removed", removed);
    }
  }

  private static void systemAttributes(JsonNode node, ObjectNode attributes) {
    switch (node.path("subtype").asText("")) {
      case "turn_duration" -> {
        put(attributes, "duration_ms", node.path("durationMs"));
        put(attributes, "message_count", node.path("messageCount"));
      }
      case "stop_hook_summary" -> {
        put(attributes, "hook_count", node.path("hookCount"));
        if (node.path("hookErrors").isArray() && !node.path("hookErrors").isEmpty()) {
          attributes.put("hook_errors", node.path("hookErrors").size());
        }
        flag(attributes, "prevented_continuation", node.path("preventedContinuation").asBoolean(false));
      }
      case "compact_boundary" -> {
        JsonNode compact = node.path("compactMetadata");
        put(attributes, "compact_trigger", compact.path("trigger"));
        put(attributes, "pre_tokens", compact.path("preTokens"));
        put(attributes, "post_tokens", compact.path("postTokens"));
        put(attributes, "duration_ms", compact.path("durationMs"));
      }
      case "local_command" -> put(attributes, "command", node.path("commandRun").path("command"));
      default -> {}
    }
  }

  /** Scalars only, and only when there is one: an attribute is absent rather than null or empty. */
  private static void put(ObjectNode attributes, String name, JsonNode value) {
    if (value.isNumber() || value.isBoolean()) {
      attributes.set(name, value);
    } else if (value.isTextual() && !value.asText().isEmpty()) {
      String text = value.asText();
      attributes.put(name, text.length() <= ATTRIBUTE_CHARS ? text : text.substring(0, ATTRIBUTE_CHARS));
    }
  }

  private static void flag(ObjectNode attributes, String name, boolean set) {
    if (set) {
      attributes.put(name, true);
    }
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
        longOrNull(usage.path("cache_creation_input_tokens")),
        longOrNull(usage.path("output_tokens_details").path("thinking_tokens")),
        longOrNull(usage.path("cache_creation").path("ephemeral_5m_input_tokens")),
        longOrNull(usage.path("cache_creation").path("ephemeral_1h_input_tokens")),
        longOrNull(usage.path("server_tool_use").path("web_search_requests")),
        longOrNull(usage.path("server_tool_use").path("web_fetch_requests")));
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
    return new Line(blocks, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
