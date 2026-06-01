package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.cmd.*;
import dev.nivic.coa.error.*;
import dev.nivic.coa.report.BalanceSheet;
import dev.nivic.coa.report.FundFlowReports;
import dev.nivic.coa.report.ProfitAndLoss;
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
 * Integration tests cho khoá sổ cuối kỳ (period close):
 * kết chuyển doanh thu (4xxx) + chi phí (5xxx) → Lợi nhuận giữ lại (6100).
 */
@Testcontainers
@Tag("integration")
class PeriodCloseFlowTest {

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

  /** Tạo lãi: doanh thu phí nạp 1k (chỉ revenue, no expense). */
  private void seedRevenueOnly() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "PC-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "PC-C", null));
    // 4110 = -1,000 (revenue)
  }

  /** Tạo cả revenue + expense: IBFT (phí 1k, Napas 500). */
  private void seedRevenueAndExpense() {
    ledger.receiveTopUp(new TopUpReceiveCmd(200_000L, "PCX-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(200_000L, 0L, "PCX-C", null));
    ledger.initIbftTransfer(new IbftInitCmd(40_000L, 1_000L, "PCX-I", null));
    ledger.settleIbftTransfer(new IbftSettleCmd(40_000L, 1_000L, 500L, "PCX-S", null));
    // 4130 = -1,000 | 5100 = +500
  }

  // ── Basic close ────────────────────────────────────────────────────────────────

  @Test
  void close_revenueOnly_movesToRetainedEarnings() {
    seedRevenueOnly();

    CoaTrans t = ledger.closePeriod(new PeriodCloseCmd("CLOSE-1", null));

    assertTrue(t.isBalanced());
    // 4110 closed to 0
    assertEquals(0L, ledger.getBalance("4110"), "revenue account closed");
    // 6100 retained earnings: credit-normal → -1,000 (natural +1,000)
    assertEquals(-1_000L, ledger.getBalance("6100"), "retained earnings += net income");
  }

  @Test
  void close_revenueAndExpense_netToRetained() {
    seedRevenueAndExpense();

    ledger.closePeriod(new PeriodCloseCmd("CLOSE-2", null));

    assertEquals(0L, ledger.getBalance("4130"), "revenue closed");
    assertEquals(0L, ledger.getBalance("5100"), "expense closed");
    // net income = 1000 - 500 = 500 → 6100 = -500
    assertEquals(-500L, ledger.getBalance("6100"), "retained = net profit 500");
  }

  @Test
  void close_postsCorrectLines() {
    seedRevenueAndExpense();

    CoaTrans t = ledger.closePeriod(new PeriodCloseCmd("CLOSE-3", null));

    // DR 4130 (1000) / CR 5100 (500) / CR 6100 (500)
    assertEquals(1_000L, t.lines().stream()
        .filter(l -> "4130".equals(l.accountCode())).findFirst().orElseThrow().debitMinor());
    assertEquals(500L, t.lines().stream()
        .filter(l -> "5100".equals(l.accountCode())).findFirst().orElseThrow().creditMinor());
    assertEquals(500L, t.lines().stream()
        .filter(l -> "6100".equals(l.accountCode())).findFirst().orElseThrow().creditMinor());
  }

  // ── Loss scenario ────────────────────────────────────────────────────────────

  @Test
  void close_loss_debitsRetainedEarnings() {
    // Chỉ có chi phí, không doanh thu → lỗ. Tạo expense bằng cách reverse revenue?
    // Đơn giản: IBFT với phí 0 nhưng Napas 500 → chỉ có expense 500.
    ledger.receiveTopUp(new TopUpReceiveCmd(200_000L, "LOSS-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(200_000L, 0L, "LOSS-C", null));
    ledger.initIbftTransfer(new IbftInitCmd(40_000L, 0L, "LOSS-I", null));
    ledger.settleIbftTransfer(new IbftSettleCmd(40_000L, 0L, 500L, "LOSS-S", null));
    // 5100 = +500, no revenue → lỗ 500

    CoaTrans t = ledger.closePeriod(new PeriodCloseCmd("CLOSE-LOSS", null));

    assertTrue(t.isBalanced());
    assertEquals(0L, ledger.getBalance("5100"), "expense closed");
    // loss → DR 6100 by 500 → balance_minor +500 (equity giảm, natural -500)
    assertEquals(500L, ledger.getBalance("6100"), "retained debited on loss");
  }

  // ── After-close state ──────────────────────────────────────────────────────────

  @Test
  void close_thenPnLEmpty_balanceSheetUnchanged() {
    seedRevenueAndExpense();
    BalanceSheet before = reports.balanceSheet();

    ledger.closePeriod(new PeriodCloseCmd("CLOSE-4", null));

    ProfitAndLoss plAfter = reports.profitAndLoss();
    assertEquals(0L, plAfter.totalRevenue(), "P&L revenue zero after close");
    assertEquals(0L, plAfter.totalExpense(), "P&L expense zero after close");
    assertEquals(0L, plAfter.netProfit(), "P&L net zero after close");

    // Balance sheet: netIncome giờ = 0, nhưng equity tăng đúng bằng net income cũ
    BalanceSheet after = reports.balanceSheet();
    assertEquals(0L, after.netIncome(), "current-period income reset");
    assertEquals(before.netIncome(), after.equity() - before.equity(),
        "net income moved into equity (retained earnings)");
    assertTrue(after.isBalanced());
  }

  @Test
  void close_keepsDoubleEntryBalanced() {
    seedRevenueAndExpense();
    assertTrue(ledger.isDoubleEntryBalanced());

    ledger.closePeriod(new PeriodCloseCmd("CLOSE-5", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "balanced after close");
  }

  // ── Idempotency & guards ────────────────────────────────────────────────────

  @Test
  void close_idempotent() {
    seedRevenueOnly();
    CoaTrans first  = ledger.closePeriod(new PeriodCloseCmd("CLOSE-IDEM", null));
    CoaTrans second = ledger.closePeriod(new PeriodCloseCmd("CLOSE-IDEM", null));

    assertEquals(first.id(), second.id());
    assertEquals(-1_000L, ledger.getBalance("6100"), "no double close on retry");
  }

  @Test
  void close_nothingToClose_throws() {
    // No revenue/expense → throw
    assertThrows(NothingToCloseException.class,
        () -> ledger.closePeriod(new PeriodCloseCmd("CLOSE-EMPTY", null)));
  }

  @Test
  void close_secondPeriod_accumulatesRetained() {
    // Kỳ 1: lãi 1k
    seedRevenueOnly();
    ledger.closePeriod(new PeriodCloseCmd("CLOSE-Q1", null));
    assertEquals(-1_000L, ledger.getBalance("6100"));

    // Kỳ 2: thêm lãi 1k nữa
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "Q2-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "Q2-C", null));
    ledger.closePeriod(new PeriodCloseCmd("CLOSE-Q2", null));

    assertEquals(-2_000L, ledger.getBalance("6100"), "retained tích luỹ 2 kỳ");
    assertEquals(0L, ledger.getBalance("4110"), "revenue reset mỗi kỳ");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void close_twiceInSamePeriod_secondNothingToClose() {
    seedRevenueOnly();
    ledger.closePeriod(new PeriodCloseCmd("CLOSE-A", null));
    // Đã đóng → không còn revenue/expense → lần đóng mới (ref khác) throw
    assertThrows(NothingToCloseException.class,
        () -> ledger.closePeriod(new PeriodCloseCmd("CLOSE-B", null)));
  }
}
