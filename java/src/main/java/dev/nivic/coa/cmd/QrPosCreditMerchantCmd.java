package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Step 2 — Giải phóng transit, ghi ví merchant (chờ Settlement & Clearing EOD).
 * Bút toán:
 * <pre>
 *   DR 3500 (Transit Thanh toán)  amount
 *   CR 2120 (Wallet Merchant)     amount
 * </pre>
 * Merchant wallet giữ tiền đến khi Settlement & Clearing (Use Case 10) xử lý EOD.
 */
public record QrPosCreditMerchantCmd(
    long amountMinor,
    String settleRef,
    String memo) {

  public QrPosCreditMerchantCmd {
    Objects.requireNonNull(settleRef, "settleRef");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
  }
}
