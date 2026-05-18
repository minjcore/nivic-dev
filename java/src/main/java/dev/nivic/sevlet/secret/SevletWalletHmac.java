package dev.nivic.sevlet.secret;

import dev.nivic.sevlet.SevletWalletCodec;
import dev.nivic.util.Bytes;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256: MAC input is {@code raw[OFFSET_COMMAND .. raw.length - SIG_LEN)} ({@code command}
 * through {@code extraData}). Trailing {@value SevletWalletCodec#SIG_LEN} bytes are the tag. Only
 * leading 3-byte padding is unauthenticated.
 */
public final class SevletWalletHmac {

  private SevletWalletHmac() {}

  /**
   * Verifies trailing HMAC-SHA256 using {@link SevletWalletCodec#signedBytesForHmac(byte[])}.
   *
   * @throws IllegalArgumentException if {@code raw} is shorter than {@link SevletWalletCodec#MIN_WIRE_LEN}
   * @throws SecurityException if the MAC does not match
   */
  public static void verify(byte[] raw, byte[] secretKey) {
    if (raw.length < SevletWalletCodec.MIN_WIRE_LEN) {
      throw new IllegalArgumentException("raw too short for wire + sig");
    }
    byte[] macInput = SevletWalletCodec.signedBytesForHmac(raw);
    byte[] sigInWire = SevletWalletCodec.trailingSignature(raw);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
      byte[] expected = mac.doFinal(macInput);
      if (!Bytes.constantTimeEquals(expected, sigInWire)) {
        throw new SecurityException("HMAC signature mismatch");
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
