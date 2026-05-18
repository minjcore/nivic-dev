package dev.nivic.ledger;

import java.util.Arrays;
import java.util.Objects;

/**
 * Extra columns when persisting an order-payment intent. {@link #NONE} keeps legacy behaviour for
 * non-order and full-settle paths.
 */
public final class PaymentIntentAppendCtx {

  public static final PaymentIntentAppendCtx NONE = new PaymentIntentAppendCtx(false, 0, null);

  private final boolean orderIntent;
  private final int ttlMinutes;
  private final byte[] confirmChallenge;

  private PaymentIntentAppendCtx(boolean orderIntent, int ttlMinutes, byte[] confirmChallenge) {
    this.orderIntent = orderIntent;
    this.ttlMinutes = ttlMinutes;
    this.confirmChallenge = confirmChallenge;
  }

  public static PaymentIntentAppendCtx orderIntent(int ttlMinutes, byte[] confirmChallenge) {
    Objects.requireNonNull(confirmChallenge, "confirmChallenge");
    if (confirmChallenge.length != 32) {
      throw new IllegalArgumentException("confirmChallenge must be 32 bytes");
    }
    if (ttlMinutes <= 0 || ttlMinutes > 24 * 60 * 14) {
      throw new IllegalArgumentException("ttlMinutes out of range");
    }
    return new PaymentIntentAppendCtx(true, ttlMinutes, Arrays.copyOf(confirmChallenge, 32));
  }

  public boolean isOrderIntent() {
    return orderIntent;
  }

  public int ttlMinutes() {
    return ttlMinutes;
  }

  public byte[] confirmChallenge() {
    return confirmChallenge == null ? null : Arrays.copyOf(confirmChallenge, confirmChallenge.length);
  }
}
