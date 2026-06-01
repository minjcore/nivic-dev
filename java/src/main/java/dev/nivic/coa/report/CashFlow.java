package dev.nivic.coa.report;

import java.util.List;
import java.util.Objects;

/**
 * Báo cáo lưu chuyển tiền tệ (direct method) trên các tài khoản tiền (nhóm ASSET = TK ngân hàng).
 * Tiền vào = Σ ghi nợ trên TK tiền; tiền ra = Σ ghi có.
 *
 * <p>Hiện tại không có as-of lịch sử nên {@code openingCash = 0}; do đó
 * {@code netCashFlow == closingCash == Σ số dư TK tiền}.</p>
 */
public record CashFlow(
    long openingCash,
    long inflows,
    long outflows,
    long closingCash,
    List<Line> byAccount) {

  public CashFlow {
    Objects.requireNonNull(byAccount, "byAccount");
    byAccount = List.copyOf(byAccount);
  }

  public long netCashFlow() { return inflows - outflows; }

  /** Đối soát: opening + net = closing (luôn đúng vì cùng nguồn bút toán). */
  public boolean isConsistent() { return openingCash + netCashFlow() == closingCash; }

  /** Một tài khoản tiền: tiền vào (Σnợ), tiền ra (Σcó), ròng. */
  public record Line(String code, String name, long inflow, long outflow) {
    public long net() { return inflow - outflow; }
  }
}
