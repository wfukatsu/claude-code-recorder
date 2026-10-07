package dev.ccrec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ccrec.content.ContentCodec;
import dev.ccrec.ingest.Ingester;
import dev.ccrec.model.Account;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import dev.ccrec.redact.Redactor;
import dev.ccrec.store.RecordStore;
import dev.ccrec.store.ScalarDbRecordStore;
import dev.ccrec.sync.Syncer;
import dev.ccrec.transcript.TranscriptParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs against a real ScalarDB engine over a SQLite file — no mock stands in for the store. */
class RecorderTest {

  private static final String SESSION = "11111111-2222-3333-4444-555555555555";
  private static final Account ALICE =
      new Account("acct-alice", "alice@example.com", "Alice", "org-1", "Example", "oauth");

  private static final String SNAPSHOT =
      "{\"type\":\"attachment\",\"timestamp\":\"2026-10-07T01:00:00.000Z\",\"attachment\":{\"type\":\"prompt_snapshot\","
          + "\"systemPrompt\":[\"You are a helpful agent.\",\"" + "long system prompt ".repeat(3000) + "\"],"
          + "\"tools\":[{\"name\":\"Bash\"}]}}";
  private static final String PROMPT =
      "{\"type\":\"user\",\"uuid\":\"u1\",\"timestamp\":\"2026-10-07T01:00:01.000Z\",\"cwd\":\"/work/app\","
          + "\"gitBranch\":\"main\",\"version\":\"2.1.292\",\"message\":{\"role\":\"user\",\"content\":\"日本語の質問です "
          + "token sk-ant-abcdefghijklmnopqrstuvwxyz0123\"}}";
  private static final String ANSWER =
      "{\"type\":\"assistant\",\"uuid\":\"a1\",\"parentUuid\":\"u1\",\"timestamp\":\"2026-10-07T01:00:05.000Z\","
          + "\"message\":{\"model\":\"claude-opus-5-5\",\"usage\":{\"input_tokens\":10,\"output_tokens\":20,"
          + "\"cache_read_input_tokens\":30,\"cache_creation_input_tokens\":40},\"content\":["
          + "{\"type\":\"thinking\",\"thinking\":\"let me think\"},{\"type\":\"text\",\"text\":\"回答です\"},"
          + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}";
  private static final String RESULT =
      "{\"type\":\"user\",\"uuid\":\"u2\",\"timestamp\":\"2026-10-07T01:00:06.000Z\",\"message\":{\"role\":\"user\","
          + "\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":[{\"type\":\"text\",\"text\":\"a.txt\"}]}]}}";
  private static final String BOOKKEEPING = "{\"type\":\"mode\",\"mode\":\"default\"}";
  private static final String TITLE = "{\"type\":\"ai-title\",\"aiTitle\":\"Recording design\"}";
  private static final String FUTURE = "{\"type\":\"brand-new-record\",\"payload\":1}";

  private static RecordStore store(Path dir) {
    Properties properties = new Properties();
    properties.setProperty("scalar.db.storage", "jdbc");
    properties.setProperty(
        "scalar.db.contact_points", "jdbc:sqlite:" + dir.resolve("test.sqlite3") + "?busy_timeout=10000");
    properties.setProperty("scalar.db.username", "");
    properties.setProperty("scalar.db.password", "");
    properties.setProperty("scalar.db.transaction_manager", "consensus-commit");
    return new ScalarDbRecordStore(properties);
  }

  private static Ingester ingester(RecordStore store, boolean thinking) {
    return new Ingester(store, Redactor.standard(), Syncer.NONE, thinking, "host-1");
  }

  @Test
  void recordsEveryPartOfTheExchangeUnderTheAccount(@TempDir Path dir) throws IOException {
    Path transcript = dir.resolve(SESSION + ".jsonl");
    Files.writeString(
        transcript, String.join("\n", SNAPSHOT, PROMPT, ANSWER, RESULT, BOOKKEEPING, TITLE, FUTURE) + "\n");

    try (RecordStore store = store(dir)) {
      Ingester.Summary summary = ingester(store, true).ingestSession(transcript, SESSION, ALICE);
      assertEquals(7, summary.lines());

      List<MessageRecord> messages = store.messages(SESSION);
      assertEquals(
          List.of(
              "system_prompt", "tool_definitions", "user_prompt", "thinking", "assistant_text",
              "tool_use", "tool_result", "unknown"),
          messages.stream().map(MessageRecord::kind).toList());
      assertTrue(messages.stream().allMatch(m -> m.accountId().equals("acct-alice")));

      MessageRecord systemPrompt = messages.get(0);
      String prompt = store.content(systemPrompt.contentHash()).orElseThrow();
      assertTrue(prompt.startsWith("You are a helpful agent.\nlong system prompt"));
      assertTrue(prompt.length() > 50_000, "a prompt far larger than one chunk round-trips");
      assertEquals(1000, systemPrompt.preview().length());

      MessageRecord userPrompt = messages.get(2);
      String asked = store.content(userPrompt.contentHash()).orElseThrow();
      assertTrue(asked.startsWith("日本語の質問です"));
      assertFalse(asked.contains("sk-ant-"), "credentials are masked before storage");

      MessageRecord thinking = messages.get(3);
      assertEquals(10L, thinking.inputTokens(), "usage is recorded once per line, on its first block");
      assertNull(messages.get(4).inputTokens());
      assertEquals("Bash", messages.get(5).toolName());
      assertEquals("a.txt", store.content(messages.get(6).contentHash()).orElseThrow());
      assertEquals("t1", messages.get(6).toolUseId());

      SessionRecord session = store.sessions("acct-alice", 10).get(0);
      assertEquals(SESSION, session.sessionId());
      assertEquals("/work/app", session.projectPath());
      assertEquals("claude-opus-5-5", session.model());
      assertEquals("Recording design", session.title());
      assertEquals("org-1", session.orgId());
      assertEquals(SESSION, store.sessionsByDay("org-1", 20261007, 10).get(0).sessionId());
      assertEquals("alice@example.com", store.account("acct-alice").orElseThrow().email());
    }
  }

  @Test
  void ingestsOnlyWhatIsNewAndNeverTwice(@TempDir Path dir) throws IOException {
    Path transcript = dir.resolve(SESSION + ".jsonl");
    // The last line has no newline yet: Claude Code is still writing it.
    Files.writeString(transcript, PROMPT + "\n" + ANSWER);

    try (RecordStore store = store(dir)) {
      Ingester ingester = ingester(store, true);
      assertEquals(1, ingester.ingestSession(transcript, SESSION, ALICE).lines());
      assertEquals(1, store.messages(SESSION).size());

      assertEquals(0, ingester.ingestSession(transcript, SESSION, ALICE).lines());

      Files.writeString(transcript, "\n" + RESULT + "\n", StandardOpenOption.APPEND);
      assertEquals(2, ingester.ingestSession(transcript, SESSION, ALICE).lines());
      assertEquals(5, store.messages(SESSION).size());
      assertEquals(1, store.sessions("acct-alice", 10).size());
    }
  }

  @Test
  void keepsSubAgentsInTheSessionAndCanLeaveThinkingOut(@TempDir Path dir) throws IOException {
    Path transcript = dir.resolve(SESSION + ".jsonl");
    Files.writeString(transcript, PROMPT + "\n" + ANSWER + "\n");
    Path subagents = Files.createDirectories(dir.resolve(SESSION).resolve("subagents"));
    Files.writeString(subagents.resolve("agent-abc123.jsonl"), PROMPT + "\n");

    try (RecordStore store = store(dir)) {
      Ingester.Summary summary = ingester(store, false).ingestSession(transcript, SESSION, ALICE);
      assertEquals(2, summary.files());
      List<MessageRecord> messages = store.messages(SESSION);
      assertTrue(messages.stream().noneMatch(m -> m.kind().equals(TranscriptParser.THINKING)));
      assertEquals(
          List.of("main", "main", "main", "abc123"),
          messages.stream().map(MessageRecord::agentId).toList());
    }
  }

  @Test
  void contentIsChunkedBelowTheSmallestBlobLimit() {
    StringBuilder text = new StringBuilder();
    java.util.Random random = new java.util.Random(7);
    for (int i = 0; i < 40_000; i++) {
      text.append((char) ('a' + random.nextInt(26)));
    }
    List<byte[]> chunks = ContentCodec.encode(text.toString());
    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(chunk -> chunk.length <= ContentCodec.CHUNK_BYTES));
    assertEquals(text.toString(), ContentCodec.decode(chunks));
    assertEquals(64, ContentCodec.hash("x").length());
  }

  @Test
  void accountKeysNeverCarryAColon() {
    Account account = new Account("urn:acct:1", null, null, null, null, "env");
    assertEquals("urn_acct_1", account.accountId());
    assertEquals(Account.NO_ORG, account.orgId());
  }
}
