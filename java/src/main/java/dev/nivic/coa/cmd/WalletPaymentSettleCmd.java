package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 2 — Giải phóng transit, cộng ví merchant (chờ Settlement &amp; Clearing EOD).
 * Bút toán: DR 3500 (Transit Thanh toán) / CR 2120 (Wallet Merchant).
 *
 * <p>2120 giữ tiền đến khi Settlement &amp; Clearing (Use Case 11) xử lý EOD.</p>
 */
public record WalletPaymentSettleCmd(
    long amount,
    String settleRef,
    String memo) {

  public WalletPaymentSettleCmd {
    Objects.requireNonNull(settleRef, "settleRef");
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
  }
}
