package dev.nivic.sevlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads a binary stream into a byte array with a hard size cap. */
public final class BinaryBodyReader {

  private BinaryBodyReader() {}

  /**
   * Reads until EOF from {@code in}.
   *
   * @param declaredContentLength {@code Content-Length} from the HTTP request, or {@code -1} if
   *     unknown (chunked). Used only for an early reject; bytes are still bounded by {@code
   *     maxBytes} while reading.
   */
  public static byte[] readFully(InputStream in, long declaredContentLength, int maxBytes)
      throws IOException {
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes must be >= 0");
    }
    if (declaredContentLength > maxBytes) {
      throw new BodyTooLargeException(
          "declared length " + declaredContentLength + " exceeds max " + maxBytes);
    }

    int initialCapacity = 8192;
    if (declaredContentLength >= 0) {
      initialCapacity = (int) Math.min(declaredContentLength, (long) maxBytes);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, initialCapacity));

    byte[] buf = new byte[8192];
    int total = 0;
    int n;
    while ((n = in.read(buf)) != -1) {
      if (total + n > maxBytes) {
        throw new BodyTooLargeException("body exceeds max " + maxBytes + " bytes");
      }
      out.write(buf, 0, n);
      total += n;
    }
    return out.toByteArray();
  }
}
