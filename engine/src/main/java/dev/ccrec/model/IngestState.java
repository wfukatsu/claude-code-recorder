package dev.ccrec.model;

/**
 * How far a source file has been ingested: the byte offset after the last committed line, and the
 * API message whose token usage that line already accounted for (null when it carried none), and the
 * pull requests already recorded from the file, one URL per line — Claude Code writes the same link
 * down again and again (null when there are none).
 */
public record IngestState(
    String hostId,
    String sourcePathHash,
    String sourcePath,
    long offset,
    int lineNo,
    String usageMessageId,
    String prUrls) {}
