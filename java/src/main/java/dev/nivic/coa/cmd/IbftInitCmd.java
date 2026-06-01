package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 1 — Trừ ví user, ghi transit IBFT.
 * Bút toán: DR 2110 (amount + fee) / CR 3400 (Transit IBFT, amount + fee).
 */
public record IbftInitCmd(
    long amountMinor,
    long feeMinor,
    String requestRef,
    String memo,
    /** Ví người gửi (subledger của 2110); null = không gắn party. */
    Long mid) {

  public IbftInitCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (feeMinor    <  0) throw new IllegalArgumentException("feeMinor must be >= 0");
  }

  public IbftInitCmd(long amountMinor, long feeMinor, String requestRef, String memo) {
    this(amountMinor, feeMinor, requestRef, memo, null);
  }

  public long totalDebit() { return amountMinor + feeMinor; }
}
