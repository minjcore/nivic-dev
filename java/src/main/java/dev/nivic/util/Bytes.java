package dev.nivic.util;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** Big-endian binary primitives and safe byte comparisons. */
public final class Bytes {

  private Bytes() {}

  /** Exactly 8 bytes, interpreted as big-endian int64 (wire u64 bit pattern in {@code long}). */
  public static long readInt64BE(byte[] buf) {
    Objects.requireNonNull(buf, "buf");
    if (buf.length != 8) {
      throw new IllegalArgumentException("expected 8 bytes, got " + buf.length);
    }
    return ByteBuffer.wrap(buf).getLong();
  }

  /** Eight big-endian bytes at {@code offset}. */
  public static long readInt64BE(byte[] buf, int offset) {
    Objects.requireNonNull(buf, "buf");
    if (offset < 0 || buf.length - offset < 8) {
      throw new IllegalArgumentException(
          "need 8 bytes at offset " + offset + ", length=" + buf.length);
    }
    return ByteBuffer.wrap(buf, offset, 8).getLong();
  }

  /** New array: {@code value} as 8 big-endian bytes. */
  public static byte[] writeInt64BE(long value) {
    return ByteBuffer.allocate(8).putLong(value).array();
  }

  /** Writes 8 big-endian bytes of {@code value} at {@code offset} in {@code dest}. */
  public static void writeInt64BE(long value, byte[] dest, int offset) {
    Objects.requireNonNull(dest, "dest");
    if (offset < 0 || dest.length - offset < 8) {
      throw new IllegalArgumentException(
          "need 8 bytes at offset " + offset + ", dest.length=" + dest.length);
    }
    ByteBuffer.wrap(dest, offset, 8).putLong(value);
  }

  /** Four big-endian bytes at {@code offset} as signed int32. */
  public static int readInt32BE(byte[] buf, int offset) {
    Objects.requireNonNull(buf, "buf");
    if (offset < 0 || buf.length - offset < 4) {
      throw new IllegalArgumentException(
          "need 4 bytes at offset " + offset + ", length=" + buf.length);
    }
    return ByteBuffer.wrap(buf, offset, 4).getInt();
  }

  /** Writes 4 big-endian bytes of {@code value} at {@code offset}. */
  public static void writeInt32BE(int value, byte[] dest, int offset) {
    Objects.requireNonNull(dest, "dest");
    if (offset < 0 || dest.length - offset < 4) {
      throw new IllegalArgumentException(
          "need 4 bytes at offset " + offset + ", dest.length=" + dest.length);
    }
    ByteBuffer.wrap(dest, offset, 4).putInt(value);
  }

  /** Delegates to {@link MessageDigest#isEqual(byte[], byte[])} (constant-time for equal lengths). */
  public static boolean constantTimeEquals(byte[] a, byte[] b) {
    return MessageDigest.isEqual(
        Objects.requireNonNull(a, "a"), Objects.requireNonNull(b, "b"));
  }

  /**
   * Concatenate arrays (defensive copy). Empty {@code parts} yields length 0 array; {@code null}
   * elements are rejected.
   */
  public static byte[] concat(byte[]... parts) {
    Objects.requireNonNull(parts, "parts");
    int len = 0;
    for (byte[] p : parts) {
      len += Objects.requireNonNull(p, "part").length;
    }
    byte[] out = new byte[len];
    int pos = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, pos, p.length);
      pos += p.length;
    }
    return out;
  }

  /**
   * View-safe copy of range ({@link Arrays#copyOfRange(byte[], int, int)}).
   */
  public static byte[] copyOfRange(byte[] buf, int from, int to) {
    return Arrays.copyOfRange(Objects.requireNonNull(buf, "buf"), from, to);
  }
}
