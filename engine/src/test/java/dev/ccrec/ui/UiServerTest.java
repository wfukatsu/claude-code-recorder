package dev.ccrec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ccrec.ingest.Ingester;
import dev.ccrec.model.Account;
import dev.ccrec.redact.Redactor;
import dev.ccrec.store.RecordStore;
import dev.ccrec.store.ScalarDbRecordStore;
import dev.ccrec.sync.Syncer;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The UI's server over a real store on SQLite, spoken to over HTTP as a browser would. */
class UiServerTest {

  private static final String TOKEN = "0123456789abcdef0123456789abcdef";
  private static final String SESSION = "11111111-2222-3333-4444-555555555555";
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private static RecordStore store(Path dir) throws IOException {
    Properties properties = new Properties();
    properties.setProperty("scalar.db.storage", "jdbc");
    properties.setProperty(
        "scalar.db.contact_points", "jdbc:sqlite:" + dir.resolve("test.sqlite3") + "?busy_timeout=10000");
    properties.setProperty("scalar.db.username", "");
    properties.setProperty("scalar.db.password", "");
    properties.setProperty("scalar.db.transaction_manager", "consensus-commit");
    RecordStore store = new ScalarDbRecordStore(properties);

    String longOutput = "line of output\\n".repeat(200);
    Path transcript = dir.resolve(SESSION + ".jsonl");
    Files.writeString(
        transcript,
        String.join(
                "\n",
                "{\"type\":\"user\",\"timestamp\":\"2026-10-07T01:00:01.000Z\",\"cwd\":\"/work/app\",\"promptSource\":\"typed\","
                    + "\"message\":{\"role\":\"user\",\"content\":\"<script>alert(1)</script> list the files\"}}",
                "{\"type\":\"assistant\",\"timestamp\":\"2026-10-07T01:00:05.000Z\",\"message\":{\"id\":\"m1\","
                    + "\"model\":\"claude-opus-5-5\",\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":10,"
                    + "\"output_tokens\":20},\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\","
                    + "\"input\":{\"command\":\"ls\"}}]}}",
                "{\"type\":\"user\",\"timestamp\":\"2026-10-07T01:00:06.000Z\",\"message\":{\"role\":\"user\",\"content\":["
                    + "{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"" + longOutput + "\"}]}}",
                "{\"type\":\"attachment\",\"timestamp\":\"2026-10-07T01:00:07.000Z\",\"attachment\":{\"type\":\"hook_success\"}}",
                "{\"type\":\"ai-title\",\"aiTitle\":\"Listing files\"}")
            + "\n");
    Ingester ingester = new Ingester(store, Redactor.standard(), Syncer.NONE, true, "host-1");
    ingester.ingestSession(transcript, SESSION, new Account("acct-alice", "alice@example.com", "Alice", "org-1", null, "oauth"));
    Path other = dir.resolve("99999999-2222-3333-4444-555555555555.jsonl");
    Files.writeString(
        other,
        "{\"type\":\"user\",\"timestamp\":\"2026-10-06T01:00:01.000Z\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}\n");
    ingester.ingestSession(
        other, "99999999-2222-3333-4444-555555555555", new Account("acct-bob", null, "Bob", "org-1", null, "env"));
    return store;
  }

