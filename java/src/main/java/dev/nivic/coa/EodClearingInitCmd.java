package dev.nivic.coa;

import java.util.Objects;

/**
 * Step 1 — Lock toàn bộ số dư ví merchant, ghi transit clearing.
 * Bút toán: DR 2120 (Wallet Merchant) / CR 3800 (Transit Clearing).
 */
public record EodClearingInitCmd(
    long totalAmount,
    String clearingRef,
    String memo) {

  public EodClearingInitCmd {
    Objects.requireNonNull(clearingRef, "clearingRef");
    if (totalAmount <= 0) throw new IllegalArgumentException("totalAmount must be positive");
  }
}
