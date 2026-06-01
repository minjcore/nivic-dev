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
 * Integration tests for luồng Chi Hộ (Disbursement on Behalf, Use Case 10):
 * prefundDisbursement (DR 1111 / CR 2130) →
 * initDisbursement (DR 2130 / CR 3700) →
 * settleDisbursement (DR 3700 / DR 5100 / CR 4150 / CR 1112).
 *
 * <p>PDF example: chi hộ 100,000đ, phí 1,000đ, Napas 500đ.
 * Kết quả: 2130 -101,000 | 1111 +100,000 | 1112 -100,500 | 4150 +1,000 | 5100 +500 | Lãi thuần +500</p>
 */
@Testcontainers
@Tag("integration")
class DisbursementFlowTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

  private static final long PREFUND    = 200_000L; // đối tác nạp ký quỹ
  private static final long AMOUNT     = 100_000L;
  private static final long FEE        =   1_000L;
  private static final long NAPAS_COST =     500L;
  private static final long TOTAL_LOCK = AMOUNT + FEE;          // 101,000

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

  // ── Bước 0: prefundDisbursement ──────────────────────────────────────────────

  @Test
  void prefund_postsTwoBalancedLines() {
    CoaTrans t = ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-01", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    CoaTransLine dr1111 = lineOf(t, "1111", true);
    assertEquals(PREFUND, dr1111.debitMinor(), "DR 1111 = tiền ký quỹ");
    assertEquals("TK Vietinbank Chuyên dùng", dr1111.accountName());

    CoaTransLine cr2130 = lineOf(t, "2130", false);
    assertEquals(PREFUND, cr2130.creditMinor(), "CR 2130 = nghĩa vụ với đối tác");
    assertEquals("Ký quỹ - Đối tác Chi hộ", cr2130.accountName());
  }

  @Test
  void prefund_updatesBalances() {
    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-02", null));

    assertEquals( PREFUND, ledger.getBalance("1111"), "Vietinbank nhận tiền ký quỹ");
    assertEquals(-PREFUND, ledger.getBalance("2130"), "Ký quỹ đối tác (credit-normal)");
  }

  @Test
  void prefund_idempotent() {
    CoaTrans first  = ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-03", null));
    CoaTrans second = ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-03", null));

    assertEquals(first.id(), second.id());
    assertEquals(PREFUND, ledger.getBalance("1111"), "no double-credit on retry");
  }

  // ── Bước 1a: initDisbursement ────────────────────────────────────────────────

  @Test
  void init_postsTwoBalancedLines() {
    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-I01", null));
    CoaTrans t = ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "DIS-I-01", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    assertEquals(TOTAL_LOCK, lineOf(t, "2130", true).debitMinor(), "DR 2130 = amount + fee");
    assertEquals(TOTAL_LOCK, lineOf(t, "3700", false).creditMinor(), "CR 3700 = amount + fee");
    assertEquals("Transit - Chi hộ", lineOf(t, "3700", false).accountName());
  }

  @Test
  void init_updatesEscrowAndTransit() {
    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-I02", null));
    ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "DIS-I-02", null));

    // 2130: -200k (prefund) + 101k (DR) = -99k
    assertEquals(-PREFUND + TOTAL_LOCK, ledger.getBalance("2130"),
        "escrow giảm theo total lock");
    assertEquals(-TOTAL_LOCK, ledger.getBalance("3700"), "transit chi hộ giữ tiền");
  }

  @Test
  void init_insufficientEscrow_throws() {
    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-I03", null));
    // ký quỹ 200k, cố chi 250k
    assertThrows(InsufficientEscrowException.class,
        () -> ledger.initDisbursement(new DisbursementInitCmd(250_000L, FEE, "DIS-OVER", null)));

    assertEquals(-PREFUND, ledger.getBalance("2130"), "escrow unchanged");
    assertEquals(0L, ledger.getBalance("3700"), "transit unchanged");
  }

  @Test
  void init_withoutPrefund_throws() {
    // Chưa pre-fund → 2130 = 0 → không đủ ký quỹ
    assertThrows(InsufficientEscrowException.class,
        () -> ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "DIS-NOPF", null)));
  }

  @Test
  void init_idempotent() {
    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-I04", null));
    CoaTrans first  = ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "DIS-I-04", null));
    CoaTrans second = ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "DIS-I-04", null));

    assertEquals(first.id(), second.id());
    assertEquals(-PREFUND + TOTAL_LOCK, ledger.getBalance("2130"), "no double-debit on retry");
  }

  // ── Bước 1b: settleDisbursement ──────────────────────────────────────────────

  @Test
  void settle_postsFourBalancedLines() {
    doPrefundAndInit();
    CoaTrans t = ledger.settleDisbursement(
        new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "DIS-S-01", null));

    assertTrue(t.isBalanced());
    assertEquals(4, t.lines().size());

    assertEquals(TOTAL_LOCK,        lineOf(t, "3700", true).debitMinor(), "DR 3700 = transit release");
    assertEquals(NAPAS_COST,        lineOf(t, "5100", true).debitMinor(), "DR 5100 = Napas cost");
    assertEquals(FEE,               lineOf(t, "4150", false).creditMinor(), "CR 4150 = doanh thu phí chi hộ");
    assertEquals(AMOUNT + NAPAS_COST, lineOf(t, "1112", false).creditMinor(), "CR 1112 = gửi + phí Napas");
    assertEquals("Doanh thu Phí Chi Lương/Chi hộ", lineOf(t, "4150", false).accountName());
  }

  @Test
  void settle_allBalancesMatchPdf() {
    // PDF: 2130 -101k | 1111 +100k (prefund 200k - ... no, 1111 stays at prefund) | 1112 -100.5k | 4150 +1k | 5100 +500
    doPrefundAndInit();
    ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "DIS-S-02", null));

    // 2130: -200k (prefund) + 101k (init DR) = -99k → ký quỹ còn lại 99k
    assertEquals(-PREFUND + TOTAL_LOCK, ledger.getBalance("2130"), "ký quỹ còn lại");
    assertEquals( PREFUND,              ledger.getBalance("1111"), "1111 giữ nguyên tiền pre-fund");
    assertEquals(             0L,       ledger.getBalance("3700"), "Transit Chi hộ = 0 ✓");
    assertEquals(-(AMOUNT + NAPAS_COST), ledger.getBalance("1112"), "1112 = -100,500 ✓");
    assertEquals(-FEE,                  ledger.getBalance("4150"), "4150 = +1,000 revenue ✓");
    assertEquals(NAPAS_COST,            ledger.getBalance("5100"), "5100 = +500 expense ✓");
  }

  @Test
  void settle_transitClearsToZero() {
    doPrefundAndInit();
    ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "DIS-S-03", null));
    assertEquals(0L, ledger.getBalance("3700"), "Transit 3700 must be 0 after full flow");
  }

  @Test
  void settle_netProfit_feeMinusNapasCost() {
    doPrefundAndInit();
    ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "DIS-S-04", null));

    long revenue = -ledger.getBalance("4150");
    long expense =  ledger.getBalance("5100");
    assertEquals(FEE - NAPAS_COST, revenue - expense, "lãi thuần = fee − napasCost");
    assertEquals(500L, revenue - expense);
  }

  @Test
  void settle_withoutInit_throws() {
    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-S05", null));
    // Chưa init → 3700 = 0
    assertThrows(InsufficientTransitException.class,
        () -> ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "DIS-ORPHAN", null)));
  }

  @Test
  void settle_idempotent() {
    doPrefundAndInit();
    CoaTrans first  = ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "DIS-S-06", null));
    CoaTrans second = ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "DIS-S-06", null));

    assertEquals(first.id(), second.id());
    assertEquals(0L, ledger.getBalance("3700"), "no double-debit on retry");
  }

  @Test
  void settle_zeroFee_threeLines() {
    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-PF-NOFEE", null));
    ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, 0L, "DIS-I-NOFEE", null));
    CoaTrans t = ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, 0L, NAPAS_COST, "DIS-S-NOFEE", null));

    assertTrue(t.isBalanced());
    assertEquals(3, t.lines().size(), "zero fee: DR 3700 + DR 5100 + CR 1112 (no CR 4150)");
    assertEquals(0L, ledger.getBalance("4150"), "no revenue on zero fee");
  }

  // ── Full flow ─────────────────────────────────────────────────────────────────

  @Test
  void fullFlow_doubleEntryAlwaysBalanced() {
    assertTrue(ledger.isDoubleEntryBalanced(), "empty");

    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "DIS-E2E-PF", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after prefund");

    ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "DIS-E2E-I", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after init");

    ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "DIS-E2E-S", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after settle");
  }

  @Test
  void fullFlow_matchesPdfExample() {
    // PDF: 2130 -101,000 | 1111 +100,000 | 1112 -100,500 | 4150 +1,000 | 5100 +500 | Lãi thuần +500
    // PDF pre-fund 100,000 nhưng chi hộ lock 101,000 (gốc + phí) → cần ký quỹ ≥ 101k.
    // Dùng pre-fund 101,000 để khớp invariant (đối tác nạp đủ gốc + phí).
    ledger.prefundDisbursement(new DisbursementPrefundCmd(101_000L, "PDF-PF", null));
    ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "PDF-I", null));
    ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "PDF-S", null));

    assertEquals(            0L, ledger.getBalance("2130"), "ký quỹ đã dùng hết: -101k + 101k = 0");
    assertEquals(     101_000L, ledger.getBalance("1111"), "1111 giữ tiền pre-fund");
    assertEquals(-(AMOUNT + NAPAS_COST), ledger.getBalance("1112"), "1112 = -100,500 ✓");
    assertEquals(         -FEE, ledger.getBalance("4150"), "4150 = +1,000 ✓");
    assertEquals(   NAPAS_COST, ledger.getBalance("5100"), "5100 = +500 ✓");
    assertEquals(           0L, ledger.getBalance("3700"), "Transit Chi hộ = 0 ✓");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fullFlow_multipleDisbursements_drainEscrow() {
    // Pre-fund 1M, chi hộ 5 lần × (100k + 1k phí) = 505k
    ledger.prefundDisbursement(new DisbursementPrefundCmd(1_000_000L, "MULTI-PF", null));

    for (int i = 1; i <= 5; i++) {
      ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "MULTI-I-" + i, null));
      ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "MULTI-S-" + i, null));
    }

    // 2130: -1M + 5×101k = -1M + 505k = -495k (ký quỹ còn 495k)
    assertEquals(-1_000_000L + 5 * TOTAL_LOCK, ledger.getBalance("2130"));
    assertEquals(0L, ledger.getBalance("3700"), "all transit cleared");
    assertEquals(-5 * FEE, ledger.getBalance("4150"), "5 × 1k revenue");
    assertEquals(5 * NAPAS_COST, ledger.getBalance("5100"), "5 × 500 Napas cost");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fullFlow_escrowExhausted_initThrows() {
    // Pre-fund 100k, đã chi hết → lần sau init thiếu ký quỹ
    ledger.prefundDisbursement(new DisbursementPrefundCmd(101_000L, "EXH-PF", null));
    ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "EXH-I-1", null));
    ledger.settleDisbursement(new DisbursementSettleCmd(AMOUNT, FEE, NAPAS_COST, "EXH-S-1", null));

    // 2130 = -101k + 101k = 0 → hết ký quỹ
    assertEquals(0L, ledger.getBalance("2130"));
    assertThrows(InsufficientEscrowException.class,
        () -> ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "EXH-I-2", null)));
  }

  // ── Helper ───────────────────────────────────────────────────────────────────

  private void doPrefundAndInit() {
    ledger.prefundDisbursement(new DisbursementPrefundCmd(PREFUND, "HELPER-PF", null));
    ledger.initDisbursement(new DisbursementInitCmd(AMOUNT, FEE, "HELPER-I", null));
  }

  private static CoaTransLine lineOf(CoaTrans t, String code, boolean debit) {
    return t.lines().stream()
        .filter(l -> code.equals(l.accountCode()) && (debit ? l.isDebit() : l.isCredit()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("line not found: " + code + " debit=" + debit));
  }
}
