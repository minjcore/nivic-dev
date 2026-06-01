package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 2 — NH thực hiện chuyển tiền, giải phóng transit.
 * Bút toán:
 * <pre>
 *   DR 3200 (Transit Rút)     amountMinor + feeMinor
 *   CR 1111 (TK Vietinbank)   amountMinor             ← tiền về TK user
 *   CR 4120 (Doanh thu phí)   feeMinor
 * </pre>
 */
public record WithdrawSettleCmd(
    long amountMinor,
    long feeMinor,
    /** Idempotency key for this settlement step. */
    String settleRef,
    String memo) {

  public WithdrawSettleCmd {
    Objects.requireNonNull(settleRef, "settleRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (feeMinor    <  0) throw new IllegalArgumentException("feeMinor must be >= 0");
  }

  public long totalTransitRelease() { return amountMinor + feeMinor; }
}
