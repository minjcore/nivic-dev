package dev.nivic.analytics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Append-only NDJSON sink (one JSON object per line). */
public final class NdjsonEventSink implements AutoCloseable {

  private final Path path;
  private final boolean syncEachLine;

  public NdjsonEventSink(Path path) {
    this(path, false);
  }

  public NdjsonEventSink(Path path, boolean syncEachLine) {
    this.path = Objects.requireNonNull(path, "path");
    this.syncEachLine = syncEachLine;
  }

  public void appendLine(String jsonLine) throws IOException {
    Objects.requireNonNull(jsonLine, "jsonLine");
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    byte[] bytes = (jsonLine + '\n').getBytes(StandardCharsets.UTF_8);
    StandardOpenOption[] opts =
        syncEachLine
            ? new StandardOpenOption[] {
              StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
              StandardOpenOption.DSYNC
            }
            : new StandardOpenOption[] {
              StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
            };
    Files.write(path, bytes, opts);
  }

  public Path path() {
    return path;
  }

  @Override
  public void close() {}
}
