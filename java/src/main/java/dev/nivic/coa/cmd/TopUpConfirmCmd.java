package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 2 — Xác nhận nạp tiền thành công.
 * Bút toán:
 * <pre>
 *   DR 3100 (Transit Nạp)      amountMinor
 *   CR 2110 (Wallet User)      amountMinor − feeMinor
 *   CR 4110 (Doanh thu phí)    feeMinor
 * </pre>
 */
public record TopUpConfirmCmd(
    long amountMinor,
    long feeMinor,
    /** Idempotency key for this confirmation step. */
    String confirmRef,
    String memo) {

  public TopUpConfirmCmd {
    Objects.requireNonNull(confirmRef, "confirmRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (feeMinor < 0)     throw new IllegalArgumentException("feeMinor must be >= 0");
    if (feeMinor >= amountMinor) throw new IllegalArgumentException("feeMinor must be < amountMinor");
  }
}
