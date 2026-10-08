package dev.ccrec.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import dev.ccrec.model.UsageRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One session at a glance: what its row says, what Claude Code wrote down about its cost, and what
 * its records add up to — turns, stops, errors, tools, usage. The command line prints it and the
 * browser UI shows it, from the same object.
 */
public final class SessionSummary {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The sources of a prompt somebody, or a program driving Claude Code, sent. */
  private static final Set<String> PROMPTED = Set.of("typed", "suggestion_accepted", "queued", "sdk");

  private SessionSummary() {}

  public static ObjectNode of(
      String sessionId, Optional<SessionRecord> session, List<MessageRecord> messages, List<UsageRecord> usage) {
    ObjectNode summary = JSON.createObjectNode();
    summary.put("sessionId", sessionId);
    session.ifPresent(
        s -> {
          summary.put("accountId", s.accountId());
          putText(summary, "projectPath", s.projectPath());
          putText(summary, "gitBranch", s.gitBranch());
          putText(summary, "entrypoint", s.entrypoint());
          putText(summary, "ccVersion", s.ccVersion());
          summary.put("startedAt", Instant.ofEpochMilli(s.startedAt()).toString());
          if (s.endedAt() != null) {
            summary.put("lastActivityAt", Instant.ofEpochMilli(s.endedAt()).toString());
          }
          putText(summary, "title", s.title());
          if (s.costUsd() != null) {
            summary.put("costUsd", s.costUsd());
          }
          putNumber(summary, "apiDurationMs", s.apiDurationMs());
          putNumber(summary, "toolDurationMs", s.toolDurationMs());
          putNumber(summary, "linesAdded", s.linesAdded());
          putNumber(summary, "linesRemoved", s.linesRemoved());
        });

    long prompts = 0;
    Map<String, Long> promptSources = new java.util.TreeMap<>();
    long turns = 0;
    long turnMillis = 0;
    long longestTurnMillis = 0;
    long thinkingMillis = 0;
    long compactions = 0;
    long interrupted = 0;
    long hookErrors = 0;
    Set<String> agents = new java.util.TreeSet<>();
    Set<String> files = new java.util.TreeSet<>();
    Map<String, String> stops = new LinkedHashMap<>();
    Map<String, Long> apiErrors = new java.util.TreeMap<>();
    Map<String, Long> denials = new java.util.TreeMap<>();
    Map<String, Set<String>> named = new LinkedHashMap<>();
    Map<String, Long> mcpServers = new java.util.TreeMap<>();
    for (String key : List.of("permission_mode", "skill", "plugin", "command")) {
      named.put(key, new java.util.TreeSet<>());
    }
    Set<String> pullRequests = new java.util.LinkedHashSet<>();
    Map<String, String> toolOfCall = new HashMap<>();
    Map<String, long[]> tools = new java.util.TreeMap<>();
    for (MessageRecord m : messages) {
      if (!MessageRecord.MAIN_AGENT.equals(m.agentId())) {
        agents.add(m.agentId());
      }
      switch (m.kind()) {
        case "user_prompt" -> {
          // The main thread's prompts by where they came from; Claude Code writes some itself (a
          // background task's notification, the summary after a compaction), and those are not
          // somebody's prompt.
          JsonNode prompt = attributes(m);
          if (MessageRecord.MAIN_AGENT.equals(m.agentId())) {
            String source =
                prompt.path("compact_summary").asBoolean(false)
                    ? "compact_summary"
                    : prompt.path("prompt_source").asText(prompt.path("origin").asText("unlabelled"));
            promptSources.merge(source, 1L, Long::sum);
            prompts += PROMPTED.contains(source) || "human".equals(prompt.path("origin").asText()) ? 1 : 0;
          }
        }
        case "tool_use" -> {
          String tool = m.toolName() != null ? m.toolName() : "unknown";
          tools.computeIfAbsent(tool, k -> new long[2])[0]++;
          // An MCP tool is named mcp__<server>__<tool>. Claude Code also names the server on the
          // line, but not on every one and not by the same name, so the tool's name is what counts.
          String[] mcp = tool.split("__", 3);
          if (mcp.length == 3 && mcp[0].equals("mcp")) {
            mcpServers.merge(mcp[1], 1L, Long::sum);
          }
          if (m.toolUseId() != null) {
            toolOfCall.put(m.toolUseId(), tool);
          }
        }
        case "tool_result" -> {
          if ("error".equals(m.subtype())) {
            tools.computeIfAbsent(toolOfCall.getOrDefault(m.toolUseId(), "unknown"), k -> new long[2])[1]++;
          }
        }
        case "pr_link" -> pullRequests.add(m.preview());
        case "system" -> compactions += "compact_boundary".equals(m.subtype()) ? 1 : 0;
        default -> {}
      }
      JsonNode said = attributes(m);
      if ("turn_duration".equals(m.subtype()) && said.path("duration_ms").isNumber()) {
        turns++;
        turnMillis += said.path("duration_ms").asLong();
        longestTurnMillis = Math.max(longestTurnMillis, said.path("duration_ms").asLong());
      }
      thinkingMillis += said.path("thinking_ms").asLong(0);
      hookErrors += said.path("hook_errors").asLong(0);
      interrupted += said.path("interrupted").asBoolean(false) ? 1 : 0;
      if (said.path("stop_reason").isTextual()) {
        // One API message is several records, each with the reason: it counts once.
        stops.put(m.messageId() != null ? m.messageId() : m.agentId() + "." + m.lineNo(), said.path("stop_reason").asText());
      }
      if (said.has("api_error") || said.has("api_error_status")) {
        String error = said.path("api_error").asText("error");
        apiErrors.merge(said.has("api_error_status") ? error + " " + said.path("api_error_status").asText() : error, 1L, Long::sum);
      }
      if (said.path("tool_denial").isTextual()) {
        denials.merge(said.path("tool_denial").asText(), 1L, Long::sum);
      }
      // Only a tool that writes a file reports its path this way; one that reads says it elsewhere.
      if (said.path("file_path").isTextual()) {
        files.add(said.path("file_path").asText());
      }
      named.forEach(
          (key, values) -> {
            if (said.path(key).isTextual()) {
              values.add(said.path(key).asText());
            }
          });
    }
    summary.put("records", messages.size());
    summary.put("prompts", prompts);
    summary.set("promptSources", JSON.valueToTree(promptSources));
    summary.put("subAgents", agents.size());
    summary.put("turns", turns);
    summary.put("turnDurationMs", turnMillis);
    summary.put("longestTurnMs", longestTurnMillis);
    summary.put("thinkingDurationMs", thinkingMillis);
    summary.put("compactions", compactions);
    summary.put("interrupted", interrupted);
    summary.put("hookErrors", hookErrors);
    summary.put("filesEdited", files.size());
    Map<String, Long> stopCounts = new java.util.TreeMap<>();
    stops.values().forEach(reason -> stopCounts.merge(reason, 1L, Long::sum));
    summary.set("stopReasons", JSON.valueToTree(stopCounts));
    summary.set("apiErrors", JSON.valueToTree(apiErrors));
    summary.set("toolDenials", JSON.valueToTree(denials));
    summary.set("permissionModes", JSON.valueToTree(named.get("permission_mode")));
    summary.set("skills", JSON.valueToTree(named.get("skill")));
    summary.set("plugins", JSON.valueToTree(named.get("plugin")));
    summary.set("mcpServers", JSON.valueToTree(mcpServers));
    summary.set("commands", JSON.valueToTree(named.get("command")));
    summary.set("pullRequests", JSON.valueToTree(pullRequests));
    ArrayNode toolRows = summary.putArray("tools");
    tools.forEach((tool, counts) -> toolRows.addObject().put("tool", tool).put("calls", counts[0]).put("errors", counts[1]));
    summary.set("usage", JSON.valueToTree(usage));

    return summary;
  }

  /** What a record's line says about itself, as an object; an empty one when it says nothing. */
  public static JsonNode attributes(MessageRecord m) {
    if (m.attributes() == null) {
      return JSON.createObjectNode();
    }
    try {
      return JSON.readTree(m.attributes());
    } catch (JsonProcessingException e) {
      return JSON.createObjectNode();
    }
  }

  private static void putText(ObjectNode node, String name, String value) {
    if (value != null) {
      node.put(name, value);
    }
  }

  private static void putNumber(ObjectNode node, String name, Long value) {
    if (value != null) {
      node.put(name, value);
    }
  }
}
