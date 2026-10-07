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

  private static void queue(Path home, String sessionId, Path transcript) throws IOException {
    Files.writeString(
        home.resolve("spool").resolve(sessionId + ".json"),
        "{\"session_id\":\"" + sessionId + "\",\"transcript_path\":\"" + transcript + "\",\"host_id\":\"host-1\","
            + "\"account\":{\"account_id\":\"acct-1\"},\"ended\":true}");
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
    queue(home, "good", transcript);

    assertEquals(1, Main.run(new String[] {"ingest", "--home", home.toString()}));

    assertEquals(List.of("good"), sessions(home).stream().map(SessionRecord::sessionId).toList());
    assertFalse(Files.exists(spool.resolve("good.json")), "an ended session leaves the queue");
    assertTrue(Files.exists(spool.resolve("000.json.bad")));
    assertTrue(Files.exists(spool.resolve("zzz.json.bad")));
    assertEquals(0, Main.run(new String[] {"ingest", "--home", home.toString()}), "nothing is left to fail on");
  }

  @Test
  void ingestingOneSessionDoesNotReadTheOtherEntries(@TempDir Path dir) throws IOException {
    Path home = home(dir);
    Path transcript = dir.resolve("good.jsonl");
    Files.writeString(transcript, String.format(LINE, "/work/app"));
    Files.writeString(home.resolve("spool").resolve("000.json"), "{broken");
    queue(home, "good", transcript);

    assertEquals(0, Main.run(new String[] {"ingest", "--session", "good", "--home", home.toString()}));
    assertEquals(1, sessions(home).size());
    assertTrue(Files.exists(home.resolve("spool").resolve("000.json")));
  }
}
