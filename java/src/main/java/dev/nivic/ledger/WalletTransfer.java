package dev.nivic.ledger;

import java.time.Instant;

/**
 * A→B transfer: PENDING → POSTED (ledger) → CONFIRMED (both wallets).
 * Ref_id ensures idempotency (same transfer never posted twice).
 */
public record WalletTransfer(
    long id,
    long fromWalletId,
    long toWalletId,
    long amountMinor,
    String currencyCode,
    String status,                 // PENDING, POSTED, CONFIRMED, FAILED
    Long transactionId,            // coa_trans.id reference (null until POSTED)
    String refId,                  // idempotency key
    String memo,
    Instant createdAt,
    Instant confirmedAt
) {
  public boolean isPending() {
    return "PENDING".equals(status);
  }

  public boolean isPosted() {
    return "POSTED".equals(status);
  }

  public boolean isConfirmed() {
    return "CONFIRMED".equals(status);
  }

  public boolean isFailed() {
    return "FAILED".equals(status);
  }
}
