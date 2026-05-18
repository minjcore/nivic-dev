package dev.nivic.payment;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * {@code extraData} for CONFIRM / REJECT — <strong>profile v0</strong> prefix:
 *
 * <ul>
 *   <li>Bytes {@code [0..7]}: original intent {@code request_id} (big-endian int64).
 *   <li>Bytes {@code [8..39]}: 32-byte {@code confirm_challenge} to match stored intent.
 *   <li>Bytes {@code [40..)}}: optional application TLV or extensions; ignored by Core v0
 *       parsers ({@link #challenge} only reads the fixed prefix).
 * </ul>
 */
public final class ConfirmPayloadParser {

  /** Length of the fixed v0 prefix: {@code request_id} + {@code challenge}. */
  public static final int CONFIRM_V0_PREFIX_LEN = 8 + 32;

  public static final int MIN_EXTRA_LEN = CONFIRM_V0_PREFIX_LEN;

  private ConfirmPayloadParser() {}

  public static void validateExtra(byte[] extra) {
    Objects.requireNonNull(extra, "extra");
    if (extra.length < MIN_EXTRA_LEN) {
      throw new IllegalArgumentException(
          "extraData must be at least " + MIN_EXTRA_LEN + " bytes for confirm/reject");
    }
  }

  public static long originalRequestId(byte[] extra) {
    validateExtra(extra);
    return ByteBuffer.wrap(extra).getLong();
  }

  public static byte[] challenge(byte[] extra) {
    validateExtra(extra);
    return Arrays.copyOfRange(extra, 8, 40);
  }
}
