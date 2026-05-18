package dev.nivic.sevlet.secret;

/** No row in {@code wallet_mid_secret} for the given merchant / member id. */
public final class UnknownMidException extends RuntimeException {
  public UnknownMidException(long mid) {
    super("unknown mid: " + Long.toUnsignedString(mid));
  }
}
