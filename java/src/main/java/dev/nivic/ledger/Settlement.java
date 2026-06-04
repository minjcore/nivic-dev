package dev.nivic.ledger;

import java.time.Instant;

/**
 * Settlement: Wallet → Bank/Blockchain conversion.
 * State: PENDING → HOLD → POSTED → EXECUTING → CONFIRMED (or FAILED_BANK)
 */
public record Settlement(
    long id,
    long walletId,
    String settlementType,         // MERCHANT, WITHDRAWAL, REBALANCE
    String status,                 // PENDING, HOLD, POSTED, EXECUTING, CONFIRMED, FAILED_BANK
    long amountMinor,
    String currency,
    String destinationBank,        // Bank code or blockchain address
    String bankTransactionId,      // SWIFT/ACH/Blockchain tx hash
    Long transactionId,            // coa_trans.id (null until POSTED)
    Long walletHoldId,             // wallet_hold.id (null until HOLD)
    Instant createdAt,
    Instant confirmedAt
) {
  public boolean isPending() {
    return "PENDING".equals(status);
  }

  public boolean isHeld() {
    return "HOLD".equals(status);
  }

  public boolean isPosted() {
    return "POSTED".equals(status);
  }

  public boolean isExecuting() {
    return "EXECUTING".equals(status);
  }

  public boolean isConfirmed() {
    return "CONFIRMED".equals(status);
  }

  public boolean isFailed() {
    return "FAILED_BANK".equals(status);
  }

  public boolean isMerchantSettlement() {
    return "MERCHANT".equals(settlementType);
  }

  public boolean isWithdrawal() {
    return "WITHDRAWAL".equals(settlementType);
  }
}
