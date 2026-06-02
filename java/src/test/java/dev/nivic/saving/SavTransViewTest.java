package dev.nivic.saving;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Tests for {@link SavingLedger#findTransfer(long)}: full bút toán view with account details.
 */
@Testcontainers
@Tag("integration")
class SavTransViewTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcSavingLedger ledger;

  private static final String VND  = "VND";
  private static final long OWNER  = 9001L;

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
  void setUp() { ledger = new JdbcSavingLedger(ds); }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE sav_trans_data, sav_trans CASCADE");
      st.execute("DELETE FROM sav_account WHERE owner_mid <> 0");
    }
  }

  // ── findTransfer — not found ──────────────────────────────────────────────

  @Test
  void findTransfer_unknown_returnsNull() {
    assertNull(ledger.findTransfer(999999999L));
  }

  // ── DEPOSIT — hai legs cân bằng ───────────────────────────────────────────

  @Test
  void findTransfer_deposit_twoBalancedLines() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    SavTransfer t   = ledger.deposit(new DepositCmd(acct.id(), 500_000L, VND, null, null, null, null));

    SavTransView view = ledger.findTransfer(t.id());

    assertNotNull(view);
    assertEquals(t.id(),                 view.id());
    assertEquals(SavTransferKind.DEPOSIT, view.kind());
    assertEquals(SavTransferPhase.POSTED, view.phase());

    // Hai dòng bút toán
    assertEquals(2, view.lines().size());

    // Tổng cân bằng
    assertTrue(view.isBalanced(), "debitTotal must equal creditTotal");
    assertEquals(500_000L, view.debitTotal());
    assertEquals(500_000L, view.creditTotal());
  }

  @Test
  void findTransfer_deposit_debitLineSide() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    SavTransfer t   = ledger.deposit(new DepositCmd(acct.id(), 500_000L, VND, null, null, null, null));

    SavTransView view = ledger.findTransfer(t.id());

    SavTransLineView debitLine = view.lines().stream()
        .filter(SavTransLineView::isDebit)
        .findFirst()
        .orElseThrow();

    // Debit side = SAV-EXTERNAL (system account)
    assertEquals("SAV-EXTERNAL",          debitLine.accountNo());
    assertEquals(SavAccountKind.RESERVE,  debitLine.accountKind());
    assertEquals(500_000L,                debitLine.debitMinor());
    assertEquals(0L,                      debitLine.creditMinor());
    assertEquals(VND,                     debitLine.currencyCode());
    assertEquals(1,                       debitLine.lineNo());
  }

  @Test
  void findTransfer_deposit_creditLineSide() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    SavTransfer t   = ledger.deposit(new DepositCmd(acct.id(), 500_000L, VND, null, null, null, null));

    SavTransView view = ledger.findTransfer(t.id());

    SavTransLineView creditLine = view.lines().stream()
        .filter(SavTransLineView::isCredit)
        .findFirst()
        .orElseThrow();

    // Credit side = user savings account
    assertEquals(acct.accountNo(),        creditLine.accountNo());
    assertEquals(SavAccountKind.DEMAND,   creditLine.accountKind());
    assertEquals(0L,                      creditLine.debitMinor());
    assertEquals(500_000L,                creditLine.creditMinor());
    assertEquals(2,                       creditLine.lineNo());
  }

  // ── WITHDRAWAL ────────────────────────────────────────────────────────────

  @Test
  void findTransfer_withdrawal_debitIsSavingsAccount() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(new DepositCmd(acct.id(), 500_000L, VND, null, null, null, null));
    SavTransfer t = ledger.withdrawal(new WithdrawalCmd(acct.id(), 200_000L, VND, false, null, null, null, null));

    SavTransView view = ledger.findTransfer(t.id());

    assertTrue(view.isBalanced());
    assertEquals(200_000L, view.debitTotal());

    SavTransLineView debitLine = view.lines().stream().filter(SavTransLineView::isDebit).findFirst().orElseThrow();
    assertEquals(acct.accountNo(), debitLine.accountNo(), "savings account debited on withdrawal");
    assertEquals(SavAccountKind.DEMAND, debitLine.accountKind());

    SavTransLineView creditLine = view.lines().stream().filter(SavTransLineView::isCredit).findFirst().orElseThrow();
    assertEquals("SAV-EXTERNAL", creditLine.accountNo(), "external account credited on withdrawal");
  }

  // ── INTEREST ─────────────────────────────────────────────────────────────

  @Test
  void findTransfer_interest_reserveDebitsSavingsCredits() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.term(OWNER, VND, 650,
        Instant.now().plus(365, ChronoUnit.DAYS)));
    ledger.deposit(new DepositCmd(acct.id(), 10_000_000L, VND, null, null, null, null));

    long interest = SavInterestCalc.compute(10_000_000L, 650, 30);
    List<SavTransfer> results = ledger.accrueInterest(
        List.of(new AccrueInterestCmd(acct.id(), interest, VND, null)));

    SavTransView view = ledger.findTransfer(results.get(0).id());

    assertEquals(SavTransferKind.INTEREST, view.kind());
    assertTrue(view.isBalanced());

    SavTransLineView debitLine  = view.lines().stream().filter(SavTransLineView::isDebit).findFirst().orElseThrow();
    SavTransLineView creditLine = view.lines().stream().filter(SavTransLineView::isCredit).findFirst().orElseThrow();

    assertEquals("SAV-RESERVE",        debitLine.accountNo(),   "reserve account is debited for interest");
    assertEquals(acct.accountNo(),     creditLine.accountNo(),  "savings account is credited");
    assertEquals(interest,             view.debitTotal());
  }

  // ── Two-phase PENDING → POSTED ────────────────────────────────────────────

  @Test
  void findTransfer_pendingWithdrawal_phaseIsPending() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(new DepositCmd(acct.id(), 500_000L, VND, null, null, null, null));
    SavTransfer pending = ledger.withdrawal(new WithdrawalCmd(acct.id(), 200_000L, VND, true, null, null, null, null));

    SavTransView view = ledger.findTransfer(pending.id());

    assertEquals(SavTransferPhase.PENDING, view.phase());
    assertTrue(view.isBalanced(), "PENDING transfer must still be balanced");
    assertNull(view.pendingId(),  "PENDING has no pending_id reference");
  }

  @Test
  void findTransfer_postedSettlement_linksToPending() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(new DepositCmd(acct.id(), 500_000L, VND, null, null, null, null));
    SavTransfer pending = ledger.withdrawal(new WithdrawalCmd(acct.id(), 200_000L, VND, true, null, null, null, null));
    SavTransfer posted  = ledger.postPending(pending.id());

    SavTransView postedView  = ledger.findTransfer(posted.id());
    SavTransView pendingView = ledger.findTransfer(pending.id());

    assertEquals(SavTransferPhase.POSTED, postedView.phase());
    assertEquals(pending.id(),            postedView.pendingId(), "POSTED row must reference its PENDING");

    // Cả PENDING lẫn POSTED đều balanced
    assertTrue(postedView.isBalanced());
    assertTrue(pendingView.isBalanced());

    // Cùng số tiền
    assertEquals(pendingView.debitTotal(), postedView.debitTotal());
  }

  @Test
  void findTransfer_voidedSettlement_linksToPending() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(new DepositCmd(acct.id(), 500_000L, VND, null, null, null, null));
    SavTransfer pending = ledger.withdrawal(new WithdrawalCmd(acct.id(), 200_000L, VND, true, null, null, null, null));
    SavTransfer voided  = ledger.voidPending(pending.id());

    SavTransView view = ledger.findTransfer(voided.id());

    assertEquals(SavTransferPhase.VOIDED, view.phase());
    assertEquals(pending.id(), view.pendingId());
    assertTrue(view.isBalanced());
  }

  // ── isBalanced invariant ──────────────────────────────────────────────────

  @Test
  void findTransfer_allKinds_alwaysBalanced() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(new DepositCmd(acct.id(), 1_000_000L, VND, null, null, null, null));
    SavTransfer w = ledger.withdrawal(new WithdrawalCmd(acct.id(), 300_000L, VND, false, null, null, null, null));

    long interest = SavInterestCalc.compute(700_000L, 650, 30);
    List<SavTransfer> ir = ledger.accrueInterest(List.of(new AccrueInterestCmd(acct.id(), interest, VND, null)));

    for (long id : List.of(w.id(), ir.get(0).id())) {
      SavTransView view = ledger.findTransfer(id);
      assertTrue(view.isBalanced(), "transfer " + id + " must be balanced: debit=" + view.debitTotal() + " credit=" + view.creditTotal());
    }
  }
}
