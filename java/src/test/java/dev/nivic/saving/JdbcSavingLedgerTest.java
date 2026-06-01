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
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Tag("integration")
class JdbcSavingLedgerTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static HikariDataSource ds;
  private JdbcSavingLedger ledger;

  private static final String VND = "VND";
  private static final long OWNER = 1001L;
  private static final long OTHER = 2002L;

  @BeforeAll
  static void initPool() {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(10);
    ds = new HikariDataSource(cfg);
  }

  @AfterAll
  static void closePool() {
    ds.close();
  }

  @BeforeEach
  void setUp() {
    ledger = new JdbcSavingLedger(ds);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      // transfers first (FK), then user accounts (system accounts stay)
      st.execute("TRUNCATE sav_transfer");
      st.execute("DELETE FROM sav_account WHERE owner_mid <> 0");
    }
  }

  // ── openAccount ─────────────────────────────────────────────────────────────

  @Test
  void openAccount_demand_fieldsCorrect() {
    SavAccount a = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));

    assertNotNull(a.id());
    assertEquals(OWNER, a.ownerMid());
    assertEquals(SavAccountKind.DEMAND, a.kind());
    assertEquals(VND, a.currencyCode());
    assertEquals(0L, a.availableBalance());
    assertFalse(a.isClosed());
    assertFalse(a.isTermLocked());
    assertFalse(a.isFrozen());
    assertNotNull(a.openedAt());
    assertNull(a.closedAt());
  }

  @Test
  void openAccount_term_setsTermLockedFlagAndInterestRate() {
    Instant maturity = Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    SavAccount a = ledger.openAccount(OpenAccountCmd.term(OWNER, VND, 650, maturity));

    assertEquals(SavAccountKind.TERM, a.kind());
    assertEquals(650, a.interestRateBps());
    assertTrue(a.isTermLocked());
    // DB timestamp truncated to microsecond; allow 1-second window
    assertTrue(Math.abs(a.maturityAt().getEpochSecond() - maturity.getEpochSecond()) <= 1);
  }

  // ── deposit ─────────────────────────────────────────────────────────────────

  @Test
  void deposit_incrementsCreditsPosted() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));

    SavTransfer t = ledger.deposit(deposit(acct.id(), 100_000L));

    assertEquals(SavTransferKind.DEPOSIT, t.kind());
    assertEquals(SavTransferPhase.POSTED, t.phase());
    assertEquals(100_000L, t.amountMinor());
    assertEquals(VND, t.currencyCode());
    assertNotNull(t.createdAt());

    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(100_000L, updated.creditsPosted());
    assertEquals(100_000L, updated.availableBalance());
    assertEquals(0L, updated.debitsPosted());
    assertEquals(0L, updated.debitsPending());
  }

  @Test
  void deposit_multipleDeposits_balanceAccumulates() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));

    ledger.deposit(deposit(acct.id(), 100_000L));
    ledger.deposit(deposit(acct.id(), 200_000L));
    ledger.deposit(deposit(acct.id(), 50_000L));

    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(350_000L, updated.availableBalance());
  }

  @Test
  void deposit_closedAccount_throwsAccountClosedException() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    setFlag(acct.id(), SavAccountFlags.CLOSED);

    assertThrows(AccountClosedException.class, () -> ledger.deposit(deposit(acct.id(), 1_000L)));
  }

  @Test
  void deposit_frozenAccount_throwsAccountFrozenException() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    setFlag(acct.id(), SavAccountFlags.FROZEN);

    assertThrows(AccountFrozenException.class, () -> ledger.deposit(deposit(acct.id(), 1_000L)));
  }

  @Test
  void deposit_idempotencyKey_noDuplicateCredit() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    UUID key = UUID.randomUUID();

    SavTransfer first  = ledger.deposit(deposit(acct.id(), 100_000L, key));
    SavTransfer second = ledger.deposit(deposit(acct.id(), 100_000L, key));

    assertEquals(first.id(), second.id(), "idempotent: same transfer returned");
    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(100_000L, updated.creditsPosted(), "no double credit");
  }

  // ── withdrawal (POSTED) ──────────────────────────────────────────────────────

  @Test
  void withdrawal_posted_decrementsAvailableBalance() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));

    SavTransfer t = ledger.withdrawal(withdrawal(acct.id(), 200_000L));

    assertEquals(SavTransferKind.WITHDRAWAL, t.kind());
    assertEquals(SavTransferPhase.POSTED, t.phase());
    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(300_000L, updated.availableBalance());
    assertEquals(200_000L, updated.debitsPosted());
    assertEquals(0L, updated.debitsPending());
  }

  @Test
  void withdrawal_exactBalance_succeeds() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 100_000L));

    assertDoesNotThrow(() -> ledger.withdrawal(withdrawal(acct.id(), 100_000L)));

    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(0L, updated.availableBalance());
  }

  @Test
  void withdrawal_insufficientFunds_throws() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 100_000L));

    assertThrows(
        InsufficientFundsException.class,
        () -> ledger.withdrawal(withdrawal(acct.id(), 100_001L)));

    // balance unchanged after failure
    assertEquals(100_000L, ledger.findAccount(acct.id()).availableBalance());
  }

  @Test
  void withdrawal_termLocked_throws() {
    Instant maturity = Instant.now().plus(365, ChronoUnit.DAYS);
    SavAccount acct = ledger.openAccount(OpenAccountCmd.term(OWNER, VND, 650, maturity));
    ledger.deposit(deposit(acct.id(), 500_000L));

    assertThrows(TermLockedException.class, () -> ledger.withdrawal(withdrawal(acct.id(), 100_000L)));
  }

  @Test
  void withdrawal_closedAccount_throws() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 100_000L));
    setFlag(acct.id(), SavAccountFlags.CLOSED);

    assertThrows(AccountClosedException.class, () -> ledger.withdrawal(withdrawal(acct.id(), 100_000L)));
  }

  @Test
  void withdrawal_idempotencyKey_noDuplicateDebit() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));
    UUID key = UUID.randomUUID();

    SavTransfer first  = ledger.withdrawal(withdrawal(acct.id(), 100_000L, key));
    SavTransfer second = ledger.withdrawal(withdrawal(acct.id(), 100_000L, key));

    assertEquals(first.id(), second.id());
    assertEquals(400_000L, ledger.findAccount(acct.id()).availableBalance(), "no double debit");
  }

  // ── two-phase withdrawal ─────────────────────────────────────────────────────

  @Test
  void withdrawal_pending_incrementsDebitsPending() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));

    SavTransfer t = ledger.withdrawal(pendingWithdrawal(acct.id(), 200_000L));

    assertEquals(SavTransferPhase.PENDING, t.phase());
    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(200_000L, updated.debitsPending());
    assertEquals(300_000L, updated.availableBalance(), "pending reduces available");
    assertEquals(0L, updated.debitsPosted());
  }

  @Test
  void postPending_movesDebitsPendingToDebitsPosted() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));
    SavTransfer pending = ledger.withdrawal(pendingWithdrawal(acct.id(), 200_000L));

    SavTransfer posted = ledger.postPending(pending.id());

    assertEquals(SavTransferPhase.POSTED, posted.phase());
    assertEquals(pending.id(), posted.pendingId());
    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(0L, updated.debitsPending());
    assertEquals(200_000L, updated.debitsPosted());
    assertEquals(300_000L, updated.availableBalance());
  }

  @Test
  void voidPending_reversesDebitsPending() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));
    SavTransfer pending = ledger.withdrawal(pendingWithdrawal(acct.id(), 200_000L));

    SavTransfer voided = ledger.voidPending(pending.id());

    assertEquals(SavTransferPhase.VOIDED, voided.phase());
    assertEquals(pending.id(), voided.pendingId());
    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(0L, updated.debitsPending());
    assertEquals(0L, updated.debitsPosted());
    assertEquals(500_000L, updated.availableBalance(), "full balance restored");
  }

  @Test
  void postPending_onNonPendingTransfer_throwsPhaseException() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));
    // A one-phase POSTED withdrawal is not PENDING
    SavTransfer posted = ledger.withdrawal(withdrawal(acct.id(), 100_000L));

    assertThrows(SavTransferPhaseException.class, () -> ledger.postPending(posted.id()));
  }

  @Test
  void voidPending_onNonPendingTransfer_throwsPhaseException() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));
    SavTransfer posted = ledger.withdrawal(withdrawal(acct.id(), 100_000L));

    assertThrows(SavTransferPhaseException.class, () -> ledger.voidPending(posted.id()));
  }

  @Test
  void postPending_alreadySettled_throwsPhaseException() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));
    SavTransfer pending = ledger.withdrawal(pendingWithdrawal(acct.id(), 200_000L));
    ledger.postPending(pending.id());

    assertThrows(SavTransferPhaseException.class, () -> ledger.postPending(pending.id()));
  }

  @Test
  void voidPending_alreadySettled_throwsPhaseException() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 500_000L));
    SavTransfer pending = ledger.withdrawal(pendingWithdrawal(acct.id(), 200_000L));
    ledger.voidPending(pending.id());

    assertThrows(SavTransferPhaseException.class, () -> ledger.voidPending(pending.id()));
  }

  @Test
  void pendingWithdrawal_countsAgainstAvailableForSubsequentWithdrawal() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 300_000L));
    ledger.withdrawal(pendingWithdrawal(acct.id(), 200_000L)); // holds 200k

    // Only 100k available now
    assertThrows(
        InsufficientFundsException.class,
        () -> ledger.withdrawal(withdrawal(acct.id(), 150_000L)));
    assertDoesNotThrow(() -> ledger.withdrawal(withdrawal(acct.id(), 100_000L)));
  }

  @Test
  void postPending_concurrent_onlyOneSucceeds() throws Exception {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 1_000_000L));
    SavTransfer pending = ledger.withdrawal(pendingWithdrawal(acct.id(), 500_000L));

    CyclicBarrier barrier = new CyclicBarrier(2);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger phaseErrors = new AtomicInteger();

    Runnable task = () -> {
      try { barrier.await(); } catch (Exception e) { return; }
      try {
        ledger.postPending(pending.id());
        successes.incrementAndGet();
      } catch (SavTransferPhaseException e) {
        phaseErrors.incrementAndGet();
      }
    };

    Thread t1 = new Thread(task);
    Thread t2 = new Thread(task);
    t1.start(); t2.start();
    t1.join(5_000); t2.join(5_000);

    assertEquals(1, successes.get(), "exactly one postPending must succeed");
    assertEquals(1, phaseErrors.get(), "exactly one must fail with SavTransferPhaseException");

    SavAccount final_ = ledger.findAccount(acct.id());
    assertEquals(0L, final_.debitsPending());
    assertEquals(500_000L, final_.debitsPosted());
  }

  // ── accrueInterest ───────────────────────────────────────────────────────────

  @Test
  void accrueInterest_creditsTargetAccount() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.term(OWNER, VND, 800,
        Instant.now().plus(365, ChronoUnit.DAYS)));
    ledger.deposit(deposit(acct.id(), 10_000_000L));
    long interestAmount = 8_000L; // pre-computed by caller

    List<SavTransfer> results = ledger.accrueInterest(
        List.of(new AccrueInterestCmd(acct.id(), interestAmount, VND, UUID.randomUUID())));

    assertEquals(1, results.size());
    SavTransfer t = results.get(0);
    assertEquals(SavTransferKind.INTEREST, t.kind());
    assertEquals(SavTransferPhase.POSTED, t.phase());
    assertEquals(interestAmount, t.amountMinor());

    SavAccount updated = ledger.findAccount(acct.id());
    assertEquals(10_008_000L, updated.creditsPosted());
    assertEquals(10_008_000L, updated.availableBalance());
  }

  @Test
  void accrueInterest_batch_allAccountsCredited() {
    SavAccount a1 = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    SavAccount a2 = ledger.openAccount(OpenAccountCmd.demand(OTHER, VND));
    ledger.deposit(deposit(a1.id(), 1_000_000L));
    ledger.deposit(deposit(a2.id(), 2_000_000L));

    UUID batchId = UUID.randomUUID();
    List<SavTransfer> results = ledger.accrueInterest(List.of(
        new AccrueInterestCmd(a1.id(), 1_000L, VND, batchId),
        new AccrueInterestCmd(a2.id(), 2_000L, VND, batchId)));

    assertEquals(2, results.size());
    assertEquals(batchId, results.get(0).linkedBatchId());
    assertEquals(batchId, results.get(1).linkedBatchId());
    assertEquals(1_001_000L, ledger.findAccount(a1.id()).creditsPosted());
    assertEquals(2_002_000L, ledger.findAccount(a2.id()).creditsPosted());
  }

  // ── closeAccount ─────────────────────────────────────────────────────────────

  @Test
  void closeAccount_zeroBalance_setsClosedFlag() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));

    SavAccount closed = ledger.closeAccount(acct.id(), OWNER);

    assertTrue(closed.isClosed());
    assertNotNull(closed.closedAt());
    SavAccount fromDb = ledger.findAccount(acct.id());
    assertTrue(fromDb.isClosed());
  }

  @Test
  void closeAccount_nonZeroBalance_throwsIllegalState() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 100_000L));

    assertThrows(IllegalStateException.class, () -> ledger.closeAccount(acct.id(), OWNER));
    assertFalse(ledger.findAccount(acct.id()).isClosed(), "account must remain open");
  }

  @Test
  void closeAccount_withPendingWithdrawal_throwsIllegalState() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.deposit(deposit(acct.id(), 100_000L));
    ledger.withdrawal(pendingWithdrawal(acct.id(), 100_000L)); // 0 available, but pending != 0

    assertThrows(IllegalStateException.class, () -> ledger.closeAccount(acct.id(), OWNER));
  }

  @Test
  void closeAccount_wrongMid_throwsIllegalState() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));

    assertThrows(IllegalStateException.class, () -> ledger.closeAccount(acct.id(), OTHER));
    assertFalse(ledger.findAccount(acct.id()).isClosed());
  }

  @Test
  void closeAccount_alreadyClosed_idempotent() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.closeAccount(acct.id(), OWNER);

    SavAccount second = ledger.closeAccount(acct.id(), OWNER);
    assertTrue(second.isClosed(), "still closed, no error");
  }

  @Test
  void deposit_afterClose_throwsAccountClosedException() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    ledger.closeAccount(acct.id(), OWNER);

    assertThrows(AccountClosedException.class, () -> ledger.deposit(deposit(acct.id(), 100_000L)));
  }

  // ── statement ────────────────────────────────────────────────────────────────

  @Test
  void statement_returnsTransfersNewestFirst() throws InterruptedException {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));

    ledger.deposit(deposit(acct.id(), 100_000L));
    Thread.sleep(10);
    ledger.deposit(deposit(acct.id(), 200_000L));
    Thread.sleep(10);
    ledger.deposit(deposit(acct.id(), 300_000L));

    List<SavTransfer> stmt = ledger.statement(acct.id(), future(), 10);

    assertEquals(3, stmt.size());
    assertEquals(300_000L, stmt.get(0).amountMinor(), "newest first");
    assertEquals(200_000L, stmt.get(1).amountMinor());
    assertEquals(100_000L, stmt.get(2).amountMinor());
  }

  @Test
  void statement_limit_caps_results() throws InterruptedException {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    for (int i = 0; i < 5; i++) {
      ledger.deposit(deposit(acct.id(), 10_000L));
      Thread.sleep(5);
    }

    List<SavTransfer> stmt = ledger.statement(acct.id(), future(), 3);
    assertEquals(3, stmt.size());
  }

  @Test
  void statement_cursor_paginates() throws InterruptedException {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));

    ledger.deposit(deposit(acct.id(), 100_000L));
    Thread.sleep(10);
    ledger.deposit(deposit(acct.id(), 200_000L));
    Thread.sleep(10);
    ledger.deposit(deposit(acct.id(), 300_000L));

    List<SavTransfer> page1 = ledger.statement(acct.id(), future(), 2);
    assertEquals(2, page1.size());
    assertEquals(300_000L, page1.get(0).amountMinor());
    assertEquals(200_000L, page1.get(1).amountMinor());

    // cursor = createdAt of oldest entry on page 1
    List<SavTransfer> page2 = ledger.statement(acct.id(), page1.get(1).createdAt(), 2);
    assertEquals(1, page2.size());
    assertEquals(100_000L, page2.get(0).amountMinor());
  }

  @Test
  void statement_includesDebitAndCreditSide() {
    SavAccount src = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));
    SavAccount dst = ledger.openAccount(OpenAccountCmd.demand(OTHER, VND));
    ledger.deposit(deposit(src.id(), 500_000L));
    ledger.deposit(deposit(dst.id(), 500_000L));
    // interest accrual: reserve → src
    ledger.accrueInterest(List.of(new AccrueInterestCmd(src.id(), 1_000L, VND, null)));

    // src sees both its deposit and the interest credit
    List<SavTransfer> srcStmt = ledger.statement(src.id(), future(), 10);
    assertTrue(srcStmt.stream().anyMatch(t -> t.kind() == SavTransferKind.DEPOSIT));
    assertTrue(srcStmt.stream().anyMatch(t -> t.kind() == SavTransferKind.INTEREST));
  }

  // ── findAccount ──────────────────────────────────────────────────────────────

  @Test
  void findAccount_unknown_returnsNull() {
    assertNull(ledger.findAccount(UUID.randomUUID()));
  }

  // ── balance invariants ────────────────────────────────────────────────────────

  @Test
  void balanceInvariant_depositWithdrawPendingPostSequence() {
    SavAccount acct = ledger.openAccount(OpenAccountCmd.demand(OWNER, VND));

    ledger.deposit(deposit(acct.id(), 1_000_000L));        // credits_posted = 1M
    ledger.withdrawal(withdrawal(acct.id(), 200_000L));     // debits_posted = 200k
    SavTransfer p = ledger.withdrawal(pendingWithdrawal(acct.id(), 300_000L)); // debits_pending = 300k
    ledger.postPending(p.id());                             // debits_pending=0, debits_posted=500k

    SavAccount final_ = ledger.findAccount(acct.id());
    assertEquals(1_000_000L, final_.creditsPosted());
    assertEquals(500_000L,   final_.debitsPosted());
    assertEquals(0L,         final_.debitsPending());
    assertEquals(500_000L,   final_.availableBalance());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private DepositCmd deposit(UUID accountId, long amount) {
    return new DepositCmd(accountId, amount, VND, null, null, null, null);
  }

  private DepositCmd deposit(UUID accountId, long amount, UUID idempotencyKey) {
    return new DepositCmd(accountId, amount, VND, idempotencyKey, null, null, null);
  }

  private WithdrawalCmd withdrawal(UUID accountId, long amount) {
    return new WithdrawalCmd(accountId, amount, VND, false, null, null, null, null);
  }

  private WithdrawalCmd withdrawal(UUID accountId, long amount, UUID idempotencyKey) {
    return new WithdrawalCmd(accountId, amount, VND, false, idempotencyKey, null, null, null);
  }

  private WithdrawalCmd pendingWithdrawal(UUID accountId, long amount) {
    return new WithdrawalCmd(accountId, amount, VND, true, null, null, null, null);
  }

  /** Cursor for the first statement page: a few seconds in the future to absorb host↔container clock skew. */
  private static Instant future() {
    return Instant.now().plusSeconds(5);
  }

  /** Directly sets an account flag in the DB (for test setup only). */
  private void setFlag(UUID accountId, int flag) {
    try (Connection c = ds.getConnection()) {
      try (var ps = c.prepareStatement(
          "UPDATE sav_account SET flags = flags | ? WHERE id = ?")) {
        ps.setInt(1, flag);
        ps.setObject(2, accountId);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }
}
