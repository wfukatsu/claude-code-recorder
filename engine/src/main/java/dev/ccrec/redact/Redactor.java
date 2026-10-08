package dev.ccrec.redact;

import java.util.List;
import java.util.regex.Pattern;

/** Masks credentials before content is hashed and stored. Applied to every stored text. */
public interface Redactor {

  String MASK = "[REDACTED]";

  String redact(String text);

  Redactor NONE = text -> text;

  /**
   * Well-known credential shapes. Conservative on purpose: a miss is expected, a false hit is not.
   *
   * <p>Most are tokens recognizable by their own prefix. The last three recognize a secret by where
   * it stands — a URL's password, an Authorization header, an environment-style assignment to a name
   * that says it is one — and mask the value only, so the record still shows what was there.
   */
  static Redactor standard() {
    List<Rule> rules =
        List.of(
            token("sk-ant-[A-Za-z0-9_-]{20,}"),
            token("sk-(?:proj|svcacct|admin)-[A-Za-z0-9_-]{20,}"),
            token("sk-[A-Za-z0-9]{32,}"),
            token("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            token("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
            token("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b"),
            token("\\bglpat-[A-Za-z0-9_-]{20,}"),
            token("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            token("https://hooks\\.slack\\.com/services/[A-Za-z0-9/]{20,}"),
            token("\\bAIza[A-Za-z0-9_-]{35}\\b"),
            token("\\bya29\\.[A-Za-z0-9_-]{20,}"),
            token("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{16,}"),
            token("\\bwhsec_[A-Za-z0-9]{24,}"),
            token("\\bnpm_[A-Za-z0-9]{36}\\b"),
            token("\\bhf_[A-Za-z0-9]{30,}\\b"),
            token("\\bSG\\.[A-Za-z0-9_-]{22}\\.[A-Za-z0-9_-]{43}\\b"),
            token("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            token("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            // scheme://user:password@host — the password. Neither part may hold a '/', which keeps
            // "http://localhost:8080/a@b" and the like out.
            value("\\b([A-Za-z][A-Za-z0-9+.-]*://[^\\s/:@\"'<>]+:)[^\\s/@\"'<>\\\\]+(?=@)"),
            value("(?i)(\\bauthorization[\"']?\\s*[:=]\\s*[\"']?(?:basic|bearer|token)\\s+)[A-Za-z0-9._~+/=-]{8,}"),
            value("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/-]{20,}=*"),
            value("\\b(AccountKey=)[A-Za-z0-9+/]{40,}=*"),
            // NAME=value in the upper-case style of a shell or a .env file, where a whole word of the
            // name says it is a secret: DB_PASSWORD, AWS_SECRET_ACCESS_KEY, GITHUB_TOKEN — not
            // MAX_TOKENS, and not a reference such as $TOKEN. The quote may be escaped, as it is in
            // the JSON of a tool call.
            value(
                "\\b((?:[A-Z0-9]+_)*(?:SECRET|PASSWORD|PASSWD|TOKEN|API_KEY|APIKEY|ACCESS_KEY|PRIVATE_KEY|CREDENTIALS)"
                    + "(?:_[A-Z0-9]+)*=(?:\\\\?[\"'])?)(?![$<{])[^\\s\"'\\\\]{8,}"));
    return text -> {
      String result = text;
      for (Rule rule : rules) {
        result = rule.pattern().matcher(result).replaceAll(rule.replacement());
      }
      return result;
    };
  }

  /** A pattern and what a match becomes. */
  record Rule(Pattern pattern, String replacement) {}

  /** The whole match is the credential. */
  private static Rule token(String regex) {
    return new Rule(Pattern.compile(regex), "\\" + MASK);
  }

  /** Group 1 is what stands before the credential and is kept. */
  private static Rule value(String regex) {
    return new Rule(Pattern.compile(regex), "$1\\" + MASK);
  }
}
