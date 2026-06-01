package dev.nivic.coa;

import java.util.Objects;

/**
 * Step 2 — Napas thực hiện chuyển tiền, giải phóng transit, ghi chi phí.
 * Bút toán (một entry balanced):
 * <pre>
 *   DR 3400 (Transit IBFT)    amount + fee
 *   DR 5100 (Chi phí Napas)   napasCost
 *   CR 1112 (TK Napas)        amount + napasCost
 *   CR 4130 (Doanh thu phí)   fee
 * </pre>
 * Lãi thuần = fee − napasCost.
 */
public record IbftSettleCmd(
    long amountMinor,
    long feeMinor,
    long napasCost,
    String settleRef,
    String memo) {

  public IbftSettleCmd {
    Objects.requireNonNull(settleRef, "settleRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (feeMinor    <  0) throw new IllegalArgumentException("feeMinor must be >= 0");
    if (napasCost   <  0) throw new IllegalArgumentException("napasCost must be >= 0");
  }

  public long totalTransitRelease() { return amountMinor + feeMinor; }
  public long napasOutflow()        { return amountMinor + napasCost; }
  public long netProfit()           { return feeMinor - napasCost; }
}
