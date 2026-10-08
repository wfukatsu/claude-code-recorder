package dev.ccrec.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ccrec.ingest.Ingester;
import dev.ccrec.model.Account;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import dev.ccrec.redact.Redactor;
import dev.ccrec.store.RecordStore;
import dev.ccrec.store.ScalarDbRecordStore;
import dev.ccrec.sync.Syncer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * The engine's command line. The Node launcher ({@code ccrec}) resolves the home directory and the
 * identity, then delegates here; this class is not meant to be typed by hand.
 */
public final class Main {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private static final long LOCK_WAIT_MILLIS = 60_000;
  private static final Duration QUEUE_RETENTION = Duration.ofDays(30);

  private final Path home;
  private final Path config;
  private final Map<String, String> options;
  private final List<String> positional;
  private final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

  private Main(Path home, Path config, Map<String, String> options, List<String> positional) {
    this.home = home;
    this.config = config;
    this.options = options;
    this.positional = positional;
  }

  public static void main(String[] args) {
    try {
      System.exit(run(args));
    } catch (UsageException e) {
      System.err.println("ccrec: " + e.getMessage());
      System.exit(2);
    } catch (Exception e) {
      printError(null, e);
      System.exit(1);
    }
  }

  private static void printError(String what, Exception e) {
    System.err.println("ccrec: " + (what == null ? "" : what + ": ") + e);
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      System.err.println("  caused by: " + cause);
    }
  }

  static int run(String[] args) throws IOException {
    if (args.length == 0) {
      throw new UsageException("missing command");
    }
    Map<String, String> options = new HashMap<>();
    List<String> positional = new ArrayList<>();
    Set<String> flags = Set.of("json", "full");
    for (int i = 1; i < args.length; i++) {
      String arg = args[i];
      if (!arg.startsWith("--")) {
        positional.add(arg);
        continue;
      }
      String name = arg.substring(2);
      int equals = name.indexOf('=');
      if (equals >= 0) {
        options.put(name.substring(0, equals), name.substring(equals + 1));
      } else if (flags.contains(name)) {
        options.put(name, "true");
      } else if (i + 1 < args.length) {
        options.put(name, args[++i]);
      } else {
        throw new UsageException("option --" + name + " needs a value");
      }
    }
    String homeOption = options.get("home");
    if (homeOption == null) {
      throw new UsageException("--home is required");
    }
    Path home = Path.of(homeOption);
    Path config = Path.of(options.getOrDefault("config", home.resolve("database.properties").toString()));
    Main main = new Main(home, config, options, positional);
    return switch (args[0]) {
      case "ingest" -> main.ingest();
      case "import" -> main.importTranscripts();
      case "sessions" -> main.sessions();
      case "show" -> main.show();
      case "delete" -> main.delete();
      default -> throw new UsageException("unknown command: " + args[0]);
    };
  }

  // ---- write commands -------------------------------------------------------------------------

  /**
   * Ingests what the hooks queued under {@code spool/}: every session, or with {@code --session} that
   * one and any other whose ingest was requested and has not succeeded since — so a run that was cut
   * short, timed out on the lock or could not start is made up for by the next one.
   */
  private int ingest() throws IOException {
    Path spool = home.resolve("spool");
    if (!Files.isDirectory(spool)) {
      return 0;
    }
    String only = options.get("session");
    int failed = 0;
    try (FileChannel lockChannel = openLock();
        FileLock lock = acquire(lockChannel)) {
      if (lock == null) {
        // Another ingest holds the store; the session stays waiting and a later run takes it.
        return 0;
      }
      try (RecordStore store = openStore()) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(spool, "*.json")) {
          for (Path entry : entries) {
            // One entry that cannot be ingested must not keep the sessions after it from being recorded.
            try {
              ingestQueued(store, entry, only);
            } catch (JsonProcessingException | UsageException e) {
              // It will never read any better: set it aside rather than fail on it at every hook.
              failed++;
              printError(entry.getFileName() + " is malformed, set aside as .bad", e);
              Files.move(
                  entry,
                  entry.resolveSibling(entry.getFileName() + ".bad"),
                  StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
              failed++;
              printError(entry.getFileName() + " stays queued", e);
            }
          }
        }
      }
    }
    return failed == 0 ? 0 : 1;
  }

  private void ingestQueued(RecordStore store, Path entry, String only) throws IOException {
    // Taken before the entry is read: an event the hook queues from here on is still waiting afterwards.
    Instant started = Instant.now();
    JsonNode queued = JSON.readTree(Files.readString(entry));
    String sessionId = queued.path("session_id").asText("");
    if (sessionId.isEmpty()) {
      throw new UsageException("the entry has no session_id");
    }
    String name = entry.getFileName().toString();
    Path done = entry.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".done");
    if (only != null && !only.equals(sessionId) && !waiting(queued, done)) {
      // Up to date. A session that never ended (a crash, a closed laptop) would otherwise stay forever.
      if (Files.getLastModifiedTime(entry).toInstant().isBefore(started.minus(QUEUE_RETENTION))) {
        Files.deleteIfExists(entry);
        Files.deleteIfExists(done);
      }
      return;
    }
    Path transcript = Path.of(queued.path("transcript_path").asText(""));
    if (!Files.isRegularFile(transcript)) {
      if (!waiting(queued, done)) {
        // A session that has only started may have no transcript yet.
        return;
      }
      if (Files.getLastModifiedTime(entry).toInstant().isBefore(started.minus(QUEUE_RETENTION))) {
        out.printf("%s  dropped from the queue: its transcript %s never appeared%n", sessionId, transcript);
        Files.deleteIfExists(entry);
        Files.deleteIfExists(done);
        return;
      }
      // Asked for and not there: it stays waiting, where `ccrec doctor` shows it, rather than pass
      // for recorded.
      throw new NoSuchFileException(transcript.toString(), null, "the transcript to record is not there");
    }
    Ingester ingester = ingester(store, queued.path("host_id").asText("unknown-host"));
    report(ingester.ingestSession(transcript, sessionId, account(queued.path("account"))));
    if (queued.path("ended").asBoolean(false)) {
      Files.deleteIfExists(entry);
      Files.deleteIfExists(done);
    } else {
      // The mark of success `ccrec doctor` and the next run compare the hook's requests against. The
      // time is written into it: a file system may keep modification times to the second only.
      Files.writeString(done, started.toString());
    }
  }

  /** Whether the hook asked for this session to be ingested after its last successful ingest. */
  private static boolean waiting(JsonNode queued, Path done) throws IOException {
    String requested = queued.path("ingest_requested_at").asText("");
    if (requested.isEmpty()) {
      return false;
    }
    if (!Files.exists(done)) {
      return true;
    }
    try {
      return Instant.parse(requested).isAfter(recordedAt(done));
    } catch (DateTimeParseException e) {
      return true;
    }
  }

  /** When the ingest that left this mark started; a mark left by an earlier version says it by its age alone. */
  private static Instant recordedAt(Path done) throws IOException {
    try {
      return Instant.parse(Files.readString(done).strip());
    } catch (DateTimeParseException e) {
      return Files.getLastModifiedTime(done).toInstant();
    }
  }

  /** Ingests transcript files or directories directly, under the caller's identity. */
  private int importTranscripts() throws IOException {
    if (positional.isEmpty()) {
      throw new UsageException("import needs at least one transcript file or directory");
    }
    JsonNode identity = identity();
    List<Path> transcripts = new ArrayList<>();
    for (String argument : positional) {
      Path path = Path.of(argument);
      if (Files.isDirectory(path)) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path, "*.jsonl")) {
          files.forEach(transcripts::add);
        }
      } else if (Files.isRegularFile(path)) {
        transcripts.add(path);
      } else {
        throw new UsageException("no such transcript: " + argument);
      }
    }
    int failed = 0;
    try (FileChannel lockChannel = openLock();
        FileLock lock = acquire(lockChannel)) {
      if (lock == null) {
        throw new IllegalStateException("another ccrec process is writing; try again");
      }
      try (RecordStore store = openStore()) {
        Ingester ingester = ingester(store, identity.path("host_id").asText("unknown-host"));
        Account account = account(identity.path("account"));
        for (Path transcript : transcripts) {
          try {
            if (optedOut(transcript)) {
              out.printf("%s  skipped: its project carries .ccrec-ignore%n", transcript.getFileName());
              continue;
            }
            String name = transcript.getFileName().toString();
            String sessionId = Account.keySafe(name.substring(0, name.length() - ".jsonl".length()));
            report(ingester.ingestSession(transcript, sessionId, account));
          } catch (IOException | RuntimeException e) {
            failed++;
            printError(transcript + " was not imported", e);
          }
        }
      }
    }
    return failed == 0 ? 0 : 1;
  }

  /**
   * Whether the directory the session started in, or one above it, carries {@code .ccrec-ignore} —
   * the same opt-out the hook honours.
   */
  private static boolean optedOut(Path transcript) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(transcript, StandardCharsets.UTF_8)) {
      String raw;
      // The working directory is on the first conversation line; bookkeeping lines come before it.
      for (int read = 0; read < 200 && (raw = reader.readLine()) != null; read++) {
        JsonNode cwd;
        try {
          cwd = raw.isBlank() ? null : JSON.readTree(raw).path("cwd");
        } catch (JsonProcessingException e) {
          continue;
        }
        if (cwd == null || !cwd.isTextual()) {
          continue;
        }
        for (Path dir = Path.of(cwd.asText()); dir != null; dir = dir.getParent()) {
          if (Files.exists(dir.resolve(".ccrec-ignore"))) {
            return true;
          }
        }
        return false;
      }
    }
    return false;
  }

  /**
   * Deletes recorded sessions, and keeps the hooks from recording them again: a session still open
   * would otherwise be back, in part, at its next response.
   */
  private int delete() throws IOException {
    if (positional.isEmpty()) {
      throw new UsageException("delete needs at least one session id");
    }
    String accountId =
        options.containsKey("account") ? options.get("account") : account(identity().path("account")).accountId();
    // Where this machine's ingest positions are, for a session that has no row to say.
    String hostId = System.getenv("CCREC_IDENTITY_JSON") == null ? null : text(identity(), "host_id");
    int missing = 0;
    try (FileChannel lockChannel = openLock();
        FileLock lock = acquire(lockChannel)) {
      if (lock == null) {
        throw new IllegalStateException("another ccrec process is writing; try again");
      }
      try (RecordStore store = openStore()) {
        for (String sessionId : positional) {
          RecordStore.Deleted deleted = store.deleteSession(sessionId, accountId, hostId);
          if (!deleted.anything()) {
            missing++;
            out.printf("%s  not recorded%n", sessionId);
            continue;
          }
          if (sessionId.matches("[A-Za-z0-9_-]+")) {
            Path spool = Files.createDirectories(home.resolve("spool"));
            Files.deleteIfExists(spool.resolve(sessionId + ".json"));
            Files.deleteIfExists(spool.resolve(sessionId + ".done"));
            Path ignored = spool.resolve(sessionId + ".ignored");
            if (!Files.exists(ignored)) {
              Files.createFile(ignored);
            }
          }
          out.printf(
              "%s  deleted  records=%d  contents=%d  contents kept for other sessions=%d%n",
              sessionId, deleted.messages(), deleted.contents(), deleted.sharedContents());
        }
      }
    }
    return missing == 0 ? 0 : 1;
  }

  private void report(Ingester.Summary summary) {
    out.printf(
        "%s  files=%d  new lines=%d  new records=%d%n",
        summary.sessionId(), summary.files(), summary.lines(), summary.messages());
  }

  private Ingester ingester(RecordStore store, String hostId) throws IOException {
    JsonNode settings = settings();
    return new Ingester(
        store,
        settings.path("redact").asBoolean(true) ? Redactor.standard() : Redactor.NONE,
        Syncer.NONE,
        settings.path("recordThinking").asBoolean(true),
        hostId);
  }

  // ---- read commands --------------------------------------------------------------------------

  private int sessions() throws IOException {
    int limit = Integer.parseInt(options.getOrDefault("limit", "30"));
    List<SessionRecord> sessions;
    try (RecordStore store = openStore()) {
      if (options.containsKey("day")) {
        String org = options.containsKey("org") ? options.get("org") : account(identity().path("account")).orgId();
        sessions = store.sessionsByDay(org, Integer.parseInt(options.get("day").replace("-", "")), limit);
      } else {
        String accountId =
            options.containsKey("account")
                ? options.get("account")
                : account(identity().path("account")).accountId();
        sessions = store.sessions(accountId, limit);
      }
    }
    if (options.containsKey("json")) {
      ArrayNode array = JSON.createArrayNode();
      sessions.forEach(session -> array.add(JSON.valueToTree(session)));
      out.println(array.toPrettyString());
      return 0;
    }
    for (SessionRecord s : sessions) {
      out.printf(
          "%s  %s  %s  %s%n",
          TIME.format(Instant.ofEpochMilli(s.startedAt())),
          s.sessionId(),
          s.accountId(),
          s.title() != null ? s.title() : s.projectPath() != null ? s.projectPath() : "");
    }
    return 0;
  }

  private int show() throws IOException {
    if (positional.size() != 1) {
      throw new UsageException("show needs exactly one session id");
    }
    Set<String> kinds =
        options.containsKey("kind") ? Set.copyOf(Arrays.asList(options.get("kind").split(","))) : null;
    boolean full = options.containsKey("full");
    boolean json = options.containsKey("json");
    ArrayNode array = JSON.createArrayNode();
    try (RecordStore store = openStore()) {
      for (MessageRecord m : store.messages(positional.get(0))) {
        if (kinds != null && !kinds.contains(m.kind())) {
          continue;
        }
        String body = full ? store.content(m.contentHash()).orElse(m.preview()) : m.preview();
        if (json) {
          ObjectNode node = JSON.valueToTree(m);
          if (full) {
            node.put("content", body);
          }
          array.add(node);
          continue;
        }
        out.printf(
            "--- [%s] %s%s%s  line %d.%d  %s  %d bytes%n",
            m.agentId(),
            m.kind(),
            m.subtype() != null ? "/" + m.subtype() : "",
            m.toolName() != null ? " " + m.toolName() : "",
            m.lineNo(),
            m.blockNo(),
            m.ts() != null ? TIME.format(Instant.ofEpochMilli(m.ts())) : "-",
            m.contentBytes());
        out.println(full || body.length() <= 400 ? body : body.substring(0, 400) + " …");
      }
    }
    if (json) {
      out.println(array.toPrettyString());
    }
    return 0;
  }

  // ---- plumbing -------------------------------------------------------------------------------

  private RecordStore openStore() throws IOException {
    if (!Files.isRegularFile(config)) {
      throw new UsageException("no ScalarDB configuration at " + config);
    }
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(config)) {
      properties.load(in);
    }
    return new ScalarDbRecordStore(properties);
  }

  private JsonNode settings() throws IOException {
    Path file = home.resolve("config.json");
    return Files.isRegularFile(file) ? JSON.readTree(Files.readString(file)) : JSON.createObjectNode();
  }

  /** The caller's identity, resolved by the launcher: {@code {"host_id": …, "account": {…}}}. */
  private static JsonNode identity() throws IOException {
    String value = System.getenv("CCREC_IDENTITY_JSON");
    if (value == null || value.isBlank()) {
      throw new UsageException("CCREC_IDENTITY_JSON is not set; run this through the ccrec launcher");
    }
    return JSON.readTree(value);
  }

  private static Account account(JsonNode node) {
    String id = node.path("account_id").asText("");
    if (id.isEmpty()) {
      throw new UsageException("the identity has no account_id");
    }
    return new Account(
        id,
        text(node, "email"),
        text(node, "display_name"),
        text(node, "org_id"),
        text(node, "org_name"),
        text(node, "auth_method"));
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).asText("");
    return value.isEmpty() ? null : value;
  }

  private FileChannel openLock() throws IOException {
    Files.createDirectories(home);
    return FileChannel.open(
        home.resolve("ingest.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
  }

  /** SQLite takes one writer, so writers queue here rather than colliding inside the database. */
  private static FileLock acquire(FileChannel channel) throws IOException {
    long deadline = System.currentTimeMillis() + LOCK_WAIT_MILLIS;
    while (true) {
      FileLock lock = channel.tryLock();
      if (lock != null || System.currentTimeMillis() >= deadline) {
        return lock;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
  }

  static final class UsageException extends RuntimeException {
    UsageException(String message) {
      super(message);
    }
  }
}
