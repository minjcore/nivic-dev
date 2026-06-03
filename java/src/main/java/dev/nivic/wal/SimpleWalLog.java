package dev.nivic.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Append-only write-ahead style log: each record is {@code BE uint32 length}{@code length} bytes of
 * payload. Thread-safe for concurrent {@link #append} calls on one instance.
 */
public final class SimpleWalLog implements AutoCloseable {

  private final FileChannel channel;
  private final Object writeLock = new Object();

  /**
   * @param path log file (created if missing)
   * @param syncEachWrite if true, opens with {@link StandardOpenOption#DSYNC} so each append tends
   *     to hit disk (slower, safer on crash)
   */
  public SimpleWalLog(Path path, boolean syncEachWrite) throws IOException {
    Objects.requireNonNull(path, "path");
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    OpenOption[] opts =
        syncEachWrite
            ? new StandardOpenOption[] {
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND,
              StandardOpenOption.DSYNC
            }
            : new StandardOpenOption[] {
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              StandardOpenOption.APPEND
            };
    channel = FileChannel.open(path, opts);
  }

  public SimpleWalLog(Path path) throws IOException {
    this(path, false);
  }

  /**
   * Appends one record (length-prefixed). {@code payload} may be empty (length 0).
   *
   * @throws IllegalArgumentException if {@code payload} length does not fit in unsigned 32-bit
   */
  public void append(byte[] payload) throws IOException {
    Objects.requireNonNull(payload, "payload");
    ByteBuffer header = ByteBuffer.allocate(4);
    header.putInt(payload.length);
    header.flip();
    synchronized (writeLock) {
      channel.write(header);
      channel.write(ByteBuffer.wrap(payload));
    }
  }

  /** Flushes OS buffers; {@code metaData} as in {@link FileChannel#force(boolean)}. */
  public void force(boolean metaData) throws IOException {
    synchronized (writeLock) {
      channel.force(metaData);
    }
  }

  /**
   * Reads the file from the beginning; invokes each decoded payload once. Skips cleanly if the file
   * does not exist. Stops at truncated tail without throwing if EOF mid-record (empty file OK).
   */
  public static void replay(Path path, Consumer<byte[]> consumer) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(consumer, "consumer");
    if (!Files.exists(path)) {
      return;
    }
    try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
      ByteBuffer lenBuf = ByteBuffer.allocate(4);
      while (true) {
        lenBuf.clear();
        if (!readFully(ch, lenBuf)) {
          break;
        }
        lenBuf.flip();
        int len = lenBuf.getInt();
        if (len < 0) {
          throw new IOException("invalid WAL record length: " + len);
        }
        if (len == 0) {
          consumer.accept(new byte[0]);
          continue;
        }
        ByteBuffer body = ByteBuffer.allocate(len);
        if (!readFully(ch, body)) {
          break;
        }
        consumer.accept(body.array());
      }
    }
  }

  private static boolean readFully(FileChannel ch, ByteBuffer dst) throws IOException {
    while (dst.hasRemaining()) {
      int n = ch.read(dst);
      if (n < 0) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
