package dev.nivic.saving;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL-backed {@link SavingLedger}.
 *
 * <p>Storage is split across two tables:
 * <ul>
 *   <li>{@code sav_trans} — transfer header: kind, phase, pending link, idempotency key, meta.</li>
 *   <li>{@code sav_trans_data} — bút toán lines: (debit side, credit side) keyed by
 *       {@code (trans_id, line_no)}.</li>
 * </ul>
 *
 * <p>Balance invariant (credit-normal account):
 * {@code available = credits_posted - debits_posted - debits_pending}.</p>
 *
 * <p>Two system accounts (SAV-EXTERNAL, SAV-RESERVE) are created at startup. Transfer rows are
 * immutable; POSTED/VOIDED settlements are new rows referencing the PENDING row via
 * {@code pending_id}. The unique index {@code sav_trans_settled_uidx(pending_id)} enforces
 * at-most-one settlement per PENDING.</p>
 */
public final class JdbcSavingLedger implements SavingLedger {

  // ── DDL ────────────────────────────────────────────────────────────────────

  private static final String DDL_ACCOUNT =
      """
      CREATE TABLE IF NOT EXISTS sav_account (
        id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        owner_mid         BIGINT      NOT NULL,
        account_no        VARCHAR(32) NOT NULL UNIQUE,
        kind              VARCHAR(16) NOT NULL,
        currency_code     VARCHAR(3)  NOT NULL DEFAULT 'VND',
        debits_pending    BIGINT      NOT NULL DEFAULT 0,
        debits_posted     BIGINT      NOT NULL DEFAULT 0,
        credits_pending   BIGINT      NOT NULL DEFAULT 0,
        credits_posted    BIGINT      NOT NULL DEFAULT 0,
        flags             INTEGER     NOT NULL DEFAULT 0,
        interest_rate_bps INTEGER,
        maturity_at       TIMESTAMPTZ,
        opened_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        closed_at         TIMESTAMPTZ,
        version           BIGINT      NOT NULL DEFAULT 0
      )
      """;

  private static final String DDL_TRANS =
      """
      CREATE TABLE IF NOT EXISTS sav_trans (
        id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        kind              VARCHAR(24) NOT NULL,
        phase             VARCHAR(16) NOT NULL DEFAULT 'POSTED',
        pending_id        UUID        REFERENCES sav_trans(id),
        idempotency_key   UUID        UNIQUE,
        ref_mid           BIGINT,
        ref_request_id    BIGINT,
        linked_batch_id   UUID,
        memo              VARCHAR(256),
        created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
      """;

  private static final String DDL_TRANS_DATA =
      """
      CREATE TABLE IF NOT EXISTS sav_trans_data (
        trans_id          UUID        NOT NULL REFERENCES sav_trans(id),
        line_no           SMALLINT    NOT NULL,
        account_id        UUID        NOT NULL REFERENCES sav_account(id),
        debit_minor       BIGINT      NOT NULL DEFAULT 0,
        credit_minor      BIGINT      NOT NULL DEFAULT 0,
        currency_code     VARCHAR(3)  NOT NULL,
        PRIMARY KEY (trans_id, line_no)
      )
      """;

  private static final String DDL_IDX_TRANS_DATA_ACCOUNT =
      "CREATE INDEX IF NOT EXISTS sav_trans_data_account_idx ON sav_trans_data (account_id)";

  /** At most one settlement (POSTED or VOIDED) per PENDING transfer. */
  static final String SETTLED_UIDX = "sav_trans_settled_uidx";
  private static final String DDL_IDX_SETTLED =
      "CREATE UNIQUE INDEX IF NOT EXISTS "
          + SETTLED_UIDX
          + " ON sav_trans (pending_id) WHERE pending_id IS NOT NULL";

  // ── System accounts ─────────────────────────────────────────────────────────

  private static final String ACCOUNT_NO_EXTERNAL = "SAV-EXTERNAL";
  private static final String ACCOUNT_NO_RESERVE  = "SAV-RESERVE";

