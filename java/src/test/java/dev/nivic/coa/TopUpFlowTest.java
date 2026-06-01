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
 * Integration tests for the nạp tiền fund flow:
 * receiveTopUp (DR 1111 / CR 3100) → confirmTopUp (DR 3100 / CR 2110 / CR 4110).
 */
@Testcontainers
@Tag("integration")
class TopUpFlowTest {

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

  // ── Step 1: receiveTopUp ──────────────────────────────────────────────────────

  @Test
  void receiveTopUp_postsCorrectJournal() {
    CoaTrans t = ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-001", null));

    assertNotNull(t.id());
    assertEquals("BANK-001", t.refId());
    assertTrue(t.isBalanced());
    assertEquals(2, t.lines().size());

    CoaTransLine dr = t.lines().stream().filter(CoaTransLine::isDebit).findFirst().orElseThrow();
    CoaTransLine cr = t.lines().stream().filter(CoaTransLine::isCredit).findFirst().orElseThrow();

    assertEquals("1111", dr.accountCode());
    assertEquals(100_000L, dr.debitMinor());
    assertEquals("TK Vietinbank Chuyên dùng", dr.accountName());

    assertEquals("3100", cr.accountCode());
    assertEquals(100_000L, cr.creditMinor());
    assertEquals("Transit - Nạp tiền", cr.accountName());
  }

  @Test
  void receiveTopUp_updatesAccountBalances() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-002", null));

    assertEquals( 100_000L, ledger.getBalance("1111")); // DR → positive
    assertEquals(-100_000L, ledger.getBalance("3100")); // CR → negative (credit-normal)
  }

  @Test
  void receiveTopUp_idempotent() {
    CoaTrans first  = ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-003", null));
    CoaTrans second = ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-003", null));

    assertEquals(first.id(), second.id(), "same bankRef must return same transaction");
    assertEquals(100_000L, ledger.getBalance("1111"), "no double-credit on retry");
  }

  // ── Step 2: confirmTopUp ─────────────────────────────────────────────────────

  @Test
  void confirmTopUp_postsThreeLines() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-004", null));
    CoaTrans t = ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "CONFIRM-004", null));

    assertTrue(t.isBalanced());
    assertEquals(3, t.lines().size());

    // DR 3100
    CoaTransLine dr3100 = t.lines().stream()
        .filter(l -> "3100".equals(l.accountCode()) && l.isDebit())
        .findFirst().orElseThrow();
    assertEquals(100_000L, dr3100.debitMinor());

    // CR 2110
    CoaTransLine cr2110 = t.lines().stream()
        .filter(l -> "2110".equals(l.accountCode()))
        .findFirst().orElseThrow();
    assertEquals(99_000L, cr2110.creditMinor());

    // CR 4110
    CoaTransLine cr4110 = t.lines().stream()
        .filter(l -> "4110".equals(l.accountCode()))
        .findFirst().orElseThrow();
    assertEquals(1_000L, cr4110.creditMinor());
  }

  @Test
  void confirmTopUp_updatesAllBalances() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-005", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "CONFIRM-005", null));

    assertEquals( 100_000L, ledger.getBalance("1111"), "bank unchanged");
    assertEquals(       0L, ledger.getBalance("3100"), "transit cleared");
    assertEquals( -99_000L, ledger.getBalance("2110"), "user wallet credited");
    assertEquals(  -1_000L, ledger.getBalance("4110"), "revenue recorded");
  }

  @Test
  void confirmTopUp_transitClearsToZero() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-006", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "CONFIRM-006", null));

    assertEquals(0L, ledger.getBalance("3100"), "Transit 3100 must be 0 after full flow");
  }

  @Test
  void confirmTopUp_idempotent() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-007", null));
    CoaTrans first  = ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "CONFIRM-007", null));
    CoaTrans second = ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "CONFIRM-007", null));

    assertEquals(first.id(), second.id());
    assertEquals(0L, ledger.getBalance("3100"), "no double-debit on retry");
  }

  @Test
  void confirmTopUp_insufficientTransit_throws() {
    // Không có receiveTopUp trước → transit = 0 → không đủ để release
    assertThrows(InsufficientTransitException.class,
        () -> ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "CONFIRM-X", null)));
  }

  // ── Full flow end-to-end ─────────────────────────────────────────────────────

  @Test
  void fullTopUpFlow_doubleEntryAlwaysBalanced() {
    assertTrue(ledger.isDoubleEntryBalanced(), "empty ledger balanced");

    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-E2E-1", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after receive");

    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "CONF-E2E-1", null));
    assertTrue(ledger.isDoubleEntryBalanced(), "after confirm");
  }

  @Test
  void fullTopUpFlow_multipleUsers_accumulatesCorrectly() {
    // User 1: nạp 100k, phí 1k
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-U1", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "CONF-U1", null));

    // User 2: nạp 200k, phí 1k
    ledger.receiveTopUp(new TopUpReceiveCmd(200_000L, "BANK-U2", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(200_000L, 1_000L, "CONF-U2", null));

    assertEquals( 300_000L, ledger.getBalance("1111"), "bank = tổng tiền nhận");
    assertEquals(       0L, ledger.getBalance("3100"), "transit cleared");
    assertEquals(-298_000L, ledger.getBalance("2110"), "user wallet = (99k + 199k)"); // (100k-1k) + (200k-1k)
    assertEquals(  -2_000L, ledger.getBalance("4110"), "doanh thu = 2 × 1k phí");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void fullTopUpFlow_zeroFee() {
    ledger.receiveTopUp(new TopUpReceiveCmd(50_000L, "BANK-NOFEE", null));
    ledger.confirmTopUp(new TopUpConfirmCmd(50_000L, 0L, "CONF-NOFEE", null));

    assertEquals( 50_000L, ledger.getBalance("1111"));
    assertEquals(      0L, ledger.getBalance("3100"));
    assertEquals(-50_000L, ledger.getBalance("2110"));
    assertEquals(      0L, ledger.getBalance("4110"), "zero fee = no revenue");
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── findTrans ─────────────────────────────────────────────────────────────────

  @Test
  void findTrans_receiveStep_hasAccountNames() {
    CoaTrans posted = ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "BANK-FIND", null));
    CoaTrans found  = ledger.findTrans(posted.id());

    assertNotNull(found);
    assertEquals(posted.id(), found.id());
    assertTrue(found.isBalanced());
    assertTrue(found.lines().stream().anyMatch(l -> "TK Vietinbank Chuyên dùng".equals(l.accountName())));
    assertTrue(found.lines().stream().anyMatch(l -> "Transit - Nạp tiền".equals(l.accountName())));
  }

  @Test
  void findTrans_unknown_returnsNull() {
    assertNull(ledger.findTrans(java.util.UUID.randomUUID()));
  }
}
