package dev.nivic.coa;

import java.util.Objects;

/**
 * Step 1 — Trừ số dư ví user, ghi transit thanh toán.
 * Bút toán: DR 2110 (Wallet User) / CR 3500 (Transit Thanh toán).
 *
 * <p>Thanh toán bằng ví không thu phí (theo Fund Flow doc); chỉ chuyển nội bộ ví → merchant.</p>
 */
public record WalletPaymentInitCmd(
    long amount,
    String requestRef,
    String memo) {

  public WalletPaymentInitCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
  }
}
