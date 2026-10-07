package dev.ccrec.ingest;

import dev.ccrec.content.ContentCodec;
import dev.ccrec.model.Account;
import dev.ccrec.model.IngestState;
import dev.ccrec.model.MessageRecord;
import dev.ccrec.model.SessionRecord;
import dev.ccrec.redact.Redactor;
import dev.ccrec.store.RecordStore;
import dev.ccrec.sync.Syncer;
import dev.ccrec.transcript.TranscriptParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a session's transcript files incrementally into a {@link RecordStore}.
 *
 * <p>Only complete lines past the stored offset are read, and each batch commits its records together
 * with the new offset and the session row, so an interrupted or repeated run neither loses nor
 * duplicates anything, and no recorded line is left without its session.
 */
public final class Ingester {

  private static final int BATCH_LINES = 50;
  private static final int BATCH_CHARS = 1_000_000;
  private static final int PREVIEW_CHARS = 1000;

  private final RecordStore store;
  private final Redactor redactor;
  private final Syncer syncer;
  private final boolean recordThinking;
  private final String hostId;
  private final TranscriptParser parser = new TranscriptParser();

  public record Summary(String sessionId, int files, int lines, int messages) {}

  public Ingester(
      RecordStore store, Redactor redactor, Syncer syncer, boolean recordThinking, String hostId) {
    this.store = store;
    this.redactor = redactor;
    this.syncer = syncer;
    this.recordThinking = recordThinking;
    this.hostId = Account.keySafe(hostId);
  }