  private HttpResponse<String> get(UiServer server, String path, boolean withCookie) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
    if (withCookie) {
      request.header("Cookie", "other=1; ccrec_ui_" + server.port() + "=" + TOKEN);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private JsonNode json(UiServer server, String path) throws Exception {
    HttpResponse<String> response = get(server, path, true);
    assertEquals(200, response.statusCode(), path + ": " + response.body());
    assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    return JSON.readTree(response.body());
  }

  @Test
  void nothingIsServedWithoutTheTokenOfTheRunOrUnderAnotherName(@TempDir Path dir) throws Exception {
    try (RecordStore store = store(dir);
        UiServer server = new UiServer(store, "acct-alice", 0, TOKEN)) {
      server.start();
      assertEquals(401, get(server, "/", false).statusCode());
      assertEquals(401, get(server, "/api/accounts", false).statusCode());
      assertEquals(401, get(server, "/app.js", false).statusCode());
      assertEquals(401, get(server, "/?token=" + "f".repeat(32), false).statusCode());

      // The address the launcher opens trades the token for a cookie and drops it from the URL.
      HttpResponse<String> first = get(server, "/?token=" + TOKEN, false);
      assertEquals(302, first.statusCode());
      assertEquals("/", first.headers().firstValue("Location").orElseThrow());
      String cookie = first.headers().firstValue("Set-Cookie").orElseThrow();
      assertTrue(cookie.startsWith("ccrec_ui_" + server.port() + "=" + TOKEN + ";"), cookie);
      assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);

      HttpResponse<String> page = get(server, "/", true);
      assertEquals(200, page.statusCode());
      assertTrue(page.body().contains("/app.js"));
      assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
      assertEquals("nosniff", page.headers().firstValue("X-Content-Type-Options").orElseThrow());
      assertEquals(200, get(server, "/app.css", true).statusCode());
      assertEquals(200, get(server, "/render.js", true).statusCode());
      assertEquals(404, get(server, "/app.js.map", true).statusCode());
      assertEquals(404, get(server, "/api/nothing", true).statusCode());

      // A page elsewhere that points a name of its own at 127.0.0.1 arrives with that name as Host.
      assertTrue(raw(server, "GET /api/accounts HTTP/1.1", "evil.example:" + server.port()).startsWith("HTTP/1.1 403"));
      assertTrue(raw(server, "GET /api/accounts HTTP/1.1", "localhost:" + server.port()).startsWith("HTTP/1.1 200"));
      assertTrue(raw(server, "GET /../../etc/passwd HTTP/1.1", "127.0.0.1:" + server.port()).startsWith("HTTP/1.1 404"));
      assertTrue(raw(server, "POST /api/accounts HTTP/1.1", "127.0.0.1:" + server.port()).startsWith("HTTP/1.1 405"));
    }
  }

