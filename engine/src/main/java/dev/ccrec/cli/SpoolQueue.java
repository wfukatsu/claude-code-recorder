package dev.ccrec.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What the hooks queued under {@code spool/} and what became of it: an entry per session the hooks
 * hold, and beside it the mark of its last successful ingest. The launcher's {@code doctor} reads the
 * same files the same way.
 */
public final class SpoolQueue {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A session the hooks hold. {@code waiting}: an ingest was asked for and none has succeeded since. */
  public record Entry(
      String sessionId,
      String lastEvent,
      String updatedAt,
      String ingestRequestedAt,
      String recordedAt,
      boolean ended,
      boolean waiting) {}

  /** {@code unreadable} counts the entries set aside as {@code .bad} and those that will be. */
  public record Status(int waiting, String oldestWaiting, int unreadable, List<Entry> entries) {}

  private SpoolQueue() {}

  public static Status of(Path spool) throws IOException {
    List<Entry> entries = new ArrayList<>();
    int unreadable = 0;
    if (Files.isDirectory(spool)) {
      try (DirectoryStream<Path> files = Files.newDirectoryStream(spool)) {
        for (Path file : files) {
          String name = file.getFileName().toString();
          if (name.endsWith(".json.bad")) {
            unreadable++;
          } else if (name.endsWith(".json")) {
            try {
              JsonNode queued = JSON.readTree(Files.readString(file));
              Path done = done(file);
              entries.add(
                  new Entry(
                      queued.path("session_id").asText(name.substring(0, name.length() - ".json".length())),
                      text(queued, "last_event"),
                      text(queued, "updated_at"),
                      text(queued, "ingest_requested_at"),
                      Files.exists(done) ? recordedAt(done).toString() : null,
                      queued.path("ended").asBoolean(false),
                      waiting(queued, done)));
            } catch (JsonProcessingException e) {
              unreadable++;
            }
          }
        }
      }
    }
    entries.sort(Comparator.comparing((Entry e) -> e.updatedAt() == null ? "" : e.updatedAt()).reversed());
    List<String> asked = entries.stream().filter(Entry::waiting).map(Entry::ingestRequestedAt).sorted().toList();
    return new Status(asked.size(), asked.isEmpty() ? null : asked.get(0), unreadable, entries);
  }

  /** The mark that goes with a queue entry. */
  static Path done(Path entry) {
    String name = entry.getFileName().toString();
    return entry.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".done");
  }

  /** Whether the hook asked for this session to be ingested after its last successful ingest. */
  static boolean waiting(JsonNode queued, Path done) throws IOException {
    String requested = queued.path("ingest_requested_at").asText("");
    if (requested.isEmpty()) {
      return false;
    }
    if (!Files.exists(done)) {
      return true;
    }
    try {
      return Instant.parse(requested).isAfter(recordedAt(done));
    } catch (DateTimeParseException e) {
      return true;
    }
  }

  /** When the ingest that left this mark started; a mark left by an earlier version says it by its age alone. */
  static Instant recordedAt(Path done) throws IOException {
    try {
      return Instant.parse(Files.readString(done).strip());
    } catch (DateTimeParseException e) {
      return Files.getLastModifiedTime(done).toInstant();
    }
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).isTextual() ? node.path(field).asText() : null;
  }
}
