package dev.nivic.coa.report;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.JdbcFundFlowLedger;
import dev.nivic.coa.cmd.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests cho báo cáo kế toán: Trial Balance, Balance Sheet, P&amp;L —
 * dẫn xuất từ coa_account sau khi chạy các luồng fund flow.
 */
@Testcontainers
@Tag("integration")
class FundFlowReportsTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;
  private FundFlowReports reports;

  @BeforeAll
  static void initPool() {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(5);
    ds = new HikariDataSource(cfg);
  }

  @AfterAll
  static void closePool() { ds.close(); }

  @BeforeEach
  void setUp() {
    ledger = new JdbcFundFlowLedger(ds);
    reports = new FundFlowReports(ds);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Trial Balance ──────────────────────────────────────────────────────────────

  @Test
  void trialBalance_empty_isBalanced() {
    TrialBalance tb = reports.trialBalance();
    assertTrue(tb.isBalanced());
    assertEquals(0, tb.rows().size(), "no nonzero accounts");
    assertEquals(0L, tb.totalDebit());
    assertEquals(0L, tb.totalCredit());
  }

  @Test
  void trialBalance_afterTopup_debitEqualsCredit() {
    // Nạp 100k phí 1k: 1111 +100k (DR) | 2110 -99k (CR) | 4110 -1k (CR)
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "TB-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "TB-C", null));

    TrialBalance tb = reports.trialBalance();
    assertTrue(tb.isBalanced(), "trial balance must balance");
    assertEquals(100_000L, tb.totalDebit(),  "DR side: 1111");
    assertEquals(100_000L, tb.totalCredit(), "CR side: 2110 99k + 4110 1k");

    // 1111 in debit column
    TrialBalanceRow r1111 = tb.rows().stream()
        .filter(r -> "1111".equals(r.code())).findFirst().orElseThrow();
    assertEquals(100_000L, r1111.debitMinor());
    assertEquals(0L, r1111.creditMinor());

    // 2110 in credit column
    TrialBalanceRow r2110 = tb.rows().stream()
        .filter(r -> "2110".equals(r.code())).findFirst().orElseThrow();
    assertEquals(0L, r2110.debitMinor());
    assertEquals(99_000L, r2110.creditMinor());
  }

  @Test
  void trialBalance_omitsZeroBalanceAccounts() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "TBZ-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "TBZ-C", null));
    // 3100 transit cleared to 0 → must not appear
    TrialBalance tb = reports.trialBalance();
    assertTrue(tb.rows().stream().noneMatch(r -> "3100".equals(r.code())),
        "zero-balance transit omitted");
  }

  // ── Balance Sheet ───────────────────────────────────────────────────────────────

  @Test
  void balanceSheet_empty_allZeroBalanced() {
    BalanceSheet bs = reports.balanceSheet();
    assertEquals(0L, bs.assets());
    assertEquals(0L, bs.liabilities());
    assertEquals(0L, bs.equity());
    assertEquals(0L, bs.netIncome());
    assertTrue(bs.isBalanced());
  }

  @Test
  void balanceSheet_afterTopup_equationHolds() {
    // Nạp 100k phí 1k → 1111 (asset) +100k | 2110 (liab) 99k | 4110 (revenue) 1k
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BS-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "BS-C", null));

    BalanceSheet bs = reports.balanceSheet();
    assertEquals(100_000L, bs.assets(),      "tài sản = 1111");
    assertEquals( 99_000L, bs.liabilities(), "nợ phải trả = ví user");
    assertEquals(  1_000L, bs.netIncome(),   "lãi = doanh thu phí 1k − chi phí 0");
    assertEquals(      0L, bs.transit(),     "transit clear");
    assertTrue(bs.isBalanced(), "Tài sản = Nợ + Vốn + Lãi + Transit");
    // 100k = 99k + 0 + 1k + 0 ✓
    assertEquals(0L, bs.imbalance());
  }

  @Test
  void balanceSheet_withIbftExpense_netIncomeReflectsExpense() {
    // Nạp 200k (phí 0) rồi IBFT 40k phí 1k Napas 500
    ledger.receiveTopUp(new TopUpReceiveCmd(200_000L, "BSX-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(200_000L, 0L, "BSX-C", null));
    ledger.initIbftTransfer(new IbftInitCmd(40_000L, 1_000L, "BSX-I", null));
    ledger.settleIbftTransfer(new IbftSettleCmd(40_000L, 1_000L, 500L, "BSX-S", null));

    BalanceSheet bs = reports.balanceSheet();
    // netIncome = revenue (4130 = 1k) − expense (5100 = 500) = 500
    assertEquals(500L, bs.netIncome(), "lãi thuần IBFT = phí 1k − Napas 500");
    assertTrue(bs.isBalanced());
    assertEquals(0L, bs.imbalance(), "phương trình cân");
  }

  @Test
  void balanceSheet_midFlow_transitNonZero_stillBalanced() {
    // Dừng giữa luồng (chỉ init withdraw, chưa settle) → transit ≠ 0
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "MID-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 0L, "MID-C", null));
    ledger.initWithdraw(new WithdrawInitCmd(50_000L, 1_000L, "MID-I", null));
    // 3200 transit = -51k → transit natural = 51k

    BalanceSheet bs = reports.balanceSheet();
    assertEquals(51_000L, bs.transit(), "transit giữ tiền giữa luồng");
    assertTrue(bs.isBalanced(), "phương trình vẫn cân kể cả khi transit ≠ 0");
    assertEquals(0L, bs.imbalance());
  }

  // ── Profit & Loss ───────────────────────────────────────────────────────────────

  @Test
  void profitAndLoss_empty_zero() {
    ProfitAndLoss pl = reports.profitAndLoss();
    assertEquals(0L, pl.totalRevenue());
    assertEquals(0L, pl.totalExpense());
    assertEquals(0L, pl.netProfit());
    assertTrue(pl.revenue().isEmpty());
    assertTrue(pl.expense().isEmpty());
  }

  @Test
  void profitAndLoss_topupRevenueOnly() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "PL-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "PL-C", null));

    ProfitAndLoss pl = reports.profitAndLoss();
    assertEquals(1_000L, pl.totalRevenue(), "phí nạp 1k");
    assertEquals(0L, pl.totalExpense());
    assertEquals(1_000L, pl.netProfit());

    ProfitAndLoss.Line r = pl.revenue().stream()
        .filter(l -> "4110".equals(l.code())).findFirst().orElseThrow();
    assertEquals(1_000L, r.amount());
    assertEquals("Doanh thu Phí nạp tiền", r.name());
  }

  @Test
  void profitAndLoss_ibft_revenueAndExpense() {
    ledger.receiveTopUp(new TopUpReceiveCmd(200_000L, "PLX-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(200_000L, 0L, "PLX-C", null));
    ledger.initIbftTransfer(new IbftInitCmd(40_000L, 1_000L, "PLX-I", null));
    ledger.settleIbftTransfer(new IbftSettleCmd(40_000L, 1_000L, 500L, "PLX-S", null));

    ProfitAndLoss pl = reports.profitAndLoss();
    assertEquals(1_000L, pl.totalRevenue(), "phí IBFT 4130");
    assertEquals(  500L, pl.totalExpense(), "Napas 5100");
    assertEquals(  500L, pl.netProfit(), "lãi thuần = 1k − 500");

    assertEquals(1, pl.revenue().size());
    assertEquals(1, pl.expense().size());
    assertEquals("Chi phí Phí NH / Napas", pl.expense().get(0).name());
  }

  @Test
  void profitAndLoss_payroll_aggregatesAcrossFlows() {
    // Seed merchant + chi lương: doanh thu 4150 = 5k, chi phí Napas 5100 = 2.5k
    ledger.receiveQrPos(new QrPosReceiveCmd(600_000L, 0L, "PLP-QR", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(600_000L, "PLP-QRC", null));
    ledger.initPayroll(new PayrollInitCmd(500_000L, 5_000L, 5, "PLP-I", null));
    ledger.disbursePayroll(new PayrollDisburseCmd(500_000L, 5_000L, 2_500L, "PLP-D", null));

    ProfitAndLoss pl = reports.profitAndLoss();
    assertEquals(5_000L, pl.totalRevenue(), "phí chi lương 4150");
    assertEquals(2_500L, pl.totalExpense(), "Napas 5 giao dịch");
    assertEquals(2_500L, pl.netProfit());
  }

  // ── Cross-report consistency ──────────────────────────────────────────────────

  @Test
  void reports_consistentAfterMixedFlows() {
    // Chạy nhiều luồng, kiểm tra 3 báo cáo nhất quán
    ledger.receiveTopUp(new TopUpReceiveCmd(500_000L, "MX-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(500_000L, 1_000L, "MX-C", null));
    ledger.initWalletPayment(new WalletPaymentInitCmd(100_000L, "MX-WI", null));
    ledger.settleWalletPayment(new WalletPaymentSettleCmd(100_000L, "MX-WS", null));

    TrialBalance tb = reports.trialBalance();
    BalanceSheet bs = reports.balanceSheet();
    ProfitAndLoss pl = reports.profitAndLoss();

    assertTrue(tb.isBalanced(), "trial balance cân");
    assertTrue(bs.isBalanced(), "balance sheet cân");
    // netIncome trong balance sheet = netProfit trong P&L
    assertEquals(pl.netProfit(), bs.netIncome(), "netIncome khớp giữa BS và P&L");
    // P&L: chỉ có phí nạp 1k (thanh toán ví không phí)
    assertEquals(1_000L, pl.netProfit());
  }
}
