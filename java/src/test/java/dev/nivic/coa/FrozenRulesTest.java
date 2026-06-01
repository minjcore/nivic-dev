package dev.nivic.coa;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.coa.cmd.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Frozen rules ở tầng DB:
 * 1) Append-only — cấm UPDATE/DELETE coa_trans / coa_trans_data.
 * 2) Deferred double-entry — Σdebit = Σcredit per trans_id, ép tại COMMIT.
 */
@Testcontainers
@Tag("integration")
class FrozenRulesTest {

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
  void setUp() {
    ledger = new JdbcFundFlowLedger(ds);
    ledger.isDoubleEntryBalanced(); // ensure schema + triggers
  }

  @AfterEach
  void cleanUp() throws SQLException {
    try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE"); // TRUNCATE bypasses row triggers
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
    }
  }

  // ── Append-only ───────────────────────────────────────────────────────────────

  @Test
  void updateJournalHeader_forbidden() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "FR-1", null));
    SQLException ex = assertThrows(SQLException.class, () -> {
      try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
        st.execute("UPDATE coa_trans SET memo = 'tampered' WHERE ref_id = 'FR-1'");
      }
    });
    assertTrue(String.valueOf(ex.getMessage()).contains("append-only"), ex.getMessage());
  }

  @Test
  void deleteJournalHeader_forbidden() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "FR-2", null));
    assertThrows(SQLException.class, () -> {
      try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
        st.execute("DELETE FROM coa_trans WHERE ref_id = 'FR-2'");
      }
    });
  }

  @Test
  void updateJournalLine_forbidden() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "FR-3", null));
    SQLException ex = assertThrows(SQLException.class, () -> {
      try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
        st.execute("UPDATE coa_trans_data SET debit_minor = 999999");
      }
    });
    assertTrue(String.valueOf(ex.getMessage()).contains("append-only"));
  }

  @Test
  void deleteJournalLine_forbidden() {
    ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "FR-4", null));
    assertThrows(SQLException.class, () -> {
      try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
        st.execute("DELETE FROM coa_trans_data");
      }
    });
  }

  @Test
  void insertNewJournal_stillAllowed() {
    // Append-only chỉ chặn sửa/xoá; ghi mới (qua ledger) vẫn bình thường.
    assertDoesNotThrow(() -> {
      ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, "FR-5", null));
      ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, "FR-5C", null));
    });
    assertTrue(ledger.isDoubleEntryBalanced());
  }

  // ── Deferred double-entry ──────────────────────────────────────────────────────

  @Test
  void unbalancedJournal_rejectedAtCommit() throws SQLException {
    // Chèn trực tiếp header + 1 dòng lệch (DR 100 không có CR) → COMMIT phải fail.
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      UUID id;
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO coa_trans (ref_id, memo) VALUES ('FR-UNBAL', 'x') RETURNING id")) {
        try (ResultSet rs = ps.executeQuery()) { rs.next(); id = rs.getObject(1, UUID.class); }
      }
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO coa_trans_data (trans_id, line_no, account_code, debit_minor, credit_minor)"
              + " VALUES (?, 1, '1111', 100, 0)")) {
        ps.setObject(1, id);
        ps.executeUpdate(); // chưa fire (deferred)
      }
      SQLException ex = assertThrows(SQLException.class, c::commit, "commit phải bị deferred trigger chặn");
      assertEquals("23514", ex.getSQLState());
      c.rollback();
    }
  }

  @Test
  void balancedJournal_commitsFine() throws SQLException {
    // DR 100 / CR 100 → cân → commit OK (kể cả chèn tay).
    try (Connection c = ds.getConnection()) {
      c.setAutoCommit(false);
      UUID id;
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO coa_trans (ref_id, memo) VALUES ('FR-BAL', 'x') RETURNING id")) {
        try (ResultSet rs = ps.executeQuery()) { rs.next(); id = rs.getObject(1, UUID.class); }
      }
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO coa_trans_data (trans_id, line_no, account_code, debit_minor, credit_minor)"
              + " VALUES (?, 1, '1111', 100, 0), (?, 2, '3100', 0, 100)")) {
        ps.setObject(1, id); ps.setObject(2, id);
        ps.executeUpdate();
      }
      assertDoesNotThrow(c::commit);
    }
  }

  @Test
  void allLedgerFlows_passDeferredCheck() {
    // Mọi nghiệp vụ qua ledger luôn cân → không bị deferred trigger chặn.
    assertDoesNotThrow(() -> {
      ledger.receiveTopUp(new TopUpReceiveCmd(1_000_000L, "FR-F-R", null));
      ledger.confirmTopUp(new TopUpConfirmCmd(1_000_000L, 1_000L, "FR-F-C", null, 1L));
      ledger.initIbftTransfer(new IbftInitCmd(40_000L, 1_000L, "FR-F-I", null, 1L));
      ledger.settleIbftTransfer(new IbftSettleCmd(40_000L, 1_000L, 500L, "FR-F-S", null));
      // Reverse một giao dịch hoàn chỉnh, tự cân (topup receive chưa confirm).
      ledger.receiveTopUp(new TopUpReceiveCmd(50_000L, "FR-F-R2", null));
      ledger.reverse(new ReversalCmd("FR-F-R2", "FR-F-REV", null));
    });
    assertTrue(ledger.isDoubleEntryBalanced());
  }
}
