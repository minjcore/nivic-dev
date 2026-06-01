package dev.nivic.coa;

import java.util.Objects;

/**
 * Step 1 — Trừ ví user, ghi transit IBFT.
 * Bút toán: DR 2110 (amount + fee) / CR 3400 (Transit IBFT, amount + fee).
 */
public record IbftInitCmd(
    long amountMinor,
    long feeMinor,
    String requestRef,
    String memo) {

  public IbftInitCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (feeMinor    <  0) throw new IllegalArgumentException("feeMinor must be >= 0");
  }

  public long totalDebit() { return amountMinor + feeMinor; }
}
