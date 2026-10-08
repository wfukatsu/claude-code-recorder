package dev.ccrec.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The lock every writer holds while it writes. SQLite takes one writer, so writers queue here rather
 * than colliding inside the database — the hooks' ingests, the commands that delete, and the UI.
 */
public final class IngestLock {

  private IngestLock() {}

  public static FileChannel open(Path home) throws IOException {
    Files.createDirectories(home);
    return FileChannel.open(home.resolve("ingest.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
  }

  /** The lock, or null when another process still holds it after {@code waitMillis}. */
  public static FileLock acquire(FileChannel channel, long waitMillis) throws IOException {
    long deadline = System.currentTimeMillis() + waitMillis;
    while (true) {
      FileLock lock = channel.tryLock();
      if (lock != null || System.currentTimeMillis() >= deadline) {
        return lock;
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
  }
}
