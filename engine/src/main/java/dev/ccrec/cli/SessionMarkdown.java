package dev.ccrec.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import dev.ccrec.transcript.TranscriptParser;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One session's conversation as a Markdown document: what was asked, what Claude answered, and —
 * folded away under each answer — the tools it called with what came back, and its thinking.
 *
 * <p>The main thread comes first, then each sub-agent under a heading of its own. What Claude Code
 * adds around the conversation (the system prompt, tool definitions, attachments, bookkeeping) is
 * left out: it is context, not the exchange. The text is what was recorded, already redacted.
 */
public final class SessionMarkdown {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  /** How much of a tool call's subject the folded line shows. */
  private static final int GIST_CHARS = 100;
  /** Claude Code's wrappers in a prompt, as the UI reads them. */
  private static final Pattern WRAPPED = Pattern.compile("<([a-zA-Z][\\w-]*)((?:\\s[^<>]*)?)>([\\s\\S]*?)</\\1>");
  private static final Set<String> SHELL =
      Set.of("bash-input", "bash-stdout", "bash-stderr", "local-command-stdout", "local-command-stderr");
  private static final Pattern BACKTICKS = Pattern.compile("`+");

  /**
   * @param thinking whether the thinking blocks are included
   * @param tools whether the tool calls and their results are included
   * @param maxToolChars how much of each tool input and result is kept; 0 for all of it
   * @param lang {@code en} or {@code ja}, for the labels
   */
  public record Options(boolean thinking, boolean tools, int maxToolChars, String lang, ZoneId zone) {
    public static final int DEFAULT_MAX_TOOL_CHARS = 20_000;

    public static Options defaults() {
      return new Options(true, true, DEFAULT_MAX_TOOL_CHARS, "en", ZoneId.systemDefault());
    }
  }

  private static final Map<String, Map<String, String>> LABELS =
      Map.of(
          "en",
          Map.ofEntries(
              Map.entry("session", "Session"),
              Map.entry("account", "Account"),
              Map.entry("project", "Project"),
              Map.entry("branch", "Branch"),
              Map.entry("period", "Period"),
              Map.entry("models", "Models"),
              Map.entry("version", "Claude Code"),
              Map.entry("cost", "Cost"),
              Map.entry("costNote", "as Claude Code last wrote it down"),
              Map.entry("zone", "Times in"),
              Map.entry("untitled", "Untitled session"),
              Map.entry("user", "User"),
              Map.entry("claude", "Claude"),
              Map.entry("thinking", "Thinking"),
              Map.entry("result", "Result"),
              Map.entry("error", "Result (error)"),
              Map.entry("noResult", "No result was recorded."),
              Map.entry("cut", "%,d more characters not shown"),
              Map.entry("subAgent", "Sub-agent"),
              Map.entry("compacted", "The context was compacted here."),
              Map.entry("summary", "Summary after compaction"),
              Map.entry("reminder", "System reminder"),
              Map.entry("command", "Command"),
              Map.entry("notice", "Notification"),
              Map.entry("interrupted", "Interrupted by the user."),
              Map.entry("pullRequest", "Pull request"),
              Map.entry("attachment", "[%s: %s, %,d KB, not included]"),
              Map.entry("exported", "Exported by ccrec on %s.")),
          "ja",
          Map.ofEntries(
              Map.entry("session", "セッション"),
              Map.entry("account", "アカウント"),
              Map.entry("project", "プロジェクト"),
              Map.entry("branch", "ブランチ"),
              Map.entry("period", "期間"),
              Map.entry("models", "モデル"),
              Map.entry("version", "Claude Code"),
              Map.entry("cost", "コスト"),
              Map.entry("costNote", "Claude Code が最後に記録した値"),
              Map.entry("zone", "時刻のタイムゾーン"),
              Map.entry("untitled", "無題のセッション"),
              Map.entry("user", "ユーザー"),
              Map.entry("claude", "Claude"),
              Map.entry("thinking", "思考"),
              Map.entry("result", "結果"),
              Map.entry("error", "結果（エラー）"),
              Map.entry("noResult", "結果は記録されていません。"),
              Map.entry("cut", "残り %,d 文字は省略しました"),
              Map.entry("subAgent", "サブエージェント"),
              Map.entry("compacted", "ここでコンテキストが圧縮されました。"),
              Map.entry("summary", "圧縮後の要約"),
              Map.entry("reminder", "システムリマインダー"),
              Map.entry("command", "コマンド"),
              Map.entry("notice", "通知"),
              Map.entry("interrupted", "ユーザーが中断しました。"),
              Map.entry("pullRequest", "プルリクエスト"),
              Map.entry("attachment", "[%s: %s、%,d KB、省略]"),
              Map.entry("exported", "ccrec が %s に書き出しました。")));

