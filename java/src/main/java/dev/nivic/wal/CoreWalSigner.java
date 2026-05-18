package dev.nivic.wal;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Objects;

/**
 * Thread-safe Ed25519 seal for each WAL payload: signs {@code magic ‖ seq ‖ prevHash ‖
 * payloadLen ‖ payload}; updates monotonic {@code seq} and SHA-256 hash-chain for the next
 * record.
 */
public final class CoreWalSigner {

  private final PrivateKey privateKey;
  private long seq;
  private final byte[] prevHash;

  public CoreWalSigner(PrivateKey privateKey) {
    this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
    if (!isEd25519PrivateKey(privateKey)) {
      throw new IllegalArgumentException(
          "expected Ed25519 private key, got " + privateKey.getAlgorithm());
    }
    this.prevHash = new byte[SignedWalConstants.PREV_HASH_LEN];
  }

  private static boolean isEd25519PrivateKey(PrivateKey privateKey) {
    if ("Ed25519".equalsIgnoreCase(privateKey.getAlgorithm())) {
      return true;
    }
    if (privateKey instanceof java.security.interfaces.EdECPrivateKey ed) {
      return "Ed25519".equalsIgnoreCase(ed.getParams().getName());
    }
    return false;
  }

  public synchronized byte[] signRecord(byte[] payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload.length > 0x7fff_ffff) {
      throw new IllegalArgumentException("payload too large for WAL framing");
    }
    long currentSeq = seq;
    byte[] signedPrefix =
        buildSignedPrefix(currentSeq, prevHash, payload.length, payload);
    byte[] sig = ed25519Sign(signedPrefix);
    byte[] record =
        ByteBuffer.allocate(
                SignedWalConstants.HEADER_LEN
                    + payload.length
                    + SignedWalConstants.ED25519_SIG_LEN)
            .put(SignedWalConstants.MAGIC)
            .putLong(currentSeq)
            .put(prevHash)
            .putInt(payload.length)
            .put(payload)
            .put(sig)
            .array();
    byte[] nextPrev = sha256(record);
    System.arraycopy(nextPrev, 0, prevHash, 0, prevHash.length);
    seq = currentSeq + 1L;
    return record;
  }

  static byte[] buildSignedPrefix(long seq, byte[] prevHash, int payloadLen, byte[] payload) {
    return ByteBuffer.allocate(
            SignedWalConstants.MAGIC_LEN
                + SignedWalConstants.SEQ_LEN
                + SignedWalConstants.PREV_HASH_LEN
                + SignedWalConstants.PAYLOAD_LEN_FIELD
                + payload.length)
        .put(SignedWalConstants.MAGIC)
        .putLong(seq)
        .put(prevHash, 0, SignedWalConstants.PREV_HASH_LEN)
        .putInt(payloadLen)
        .put(payload)
        .array();
  }

  private byte[] ed25519Sign(byte[] message) {
    try {
      Signature s = Signature.getInstance("Ed25519");
      s.initSign(privateKey);
      s.update(message);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 sign failed", e);
    }
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
