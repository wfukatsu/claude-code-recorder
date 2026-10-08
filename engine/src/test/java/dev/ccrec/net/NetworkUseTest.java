package dev.ccrec.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ccrec.net.NetworkUse.Use;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NetworkUseTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Optional<Use> bash(String command) throws Exception {
    return NetworkUse.of("Bash", JSON.writeValueAsString(Map.of("command", command, "description", "x")), Set.of());
  }

  private static List<String> hosts(String command) throws Exception {
    return bash(command).map(Use::hosts).orElse(List.of());
  }

  @Test
  void aWebToolGoesWhereItsAddressSays() {
    Use fetch = NetworkUse.of("WebFetch", "{\"url\":\"https://docs.example.com/guide?x=1\",\"prompt\":\"summarise\"}", Set.of()).orElseThrow();
    assertEquals(NetworkUse.WEB, fetch.category());
    assertEquals(List.of("docs.example.com"), fetch.hosts());
    assertEquals("https://docs.example.com/guide?x=1", fetch.sent());

    Use search = NetworkUse.of("WebSearch", "{\"query\":\"scalardb sqlite\"}", Set.of()).orElseThrow();
    assertEquals(List.of("(web search)"), search.hosts());
    assertEquals("scalardb sqlite", search.sent());

    assertTrue(NetworkUse.of("WebFetch", "{\"url\":\"http://localhost:3000/health\"}", Set.of()).isEmpty());
    assertTrue(NetworkUse.of("WebFetch", "{\"url\":\"https://docs.claude.com/en/hooks\"}", Set.of()).isEmpty(), "Claude's own");
  }

  @Test
  void anMcpToolGoesToItsServerUnlessThatIsKnownToBeLocal() {
    Use drive = NetworkUse.of("mcp__claude_ai_Google_Drive__read_file_content", "{\"fileId\":\"abc\"}", Set.of()).orElseThrow();
    assertEquals(NetworkUse.MCP, drive.category());
    assertEquals(List.of("(MCP: claude_ai_Google_Drive)"), drive.hosts());
    assertTrue(drive.sent().startsWith("read_file_content "));

    assertTrue(NetworkUse.of("mcp__my_local__find", "{\"name\":\"x\"}", Set.of("my_local")).isEmpty(), "named in the settings");
    assertTrue(NetworkUse.of("mcp__claude-in-chrome__computer", "{\"action\":\"screenshot\"}", Set.of()).isEmpty(), "known to be local");
    assertTrue(NetworkUse.of("mcp__team_wiki__open", "{\"url\":\"http://localhost:8080/p\"}", Set.of()).isEmpty(), "sent to this machine");
    // A local server sent on to an address still reaches it.
    assertEquals(
        List.of("example.com"),
        NetworkUse.of("mcp__playwright__browser_navigate", "{\"url\":\"https://example.com/a\"}", Set.of()).orElseThrow().hosts());
    assertTrue(NetworkUse.of("mcp__playwright__browser_navigate", "{\"url\":\"http://127.0.0.1:4127/\"}", Set.of()).isEmpty());
  }

  @Test
  void aShellCommandIsReadForThePlacesItNamesAndTheProgramsItRuns() throws Exception {
    assertEquals(List.of("api.example.com"), hosts("curl -s -H 'X: 1' https://api.example.com/v1/items | jq ."));
    assertEquals(List.of("a.example.com", "b.example.org"), hosts("wget https://a.example.com/x && curl http://b.example.org:8080/y"));
    assertEquals(List.of("github.com"), hosts("gh pr create --title x --fill"));
    assertEquals(List.of("github.com"), hosts("cd repo && git clone https://github.com/org/repo.git"));
    assertEquals(List.of("(git remote)"), hosts("git push -q -u origin main"));
    assertEquals(List.of("(npm registry)"), hosts("npm install -g ./x.tgz 2>&1 | tail -1"));
    assertEquals(List.of("(PyPI)"), hosts("python3 -m venv v && v/bin/pip install requests"));
    assertEquals(List.of("build.example.com"), hosts("scp out.tgz deploy@build.example.com:/srv/"));
    assertEquals(List.of("(AWS)"), hosts("FOO=1 aws s3 ls"));
    assertEquals(List.of(NetworkUse.UNKNOWN_HOST), hosts("curl -s \"$URL\""));
    assertEquals(List.of("github.com", "api.example.com"), hosts("gh pr view 3 && curl -s \"$URL\" && curl https://api.example.com/x"));
    assertEquals(NetworkUse.SHELL, bash("gh run list").orElseThrow().category());
    assertEquals("git push origin main", bash("cd repo && ls\ngit push origin main\necho done").orElseThrow().sent());
  }

  @Test
  void whatStaysOnThisMachineIsNotCounted() throws Exception {
    for (String command :
        List.of(
            "git status --short && git log --oneline | head",
            "npm test 2>&1 | tail -5",
            "npm run build",
            "curl -s http://127.0.0.1:4127/api/status",
            "curl -s -b \"c=$T\" http://localhost:4188/api/sessions | python3 -c 'print(1)'",
            "ls -la && cat package.json",
            "echo 'see https://docs.claude.com/hooks'",
            "docker ps",
            "go build ./...",
            "grep -rn 'curl' src/",
            "grep -o 'http://[^ ]*' ui.log",
            // An address that is only written into a file, or said in a commit message, is not visited.
            "cat > notes.md <<'EOF'\nsee https://example.com/docs\ncurl https://example.com/x\nEOF\nnpm test",
            "python3 - <<'PY'\nimport json\nprint('http://www.w3.org/2000/svg')\nPY",
            "git add -A && git commit -q -m \"$(cat <<'EOF'\nfix: x\n\ngh pr create is run later; see https://example.com\nEOF\n)\"",
            "pkill -f \"Main ui --port 4188\"")) {
      assertTrue(bash(command).isEmpty(), command);
    }
    assertTrue(NetworkUse.of("Read", "{\"file_path\":\"/x/https://example.com\"}", Set.of()).isEmpty());
    assertTrue(NetworkUse.of("Edit", "{\"file_path\":\"a\",\"new_string\":\"curl https://example.com\"}", Set.of()).isEmpty());
    assertTrue(NetworkUse.of("Agent", "{\"prompt\":\"look around\"}", Set.of()).isEmpty());
  }

  @Test
  void localAndClaudeAddressesAreToldFromTheRest() {
    for (String local : List.of("localhost", "127.0.0.1", "10.1.2.3", "192.168.0.5", "172.20.0.1", "db", "app.local", "[::1]")) {
      assertTrue(NetworkUse.isLocal(local), local);
    }
    for (String remote : List.of("example.com", "172.32.0.1", "8.8.8.8", "[2001:db8::1]")) {
      assertTrue(!NetworkUse.isLocal(remote), remote);
    }
    assertTrue(NetworkUse.isClaude("api.anthropic.com") && NetworkUse.isClaude("claude.ai") && !NetworkUse.isClaude("notclaude.ai"));
    assertEquals(List.of("example.com"), NetworkUse.hostsIn("https://user:pw@example.com:8443/x, http://localhost/y, ${BASE}/z, http://[^ ]* and https://$HOST/a"));
  }
}