  private static final String UPSERT_SYSTEM_ACCOUNT =
      "INSERT INTO sav_account (owner_mid, account_no, kind, currency_code)"
          + " VALUES (0, ?, 'RESERVE', 'VND') ON CONFLICT (account_no) DO NOTHING";

  private static final String SELECT_SYSTEM_ACCOUNT_ID =
      "SELECT id FROM sav_account WHERE account_no = ?";

  // ── Account DML ─────────────────────────────────────────────────────────────

  private static final String INSERT_ACCOUNT =
      "INSERT INTO sav_account"
          + " (owner_mid, account_no, kind, currency_code, flags, interest_rate_bps, maturity_at)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id, opened_at, version";

  private static final String SELECT_ACCOUNT_COLS =
      "id, owner_mid, account_no, kind, currency_code,"
          + " debits_pending, debits_posted, credits_pending, credits_posted,"
          + " flags, interest_rate_bps, maturity_at, opened_at, closed_at, version";

  private static final String SELECT_ACCOUNT =
      "SELECT " + SELECT_ACCOUNT_COLS + " FROM sav_account WHERE id = ?";

  private static final String SELECT_ACCOUNT_FOR_UPDATE =
      "SELECT " + SELECT_ACCOUNT_COLS + " FROM sav_account WHERE id = ? FOR UPDATE";

  private static final String INC_CREDITS_POSTED =
      "UPDATE sav_account SET credits_posted = credits_posted + ?, version = version + 1 WHERE id = ?";

  private static final String INC_DEBITS_POSTED =
      "UPDATE sav_account SET debits_posted = debits_posted + ?, version = version + 1 WHERE id = ?";

  private static final String INC_DEBITS_PENDING =
      "UPDATE sav_account SET debits_pending = debits_pending + ?, version = version + 1 WHERE id = ?";

  private static final String SETTLE_PENDING =
      "UPDATE sav_account"
          + " SET debits_pending = debits_pending - ?,"
          + "     debits_posted  = debits_posted  + ?,"
          + "     version = version + 1"
          + " WHERE id = ?";

  private static final String VOID_PENDING =
      "UPDATE sav_account"
          + " SET debits_pending = debits_pending - ?,"
          + "     version = version + 1"
          + " WHERE id = ?";

  /** Clears a single flag bit: {@code flags = flags & ~bit}. */
  private static final String CLEAR_FLAG =
      "UPDATE sav_account SET flags = flags & ~CAST(? AS INTEGER), version = version + 1"
          + " WHERE id = ?";

  private static final String CLOSE_ACCOUNT =
      "UPDATE sav_account SET flags = flags | ?, closed_at = NOW(), version = version + 1"
          + " WHERE id = ? AND owner_mid = ?";

  // ── Transfer DML ─────────────────────────────────────────────────────────────

  private static final String INSERT_TRANS =
      "INSERT INTO sav_trans"
          + " (kind, phase, pending_id, idempotency_key, ref_mid, ref_request_id, linked_batch_id, memo)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id, created_at";

  private static final String INSERT_TRANS_DATA =
      "INSERT INTO sav_trans_data (trans_id, line_no, account_id, debit_minor, credit_minor, currency_code)"
          + " VALUES (?, ?, ?, ?, ?, ?)";

  // Columns for JOIN queries: 10 from sav_trans + 4 from sav_trans_data = 14 total
  private static final String TRANS_JOIN_COLS =
      "t.id, t.kind, t.phase, t.pending_id, t.idempotency_key,"
          + " t.ref_mid, t.ref_request_id, t.linked_batch_id, t.memo, t.created_at,"
          + " d.account_id, d.debit_minor, d.credit_minor, d.currency_code";

  private static final String SELECT_TRANS_BY_ID =
      "SELECT " + TRANS_JOIN_COLS
          + " FROM sav_trans t JOIN sav_trans_data d ON t.id = d.trans_id"
          + " WHERE t.id = ? ORDER BY d.line_no";

  private static final String SELECT_TRANS_BY_IDEMPOTENCY =
      "SELECT " + TRANS_JOIN_COLS
          + " FROM sav_trans t JOIN sav_trans_data d ON t.id = d.trans_id"
          + " WHERE t.idempotency_key = ? ORDER BY d.line_no";

