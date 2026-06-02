package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Đánh giá lại vị thế FX theo tỷ giá hiện tại → ghi nhận lãi/lỗ chênh lệch tỷ giá.
 *
 * <p>{@code rateVndPerUsd} = số VND cho 1.00 USD (vd 25000). Giá trị thị trường của vị thế USD
 * (1921) được mark-to-market vào TK vị thế VND (1920); chênh lệch so với cơ sở hiện tại →
 * DR 1920 / CR 4170 (lãi) hoặc DR 5300 / CR 1920 (lỗ).</p>
 */
public record FxRevalueCmd(long rateVndPerUsd, String requestRef, String memo) {
  public FxRevalueCmd {
    Objects.requireNonNull(requestRef, "requestRef");
    if (rateVndPerUsd <= 0) throw new IllegalArgumentException("rateVndPerUsd must be positive");
  }
}
