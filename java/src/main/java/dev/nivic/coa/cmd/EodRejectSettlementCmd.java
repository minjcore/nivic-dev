package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 5 (Exception) — Đối soát không khớp: hoàn tiền về ví merchant.
 * Áp dụng SAU step 2 (reconcile), TRƯỚC step 3+4 (recognize MDR + settle outbound).
 * Bút toán (3 legs):
 * <pre>
 *   DR 3810 (Transit Settlement)    netAmount
 *   DR 3820 (Transit MDR Holdback)  mdrAmount
 *   CR 2120 (Wallet Merchant)       netAmount + mdrAmount  ← hoàn toàn bộ
 * </pre>
 */
public record EodRejectSettlementCmd(
    long netAmount,
    long mdrAmount,
    String rejectRef,
    String memo) {

  public EodRejectSettlementCmd {
    Objects.requireNonNull(rejectRef, "rejectRef");
    if (netAmount <= 0) throw new IllegalArgumentException("netAmount must be positive");
    if (mdrAmount <  0) throw new IllegalArgumentException("mdrAmount must be >= 0");
  }

  public long totalRefund() { return netAmount + mdrAmount; }
}