  /** Ingests the main transcript and the sub-agent transcripts stored beside it. */
  public Summary ingestSession(Path transcript, String sessionId, Account account) {
    store.upsertAccount(account, System.currentTimeMillis());

    SessionMeta meta = new SessionMeta(firstTimestamp(transcript));
    FileResult main = ingestFile(transcript, sessionId, MessageRecord.MAIN_AGENT, account, meta);
    int files = 1;
    int lines = main.lines;
    int messages = main.messages;

    Path subagents = transcript.resolveSibling(sessionId).resolve("subagents");
    if (Files.isDirectory(subagents)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(subagents, "agent-*.jsonl")) {
        for (Path file : stream) {
          String name = file.getFileName().toString();
          String agentId = Account.keySafe(name.substring("agent-".length(), name.length() - ".jsonl".length()));
          FileResult result = ingestFile(file, sessionId, agentId, account, null);
          files++;
          lines += result.lines;
          messages += result.messages;
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    if (lines > 0 && meta.startedAt != null) {
      syncer.sessionUpdated(account.accountId(), sessionId);
    }
    return new Summary(sessionId, files, lines, messages);
  }

  /** What the main transcript says about its session, gathered from the lines read so far. */
  private final class SessionMeta {
    /** The session's start: the first timestamp in its main transcript, the same on every run. */
    final Long startedAt;

    String cwd;
    String gitBranch;
    String version;
    String model;
    String title;
    Long lastTs;

    SessionMeta(Long startedAt) {
      this.startedAt = startedAt;
    }

    /**
     * The session row as known so far, or null while the transcript has no timestamp to key it by.
     * Fields no line has mentioned in this run are null, which leaves the stored value in place.
     */
    SessionRecord record(String sessionId, Account account) {
      if (startedAt == null) {
        return null;
      }
      return new SessionRecord(
          account.accountId(),
          startedAt,
          sessionId,
          account.orgId(),
          hostId,
          cwd,
          gitBranch,
          version,
          model,
          title,
          lastTs);
    }

    void observe(TranscriptParser.Line line) {
      cwd = line.cwd() != null ? line.cwd() : cwd;
      gitBranch = line.gitBranch() != null ? line.gitBranch() : gitBranch;
      version = line.version() != null ? line.version() : version;
      model = line.model() != null ? line.model() : model;
      title = line.title() != null ? line.title() : title;
      if (line.ts() != null && (lastTs == null || line.ts() > lastTs)) {
        lastTs = line.ts();
      }
    }
  }

  private record FileResult(int lines, int messages) {}

  /**
   * @param meta the session's metadata when {@code file} is its main transcript, which then writes
   *     the session row with every batch; null for a sub-agent's transcript
   */
  private FileResult ingestFile(
      Path file, String sessionId, String agentId, Account account, SessionMeta meta) {
    String absolute = file.toAbsolutePath().normalize().toString();
    String pathHash = ContentCodec.hash(absolute);
    IngestState state =
        store.ingestState(hostId, pathHash).orElse(new IngestState(hostId, pathHash, absolute, 0, 0, null));

    long position = state.offset();
    int lineNo = state.lineNo();
    String usageMessageId = state.usageMessageId();

    List<MessageRecord> batch = new ArrayList<>();
    Map<String, String> contents = new LinkedHashMap<>();
    int batchLines = 0;
    int batchChars = 0;
    int totalLines = 0;
    int totalMessages = 0;

    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      if (channel.size() < position) {
        // The file was replaced by a shorter one: read it again from the start.
        position = 0;
        lineNo = 0;
        usageMessageId = null;
      }
      // Read as a stream, a batch at a time: a first import can be a transcript of any size, and only
      // the line being read and the batch being built are held in memory.
      InputStream in = new BufferedInputStream(Channels.newInputStream(channel.position(position)), 1 << 16);
      ByteArrayOutputStream pending = new ByteArrayOutputStream();
      long read = position;
      for (int next = in.read(); next >= 0; next = in.read()) {
        read++;
        if (next != '\n') {
          pending.write(next);
          continue;
        }
        // Whatever follows the last newline is a line Claude Code is still writing; it stays unread.
        position = read;
        String raw = pending.toString(StandardCharsets.UTF_8).strip();
        pending.reset();
        lineNo++;
        totalLines++;
        batchLines++;
        if (!raw.isEmpty()) {
          TranscriptParser.Line line = parser.parse(raw);
          if (meta != null) {
            meta.observe(line);
          }
          // One API message is written as several lines, each repeating its usage: it is recorded on
          // the first block kept for the message, and on no later line of the same message.
          TranscriptParser.Usage usage =
              line.messageId() != null && line.messageId().equals(usageMessageId) ? null : line.usage();
          int blockNo = 0;
          for (TranscriptParser.Block block : line.blocks()) {
            int thisBlock = blockNo++;
            if (!recordThinking && block.kind().equals(TranscriptParser.THINKING)) {
              continue;
            }
            String text = redactor.redact(block.text());
            String hash = ContentCodec.hash(text);
            if (contents.putIfAbsent(hash, text) == null) {
              batchChars += text.length();
            }
            TranscriptParser.Usage recorded = usage;
            if (recorded != null) {
              usage = null;
              usageMessageId = line.messageId();
            }
            batch.add(
                new MessageRecord(
                    sessionId,
                    agentId,
                    lineNo,
                    thisBlock,
                    account.accountId(),
                    block.kind(),
                    block.subtype(),
                    line.ts(),
                    line.uuid(),
                    line.parentUuid(),
                    line.messageId(),
                    line.model(),
                    block.toolName(),
                    block.toolUseId(),
                    hash,
                    text.getBytes(StandardCharsets.UTF_8).length,
                    preview(text),
                    recorded == null ? null : recorded.input(),
                    recorded == null ? null : recorded.output(),
                    recorded == null ? null : recorded.cacheRead(),
                    recorded == null ? null : recorded.cacheCreation()));
          }
        }
        if (batchLines >= BATCH_LINES || batchChars >= BATCH_CHARS) {
          store.writeBatch(
              batch,
              contents,
              new IngestState(hostId, pathHash, absolute, position, lineNo, usageMessageId),
              meta == null ? null : meta.record(sessionId, account));
          totalMessages += batch.size();
          batch = new ArrayList<>();
          contents = new LinkedHashMap<>();
          batchLines = 0;
          batchChars = 0;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (batchLines > 0) {
      store.writeBatch(
          batch,
          contents,
          new IngestState(hostId, pathHash, absolute, position, lineNo, usageMessageId),
          meta == null ? null : meta.record(sessionId, account));
      totalMessages += batch.size();
    }
    return new FileResult(totalLines, totalMessages);
  }

  private Long firstTimestamp(Path transcript) {
    try (var reader = Files.newBufferedReader(transcript, StandardCharsets.UTF_8)) {
      String raw;
      while ((raw = reader.readLine()) != null) {
        if (raw.isBlank()) {
          continue;
        }
        Long ts = parser.parse(raw).ts();
        if (ts != null) {
          return ts;
        }
      }
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String preview(String text) {
    if (text.length() <= PREVIEW_CHARS) {
      return text;
    }
    int end = Character.isHighSurrogate(text.charAt(PREVIEW_CHARS - 1)) ? PREVIEW_CHARS - 1 : PREVIEW_CHARS;
    return text.substring(0, end);
  }
}
