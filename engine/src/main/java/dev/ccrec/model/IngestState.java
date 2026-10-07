package dev.ccrec.model;

/** How far a source file has been ingested: the byte offset after the last committed line. */
public record IngestState(String hostId, String sourcePathHash, String sourcePath, long offset, int lineNo) {}
