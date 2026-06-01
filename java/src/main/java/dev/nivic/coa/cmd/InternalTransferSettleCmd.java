package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 2 — Cộng ví người nhận, giải phóng transit nội bộ.
 * Bút toán:
 * <pre>
 *   DR 3300 (Transit Nội bộ)   amountMinor + feeMinor
 *   CR 2110 (Wallet User)      amountMinor             ← người nhận
 *   CR 4130 (Doanh thu phí)    feeMinor
 * </pre>
 * Lưu ý: không phát sinh tài khoản NH (1111/1112/1113).
 */
public record InternalTransferSettleCmd(
    long amountMinor,
    long feeMinor,
    String settleRef,
    String memo) {

  public InternalTransferSettleCmd {
    Objects.requireNonNull(settleRef, "settleRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (feeMinor    <  0) throw new IllegalArgumentException("feeMinor must be >= 0");
  }

  public long totalTransitRelease() { return amountMinor + feeMinor; }
}
