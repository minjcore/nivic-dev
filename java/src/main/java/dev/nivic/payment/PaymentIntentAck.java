package dev.nivic.payment;

import java.time.Instant;
import java.util.Objects;

/** Returned to the HTTP layer after a successful order-intent append (challenge + expiry). */
public final class PaymentIntentAck {

  private final String confirmChallengeBase64;
  private final Instant expiresAt;

  public PaymentIntentAck(String confirmChallengeBase64, Instant expiresAt) {
    this.confirmChallengeBase64 =
        Objects.requireNonNull(confirmChallengeBase64, "confirmChallengeBase64");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public String confirmChallengeBase64() {
    return confirmChallengeBase64;
  }

  public Instant expiresAt() {
    return expiresAt;
  }
}
