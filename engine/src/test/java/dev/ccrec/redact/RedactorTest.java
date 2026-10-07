package dev.ccrec.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedactorTest {

  private final Redactor redactor = Redactor.standard();

  @Test
  void masksTokensKnownByTheirPrefix() {
    List<String> tokens =
        List.of(
            "sk-ant-abcdefghijklmnopqrstuvwxyz0123",
            "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCd",
            "sk-abcdefghijklmnopqrstuvwxyz0123456789ABCD",
            "AKIAIOSFODNN7EXAMPLE",
            "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "glpat-abcdefghijklmnopqrst",
            "xoxb-123456789012-abcdefghijkl",
            "https://hooks.slack.com/services/T00000000/B00000000/abcdefghijklmnopqrstuvwx",
            "AIzaSyA-abcdefghijklmnopqrstuvwxyz01234",
            "ya29.a0AfH6SMBabcdefghijklmnopqrstuvwxyz",
            "sk_live_abcdefghijklmnopqrstuvwx",
            "rk_test_abcdefghijklmnopqrstuvwx",
            "whsec_abcdefghijklmnopqrstuvwxyz012345",
            "npm_abcdefghijklmnopqrstuvwxyz0123456789",
            "hf_abcdefghijklmnopqrstuvwxyzABCDEFGH",
            "SG.abcdefghijklmnopqrstuv.abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nIBAAKC\n-----END RSA PRIVATE KEY-----");
    for (String token : tokens) {
      assertEquals("key: [REDACTED].", redactor.redact("key: " + token + "."), token);
    }
  }

  @Test
  void masksASecretKnownByItsPlaceAndKeepsWhatSurroundsIt() {
    Map<String, String> cases =
        Map.ofEntries(
            Map.entry(
                "postgres://app:S3cretPassw0rd@db.example.com:5432/x",
                "postgres://app:[REDACTED]@db.example.com:5432/x"),
            Map.entry("https://deploy:p%40ss@git.example.com/r.git", "https://deploy:[REDACTED]@git.example.com/r.git"),
            Map.entry("Authorization: Bearer abc.DEF-123_456", "Authorization: Bearer [REDACTED]"),
            Map.entry("authorization: Basic dXNlcjpwYXNzd29yZA==", "authorization: Basic [REDACTED]"),
            Map.entry("-H 'Authorization: token abcdef123456'", "-H 'Authorization: token [REDACTED]'"),
            Map.entry("curl -H \"X-Auth: bearer abcdefghijklmnopqrstuvwxyz\"", "curl -H \"X-Auth: bearer [REDACTED]\""),
            Map.entry(
                "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "AWS_SECRET_ACCESS_KEY=[REDACTED]"),
            Map.entry("export DB_PASSWORD='correct-horse-battery'", "export DB_PASSWORD='[REDACTED]'"),
            Map.entry("GITHUB_TOKEN=abcdef0123456789 npm ci", "GITHUB_TOKEN=[REDACTED] npm ci"),
            // As it stands inside a tool call's JSON input: the value ends at the escaped quote.
            Map.entry("{\"command\":\"API_KEY=\\\"abcdef0123456789\\\" make\"}", "{\"command\":\"API_KEY=\\\"[REDACTED]\\\" make\"}"),
            Map.entry(
                "DefaultEndpointsProtocol=https;AccountName=acct;AccountKey=" + "Ab0+/".repeat(17) + "Ab==;",
                "DefaultEndpointsProtocol=https;AccountName=acct;AccountKey=[REDACTED];"));
    cases.forEach((text, masked) -> assertEquals(masked, redactor.redact(text)));
  }

  @Test
  void leavesAloneWhatOnlyLooksLikeASecret() {
    List<String> harmless =
        List.of(
            "http://localhost:8080/users/a@b",
            "git clone git@github.com:org/repo.git",
            "see https://example.com/docs?mail=a@b.c for Basic authentication",
            "MAX_TOKENS=100000000 TOKENS_PER_MINUTE=40000000",
            "PRIMARY_KEY=customer_identifier PWD=/Users/someone/work",
            "GITHUB_TOKEN=$GITHUB_TOKEN API_KEY=${API_KEY} DB_PASSWORD=<your-password>",
            "const token = readTokenFromKeychain(); password: string;",
            "the bearer of this message, and a task-list of sk-ip-able items",
            "commit 0123456789abcdef0123456789abcdef01234567",
            "日本語のテキストと path/to/some_file-name.txt");
    for (String text : harmless) {
      assertEquals(text, redactor.redact(text));
    }
  }

  @Test
  void theMaskItselfIsStable() {
    String once = redactor.redact("TOKEN=abcdefgh12345678 and sk-ant-abcdefghijklmnopqrstuvwxyz0123");
    assertFalse(once.contains("abcdefgh"));
    assertEquals(once, redactor.redact(once), "redacting again changes nothing");
  }
}
