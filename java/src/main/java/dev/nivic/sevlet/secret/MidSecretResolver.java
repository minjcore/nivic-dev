package dev.nivic.sevlet.secret;

/** Resolves HMAC secret and policy for a {@code mid} from {@link dev.nivic.sevlet.SevletWalletPayload}. */
public interface MidSecretResolver {

  /**
   * @throws UnknownMidException if no row exists for {@code mid}
   */
  MidProfile requireProfile(long mid);

  /** @throws UnknownMidException if no secret is configured for {@code mid} */
  default byte[] requireSecretKey(long mid) {
    return requireProfile(mid).secretKey();
  }
}
