package dev.nivic.payment;

/** How {@link dev.nivic.payment.disruptor.WalletPersistDisruptor} persists one accepted message. */
public enum PersistKind {
  /** WAL + wallet ledger + journal + {@code payment_ledger} upsert (immediate settle). */
  FULL_POST,
  /** WAL + order intent row (+ optional hold). */
  ORDER_INTENT,
  /** WAL + GL + settle {@code payment_ledger} by {@code (mid, order_id)}. */
  CONFIRM_SETTLE,
  /** WAL + cancel intent + release hold. */
  REJECT_INTENT
}
