package dev.nivic.payment;

import java.util.Objects;
import java.util.Optional;

/** Outcome of {@link WalletAcceptService#claimAndPersist(byte[], dev.nivic.sevlet.SevletWalletPayload)}. */
public final class AcceptResult {

  private final boolean duplicate;
  private final PaymentIntentAck intentAck;

  private AcceptResult(boolean duplicate, PaymentIntentAck intentAck) {
    this.duplicate = duplicate;
    this.intentAck = intentAck;
  }

  public static AcceptResult idempotentDuplicate() {
    return new AcceptResult(true, null);
  }

  public static AcceptResult ok() {
    return new AcceptResult(false, null);
  }

  public static AcceptResult okWithIntentAck(PaymentIntentAck ack) {
    return new AcceptResult(false, Objects.requireNonNull(ack, "ack"));
  }

  public boolean isDuplicate() {
    return duplicate;
  }

  public Optional<PaymentIntentAck> intentAck() {
    return Optional.ofNullable(intentAck);
  }
}
