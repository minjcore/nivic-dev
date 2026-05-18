package dev.nivic.sevlet;

import dev.nivic.util.Bytes;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Encode/decode Sevlet wallet binary format (three segments on the wire):
 *
 * <ol>
 *   <li><b>Head</b> — zero padding + {@code command} + numeric fields through {@code credit} (fixed
 *       {@value #PREFIX_BEFORE_EXTRA_LEN} bytes).
 *   <li><b>End</b> — <b>{@code extraData}</b> (0+ bytes), then fixed {@value #SIG_LEN}-byte {@code
 *       sig}.
 * </ol>
 *
 * <pre>
 * | pad(3) | command(8) |&lt;-- HMAC: command … end of extraData --&gt;| sig(32) |
 *          |            | mid … credit | extraData (var)             |
 * </pre>
 *
 * <p><b>HMAC-SHA256</b> is over {@code raw[OFFSET_COMMAND .. raw.length - SIG_LEN)}: from {@code
 * command} through the last byte of {@code extraData}. Only leading 3-byte padding is
 * unauthenticated.</p>
 *
 * <p><b>Flow:</b> last {@value #SIG_LEN} bytes = signature; {@code mid} at {@link #OFFSET_MID}
 * (for merchants equals {@code merchant_id}); secret from DB by {@code mid}; verify {@code
 * HMAC-SHA256(secret, signedBytesForHmac(raw))}.</p>
 *
 * <p>Ingress may cap {@code extraData} length via {@link ExtraDataPolicy} (servlet init {@code
 * maxExtraDataBytes}).</p>
 */
public final class SevletWalletCodec {

  public static final int HEADER_PADDING_LEN = 3;

  /** Trailing HMAC-SHA256 / MAC output size. */
  public static final int SIG_LEN = 32;

  /**
   * Fixed bytes before variable {@code extraData}: padding + command + mid + requestId + orderId +
   * amount + debit + credit.
   */
  public static final int PREFIX_BEFORE_EXTRA_LEN =
      HEADER_PADDING_LEN + 8 + 8 + 8 + 8 + 8 + 4 + 4;

  /** Byte offset of {@code command} (8-byte BE) after zero padding. */
  public static final int OFFSET_COMMAND = HEADER_PADDING_LEN;

  /** Byte offset of {@code mid} (8-byte BE). */
  public static final int OFFSET_MID = OFFSET_COMMAND + 8;

  /** First byte included in HMAC-SHA256 (same as {@link #OFFSET_COMMAND}). */
  public static final int HMAC_INPUT_OFFSET = OFFSET_COMMAND;

  /** Byte offset of {@code requestId} (8-byte BE). */
  public static final int OFFSET_REQUEST_ID = OFFSET_MID + 8;

  /** Byte offset of {@code orderId} (8-byte BE). */
  public static final int OFFSET_ORDER_ID = OFFSET_REQUEST_ID + 8;

  /** Byte offset of {@code amount} (8-byte BE). */
  public static final int OFFSET_AMOUNT = OFFSET_ORDER_ID + 8;

  /** Byte offset of {@code debit} (4-byte BE). */
  public static final int OFFSET_DEBIT = OFFSET_AMOUNT + 8;

  /** Byte offset of {@code credit} (4-byte BE). */
  public static final int OFFSET_CREDIT = OFFSET_DEBIT + 4;

  static {
    if (OFFSET_CREDIT + 4 != PREFIX_BEFORE_EXTRA_LEN) {
      throw new AssertionError("head layout constants out of sync");
    }
  }

  /** Start of variable-length {@code extraData} (end of fixed head). */
  public static final int EXTRA_DATA_OFFSET = PREFIX_BEFORE_EXTRA_LEN;

  /** Minimum wire size: empty {@code extraData} and {@link #SIG_LEN} tail. */
  public static final int MIN_WIRE_LEN = PREFIX_BEFORE_EXTRA_LEN + SIG_LEN;

  /**
   * Length of the signed blob (head + extraData) for a full wire of {@code totalWireLength} bytes.
   */
  public static int signedLength(int totalWireLength) {
    validateTotalWireLength(totalWireLength);
    return totalWireLength - SIG_LEN;
  }

  /**
   * Length of {@code extraData} given total wire size (head + extraData + sig).
   */
  public static int extraDataLength(int totalWireLength) {
    validateTotalWireLength(totalWireLength);
    return totalWireLength - PREFIX_BEFORE_EXTRA_LEN - SIG_LEN;
  }

  private static void validateTotalWireLength(int totalWireLength) {
    if (totalWireLength < MIN_WIRE_LEN) {
      throw new IllegalArgumentException(
          "wire length " + totalWireLength + " < minimum " + MIN_WIRE_LEN);
    }
  }

  /**
   * Bytes over which HMAC-SHA256 is computed: from {@link #OFFSET_COMMAND} through the byte before
   * the trailing {@value #SIG_LEN}-byte {@code sig} ({@code command} through {@code extraData}).
   */
  public static byte[] signedBytesForHmac(byte[] wire) {
    Objects.requireNonNull(wire, "wire");
    if (wire.length < MIN_WIRE_LEN) {
      throw new IllegalArgumentException(
          "wire too short: need at least " + MIN_WIRE_LEN + ", got " + wire.length);
    }
    int macEnd = wire.length - SIG_LEN;
    if (macEnd <= OFFSET_COMMAND) {
      throw new IllegalArgumentException("signed region too short for HMAC from command");
    }
    return Arrays.copyOfRange(wire, OFFSET_COMMAND, macEnd);
  }

  /** Last {@value #SIG_LEN} bytes of {@code wire} (the MAC / signature tail). */
  public static byte[] trailingSignature(byte[] wire) {
    Objects.requireNonNull(wire, "wire");
    if (wire.length < SIG_LEN) {
      throw new IllegalArgumentException("wire shorter than SIG_LEN");
    }
    return Arrays.copyOfRange(wire, wire.length - SIG_LEN, wire.length);
  }

  /**
   * Read {@code mid} at {@link #OFFSET_MID} without parsing the rest (e.g. DB secret lookup before
   * full verify).
   */
  public static long peekMid(byte[] wire) {
    Objects.requireNonNull(wire, "wire");
    if (wire.length < OFFSET_MID + 8) {
      throw new IllegalArgumentException(
          "wire too short for mid at offset " + OFFSET_MID + ": length " + wire.length);
    }
    return Bytes.readInt64BE(wire, OFFSET_MID);
  }

  private SevletWalletCodec() {}

  /** Full wire: pad + fixed head + {@code extraData} + trailing {@code sig}. */
  public static byte[] encode(SevletWalletPayload p) {
    int bodyLen = PREFIX_BEFORE_EXTRA_LEN + p.extraData().length;
    byte[] out = new byte[bodyLen + SIG_LEN];
    ByteBuffer buf = ByteBuffer.wrap(out, 0, bodyLen);
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.putLong(p.command());
    buf.putLong(p.mid());
    buf.putLong(p.requestId());
    buf.putLong(p.orderId());
    buf.putLong(p.amount());
    buf.putInt(p.debit());
    buf.putInt(p.credit());
    buf.put(p.extraData());
    System.arraycopy(p.sig(), 0, out, bodyLen, SIG_LEN);
    return out;
  }

  /**
   * Parse full wire buffer. Copies {@code extraData} and trailing {@code sig}.
   *
   * @throws IllegalArgumentException if buffer shorter than {@link #MIN_WIRE_LEN} or signed region
   *     shorter than {@link #PREFIX_BEFORE_EXTRA_LEN}
   */
  public static SevletWalletPayload decode(byte[] buf) {
    if (buf.length < MIN_WIRE_LEN) {
      throw new IllegalArgumentException(
          "buffer too short: need at least " + MIN_WIRE_LEN + ", got " + buf.length);
    }
    byte[] sig = trailingSignature(buf);
    byte[] signed = Arrays.copyOfRange(buf, 0, buf.length - SIG_LEN);
    if (signed.length < PREFIX_BEFORE_EXTRA_LEN) {
      throw new IllegalArgumentException("signed region too short: " + signed.length);
    }
    ByteBuffer bb = ByteBuffer.wrap(signed);
    if (bb.get() != 0 || bb.get() != 0 || bb.get() != 0) {
      throw new IllegalArgumentException("first 3 header bytes must be zero padding");
    }
    long command = bb.getLong();
    long mid = bb.getLong();
    long requestId = bb.getLong();
    long orderId = bb.getLong();
    long amount = bb.getLong();
    int debit = bb.getInt();
    int credit = bb.getInt();
    byte[] extra = Arrays.copyOfRange(signed, EXTRA_DATA_OFFSET, signed.length);
    return new SevletWalletPayload(
        command, mid, requestId, orderId, amount, debit, credit, extra, sig);
  }
}
