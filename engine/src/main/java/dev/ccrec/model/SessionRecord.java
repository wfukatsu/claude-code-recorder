package dev.ccrec.model;

/** One Claude Code session. Nullable fields are left untouched in the store when null. */
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
    Long endedAt) {}
