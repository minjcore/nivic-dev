package dev.nivic.coa.report;

/**
 * Bảng cân đối kế toán (natural balances, đơn vị minor):
 * <pre>
 *   Tài sản (1xxx) = Nợ phải trả (2xxx) + Vốn (6xxx) + Lãi/Lỗ kỳ này + Transit dư
 * </pre>
 *
 * <p>{@code netIncome} = doanh thu − chi phí (chưa kết chuyển về vốn). {@code transit} là tổng
 * natural của nhóm 3xxx — phải bằng 0 khi mọi luồng đã hoàn tất. Phương trình luôn cân
 * (hệ quả Σ balance_minor = 0 của double-entry); {@link #isBalanced()} kiểm tra lại.</p>
 */
public record BalanceSheet(
    long assets,
    long liabilities,
    long equity,
    long netIncome,
    long transit) {

  /** Tài sản − (Nợ + Vốn + Lãi + Transit) phải = 0. */
  public long imbalance() {
    return assets - (liabilities + equity + netIncome + transit);
  }

  public boolean isBalanced() {
    return imbalance() == 0;
  }
}
