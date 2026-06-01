package dev.nivic.coa;

import java.util.Objects;

/**
 * Step 1 — Trừ ví người gửi, ghi transit nội bộ.
 * Bút toán: DR 2110 (amount + fee) / CR 3300 (Transit Chuyển tiền nội bộ, amount + fee).
 */
public record InternalTransferInitCmd(
    long amountMinor,
    long feeMinor,
    /** Idempotency key for this step. */
    String requestRef,
    String memo) {

  public InternalTransferInitCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (feeMinor    <  0) throw new IllegalArgumentException("feeMinor must be >= 0");
  }

  public long totalDebit() { return amountMinor + feeMinor; }
}
