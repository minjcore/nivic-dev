package dev.nivic.ledger;

/** Another open {@code payment_ledger} row already uses this {@code (mid, order_id)}. */
public final class OrderIdConflictException extends RuntimeException {

  private final long mid;
  private final long orderId;
  private final long conflictingRequestId;

  public OrderIdConflictException(long mid, long orderId, long conflictingRequestId) {
    super(
        "open payment intent already exists for mid="
            + mid
            + " order_id="
            + orderId
            + " (other request_id="
            + conflictingRequestId
            + ")");
    this.mid = mid;
    this.orderId = orderId;
    this.conflictingRequestId = conflictingRequestId;
  }

  public long mid() {
    return mid;
  }

  public long orderId() {
    return orderId;
  }

  /** {@code -1} when the conflict was detected only from a DB unique constraint. */
  public long conflictingRequestId() {
    return conflictingRequestId;
  }
}
