package dev.ccrec.store;

import dev.ccrec.model.Account;
import dev.ccrec.model.IngestState;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The storage port. Nothing above it knows which database is underneath; the ScalarDB implementation
 * is selected by a properties file alone.
 */
public interface RecordStore extends AutoCloseable {

  void upsertAccount(Account account, long seenAt);

  /** Writes the session row and its by-day index row. */
  void upsertSession(SessionRecord session);

  /**
   * Atomically writes messages, the content they reference and the ingest position. All keys are
   * derived from the source, so repeating a batch is harmless.
   *
   * @param contents text by content hash; content already stored is not rewritten
   */
  void writeBatch(List<MessageRecord> messages, Map<String, String> contents, IngestState state);

  Optional<IngestState> ingestState(String hostId, String sourcePathHash);

  Optional<Account> account(String accountId);

  /** Newest first. */
  List<SessionRecord> sessions(String accountId, int limit);

  /** Sessions of an organization that started on a UTC day ({@code yyyyMMdd}), oldest first. */
  List<SessionRecord> sessionsByDay(String orgId, int day, int limit);

  /** In source order: main thread first, then each sub-agent. */
  List<MessageRecord> messages(String sessionId);

  Optional<String> content(String contentHash);

  @Override
  void close();
}
