package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Đổi tiền (FX): nền tảng chi {@code vndAmount} VND, nhận {@code usdAmount} USD (hoặc ngược lại
 * tuỳ {@code buyUsd}). Bút toán cân theo TỪNG currency, bắc cầu qua tài khoản vị thế FX:
 * <pre>
 *   VND leg: DR 1920 (vị thế VND) / CR 1111 (TK VND)   [cân trong VND]
 *   USD leg: DR 1121 (TK USD)     / CR 1921 (vị thế USD) [cân trong USD]
 * </pre>
 * Tỷ giá ngầm = vndAmount / usdAmount. Lãi/lỗ tỷ giá hạch toán khi đánh giá lại vị thế.
 */
public record FxExchangeCmd(
    long vndAmount,
    long usdAmount,
    boolean buyUsd,
    String requestRef,
    String memo) {

  public FxExchangeCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (vndAmount <= 0) throw new IllegalArgumentException("vndAmount must be positive");
    if (usdAmount <= 0) throw new IllegalArgumentException("usdAmount must be positive");
  }
}
