package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Bước 1b — Napas gửi đến bên thụ hưởng, giải phóng transit, ghi phí.
 * Bút toán (4 legs, balanced):
 * <pre>
 *   DR 3700 (Transit Chi hộ)    amount + fee
 *   DR 5100 (Chi phí Napas)     napasCost
 *   CR 4150 (Doanh thu phí)     fee
 *   CR 1112 (TK Napas Clearing) amount + napasCost
 * </pre>
 * Lãi thuần = fee − napasCost.
 */
public record DisbursementSettleCmd(
    long amount,
    long fee,
    long napasCost,
    String settleRef,
    String memo) {

  public DisbursementSettleCmd {
    Objects.requireNonNull(settleRef, "settleRef");
    if (amount    <= 0) throw new IllegalArgumentException("amount must be positive");
    if (fee       <  0) throw new IllegalArgumentException("fee must be >= 0");
    if (napasCost <  0) throw new IllegalArgumentException("napasCost must be >= 0");
  }

  public long totalTransitRelease() { return amount + fee; }
  public long napasOutflow()        { return amount + napasCost; }
  public long netProfit()           { return fee - napasCost; }
}
