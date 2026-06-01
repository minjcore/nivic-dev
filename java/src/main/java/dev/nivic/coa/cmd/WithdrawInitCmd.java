package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 1 — User khởi tạo rút tiền.
 * Bút toán: DR 2110 (Wallet User, amount + fee) / CR 3200 (Transit Rút, amount + fee).
 */
public record WithdrawInitCmd(
    long amountMinor,
    long feeMinor,
    /** Unique request reference — idempotency key for this step. */
    String requestRef,
    String memo) {

  public WithdrawInitCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (feeMinor    <  0) throw new IllegalArgumentException("feeMinor must be >= 0");
  }

  public long totalDebit() { return amountMinor + feeMinor; }
}
