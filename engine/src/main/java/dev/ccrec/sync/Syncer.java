package dev.ccrec.sync;

/**
 * Ships locally recorded sessions to a company-wide collector. The local store is the outbox; a
 * collector implementation authenticates the sender and stamps the account server-side.
 */
public interface Syncer {

  /** Called after a session's new records are committed locally. */
  void sessionUpdated(String accountId, String sessionId);

  Syncer NONE = (accountId, sessionId) -> {};
}
