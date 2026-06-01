package dev.nivic.coa;

import java.util.Objects;

/**
 * Step 2 — Bulk IBFT đến TK NH nhân viên, giải phóng transit, ghi phí.
 * Bút toán (4 legs, balanced):
 * <pre>
 *   DR 3600 (Transit Chi Lương)   amount + totalFee
 *   DR 5100 (Chi phí Napas)       napasCost          ← count × napas_per_tx
 *   CR 4150 (Doanh thu phí)       totalFee
 *   CR 1112 (TK Napas Clearing)   amount + napasCost  ← bulk send + trả phí Napas
 * </pre>
 * Lãi thuần = totalFee − napasCost.
 */
public record PayrollDisburseCmd(
    long amount,
    long totalFee,
    long napasCost,
    String disburseRef,
    String memo) {

  public PayrollDisburseCmd {
    Objects.requireNonNull(disburseRef, "disburseRef");
    if (amount    <= 0) throw new IllegalArgumentException("amount must be positive");
    if (totalFee  <  0) throw new IllegalArgumentException("totalFee must be >= 0");
    if (napasCost <  0) throw new IllegalArgumentException("napasCost must be >= 0");
  }

  public long totalTransitRelease() { return amount + totalFee; }
  public long napasOutflow()        { return amount + napasCost; }
  public long netProfit()           { return totalFee - napasCost; }
}
