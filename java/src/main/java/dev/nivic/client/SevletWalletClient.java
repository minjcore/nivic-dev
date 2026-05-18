package dev.nivic.client;

import dev.nivic.sevlet.SevletWalletCodec;
import dev.nivic.sevlet.SevletWalletPayload;
import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Client-side builder for the Sevlet wallet wire format: {@link SevletWalletCodec#encode} then
 * HMAC-SHA256(secret, {@link SevletWalletCodec#signedBytesForHmac(byte[])}), written to the trailing
 * 32 bytes.
 */
public final class SevletWalletClient {

  private SevletWalletClient() {}

  /**
   * Returns a full wire buffer with {@code sig} computed. Any {@code payload.sig()} is ignored; use
   * placeholders if constructing from a record.
   */
  public static byte[] sign(SevletWalletPayload payload, byte[] hmacSecret) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(hmacSecret, "hmacSecret");
    SevletWalletPayload unsigned =
        new SevletWalletPayload(
            payload.command(),
            payload.mid(),
            payload.requestId(),
            payload.orderId(),
            payload.amount(),
            payload.debit(),
            payload.credit(),
            payload.extraData(),
            new byte[SevletWalletCodec.SIG_LEN]);
    byte[] wire = SevletWalletCodec.encode(unsigned);
    byte[] tag = hmacSha256(hmacSecret, SevletWalletCodec.signedBytesForHmac(wire));
    System.arraycopy(tag, 0, wire, wire.length - SevletWalletCodec.SIG_LEN, SevletWalletCodec.SIG_LEN);
    return wire;
  }

  private static byte[] hmacSha256(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
