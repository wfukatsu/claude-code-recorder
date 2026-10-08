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

  /**
   * Atomically writes messages, the content they reference, the ingest position and — so that no
   * recorded line is ever left without one — the session they belong to. All keys are derived from
   * the source, so repeating a batch is harmless.
   *
   * @param contents text by content hash; content already stored is not rewritten
   * @param session the session row and its by-day index row to write along, or null for none
   */
  void writeBatch(
      List<MessageRecord> messages,
      Map<String, String> contents,
      IngestState state,
      SessionRecord session);

  Optional<IngestState> ingestState(String hostId, String sourcePathHash);

  Optional<Account> account(String accountId);

  /** The account a session's records are filed under, once it has any. */
  Optional<String> sessionAccount(String sessionId);

  /** Newest first. */
  List<SessionRecord> sessions(String accountId, int limit);

  /** Sessions of an organization that started on a UTC day ({@code yyyyMMdd}), oldest first. */
  List<SessionRecord> sessionsByDay(String orgId, int day, int limit);

  /** In source order: main thread first, then each sub-agent. */
  List<MessageRecord> messages(String sessionId);

  Optional<String> content(String contentHash);

  /** What {@link #deleteSession} removed; {@code sharedContents} were kept for the sessions that still use them. */
  record Deleted(boolean session, int messages, int contents, int sharedContents) {
    public boolean anything() {
      return session || messages > 0;
    }
  }

  /**
   * Removes a session: its row and by-day index row, its records, how far its files were ingested,
   * and every content no other session refers to. Repeating it after an interruption finishes the job.
   *
   * @param accountId where else to look for the session row, besides the accounts its records name;
   *     may be null
   * @param hostId where else to look for the ingest positions, besides the host the session row
   *     names; may be null
   */
  Deleted deleteSession(String sessionId, String accountId, String hostId);

  @Override
  void close();
}
