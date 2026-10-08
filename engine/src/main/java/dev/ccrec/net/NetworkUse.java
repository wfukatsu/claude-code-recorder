package dev.ccrec.net;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells, from a tool call's name and input, whether it reaches beyond this machine and Claude itself
 * — and where to. What was sent is the call's input; what came back is its result.
 *
 * <p>This is a reading of what the transcript shows, not a capture of traffic: a web tool names its
 * address, an MCP tool names its server, and a shell command is recognised by the programs it runs
 * and the addresses it mentions. A script that opens a socket by itself is not seen, and a program
 * that usually talks to the network is counted even when a flag kept it offline.
 */
public final class NetworkUse {

  public static final String WEB = "web";
  public static final String SHELL = "shell";
  public static final String MCP = "mcp";

  /** Stands in for a destination the input does not name. */
  public static final String UNKNOWN_HOST = "(unknown)";

  /**
   * @param category {@link #WEB}, {@link #SHELL} or {@link #MCP}
   * @param hosts where to, in the order found; a name in parentheses when only the kind of place is known
   * @param sent what was sent, in a line: the address, the query, the command
   */
  public record Use(String category, List<String> hosts, String sent) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Pattern URL = Pattern.compile("\\b(?:https?|wss?|ftp|ssh|git)://([^\\s/'\"<>`\\\\)]+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern USER_AT_HOST = Pattern.compile("(?<![\\w./-])[\\w.-]+@([A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}|\\d{1,3}(?:\\.\\d{1,3}){3})(?=[:\\s'\"]|$)");

  /** A program at the start of a command, of a pipeline stage or of a substitution. */
  private static final Pattern PROGRAM =
      Pattern.compile(
          "(?:^|[;&|(\\n`]|\\$\\(|\\bsudo\\s+|\\bxargs\\s+(?:-\\S+\\s+)*|\\btime\\s+|\\bnohup\\s+|\\bexec\\s+)\\s*(?:[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+)*"
              + "(?:\\S*/)?([A-Za-z][\\w.+-]*)((?:[ \\t]+[^;&|\\n`)]*)?)");

  /** Programs that talk to the network whatever their arguments: where to, when no address is given. */
  private static final Map<String, String> ALWAYS =
      Map.ofEntries(
          Map.entry("curl", UNKNOWN_HOST),
          Map.entry("wget", UNKNOWN_HOST),
          Map.entry("http", UNKNOWN_HOST),
          Map.entry("https", UNKNOWN_HOST),
          Map.entry("gh", "github.com"),
          Map.entry("ssh", UNKNOWN_HOST),
          Map.entry("scp", UNKNOWN_HOST),
          Map.entry("sftp", UNKNOWN_HOST),
          Map.entry("rsync", UNKNOWN_HOST),
          Map.entry("ftp", UNKNOWN_HOST),
          Map.entry("telnet", UNKNOWN_HOST),
          Map.entry("nc", UNKNOWN_HOST),
          Map.entry("ncat", UNKNOWN_HOST),
          Map.entry("ping", UNKNOWN_HOST),
          Map.entry("dig", UNKNOWN_HOST),
          Map.entry("nslookup", UNKNOWN_HOST),
          Map.entry("traceroute", UNKNOWN_HOST),
          Map.entry("npx", "(npm registry)"),
          Map.entry("aws", "(AWS)"),
          Map.entry("gcloud", "(Google Cloud)"),
          Map.entry("gsutil", "(Google Cloud)"),
          Map.entry("az", "(Azure)"),
          Map.entry("kubectl", "(Kubernetes)"),
          Map.entry("helm", "(Kubernetes)"));

  /** Programs that talk to the network for some of their subcommands only. */
  private static final Map<String, Map.Entry<String, Pattern>> SOMETIMES =
      Map.ofEntries(
          sometimes("git", "(git remote)", "clone|fetch|pull|push|ls-remote|remote\\s+update|submodule\\s+update"),
          sometimes("npm", "(npm registry)", "install|i|ci|add|update|up|publish|view|info|show|outdated|audit|login|whoami|search|dist-tag|access"),
          sometimes("pnpm", "(npm registry)", "install|i|add|update|up|publish|dlx|outdated|audit"),
          sometimes("yarn", "(npm registry)", "install|add|up|upgrade|publish|dlx|npm"),
          sometimes("bun", "(npm registry)", "install|add|update|x"),
          sometimes("pip", "(PyPI)", "install|download|index|search"),
          sometimes("pip3", "(PyPI)", "install|download|index|search"),
          sometimes("uv", "(PyPI)", "pip\\s+install|add|sync|lock|tool\\s+install"),
          sometimes("poetry", "(PyPI)", "install|add|update|publish|lock"),
          sometimes("brew", "(Homebrew)", "install|update|upgrade|fetch|tap|search|outdated"),
          sometimes("apt", "(package repository)", "install|update|upgrade"),
          sometimes("apt-get", "(package repository)", "install|update|upgrade"),
          sometimes("docker", "(container registry)", "pull|push|login|search"),
          sometimes("podman", "(container registry)", "pull|push|login|search"),
          sometimes("cargo", "(crates.io)", "install|fetch|publish|search|update|add"),
          sometimes("go", "(Go modules)", "get|install|mod\\s+download|mod\\s+tidy"),
          sometimes("gem", "(RubyGems)", "install|update|push|fetch|search"),
          sometimes("terraform", "(Terraform registry and providers)", "init|apply|plan|destroy|refresh"));

  private static Map.Entry<String, Map.Entry<String, Pattern>> sometimes(String program, String place, String subcommands) {
    return Map.entry(program, Map.entry(place, Pattern.compile("^\\s*(?:-\\S+\\s+)*(?:" + subcommands + ")\\b")));
  }

  /** A here-document: its body is data, whatever it looks like. Group 3 is the rest of the line it starts on. */
  private static final Pattern HERE_DOCUMENT =
      Pattern.compile("<<-?\\s*(['\"]?)([A-Za-z_]\\w*)\\1([^\\n]*)\\n[\\s\\S]*?\\n[ \\t]*\\2[ \\t]*(?=\\n|\\)|$)");

  /** A host name or an address; what a pattern or a placeholder leaves behind is neither. */
  private static final Pattern HOST = Pattern.compile("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?|\\[[0-9a-f:.]+\\]");

  /**
   * MCP servers that run on this machine and are not themselves a way out: browser and editor
   * automation, local reasoning aids. An address in a call's input still counts. The settings can
   * name more.
   */
  private static final Set<String> LOCAL_MCP_SERVERS =
      Set.of("claude-in-chrome", "chrome-devtools", "playwright", "puppeteer", "ide", "serena", "sequential-thinking", "filesystem", "memory");

  private static final Set<String> FILE_TOOLS = Set.of("Read", "Write", "Edit", "MultiEdit", "NotebookEdit", "Glob", "Grep", "LS");

  private NetworkUse() {}

  /**
   * @param toolName the tool called
   * @param input its input as recorded: JSON, whole
   * @param localMcpServers MCP servers known to run on this machine and reach nothing beyond it
   */
  public static Optional<Use> of(String toolName, String input, Set<String> localMcpServers) {
    if (toolName == null || input == null || FILE_TOOLS.contains(toolName)) {
      return Optional.empty();
    }
    JsonNode node;
    try {
      node = JSON.readTree(input);
    } catch (JsonProcessingException e) {
      node = JSON.createObjectNode();
    }
    if (toolName.startsWith("mcp__")) {
      String[] parts = toolName.split("__", 3);
      String server = parts.length == 3 ? parts[1] : toolName;
      List<String> hosts = hostsIn(input);
      boolean local = LOCAL_MCP_SERVERS.contains(server) || localMcpServers.contains(server);
      // Sent to addresses that are all this machine or Claude, it stays here whatever the server.
      if (hosts.isEmpty() && (local || URL.matcher(input).find())) {
        return Optional.empty();
      }
      // The server is where the call goes; an address in the input is where the server is sent on to.
      return Optional.of(new Use(MCP, hosts.isEmpty() ? List.of("(MCP: " + server + ")") : hosts, line(parts.length == 3 ? parts[2] : toolName, input)));
    }
    if (toolName.equals("WebSearch")) {
      return Optional.of(new Use(WEB, List.of("(web search)"), node.path("query").asText(input)));
    }
    if (node.path("command").isTextual()) {
      return shell(node.path("command").asText());
    }
    if (node.path("url").isTextual()) {
      List<String> hosts = hostsIn(node.path("url").asText());
      return hosts.isEmpty() ? Optional.empty() : Optional.of(new Use(WEB, hosts, node.path("url").asText()));
    }
    return Optional.empty();
  }

  /**
   * A command is read program by program. An address counts where a program that talks to the
   * network is given it — not where it merely appears, in a file written by a here-document or in the
   * text of a commit message.
   */
  private static Optional<Use> shell(String command) {
    String script = HERE_DOCUMENT.matcher(command).replaceAll("$3\n");
    Set<String> hosts = new LinkedHashSet<>();
    String first = null;
    Matcher m = PROGRAM.matcher(script);
    while (m.find()) {
      String program = m.group(1).toLowerCase(Locale.ROOT);
      String arguments = m.group(2) == null ? "" : m.group(2);
      String place;
      if (ALWAYS.containsKey(program)) {
        place = ALWAYS.get(program);
      } else if (SOMETIMES.containsKey(program) && SOMETIMES.get(program).getValue().matcher(arguments).find()) {
        place = SOMETIMES.get(program).getKey();
      } else {
        continue;
      }
      Set<String> named = new LinkedHashSet<>(hostsIn(arguments));
      Matcher remote = USER_AT_HOST.matcher(arguments);
      while (remote.find()) {
        add(named, remote.group(1));
      }
      int before = hosts.size();
      if (!named.isEmpty()) {
        hosts.addAll(named);
      } else if (!URL.matcher(arguments).find()) {
        // No address given: it goes where the program goes by itself.
        hosts.add(place);
      }
      if (first == null && hosts.size() > before) {
        first = (m.group(1) + arguments).strip();
      }
      // Otherwise every address it was given is this machine or Claude: a curl to localhost goes nowhere.
    }
    if (hosts.isEmpty()) {
      return Optional.empty();
    }
    // A place known by name says more than "somewhere".
    if (hosts.size() > 1) {
      hosts.remove(UNKNOWN_HOST);
    }
    // What was sent, in a line: the first of the programs that reached out, as it was run.
    return Optional.of(new Use(SHELL, List.copyOf(hosts), first.length() <= 300 ? first : first.substring(0, 300) + "…"));
  }

  /** The hosts of the addresses in a text, without this machine's and Claude's own. */
  static List<String> hostsIn(String text) {
    Set<String> hosts = new LinkedHashSet<>();
    Matcher m = URL.matcher(text);
    while (m.find()) {
      add(hosts, m.group(1));
    }
    return new ArrayList<>(hosts);
  }

  private static void add(Set<String> hosts, String authority) {
    String host = authority;
    host = host.substring(host.lastIndexOf('@') + 1);
    if (host.startsWith("[")) {
      host = host.substring(0, host.indexOf(']') < 0 ? host.length() : host.indexOf(']') + 1);
    } else if (host.contains(":")) {
      host = host.substring(0, host.indexOf(':'));
    }
    host = host.replaceAll("[.,;]+$", "").toLowerCase(Locale.ROOT);
    // A shell variable, a placeholder or a piece of a pattern is not a place.
    if (!HOST.matcher(host).matches() || isLocal(host) || isClaude(host)) {
      return;
    }
    hosts.add(host);
  }

  static boolean isLocal(String host) {
    return host.equals("localhost")
        || host.endsWith(".localhost")
        || host.endsWith(".local")
        || host.endsWith(".internal")
        || host.equals("[::1]")
        || host.equals("0.0.0.0")
        || host.matches("127(\\.\\d{1,3}){3}")
        || host.matches("10(\\.\\d{1,3}){3}")
        || host.matches("192\\.168(\\.\\d{1,3}){2}")
        || host.matches("172\\.(1[6-9]|2\\d|3[01])(\\.\\d{1,3}){2}")
        || host.matches("169\\.254(\\.\\d{1,3}){2}")
        || !host.contains(".") && !host.startsWith("[");
  }

  static boolean isClaude(String host) {
    for (String domain : List.of("anthropic.com", "claude.ai", "claude.com")) {
      if (host.equals(domain) || host.endsWith("." + domain)) {
        return true;
      }
    }
    return false;
  }

  private static String line(String what, String input) {
    String compact = input.replaceAll("\\s+", " ");
    return what + " " + (compact.length() <= 200 ? compact : compact.substring(0, 200) + "…");
  }
}
