package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 1 — Lock ví merchant doanh nghiệp, ghi transit chi lương.
 * Bút toán: DR 2120 (amount + totalFee) / CR 3600 (Transit Chi Lương, amount + totalFee).
 *
 * <p>totalFee = count × feePerEmployee (ví dụ 5 nhân viên × 1,000đ = 5,000đ).</p>
 */
public record PayrollInitCmd(
    long amount,
    long totalFee,
    int  employeeCount,
    String requestRef,
    String memo) {

  public PayrollInitCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amount        <= 0) throw new IllegalArgumentException("amount must be positive");
    if (totalFee      <  0) throw new IllegalArgumentException("totalFee must be >= 0");
    if (employeeCount <= 0) throw new IllegalArgumentException("employeeCount must be positive");
  }

  public long totalLock() { return amount + totalFee; }
}