  /**
   * CTE finds the top N transfer IDs matching the account (either side), then JOINs all lines
   * for those transfers. LIMIT applies to number of transfers, not rows.
   */
  private static final String SELECT_STATEMENT =
      "WITH top_trans AS ("
          + "  SELECT DISTINCT t.id, t.created_at FROM sav_trans t"
          + "  JOIN sav_trans_data d ON t.id = d.trans_id"
          + "  WHERE d.account_id = ? AND t.created_at < ?"
          + "  ORDER BY t.created_at DESC LIMIT ?"
          + ") SELECT " + TRANS_JOIN_COLS
          + " FROM sav_trans t"
          + " JOIN sav_trans_data d ON t.id = d.trans_id"
          + " JOIN top_trans tt ON t.id = tt.id"
          + " ORDER BY t.created_at DESC, d.line_no";

  // ── State ───────────────────────────────────────────────────────────────────

  private final DataSource dataSource;
  private volatile boolean tablesEnsured;
  private volatile UUID externalAccountId;
  private volatile UUID reserveAccountId;

  public JdbcSavingLedger(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  // ── Public API ──────────────────────────────────────────────────────────────

  @Override
  public SavAccount openAccount(OpenAccountCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    String accountNo = generateAccountNo();
    try {
      ensureTables();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(INSERT_ACCOUNT)) {
        ps.setLong(1, cmd.ownerMid());
        ps.setString(2, accountNo);
        ps.setString(3, cmd.kind().name());
        ps.setString(4, cmd.currencyCode());
        ps.setInt(5, cmd.initialFlags());
        setNullableInt(ps, 6, cmd.interestRateBps());
        setNullableTimestamp(ps, 7, cmd.maturityAt());
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          UUID id = rs.getObject(1, UUID.class);
          Instant openedAt = rs.getTimestamp(2).toInstant();
          long version = rs.getLong(3);
          return new SavAccount(
              id, cmd.ownerMid(), accountNo, cmd.kind(), cmd.currencyCode(),
              0, 0, 0, 0, cmd.initialFlags(),
              cmd.interestRateBps(), cmd.maturityAt(), openedAt, null, version);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("openAccount failed: ownerMid=" + cmd.ownerMid(), e);
    }
  }

  @Override
  public SavTransfer deposit(DepositCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureTables();
      if (cmd.idempotencyKey() != null) {
        SavTransfer existing = findByIdempotencyKey(cmd.idempotencyKey());
        if (existing != null) return existing;
      }
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          SavAccount acct = lockAccount(c, cmd.creditAccountId());
          validateTransferable(acct);
          SavTransfer t = insertTransfer(
              c, SavTransferKind.DEPOSIT,
              externalAccountId, cmd.creditAccountId(),
              cmd.amountMinor(), cmd.currencyCode(),
              SavTransferPhase.POSTED, null,
              cmd.idempotencyKey(), cmd.refMid(), cmd.refRequestId(), null, cmd.memo());
          execUpdate(c, INC_CREDITS_POSTED, cmd.amountMinor(), cmd.creditAccountId());
          c.commit();
          return t;
        } catch (SQLException | RuntimeException e) {
          safeRollback(c);
          throw e;
        }
      }
    } catch (AccountClosedException | AccountFrozenException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("deposit failed: account=" + cmd.creditAccountId(), e);
    }
  }

  @Override
  public SavTransfer withdrawal(WithdrawalCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureTables();
      if (cmd.idempotencyKey() != null) {
        SavTransfer existing = findByIdempotencyKey(cmd.idempotencyKey());
        if (existing != null) return existing;
      }
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          SavAccount acct = lockAccount(c, cmd.debitAccountId());
          validateTransferable(acct);
          if (acct.isTermLocked()) {
            Instant maturity = acct.maturityAt();
            if (maturity != null && Instant.now().isBefore(maturity)) {
              throw new TermLockedException(acct.id(), maturity);
            }
            // Maturity passed — clear TERM_LOCKED in the same transaction
            clearFlag(c, acct.id(), SavAccountFlags.TERM_LOCKED);
          }
          long available = acct.availableBalance();
          if (available < cmd.amountMinor()) {
            throw new InsufficientFundsException(acct.id(), available, cmd.amountMinor());
          }
          SavTransferPhase phase = cmd.pending() ? SavTransferPhase.PENDING : SavTransferPhase.POSTED;
          SavTransfer t = insertTransfer(
              c, SavTransferKind.WITHDRAWAL,
              cmd.debitAccountId(), externalAccountId,
              cmd.amountMinor(), cmd.currencyCode(),
              phase, null,
              cmd.idempotencyKey(), cmd.refMid(), cmd.refRequestId(), null, cmd.memo());
          String balanceSql = phase == SavTransferPhase.PENDING ? INC_DEBITS_PENDING : INC_DEBITS_POSTED;
          execUpdate(c, balanceSql, cmd.amountMinor(), cmd.debitAccountId());
          c.commit();
          return t;
        } catch (SQLException | RuntimeException e) {
          safeRollback(c);
          throw e;
        }
      }
    } catch (AccountClosedException | AccountFrozenException | TermLockedException
        | InsufficientFundsException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("withdrawal failed: account=" + cmd.debitAccountId(), e);
    }
  }

  @Override
  public SavTransfer postPending(UUID pendingTransferId) {
    Objects.requireNonNull(pendingTransferId, "pendingTransferId");
    return settlePending(pendingTransferId, SavTransferPhase.POSTED);
  }

  @Override
  public SavTransfer voidPending(UUID pendingTransferId) {
    Objects.requireNonNull(pendingTransferId, "pendingTransferId");
    return settlePending(pendingTransferId, SavTransferPhase.VOIDED);
  }

  @Override
  public List<SavTransfer> accrueInterest(List<AccrueInterestCmd> cmds) {
    Objects.requireNonNull(cmds, "cmds");
    List<SavTransfer> results = new ArrayList<>(cmds.size());
    for (AccrueInterestCmd cmd : cmds) {
      try {
        ensureTables();
        try (Connection c = dataSource.getConnection()) {
          c.setAutoCommit(false);
          try {
            UUID first  = reserveAccountId.compareTo(cmd.savAccountId()) < 0
                ? reserveAccountId : cmd.savAccountId();
            UUID second = reserveAccountId.compareTo(cmd.savAccountId()) < 0
                ? cmd.savAccountId() : reserveAccountId;
            lockAccountById(c, first);
            lockAccountById(c, second);
            SavTransfer t = insertTransfer(
                c, SavTransferKind.INTEREST,
                reserveAccountId, cmd.savAccountId(),
                cmd.amountMinor(), cmd.currencyCode(),
                SavTransferPhase.POSTED, null,
                null, null, null, cmd.linkedBatchId(), null);
            execUpdate(c, INC_CREDITS_POSTED, cmd.amountMinor(), cmd.savAccountId());
            execUpdate(c, INC_DEBITS_POSTED,  cmd.amountMinor(), reserveAccountId);
            c.commit();
            results.add(t);
          } catch (SQLException | RuntimeException e) {
            safeRollback(c);
            throw e;
          }
        }
      } catch (SQLException e) {
        throw new IllegalStateException(
            "accrueInterest failed: account=" + cmd.savAccountId(), e);
      }
    }
    return results;
  }

  @Override
  public SavAccount closeAccount(UUID accountId, long ownerMid) {
    Objects.requireNonNull(accountId, "accountId");
    try {
      ensureTables();
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          SavAccount acct = lockAccount(c, accountId);
          if (acct.ownerMid() != ownerMid) {
            throw new IllegalStateException(
                "account " + accountId + " does not belong to mid=" + ownerMid);
          }
          if (acct.isClosed()) {
            c.rollback();
            return acct;
          }
          if (acct.availableBalance() != 0 || acct.debitsPending() != 0) {
            throw new IllegalStateException(
                "balance must be zero to close: available="
                    + acct.availableBalance()
                    + " debitsPending="
                    + acct.debitsPending()
                    + " account=" + accountId);
          }
          try (PreparedStatement ps = c.prepareStatement(CLOSE_ACCOUNT)) {
            ps.setInt(1, SavAccountFlags.CLOSED);
            ps.setObject(2, accountId);
            ps.setLong(3, ownerMid);
            ps.executeUpdate();
          }
          Instant closedAt = Instant.now();
          c.commit();
          return new SavAccount(
              acct.id(), acct.ownerMid(), acct.accountNo(), acct.kind(), acct.currencyCode(),
              acct.debitsPending(), acct.debitsPosted(), acct.creditsPending(), acct.creditsPosted(),
              acct.flags() | SavAccountFlags.CLOSED,
              acct.interestRateBps(), acct.maturityAt(), acct.openedAt(),
              closedAt, acct.version() + 1);
        } catch (SQLException | RuntimeException e) {
          safeRollback(c);
          throw e;
        }
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("closeAccount failed: " + accountId, e);
    }
  }

  @Override
  public SavAccount findAccount(UUID id) {
    Objects.requireNonNull(id, "id");
    try {
      ensureTables();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_ACCOUNT)) {
        ps.setObject(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? accountFromRs(rs) : null;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("findAccount failed: " + id, e);
    }
  }

  @Override
  public List<SavTransfer> statement(UUID accountId, Instant before, int limit) {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(before, "before");
    if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
    try {
      ensureTables();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_STATEMENT)) {
        ps.setObject(1, accountId);
        ps.setTimestamp(2, Timestamp.from(before));
        ps.setInt(3, limit);
        try (ResultSet rs = ps.executeQuery()) {
          return collectTransfers(rs);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("statement failed: account=" + accountId, e);
    }
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  private SavTransfer settlePending(UUID pendingId, SavTransferPhase resolution) {
    try {
      ensureTables();
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          SavTransfer pending = readTransfer(c, pendingId);
          if (pending.phase() != SavTransferPhase.PENDING) {
            throw new SavTransferPhaseException(pendingId, SavTransferPhase.PENDING, pending.phase());
          }
          lockAccountById(c, pending.debitAccountId());
          SavTransfer settled;
          try {
            settled = insertTransfer(
                c, pending.kind(),
                pending.debitAccountId(), pending.creditAccountId(),
                pending.amountMinor(), pending.currencyCode(),
                resolution, pendingId,
                null, pending.refMid(), pending.refRequestId(), pending.linkedBatchId(),
                pending.memo());
          } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())
                && e.getMessage() != null
                && e.getMessage().contains(SETTLED_UIDX)) {
              throw new SavTransferPhaseException(pendingId);
            }
            throw e;
          }
          if (resolution == SavTransferPhase.POSTED) {
            try (PreparedStatement ps = c.prepareStatement(SETTLE_PENDING)) {
              ps.setLong(1, pending.amountMinor());
              ps.setLong(2, pending.amountMinor());
              ps.setObject(3, pending.debitAccountId());
              ps.executeUpdate();
            }
          } else {
            execUpdate(c, VOID_PENDING, pending.amountMinor(), pending.debitAccountId());
          }
          c.commit();
          return settled;
        } catch (SQLException | RuntimeException e) {
          safeRollback(c);
          throw e;
        }
      }
    } catch (SavTransferPhaseException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("settlePending failed: pending=" + pendingId, e);
    }
  }

  private void ensureTables() throws SQLException {
    if (tablesEnsured) return;
    synchronized (this) {
      if (tablesEnsured) return;
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_ACCOUNT);
        st.execute(DDL_TRANS);
        st.execute(DDL_TRANS_DATA);
        st.execute(DDL_IDX_TRANS_DATA_ACCOUNT);
        st.execute(DDL_IDX_SETTLED);
      }
      try (Connection c = dataSource.getConnection()) {
        upsertSystemAccount(c, ACCOUNT_NO_EXTERNAL);
        upsertSystemAccount(c, ACCOUNT_NO_RESERVE);
        externalAccountId = loadSystemAccountId(c, ACCOUNT_NO_EXTERNAL);
        reserveAccountId  = loadSystemAccountId(c, ACCOUNT_NO_RESERVE);
      }
      tablesEnsured = true;
    }
  }

  private static void upsertSystemAccount(Connection c, String accountNo) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(UPSERT_SYSTEM_ACCOUNT)) {
      ps.setString(1, accountNo);
      ps.executeUpdate();
    }
  }

  private static UUID loadSystemAccountId(Connection c, String accountNo) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT_SYSTEM_ACCOUNT_ID)) {
      ps.setString(1, accountNo);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new IllegalStateException("system account not found: " + accountNo);
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private SavAccount lockAccount(Connection c, UUID id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT_ACCOUNT_FOR_UPDATE)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new IllegalStateException("account not found: " + id);
        return accountFromRs(rs);
      }
    }
  }

  private void lockAccountById(Connection c, UUID id) throws SQLException {
    lockAccount(c, id);
  }

  private void clearFlag(Connection c, UUID accountId, int flag) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(CLEAR_FLAG)) {
      ps.setInt(1, flag);
      ps.setObject(2, accountId);
      ps.executeUpdate();
    }
  }

  private SavTransfer readTransfer(Connection c, UUID id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT_TRANS_BY_ID)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        List<SavTransfer> list = collectTransfers(rs);
        if (list.isEmpty()) throw new IllegalStateException("transfer not found: " + id);
        return list.get(0);
      }
    }
  }

  private SavTransfer insertTransfer(
      Connection c,
      SavTransferKind kind,
      UUID debitId,
      UUID creditId,
      long amountMinor,
      String currencyCode,
      SavTransferPhase phase,
      UUID pendingId,
      UUID idempotencyKey,
      Long refMid,
      Long refRequestId,
      UUID linkedBatchId,
      String memo)
      throws SQLException {

    UUID id;
    Instant createdAt;
    try (PreparedStatement ps = c.prepareStatement(INSERT_TRANS)) {
      ps.setString(1, kind.name());
      ps.setString(2, phase.name());
      setNullableUuid(ps, 3, pendingId);
      setNullableUuid(ps, 4, idempotencyKey);
      setNullableLong(ps, 5, refMid);
      setNullableLong(ps, 6, refRequestId);
      setNullableUuid(ps, 7, linkedBatchId);
      if (memo != null) ps.setString(8, memo); else ps.setNull(8, Types.VARCHAR);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        id = rs.getObject(1, UUID.class);
        createdAt = rs.getTimestamp(2).toInstant();
      }
    }
    insertLine(c, id, 1, debitId,  amountMinor, 0L,          currencyCode);
    insertLine(c, id, 2, creditId, 0L,          amountMinor, currencyCode);

    return new SavTransfer(
        id, kind, phase, pendingId, idempotencyKey,
        refMid, refRequestId, linkedBatchId, memo, createdAt,
        List.of(
            new SavTransLine(debitId,  amountMinor, 0L,          currencyCode),
            new SavTransLine(creditId, 0L,          amountMinor, currencyCode)));
  }

  private void insertLine(
      Connection c, UUID transId, int lineNo, UUID accountId,
      long debitMinor, long creditMinor, String currencyCode)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_TRANS_DATA)) {
      ps.setObject(1, transId);
      ps.setInt(2, lineNo);
      ps.setObject(3, accountId);
      ps.setLong(4, debitMinor);
      ps.setLong(5, creditMinor);
      ps.setString(6, currencyCode);
      ps.executeUpdate();
    }
  }

  private void execUpdate(Connection c, String sql, long amount, UUID accountId)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, amount);
      ps.setObject(2, accountId);
      ps.executeUpdate();
    }
  }

  private SavTransfer findByIdempotencyKey(UUID key) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_TRANS_BY_IDEMPOTENCY)) {
      ps.setObject(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        List<SavTransfer> list = collectTransfers(rs);
        return list.isEmpty() ? null : list.get(0);
      }
    }
  }

  /**
   * Collects a multi-row JOIN result (one row per sav_trans_data line) into
   * a list of SavTransfer records, preserving result order.
   * Columns 1-10 are sav_trans fields; columns 11-14 are sav_trans_data fields.
   */
  private static List<SavTransfer> collectTransfers(ResultSet rs) throws SQLException {
    Map<UUID, Object[]> headers  = new LinkedHashMap<>();
    Map<UUID, List<SavTransLine>> lineMap = new LinkedHashMap<>();
    while (rs.next()) {
      UUID id = rs.getObject(1, UUID.class);
      if (!headers.containsKey(id)) {
        headers.put(id, new Object[]{
            SavTransferKind.valueOf(rs.getString(2)),   // kind
            SavTransferPhase.valueOf(rs.getString(3)),  // phase
            rs.getObject(4, UUID.class),                // pending_id
            rs.getObject(5, UUID.class),                // idempotency_key
            (Long) rs.getObject(6),                     // ref_mid
            (Long) rs.getObject(7),                     // ref_request_id
            rs.getObject(8, UUID.class),                // linked_batch_id
            rs.getString(9),                            // memo
            rs.getTimestamp(10).toInstant()             // created_at
        });
        lineMap.put(id, new ArrayList<>());
      }
      lineMap.get(id).add(new SavTransLine(
          rs.getObject(11, UUID.class),  // account_id
          rs.getLong(12),                // debit_minor
          rs.getLong(13),                // credit_minor
          rs.getString(14)));            // currency_code
    }
    List<SavTransfer> result = new ArrayList<>(headers.size());
    for (Map.Entry<UUID, Object[]> entry : headers.entrySet()) {
      UUID id = entry.getKey();
      Object[] h = entry.getValue();
      result.add(new SavTransfer(
          id,
          (SavTransferKind)  h[0],
          (SavTransferPhase) h[1],
          (UUID)             h[2],
          (UUID)             h[3],
          (Long)             h[4],
          (Long)             h[5],
          (UUID)             h[6],
          (String)           h[7],
          (Instant)          h[8],
          lineMap.get(id)));
    }
    return result;
  }

  private static void validateTransferable(SavAccount acct) {
    if (acct.isClosed()) throw new AccountClosedException(acct.id());
    if (acct.isFrozen()) throw new AccountFrozenException(acct.id());
  }

  private static SavAccount accountFromRs(ResultSet rs) throws SQLException {
    return new SavAccount(
        rs.getObject(1, UUID.class),
        rs.getLong(2),
        rs.getString(3),
        SavAccountKind.valueOf(rs.getString(4)),
        rs.getString(5),
        rs.getLong(6),
        rs.getLong(7),
        rs.getLong(8),
        rs.getLong(9),
        rs.getInt(10),
        (Integer) rs.getObject(11),
        toInstant(rs.getTimestamp(12)),
        rs.getTimestamp(13).toInstant(),
        toInstant(rs.getTimestamp(14)),
        rs.getLong(15));
  }

  private static Instant toInstant(java.sql.Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static String generateAccountNo() {
    String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    return "SAV-" + hex;
  }

  private static void setNullableInt(PreparedStatement ps, int idx, Integer val)
      throws SQLException {
    if (val != null) ps.setInt(idx, val); else ps.setNull(idx, Types.INTEGER);
  }

  private static void setNullableTimestamp(PreparedStatement ps, int idx, Instant val)
      throws SQLException {
    if (val != null) ps.setTimestamp(idx, Timestamp.from(val));
    else ps.setNull(idx, Types.TIMESTAMP_WITH_TIMEZONE);
  }

  private static void setNullableUuid(PreparedStatement ps, int idx, UUID val)
      throws SQLException {
    if (val != null) ps.setObject(idx, val); else ps.setNull(idx, Types.OTHER);
  }

  private static void setNullableLong(PreparedStatement ps, int idx, Long val)
      throws SQLException {
    if (val != null) ps.setLong(idx, val); else ps.setNull(idx, Types.BIGINT);
  }

  private static void safeRollback(Connection c) {
    try { c.rollback(); } catch (SQLException ignored) {}
  }
}
