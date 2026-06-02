package dev.nivic.saving;

import java.util.Objects;

public record WithdrawalCmd(
    long debitAccountId,
    long amountMinor,
    String currencyCode,
    /** True → create PENDING transfer (two-phase, requires {@link SavingLedger#postPending} later). */
    boolean pending,
    Long idempotencyKey,
    Long refMid,
    Long refRequestId,
    String memo) {

  public WithdrawalCmd {
    Objects.requireNonNull(debitAccountId, "debitAccountId");
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
  }
}
