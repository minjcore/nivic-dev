package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 1 — VPBank nhận tiền từ user qua QR/POS, ghi transit + chi phí VPBank.
 * Bút toán (4 legs, balanced):
 * <pre>
 *   DR 1113 (TK VPBank)     amount
 *   CR 3500 (Transit TT)    amount
 *   DR 5100 (Chi phí NH)    vpbankCost
 *   CR 1113 (TK VPBank)     vpbankCost   ← trả phí cho VPBank
 * </pre>
 * Net 1113 = amount − vpbankCost (platform nhận tiền sau khi trừ phí VPBank).
 */
public record QrPosReceiveCmd(
    long amountMinor,
    long vpbankCost,
    String requestRef,
    String memo) {

  public QrPosReceiveCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    if (vpbankCost  <  0) throw new IllegalArgumentException("vpbankCost must be >= 0");
    if (vpbankCost  >= amountMinor) throw new IllegalArgumentException("vpbankCost must be < amountMinor");
  }

  /** Net amount credited to VPBank account after paying VPBank fee. */
  public long netVpbank() { return amountMinor - vpbankCost; }
}
