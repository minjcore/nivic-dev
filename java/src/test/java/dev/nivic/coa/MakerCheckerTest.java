package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.cmd.*;
import dev.nivic.coa.error.ProposalNotFoundException;
import dev.nivic.coa.error.ProposalStateException;
import dev.nivic.coa.error.SegregationOfDutiesException;
import dev.nivic.coa.mc.Proposal;
import dev.nivic.coa.mc.ProposalStatus;
import dev.nivic.coa.mc.ProposeJournalCmd;
import dev.nivic.coa.mc.ProposeJournalCmd.EntryLine;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
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
 * Maker-checker (4-eyes): maker đề xuất bút toán (chưa đụng số dư), checker (≠ maker) duyệt
 * mới post vào sổ cái, hoặc từ chối.
 */
@Testcontainers
@Tag("integration")
class MakerCheckerTest {

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
      st.execute("TRUNCATE coa_proposal_line, coa_proposal CASCADE");
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  /** Đề xuất nạp vốn: DR 1111 / CR 6000 (đều hợp lệ sign). */
  private ProposeJournalCmd capitalProposal(String maker, String ref, long amount) {
    return new ProposeJournalCmd(maker, ref, "Nạp vốn " + amount, List.of(
        new EntryLine("1111", amount, 0L),
        new EntryLine("6000", 0L, amount)));
  }

  // ── Propose ────────────────────────────────────────────────────────────────────

  @Test
  void propose_storesPending_noBalanceChange() {
    Proposal p = ledger.propose(capitalProposal("alice", "MC-1", 1_000_000L));

    assertEquals(ProposalStatus.PENDING, p.status());
    assertEquals("alice", p.makerId());
    assertTrue(p.isBalanced());
    assertEquals(2, p.lines().size());
    // Số dư CHƯA đổi (chưa duyệt)
    assertEquals(0L, ledger.getBalance("1111"));
    assertEquals(0L, ledger.getBalance("6000"));
  }

  @Test
  void propose_unbalanced_rejected() {
    assertThrows(IllegalArgumentException.class, () ->
        ledger.propose(new ProposeJournalCmd("alice", "MC-UB", null, List.of(
            new EntryLine("1111", 100L, 0L),
            new EntryLine("6000", 0L, 99L)))));
  }

  @Test
  void propose_idempotent() {
    Proposal first = ledger.propose(capitalProposal("alice", "MC-IDEM", 500_000L));
    Proposal second = ledger.propose(capitalProposal("alice", "MC-IDEM", 500_000L));
    assertEquals(first.id(), second.id());
    assertEquals(1, ledger.pendingProposals().size());
  }

  // ── Approve ────────────────────────────────────────────────────────────────────

  @Test
  void approve_postsToLedger() {
    Proposal p = ledger.propose(capitalProposal("alice", "MC-A1", 1_000_000L));
    CoaTrans posted = ledger.approve(p.id(), "bob");

    assertTrue(posted.isBalanced());
    assertEquals(1_000_000L, ledger.getBalance("1111"), "số dư cập nhật sau duyệt");
    assertEquals(-1_000_000L, ledger.getBalance("6000"));

    Proposal after = ledger.findProposal(p.id());
    assertEquals(ProposalStatus.APPROVED, after.status());
    assertEquals("bob", after.checkerId());
    assertEquals(posted.id(), after.postedTransId());
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  @Test
  void approve_bySameMaker_segregationViolation() {
    Proposal p = ledger.propose(capitalProposal("alice", "MC-SOD", 500_000L));
    assertThrows(SegregationOfDutiesException.class, () -> ledger.approve(p.id(), "alice"));
    // Không post — số dư vẫn 0
    assertEquals(0L, ledger.getBalance("1111"));
    assertEquals(ProposalStatus.PENDING, ledger.findProposal(p.id()).status());
  }

  @Test
  void approve_twice_stateException() {
    Proposal p = ledger.propose(capitalProposal("alice", "MC-2X", 500_000L));
    ledger.approve(p.id(), "bob");
    assertThrows(ProposalStateException.class, () -> ledger.approve(p.id(), "bob"));
    // Không double-post
    assertEquals(500_000L, ledger.getBalance("1111"));
  }

  @Test
  void approve_unknownProposal_notFound() {
    assertThrows(ProposalNotFoundException.class, () -> ledger.approve(999999999L, "bob"));
  }

  // ── Reject ─────────────────────────────────────────────────────────────────────

  @Test
  void reject_marksRejected_noPost() {
    Proposal p = ledger.propose(capitalProposal("alice", "MC-R1", 500_000L));
    Proposal rejected = ledger.reject(p.id(), "bob", "thiếu chứng từ");

    assertEquals(ProposalStatus.REJECTED, rejected.status());
    assertEquals("bob", rejected.checkerId());
    assertEquals("thiếu chứng từ", rejected.reason());
    assertNull(rejected.postedTransId());
    assertEquals(0L, ledger.getBalance("1111"), "không post");
  }

  @Test
  void reject_bySameMaker_segregationViolation() {
    Proposal p = ledger.propose(capitalProposal("alice", "MC-RSOD", 500_000L));
    assertThrows(SegregationOfDutiesException.class, () -> ledger.reject(p.id(), "alice", "x"));
  }

  @Test
  void reject_thenApprove_stateException() {
    Proposal p = ledger.propose(capitalProposal("alice", "MC-RA", 500_000L));
    ledger.reject(p.id(), "bob", "no");
    assertThrows(ProposalStateException.class, () -> ledger.approve(p.id(), "carol"));
  }

  // ── Pending list ─────────────────────────────────────────────────────────────

  @Test
  void pendingProposals_listsOnlyPending() {
    Proposal p1 = ledger.propose(capitalProposal("alice", "MC-P1", 100_000L));
    Proposal p2 = ledger.propose(capitalProposal("alice", "MC-P2", 200_000L));
    Proposal p3 = ledger.propose(capitalProposal("alice", "MC-P3", 300_000L));
    ledger.approve(p1.id(), "bob");
    ledger.reject(p2.id(), "bob", "x");

    List<Proposal> pending = ledger.pendingProposals();
    assertEquals(1, pending.size());
    assertEquals(p3.id(), pending.get(0).id());
  }

  // ── Approve validates balances at post time (insufficient funds) ──────────────

  @Test
  void approve_insufficientFunds_atPostTime() {
    // Đề xuất rút từ ví user (DR 2110) khi ví trống → duyệt sẽ vi phạm CHECK (2110 > 0).
    Proposal p = ledger.propose(new ProposeJournalCmd("alice", "MC-OD", "rút quá", List.of(
        new EntryLine("2110", 100_000L, 0L, 1L),   // DR ví user (đẩy dương → vi phạm)
        new EntryLine("3200", 0L, 100_000L))));     // CR transit
    // Duyệt → NegativeBalanceException (frozen rule chống âm), proposal vẫn PENDING (rollback)
    assertThrows(dev.nivic.coa.error.NegativeBalanceException.class, () -> ledger.approve(p.id(), "bob"));
    assertEquals(ProposalStatus.PENDING, ledger.findProposal(p.id()).status(), "rollback giữ PENDING");
    assertEquals(0L, ledger.getBalance("2110"));
  }
}
