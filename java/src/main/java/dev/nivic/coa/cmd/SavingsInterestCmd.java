package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Ghi lãi tiền gửi tiết kiệm (chi phí của nền tảng). Bút toán sổ cái:
 * DR 5200 (chi phí lãi) / CR 2140 (tiền gửi tiết kiệm, party_mid).
 */
public record SavingsInterestCmd(long mid, long amount, String requestRef, String memo) {
  public SavingsInterestCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
  }
}
