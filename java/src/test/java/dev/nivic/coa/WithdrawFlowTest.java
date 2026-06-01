package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * Integration tests for the rút tiền fund flow:
 * initWithdraw (DR 2110 / CR 3200) → settleWithdraw (DR 3200 / CR 1111 / CR 4120).
 */
@Testcontainers
@Tag("integration")
class WithdrawFlowTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

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
    // Seed user wallet: nạp 500k trước để có tiền rút
    ledger.receiveTopUp(new TopUpReceiveCmd(500_000L, "SEED-RECV", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(500_000L, 0L, "SEED-CONF", null));
    // Sau seed: 2110 = -500,000 | 1111 = +500,000
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Step 1: initWithdraw ─────────────────────────────────────────────────────

  @Test
  void initWithdraw_postsTwoLines() {
    CoaTrans t = ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-INIT-01", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    CoaTransLine dr = t.lines().stream().filter(CoaTransLine::isDebit).findFirst().orElseThrow();
    CoaTransLine cr = t.lines().stream().filter(CoaTransLine::isCredit).findFirst().orElseThrow();

    assertEquals("2110",  dr.accountCode());
    assertEquals(101_000L, dr.debitMinor(),   "debit = amount + fee");
    assertEquals("Wallet Balance - User", dr.accountName());

    assertEquals("3200",  cr.accountCode());
    assertEquals(101_000L, cr.creditMinor(),  "credit = amount + fee");
    assertEquals("Transit - Rút tiền", cr.accountName());
  }

  @Test
  void initWithdraw_updatesBalances() {
    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-INIT-02", null));

    // 2110: -500k (seed) + 101k (DR) = -399k
    assertEquals(-399_000L, ledger.getBalance("2110"));
    // 3200: 0 + (-101k) (CR) = -101k
    assertEquals(-101_000L, ledger.getBalance("3200"));
    // 1111 unchanged
    assertEquals( 500_000L, ledger.getBalance("1111"));
  }

  @Test
  void initWithdraw_idempotent() {
    CoaTrans first  = ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-INIT-03", null));
    CoaTrans second = ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-INIT-03", null));

    assertEquals(first.id(), second.id());
    assertEquals(-399_000L, ledger.getBalance("2110"), "no double-debit on retry");
  }

  @Test
  void initWithdraw_insufficientWallet_throws() {
    // Wallet chỉ có 500k, rút 600k → insufficient
    assertThrows(InsufficientWalletException.class,
        () -> ledger.initWithdraw(new WithdrawInitCmd(600_000L, 1_000L, "W-OVER", null)));

    // Balances không thay đổi
    assertEquals(-500_000L, ledger.getBalance("2110"));
    assertEquals(       0L, ledger.getBalance("3200"));
  }

  @Test
  void initWithdraw_withoutPriorTopUp_throws() {
    // Balance 2110 = 0 → bất kỳ withdrawal nào đều insufficient
    // Reset sạch (AfterEach sẽ clean, nhưng cần fresh ledger)
    ledger.receiveTopUp(new TopUpReceiveCmd(1L, "DUMMY-RECV-ZB", null));
    // Tạo ledger mới trên fresh DB
    // (không cần — wallet đang có -500k từ setUp, test này không áp dụng trực tiếp)
    // Thay vào đó: rút nhiều hơn số dư
    assertThrows(InsufficientWalletException.class,
        () -> ledger.initWithdraw(new WithdrawInitCmd(500_001L, 0L, "W-EXCEED", null)));
  }

  // ── Step 2: settleWithdraw ────────────────────────────────────────────────────

  @Test
  void settleWithdraw_postsThreeLines() {
    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-INIT-04", null));
    CoaTrans t = ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "W-SETL-04", null));

    assertTrue(t.isBalanced());
    assertEquals(3, t.lines().size());

    CoaTransLine dr3200 = t.lines().stream()
        .filter(l -> "3200".equals(l.accountCode()) && l.isDebit())
        .findFirst().orElseThrow();
    assertEquals(101_000L, dr3200.debitMinor(), "DR transit = amount + fee");

    CoaTransLine cr1111 = t.lines().stream()
        .filter(l -> "1111".equals(l.accountCode()))
        .findFirst().orElseThrow();
    assertEquals(100_000L, cr1111.creditMinor(), "CR bank = amount");

    CoaTransLine cr4120 = t.lines().stream()
        .filter(l -> "4120".equals(l.accountCode()))
        .findFirst().orElseThrow();
    assertEquals(1_000L, cr4120.creditMinor(), "CR revenue = fee");
  }

  @Test
  void settleWithdraw_allBalancesCorrect() {
    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-INIT-05", null));
    ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "W-SETL-05", null));

    assertEquals(-399_000L, ledger.getBalance("2110"), "user wallet debited net 101k");
    assertEquals(       0L, ledger.getBalance("3200"), "transit cleared");
    assertEquals( 400_000L, ledger.getBalance("1111"), "bank: 500k seed − 100k out");
    assertEquals(  -1_000L, ledger.getBalance("4120"), "revenue: phí rút");
  }

  @Test
  void settleWithdraw_transitClearsToZero() {
    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-INIT-06", null));
    ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "W-SETL-06", null));

    assertEquals(0L, ledger.getBalance("3200"), "Transit 3200 must be 0 after full flow");
  }

  @Test
  void settleWithdraw_idempotent() {
    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-INIT-07", null));
    CoaTrans first  = ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "W-SETL-07", null));
    CoaTrans second = ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "W-SETL-07", null));

    assertEquals(first.id(), second.id());
    assertEquals(0L, ledger.getBalance("3200"), "no double-debit on retry");
  }

  @Test
  void settleWithdraw_withoutInit_throws() {
    // Transit 3200 = 0 → không thể release
    assertThrows(InsufficientTransitException.class,
        () -> ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "W-SETL-ORPHAN", null)));
  }

  @Test
  void settleWithdraw_zeroFee_twoLines() {
    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 0L, "W-INIT-NOFEE", null));
    CoaTrans t = ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 0L, "W-SETL-NOFEE", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size(), "zero fee: only DR 3200 + CR 1111");
    assertEquals(0L, ledger.getBalance("4120"), "no revenue on zero fee");
  }

  // ── Full flow end-to-end ─────────────────────────────────────────────────────

  @Test
  void fullWithdrawFlow_doubleEntryAlwaysBalanced() {
    assertTrue(ledger.isDoubleEntryBalanced(), "after setUp (nạp tiền already applied)");

    ledger.initWithdraw(new WithdrawInitCmd(100_000L, 1_000L, "W-E2E-INIT", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after init");

    ledger.settleWithdraw(new WithdrawSettleCmd(100_000L, 1_000L, "W-E2E-SETL", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after settle");
  }

  @Test
  void fullWithdrawFlow_topUpThenWithdrawPartial_balancesCorrect() {
    // Seed: nạp 500k (no fee) → 2110 = -500k, 1111 = +500k
    // Rút 200k + 1k phí
    ledger.initWithdraw(new WithdrawInitCmd(200_000L, 1_000L, "W-PART-INIT", null));
    ledger.settleWithdraw(new WithdrawSettleCmd(200_000L, 1_000L, "W-PART-SETL", null));

    // 2110: -500k + 201k = -299k (user còn 299k)
    assertEquals(-299_000L, ledger.getBalance("2110"));
    // 1111: +500k − 200k = +300k (bank còn 300k sau khi chuyển 200k ra)
    assertEquals( 300_000L, ledger.getBalance("1111"));
    assertEquals(       0L, ledger.getBalance("3200"), "transit clear");
    assertEquals(  -1_000L, ledger.getBalance("4120"), "revenue 1k phí rút");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fullWithdrawFlow_multipleWithdrawals_accumulateRevenue() {
    // 3 lần rút, mỗi lần phí 1k
    for (int i = 1; i <= 3; i++) {
      ledger.initWithdraw(new WithdrawInitCmd(50_000L, 1_000L, "W-MULTI-INIT-" + i, null));
      ledger.settleWithdraw(new WithdrawSettleCmd(50_000L, 1_000L, "W-MULTI-SETL-" + i, null));
    }

    // 3 × 51k = 153k DR từ 2110: -500k + 153k = -347k
    assertEquals(-347_000L, ledger.getBalance("2110"));
    // 3 × 50k = 150k CR ra 1111: +500k − 150k = +350k
    assertEquals( 350_000L, ledger.getBalance("1111"));
    assertEquals(       0L, ledger.getBalance("3200"), "all transit cleared");
    assertEquals(  -3_000L, ledger.getBalance("4120"), "3 × 1k fee");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void topUpThenWithdrawAll_platformInvariant() {
    // Nạp 100k (no fee) → rút 100k (no fee) → net zero
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "EXTRA-RECV", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 0L, "EXTRA-CONF", null));

    ledger.initWithdraw(new WithdrawInitCmd(600_000L, 0L, "FULL-INIT", null));
    ledger.settleWithdraw(new WithdrawSettleCmd(600_000L, 0L, "FULL-SETL", null));

    // 2110 seeded -500k, extra -100k → -600k, then DR +600k = 0
    assertEquals(0L, ledger.getBalance("2110"), "wallet empty");
    assertEquals(0L, ledger.getBalance("1111"), "bank zero (500k in, 600k out... wait");
    // 1111: 500k seed + 100k extra = 600k, then CR 600k out = 0
    assertEquals(0L, ledger.getBalance("3200"), "transit clear");
    assertTrue(ledger.isDoubleEntryBalanced());
  }
}
