package dev.ccrec.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ccrec.cli.IngestLock;
import dev.ccrec.cli.SessionSummary;
import dev.ccrec.cli.SpoolQueue;
import dev.ccrec.model.Account;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import dev.ccrec.model.UsageRecord;
import dev.ccrec.net.NetworkUse;
import dev.ccrec.store.RecordStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The browser UI: a few static files and a JSON API over a {@link RecordStore}, for the person at
 * this machine only.
 *
 * <p>It reads, with one exception: sessions can be deleted from it, as from the command line.
 *
 * <p>What is recorded includes source code and whatever credentials slipped past the redactor, and
 * a browser is where other people's pages run. So the server listens on the loopback address alone,
 * answers only to the {@code Host} it was started as (a page elsewhere cannot reach it by pointing a
 * name of its own at 127.0.0.1), and wants the token of this run on every request: handed over once
 * in the URL the launcher opens, kept from then on in a cookie no other site's request carries.
 */
public final class UiServer implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int PAGE_RECORDS = 500;
  private static final int MAX_SESSIONS = 1000;
  /** What a list row shows of a record's text; the whole text is fetched when the row is opened. */
  private static final int LISTED_CHARS = 300;
  private static final int LOG_LINES = 60;
  private static final int MAX_BODY_BYTES = 64 * 1024;
  private static final int MAX_DELETED_AT_ONCE = 50;
  /** An ingest takes seconds; a click should not wait for a minute as a hook's ingest would. */
  private static final long LOCK_WAIT_MILLIS = 10_000;

  private static final Map<String, String> STATIC =
      Map.of(
          "/", "index.html",
          "/app.js", "app.js",
          "/render.js", "render.js",
          "/app.css", "app.css");
  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "html", "text/html; charset=utf-8",
          "js", "text/javascript; charset=utf-8",
          "css", "text/css; charset=utf-8");

  private final RecordStore store;
  private final String currentAccountId;
  private final String token;
  private final Path home;
  private final Path config;
  private final JsonNode launcher;
  private final HttpServer server;
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final Map<String, Optional<NetworkUse.Use>> networkUses = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * @param currentAccountId the account of whoever started the UI, which it opens on; may be null
   * @param port the port to listen on, or 0 for any free one
   */
  public UiServer(RecordStore store, String currentAccountId, int port, String token) throws IOException {
    this(store, currentAccountId, port, token, null, null, JSON.createObjectNode());
  }

  /**
   * @param home the recorder's home, whose queue, log and settings the status page shows; may be null
   * @param config the ScalarDB configuration in use; may be null
   * @param launcher what the launcher says of itself: its version, whether the hooks are installed
   */
  public UiServer(
      RecordStore store, String currentAccountId, int port, String token, Path home, Path config, JsonNode launcher)
      throws IOException {
    this.store = store;
    this.currentAccountId = currentAccountId;
    this.token = token;
    this.home = home;
    this.config = config;
    this.launcher = launcher;
    this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
    server.createContext("/", this::handle);
    server.setExecutor(workers);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  /** The address to open: it carries the token, once. */
  public String url() {
    return "http://127.0.0.1:" + port() + "/?token=" + token;
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
  }

  private String cookieName() {
    return "ccrec_ui_" + port();
  }

  // ---- every request --------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange
          .getResponseHeaders()
          .set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'");
      String host = exchange.getRequestHeaders().getFirst("Host");
      if (!Set.of("127.0.0.1:" + port(), "localhost:" + port()).contains(host)) {
        text(exchange, 403, "ccrec ui answers only as 127.0.0.1:" + port());
        return;
      }
      String path = exchange.getRequestURI().getPath();
      Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
      if (path.equals("/") && same(query.get("token"))) {
        // The token leaves the address bar, and the history, for a cookie only this site is sent.
        exchange
            .getResponseHeaders()
            .set("Set-Cookie", cookieName() + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(302, -1);
        return;
      }
      if (!same(cookie(exchange))) {
        text(exchange, 401, "Open the address that \"ccrec ui\" printed: it carries this run's token.");
        return;
      }
      if (exchange.getRequestMethod().equals("POST") && path.equals("/api/sessions/delete")) {
        delete(exchange);
        return;
      }
      if (!exchange.getRequestMethod().equals("GET")) {
        text(exchange, 405, "GET only");
        return;
      }
      if (STATIC.containsKey(path)) {
        resource(exchange, STATIC.get(path));
      } else if (path.startsWith("/api/")) {
        api(exchange, path.substring("/api/".length()), query);
      } else {
        text(exchange, 404, "not found");
      }
    } catch (RuntimeException e) {
      System.err.println("ccrec ui: " + exchange.getRequestURI().getPath() + ": " + e);
      try {
        json(exchange, 500, JSON.createObjectNode().put("error", String.valueOf(e.getMessage())));
      } catch (IOException | RuntimeException alreadyAnswered) {
        // The response had begun; the connection closes on it.
      }
    }
  }

  private boolean same(String given) {
    return given != null
        && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
  }

  private String cookie(HttpExchange exchange) {
    for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
      for (String pair : header.split(";")) {
        String[] nameValue = pair.strip().split("=", 2);
        if (nameValue.length == 2 && nameValue[0].equals(cookieName())) {
          return nameValue[1];
        }
      }
    }
    return null;
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> query = new HashMap<>();
    if (raw != null) {
      for (String pair : raw.split("&")) {
        String[] nameValue = pair.split("=", 2);
        query.put(decode(nameValue[0]), nameValue.length == 2 ? decode(nameValue[1]) : "");
      }
    }
    return query;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  // ---- the one request that changes anything ------------------------------------------------

  /**
   * Deletes the sessions named in the body, as {@code ccrec delete} does. Beyond the token, the
   * request has to be one only this page's script can make: JSON, with a header of ours, from our
   * own origin — a form on another site can send none of the three.
   */
  private void delete(HttpExchange exchange) throws IOException {
    String origin = exchange.getRequestHeaders().getFirst("Origin");
    String contentType = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
    if (!"1".equals(exchange.getRequestHeaders().getFirst("X-Ccrec-Ui"))
        || !contentType.startsWith("application/json")
        || origin != null && !Set.of("http://127.0.0.1:" + port(), "http://localhost:" + port()).contains(origin)) {
      json(exchange, 403, JSON.createObjectNode().put("error", "not a request from the ccrec ui page"));
      return;
    }
    if (home == null) {
      json(exchange, 409, JSON.createObjectNode().put("error", "this server was started without a home to lock"));
      return;
    }
    JsonNode body;
    try {
      body = JSON.readTree(exchange.getRequestBody().readNBytes(MAX_BODY_BYTES));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      body = null;
    }
    List<String> sessionIds = new java.util.ArrayList<>();
    if (body != null) {
      body.path("sessionIds").forEach(id -> sessionIds.add(id.asText()));
    }
    if (sessionIds.isEmpty() || sessionIds.size() > MAX_DELETED_AT_ONCE || sessionIds.stream().anyMatch(String::isBlank)) {
      json(exchange, 400, JSON.createObjectNode().put("error", "sessionIds: 1 to " + MAX_DELETED_AT_ONCE + " session ids"));
      return;
    }
    ObjectNode answer = JSON.createObjectNode();
    ArrayNode deleted = answer.putArray("deleted");
    // One deletion at a time in this process; the file lock keeps the other processes out.
    synchronized (this) {
      try (java.nio.channels.FileChannel channel = IngestLock.open(home);
          java.nio.channels.FileLock lock = IngestLock.acquire(channel, LOCK_WAIT_MILLIS)) {
        if (lock == null) {
          json(exchange, 409, JSON.createObjectNode().put("error", "another ccrec process is writing; try again"));
          return;
        }
        store.buildUsageIfMissing();
        String hostId = launcher.path("hostId").isTextual() ? launcher.path("hostId").asText() : null;
        for (Map.Entry<String, RecordStore.Deleted> entry : store.deleteSessions(sessionIds, null, hostId).entrySet()) {
          ObjectNode node = deleted.addObject();
          node.put("sessionId", entry.getKey());
          node.put("found", entry.getValue().anything());
          node.put("records", entry.getValue().messages());
          node.put("contents", entry.getValue().contents());
          if (entry.getValue().anything()) {
            // Named, as on the command line: the hooks are told to leave it alone from here on.
            SpoolQueue.forget(home.resolve("spool"), entry.getKey(), true);
          }
        }
      }
    }
    json(exchange, 200, answer);
  }

  // ---- the API --------------------------------------------------------------------------------

  private void api(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
    String[] parts = Arrays.stream(path.split("/")).map(UiServer::decode).toArray(String[]::new);
    if (path.equals("accounts")) {
      json(exchange, 200, accounts());
    } else if (path.equals("status")) {
      json(exchange, 200, status());
    } else if (path.equals("sessions")) {
      json(exchange, 200, sessions(query.getOrDefault("account", currentAccountId), number(query, "limit", 200, MAX_SESSIONS)));
    } else if (parts.length == 2 && parts[0].equals("sessions")) {
      Optional<ObjectNode> summary = summary(parts[1]);
      if (summary.isPresent()) {
        json(exchange, 200, summary.get());
      } else {
        json(exchange, 404, JSON.createObjectNode().put("error", "not recorded"));
      }
    } else if (parts.length == 3 && parts[0].equals("sessions") && parts[2].equals("records")) {
      json(exchange, 200, records(parts[1], query));
    } else if (parts.length == 3 && parts[0].equals("sessions") && parts[2].equals("network")) {
      json(exchange, 200, network(parts[1]));
    } else if (parts.length == 2 && parts[0].equals("content") && parts[1].matches("[0-9a-f]{64}")) {
      Optional<String> content = store.content(parts[1]);
      if (content.isPresent()) {
        json(exchange, 200, JSON.createObjectNode().put("text", content.get()));
      } else {
        json(exchange, 404, JSON.createObjectNode().put("error", "no such content"));
      }
    } else {
      json(exchange, 404, JSON.createObjectNode().put("error", "no such resource"));
    }
  }

  private static int number(Map<String, String> query, String name, int otherwise, int most) {
    try {
      return Math.max(0, Math.min(most, Integer.parseInt(query.getOrDefault(name, String.valueOf(otherwise)))));
    } catch (NumberFormatException e) {
      return otherwise;
    }
  }

  private ObjectNode accounts() {
    ObjectNode node = JSON.createObjectNode();
    node.put("current", currentAccountId);
    ArrayNode accounts = node.putArray("accounts");
    for (Account account : store.accounts()) {
      accounts.add(JSON.valueToTree(account));
    }
    return node;
  }

  /** An account's sessions, newest first, each with what it spent in all. */
  private ArrayNode sessions(String accountId, int limit) {
    ArrayNode sessions = JSON.createArrayNode();
    if (accountId == null) {
      return sessions;
    }
    for (SessionRecord session : store.sessions(accountId, limit)) {
      ObjectNode node = JSON.valueToTree(session);
      long[] spent = new long[UsageRecord.COUNTERS];
      Set<String> models = new TreeSet<>();
      List<UsageRecord> byModel = store.usage(session.sessionId());
      node.set("usageByModel", JSON.valueToTree(byModel));
      for (UsageRecord usage : byModel) {
        long[] counters = usage.counters();
        for (int i = 0; i < spent.length; i++) {
          spent[i] += counters[i];
        }
        if (usage.messages() > 0 && usage.outputTokens() > 0) {
          models.add(usage.model());
        }
      }
      node.set("usage", JSON.valueToTree(UsageRecord.of(null, null, null, spent)));
      node.set("models", JSON.valueToTree(models));
      sessions.add(node);
    }
    return sessions;
  }

  /** What `ccrec doctor` checks, and what the hooks and the ingests have been doing. */
  private ObjectNode status() throws IOException {
    ObjectNode status = JSON.createObjectNode();
    status.set("launcher", launcher);
    status.put("java", System.getProperty("java.version"));
    status.put("accounts", store.accounts().size());
    if (config != null && Files.isRegularFile(config)) {
      java.util.Properties properties = new java.util.Properties();
      try (InputStream in = Files.newInputStream(config)) {
        properties.load(in);
      }
      ObjectNode database = status.putObject("database");
      database.put("config", config.toString());
      database.put("storage", properties.getProperty("scalar.db.storage"));
      // Where it records, without what it takes to get in: a JDBC URL can carry a password.
      database.put(
          "contactPoints",
          String.valueOf(properties.getProperty("scalar.db.contact_points")).replaceAll("(?i)(password=)[^&;]*", "$1…").replaceAll("://[^/@]*@", "://…@"));
    }
    if (home != null) {
      status.put("home", home.toString());
      status.set("queue", JSON.valueToTree(SpoolQueue.of(home.resolve("spool"))));
      Path settings = home.resolve("config.json");
      if (Files.isRegularFile(settings)) {
        try {
          status.set("settings", JSON.readTree(Files.readString(settings)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
          status.put("settingsError", String.valueOf(e.getOriginalMessage()));
        }
      }
      Path log = home.resolve("logs").resolve("ingest.log");
      if (Files.isRegularFile(log)) {
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        status.set("ingestLog", JSON.valueToTree(lines.subList(Math.max(0, lines.size() - LOG_LINES), lines.size())));
      }
    }
    return status;
  }

  private Optional<ObjectNode> summary(String sessionId) {
    Optional<SessionRecord> session = store.session(sessionId, currentAccountId);
    List<MessageRecord> messages = store.messages(sessionId);
    if (session.isEmpty() && messages.isEmpty()) {
      return Optional.empty();
    }
    ObjectNode summary = SessionSummary.of(sessionId, session, messages, store.usage(sessionId));
    Set<String> agents = new TreeSet<>();
    Map<String, Integer> kinds = new java.util.TreeMap<>();
    for (MessageRecord m : messages) {
      agents.add(m.agentId());
      kinds.merge(m.kind(), 1, Integer::sum);
    }
    // What the conversation view can filter by.
    summary.set("agents", JSON.valueToTree(agents));
    summary.set("kinds", JSON.valueToTree(kinds));
    return Optional.of(summary);
  }

  /**
   * A page of a session's records in source order — or, with {@code order=desc}, from the last one
   * back — optionally of some kinds and one agent only. The
   * text is its beginning; {@code whole} says whether that is all of it.
   */
  private ObjectNode records(String sessionId, Map<String, String> query) {
    Set<String> kinds = query.containsKey("kinds") ? Set.of(query.get("kinds").split(",")) : null;
    String agent = query.get("agent");
    int offset = number(query, "offset", 0, Integer.MAX_VALUE);
    int limit = number(query, "limit", PAGE_RECORDS, PAGE_RECORDS);
    List<MessageRecord> matching = new java.util.ArrayList<>();
    List<MessageRecord> all = store.messages(sessionId);
    if ("1".equals(query.get("network"))) {
      // Only the calls that reach beyond this machine and Claude, each with what came back.
      String host = query.get("host");
      Set<String> categories = query.containsKey("categories") ? Set.of(query.get("categories").split(",")) : null;
      Set<String> localMcp = localMcpServers();
      Set<String> calls = new java.util.HashSet<>();
      for (MessageRecord m : all) {
        if (m.kind().equals("tool_use") && (agent == null || agent.equals(m.agentId()))) {
          Optional<NetworkUse.Use> use = networkUse(m, localMcp);
          if (use.isPresent()
              && (host == null || use.get().hosts().contains(host))
              && (categories == null || categories.contains(use.get().category()))) {
            matching.add(m);
            if (m.toolUseId() != null) {
              calls.add(m.toolUseId());
            }
          }
        } else if ((m.kind().equals("tool_result") || m.kind().equals("mcp_meta")) && calls.contains(m.toolUseId())) {
          matching.add(m);
        }
      }
    } else {
      for (MessageRecord m : all) {
        if ((kinds == null || kinds.contains(m.kind())) && (agent == null || agent.equals(m.agentId()))) {
          matching.add(m);
        }
      }
    }
    // Newest first when asked: the end of a long session is then on the first page.
    if ("desc".equals(query.get("order"))) {
      java.util.Collections.reverse(matching);
    }
    ObjectNode page = JSON.createObjectNode();
    ArrayNode records = page.putArray("records");
    Set<String> localMcpServers = localMcpServers();
    for (MessageRecord m : matching.subList(Math.min(offset, matching.size()), (int) Math.min((long) offset + limit, matching.size()))) {
      ObjectNode node = records.addObject();
      node.put("agentId", m.agentId());
      node.put("lineNo", m.lineNo());
      node.put("blockNo", m.blockNo());
      node.put("kind", m.kind());
      node.put("subtype", m.subtype());
      node.put("ts", m.ts());
      node.put("model", m.model());
      node.put("toolName", m.toolName());
      node.put("toolUseId", m.toolUseId());
      node.put("contentHash", m.contentHash());
      node.put("contentBytes", m.contentBytes());
      String preview = m.preview() == null ? "" : m.preview();
      boolean whole = preview.length() <= LISTED_CHARS && m.contentBytes() <= preview.getBytes(StandardCharsets.UTF_8).length;
      node.put("text", preview.length() <= LISTED_CHARS ? preview : preview.substring(0, cut(preview)));
      node.put("whole", whole);
      node.put("inputTokens", m.inputTokens());
      node.put("outputTokens", m.outputTokens());
      node.put("cacheReadTokens", m.cacheReadTokens());
      node.put("cacheCreationTokens", m.cacheCreationTokens());
      JsonNode attributes = SessionSummary.attributes(m);
      if (!attributes.isEmpty()) {
        node.set("attributes", attributes);
      }
      if (m.kind().equals("tool_use")) {
        networkUse(m, localMcpServers).ifPresent(use -> node.set("network", JSON.valueToTree(use)));
      }
    }
    page.put("offset", offset);
    page.put("total", matching.size());
    return page;
  }

  // ---- what reaches beyond this machine ----------------------------------------------------

  /**
   * Whether a tool call reaches beyond this machine and Claude, and where to. A shell command or an
   * MCP input longer than what the record keeps at hand is read in full, once, and remembered.
   */
  private Optional<NetworkUse.Use> networkUse(MessageRecord m, Set<String> localMcpServers) {
    if (m.toolName() == null || m.contentHash() == null) {
      return Optional.empty();
    }
    String key = m.toolName() + "\n" + m.contentHash() + "\n" + localMcpServers.hashCode();
    Optional<NetworkUse.Use> known = networkUses.get(key);
    if (known != null) {
      return known;
    }
    String input = m.preview() == null ? "" : m.preview();
    boolean cutShort = m.contentBytes() > input.getBytes(StandardCharsets.UTF_8).length;
    if (cutShort && (m.toolName().equals("Bash") || m.toolName().startsWith("mcp__"))) {
      input = store.content(m.contentHash()).orElse(input);
    }
    Optional<NetworkUse.Use> use = NetworkUse.of(m.toolName(), input, localMcpServers);
    if (networkUses.size() > 50_000) {
      networkUses.clear();
    }
    networkUses.put(key, use);
    return use;
  }

  /** The MCP servers the settings say stay on this machine. */
  private Set<String> localMcpServers() {
    Set<String> servers = new java.util.HashSet<>();
    if (home != null && Files.isRegularFile(home.resolve("config.json"))) {
      try {
        JSON.readTree(Files.readString(home.resolve("config.json"))).path("localMcpServers").forEach(s -> servers.add(s.asText()));
      } catch (IOException e) {
        // Settings that cannot be read name no server.
      }
    }
    return servers;
  }

  /** Where a session's tool calls went, by destination: how often, how much was sent and came back. */
  private ObjectNode network(String sessionId) {
    Set<String> localMcp = localMcpServers();
    List<MessageRecord> all = store.messages(sessionId);
    Map<String, long[]> came = new HashMap<>();
    for (MessageRecord m : all) {
      if (m.kind().equals("tool_result") && m.toolUseId() != null) {
        came.put(m.toolUseId(), new long[] {m.contentBytes(), "error".equals(m.subtype()) ? 1 : 0});
      }
    }
    Map<String, ObjectNode> hosts = new java.util.LinkedHashMap<>();
    Map<String, Integer> categories = new java.util.TreeMap<>();
    int calls = 0;
    for (MessageRecord m : all) {
      if (!m.kind().equals("tool_use")) {
        continue;
      }
      Optional<NetworkUse.Use> use = networkUse(m, localMcp);
      if (use.isEmpty()) {
        continue;
      }
      calls++;
      categories.merge(use.get().category(), 1, Integer::sum);
      long[] back = came.getOrDefault(m.toolUseId(), new long[2]);
      for (String host : use.get().hosts()) {
        ObjectNode row = hosts.computeIfAbsent(host, h -> JSON.createObjectNode().put("host", h).put("category", use.get().category()));
        row.put("calls", row.path("calls").asLong() + 1);
        row.put("sentBytes", row.path("sentBytes").asLong() + m.contentBytes());
        row.put("receivedBytes", row.path("receivedBytes").asLong() + back[0]);
        row.put("errors", row.path("errors").asLong() + back[1]);
        if (m.ts() != null) {
          row.put("lastAt", Math.max(row.path("lastAt").asLong(), m.ts()));
        }
      }
    }
    ObjectNode node = JSON.createObjectNode();
    node.put("calls", calls);
    node.set("categories", JSON.valueToTree(categories));
    List<ObjectNode> rows = new java.util.ArrayList<>(hosts.values());
    rows.sort(java.util.Comparator.comparingLong((ObjectNode row) -> row.path("calls").asLong()).reversed());
    node.putArray("hosts").addAll(rows);
    return node;
  }

  private static int cut(String text) {
    return Character.isHighSurrogate(text.charAt(LISTED_CHARS - 1)) ? LISTED_CHARS - 1 : LISTED_CHARS;
  }

  // ---- answering ------------------------------------------------------------------------------

  private static void resource(HttpExchange exchange, String name) throws IOException {
    try (InputStream in = UiServer.class.getResourceAsStream("/ui/" + name)) {
      if (in == null) {
        text(exchange, 404, "not found");
        return;
      }
      send(exchange, 200, CONTENT_TYPES.get(name.substring(name.lastIndexOf('.') + 1)), in.readAllBytes());
    }
  }

  private static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
    send(exchange, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
  }

  private static void text(HttpExchange exchange, int status, String body) throws IOException {
    send(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }
}
