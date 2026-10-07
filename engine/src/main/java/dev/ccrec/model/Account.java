package dev.ccrec.model;

/** Who a session belongs to. {@code accountId} and {@code orgId} are partition keys: no colons. */
public record Account(
    String accountId,
    String email,
    String displayName,
    String orgId,
    String orgName,
    String authMethod) {

  public static final String NO_ORG = "none";

  public Account {
    accountId = keySafe(accountId);
    orgId = orgId == null || orgId.isBlank() ? NO_ORG : keySafe(orgId);
  }

  /** Azure Cosmos DB rejects ':' in a text partition key, so no key this tool writes contains one. */
  public static String keySafe(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("key value must not be empty");
    }
    return value.replace(':', '_');
  }
}
