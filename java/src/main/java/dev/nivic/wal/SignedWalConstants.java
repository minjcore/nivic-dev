package dev.nivic.wal;

/** Framed WAL records written by Core (distinct from legacy raw client wire). */
public final class SignedWalConstants {

  private SignedWalConstants() {}

  /** Magic identifying Core-signed chained records (ASCII "NVW2"). */
  public static final byte[] MAGIC = {'N', 'V', 'W', '2'};

  public static final int MAGIC_LEN = 4;
  public static final int SEQ_LEN = 8;
  public static final int PREV_HASH_LEN = 32;
  public static final int PAYLOAD_LEN_FIELD = 4;
  public static final int ED25519_SIG_LEN = 64;

  public static final int HEADER_LEN =
      MAGIC_LEN + SEQ_LEN + PREV_HASH_LEN + PAYLOAD_LEN_FIELD;

  public static boolean startsWithMagic(byte[] body) {
    if (body == null || body.length < MAGIC_LEN) {
      return false;
    }
    for (int i = 0; i < MAGIC_LEN; i++) {
      if (body[i] != MAGIC[i]) {
        return false;
      }
    }
    return true;
  }
}
