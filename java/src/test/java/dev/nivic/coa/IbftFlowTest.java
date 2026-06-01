package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import dev.nivic.coa.cmd.*;
import dev.nivic.coa.error.*;

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
 * Integration tests for luồng IBFT (Interbank Fund Transfer):
 * initIbftTransfer (DR 2110 / CR 3400) →
 * settleIbftTransfer (DR 3400 / DR 5100 / CR 1112 / CR 4130).
 *
 * <p>Lãi thuần = fee − napasCost. Transit 3400 về 0 sau mỗi luồng.</p>
 */
@Testcontainers
@Tag("integration")
class IbftFlowTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

  // PDF example: 100k transfer, 1k fee, 500 Napas cost → lãi thuần 500
  private static final long AMOUNT    = 100_000L;
  private static final long FEE       = 1_000L;
  private static final long NAPAS     = 500L;

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
    // Seed: nạp 500k để có tiền chuyển
    ledger.receiveTopUp(new TopUpReceiveCmd(500_000L, "SEED-RECV", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(500_000L, 0L, "SEED-CONF", null));
    // 2110 = -500k | 1111 = +500k | 1112 = 0
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Step 1: initIbftTransfer ─────────────────────────────────────────────────

  @Test
  void init_postsTwoBalancedLines() {
    CoaTrans t = ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-01", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    CoaTransLine dr = t.lines().stream().filter(CoaTransLine::isDebit).findFirst().orElseThrow();
    CoaTransLine cr = t.lines().stream().filter(CoaTransLine::isCredit).findFirst().orElseThrow();

    assertEquals("2110", dr.accountCode());
    assertEquals(AMOUNT + FEE, dr.debitMinor(), "DR 2110 = amount + fee");

    assertEquals("3400", cr.accountCode());
    assertEquals(AMOUNT + FEE, cr.creditMinor(), "CR 3400 = amount + fee");
    assertEquals("Transit - IBFT", cr.accountName());
  }

  @Test
  void init_updatesBalances() {
    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-02", null));

    assertEquals(-500_000L + (AMOUNT + FEE), ledger.getBalance("2110")); // -499k+101k = -399k... wait
    // -500k + 101k = -399k
    assertEquals(-399_000L, ledger.getBalance("2110"));
    assertEquals(-(AMOUNT + FEE), ledger.getBalance("3400"), "transit holds amount+fee");
    assertEquals(0L, ledger.getBalance("1112"), "Napas not touched yet");
  }

  @Test
  void init_idempotent() {
    CoaTrans first  = ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-03", null));
    CoaTrans second = ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-03", null));

    assertEquals(first.id(), second.id());
    assertEquals(-399_000L, ledger.getBalance("2110"), "no double-debit on retry");
  }

  @Test
  void init_insufficientWallet_throws() {
    assertThrows(InsufficientWalletException.class,
        () -> ledger.initIbftTransfer(new IbftInitCmd(600_000L, FEE, "IBFT-OVER", null)));

    assertEquals(-500_000L, ledger.getBalance("2110"), "balance unchanged");
    assertEquals(0L, ledger.getBalance("3400"), "transit unchanged");
  }

  // ── Step 2: settleIbftTransfer ────────────────────────────────────────────────

  @Test
  void settle_postsFourBalancedLines() {
    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-04", null));
    CoaTrans t = ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-S-04", null));

    assertTrue(t.isBalanced());
    assertEquals(4, t.lines().size());

    // DR 3400
    CoaTransLine dr3400 = lineOf(t, "3400", true);
    assertEquals(AMOUNT + FEE, dr3400.debitMinor());

    // DR 5100
    CoaTransLine dr5100 = lineOf(t, "5100", true);
    assertEquals(NAPAS, dr5100.debitMinor(), "Napas cost debited");
    assertEquals("Chi phí Phí NH / Napas", dr5100.accountName());

    // CR 1112
    CoaTransLine cr1112 = lineOf(t, "1112", false);
    assertEquals(AMOUNT + NAPAS, cr1112.creditMinor(), "Napas credited amount + napas cost");
    assertEquals("TK Napas Clearing", cr1112.accountName());

    // CR 4130
    CoaTransLine cr4130 = lineOf(t, "4130", false);
    assertEquals(FEE, cr4130.creditMinor(), "revenue = user fee");
  }

  @Test
  void settle_allBalancesMatchPdf() {
    // PDF: 2110 -101k | 1112 -100.5k | 4130 +1k | 5100 +500 | Transit 3400 = 0
    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-05", null));
    ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-S-05", null));

    // 2110: -500k (seed) + 101k (DR init) = -399k
    assertEquals(-399_000L, ledger.getBalance("2110"), "user wallet: seed -500k + DR 101k");
    assertEquals(        0L, ledger.getBalance("3400"), "transit IBFT cleared");
    // 1112: 0 - (100k + 500) = -100.5k
    assertEquals(-100_500L, ledger.getBalance("1112"), "Napas: gửi 100k + trả 500 phí");
    assertEquals(  -1_000L, ledger.getBalance("4130"), "doanh thu phí IBFT");
    assertEquals(     500L, ledger.getBalance("5100"), "chi phí Napas");
  }

  @Test
  void settle_transitClearsToZero() {
    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-06", null));
    ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-S-06", null));

    assertEquals(0L, ledger.getBalance("3400"), "Transit 3400 must be 0 after full flow");
  }

  @Test
  void settle_netProfit_isFeeMinusNapasCost() {
    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-07", null));
    ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-S-07", null));

    // Lãi thuần = doanh thu (4130) - chi phí (5100)
    long revenue = -ledger.getBalance("4130"); // credit-normal → negative balance = revenue
    long expense =  ledger.getBalance("5100"); // debit-normal  → positive balance = expense
    assertEquals(FEE - NAPAS, revenue - expense, "lãi thuần = fee − napasCost");
    assertEquals(500L, revenue - expense);
  }

  @Test
  void settle_idempotent() {
    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-I-08", null));
    CoaTrans first  = ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-S-08", null));
    CoaTrans second = ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-S-08", null));

    assertEquals(first.id(), second.id());
    assertEquals(0L, ledger.getBalance("3400"), "no double-debit on retry");
  }

  @Test
  void settle_withoutInit_throws() {
    assertThrows(InsufficientTransitException.class,
        () -> ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-ORPHAN", null)));
  }

  @Test
  void settle_zeroFee_threeLines() {
    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, 0L, "IBFT-I-NOFEE", null));
    CoaTrans t = ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, 0L, NAPAS, "IBFT-S-NOFEE", null));

    assertTrue(t.isBalanced());
    // DR 3400, DR 5100, CR 1112 (no CR 4130 when fee=0)
    assertEquals(3, t.lines().size(), "zero fee: no CR 4130 line");
    assertEquals(0L, ledger.getBalance("4130"), "no revenue entry");
  }

  // ── Full flow ─────────────────────────────────────────────────────────────────

  @Test
  void fullFlow_doubleEntryAlwaysBalanced() {
    assertTrue(ledger.isDoubleEntryBalanced(), "after setUp");

    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-E2E-I", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after init");

    ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-E2E-S", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after settle");
  }

  @Test
  void fullFlow_napasAccountDecreases_bankDoesNotChange() {
    long bank1111Before = ledger.getBalance("1111");

    ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-NH-I", null));
    ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-NH-S", null));

    // 1111 (Vietinbank) không bị chạm — IBFT đi qua 1112 (Napas Clearing)
    assertEquals(bank1111Before, ledger.getBalance("1111"),
        "IBFT dùng TK Napas (1112), không dùng TK Vietinbank (1111)");
    assertTrue(ledger.getBalance("1112") < 0, "1112 Napas phải giảm");
  }

  @Test
  void fullFlow_multipleIbft_accumulateCorrectly() {
    // 3 lần IBFT, mỗi lần 100k phí 1k Napas 500
    for (int i = 1; i <= 3; i++) {
      ledger.initIbftTransfer(new IbftInitCmd(AMOUNT, FEE, "IBFT-M-I-" + i, null));
      ledger.settleIbftTransfer(new IbftSettleCmd(AMOUNT, FEE, NAPAS, "IBFT-M-S-" + i, null));
    }

    // 2110: -500k + 3×101k = -500k + 303k = -197k
    assertEquals(-197_000L, ledger.getBalance("2110"));
    assertEquals(         0L, ledger.getBalance("3400"), "all transit cleared");
    // 1112: 3 × -(100k+500) = -301.5k
    assertEquals(-301_500L, ledger.getBalance("1112"));
    assertEquals(  -3_000L, ledger.getBalance("4130"), "3 × 1k revenue");
    assertEquals(   1_500L, ledger.getBalance("5100"), "3 × 500 Napas cost");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private static CoaTransLine lineOf(CoaTrans t, String code, boolean debit) {
    return t.lines().stream()
        .filter(l -> code.equals(l.accountCode()) && (debit ? l.isDebit() : l.isCredit()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("line not found: " + code + " debit=" + debit));
  }
}
