package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 4 — Settlement Outbound: chuyển tiền về TK NH merchant qua Napas + ghi chi phí.
 * Bút toán (3 legs):
 * <pre>
 *   DR 3810 (Transit Settlement)   netAmount
 *   DR 5100 (Chi phí Napas)        napasCost
 *   CR 1112 (TK Napas Clearing)    netAmount + napasCost
 * </pre>
 * Lãi thuần EOD = MDR revenue (4140) − Napas cost (5100).
 */
public record EodSettleOutboundCmd(
    long netAmount,
    long napasCost,
    String settleRef,
    String memo) {

  public EodSettleOutboundCmd {
    Objects.requireNonNull(settleRef, "settleRef");
    if (netAmount  <= 0) throw new IllegalArgumentException("netAmount must be positive");
    if (napasCost  <  0) throw new IllegalArgumentException("napasCost must be >= 0");
  }

  public long napasOutflow() { return netAmount + napasCost; }
}
