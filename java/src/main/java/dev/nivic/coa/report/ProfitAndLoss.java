package dev.nivic.coa.report;

import java.util.List;
import java.util.Objects;

/**
 * Báo cáo kết quả kinh doanh (P&amp;L): doanh thu (4xxx) − chi phí (5xxx) = lãi/lỗ thuần.
 * Các dòng dùng natural balance (số dương).
 */
public record ProfitAndLoss(
    List<Line> revenue,
    List<Line> expense,
    long totalRevenue,
    long totalExpense) {

  public ProfitAndLoss {
    Objects.requireNonNull(revenue, "revenue");
    Objects.requireNonNull(expense, "expense");
    revenue = List.copyOf(revenue);
    expense = List.copyOf(expense);
  }

  public long netProfit() {
    return totalRevenue - totalExpense;
  }

  /** Một dòng doanh thu hoặc chi phí (amount = natural balance, dương). */
  public record Line(String code, String name, long amount) {
    public Line {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(name, "name");
    }
  }
}
