package dev.ccrec.store;

import com.scalar.db.api.Delete;
import com.scalar.db.api.DistributedStorage;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionAdmin;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.api.Get;
import com.scalar.db.api.Result;
import com.scalar.db.api.Scan;
import com.scalar.db.api.Scanner;
import com.scalar.db.api.TableMetadata;
import com.scalar.db.api.Upsert;
import com.scalar.db.api.UpsertBuilder;
import com.scalar.db.exception.storage.ExecutionException;
import com.scalar.db.exception.transaction.CommitConflictException;
import com.scalar.db.exception.transaction.CrudConflictException;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
import com.scalar.db.io.DataType;
import com.scalar.db.io.Key;
import com.scalar.db.service.StorageFactory;
import com.scalar.db.service.TransactionFactory;
import dev.ccrec.content.ContentCodec;
import dev.ccrec.model.Account;
import dev.ccrec.model.IngestState;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import java.time.Instant;
import java.time.ZoneOffset;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * {@link RecordStore} on ScalarDB Core (Community) with the Consensus Commit transaction manager.
 *
 * <p>The schema is the lowest common denominator of the supported databases: no secondary index
 * (an index read spans every partition — {@code sessions_by_day} is an index table instead), no key
 * containing ':' (Cosmos DB), epoch-millisecond BIGINT times (inside Cosmos DB's 2^53), and large
 * text only as chunked BLOB (TEXT is VARCHAR2(4000) on Oracle).
 */
public final class ScalarDbRecordStore implements RecordStore {

  static final String NS = "ccrec";
  static final String ACCOUNTS = "accounts";
  static final String SESSIONS = "sessions";
  static final String SESSIONS_BY_DAY = "sessions_by_day";
  static final String MESSAGES = "messages";
  static final String CONTENTS = "contents";
  static final String INGEST_STATE = "ingest_state";

  private static final int MAX_ATTEMPTS = 4;
  private static final int DELETE_BATCH = 200;

  private final Properties properties;
  private final DistributedTransactionManager manager;

  public ScalarDbRecordStore(Properties properties) {
    this.properties = properties;
    TransactionFactory factory = TransactionFactory.create(properties);
    createSchema(factory);
    this.manager = factory.getTransactionManager();
  }

  private static void createSchema(TransactionFactory factory) {
    DistributedTransactionAdmin admin = factory.getTransactionAdmin();
    try {
      admin.createCoordinatorTables(true);
      admin.createNamespace(NS, true);
      admin.createTable(
          NS,
          ACCOUNTS,
          TableMetadata.newBuilder()
              .addColumn("account_id", DataType.TEXT)
              .addColumn("email", DataType.TEXT)
              .addColumn("display_name", DataType.TEXT)
              .addColumn("org_id", DataType.TEXT)
              .addColumn("org_name", DataType.TEXT)
              .addColumn("auth_method", DataType.TEXT)
              .addColumn("last_seen_at", DataType.BIGINT)
              .addPartitionKey("account_id")
              .build(),
          true);
      admin.createTable(
          NS,
          SESSIONS,
          TableMetadata.newBuilder()
              .addColumn("account_id", DataType.TEXT)
              .addColumn("started_at", DataType.BIGINT)
              .addColumn("session_id", DataType.TEXT)
              .addColumn("org_id", DataType.TEXT)
              .addColumn("host_id", DataType.TEXT)
              .addColumn("project_path", DataType.TEXT)
              .addColumn("git_branch", DataType.TEXT)
              .addColumn("cc_version", DataType.TEXT)
              .addColumn("model", DataType.TEXT)
              .addColumn("title", DataType.TEXT)
              .addColumn("ended_at", DataType.BIGINT)
              .addPartitionKey("account_id")
              .addClusteringKey("started_at", Scan.Ordering.Order.DESC)
              .addClusteringKey("session_id", Scan.Ordering.Order.ASC)
              .build(),
          true);
      admin.createTable(
          NS,
          SESSIONS_BY_DAY,
          TableMetadata.newBuilder()
              .addColumn("org_id", DataType.TEXT)
              .addColumn("day", DataType.INT)
              .addColumn("started_at", DataType.BIGINT)
              .addColumn("session_id", DataType.TEXT)
              .addColumn("account_id", DataType.TEXT)
              .addColumn("project_path", DataType.TEXT)
              .addPartitionKey("org_id")
              .addPartitionKey("day")
              .addClusteringKey("started_at", Scan.Ordering.Order.ASC)
              .addClusteringKey("session_id", Scan.Ordering.Order.ASC)
              .build(),
          true);
      admin.createTable(
          NS,
          MESSAGES,
          TableMetadata.newBuilder()
              .addColumn("session_id", DataType.TEXT)
              .addColumn("agent_id", DataType.TEXT)
              .addColumn("line_no", DataType.INT)
              .addColumn("block_no", DataType.INT)
              .addColumn("account_id", DataType.TEXT)
              .addColumn("kind", DataType.TEXT)
              .addColumn("subtype", DataType.TEXT)
              .addColumn("ts", DataType.BIGINT)
              .addColumn("uuid", DataType.TEXT)
              .addColumn("parent_uuid", DataType.TEXT)
              .addColumn("message_id", DataType.TEXT)
              .addColumn("model", DataType.TEXT)
              .addColumn("tool_name", DataType.TEXT)
              .addColumn("tool_use_id", DataType.TEXT)
              .addColumn("content_hash", DataType.TEXT)
              .addColumn("content_bytes", DataType.BIGINT)
              .addColumn("preview", DataType.TEXT)
              .addColumn("input_tokens", DataType.BIGINT)
              .addColumn("output_tokens", DataType.BIGINT)
              .addColumn("cache_read_tokens", DataType.BIGINT)
              .addColumn("cache_creation_tokens", DataType.BIGINT)
              .addPartitionKey("session_id")
              .addClusteringKey("agent_id", Scan.Ordering.Order.ASC)
              .addClusteringKey("line_no", Scan.Ordering.Order.ASC)
              .addClusteringKey("block_no", Scan.Ordering.Order.ASC)
              .build(),
          true);
      admin.createTable(
          NS,
          CONTENTS,
          TableMetadata.newBuilder()
              .addColumn("content_hash", DataType.TEXT)
              .addColumn("chunk_no", DataType.INT)
              .addColumn("data", DataType.BLOB)
              .addColumn("encoding", DataType.TEXT)
              .addColumn("chunk_count", DataType.INT)
              .addColumn("total_bytes", DataType.BIGINT)
              .addPartitionKey("content_hash")
              .addClusteringKey("chunk_no", Scan.Ordering.Order.ASC)
              .build(),
          true);
      admin.createTable(
          NS,
          INGEST_STATE,
          TableMetadata.newBuilder()
              .addColumn("host_id", DataType.TEXT)
              .addColumn("source_path_hash", DataType.TEXT)
              .addColumn("source_path", DataType.TEXT)
              .addColumn("byte_offset", DataType.BIGINT)
              .addColumn("line_no", DataType.INT)
              .addColumn("usage_message_id", DataType.TEXT)
              .addPartitionKey("host_id")
              .addClusteringKey("source_path_hash", Scan.Ordering.Order.ASC)
              .build(),
          true);
      addColumnIfMissing(admin, MESSAGES, "message_id");
      addColumnIfMissing(admin, INGEST_STATE, "usage_message_id");
    } catch (ExecutionException e) {
      throw new StoreException("could not create the ccrec schema", e);
    } finally {
      admin.close();
    }
  }

  /** For a column introduced after 0.1.0: creating a table leaves one that exists as it is. */
  private static void addColumnIfMissing(
      DistributedTransactionAdmin admin, String table, String column) throws ExecutionException {
    if (!admin.getTableMetadata(NS, table).getColumnNames().contains(column)) {
      admin.addNewColumnToTable(NS, table, column, DataType.TEXT);
    }
  }

  @Override
  public void upsertAccount(Account account, long seenAt) {
    write(
        tx -> {
          UpsertBuilder.Buildable upsert =
              Upsert.newBuilder()
                  .namespace(NS)
                  .table(ACCOUNTS)
                  .partitionKey(Key.ofText("account_id", account.accountId()))
                  .textValue("org_id", account.orgId())
                  .bigIntValue("last_seen_at", seenAt);
          text(upsert, "email", account.email());
          text(upsert, "display_name", account.displayName());
          text(upsert, "org_name", account.orgName());
          text(upsert, "auth_method", account.authMethod());
          tx.upsert(upsert.build());
        });
  }

  /** The session row and its by-day index row. */
  private static void putSession(DistributedTransaction tx, SessionRecord session)
      throws TransactionException {
    UpsertBuilder.Buildable upsert =
        Upsert.newBuilder()
            .namespace(NS)
            .table(SESSIONS)
            .partitionKey(Key.ofText("account_id", session.accountId()))
            .clusteringKey(
                Key.newBuilder()
                    .addBigInt("started_at", session.startedAt())
                    .addText("session_id", session.sessionId())
                    .build())
            .textValue("org_id", session.orgId());
    text(upsert, "host_id", session.hostId());
    text(upsert, "project_path", session.projectPath());
    text(upsert, "git_branch", session.gitBranch());
    text(upsert, "cc_version", session.ccVersion());
    text(upsert, "model", session.model());
    text(upsert, "title", session.title());
    bigInt(upsert, "ended_at", session.endedAt());
    tx.upsert(upsert.build());

    UpsertBuilder.Buildable byDay =
        Upsert.newBuilder()
            .namespace(NS)
            .table(SESSIONS_BY_DAY)
            .partitionKey(
                Key.newBuilder()
                    .addText("org_id", session.orgId())
                    .addInt("day", utcDay(session.startedAt()))
                    .build())
            .clusteringKey(
                Key.newBuilder()
                    .addBigInt("started_at", session.startedAt())
                    .addText("session_id", session.sessionId())
                    .build())
            .textValue("account_id", session.accountId());
    text(byDay, "project_path", session.projectPath());
    tx.upsert(byDay.build());
  }

  @Override
  public void writeBatch(
      List<MessageRecord> messages,
      Map<String, String> contents,
      IngestState state,
      SessionRecord session) {
    write(
        tx -> {
          if (session != null) {
            putSession(tx, session);
          }
          for (Map.Entry<String, String> content : contents.entrySet()) {
            putContentIfAbsent(tx, content.getKey(), content.getValue());
          }
          for (MessageRecord message : messages) {
            tx.upsert(messageUpsert(message));
          }
          tx.upsert(
              Upsert.newBuilder()
                  .namespace(NS)
                  .table(INGEST_STATE)
                  .partitionKey(Key.ofText("host_id", state.hostId()))
                  .clusteringKey(Key.ofText("source_path_hash", state.sourcePathHash()))
                  .textValue("source_path", state.sourcePath())
                  .bigIntValue("byte_offset", state.offset())
                  .intValue("line_no", state.lineNo())
                  .textValue("usage_message_id", state.usageMessageId())
                  .build());
        });
  }

  private static void putContentIfAbsent(DistributedTransaction tx, String hash, String text)
      throws TransactionException {
    Optional<Result> first =
        tx.get(
            Get.newBuilder()
                .namespace(NS)
                .table(CONTENTS)
                .partitionKey(Key.ofText("content_hash", hash))
                .clusteringKey(Key.ofInt("chunk_no", 0))
                .projection("chunk_count")
                .build());
    if (first.isPresent()) {
      return;
    }
    List<byte[]> chunks = ContentCodec.encode(text);
    long totalBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    for (int i = 0; i < chunks.size(); i++) {
      tx.upsert(
          Upsert.newBuilder()
              .namespace(NS)
              .table(CONTENTS)
              .partitionKey(Key.ofText("content_hash", hash))
              .clusteringKey(Key.ofInt("chunk_no", i))
              .blobValue("data", chunks.get(i))
              .textValue("encoding", ContentCodec.ENCODING)
              .intValue("chunk_count", chunks.size())
              .bigIntValue("total_bytes", totalBytes)
              .build());
    }
  }

  private static Upsert messageUpsert(MessageRecord m) {
    UpsertBuilder.Buildable upsert =
        Upsert.newBuilder()
            .namespace(NS)
            .table(MESSAGES)
            .partitionKey(Key.ofText("session_id", m.sessionId()))
            .clusteringKey(
                Key.newBuilder()
                    .addText("agent_id", m.agentId())
                    .addInt("line_no", m.lineNo())
                    .addInt("block_no", m.blockNo())
                    .build())
            .textValue("account_id", m.accountId())
            .textValue("kind", m.kind())
            .bigIntValue("content_bytes", m.contentBytes());
    text(upsert, "subtype", m.subtype());
    bigInt(upsert, "ts", m.ts());
    text(upsert, "uuid", m.uuid());
    text(upsert, "parent_uuid", m.parentUuid());
    text(upsert, "message_id", m.messageId());
    text(upsert, "model", m.model());
    text(upsert, "tool_name", m.toolName());
    text(upsert, "tool_use_id", m.toolUseId());
    text(upsert, "content_hash", m.contentHash());
    text(upsert, "preview", m.preview());
    bigInt(upsert, "input_tokens", m.inputTokens());
    bigInt(upsert, "output_tokens", m.outputTokens());
    bigInt(upsert, "cache_read_tokens", m.cacheReadTokens());
    bigInt(upsert, "cache_creation_tokens", m.cacheCreationTokens());
    return upsert.build();
  }

  @Override
  public Optional<IngestState> ingestState(String hostId, String sourcePathHash) {
    return read(
        tx ->
            tx.get(
                    Get.newBuilder()
                        .namespace(NS)
                        .table(INGEST_STATE)
                        .partitionKey(Key.ofText("host_id", hostId))
                        .clusteringKey(Key.ofText("source_path_hash", sourcePathHash))
                        .build())
                .map(
                    r ->
                        new IngestState(
                            hostId,
                            sourcePathHash,
                            r.getText("source_path"),
                            r.getBigInt("byte_offset"),
                            r.getInt("line_no"),
                            r.getText("usage_message_id"))));
  }

  @Override
  public Optional<Account> account(String accountId) {
    return read(
        tx ->
            tx.get(
                    Get.newBuilder()
                        .namespace(NS)
                        .table(ACCOUNTS)
                        .partitionKey(Key.ofText("account_id", accountId))
                        .build())
                .map(
                    r ->
                        new Account(
                            r.getText("account_id"),
                            r.getText("email"),
                            r.getText("display_name"),
                            r.getText("org_id"),
                            r.getText("org_name"),
                            r.getText("auth_method"))));
  }

  @Override
  public Optional<String> sessionAccount(String sessionId) {
    return read(
        tx ->
            tx
                .scan(
                    Scan.newBuilder()
                        .namespace(NS)
                        .table(MESSAGES)
                        .partitionKey(Key.ofText("session_id", sessionId))
                        .projection("account_id")
                        .limit(1)
                        .build())
                .stream()
                .findFirst()
                .map(r -> r.getText("account_id")));
  }

  @Override
  public List<SessionRecord> sessions(String accountId, int limit) {
    return read(
        tx -> {
          List<SessionRecord> sessions = new ArrayList<>();
          for (Result r :
              tx.scan(
                  Scan.newBuilder()
                      .namespace(NS)
                      .table(SESSIONS)
                      .partitionKey(Key.ofText("account_id", accountId))
                      .limit(limit)
                      .build())) {
            sessions.add(
                new SessionRecord(
                    r.getText("account_id"),
                    r.getBigInt("started_at"),
                    r.getText("session_id"),
                    r.getText("org_id"),
                    r.getText("host_id"),
                    r.getText("project_path"),
                    r.getText("git_branch"),
                    r.getText("cc_version"),
                    r.getText("model"),
                    r.getText("title"),
                    nullableBigInt(r, "ended_at")));
          }
          return sessions;
        });
  }

  @Override
  public List<SessionRecord> sessionsByDay(String orgId, int day, int limit) {
    return read(
        tx -> {
          List<SessionRecord> sessions = new ArrayList<>();
          for (Result r :
              tx.scan(
                  Scan.newBuilder()
                      .namespace(NS)
                      .table(SESSIONS_BY_DAY)
                      .partitionKey(
                          Key.newBuilder().addText("org_id", orgId).addInt("day", day).build())
                      .limit(limit)
                      .build())) {
            sessions.add(
                new SessionRecord(
                    r.getText("account_id"),
                    r.getBigInt("started_at"),
                    r.getText("session_id"),
                    orgId,
                    null,
                    r.getText("project_path"),
                    null,
                    null,
                    null,
                    null,
                    null));
          }
          return sessions;
        });
  }

  @Override
  public List<MessageRecord> messages(String sessionId) {
    return read(
        tx -> {
          List<MessageRecord> messages = new ArrayList<>();
          for (Result r :
              tx.scan(
                  Scan.newBuilder()
                      .namespace(NS)
                      .table(MESSAGES)
                      .partitionKey(Key.ofText("session_id", sessionId))
                      .build())) {
            messages.add(
                new MessageRecord(
                    r.getText("session_id"),
                    r.getText("agent_id"),
                    r.getInt("line_no"),
                    r.getInt("block_no"),
                    r.getText("account_id"),
                    r.getText("kind"),
                    r.getText("subtype"),
                    nullableBigInt(r, "ts"),
                    r.getText("uuid"),
                    r.getText("parent_uuid"),
                    r.getText("message_id"),
                    r.getText("model"),
                    r.getText("tool_name"),
                    r.getText("tool_use_id"),
                    r.getText("content_hash"),
                    r.getBigInt("content_bytes"),
                    r.getText("preview"),
                    nullableBigInt(r, "input_tokens"),
                    nullableBigInt(r, "output_tokens"),
                    nullableBigInt(r, "cache_read_tokens"),
                    nullableBigInt(r, "cache_creation_tokens")));
          }
          // The main thread reads first; sub-agents follow in id order.
          messages.sort(
              java.util.Comparator.comparing(
                      (MessageRecord m) -> !MessageRecord.MAIN_AGENT.equals(m.agentId()))
                  .thenComparing(MessageRecord::agentId)
                  .thenComparingInt(MessageRecord::lineNo)
                  .thenComparingInt(MessageRecord::blockNo));
          return messages;
        });
  }

  @Override
  public Optional<String> content(String contentHash) {
    return read(
        tx -> {
          List<Result> rows =
              tx.scan(
                  Scan.newBuilder()
                      .namespace(NS)
                      .table(CONTENTS)
                      .partitionKey(Key.ofText("content_hash", contentHash))
                      .build());
          if (rows.isEmpty()) {
            return Optional.empty();
          }
          List<byte[]> chunks = new ArrayList<>();
          for (Result row : rows) {
            chunks.add(row.getBlobAsBytes("data"));
          }
          return Optional.of(ContentCodec.decode(chunks));
        });
  }

  @Override
  public Deleted deleteSession(String sessionId, String accountId, String hostId) {
    List<Key> records = new ArrayList<>();
    Set<String> hashes = new LinkedHashSet<>();
    // Records of one session can name more than one account, each with a session row of its own.
    Set<String> accounts = new LinkedHashSet<>();
    if (accountId != null) {
      accounts.add(accountId);
    }
    read(
        tx -> {
          records.clear();
          for (Result r :
              tx.scan(
                  Scan.newBuilder()
                      .namespace(NS)
                      .table(MESSAGES)
                      .partitionKey(Key.ofText("session_id", sessionId))
                      .projections("agent_id", "line_no", "block_no", "account_id", "content_hash")
                      .build())) {
            records.add(
                Key.newBuilder()
                    .addText("agent_id", r.getText("agent_id"))
                    .addInt("line_no", r.getInt("line_no"))
                    .addInt("block_no", r.getInt("block_no"))
                    .build());
            accounts.add(r.getText("account_id"));
            if (r.getText("content_hash") != null) {
              hashes.add(r.getText("content_hash"));
            }
          }
          return null;
        });

    // Content first: once the records are gone, nothing says which content was this session's alone.
    Set<String> shared = usedByAnotherSession(hashes, sessionId);
    List<String> orphans = hashes.stream().filter(hash -> !shared.contains(hash)).toList();
    for (List<String> batch : batches(orphans, 20)) {
      write(
          tx -> {
            for (String hash : batch) {
              Key partition = Key.ofText("content_hash", hash);
              for (Result chunk :
                  tx.scan(
                      Scan.newBuilder()
                          .namespace(NS)
                          .table(CONTENTS)
                          .partitionKey(partition)
                          .projection("chunk_no")
                          .build())) {
                tx.delete(
                    Delete.newBuilder()
                        .namespace(NS)
                        .table(CONTENTS)
                        .partitionKey(partition)
                        .clusteringKey(Key.ofInt("chunk_no", chunk.getInt("chunk_no")))
                        .build());
              }
            }
          });
    }

    boolean session = false;
    Set<String> hosts = new LinkedHashSet<>();
    if (hostId != null && !hostId.isBlank()) {
      hosts.add(Account.keySafe(hostId));
    }
    for (String account : accounts) {
      session |= read(tx -> deleteSessionRows(tx, account, sessionId, hosts));
    }
    for (String host : hosts) {
      write(tx -> deleteIngestStates(tx, host, sessionId));
    }
    for (List<Key> batch : batches(records, DELETE_BATCH)) {
      write(
          tx -> {
            for (Key record : batch) {
              tx.delete(
                  Delete.newBuilder()
                      .namespace(NS)
                      .table(MESSAGES)
                      .partitionKey(Key.ofText("session_id", sessionId))
                      .clusteringKey(record)
                      .build());
            }
          });
    }
    return new Deleted(session, records.size(), orphans.size(), shared.size());
  }

  /** The session row and its by-day index row. The host a row names is added to {@code hosts}. */
  private static boolean deleteSessionRows(
      DistributedTransaction tx, String accountId, String sessionId, Set<String> hosts)
      throws TransactionException {
    boolean found = false;
    for (Result r :
        tx.scan(
            Scan.newBuilder()
                .namespace(NS)
                .table(SESSIONS)
                .partitionKey(Key.ofText("account_id", accountId))
                .projections("started_at", "session_id", "org_id", "host_id")
                .build())) {
      if (!sessionId.equals(r.getText("session_id"))) {
        continue;
      }
      found = true;
      Key startedAndId =
          Key.newBuilder()
              .addBigInt("started_at", r.getBigInt("started_at"))
              .addText("session_id", sessionId)
              .build();
      tx.delete(
          Delete.newBuilder()
              .namespace(NS)
              .table(SESSIONS)
              .partitionKey(Key.ofText("account_id", accountId))
              .clusteringKey(startedAndId)
              .build());
      tx.delete(
          Delete.newBuilder()
              .namespace(NS)
              .table(SESSIONS_BY_DAY)
              .partitionKey(
                  Key.newBuilder()
                      .addText("org_id", r.getText("org_id"))
                      .addInt("day", utcDay(r.getBigInt("started_at")))
                      .build())
              .clusteringKey(startedAndId)
              .build());
      if (r.getText("host_id") != null) {
        hosts.add(r.getText("host_id"));
      }
    }
    return found;
  }

  /**
   * Forgets how far the session's files were read, so importing them again records them again. They
   * are known by their place: {@code <session>.jsonl} and {@code <session>/subagents/*}.
   */
  private static void deleteIngestStates(DistributedTransaction tx, String hostId, String sessionId)
      throws TransactionException {
    for (Result r :
        tx.scan(
            Scan.newBuilder()
                .namespace(NS)
                .table(INGEST_STATE)
                .partitionKey(Key.ofText("host_id", hostId))
                .projections("source_path_hash", "source_path")
                .build())) {
      String path = String.valueOf(r.getText("source_path")).replace('\\', '/');
      if (path.endsWith("/" + sessionId + ".jsonl") || path.contains("/" + sessionId + "/subagents/")) {
        tx.delete(
            Delete.newBuilder()
                .namespace(NS)
                .table(INGEST_STATE)
                .partitionKey(Key.ofText("host_id", hostId))
                .clusteringKey(Key.ofText("source_path_hash", r.getText("source_path_hash")))
                .build());
      }
    }
  }

  /**
   * Which of these contents a record of another session refers to. Nothing indexes records by
   * content, so this reads every record once — outside a transaction, which would hold all it reads
   * in memory. A record another process is writing at this moment is seen too, which errs on the
   * side of keeping its content.
   */
  private Set<String> usedByAnotherSession(Set<String> hashes, String sessionId) {
    Set<String> used = new HashSet<>();
    if (hashes.isEmpty()) {
      return used;
    }
    DistributedStorage storage = StorageFactory.create(properties).getStorage();
    try (Scanner scanner =
        storage.scan(
            Scan.newBuilder()
                .namespace(NS)
                .table(MESSAGES)
                .all()
                .projections("session_id", "content_hash")
                .build())) {
      for (Result r : scanner) {
        String hash = r.getText("content_hash");
        if (hash != null && hashes.contains(hash) && !sessionId.equals(r.getText("session_id"))) {
          used.add(hash);
        }
      }
    } catch (ExecutionException | IOException e) {
      throw new StoreException("could not tell which contents other sessions use", e);
    } finally {
      storage.close();
    }
    return used;
  }

  private static <T> List<List<T>> batches(List<T> items, int size) {
    List<List<T>> batches = new ArrayList<>();
    for (int from = 0; from < items.size(); from += size) {
      batches.add(items.subList(from, Math.min(from + size, items.size())));
    }
    return batches;
  }

  @Override
  public void close() {
    manager.close();
  }

  static int utcDay(long epochMillis) {
    var date = Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toLocalDate();
    return date.getYear() * 10000 + date.getMonthValue() * 100 + date.getDayOfMonth();
  }

  private static void text(UpsertBuilder.Buildable upsert, String column, String value) {
    if (value != null) {
      upsert.textValue(column, value);
    }
  }

  private static void bigInt(UpsertBuilder.Buildable upsert, String column, Long value) {
    if (value != null) {
      upsert.bigIntValue(column, value);
    }
  }

  private static Long nullableBigInt(Result result, String column) {
    return result.isNull(column) ? null : result.getBigInt(column);
  }

  private interface Work<T> {
    T apply(DistributedTransaction tx) throws TransactionException;
  }

  private interface WriteWork {
    void apply(DistributedTransaction tx) throws TransactionException;
  }

  private void write(WriteWork work) {
    read(
        tx -> {
          work.apply(tx);
          return null;
        });
  }

  /**
   * Runs one transaction, retrying the transient conflicts. An unknown commit status is surfaced
   * rather than retried here: every write is an upsert on a source-derived key, so the caller simply
   * ingests the same lines again on its next run.
   */
  private <T> T read(Work<T> work) {
    TransactionException last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      DistributedTransaction tx = null;
      try {
        tx = manager.begin();
        T result = work.apply(tx);
        tx.commit();
        return result;
      } catch (UnknownTransactionStatusException e) {
        throw new StoreException("commit status unknown; the batch will be ingested again", e);
      } catch (CrudConflictException | CommitConflictException e) {
        last = e;
        rollback(tx);
        pause(attempt);
      } catch (TransactionException e) {
        rollback(tx);
        throw new StoreException("transaction failed", e);
      } catch (RuntimeException e) {
        rollback(tx);
        throw e;
      }
    }
    throw new StoreException("transaction kept conflicting after " + MAX_ATTEMPTS + " attempts", last);
  }

  private static void rollback(DistributedTransaction tx) {
    if (tx == null) {
      return;
    }
    try {
      tx.rollback();
    } catch (TransactionException ignored) {
      // The transaction expires on its own; the original failure is what matters.
    }
  }

  private static void pause(int attempt) {
    try {
      Thread.sleep(100L * attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new StoreException("interrupted while retrying", e);
    }
  }

  public static final class StoreException extends RuntimeException {
    public StoreException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
