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
 * Integration tests for Settlement &amp; Clearing EOD (Use Case 11):
 * eodInitClearing → eodReconcile → eodRecognizeMdr → eodSettleOutbound,
 * and exception path eodRejectSettlement.
 */
@Testcontainers
@Tag("integration")
class EodSettlementFlowTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcFundFlowLedger ledger;

  private static final long GROSS     = 1_000_000L;
  private static final long MDR       = 15_000L;   // 1.5%
  private static final long NET       = GROSS - MDR;
  private static final long NAPAS_COST = 3_000L;

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
  static void closePool() {
    ds.close();
  }

  @BeforeEach
  void setUp() {
    ledger = new JdbcFundFlowLedger(ds);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  /** Fund 2120 via QR/POS (same as production path before EOD). */
  private void fundMerchantWallet() {
    ledger.receiveQrPos(new QrPosReceiveCmd(GROSS, 0L, "EOD-QR-R", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(GROSS, "EOD-QR-C", null));
    assertEquals(-GROSS, ledger.getBalance("2120"), "merchant wallet funded");
    assertEquals(0L, ledger.getBalance("3500"), "payment transit cleared");
  }

  // ── Happy path ───────────────────────────────────────────────────────────────

  @Test
  void eodInitClearing_locksMerchantTo3800() {
    fundMerchantWallet();

    CoaTrans t = ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-CLR-1", null));

    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());
    assertEquals(0L, ledger.getBalance("2120"), "2120 cleared to transit");
    assertEquals(-GROSS, ledger.getBalance("3800"), "3800 holds gross");
  }

  @Test
  void eodReconcile_splitsMdrAndNet() {
    fundMerchantWallet();
    ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-CLR-2", null));

    CoaTrans t = ledger.eodReconcile(new EodReconcileCmd(GROSS, MDR, "EOD-REC-2", null));

    assertTrue(t.isBalanced());
    assertEquals(3, t.lines().size());
    assertEquals(0L, ledger.getBalance("3800"), "clearing transit released");
    assertEquals(-MDR, ledger.getBalance("3820"), "MDR holdback");
    assertEquals(-NET, ledger.getBalance("3810"), "net settlement transit");
  }

  @Test
  void eodRecognizeMdr_postsRevenue() {
    fundMerchantWallet();
    ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-CLR-3", null));
    ledger.eodReconcile(new EodReconcileCmd(GROSS, MDR, "EOD-REC-3", null));

    CoaTrans t = ledger.eodRecognizeMdr(new EodRecognizeMdrCmd(MDR, "EOD-MDR-3", null));

    assertTrue(t.isBalanced());
    assertEquals(0L, ledger.getBalance("3820"), "MDR holdback cleared");
    assertEquals(-MDR, ledger.getBalance("4140"), "MDR revenue recognized");
  }

  @Test
  void eodSettleOutbound_paysNapasAndClears3810() {
    fundMerchantWallet();
    ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-CLR-4", null));
    ledger.eodReconcile(new EodReconcileCmd(GROSS, MDR, "EOD-REC-4", null));
    ledger.eodRecognizeMdr(new EodRecognizeMdrCmd(MDR, "EOD-MDR-4", null));

    CoaTrans t = ledger.eodSettleOutbound(
        new EodSettleOutboundCmd(NET, NAPAS_COST, "EOD-OUT-4", null));

    assertTrue(t.isBalanced());
    assertEquals(0L, ledger.getBalance("3810"), "settlement transit cleared");
    assertEquals(-(NET + NAPAS_COST), ledger.getBalance("1112"), "Napas asset credited (outflow)");
    assertEquals(NAPAS_COST, ledger.getBalance("5100"), "Napas cost expense");
  }

  @Test
  void fullEodHappyPath_allSettlementTransitsZero() {
    fundMerchantWallet();

    ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-CLR-E2E", null));
    ledger.eodReconcile(new EodReconcileCmd(GROSS, MDR, "EOD-REC-E2E", null));
    ledger.eodRecognizeMdr(new EodRecognizeMdrCmd(MDR, "EOD-MDR-E2E", null));
    ledger.eodSettleOutbound(new EodSettleOutboundCmd(NET, NAPAS_COST, "EOD-OUT-E2E", null));

    assertEquals(0L, ledger.getBalance("3800"));
    assertEquals(0L, ledger.getBalance("3810"));
    assertEquals(0L, ledger.getBalance("3820"));
    assertEquals(0L, ledger.getBalance("2120"), "merchant wallet empty after payout");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── Reject path ──────────────────────────────────────────────────────────────

  @Test
  void eodRejectSettlement_refundsMerchant() {
    fundMerchantWallet();
    ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-CLR-RJ", null));
    ledger.eodReconcile(new EodReconcileCmd(GROSS, MDR, "EOD-REC-RJ", null));

    CoaTrans t = ledger.eodRejectSettlement(
        new EodRejectSettlementCmd(NET, MDR, "EOD-REJ-RJ", null));

    assertTrue(t.isBalanced());
    assertEquals(0L, ledger.getBalance("3810"));
    assertEquals(0L, ledger.getBalance("3820"));
    assertEquals(-GROSS, ledger.getBalance("2120"), "merchant refunded in full");
  }

  // ── Idempotency & guards ─────────────────────────────────────────────────────

  @Test
  void eodInitClearing_idempotent() {
    fundMerchantWallet();
    CoaTrans first = ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-IDEM-CLR", null));
    CoaTrans second = ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-IDEM-CLR", null));
    assertEquals(first.id(), second.id());
    assertEquals(-GROSS, ledger.getBalance("3800"), "no double-lock on retry");
  }

  @Test
  void eodInitClearing_insufficientMerchantWallet() {
    assertThrows(InsufficientWalletException.class,
        () -> ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-NO-FUND", null)));
  }

  @Test
  void eodReconcile_withoutInit_throws() {
    fundMerchantWallet();
    assertThrows(InsufficientTransitException.class,
        () -> ledger.eodReconcile(new EodReconcileCmd(GROSS, MDR, "EOD-ORPHAN-REC", null)));
  }

  @Test
  void findTransByRefId_roundTrip() {
    fundMerchantWallet();
    ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-FIND-CLR", null));

    CoaTrans found = ledger.findTransByRefId("EOD-FIND-CLR");
    assertNotNull(found);
    assertEquals("EOD-FIND-CLR", found.refId());
    assertEquals(2, found.lines().size());
  }

  @Test
  void eodRejectSettlement_afterStep3_throws() {
    // Sau step 3 (recognizeMdr), 3820 đã về 0 → reject không thể lấy MDR lại
    fundMerchantWallet();
    ledger.eodInitClearing(new EodClearingInitCmd(GROSS, "EOD-CLR-RL3", null));
    ledger.eodReconcile(new EodReconcileCmd(GROSS, MDR, "EOD-REC-RL3", null));
    ledger.eodRecognizeMdr(new EodRecognizeMdrCmd(MDR, "EOD-MDR-RL3", null));

    // 3820 = 0 after step 3 → InsufficientTransitException
    assertThrows(InsufficientTransitException.class,
        () -> ledger.eodRejectSettlement(new EodRejectSettlementCmd(NET, MDR, "EOD-REJ-LATE", null)));
  }

  @Test
  void fullHappyPath_matchesPdfExample() {
    // PDF: merchant 200k → 1112=-198,500 | 4140=+2,000 | 5100=+500 | lãi thuần=+1,500
    long total = 200_000L, mdr = 2_000L, net = 198_000L, napas = 500L;

    ledger.receiveQrPos(new QrPosReceiveCmd(200_000L, 0L, "PDF-QR", null));
    ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(200_000L, "PDF-QR-C", null));

    ledger.eodInitClearing(new EodClearingInitCmd(total, "PDF-S1", null));
    ledger.eodReconcile(new EodReconcileCmd(total, mdr, "PDF-S2", null));
    ledger.eodRecognizeMdr(new EodRecognizeMdrCmd(mdr, "PDF-S3", null));
    ledger.eodSettleOutbound(new EodSettleOutboundCmd(net, napas, "PDF-S4", null));

    assertEquals(           0L, ledger.getBalance("2120"), "2120 = 0 ✓");
    assertEquals(-198_500L,     ledger.getBalance("1112"), "1112 = −198,500 ✓");
    assertEquals(  -2_000L,     ledger.getBalance("4140"), "4140 revenue = +2,000 ✓");
    assertEquals(    500L,      ledger.getBalance("5100"), "5100 expense = +500 ✓");
    assertEquals(0L, ledger.getBalance("3800"));
    assertEquals(0L, ledger.getBalance("3810"));
    assertEquals(0L, ledger.getBalance("3820"));

    long mdrRevenue   = -ledger.getBalance("4140");
    long napasExpense =  ledger.getBalance("5100");
    assertEquals(1_500L, mdrRevenue - napasExpense, "lãi thuần = 2,000 − 500 = 1,500 ✓");
    assertTrue(ledger.isDoubleEntryBalanced());
  }
}
