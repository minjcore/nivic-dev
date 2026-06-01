package dev.nivic.coa.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Read-only báo cáo kế toán dẫn xuất từ {@code coa_account} (running balance).
 *
 * <p>Mọi báo cáo tính trực tiếp từ {@code balance_minor = ΣDR − ΣCR}, không cần bảng phụ.
 * Quy ước nhóm tài khoản theo ký tự đầu của {@code code}: 1=Tài sản, 2=Nợ phải trả,
 * 3=Transit, 4=Doanh thu, 5=Chi phí, 6=Vốn.</p>
 */
public final class FundFlowReports {

  private static final String SELECT_ALL =
      "SELECT code, name, kind, balance_minor FROM coa_account ORDER BY code";

  private static final String SELECT_GROUP_SUMS =
      "SELECT kind, COALESCE(SUM(balance_minor),0) AS total FROM coa_account GROUP BY kind";

  private final DataSource dataSource;

  public FundFlowReports(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /** Bảng cân đối thử: mọi tài khoản có số dư ≠ 0, tổng Nợ/Có. */
  public TrialBalance trialBalance() {
    List<TrialBalanceRow> rows = new ArrayList<>();
    long totalDr = 0, totalCr = 0;
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_ALL);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        long bal = rs.getLong("balance_minor");
        if (bal == 0) continue;
        TrialBalanceRow row = TrialBalanceRow.of(
            rs.getString("code"), rs.getString("name"), rs.getString("kind"), bal);
        rows.add(row);
        totalDr += row.debitMinor();
        totalCr += row.creditMinor();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("trialBalance failed", e);
    }
    return new TrialBalance(rows, totalDr, totalCr);
  }

  /** Bảng cân đối kế toán: tổng natural theo nhóm + lãi/lỗ kỳ này. */
  public BalanceSheet balanceSheet() {
    long asset = 0, liability = 0, equity = 0, revenue = 0, expense = 0, transit = 0;
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_GROUP_SUMS);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String kind = rs.getString("kind");
        long sum = rs.getLong("total");
        switch (kind) {
          case "ASSET"     -> asset     = sum;        // debit-normal
          case "LIABILITY" -> liability = -sum;       // credit-normal → natural = −balance
          case "EQUITY"    -> equity    = -sum;
          case "REVENUE"   -> revenue   = -sum;
          case "EXPENSE"   -> expense   = sum;        // debit-normal
          case "TRANSIT"   -> transit   = -sum;       // credit-normal placeholder
          default -> { /* ignore unknown */ }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("balanceSheet failed", e);
    }
    long netIncome = revenue - expense;
    return new BalanceSheet(asset, liability, equity, netIncome, transit);
  }

  /** Báo cáo kết quả kinh doanh: doanh thu (4xxx) − chi phí (5xxx). */
  public ProfitAndLoss profitAndLoss() {
    List<ProfitAndLoss.Line> revenue = new ArrayList<>();
    List<ProfitAndLoss.Line> expense = new ArrayList<>();
    long totalRev = 0, totalExp = 0;
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_ALL);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String kind = rs.getString("kind");
        long bal = rs.getLong("balance_minor");
        if (bal == 0) continue;
        if ("REVENUE".equals(kind)) {
          long nat = -bal; // credit-normal
          revenue.add(new ProfitAndLoss.Line(rs.getString("code"), rs.getString("name"), nat));
          totalRev += nat;
        } else if ("EXPENSE".equals(kind)) {
          long nat = bal;  // debit-normal
          expense.add(new ProfitAndLoss.Line(rs.getString("code"), rs.getString("name"), nat));
          totalExp += nat;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("profitAndLoss failed", e);
    }
    return new ProfitAndLoss(revenue, expense, totalRev, totalExp);
  }
}
