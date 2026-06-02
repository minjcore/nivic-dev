package dev.nivic.analytics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Persisted WAL shipper progress: byte offset in the WAL file + NVW2 prev-hash chain state.
 *
 * <p>File format (properties):
 *
 * <pre>
 * wal_shipper.cursor.version=1
 * offset=12345
 * prev_hash=0000...00
 * </pre>
 */
public record WalShipperCursor(long byteOffset, byte[] prevHash) {

  private static final int PREV_HASH_LEN = 32;
  private static final String VERSION_KEY = "wal_shipper.cursor.version";
  private static final String OFFSET_KEY = "offset";
  private static final String PREV_HASH_KEY = "prev_hash";

  public WalShipperCursor {
    if (byteOffset < 0) {
      throw new IllegalArgumentException("byteOffset must be >= 0");
    }
    Objects.requireNonNull(prevHash, "prevHash");
    if (prevHash.length != PREV_HASH_LEN) {
      throw new IllegalArgumentException("prevHash must be " + PREV_HASH_LEN + " bytes");
    }
    prevHash = Arrays.copyOf(prevHash, PREV_HASH_LEN);
  }

  public static WalShipperCursor initial() {
    return new WalShipperCursor(0L, new byte[PREV_HASH_LEN]);
  }

  public WalShipperCursor withProgress(long newOffset, byte[] newPrevHash) {
    return new WalShipperCursor(newOffset, newPrevHash);
  }

  public static WalShipperCursor load(Path cursorPath) throws IOException {
    if (cursorPath == null || !Files.isRegularFile(cursorPath)) {
      return initial();
    }
    long offset = 0L;
    byte[] prev = new byte[PREV_HASH_LEN];
    for (String line : Files.readAllLines(cursorPath)) {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int eq = line.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = line.substring(0, eq).trim();
      String val = line.substring(eq + 1).trim();
      switch (key) {
        case OFFSET_KEY -> offset = Long.parseLong(val);
        case PREV_HASH_KEY -> {
          byte[] decoded = HexFormat.of().parseHex(val);
          if (decoded.length != PREV_HASH_LEN) {
            throw new IOException("prev_hash must be " + PREV_HASH_LEN + " bytes hex");
          }
          prev = decoded;
        }
        default -> { /* ignore unknown keys */ }
      }
    }
    return new WalShipperCursor(offset, prev);
  }

  public void save(Path cursorPath) throws IOException {
    Objects.requireNonNull(cursorPath, "cursorPath");
    Path parent = cursorPath.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    String body =
        VERSION_KEY
            + "=1\n"
            + OFFSET_KEY
            + "="
            + byteOffset
            + "\n"
            + PREV_HASH_KEY
            + "="
            + HexFormat.of().formatHex(prevHash)
            + "\n";
    Path tmp = cursorPath.resolveSibling(cursorPath.getFileName() + ".tmp");
    Files.writeString(tmp, body);
    Files.move(tmp, cursorPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }
}
