package dev.nivic.coa;

import java.util.Objects;

/**
 * Step 3 — Xác nhận đối soát thành công, ghi nhận doanh thu MDR.
 * Bút toán: DR 3820 (Transit MDR Holdback) / CR 4140 (Doanh thu Phí MDR).
 */
public record EodRecognizeMdrCmd(
    long mdrAmount,
    String mdrRef,
    String memo) {

  public EodRecognizeMdrCmd {
    Objects.requireNonNull(mdrRef, "mdrRef");
    if (mdrAmount <= 0) throw new IllegalArgumentException("mdrAmount must be positive");
  }
}
