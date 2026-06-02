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

  /**
   * Báo cáo lưu chuyển tiền tệ (direct method) trên TK tiền (nhóm ASSET).
   * Tiền vào = Σ ghi nợ; tiền ra = Σ ghi có trên các TK tiền.
   */
  public CashFlow cashFlow() {
    List<CashFlow.Line> lines = new ArrayList<>();
    long inflows = 0, outflows = 0, closing = 0;
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT a.code, a.name, a.balance_minor,"
                + " COALESCE(SUM(d.debit_minor),0) AS infl,"
                + " COALESCE(SUM(d.credit_minor),0) AS outfl"
                + " FROM coa_account a"
                + " LEFT JOIN coa_trans_data d ON d.account_code = a.code"
                + " WHERE a.kind = 'ASSET'"
                + " GROUP BY a.code, a.name, a.balance_minor"
                + " ORDER BY a.code");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        long in = rs.getLong("infl");
        long out = rs.getLong("outfl");
        if (in == 0 && out == 0) continue; // bỏ TK tiền chưa phát sinh
        lines.add(new CashFlow.Line(rs.getString("code"), rs.getString("name"), in, out));
        inflows += in;
        outflows += out;
        closing += rs.getLong("balance_minor"); // ASSET debit-normal: balance = net cash
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cashFlow failed", e);
    }
    return new CashFlow(0L, inflows, outflows, closing, lines);
  }

  /**
   * Báo cáo lưu chuyển tiền tệ phân loại Operating/Investing/Financing.
   * Cash = TK ngân hàng (mã 11xx). Mỗi giao dịch chạm tiền phân loại theo TK đối ứng:
   * có dòng Vốn (6xxx) → Financing; còn lại → Operating; (Investing chưa phát sinh).
   */
  public CashFlowStatement cashFlowStatement() {
    long operating = 0, investing = 0, financing = 0, closing = 0;
    // Net cash + cờ "có đối ứng Vốn" theo từng trans_id.
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "WITH per_trans AS ("
                + "  SELECT d.trans_id,"
                + "    SUM(CASE WHEN a.kind='ASSET' AND a.code LIKE '11%'"
                + "             THEN d.debit_minor - d.credit_minor ELSE 0 END) AS net_cash,"
                + "    BOOL_OR(a.kind='EQUITY') AS has_equity"
                + "  FROM coa_trans_data d JOIN coa_account a ON a.code = d.account_code"
                + "  GROUP BY d.trans_id)"
                + " SELECT"
                + "  COALESCE(SUM(CASE WHEN has_equity     THEN net_cash ELSE 0 END),0) AS financing,"
                + "  COALESCE(SUM(CASE WHEN NOT has_equity THEN net_cash ELSE 0 END),0) AS operating"
                + " FROM per_trans WHERE net_cash <> 0");
        ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        financing = rs.getLong("financing");
        operating = rs.getLong("operating");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("cashFlowStatement failed", e);
    }
    // Closing cash = Σ số dư TK tiền (11xx). Opening = 0 (chưa có as-of).
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(balance_minor),0) FROM coa_account"
                + " WHERE kind='ASSET' AND code LIKE '11%'");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      closing = rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException("cashFlowStatement closing failed", e);
    }
    return new CashFlowStatement(operating, investing, financing, 0L, closing);
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
