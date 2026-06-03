package dev.nivic.sevlet.idempotency;

/** Same {@code (mid, requestId)} was claimed with a different {@code orderId} (payment-order check). */
public final class OrderIdMismatchException extends RuntimeException {

  public OrderIdMismatchException(long mid, long requestId, long storedOrderId, long givenOrderId) {
    super(
        "orderId mismatch for mid="
            + Long.toUnsignedString(mid)
            + " requestId="
            + Long.toUnsignedString(requestId)
            + ": stored="
            + Long.toUnsignedString(storedOrderId)
            + " given="
            + Long.toUnsignedString(givenOrderId));
  }
}
