package dev.ccrec.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ccrec.model.SessionRecord;
import dev.ccrec.store.RecordStore;
import dev.ccrec.store.ScalarDbRecordStore;
import java.io.IOException;
import java.io.InputStream;
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
}
