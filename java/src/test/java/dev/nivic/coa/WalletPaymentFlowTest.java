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
 * Integration tests for luồng Thanh toán bằng Ví (Use Case 8):
 * initWalletPayment (DR 2110 / CR 3500) → settleWalletPayment (DR 3500 / CR 2120).
 *
 * <p>PDF: 2110 -100,000 | 2120 +100,000 | Không phát sinh TK NH | Transit 3500 = 0.
 * 2120 giữ tiền chờ Settlement &amp; Clearing (Use Case 11).</p>
 */
@Testcontainers
@Tag("integration")
class WalletPaymentFlowTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

  private static final long AMOUNT = 100_000L;

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
    // Seed: user nạp 500k để có tiền thanh toán
    ledger.receiveTopUp(new TopUpReceiveCmd(500_000L, "SEED-RECV", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(500_000L, 0L, "SEED-CONF", null));
    // 2110 = -500k | 1111 = +500k
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Step 1: initWalletPayment ─────────────────────────────────────────────────

  @Test
  void init_postsTwoBalancedLines() {
    CoaTrans t = ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-I-01", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    CoaTransLine dr2110 = lineOf(t, "2110", true);
    assertEquals(AMOUNT, dr2110.debitMinor(), "DR 2110 = amount");
    assertEquals("Wallet Balance - User", dr2110.accountName());

    CoaTransLine cr3500 = lineOf(t, "3500", false);
    assertEquals(AMOUNT, cr3500.creditMinor(), "CR 3500 = amount");
    assertEquals("Transit - Thanh toán", cr3500.accountName());
  }

  @Test
  void init_updatesWalletAndTransit() {
    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-I-02", null));

    // 2110: -500k + 100k (DR) = -400k
    assertEquals(-500_000L + AMOUNT, ledger.getBalance("2110"), "user wallet debited");
    assertEquals(-AMOUNT, ledger.getBalance("3500"), "transit holds amount");
  }

  @Test
  void init_idempotent() {
    CoaTrans first  = ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-I-03", null));
    CoaTrans second = ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-I-03", null));

    assertEquals(first.id(), second.id());
    assertEquals(-500_000L + AMOUNT, ledger.getBalance("2110"), "no double-debit on retry");
  }

  @Test
  void init_insufficientWallet_throws() {
    assertThrows(InsufficientWalletException.class,
        () -> ledger.initWalletPayment(new WalletPaymentInitCmd(600_000L, "WP-OVER", null)));

    assertEquals(-500_000L, ledger.getBalance("2110"), "balance unchanged");
    assertEquals(0L, ledger.getBalance("3500"), "transit unchanged");
  }

  @Test
  void init_noBankAccountMovement() {
    long b1111 = ledger.getBalance("1111");
    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-NH", null));
    assertEquals(b1111, ledger.getBalance("1111"), "NH không phát sinh");
  }

  // ── Step 2: settleWalletPayment ───────────────────────────────────────────────

  @Test
  void settle_postsTwoLines() {
    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-I-04", null));
    CoaTrans t = ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-S-04", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    assertEquals(AMOUNT, lineOf(t, "3500", true).debitMinor(), "DR 3500 = release transit");
    assertEquals(AMOUNT, lineOf(t, "2120", false).creditMinor(), "CR 2120 = merchant wallet");
    assertEquals("Wallet Balance Merchant", lineOf(t, "2120", false).accountName());
  }

  @Test
  void settle_allBalancesMatchPdf() {
    // PDF: 2110 -100,000 | 2120 +100,000 | Transit 3500 = 0 | Không phát sinh TK NH
    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-I-05", null));
    ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-S-05", null));

    assertEquals(-500_000L + AMOUNT, ledger.getBalance("2110"), "user wallet -100k net");
    assertEquals(            0L,     ledger.getBalance("3500"), "Transit Thanh toán = 0 ✓");
    assertEquals(       -AMOUNT,     ledger.getBalance("2120"), "merchant +100k (chờ settlement) ✓");
    assertEquals(      500_000L,     ledger.getBalance("1111"), "NH không phát sinh");
    assertEquals(            0L,     ledger.getBalance("1112"), "Napas không phát sinh");
  }

  @Test
  void settle_transitClearsToZero() {
    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-I-06", null));
    ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-S-06", null));
    assertEquals(0L, ledger.getBalance("3500"), "Transit 3500 must be 0 after full flow");
  }

  @Test
  void settle_idempotent() {
    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-I-07", null));
    CoaTrans first  = ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-S-07", null));
    CoaTrans second = ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-S-07", null));

    assertEquals(first.id(), second.id());
    assertEquals(0L, ledger.getBalance("3500"), "no double-debit on retry");
  }

  @Test
  void settle_withoutInit_throws() {
    assertThrows(InsufficientTransitException.class,
        () -> ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-ORPHAN", null)));
  }

  // ── Full flow ─────────────────────────────────────────────────────────────────

  @Test
  void fullFlow_doubleEntryAlwaysBalanced() {
    assertTrue(ledger.isDoubleEntryBalanced(), "after setUp");

    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-E2E-I", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after init");

    ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-E2E-S", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after settle");
  }

  @Test
  void fullFlow_walletToMerchant_noBankTouched() {
    // Thanh toán ví: user 2110 → merchant 2120, không qua bất kỳ TK NH nào
    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-NB-I", null));
    ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-NB-S", null));

    assertEquals(500_000L, ledger.getBalance("1111"), "Vietinbank không động");
    assertEquals(      0L, ledger.getBalance("1112"), "Napas không động");
    assertEquals(      0L, ledger.getBalance("1113"), "VPBank không động");
  }

  @Test
  void fullFlow_chainsWithEodSettlement() {
    // Thanh toán ví → merchant 2120 tích lũy → EOD settle về NH
    ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-EOD-I", null));
    ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-EOD-S", null));

    // 2120 = -100k → EOD settle (no MDR for simplicity)
    ledger.eodInitClearing(new EodClearingInitCmd(AMOUNT, "WP-EOD-CLR", null));
    ledger.eodReconcile(new EodReconcileCmd(AMOUNT, 1_000L, "WP-EOD-REC", null));
    ledger.eodRecognizeMdr(new EodRecognizeMdrCmd(1_000L, "WP-EOD-MDR", null));
    ledger.eodSettleOutbound(new EodSettleOutboundCmd(99_000L, 500L, "WP-EOD-OUT", null));

    assertEquals(0L, ledger.getBalance("2120"), "merchant wallet settled");
    assertEquals(0L, ledger.getBalance("3500"), "payment transit clear");
    assertEquals(0L, ledger.getBalance("3800"), "clearing transit clear");
    assertEquals(0L, ledger.getBalance("3810"), "settlement transit clear");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fullFlow_multiplePayments_accumulateInMerchantWallet() {
    // 3 thanh toán ví, merchant tích lũy chờ settlement
    for (int i = 1; i <= 3; i++) {
      ledger.initWalletPayment(new WalletPaymentInitCmd(AMOUNT, "WP-M-I-" + i, null));
      ledger.settleWalletPayment(new WalletPaymentSettleCmd(AMOUNT, "WP-M-S-" + i, null));
    }

    assertEquals(-500_000L + 3 * AMOUNT, ledger.getBalance("2110"), "user wallet: -500k + 300k");
    assertEquals(-3 * AMOUNT, ledger.getBalance("2120"), "merchant: 300k chờ settlement");
    assertEquals(0L, ledger.getBalance("3500"), "all transit cleared");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── Helper ───────────────────────────────────────────────────────────────────

  private static CoaTransLine lineOf(CoaTrans t, String code, boolean debit) {
    return t.lines().stream()
        .filter(l -> code.equals(l.accountCode()) && (debit ? l.isDebit() : l.isCredit()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("line not found: " + code + " debit=" + debit));
  }
}
