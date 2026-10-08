package dev.ccrec.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ccrec.cli.SessionSummary;
import dev.ccrec.cli.SpoolQueue;
import dev.ccrec.model.Account;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import dev.ccrec.model.UsageRecord;
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

  private static final Map<String, String> STATIC =
      Map.of(
          "/", "index.html",
          "/app.js", "app.js",
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
   * A page of a session's records in source order, optionally of some kinds and one agent only. The
   * text is its beginning; {@code whole} says whether that is all of it.
   */
  private ObjectNode records(String sessionId, Map<String, String> query) {
    Set<String> kinds = query.containsKey("kinds") ? Set.of(query.get("kinds").split(",")) : null;
    String agent = query.get("agent");
    int offset = number(query, "offset", 0, Integer.MAX_VALUE);
    int limit = number(query, "limit", PAGE_RECORDS, PAGE_RECORDS);
    ObjectNode page = JSON.createObjectNode();
    ArrayNode records = page.putArray("records");
    int matching = 0;
    for (MessageRecord m : store.messages(sessionId)) {
      if (kinds != null && !kinds.contains(m.kind()) || agent != null && !agent.equals(m.agentId())) {
        continue;
      }
      if (matching++ < offset || records.size() >= limit) {
        continue;
      }
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
    }
    page.put("offset", offset);
    page.put("total", matching);
    return page;
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
