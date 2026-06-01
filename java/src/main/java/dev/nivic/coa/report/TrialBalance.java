package dev.nivic.coa.report;

import java.util.List;
import java.util.Objects;

/**
 * Bảng cân đối thử: liệt kê mọi tài khoản có số dư, tổng cột Nợ và cột Có.
 * Trial balance cân khi {@code totalDebit == totalCredit} — hệ quả của double-entry.
 */
public record TrialBalance(
    List<TrialBalanceRow> rows,
    long totalDebit,
    long totalCredit) {

  public TrialBalance {
    Objects.requireNonNull(rows, "rows");
    rows = List.copyOf(rows);
  }

  public boolean isBalanced() {
    return totalDebit == totalCredit;
  }
}
