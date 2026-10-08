package dev.ccrec.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ccrec.model.SessionRecord;
import dev.ccrec.store.RecordStore;
import dev.ccrec.store.ScalarDbRecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The engine's commands, run the way the launcher runs them, on a SQLite file. */
class MainTest {

  private static final String LINE =
      "{\"type\":\"user\",\"uuid\":\"u1\",\"timestamp\":\"2026-10-07T01:00:01.000Z\",\"cwd\":\"%s\","
          + "\"message\":{\"role\":\"user\",\"content\":\"hello\"}}\n";

  private static Path home(Path dir) throws IOException {
    Path home = Files.createDirectories(dir.resolve("home"));
    Files.createDirectories(home.resolve("spool"));
    Files.writeString(
        home.resolve("database.properties"),
        "scalar.db.storage=jdbc\n"
            + "scalar.db.contact_points=jdbc:sqlite:" + home.resolve("ccrec.sqlite3") + "?busy_timeout=10000\n"
            + "scalar.db.username=\nscalar.db.password=\n"
            + "scalar.db.transaction_manager=consensus-commit\n");
    return home;
  }

  private static List<SessionRecord> sessions(Path home) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(home.resolve("database.properties"))) {
      properties.load(in);
    }
    try (RecordStore store = new ScalarDbRecordStore(properties)) {
      return store.sessions("acct-1", 10);
    }
  }

  /** A spool entry as the hook writes it; {@code requestedAt} is null when no ingest was asked for. */
  private static void queue(Path home, String sessionId, Path transcript, boolean ended, String requestedAt)
      throws IOException {
    Files.writeString(
        home.resolve("spool").resolve(sessionId + ".json"),
        "{\"session_id\":\"" + sessionId + "\",\"transcript_path\":\"" + transcript + "\",\"host_id\":\"host-1\","
            + "\"account\":{\"account_id\":\"acct-1\"},\"ended\":" + ended + ",\"ingest_requested_at\":"
            + (requestedAt == null ? "null" : "\"" + requestedAt + "\"") + "}");
  }

  private static Path transcript(Path dir, String sessionId) throws IOException {
    Path transcript = dir.resolve(sessionId + ".jsonl");
    Files.writeString(transcript, String.format(LINE, "/work/app"));
    return transcript;
  }

  @Test
  void anEntryThatCannotBeIngestedDoesNotHoldBackTheOthers(@TempDir Path dir) throws IOException {
    Path home = home(dir);
    Path spool = home.resolve("spool");
    Path transcript = dir.resolve("good.jsonl");
    Files.writeString(transcript, String.format(LINE, "/work/app"));
    // Whatever order the directory lists them in, a bad entry comes before the good one.
    Files.writeString(spool.resolve("000.json"), "{broken");
    Files.writeString(spool.resolve("zzz.json"), "{\"session_id\":\"zzz\",\"transcript_path\":\"" + transcript + "\"}");
    queue(home, "good", transcript, true, null);

    assertEquals(1, Main.run(new String[] {"ingest", "--home", home.toString()}));

    assertEquals(List.of("good"), sessions(home).stream().map(SessionRecord::sessionId).toList());
    assertFalse(Files.exists(spool.resolve("good.json")), "an ended session leaves the queue");
    assertTrue(Files.exists(spool.resolve("000.json.bad")));
    assertTrue(Files.exists(spool.resolve("zzz.json.bad")));
    assertEquals(0, Main.run(new String[] {"ingest", "--home", home.toString()}), "nothing is left to fail on");
  }

  @Test
  void aHookRunAlsoRecordsTheSessionsAnEarlierRunLeftWaiting(@TempDir Path dir) throws IOException {
    Path home = home(dir);
    Path spool = home.resolve("spool");
    String now = Instant.now().toString();
    queue(home, "current", transcript(dir, "current"), false, now);
    // Its own ingest never got to run: Java was missing, or the lock wait timed out.
    queue(home, "missed", transcript(dir, "missed"), true, now);
    // Only started; nothing has asked for it to be recorded.
    queue(home, "idle", transcript(dir, "idle"), false, null);
    // Recorded since its last request, and untouched for longer than the queue keeps entries.
    queue(home, "stale", transcript(dir, "stale"), false, "2026-01-01T00:00:00.000Z");
    Files.createFile(spool.resolve("stale.done"));
    Files.setLastModifiedTime(spool.resolve("stale.json"), FileTime.from(Instant.now().minus(Duration.ofDays(31))));

    assertEquals(0, Main.run(new String[] {"ingest", "--session", "current", "--home", home.toString()}));

    assertEquals(
        List.of("current", "missed"), sessions(home).stream().map(SessionRecord::sessionId).sorted().toList());
    assertTrue(Files.exists(spool.resolve("current.done")), "a session still open is marked as recorded");
    assertFalse(Files.exists(spool.resolve("missed.json")), "an ended session leaves the queue");
    assertTrue(Files.exists(spool.resolve("idle.json")));
    assertFalse(Files.exists(spool.resolve("stale.json")), "an entry long recorded and idle is dropped");
    assertFalse(Files.exists(spool.resolve("stale.done")));

    // Nothing was requested since: another session's run leaves this one alone.
    FileTime marked = Files.getLastModifiedTime(spool.resolve("current.done"));
    assertEquals(0, Main.run(new String[] {"ingest", "--session", "other", "--home", home.toString()}));
    assertEquals(marked, Files.getLastModifiedTime(spool.resolve("current.done")));

    // The hook asks again; whichever session's run comes next records it.
    queue(home, "current", transcript(dir, "current"), false, Instant.now().plusSeconds(1).toString());
    assertEquals(0, Main.run(new String[] {"ingest", "--session", "other", "--home", home.toString()}));
    assertTrue(Files.getLastModifiedTime(spool.resolve("current.done")).compareTo(marked) > 0);
  }

  @Test
  void aDeletedSessionIsGoneAndTheHooksLeaveItAlone(@TempDir Path dir) throws IOException {
    Path home = home(dir);
    Path spool = home.resolve("spool");
    queue(home, "gone", transcript(dir, "gone"), false, Instant.now().toString());
    queue(home, "kept", transcript(dir, "kept"), false, Instant.now().toString());
    assertEquals(0, Main.run(new String[] {"ingest", "--home", home.toString()}));

    String[] delete = {"delete", "gone", "--account", "acct-1", "--home", home.toString()};
    assertEquals(0, Main.run(delete));

    assertEquals(List.of("kept"), sessions(home).stream().map(SessionRecord::sessionId).toList());
    assertFalse(Files.exists(spool.resolve("gone.json")));
    assertFalse(Files.exists(spool.resolve("gone.done")));
    assertTrue(Files.exists(spool.resolve("gone.ignored")), "the mark the hook checks before it queues");
    assertTrue(Files.exists(spool.resolve("kept.json")));
    assertEquals(1, Main.run(delete), "there is nothing left to delete");
  }

  @Test
  void aSessionWhoseTranscriptIsNotThereDoesNotPassForRecorded(@TempDir Path dir) throws IOException {
    Path home = home(dir);
    Path spool = home.resolve("spool");
    String now = Instant.now().toString();
    queue(home, "moved", dir.resolve("moved.jsonl"), true, now);
    // Only started: its transcript may simply not have been written yet.
    queue(home, "fresh", dir.resolve("fresh.jsonl"), false, null);
    queue(home, "lost", dir.resolve("lost.jsonl"), false, now);
    Files.setLastModifiedTime(spool.resolve("lost.json"), FileTime.from(Instant.now().minus(Duration.ofDays(31))));

    assertEquals(1, Main.run(new String[] {"ingest", "--home", home.toString()}));

    assertTrue(Files.exists(spool.resolve("moved.json")), "it stays waiting");
    assertFalse(Files.exists(spool.resolve("moved.done")));
    assertTrue(Files.exists(spool.resolve("fresh.json")));
    assertFalse(Files.exists(spool.resolve("lost.json")), "but not for ever");

    // The transcript turns up after all.
    transcript(dir, "moved");
    assertEquals(0, Main.run(new String[] {"ingest", "--home", home.toString()}));
    assertEquals(List.of("moved"), sessions(home).stream().map(SessionRecord::sessionId).toList());
  }

  @Test
  void theMarkOfARecordedSessionSaysWhenItWasRecorded(@TempDir Path dir) throws IOException {
    Path home = home(dir);
    Path mark = home.resolve("spool").resolve("open.done");
    Instant before = Instant.now();
    queue(home, "open", transcript(dir, "open"), false, before.toString());
    assertEquals(0, Main.run(new String[] {"ingest", "--session", "other", "--home", home.toString()}));

    assertFalse(Instant.parse(Files.readString(mark)).isBefore(before));
    // Whatever the file system does to the mark's modification time, the session is not waiting again.
    Files.setLastModifiedTime(mark, FileTime.from(before.minus(Duration.ofHours(1))));
    Files.delete(dir.resolve("open.jsonl"));
    assertEquals(0, Main.run(new String[] {"ingest", "--session", "other", "--home", home.toString()}));
  }

  /** Runs a command and returns what it printed. */
  private static String printed(int expectedExit, String... args) throws IOException {
    PrintStream console = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      assertEquals(expectedExit, Main.run(args));
    } finally {
      System.setOut(console);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  private static String assistant(String messageId, String model, String stop, String block, String more) {
    return "{\"type\":\"assistant\",\"timestamp\":\"2026-10-07T01:00:05.000Z\"" + more + ",\"message\":{\"id\":\"" + messageId
        + "\",\"model\":\"" + model + "\",\"stop_reason\":\"" + stop + "\",\"usage\":{\"input_tokens\":1000,"
        + "\"output_tokens\":200,\"output_tokens_details\":{\"thinking_tokens\":50},\"cache_read_input_tokens\":30,"
        + "\"cache_creation_input_tokens\":40,\"server_tool_use\":{\"web_search_requests\":1,\"web_fetch_requests\":2}},"
        + "\"content\":[" + block + "]}}";
  }

  /** A session with two models, a failed tool call, a turn, a pull request linked twice and a cost. */
  private static Path busyTranscript(Path dir, String sessionId) throws IOException {
    String result =
        "{\"type\":\"user\",\"permissionMode\":\"acceptEdits\",\"message\":{\"role\":\"user\",\"content\":["
            + "{\"type\":\"tool_result\",\"tool_use_id\":\"%s\",\"is_error\":%s,\"content\":\"out\"}]}}";
    String pr = "{\"type\":\"pr-link\",\"prNumber\":7,\"prUrl\":\"https://github.com/org/repo/pull/7\"}";
    Path transcript = dir.resolve(sessionId + ".jsonl");
    Files.writeString(
        transcript,
        String.join(
                "\n",
                String.format(LINE, "/work/app").strip().replace("\"type\":\"user\"", "\"type\":\"user\",\"promptSource\":\"typed\""),
                "{\"type\":\"user\",\"promptSource\":\"system\",\"origin\":{\"kind\":\"task-notification\"},"
                    + "\"message\":{\"role\":\"user\",\"content\":\"a background task finished\"}}",
                assistant("msg_1", "claude-opus-5-5", "tool_use", "{\"type\":\"thinking\",\"thinking\":\"\"}", ",\"thinkingDurationMs\":3000"),
                assistant("msg_1", "claude-opus-5-5", "tool_use", "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{}}", ""),
                String.format(result, "t1", "true"),
                assistant("msg_2", "claude-opus-5-5", "tool_use", "{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"Bash\",\"input\":{}}", ""),
                String.format(result, "t2", "false"),
                // Two calls to one MCP server; Claude Code names the server on one line only, and differently.
                assistant("msg_4", "claude-opus-5-5", "tool_use", "{\"type\":\"tool_use\",\"id\":\"t3\",\"name\":\"mcp__team_wiki__search\",\"input\":{}}", ",\"attributionMcpServer\":\"Team Wiki\""),
                assistant("msg_5", "claude-opus-5-5", "tool_use", "{\"type\":\"tool_use\",\"id\":\"t4\",\"name\":\"mcp__team_wiki__read_page\",\"input\":{}}", ""),
                assistant("msg_3", "claude-haiku-4-5", "end_turn", "{\"type\":\"text\",\"text\":\"done\"}", ""),
                "{\"type\":\"system\",\"subtype\":\"turn_duration\",\"durationMs\":65000}",
                pr,
                pr,
                "{\"type\":\"cost-state\",\"totalCostUSD\":1.5,\"totalAPIDuration\":61000,\"totalLinesAdded\":12}")
            + "\n");
    return transcript;
  }

  @Test
  void usageIsReportedPerSessionAndSummedPerAccount(@TempDir Path dir) throws IOException {
    Path home = home(dir);
    queue(home, "busy", busyTranscript(dir, "busy"), true, null);
    queue(home, "busy-too", busyTranscript(dir, "busy-too"), true, null);
    assertEquals(0, Main.run(new String[] {"ingest", "--home", home.toString()}));

    String one = printed(0, "usage", "busy", "--home", home.toString());
    assertTrue(one.lines().anyMatch(l -> l.matches("busy +claude-opus-5-5 +4 +4,000 +800 +200 +120 +160 +12")), one);
    assertTrue(one.lines().anyMatch(l -> l.matches("total +1 session +5 +5,000 +1,000 +250 +150 +200 +15")), one);

    String all = printed(0, "usage", "--account", "acct-1", "--home", home.toString());
    assertTrue(all.lines().anyMatch(l -> l.matches("acct-1 +claude-haiku-4-5 +2 +2,000 +400 +100 +60 +80 +6")), all);
    assertTrue(all.lines().anyMatch(l -> l.matches("total +2 sessions +10 +10,000 .*")), all);

    JsonNode json = new ObjectMapper().readTree(printed(0, "usage", "busy", "--json", "--home", home.toString()));
    assertEquals(2, json.size());
    assertEquals(200, json.get(1).path("thinkingTokens").asInt());

    String wrong = "";
    try {
      Main.run(new String[] {"usage", "--limit", "many", "--account", "acct-1", "--home", home.toString()});
    } catch (RuntimeException e) {
      wrong = e.getMessage();
    }
    assertEquals("--limit needs a number, not \"many\"", wrong);
  }

  @Test
  void aSummaryAddsUpWhatTheSessionsRecordsSay(@TempDir Path dir) throws IOException {
    Path home = home(dir);
    queue(home, "busy", busyTranscript(dir, "busy"), true, null);
    assertEquals(0, Main.run(new String[] {"ingest", "--home", home.toString()}));

    JsonNode summary =
        new ObjectMapper().readTree(printed(0, "summary", "busy", "--json", "--account", "acct-1", "--home", home.toString()));
    assertEquals("acct-1", summary.path("accountId").asText());
    assertEquals(1.5, summary.path("costUsd").asDouble());
    assertEquals(61000, summary.path("apiDurationMs").asLong());
    assertEquals(12, summary.path("linesAdded").asLong());
    assertEquals(1, summary.path("prompts").asInt(), "what somebody typed, not what Claude Code wrote itself");
    assertEquals(1, summary.path("promptSources").path("typed").asInt());
    assertEquals(1, summary.path("promptSources").path("system").asInt());
    assertEquals(1, summary.path("turns").asInt());
    assertEquals(65000, summary.path("turnDurationMs").asLong());
    assertEquals(3000, summary.path("thinkingDurationMs").asLong(), "said by a line that left no record");
    assertEquals(4, summary.path("stopReasons").path("tool_use").asInt(), "per API message, not per record");
    assertEquals(1, summary.path("stopReasons").path("end_turn").asInt());
    assertEquals("acceptEdits", summary.path("permissionModes").get(0).asText());
    assertEquals(1, summary.path("pullRequests").size());
    assertEquals(2, summary.path("mcpServers").path("team_wiki").asInt());
    assertEquals(1, summary.path("mcpServers").size());
    assertEquals("Bash", summary.path("tools").get(0).path("tool").asText());
    assertEquals(2, summary.path("tools").get(0).path("calls").asInt());
    assertEquals(1, summary.path("tools").get(0).path("errors").asInt());
    assertEquals(2, summary.path("usage").size());

    String text = printed(0, "summary", "busy", "--account", "acct-1", "--home", home.toString());
    assertTrue(text.contains("cost usd              $1.50"), text);
    assertTrue(text.lines().anyMatch(l -> l.matches("turn duration +1m05s")), text);
    assertTrue(text.lines().anyMatch(l -> l.matches("stop reasons +end_turn 1, tool_use 4")), text);
    assertTrue(text.lines().anyMatch(l -> l.matches("Bash +2 +1")), text);
    assertTrue(text.lines().anyMatch(l -> l.matches("mcp servers +team_wiki 2")), text);

    assertTrue(printed(1, "summary", "nope", "--account", "acct-1", "--home", home.toString()).contains("not recorded"));
  }
}
