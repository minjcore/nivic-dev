package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.cmd.*;
import dev.nivic.coa.error.InsufficientWalletException;
import dev.nivic.coa.report.FundFlowReports;
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
 * Savings ví nối vào sổ cái qua control account 2140 + chi phí lãi 5200.
 * sav_account = sổ chi tiết của 2140; lãi là chi phí nền tảng.
 */
@Testcontainers
@Tag("integration")
class SavingsGlTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;
  private FundFlowReports reports;

  private static final long U1 = 1001L, U2 = 1002L;

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
  void setUp() { ledger = new JdbcFundFlowLedger(ds); reports = new FundFlowReports(ds); }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  /** Nạp ví cho user qua topup (confirm gắn mid). */
  private void fundWallet(long mid, long amount, String tag) {
    ledger.receiveTopUp(new TopUpReceiveCmd(amount, tag + "-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(amount, 0L, tag + "-C", null, mid));
  }

  // ── Deposit ví → tiết kiệm ────────────────────────────────────────────────────

  @Test
  void deposit_reclassifiesWalletToSavings() {
    fundWallet(U1, 500_000L, "D1");
    assertEquals(500_000L, ledger.walletBalance(U1));

    ledger.savingsDeposit(new SavingsDepositCmd(U1, 300_000L, "SAV-D1", null));

    assertEquals(200_000L, ledger.walletBalance(U1), "ví giảm");
    assertEquals(300_000L, ledger.savingsBalance(U1), "tiết kiệm tăng");
    // Tổng nợ phải trả với user không đổi (chỉ tái phân loại)
    assertEquals(500_000L, ledger.walletBalance(U1) + ledger.savingsBalance(U1));
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void deposit_postsControlAccounts() {
    fundWallet(U1, 500_000L, "D2");
    CoaTrans t = ledger.savingsDeposit(new SavingsDepositCmd(U1, 100_000L, "SAV-D2", null));
    assertTrue(t.isBalanced());
    assertEquals(100_000L, t.lines().stream().filter(l -> "2110".equals(l.accountCode())).findFirst().orElseThrow().debitMinor());
    assertEquals(100_000L, t.lines().stream().filter(l -> "2140".equals(l.accountCode())).findFirst().orElseThrow().creditMinor());
  }

  @Test
  void deposit_insufficientWallet_throws() {
    fundWallet(U1, 100_000L, "D3");
    assertThrows(InsufficientWalletException.class,
        () -> ledger.savingsDeposit(new SavingsDepositCmd(U1, 200_000L, "SAV-OVER", null)));
    assertEquals(100_000L, ledger.walletBalance(U1), "ví không đổi");
    assertEquals(0L, ledger.savingsBalance(U1));
  }

  // ── Withdraw tiết kiệm → ví ──────────────────────────────────────────────────

  @Test
  void withdraw_movesBackToWallet() {
    fundWallet(U1, 500_000L, "W1");
    ledger.savingsDeposit(new SavingsDepositCmd(U1, 300_000L, "W1-D", null));
    ledger.savingsWithdraw(new SavingsWithdrawCmd(U1, 100_000L, "W1-W", null));

    assertEquals(300_000L, ledger.walletBalance(U1), "200k còn + 100k rút về");
    assertEquals(200_000L, ledger.savingsBalance(U1));
    assertEquals(500_000L, ledger.walletBalance(U1) + ledger.savingsBalance(U1));
  }

  @Test
  void withdraw_insufficientSavings_throws() {
    fundWallet(U1, 500_000L, "W2");
    ledger.savingsDeposit(new SavingsDepositCmd(U1, 100_000L, "W2-D", null));
    assertThrows(InsufficientWalletException.class,
        () -> ledger.savingsWithdraw(new SavingsWithdrawCmd(U1, 200_000L, "W2-OVER", null)));
  }

  // ── Lãi tiền gửi (chi phí nền tảng) ──────────────────────────────────────────

  @Test
  void interest_increasesSavings_andExpense() {
    fundWallet(U1, 1_000_000L, "I1");
    ledger.savingsDeposit(new SavingsDepositCmd(U1, 1_000_000L, "I1-D", null));
    ledger.savingsInterest(new SavingsInterestCmd(U1, 5_000L, "I1-INT", null));

    assertEquals(1_005_000L, ledger.savingsBalance(U1), "tiết kiệm += lãi");
    assertEquals(5_000L, ledger.getBalance("5200"), "chi phí lãi (debit-normal +)");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void interest_reflectedInPnlAsExpense() {
    fundWallet(U1, 1_000_000L, "I2");
    ledger.savingsDeposit(new SavingsDepositCmd(U1, 1_000_000L, "I2-D", null));
    ledger.savingsInterest(new SavingsInterestCmd(U1, 8_000L, "I2-INT", null));

    var pl = reports.profitAndLoss();
    assertEquals(8_000L, pl.totalExpense(), "lãi tiền gửi vào chi phí P&L");
    assertEquals(-8_000L, pl.netProfit(), "lỗ thuần do chi phí lãi (chưa có doanh thu)");
  }

  // ── Reconciliation: Σ savings per-user = natural(2140) ────────────────────────

  @Test
  void reconciliation_savingsSubledgerMatchesControl() {
    fundWallet(U1, 500_000L, "R1");
    fundWallet(U2, 300_000L, "R2");
    ledger.savingsDeposit(new SavingsDepositCmd(U1, 200_000L, "R-D1", null));
    ledger.savingsDeposit(new SavingsDepositCmd(U2, 150_000L, "R-D2", null));
    ledger.savingsInterest(new SavingsInterestCmd(U1, 1_000L, "R-INT", null));

    long sumPerUser = ledger.savingsBalance(U1) + ledger.savingsBalance(U2);
    long control2140 = -ledger.getBalance("2140"); // natural liability
    assertEquals(control2140, sumPerUser, "Σ tiết kiệm per-user = control account 2140");
    assertEquals(351_000L, sumPerUser); // 201k + 150k
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void balanceSheet_includesSavingsLiability() {
    fundWallet(U1, 1_000_000L, "BS");
    ledger.savingsDeposit(new SavingsDepositCmd(U1, 400_000L, "BS-D", null));
    ledger.savingsInterest(new SavingsInterestCmd(U1, 2_000L, "BS-INT", null));

    var bs = reports.balanceSheet();
    // Nợ phải trả gồm cả ví (600k) + tiết kiệm (402k) = 1,002,000
    assertEquals(1_002_000L, bs.liabilities(), "liabilities gồm 2110 + 2140 (+ lãi)");
    assertTrue(bs.isBalanced(), "phương trình vẫn cân với chi phí lãi");
  }
}
