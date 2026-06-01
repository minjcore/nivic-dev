package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Chuyển tiền ví → tiết kiệm (savings ví). Bút toán sổ cái:
 * DR 2110 (ví user, party_mid) / CR 2140 (tiền gửi tiết kiệm, party_mid).
 * Tái phân loại nợ phải trả; tiền vẫn trong nền tảng.
 */
public record SavingsDepositCmd(long mid, long amount, String requestRef, String memo) {
  public SavingsDepositCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
  }
}
