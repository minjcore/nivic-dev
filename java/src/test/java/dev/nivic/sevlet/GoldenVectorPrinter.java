package dev.nivic.sevlet;

import dev.nivic.command.WalletInputOp;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Prints deterministic golden vectors for cross-language SDK tests.
 * Run once; copy hex output into sdk/sevlet/sevlet_cross_test.go.
 */
class GoldenVectorPrinter {

  // Fixed values — same as HotPathTest constants
  static final long   MID        = 42L;
  static final long   REQUEST_ID = 1000L;
  static final long   ORDER_ID   = 999L;
  static final long   AMOUNT     = 50_000L;
  static final int    DEBIT      = 1;
  static final int    CREDIT     = 2;

  // Deterministic 32-byte secret (ASCII, no random)
  static final byte[] SECRET = "cross-check-secret-32-bytes-pad!".getBytes();

  static {
    if (SECRET.length != 32) throw new IllegalStateException("secret must be 32 bytes: got " + SECRET.length);
  }

  @Test
  void printTransferVector() throws Exception {
    HexFormat hex = HexFormat.of().withUpperCase();

    byte[] raw = buildSigned(WalletInputOp.TRANSFER, REQUEST_ID, new byte[0]);
    System.out.println("=== TRANSFER (empty extraData) ===");
    System.out.println("wire_hex: " + hex.formatHex(raw));
    System.out.println("sig_hex:  " + hex.formatHex(Arrays.copyOfRange(raw, raw.length - 32, raw.length)));
    System.out.println("wire_len: " + raw.length);

    // Verify with the codec
    SevletWalletPayload p = SevletWalletCodec.decode(raw);
    System.out.println("decoded.command:   " + p.command());
    System.out.println("decoded.mid:       " + p.mid());
    System.out.println("decoded.requestId: " + p.requestId());
    System.out.println("decoded.orderId:   " + p.orderId());
    System.out.println("decoded.amount:    " + p.amount());
    System.out.println("decoded.debit:     " + p.debit());
    System.out.println("decoded.credit:    " + p.credit());
    System.out.println();

    // Confirm extra
    byte[] challenge = new byte[32];
    Arrays.fill(challenge, (byte) 0x07);
    long origReqID = 0xDEADBEEFL;
    byte[] extra = ByteBuffer.allocate(40).putLong(origReqID).put(challenge).array();
    byte[] confirmRaw = buildSigned(WalletInputOp.CONFIRM_PAYMENT, 2000L, extra);
    System.out.println("=== CONFIRM_PAYMENT ===");
    System.out.println("wire_hex: " + hex.formatHex(confirmRaw));
    System.out.println("sig_hex:  " + hex.formatHex(Arrays.copyOfRange(confirmRaw, confirmRaw.length - 32, confirmRaw.length)));
    System.out.println("extra_hex: " + hex.formatHex(extra));
    System.out.println("originalRequestId: " + origReqID);
    System.out.println();

    // ConfirmPayloadParserTest vector
    byte[] challenge2 = new byte[32];
    Arrays.fill(challenge2, (byte) 7);
    byte[] extra2 = new byte[40 + 12];
    ByteBuffer.wrap(extra2).putLong(0xDEADBEEFL).put(challenge2);
    Arrays.fill(extra2, 40, extra2.length, (byte) 42);
    System.out.println("=== ConfirmPayloadParser v0 ===");
    System.out.println("extra_hex: " + hex.formatHex(extra2));
    System.out.println("originalRequestId: " + ByteBuffer.wrap(extra2).getLong());
  }

  private static byte[] buildSigned(long command, long requestId, byte[] extraData) throws Exception {
    int bodyLen = SevletWalletCodec.PREFIX_BEFORE_EXTRA_LEN + extraData.length;
    byte[] raw = new byte[bodyLen + SevletWalletCodec.SIG_LEN];
    ByteBuffer buf = ByteBuffer.wrap(raw);
    buf.put((byte) 0); buf.put((byte) 0); buf.put((byte) 0);
    buf.putLong(command);
    buf.putLong(MID);
    buf.putLong(requestId);
    buf.putLong(ORDER_ID);
    buf.putLong(AMOUNT);
    buf.putInt(DEBIT);
    buf.putInt(CREDIT);
    buf.put(extraData);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
    byte[] sig = mac.doFinal(SevletWalletCodec.signedBytesForHmac(raw));
    System.arraycopy(sig, 0, raw, bodyLen, SevletWalletCodec.SIG_LEN);
    return raw;
  }
}
