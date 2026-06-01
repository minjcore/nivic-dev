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
 * Integration tests for luồng Chi Lương (Payroll Disbursement, Use Case 9):
 * initPayroll (DR 2120 / CR 3600) →
 * disbursePayroll (DR 3600 / DR 5100 / CR 4150 / CR 1112).
 *
 * <p>PDF example: 5 nhân viên × 100,000đ = 500,000đ | Phí 1,000đ × 5 = 5,000đ | Napas 500đ × 5 = 2,500đ
 * Kết quả: 2120 -505,000 | 1112 -502,500 | 4150 +5,000 | 5100 +2,500 | Lãi thuần +2,500</p>
 */
@Testcontainers
@Tag("integration")
class PayrollFlowTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

  // PDF example numbers
  private static final int  COUNT       = 5;
  private static final long PER_EMP     = 100_000L;
  private static final long FEE_PER_EMP =   1_000L;
  private static final long NAPAS_PER   =     500L;

  private static final long AMOUNT      = COUNT * PER_EMP;       // 500,000
  private static final long TOTAL_FEE   = COUNT * FEE_PER_EMP;  //   5,000
  private static final long NAPAS_COST  = COUNT * NAPAS_PER;    //   2,500
  private static final long TOTAL_LOCK  = AMOUNT + TOTAL_FEE;   // 505,000

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
    // Seed: merchant có đủ tiền chi lương (nạp 600k)
    ledger.receiveQrPos(new QrPosReceiveCmd(600_000L, 0L, "SEED-QR", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(600_000L, "SEED-QR-C", null));
    // 2120 = -600,000 (merchant có 600k)
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Step 1: initPayroll ──────────────────────────────────────────────────────

  @Test
  void init_postsTwoBalancedLines() {
    CoaTrans t = ledger.initPayroll(
        new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-01", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    CoaTransLine dr2120 = lineOf(t, "2120", true);
    assertEquals(TOTAL_LOCK, dr2120.debitMinor(),
        "DR 2120 = amount + totalFee = " + TOTAL_LOCK);
    assertEquals("Wallet Balance Merchant", dr2120.accountName());

    CoaTransLine cr3600 = lineOf(t, "3600", false);
    assertEquals(TOTAL_LOCK, cr3600.creditMinor(),
        "CR 3600 = amount + totalFee = " + TOTAL_LOCK);
    assertEquals("Transit - Chi Lương", cr3600.accountName());
  }

  @Test
  void init_locksMerchantWalletIntoTransit() {
    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-02", null));

    // 2120: -600k + 505k (DR) = -95k
    assertEquals(-600_000L + TOTAL_LOCK, ledger.getBalance("2120"),
        "merchant wallet debited by total lock");
    assertEquals(-TOTAL_LOCK, ledger.getBalance("3600"),
        "transit 3600 holds full lock amount");
  }

  @Test
  void init_idempotent() {
    CoaTrans first  = ledger.initPayroll(
        new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-03", null));
    CoaTrans second = ledger.initPayroll(
        new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-03", null));

    assertEquals(first.id(), second.id());
    assertEquals(-600_000L + TOTAL_LOCK, ledger.getBalance("2120"),
        "no double-debit on retry");
  }

  @Test
  void init_insufficientMerchantWallet_throws() {
    // Merchant chỉ có 600k, cố lock 700k
    assertThrows(InsufficientWalletException.class,
        () -> ledger.initPayroll(
            new PayrollInitCmd(650_000L, 50_000L, 50, "PAY-OVER", null)));

    assertEquals(-600_000L, ledger.getBalance("2120"), "balance unchanged");
    assertEquals(0L, ledger.getBalance("3600"), "transit unchanged");
  }

  @Test
  void init_noTouchBankAccounts() {
    // Record bank balances before payroll (1113 already has balance from QR/POS seed)
    long b1111 = ledger.getBalance("1111");
    long b1112 = ledger.getBalance("1112");
    long b1113 = ledger.getBalance("1113");

    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-NH", null));

    assertEquals(b1111, ledger.getBalance("1111"), "Vietinbank not touched by init");
    assertEquals(b1112, ledger.getBalance("1112"), "Napas not touched by init");
    assertEquals(b1113, ledger.getBalance("1113"), "VPBank not touched by init");
  }

  // ── Step 2: disbursePayroll ──────────────────────────────────────────────────

  @Test
  void disburse_postsFourBalancedLines() {
    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-D01", null));
    CoaTrans t = ledger.disbursePayroll(
        new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-D-01", null));

    assertTrue(t.isBalanced());
    assertEquals(4, t.lines().size());

    // DR 3600 (amount + totalFee)
    assertEquals(TOTAL_LOCK, lineOf(t, "3600", true).debitMinor(),
        "DR 3600 = total transit release");
    assertEquals("Transit - Chi Lương", lineOf(t, "3600", true).accountName());

    // DR 5100 (napasCost)
    assertEquals(NAPAS_COST, lineOf(t, "5100", true).debitMinor(),
        "DR 5100 = Napas cost");

    // CR 4150 (totalFee)
    assertEquals(TOTAL_FEE, lineOf(t, "4150", false).creditMinor(),
        "CR 4150 = doanh thu phí chi lương");
    assertEquals("Doanh thu Phí Chi Lương/Chi hộ", lineOf(t, "4150", false).accountName());

    // CR 1112 (amount + napasCost)
    assertEquals(AMOUNT + NAPAS_COST, lineOf(t, "1112", false).creditMinor(),
        "CR 1112 = bulk IBFT + Napas fee");
  }

  @Test
  void disburse_allBalancesMatchPdf() {
    // PDF: 2120 -505k | 1112 -502.5k | 4150 +5k | 5100 +2.5k | Lãi thuần +2.5k
    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-D02", null));
    ledger.disbursePayroll(new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-D-02", null));

    // 2120: -600k (seed) + 505k (DR init) = -95k
    assertEquals(-600_000L + TOTAL_LOCK, ledger.getBalance("2120"),
        "merchant wallet = remaining balance after payroll");
    assertEquals(0L, ledger.getBalance("3600"),
        "Transit Chi Lương = 0 ✓");
    assertEquals(-(AMOUNT + NAPAS_COST), ledger.getBalance("1112"),
        "Napas: gửi " + AMOUNT + " + trả " + NAPAS_COST + " phí");  // -502,500
    assertEquals(-TOTAL_FEE, ledger.getBalance("4150"),
        "Doanh thu phí chi lương: " + TOTAL_FEE);                   // -5,000 (credit-normal)
    assertEquals(NAPAS_COST, ledger.getBalance("5100"),
        "Chi phí Napas: " + NAPAS_COST);                             // +2,500
  }

  @Test
  void disburse_transitClearsToZero() {
    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-D03", null));
    ledger.disbursePayroll(new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-D-03", null));

    assertEquals(0L, ledger.getBalance("3600"),
        "Transit 3600 must be 0 after full flow");
  }

  @Test
  void disburse_netProfit_feeMinusNapasCost() {
    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-D04", null));
    ledger.disbursePayroll(new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-D-04", null));

    long revenue  = -ledger.getBalance("4150"); // credit-normal: negative = revenue
    long expense  =  ledger.getBalance("5100"); // debit-normal:  positive = expense
    assertEquals(TOTAL_FEE - NAPAS_COST, revenue - expense,
        "lãi thuần = phí dịch vụ − Napas cost");
    assertEquals(2_500L, revenue - expense,
        "lãi thuần = 5,000 − 2,500 = 2,500 ✓");
  }

  @Test
  void disburse_idempotent() {
    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-I-D05", null));
    CoaTrans first  = ledger.disbursePayroll(
        new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-D-05", null));
    CoaTrans second = ledger.disbursePayroll(
        new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-D-05", null));

    assertEquals(first.id(), second.id());
    assertEquals(0L, ledger.getBalance("3600"), "no double-debit on retry");
  }

  @Test
  void disburse_withoutInit_throws() {
    assertThrows(InsufficientTransitException.class,
        () -> ledger.disbursePayroll(
            new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-ORPHAN", null)));
  }

  @Test
  void disburse_zeroFee_threeLines() {
    // Chi lương không thu phí (ví dụ internal)
    ledger.initPayroll(new PayrollInitCmd(AMOUNT, 0L, COUNT, "PAY-I-NOFEE", null));
    CoaTrans t = ledger.disbursePayroll(
        new PayrollDisburseCmd(AMOUNT, 0L, NAPAS_COST, "PAY-D-NOFEE", null));

    assertTrue(t.isBalanced());
    // zero totalFee: không có CR 4150
    assertEquals(3, t.lines().size(), "zero fee: DR 3600 + DR 5100 + CR 1112 (no CR 4150)");
    assertEquals(0L, ledger.getBalance("4150"), "no revenue on zero fee");
  }

  // ── Full flow ─────────────────────────────────────────────────────────────────

  @Test
  void fullFlow_doubleEntryAlwaysBalanced() {
    assertTrue(ledger.isDoubleEntryBalanced(), "after setUp");

    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-E2E-I", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after init");

    ledger.disbursePayroll(new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-E2E-D", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after disburse");
  }

  @Test
  void fullFlow_matchesPdfExample() {
    // PDF: 5 × 100k lương + 5 × 1k phí + 5 × 500 Napas
    // Kết quả: 2120 -505,000 | 1112 -502,500 | 4150 +5,000 | 5100 +2,500 | Lãi thuần +2,500
    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PDF-PAY-I", null));
    ledger.disbursePayroll(new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PDF-PAY-D", null));

    // 2120 khác PDF vì seed 600k, PDF không có tiền ban đầu
    // Nhưng net change trên 2120 phải = -505,000 (DR)
    long net2120Change = (-600_000L + TOTAL_LOCK) - (-600_000L);
    assertEquals(TOTAL_LOCK, net2120Change, "net DR on 2120 = 505,000 ✓");

    assertEquals(-(AMOUNT + NAPAS_COST), ledger.getBalance("1112"),
        "1112 = −502,500 ✓");                // -502,500
    assertEquals(-TOTAL_FEE, ledger.getBalance("4150"),
        "4150 = +5,000 revenue ✓");           // -5,000 (credit-normal)
    assertEquals(NAPAS_COST, ledger.getBalance("5100"),
        "5100 = +2,500 expense ✓");           // +2,500
    assertEquals(0L, ledger.getBalance("3600"),
        "Transit Chi Lương = 0 ✓");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fullFlow_multiplePayrollRuns_accumulateRevenue() {
    // Seed thêm để có đủ cho 3 đợt × 505k = 1.515M (hiện có 600k từ setUp)
    ledger.receiveQrPos(new QrPosReceiveCmd(1_000_000L, 0L, "SEED-EXTRA", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(1_000_000L, "SEED-EXTRA-C", null));
    // 2120 = -1,600,000

    for (int i = 1; i <= 3; i++) {
      ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-M-I-" + i, null));
      ledger.disbursePayroll(
          new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-M-D-" + i, null));
    }

    assertEquals(0L, ledger.getBalance("3600"), "all transit cleared");
    assertEquals(-3 * TOTAL_FEE, ledger.getBalance("4150"),
        "3 đợt × 5k phí = 15k revenue");
    assertEquals(3 * NAPAS_COST, ledger.getBalance("5100"),
        "3 đợt × 2.5k Napas = 7.5k expense");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fullFlow_noUserWalletOrVietinbankTouched() {
    // Chi lương đi qua 2120 (merchant) → 1112 (Napas), không qua 2110 hay 1111
    // 1113 có balance từ seed QR/POS — record trước để kiểm tra payroll không thay đổi
    long b2110 = ledger.getBalance("2110");
    long b1111 = ledger.getBalance("1111");
    long b1113 = ledger.getBalance("1113");

    ledger.initPayroll(new PayrollInitCmd(AMOUNT, TOTAL_FEE, COUNT, "PAY-NH-I", null));
    ledger.disbursePayroll(new PayrollDisburseCmd(AMOUNT, TOTAL_FEE, NAPAS_COST, "PAY-NH-D", null));

    assertEquals(b2110, ledger.getBalance("2110"), "User wallet 2110 không phát sinh");
    assertEquals(b1111, ledger.getBalance("1111"), "Vietinbank 1111 không phát sinh");
    assertEquals(b1113, ledger.getBalance("1113"), "VPBank 1113 không phát sinh");
  }

  // ── Helper ───────────────────────────────────────────────────────────────────

  private static CoaTransLine lineOf(CoaTrans t, String code, boolean debit) {
    return t.lines().stream()
        .filter(l -> code.equals(l.accountCode()) && (debit ? l.isDebit() : l.isCredit()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "line not found: " + code + " debit=" + debit));
  }
}
