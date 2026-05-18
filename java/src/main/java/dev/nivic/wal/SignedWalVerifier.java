package dev.nivic.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Replay WAL: verify Core-signed NVW2 chain (optional public key); pass legacy payloads through. */
public final class SignedWalVerifier {

  private SignedWalVerifier() {}

  public record VerifiedRecord(long seq, byte[] payload, boolean signed) {}

  /**
   * Reads length-prefixed records from {@code path}. For NVW2 bodies, verifies Ed25519 and
   * prev-hash chain when {@code publicKey != null}; invokes {@code consumer} with the <strong>inner
   * client payload</strong> only (same bytes as originally passed to {@link WalService#append}).
   * Legacy records (non-NVW2) are passed through as-is.
   */
  public static void replayVerify(
      Path path, PublicKey publicKey, Consumer<byte[]> consumer) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(consumer, "consumer");
    List<VerifiedRecord> batch = replayVerifyCollect(path, publicKey);
    for (VerifiedRecord r : batch) {
      consumer.accept(r.payload());
    }
  }

  /** Returns each decoded payload and whether it was a signed frame. */
  public static List<VerifiedRecord> replayVerifyCollect(Path path, PublicKey publicKey)
      throws IOException {
    List<VerifiedRecord> out = new ArrayList<>();
    byte[] expectedPrev = new byte[SignedWalConstants.PREV_HASH_LEN]; // zeros
    SimpleWalLog.replay(
        path,
        body -> {
          try {
            if (!SignedWalConstants.startsWithMagic(body)) {
              out.add(new VerifiedRecord(-1L, body, false));
              return;
            }
            ParsedSigned p = parseSignedBody(body, expectedPrev, publicKey);
            out.add(new VerifiedRecord(p.seq(), p.payload(), true));
            System.arraycopy(p.nextPrevHash(), 0, expectedPrev, 0, expectedPrev.length);
          } catch (GeneralSecurityException e) {
            throw new IllegalStateException("WAL verify failed", e);
          }
        });
    return out;
  }

  record ParsedSigned(long seq, byte[] payload, byte[] nextPrevHash) {}

  static ParsedSigned parseSignedBody(byte[] record, byte[] expectedPrev, PublicKey verifyKey)
      throws GeneralSecurityException {
    int min =
        SignedWalConstants.HEADER_LEN + SignedWalConstants.ED25519_SIG_LEN;
    if (record.length < min) {
      throw new GeneralSecurityException("signed WAL record too short: " + record.length);
    }
    ByteBuffer bb = ByteBuffer.wrap(record);
    byte[] magic = new byte[SignedWalConstants.MAGIC_LEN];
    bb.get(magic);
    for (int i = 0; i < SignedWalConstants.MAGIC_LEN; i++) {
      if (magic[i] != SignedWalConstants.MAGIC[i]) {
        throw new GeneralSecurityException("bad magic");
      }
    }
    long seq = bb.getLong();
    byte[] prev = new byte[SignedWalConstants.PREV_HASH_LEN];
    bb.get(prev);
    for (int i = 0; i < prev.length; i++) {
      if (prev[i] != expectedPrev[i]) {
        throw new GeneralSecurityException("prev-hash chain mismatch at seq=" + seq);
      }
    }
    int payloadLen = bb.getInt();
    if (payloadLen < 0 || payloadLen > record.length - SignedWalConstants.HEADER_LEN - SignedWalConstants.ED25519_SIG_LEN) {
      throw new GeneralSecurityException("bad payload length: " + payloadLen);
    }
    byte[] payload = new byte[payloadLen];
    bb.get(payload);
    byte[] sig = new byte[SignedWalConstants.ED25519_SIG_LEN];
    bb.get(sig);
    if (bb.hasRemaining()) {
      throw new GeneralSecurityException("trailing garbage in signed record");
    }
    byte[] signedPrefix = CoreWalSigner.buildSignedPrefix(seq, prev, payloadLen, payload);
    if (verifyKey != null) {
      Signature s = Signature.getInstance("Ed25519");
      s.initVerify(verifyKey);
      s.update(signedPrefix);
      if (!s.verify(sig)) {
        throw new GeneralSecurityException("Ed25519 verify failed at seq=" + seq);
      }
    } // else: structural + chain check only (no signature proof)
    byte[] nextPrev = java.security.MessageDigest.getInstance("SHA-256").digest(record);
    return new ParsedSigned(seq, payload, nextPrev);
  }
}
