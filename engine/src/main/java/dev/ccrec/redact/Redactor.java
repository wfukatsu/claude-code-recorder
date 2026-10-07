package dev.ccrec.redact;

import java.util.List;
import java.util.regex.Pattern;

/** Masks credentials before content is hashed and stored. Applied to every stored text. */
public interface Redactor {

  String redact(String text);

  Redactor NONE = text -> text;

  /** Well-known credential shapes. Conservative on purpose: a miss is expected, a false hit is not. */
  static Redactor standard() {
    List<Pattern> patterns =
        List.of(
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("sk-[A-Za-z0-9]{32,}"),
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b"),
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{35}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile(
                "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"));
    return text -> {
      String result = text;
      for (Pattern pattern : patterns) {
        result = pattern.matcher(result).replaceAll("[REDACTED]");
      }
      return result;
    };
  }
}
