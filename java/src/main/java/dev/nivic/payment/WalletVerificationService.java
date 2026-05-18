package dev.nivic.payment;

import dev.nivic.merchant.MerchantDisabledException;
import dev.nivic.sevlet.SevletWalletPayload;
import dev.nivic.sevlet.secret.MidSecretResolver;
import dev.nivic.sevlet.secret.SevletWalletHmac;
import dev.nivic.sevlet.secret.UnknownMidException;
import java.util.Objects;

/** Verifies trailing HMAC when a {@link MidSecretResolver} is configured; otherwise no-op. */
public final class WalletVerificationService {

  private final MidSecretResolver midSecrets;

  /**
   * @param midSecrets {@code null} means verification is skipped (same as servlet {@code
   *     midSecretMode=skip} or missing DataSource in auto mode)
   */
  public WalletVerificationService(MidSecretResolver midSecrets) {
    this.midSecrets = midSecrets;
  }

  /**
   * @throws UnknownMidException if {@code mid} is missing from the secret store
   * @throws SecurityException if the MAC does not match
   */
  public void verify(byte[] raw, SevletWalletPayload payload) {
    Objects.requireNonNull(raw, "raw");
    Objects.requireNonNull(payload, "payload");
    if (midSecrets == null) {
      return;
    }
    var profile = midSecrets.requireProfile(payload.mid());
    if (!profile.merchantConfig().enabled()) {
      throw new MerchantDisabledException(payload.mid());
    }
    SevletWalletHmac.verify(raw, profile.secretKey());
  }
}
