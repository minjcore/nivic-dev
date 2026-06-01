package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 2 — Đối soát: tách MDR khỏi transit clearing, chuyển phần net sang transit settlement.
 * Bút toán (1 entry 3 legs):
 * <pre>
 *   DR 3800 (Transit Clearing)       totalAmount
 *   CR 3820 (Transit MDR Holdback)   mdrAmount
 *   CR 3810 (Transit Settlement)     totalAmount − mdrAmount
 * </pre>
 */
public record EodReconcileCmd(
    long totalAmount,
    long mdrAmount,
    String reconcileRef,
    String memo) {

  public EodReconcileCmd {
    Objects.requireNonNull(reconcileRef, "reconcileRef");
    if (totalAmount <= 0) throw new IllegalArgumentException("totalAmount must be positive");
    if (mdrAmount   <  0) throw new IllegalArgumentException("mdrAmount must be >= 0");
    if (mdrAmount   >= totalAmount) throw new IllegalArgumentException("mdrAmount must be < totalAmount");
  }

  public long netAmount() { return totalAmount - mdrAmount; }
}
