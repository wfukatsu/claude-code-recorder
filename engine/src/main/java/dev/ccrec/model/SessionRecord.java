package dev.ccrec.model;

/**
 * One Claude Code session. Nullable fields are left untouched in the store when null.
 *
 * <p>The cost, the durations and the line counts are what Claude Code itself last wrote down for the
 * session (its {@code cost-state} record), which it does not do for every session.
 */
public record SessionRecord(
    String accountId,
    long startedAt,
    String sessionId,
    String orgId,
    String hostId,
    String projectPath,
    String gitBranch,
    String ccVersion,
    String model,
    String title,
    Long endedAt,
    String entrypoint,
    Double costUsd,
    Long apiDurationMs,
    Long toolDurationMs,
    Long linesAdded,
    Long linesRemoved) {}
