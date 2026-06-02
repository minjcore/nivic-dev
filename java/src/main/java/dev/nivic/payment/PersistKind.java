package dev.nivic.payment;

/** How {@link dev.nivic.payment.disruptor.WalletPersistDisruptor} persists one accepted message. */
public enum PersistKind {
  /** WAL + led_wallet + journal + {@code led_payment} upsert (immediate settle). */
  FULL_POST,
  /** WAL + order intent row (+ optional hold). */
  ORDER_INTENT,
  /** WAL + GL + settle {@code led_payment} by {@code (mid, order_id)}. */
  CONFIRM_SETTLE,
  /** WAL + cancel intent + release hold. */
  REJECT_INTENT
}
