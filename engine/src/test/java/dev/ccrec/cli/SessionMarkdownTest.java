package dev.ccrec.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A session's records written out as Markdown. */
class SessionMarkdownTest {

  private static final ZoneId UTC = ZoneId.of("UTC");
  private static final long AT = Instant.parse("2026-10-21T01:00:00Z").toEpochMilli();
  private static final SessionRecord SESSION =
      new SessionRecord(
          "acct-1", AT, "1a2b3c4d-0000-0000-0000-000000000000", "org-1", "host-1", "/work/app", "main",
          "2.1.0", "claude-opus-5-5", "Fix the login form", AT + 600_000, "cli", 1.5, null, null, null, null);

  private static MessageRecord record(
      String agent, int line, String kind, String subtype, String toolName, String toolUseId, String text, String attributes) {
    return new MessageRecord(
        SESSION.sessionId(), agent, line, 0, "acct-1", kind, subtype, AT + line * 1000L, null, null, null,
        kind.equals("assistant_text") ? "claude-opus-5-5" : null, toolName, toolUseId, text == null ? null : "h" + line,
        text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length, text, null,
        kind.equals("assistant_text") ? 5L : null, null, null, null, null, null, null, null, attributes);
  }

  private static final List<MessageRecord> RECORDS =
      List.of(
          record("main", 1, "system_prompt", null, null, null, "You are Claude Code.", null),
          record("main", 2, "user_prompt", null, null, null, "Why does **login** fail?", "{\"permission_mode\":\"default\"}"),
          record("main", 3, "thinking", null, null, null, "Look at the form first.", null),
          record("main", 4, "tool_use", null, "Bash", "t1", "{\"command\":\"grep -n login src/form.js\",\"description\":\"Find it\"}", null),
          record("main", 5, "tool_result", null, null, "t1", "12: ```login``` here", null),
          record("main", 6, "tool_use", null, "Agent", "t2", "{\"description\":\"Check the tests\",\"prompt\":\"Run them\"}", null),
          record("main", 7, "tool_result", "error", null, "t2", "it failed", "{\"agent_id\":\"a1\"}"),
          record("main", 8, "assistant_text", null, null, null, "The form posts to the <wrong> URL.", null),
          record("main", 9, "user_prompt", null, null, null,
              "<command-name>/review</command-name><command-message>review</command-message><command-args>src</command-args>", null),
          record("main", 10, "pr_link", null, null, null, "https://github.com/o/r/pull/7", "{\"pr_url\":\"https://github.com/o/r/pull/7\"}"),
          record("a1", 1, "user_prompt", null, null, null, "Run them", null),
          record("a1", 2, "assistant_text", null, null, null, "All green.", null));

  private static String export(SessionMarkdown.Options options) {
    return SessionMarkdown.of(SESSION.sessionId(), Optional.of(SESSION), RECORDS, hash -> Optional.empty(), options);
  }

  @Test
  void theConversationReadsInOrderWithEachToolCallFoldedUnderTheAnswer() {
    String markdown = export(new SessionMarkdown.Options(true, true, 0, "en", UTC));

    assertTrue(markdown.startsWith("# Fix the login form\n\n"), markdown);
    assertTrue(markdown.contains("- **Project**: `/work/app`"), markdown);
    assertTrue(markdown.contains("- **Period**: 2026-10-21 01:00 – 2026-10-21 01:10"), markdown);
    assertTrue(markdown.contains("$1.50"), markdown);
    assertFalse(markdown.contains("You are Claude Code."), "the system prompt is context, not the exchange");

    assertTrue(markdown.contains("## User · 2026-10-21 01:00\n\nWhy does **login** fail?\n\n"), markdown);
    // One heading for everything Claude did until the user spoke again.
    assertEquals(2, markdown.split("## Claude · ", -1).length - 1, markdown);
    assertTrue(markdown.contains("<summary>Thinking</summary>"), markdown);
    assertTrue(markdown.contains("<summary>Bash: <code>grep -n login src/form.js</code></summary>"), markdown);
    assertTrue(markdown.contains("```bash\ngrep -n login src/form.js\n```"), markdown);
    // A fence longer than any run of backticks in what it holds.
    assertTrue(markdown.contains("````\n12: ```login``` here\n````"), markdown);
    assertTrue(markdown.contains("<summary>Agent: <code>Check the tests</code> ✗</summary>"), markdown);
    assertTrue(markdown.contains("**Result (error)**"), markdown);
    assertTrue(markdown.contains("The form posts to the <wrong> URL."), markdown);
    assertTrue(markdown.contains("**Command**: `/review src`"), markdown);
    assertTrue(markdown.contains("**Pull request**: https://github.com/o/r/pull/7"), markdown);

    // The sub-agent after the main thread, under what it was asked to do.
    int subAgent = markdown.indexOf("## Sub-agent `a1`: Check the tests");
    assertTrue(subAgent > markdown.indexOf("/review src"), markdown);
    assertTrue(markdown.indexOf("All green.") > subAgent, markdown);
  }

  @Test
  void toolsAndThinkingCanBeLeftOutAndLongOutputIsCut() {
    String bare = export(new SessionMarkdown.Options(false, false, 0, "en", UTC));
    assertFalse(bare.contains("<summary>Thinking"), bare);
    assertFalse(bare.contains("grep -n"), bare);
    assertTrue(bare.contains("The form posts"), bare);

    String cut = export(new SessionMarkdown.Options(true, true, 8, "ja", UTC));
    assertTrue(cut.contains("## ユーザー · "), cut);
    assertTrue(cut.contains("*残り 12 文字は省略しました*"), cut);
  }

  @Test
  void anImageAToolReturnedIsNamedRatherThanPrintedAsBase64() {
    String image = "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + "A".repeat(4096) + "\"}}";
    List<MessageRecord> records =
        List.of(
            record("main", 1, "tool_use", null, "Read", "t1", "{\"file_path\":\"/work/a.png\"}", null),
            record("main", 2, "tool_result", null, null, "t1", "Read it.\n" + image, null));
    String markdown =
        SessionMarkdown.of(SESSION.sessionId(), Optional.of(SESSION), records, hash -> Optional.empty(), new SessionMarkdown.Options(true, true, 0, "en", UTC));
    assertFalse(markdown.contains("AAAA"), markdown);
    assertTrue(markdown.contains("Read it.\n[image: image/png, 3 KB, not included]"), markdown);
  }

  @Test
  void theFileNameSortsByDateAndSaysWhatTheSessionWas() {
    assertEquals("2026-10-21_Fix-the-login-form_1a2b3c4d.md", SessionMarkdown.fileName(SESSION.sessionId(), Optional.of(SESSION), UTC));
    SessionRecord japanese =
        new SessionRecord("acct-1", AT, "s/1", null, null, null, null, null, null, "ログイン: 修正?", null, null, null, null, null, null, null);
    assertEquals("2026-10-21_ログイン-修正_s1.md", SessionMarkdown.fileName("s/1", Optional.of(japanese), UTC));
    assertEquals("abc.md", SessionMarkdown.fileName("abc", Optional.empty(), UTC));
  }

  @Test
  void theWholeTextIsUsedWhereTheStoreHasIt() {
    String markdown =
        SessionMarkdown.of(
            SESSION.sessionId(), Optional.of(SESSION), RECORDS, hash -> Optional.ofNullable(Map.of("h8", "The whole answer.").get(hash)), new SessionMarkdown.Options(true, true, 0, "en", UTC));
    assertTrue(markdown.contains("The whole answer."), markdown);
  }
}
