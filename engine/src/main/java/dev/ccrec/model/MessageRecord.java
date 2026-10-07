package dev.ccrec.model;

/**
 * One content block of one transcript line. The key (sessionId, agentId, lineNo, blockNo) is derived
 * from the block's position in the source file, so ingesting the same file twice writes the same rows.
 *
 * <p>Claude Code writes one API message as several lines that share {@code messageId} and each repeat
 * its token usage. The token counts are kept on the first recorded block of the message only, so
 * they add up to what was spent.
 */
public record MessageRecord(
    String sessionId,
    String agentId,
    int lineNo,
    int blockNo,
    String accountId,
    String kind,
    String subtype,
    Long ts,
    String uuid,
    String parentUuid,
    String messageId,
    String model,
    String toolName,
    String toolUseId,
    String contentHash,
    long contentBytes,
    String preview,
    Long inputTokens,
    Long outputTokens,
    Long cacheReadTokens,
    Long cacheCreationTokens) {

  public static final String MAIN_AGENT = "main";
}