  /** A request the way no HTTP client library lets it be written: any Host, any path. */
  private static String raw(UiServer server, String requestLine, String host) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket
          .getOutputStream()
          .write(
              (requestLine + "\r\nHost: " + host + "\r\nCookie: ccrec_ui_" + server.port() + "=" + TOKEN
                      + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void theApiServesAccountsSessionsRecordsAndContent(@TempDir Path dir) throws Exception {
    try (RecordStore store = store(dir);
        UiServer server = new UiServer(store, "acct-alice", 0, TOKEN)) {
      server.start();

      JsonNode accounts = json(server, "/api/accounts");
      assertEquals("acct-alice", accounts.path("current").asText());
      assertEquals(2, accounts.path("accounts").size(), "every account in the store, not the caller's alone");
      assertEquals("alice@example.com", accounts.path("accounts").get(0).path("email").asText());

      JsonNode sessions = json(server, "/api/sessions");
      assertEquals(1, sessions.size(), "the caller's own by default");
      assertEquals(SESSION, sessions.get(0).path("sessionId").asText());
      assertEquals("Listing files", sessions.get(0).path("title").asText());
      assertEquals(1, sessions.get(0).path("usage").path("messages").asInt());
      assertEquals(20, sessions.get(0).path("usage").path("outputTokens").asInt());
      assertEquals("claude-opus-5-5", sessions.get(0).path("models").get(0).asText());
      assertEquals(20, sessions.get(0).path("usageByModel").get(0).path("outputTokens").asInt());
      assertEquals(1, json(server, "/api/sessions?account=acct-bob").size());
      assertEquals(0, json(server, "/api/sessions?account=nobody").size());

      JsonNode summary = json(server, "/api/sessions/" + SESSION);
      assertEquals(1, summary.path("prompts").asInt());
      assertEquals("Bash", summary.path("tools").get(0).path("tool").asText());
      assertEquals(1, summary.path("kinds").path("tool_result").asInt());
      assertEquals("main", summary.path("agents").get(0).asText());
      assertEquals(404, get(server, "/api/sessions/never-recorded", true).statusCode());

      JsonNode all = json(server, "/api/sessions/" + SESSION + "/records");
      assertEquals(4, all.path("total").asInt());
      assertEquals("user_prompt", all.path("records").get(0).path("kind").asText());
      assertTrue(all.path("records").get(0).path("whole").asBoolean());
      assertEquals(
          "<script>alert(1)</script> list the files",
          all.path("records").get(0).path("text").asText(),
          "text goes out as it was recorded; the page puts it in as text");
      assertEquals("typed", all.path("records").get(0).path("attributes").path("prompt_source").asText());

      JsonNode newestFirst = json(server, "/api/sessions/" + SESSION + "/records?order=desc&limit=2");
      assertEquals(4, newestFirst.path("total").asInt());
      assertEquals(
          List.of("context", "tool_result"),
          List.of(newestFirst.path("records").get(0).path("kind").asText(), newestFirst.path("records").get(1).path("kind").asText()),
          "from the last record back");
      assertEquals(
          "user_prompt",
          json(server, "/api/sessions/" + SESSION + "/records?order=desc&offset=3").path("records").get(0).path("kind").asText());
      assertEquals(0, json(server, "/api/sessions/" + SESSION + "/records?offset=99").path("records").size());

      JsonNode tools = json(server, "/api/sessions/" + SESSION + "/records?kinds=tool_use,tool_result&offset=1&limit=5");
      assertEquals(2, tools.path("total").asInt(), "of the kinds asked for");
      assertEquals(1, tools.path("records").size(), "from the offset on");
      JsonNode result = tools.path("records").get(0);
      assertEquals("tool_result", result.path("kind").asText());
      assertEquals("t1", result.path("toolUseId").asText());
      assertEquals(300, result.path("text").asText().length(), "only the beginning of a long text");
      assertFalse(result.path("whole").asBoolean());

      String content = json(server, "/api/content/" + result.path("contentHash").asText()).path("text").asText();
      assertEquals(3000, content.length());
      assertEquals(404, get(server, "/api/content/" + "0".repeat(64), true).statusCode());
      assertEquals(404, get(server, "/api/content/not-a-hash", true).statusCode());
    }
  }

  @Test
  void theStatusSaysWhatTheHooksHoldAndWhereItRecordsWithoutTheWayIn(@TempDir Path dir) throws Exception {
    Path home = Files.createDirectories(dir.resolve("home"));
    Path spool = Files.createDirectories(home.resolve("spool"));
    Files.createDirectories(home.resolve("logs"));
    Files.writeString(home.resolve("config.json"), "{\"recordThinking\":true,\"redact\":true,\"exclude\":[\"context/hook_success\"]}");
    Files.writeString(home.resolve("logs").resolve("ingest.log"), "2026-10-08T05:10:30.561Z Stop s1\ns1  files=1  new lines=23  new records=15\n");
    Path config = home.resolve("database.properties");
    Files.writeString(
        config,
        "scalar.db.storage=jdbc\nscalar.db.contact_points=jdbc:postgresql://app:hunter2@db.example.com/ccrec?password=hunter2&ssl=true\n"
            + "scalar.db.password=hunter2\n");
    // One session recorded since it was last asked for, one still waiting, one entry that cannot be read.
    Files.writeString(
        spool.resolve("s1.json"),
        "{\"session_id\":\"s1\",\"last_event\":\"SubagentStop\",\"updated_at\":\"2026-10-08T05:10:31.000Z\","
            + "\"ingest_requested_at\":\"2026-10-08T05:10:30.000Z\",\"ended\":false}");
    Files.writeString(spool.resolve("s1.done"), "2026-10-08T05:10:30.500Z");
    Files.writeString(
        spool.resolve("s2.json"),
        "{\"session_id\":\"s2\",\"last_event\":\"Stop\",\"updated_at\":\"2026-10-08T06:00:00.000Z\","
            + "\"ingest_requested_at\":\"2026-10-08T06:00:00.000Z\",\"ended\":false}");
    Files.writeString(spool.resolve("s3.json"), "{broken");
    Files.writeString(spool.resolve("s4.json.bad"), "{broken");

    JsonNode launcher = JSON.readTree("{\"version\":\"0.1.0\",\"hooksInstalled\":true}");
    try (RecordStore store = store(dir);
        UiServer server = new UiServer(store, "acct-alice", 0, TOKEN, home, config, launcher)) {
      server.start();
      HttpResponse<String> response = get(server, "/api/status", true);
      assertEquals(200, response.statusCode());
      assertFalse(response.body().contains("hunter2"), "the password is nowhere in it");
      JsonNode status = JSON.readTree(response.body());

      assertTrue(status.path("launcher").path("hooksInstalled").asBoolean());
      assertEquals(2, status.path("accounts").asInt());
      assertEquals("jdbc", status.path("database").path("storage").asText());
      assertTrue(status.path("database").path("contactPoints").asText().contains("db.example.com/ccrec"));
      assertEquals("context/hook_success", status.path("settings").path("exclude").get(0).asText());
      assertEquals(2, status.path("ingestLog").size());

      JsonNode queue = status.path("queue");
      assertEquals(1, queue.path("waiting").asInt());
      assertEquals("2026-10-08T06:00:00.000Z", queue.path("oldestWaiting").asText());
      assertEquals(2, queue.path("unreadable").asInt());
      assertEquals("s2", queue.path("entries").get(0).path("sessionId").asText(), "the latest first");
      assertTrue(queue.path("entries").get(0).path("waiting").asBoolean());
      assertEquals("SubagentStop", queue.path("entries").get(1).path("lastEvent").asText());
      assertFalse(queue.path("entries").get(1).path("waiting").asBoolean());
    }
  }

  private HttpResponse<String> post(UiServer server, String body, String... headers) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/sessions/delete"))
            .header("Cookie", "ccrec_ui_" + server.port() + "=" + TOKEN)
            .POST(HttpRequest.BodyPublishers.ofString(body));
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  @Test
  void aSessionIsDeletedOnlyByARequestThePageItselfCanMake(@TempDir Path dir) throws Exception {
    Path home = Files.createDirectories(dir.resolve("home"));
    Path spool = Files.createDirectories(home.resolve("spool"));
    Files.writeString(spool.resolve(SESSION + ".json"), "{\"session_id\":\"" + SESSION + "\"}");
    String body = "{\"sessionIds\":[\"" + SESSION + "\",\"never-recorded\"]}";
    try (RecordStore store = store(dir);
        UiServer server = new UiServer(store, "acct-alice", 0, TOKEN, home, null, JSON.createObjectNode())) {
      server.start();
      String json = "application/json";

      // What a form on another site can send, token cookie and all, is refused.
      assertEquals(403, post(server, body, "Content-Type", "text/plain").statusCode());
      assertEquals(403, post(server, body, "Content-Type", json).statusCode(), "without the header of ours");
      assertEquals(
          403, post(server, body, "Content-Type", json, "X-Ccrec-Ui", "1", "Origin", "https://evil.example").statusCode());
      assertEquals(400, post(server, "{\"sessionIds\":[]}", "Content-Type", json, "X-Ccrec-Ui", "1").statusCode());
      assertEquals(400, post(server, "not json", "Content-Type", json, "X-Ccrec-Ui", "1").statusCode());
      assertEquals(200, get(server, "/api/sessions/" + SESSION, true).statusCode(), "nothing was deleted so far");
      assertEquals(401, get(server, "/api/sessions/delete", false).statusCode());

      HttpResponse<String> done =
          post(server, body, "Content-Type", json, "X-Ccrec-Ui", "1", "Origin", "http://127.0.0.1:" + server.port());
      assertEquals(200, done.statusCode(), done.body());
      JsonNode deleted = JSON.readTree(done.body()).path("deleted");
      assertTrue(deleted.get(0).path("found").asBoolean());
      assertEquals(4, deleted.get(0).path("records").asInt());
      assertFalse(deleted.get(1).path("found").asBoolean());

      assertEquals(404, get(server, "/api/sessions/" + SESSION, true).statusCode());
      assertEquals(0, json(server, "/api/sessions").size());
      assertEquals(1, json(server, "/api/sessions?account=acct-bob").size(), "another account's session is untouched");
      assertFalse(Files.exists(spool.resolve(SESSION + ".json")));
      assertTrue(Files.exists(spool.resolve(SESSION + ".ignored")), "the hooks are told to leave it alone");
      assertFalse(Files.exists(spool.resolve("never-recorded.ignored")));
    }
  }
}
