package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Rút tiết kiệm → ví. Bút toán sổ cái:
 * DR 2140 (tiền gửi tiết kiệm, party_mid) / CR 2110 (ví user, party_mid).
 */
public record SavingsWithdrawCmd(long mid, long amount, String requestRef, String memo) {
  public SavingsWithdrawCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
  }
}
