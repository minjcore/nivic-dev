package dev.nivic.sevlet.idempotency;

/**
 * First-success idempotency on {@code (mid, requestId)} from {@link
 * dev.nivic.sevlet.SevletWalletPayload}. When {@code orderPaymentMode} is {@code true}, duplicate
 * retries must use the same {@code order_id} (order payment phase before transaction posting).
 */
public interface IdempotencyGate {

  /**
   * Records {@code (mid, requestId)} with {@code orderId} on first claim and returns {@code true}.
   *
   * @param orderPaymentMode when {@code true}, an existing claim with a different {@code orderId}
   *     throws {@link OrderIdMismatchException}; when {@code false}, duplicate returns {@code false}
   *     without comparing {@code orderId}
   * @return {@code false} if {@code (mid, requestId)} was already claimed (same stored {@code orderId}
   *     when in order-payment mode)
   */
  boolean claimFirst(long mid, long requestId, long orderId, boolean orderPaymentMode);
}
