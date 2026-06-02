package dev.nivic.analytics;

import dev.nivic.wal.SignedWalConstants;
import dev.nivic.wal.SignedWalVerifier;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Reads new length-prefixed WAL records from a byte offset (NVW2-aware). */
public final class WalIncrementalReader {

  private WalIncrementalReader() {}

  public record WalRecord(
      long recordEndOffset,
      SignedWalVerifier.VerifiedRecord verified,
      byte[] nextPrevHash) {}

  /**
   * Drains records from {@code walPath} starting at {@code cursor}; invokes {@code consumer} for
   * each decoded frame. Returns updated cursor (offset + prev-hash for signed chain).
   */
  public static WalShipperCursor drain(
      Path walPath,
      WalShipperCursor cursor,
      PublicKey verifyKey,
      Consumer<WalRecord> consumer)
      throws IOException {
    Objects.requireNonNull(walPath, "walPath");
    Objects.requireNonNull(cursor, "cursor");
    Objects.requireNonNull(consumer, "consumer");
    if (!Files.exists(walPath)) {
      return cursor;
    }

    long offset = cursor.byteOffset();
    byte[] expectedPrev = Arrays.copyOf(cursor.prevHash(), cursor.prevHash().length);

    try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.READ)) {
      long size = ch.size();
      if (offset > size) {
        offset = 0L;
        expectedPrev = new byte[SignedWalConstants.PREV_HASH_LEN];
      }

      while (offset < size) {
        long recordStart = offset;
        ByteBuffer lenBuf = ByteBuffer.allocate(4);
        ch.position(offset);
        if (!readFully(ch, lenBuf)) {
          break;
        }
        lenBuf.flip();
        int len = lenBuf.getInt();
        if (len < 0) {
          throw new IOException("invalid WAL record length at offset " + recordStart + ": " + len);
        }
        offset += 4;

        byte[] body;
        if (len == 0) {
          body = new byte[0];
        } else {
          ByteBuffer bodyBuf = ByteBuffer.allocate(len);
          ch.position(offset);
          if (!readFully(ch, bodyBuf)) {
            break;
          }
          body = bodyBuf.array();
          offset += len;
        }

        SignedWalVerifier.VerifiedRecord verified;
        byte[] nextPrev;
        try {
          if (!SignedWalConstants.startsWithMagic(body)) {
            verified = new SignedWalVerifier.VerifiedRecord(-1L, body, false);
            nextPrev = expectedPrev;
          } else {
            SignedWalVerifier.ParsedSigned parsed =
                SignedWalVerifier.parseSignedBody(body, expectedPrev, verifyKey);
            verified =
                new SignedWalVerifier.VerifiedRecord(parsed.seq(), parsed.payload(), true);
            nextPrev = parsed.nextPrevHash();
            expectedPrev = nextPrev;
          }
        } catch (GeneralSecurityException e) {
          throw new IOException("WAL verify failed at offset " + recordStart, e);
        }

        consumer.accept(new WalRecord(offset, verified, Arrays.copyOf(nextPrev, nextPrev.length)));
        cursor = new WalShipperCursor(offset, expectedPrev);
      }
    }
    return cursor;
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

  /** Next prev-hash after a signed NVW2 frame (for tests). */
  static byte[] nextPrevHashForSignedFrame(byte[] framedBody) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(framedBody);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
