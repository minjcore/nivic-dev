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
 * Integration tests for reversal / hoàn tiền: post a compensating journal (debit↔credit swapped)
 * for any previously posted transaction, preserving the original (audit trail).
 */
@Testcontainers
@Tag("integration")
class ReversalFlowTest {

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
  void setUp() { ledger = new JdbcFundFlowLedger(ds); }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Basic reversal ────────────────────────────────────────────────────────────

  @Test
  void reverse_topupReceive_swapsDebitCredit() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "ORIG-1", null));
    // ORIG-1: DR 1111 100k / CR 3100 100k
    assertEquals( 100_000L, ledger.getBalance("1111"));
    assertEquals(-100_000L, ledger.getBalance("3100"));

    CoaTrans rev = ledger.reverse(new ReversalCmd("ORIG-1", "REV-1", null));

    assertTrue(rev.isBalanced());
    assertEquals(2, rev.lines().size());
    // Inverse: CR 1111 / DR 3100
    assertEquals(100_000L, rev.lines().stream()
        .filter(l -> "3100".equals(l.accountCode())).findFirst().orElseThrow().debitMinor());
    assertEquals(100_000L, rev.lines().stream()
        .filter(l -> "1111".equals(l.accountCode())).findFirst().orElseThrow().creditMinor());

    // Balances back to zero
    assertEquals(0L, ledger.getBalance("1111"), "1111 reversed to 0");
    assertEquals(0L, ledger.getBalance("3100"), "3100 reversed to 0");
  }

  @Test
  void reverse_multiLegTransaction_allLegsInverted() {
    // confirmTopUp posts a 3-leg journal: DR 3100 / CR 2110 / CR 4110
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "MULTI-R", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "MULTI-C", null));
    // After: 1111 +100k | 3100 0 | 2110 -99k | 4110 -1k

    ledger.reverse(new ReversalCmd("MULTI-C", "REV-MULTI-C", null));

    // confirmTopUp reversed: 3100 back to -100k, 2110 to 0, 4110 to 0
    assertEquals(-100_000L, ledger.getBalance("3100"), "3100 restored");
    assertEquals(       0L, ledger.getBalance("2110"), "user wallet credit reversed");
    assertEquals(       0L, ledger.getBalance("4110"), "revenue reversed");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void reverse_preservesOriginalTransaction() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "KEEP-1", null));
    ledger.reverse(new ReversalCmd("KEEP-1", "REV-KEEP-1", null));

    // Original still queryable
    CoaTrans original = ledger.findTransByRefId("KEEP-1");
    assertNotNull(original, "original transaction preserved");
    assertEquals(2, original.lines().size());

    CoaTrans reversal = ledger.findTransByRefId("REV-KEEP-1");
    assertNotNull(reversal, "reversal transaction recorded");
  }

  // ── Refund a payment (end-to-end) ──────────────────────────────────────────────

  @Test
  void reverse_walletPaymentSettle_refundsMerchant() {
    // Seed user, then wallet payment
    ledger.receiveTopUp(new TopUpReceiveCmd(500_000L, "RF-RECV", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(500_000L, 0L, "RF-CONF", null));
    ledger.initWalletPayment(new WalletPaymentInitCmd(100_000L, "RF-PAY-I", null));
    ledger.settleWalletPayment(new WalletPaymentSettleCmd(100_000L, "RF-PAY-S", null));
    // 2110 -400k | 2120 -100k | 3500 0

    // Hoàn tiền: reverse cả 2 bước (settle trước, rồi init)
    ledger.reverse(new ReversalCmd("RF-PAY-S", "RF-REV-S", null));
    ledger.reverse(new ReversalCmd("RF-PAY-I", "RF-REV-I", null));

    assertEquals(-500_000L, ledger.getBalance("2110"), "user wallet fully restored");
    assertEquals(       0L, ledger.getBalance("2120"), "merchant credit reversed");
    assertEquals(       0L, ledger.getBalance("3500"), "transit balanced");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── Idempotency & guards ───────────────────────────────────────────────────────

  @Test
  void reverse_idempotent_sameReversalRef() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "IDEM-1", null));
    CoaTrans first  = ledger.reverse(new ReversalCmd("IDEM-1", "REV-IDEM-1", null));
    CoaTrans second = ledger.reverse(new ReversalCmd("IDEM-1", "REV-IDEM-1", null));

    assertEquals(first.id(), second.id(), "same reversalRef returns same transaction");
    assertEquals(0L, ledger.getBalance("1111"), "no double reversal");
  }

  @Test
  void reverse_alreadyReversed_differentRef_throws() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "DBL-1", null));
    ledger.reverse(new ReversalCmd("DBL-1", "REV-DBL-1", null));

    // Second reversal with a different ref must be rejected
    assertThrows(AlreadyReversedException.class,
        () -> ledger.reverse(new ReversalCmd("DBL-1", "REV-DBL-2", null)));
  }

  @Test
  void reverse_unknownOriginal_throws() {
    assertThrows(TransactionNotFoundException.class,
        () -> ledger.reverse(new ReversalCmd("NONEXISTENT", "REV-X", null)));
  }

  @Test
  void reverse_sameRefForOriginalAndReversal_rejected() {
    assertThrows(IllegalArgumentException.class,
        () -> new ReversalCmd("SAME", "SAME", null));
  }

  // ── Double-entry invariant after reversals ──────────────────────────────────────

  @Test
  void reverse_keepsDoubleEntryBalanced() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BAL-R", null));
    assertTrue(ledger.isDoubleEntryBalanced());

    ledger.reverse(new ReversalCmd("BAL-R", "BAL-REV", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "still balanced after reversal");
  }

  @Test
  void reverse_reversalItselfCanBeReversed() {
    // Reverse a reversal = re-apply the original effect
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "RR-1", null));
    ledger.reverse(new ReversalCmd("RR-1", "RR-REV-1", null));
    assertEquals(0L, ledger.getBalance("1111"), "reversed to 0");

    // Reverse the reversal → original effect restored
    ledger.reverse(new ReversalCmd("RR-REV-1", "RR-REV-2", null));
    assertEquals(100_000L, ledger.getBalance("1111"), "re-applied original");
    assertTrue(ledger.isDoubleEntryBalanced());
  }
}
