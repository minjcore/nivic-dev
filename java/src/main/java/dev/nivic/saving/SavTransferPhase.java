package dev.nivic.saving;

public enum SavTransferPhase {
  /** Funds reserved; debit account's {@code debits_pending} incremented. */
  PENDING,
  /** Settled; balances fully updated. */
  POSTED,
  /** Cancelled PENDING; {@code debits_pending} reversed, no net balance change. */
  VOIDED
}
