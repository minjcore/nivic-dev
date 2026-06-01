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
 * Integration tests for luồng Thanh toán QR/POS:
 * receiveQrPos (4-leg: DR 1113 / CR 3500 / DR 5100 / CR 1113) →
 * creditMerchantQrPos (DR 3500 / CR 2120).
 *
 * <p>2120 Wallet Merchant giữ tiền chờ Settlement & Clearing (Use Case 10).</p>
 */
@Testcontainers
@Tag("integration")
class QrPosFlowTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

  // PDF example: 100k payment, 500 VPBank cost
  private static final long AMOUNT      = 100_000L;
  private static final long VPBANK_COST = 500L;

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
  void setUp() { ledger = new JdbcFundFlowLedger(ds); }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Step 1: receiveQrPos ─────────────────────────────────────────────────────

  @Test
  void receive_postsFourBalancedLines() {
    CoaTrans t = ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-01", null));

    assertTrue(t.isBalanced());
    assertEquals(4, t.lines().size());

    // DR 1113 amount
    CoaTransLine dr1113 = t.lines().stream()
        .filter(l -> "1113".equals(l.accountCode()) && l.isDebit())
        .findFirst().orElseThrow();
    assertEquals(AMOUNT, dr1113.debitMinor());
    assertEquals("TK VPBank - QR/POS", dr1113.accountName());

    // CR 3500 amount
    CoaTransLine cr3500 = t.lines().stream()
        .filter(l -> "3500".equals(l.accountCode()) && l.isCredit())
        .findFirst().orElseThrow();
    assertEquals(AMOUNT, cr3500.creditMinor());
    assertEquals("Transit - Thanh toán", cr3500.accountName());

    // DR 5100 vpbankCost
    CoaTransLine dr5100 = t.lines().stream()
        .filter(l -> "5100".equals(l.accountCode()) && l.isDebit())
        .findFirst().orElseThrow();
    assertEquals(VPBANK_COST, dr5100.debitMinor());
    assertEquals("Chi phí Phí NH / Napas", dr5100.accountName());

    // CR 1113 vpbankCost
    CoaTransLine cr1113 = t.lines().stream()
        .filter(l -> "1113".equals(l.accountCode()) && l.isCredit())
        .findFirst().orElseThrow();
    assertEquals(VPBANK_COST, cr1113.creditMinor());
  }

  @Test
  void receive_updatesBalancesCorrectly() {
    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-02", null));

    // 1113 net: +100k (DR) - 500 (CR) = +99,500
    assertEquals(AMOUNT - VPBANK_COST, ledger.getBalance("1113"),
        "net 1113 = amount − vpbankCost");
    // 3500 transit: -100k (CR)
    assertEquals(-AMOUNT, ledger.getBalance("3500"), "transit holds full amount");
    // 5100 expense: +500
    assertEquals(VPBANK_COST, ledger.getBalance("5100"), "VPBank cost recorded");
    // Không đụng TK nào khác
    assertEquals(0L, ledger.getBalance("1111"), "Vietinbank unchanged");
    assertEquals(0L, ledger.getBalance("1112"), "Napas unchanged");
    assertEquals(0L, ledger.getBalance("2110"), "User wallet unchanged");
    assertEquals(0L, ledger.getBalance("2120"), "Merchant wallet not yet credited");
  }

  @Test
  void receive_idempotent() {
    CoaTrans first  = ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-03", null));
    CoaTrans second = ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-03", null));

    assertEquals(first.id(), second.id());
    assertEquals(AMOUNT - VPBANK_COST, ledger.getBalance("1113"), "no double-credit on retry");
  }

  @Test
  void receive_zeroVpbankCost_stillBalanced() {
    CoaTrans t = ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, 0L, "QR-R-NOFEE", null));

    // 0-cost: still 4 lines but CR 1113 line_no=4 has creditMinor=0
    assertTrue(t.isBalanced());
    assertEquals(AMOUNT, ledger.getBalance("1113"),
        "zero vpbankCost: 1113 net = full amount");
    assertEquals(0L, ledger.getBalance("5100"), "no expense on zero cost");
  }

  // ── Step 2: creditMerchantQrPos ───────────────────────────────────────────────

  @Test
  void creditMerchant_postsTwoLines() {
    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-04", null));
    CoaTrans t = ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-C-04", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    CoaTransLine dr3500 = t.lines().stream()
        .filter(l -> "3500".equals(l.accountCode()) && l.isDebit())
        .findFirst().orElseThrow();
    assertEquals(AMOUNT, dr3500.debitMinor(), "DR transit = full amount");

    CoaTransLine cr2120 = t.lines().stream()
        .filter(l -> "2120".equals(l.accountCode()))
        .findFirst().orElseThrow();
    assertEquals(AMOUNT, cr2120.creditMinor(), "CR merchant wallet = full amount");
    assertEquals("Wallet Balance Merchant", cr2120.accountName());
  }

  @Test
  void creditMerchant_allBalancesMatchPdf() {
    // PDF: 1113 +99,500 | 2120 −100,000 | 5100 +500 | Transit 3500 = 0
    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-05", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-C-05", null));

    assertEquals( 99_500L, ledger.getBalance("1113"), "VPBank: net 99.5k");
    assertEquals(      0L, ledger.getBalance("3500"), "Transit Thanh toán cleared");
    assertEquals(-100_000L, ledger.getBalance("2120"), "Merchant wallet: 100k (chờ settlement)");
    assertEquals(    500L, ledger.getBalance("5100"), "Chi phí VPBank");
  }

  @Test
  void creditMerchant_transitClearsToZero() {
    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-06", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-C-06", null));

    assertEquals(0L, ledger.getBalance("3500"), "Transit 3500 must be 0 after full flow");
  }

  @Test
  void creditMerchant_merchantWalletAccumulates_pendingSettlement() {
    // 3 giao dịch QR, merchant wallet tích lũy — chờ EOD settlement
    for (int i = 1; i <= 3; i++) {
      ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-ACC-" + i, null));
      ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-C-ACC-" + i, null));
    }

    assertEquals(3 * (AMOUNT - VPBANK_COST), ledger.getBalance("1113"),
        "VPBank: 3 × 99.5k");          // 298,500
    assertEquals(              0L, ledger.getBalance("3500"), "all transit cleared");
    assertEquals(-3 * AMOUNT, ledger.getBalance("2120"),
        "merchant wallet: 3 × 100k chờ settlement"); // -300,000
    assertEquals(3 * VPBANK_COST, ledger.getBalance("5100"),
        "total VPBank cost: 3 × 500");  // 1,500
  }

  @Test
  void creditMerchant_idempotent() {
    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-R-07", null));
    CoaTrans first  = ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-C-07", null));
    CoaTrans second = ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-C-07", null));

    assertEquals(first.id(), second.id());
    assertEquals(0L, ledger.getBalance("3500"), "no double-debit on retry");
  }

  @Test
  void creditMerchant_withoutReceive_throws() {
    assertThrows(InsufficientTransitException.class,
        () -> ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-ORPHAN", null)));
  }

  // ── Full flow ─────────────────────────────────────────────────────────────────

  @Test
  void fullFlow_doubleEntryAlwaysBalanced() {
    assertTrue(ledger.isDoubleEntryBalanced(), "empty");

    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-E2E-R", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after receive");

    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-E2E-C", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after credit merchant");
  }

  @Test
  void fullFlow_userWalletAndVietinbankNotTouched() {
    // QR/POS: tiền đến từ user's bank account trực tiếp — không qua ví user (2110) hay Vietinbank (1111)
    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-NH-R", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-NH-C", null));

    assertEquals(0L, ledger.getBalance("2110"), "user wallet 2110 không phát sinh");
    assertEquals(0L, ledger.getBalance("1111"), "Vietinbank 1111 không phát sinh");
    assertEquals(0L, ledger.getBalance("1112"), "Napas 1112 không phát sinh");
  }

  @Test
  void fullFlow_merchantWalletPendingSettlement() {
    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-PEND-R", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-PEND-C", null));

    // 2120 âm = merchant được credit (credit-normal account)
    assertTrue(ledger.getBalance("2120") < 0,
        "2120 Merchant Wallet âm = merchant có tiền chờ settlement");
    assertEquals(-AMOUNT, ledger.getBalance("2120"),
        "merchant giữ đúng số tiền = amount (trước MDR)");
  }

  @Test
  void fullFlow_mixedQrPosAndOtherFlows_allTransitsClear() {
    // Kết hợp nạp tiền + QR/POS
    ledger.receiveTopUp(new TopUpReceiveCmd(200_000L, "TOP-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(200_000L, 0L, "TOP-C", null));

    ledger.receiveQrPos(new QrPosReceiveCmd(AMOUNT, VPBANK_COST, "QR-MIX-R", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(AMOUNT, "QR-MIX-C", null));

    assertEquals(0L, ledger.getBalance("3100"), "Transit Nạp clear");
    assertEquals(0L, ledger.getBalance("3500"), "Transit Thanh toán clear");
    assertTrue(ledger.isDoubleEntryBalanced());
  }
}