  private final Options options;
  private final Map<String, String> labels;
  private final DateTimeFormatter time;
  private final Function<String, Optional<String>> content;
  private final StringBuilder out = new StringBuilder();
  /** Who spoke last in the section being written, so that a new speaker gets a heading. */
  private String speaker;

  private SessionMarkdown(Options options, Function<String, Optional<String>> content) {
    this.options = options;
    this.labels = LABELS.getOrDefault(options.lang(), LABELS.get("en"));
    this.time = TIME.withZone(options.zone());
    this.content = content;
  }

  /**
   * @param messages the session's records in the store's order: the main thread, then each sub-agent
   * @param content the whole text of a content hash; a record whose text cannot be had shows its preview
   */
  public static String of(
      String sessionId,
      Optional<SessionRecord> session,
      List<MessageRecord> messages,
      Function<String, Optional<String>> content,
      Options options) {
    SessionMarkdown markdown = new SessionMarkdown(options, content);
    markdown.write(sessionId, session, messages);
    return markdown.out.toString();
  }

  /**
   * A file name for the export that sorts by date and says what the session was: {@code
   * 2026-10-21_fix-the-login-form_1a2b3c4d.md}. It keeps letters of any script; what a file system
   * would refuse is replaced.
   */
  public static String fileName(String sessionId, Optional<SessionRecord> session, ZoneId zone) {
    StringBuilder name = new StringBuilder();
    session.ifPresent(s -> name.append(DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone).format(Instant.ofEpochMilli(s.startedAt()))).append('_'));
    String title = session.map(SessionRecord::title).orElse(null);
    if (title != null) {
      String slug = title.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replaceAll("\\s+", "-");
      if (slug.codePointCount(0, slug.length()) > 60) {
        slug = slug.substring(0, slug.offsetByCodePoints(0, 60));
      }
      if (!slug.isEmpty()) {
        name.append(slug).append('_');
      }
    }
    String id = sessionId.replaceAll("[^A-Za-z0-9._-]", "");
    name.append(id.length() > 8 ? id.substring(0, 8) : id);
    return name + ".md";
  }

  private String label(String key) {
    return labels.get(key);
  }

  // ---- the document ---------------------------------------------------------------------------

  private void write(String sessionId, Optional<SessionRecord> session, List<MessageRecord> messages) {
    String title = session.map(SessionRecord::title).orElse(null);
    out.append("# ").append(oneLine(title != null ? title : label("untitled"))).append("\n\n");
    header(sessionId, session, messages);

    Map<String, MessageRecord> results = new HashMap<>();
    Map<String, String> agentFor = new HashMap<>();
    Map<String, String> callOf = new HashMap<>();
    Map<String, List<MessageRecord>> byAgent = new LinkedHashMap<>();
    for (MessageRecord m : messages) {
      byAgent.computeIfAbsent(m.agentId(), a -> new ArrayList<>()).add(m);
      if (m.kind().equals(TranscriptParser.TOOL_RESULT) && m.toolUseId() != null) {
        results.putIfAbsent(m.toolUseId(), m);
        String agent = SessionSummary.attributes(m).path("agent_id").asText("");
        if (!agent.isEmpty()) {
          agentFor.put(m.toolUseId(), agent);
        }
      }
    }
    // What each sub-agent was asked to do, from the call that started it.
    for (MessageRecord m : messages) {
      if (m.kind().equals(TranscriptParser.TOOL_USE) && agentFor.containsKey(m.toolUseId())) {
        callOf.put(agentFor.get(m.toolUseId()), gist(m.toolName(), text(m)));
      }
    }

    for (Map.Entry<String, List<MessageRecord>> agent : byAgent.entrySet()) {
      if (!agent.getKey().equals(MessageRecord.MAIN_AGENT)) {
        out.append("\n---\n\n## ").append(label("subAgent")).append(" `").append(agent.getKey()).append('`');
        String asked = callOf.get(agent.getKey());
        if (asked != null && !asked.isEmpty()) {
          out.append(": ").append(oneLine(asked));
        }
        out.append("\n\n");
      }
      speaker = null;
      Set<String> shown = new java.util.HashSet<>();
      for (MessageRecord m : agent.getValue()) {
        record(m, results, shown);
      }
    }
    out.append("\n---\n\n*").append(String.format(label("exported"), time.format(Instant.now()))).append("*\n");
  }

  private void header(String sessionId, Optional<SessionRecord> session, List<MessageRecord> messages) {
    Long first = null;
    Long last = null;
    Set<String> models = new TreeSet<>();
    for (MessageRecord m : messages) {
      if (m.ts() != null) {
        first = first == null ? m.ts() : Math.min(first, m.ts());
        last = last == null ? m.ts() : Math.max(last, m.ts());
      }
      if (m.model() != null && !m.model().startsWith("<") && m.outputTokens() != null && m.outputTokens() > 0) {
        models.add(m.model());
      }
    }
    Long started = session.map(SessionRecord::startedAt).orElse(first);
    Long ended = session.map(SessionRecord::endedAt).orElse(last);
    List<String[]> facts = new ArrayList<>();
    facts.add(new String[] {label("session"), "`" + sessionId + "`"});
    session.ifPresent(
        s -> {
          facts.add(new String[] {label("account"), s.accountId()});
          if (s.projectPath() != null) facts.add(new String[] {label("project"), "`" + s.projectPath() + "`"});
          if (s.gitBranch() != null) facts.add(new String[] {label("branch"), "`" + s.gitBranch() + "`"});
        });
    if (started != null) {
      String period = time.format(Instant.ofEpochMilli(started));
      if (ended != null && ended > started) {
        period += " – " + time.format(Instant.ofEpochMilli(ended));
      }
      facts.add(new String[] {label("period"), period});
    }
    if (!models.isEmpty()) {
      facts.add(new String[] {label("models"), String.join(", ", models)});
    }
    session.ifPresent(
        s -> {
          if (s.ccVersion() != null) facts.add(new String[] {label("version"), s.ccVersion()});
          if (s.costUsd() != null) {
            facts.add(new String[] {label("cost"), String.format("$%.2f (%s)", s.costUsd(), label("costNote"))});
          }
        });
    facts.add(new String[] {label("zone"), options.zone().getId()});
    for (String[] fact : facts) {
      out.append("- **").append(fact[0]).append("**: ").append(oneLine(fact[1])).append('\n');
    }
    out.append('\n');
  }

  // ---- one record -----------------------------------------------------------------------------

  private void record(MessageRecord m, Map<String, MessageRecord> results, Set<String> shown) {
    JsonNode attributes = SessionSummary.attributes(m);
    switch (m.kind()) {
      case TranscriptParser.USER_PROMPT -> {
        if (attributes.path("compact_summary").asBoolean(false)) {
          details(label("summary"), text(m));
          return;
        }
        speak("user", m);
        prompt(text(m));
        if (attributes.path("interrupted").asBoolean(false)) {
          out.append("*").append(label("interrupted")).append("*\n\n");
        }
      }
      case TranscriptParser.ASSISTANT_TEXT -> {
        speak("claude", m);
        out.append(text(m).strip()).append("\n\n");
      }
      case TranscriptParser.THINKING -> {
        if (options.thinking()) {
          speak("claude", m);
          details(label("thinking"), text(m));
        }
      }
      case TranscriptParser.TOOL_USE -> {
        if (options.tools()) {
          speak("claude", m);
          toolCall(m, m.toolUseId() == null ? null : results.get(m.toolUseId()));
          if (m.toolUseId() != null) {
            shown.add(m.toolUseId());
          }
        }
      }
      case TranscriptParser.TOOL_RESULT -> {
        // Shown under its call; one whose call was not recorded stands on its own.
        if (options.tools() && (m.toolUseId() == null || !shown.contains(m.toolUseId()))) {
          out.append("<details>\n<summary>").append(html(label("result"))).append("</summary>\n\n");
          result(m);
          out.append("</details>\n\n");
        }
      }
      case TranscriptParser.PR_LINK -> {
        String url = attributes.path("pr_url").asText(text(m));
        out.append("**").append(label("pullRequest")).append("**: ").append(oneLine(url)).append("\n\n");
      }
      case TranscriptParser.SYSTEM -> {
        if ("compact_boundary".equals(m.subtype())) {
          out.append("*").append(label("compacted")).append("*\n\n");
          speaker = null;
        }
      }
      default -> {
        // Context and bookkeeping: not part of the exchange.
      }
    }
  }

  /** A heading when the speaker changes: the user, or Claude for everything it did until then. */
  private void speak(String who, MessageRecord m) {
    if (who.equals(speaker)) {
      return;
    }
    speaker = who;
    out.append("## ").append(label(who));
    if (m.ts() != null) {
      out.append(" · ").append(time.format(Instant.ofEpochMilli(m.ts())));
    }
    out.append("\n\n");
  }

  /** A prompt with what Claude Code wrapped into it laid out: a command, a notification, a reminder. */
  private void prompt(String text) {
    Matcher matcher = WRAPPED.matcher(text);
    List<String[]> parts = new ArrayList<>();
    int last = 0;
    while (matcher.find()) {
      String before = text.substring(last, matcher.start());
      if (!before.isBlank()) parts.add(new String[] {null, null, before.strip()});
      parts.add(new String[] {matcher.group(1).toLowerCase(java.util.Locale.ROOT), matcher.group(2).strip(), matcher.group(3)});
      last = matcher.end();
    }
    String rest = text.substring(last);
    if (!rest.isBlank() || parts.isEmpty()) parts.add(new String[] {null, null, rest.strip()});

    String args = parts.stream().filter(p -> "command-args".equals(p[0])).map(p -> p[2].strip()).findFirst().orElse("");
    boolean command = parts.stream().anyMatch(p -> "command-name".equals(p[0]));
    for (String[] part : parts) {
      String tag = part[0];
      if (tag == null) {
        out.append(part[2]).append("\n\n");
      } else if (tag.equals("command-name")) {
        String line = part[2].strip() + (args.isEmpty() ? "" : " " + args);
        out.append("**").append(label("command")).append("**: ").append(inlineCode(line)).append("\n\n");
      } else if (command && (tag.equals("command-message") || tag.equals("command-args"))) {
        // Said by the command line already.
      } else if (tag.equals("system-reminder")) {
        details(label("reminder"), part[2]);
      } else if (tag.equals("task-notification")) {
        out.append("> **").append(label("notice")).append("**\n>\n");
        for (String line : part[2].strip().split("\n", -1)) {
          out.append(line.isEmpty() ? ">" : "> " + line).append('\n');
        }
        out.append('\n');
      } else if (SHELL.contains(tag)) {
        String body = part[2].replaceAll("^\\n+|\\n+$", "");
        if (!body.isBlank()) {
          out.append('*').append(tag).append("*\n\n");
          fence(body, tag.equals("bash-input") ? "bash" : "");
        }
      } else {
        details(part[1].isEmpty() ? tag : tag + " " + part[1], part[2]);
      }
    }
  }

  /** A tool call folded into one line that says what it did, with its input and its result inside. */
  private void toolCall(MessageRecord call, MessageRecord result) {
    String input = text(call);
    String tool = call.toolName() != null ? call.toolName() : "tool";
    String gist = gist(tool, input);
    String status = result == null ? "" : "error".equals(result.subtype()) ? " ✗" : "";
    out.append("<details>\n<summary>")
        .append(html(tool))
        .append(gist.isEmpty() ? "" : ": <code>" + html(oneLine(gist)) + "</code>")
        .append(status)
        .append("</summary>\n\n");
    toolInput(tool, input);
    if (result == null) {
      out.append('*').append(label("noResult")).append("*\n\n");
    } else {
      result(result);
    }
    out.append("</details>\n\n");
  }

  private void result(MessageRecord result) {
    out.append("**").append(label("error".equals(result.subtype()) ? "error" : "result")).append("**\n\n");
    fence(withoutAttachments(text(result)), "");
  }

  /**
   * A result with what it carried for the model to look at — an image, a PDF, as base64 on a line
   * of its own — said in a few words: in a document to read, it is only noise.
   */
  private String withoutAttachments(String text) {
    if (!text.contains("\"base64\"")) {
      return text;
    }
    StringBuilder kept = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      if (kept.length() > 0) {
        kept.append('\n');
      }
      JsonNode item = null;
      if (line.startsWith("{") && line.contains("\"base64\"")) {
        try {
          item = JSON.readTree(line);
        } catch (JsonProcessingException e) {
          // Not an item: kept as it is.
        }
      }
      JsonNode source = item == null ? null : item.path("source");
      if (source != null && "base64".equals(source.path("type").asText())) {
        long bytes = source.path("data").asText("").length() * 3L / 4;
        kept.append(String.format(label("attachment"), item.path("type").asText("file"), source.path("media_type").asText("?"), Math.max(1, bytes / 1024)));
      } else {
        kept.append(line);
      }
    }
    return kept.toString();
  }

  /** A tool's input the way the tool is used: a command to run, a file and what changes in it. */
  private void toolInput(String tool, String input) {
    JsonNode node;
    try {
      node = JSON.readTree(input);
    } catch (JsonProcessingException e) {
      node = null;
    }
    if (!(node instanceof ObjectNode)) {
      fence(input, node == null ? "" : "json");
      return;
    }
    ObjectNode rest = ((ObjectNode) node).deepCopy();
    if (tool.equals("Bash") && rest.path("command").isTextual()) {
      rest.remove("description");
      fence(rest.remove("command").asText(), "bash");
    } else if (Set.of("Edit", "Write", "Read", "NotebookEdit").contains(tool) && rest.path("file_path").isTextual()) {
      out.append(inlineCode(rest.remove("file_path").asText())).append("\n\n");
      if (rest.path("old_string").isTextual() && rest.path("new_string").isTextual()) {
        fence(diff(rest.remove("old_string").asText(), rest.remove("new_string").asText()), "diff");
      }
      if (rest.path("content").isTextual()) {
        fence(rest.remove("content").asText(), "");
      }
    }
    if (!rest.isEmpty()) {
      fence(rest.toPrettyString(), "json");
    }
  }

  /** What a call was about, in a line: its command, file, pattern, address or task. */
  private static String gist(String tool, String input) {
    JsonNode node;
    try {
      node = JSON.readTree(input);
    } catch (JsonProcessingException e) {
      return "";
    }
    for (String field : List.of("description", "command", "file_path", "notebook_path", "pattern", "url", "query", "skill", "prompt")) {
      if (tool.equals("Bash") && field.equals("description")) {
        // The command itself says more than its description to whoever reads the export.
        continue;
      }
      JsonNode value = node.path(field);
      if (value.isTextual() && !value.asText().isBlank()) {
        String line = oneLine(value.asText());
        return line.codePointCount(0, line.length()) <= GIST_CHARS
            ? line
            : line.substring(0, line.offsetByCodePoints(0, GIST_CHARS)) + "…";
      }
    }
    return "";
  }

  private static String diff(String removed, String added) {
    StringBuilder diff = new StringBuilder();
    for (String line : removed.split("\n", -1)) diff.append("- ").append(line).append('\n');
    for (String line : added.split("\n", -1)) diff.append("+ ").append(line).append('\n');
    return diff.toString();
  }

  // ---- Markdown -------------------------------------------------------------------------------

  private void details(String summary, String body) {
    out.append("<details>\n<summary>").append(html(summary)).append("</summary>\n\n")
        .append(body.strip()).append("\n\n</details>\n\n");
  }

  /** A code block whose fence is longer than any run of backticks in the text, cut to the limit. */
  private void fence(String text, String language) {
    String body = text.replaceAll("\\s+$", "");
    int cut = 0;
    int max = options.maxToolChars();
    if (max > 0 && body.length() > max) {
      int end = Character.isHighSurrogate(body.charAt(max - 1)) ? max - 1 : max;
      cut = body.length() - end;
      body = body.substring(0, end);
    }
    int longest = 0;
    Matcher matcher = BACKTICKS.matcher(body);
    while (matcher.find()) {
      longest = Math.max(longest, matcher.group().length());
    }
    String marks = "`".repeat(Math.max(3, longest + 1));
    out.append(marks).append(language).append('\n').append(body).append('\n').append(marks).append("\n\n");
    if (cut > 0) {
      out.append("*").append(String.format(label("cut"), cut)).append("*\n\n");
    }
  }

  private static String inlineCode(String text) {
    int longest = 0;
    Matcher matcher = BACKTICKS.matcher(text);
    while (matcher.find()) {
      longest = Math.max(longest, matcher.group().length());
    }
    String marks = "`".repeat(longest + 1);
    String pad = text.startsWith("`") || text.endsWith("`") ? " " : "";
    return marks + pad + oneLine(text) + pad + marks;
  }

  private static String oneLine(String text) {
    return text.replaceAll("\\s*\\R\\s*", " ").strip();
  }

  /** Inside {@code <summary>}, recorded text is text, not markup. */
  private static String html(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private String text(MessageRecord m) {
    String preview = m.preview() == null ? "" : m.preview();
    if (m.contentHash() == null) {
      return preview;
    }
    return content.apply(m.contentHash()).orElse(preview);
  }
}
