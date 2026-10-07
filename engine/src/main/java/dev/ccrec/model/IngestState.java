package dev.ccrec.model;

/**
 * How far a source file has been ingested: the byte offset after the last committed line, and the
 * API message whose token usage that line already accounted for (null when it carried none).
 */
public record IngestState(
    String hostId,
    String sourcePathHash,
    String sourcePath,
    long offset,
    int lineNo,
    String usageMessageId) {}
