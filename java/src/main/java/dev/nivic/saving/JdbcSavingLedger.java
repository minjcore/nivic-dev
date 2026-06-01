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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL-backed {@link SavingLedger}.
 *
 * <p>Balance invariant (credit-normal account):
 * {@code available = credits_posted - debits_posted - debits_pending}.</p>
 *
 * <p>Two system accounts are created at startup (owner_mid=0, kind=RESERVE):
 * {@code SAV-EXTERNAL} (wallet side of deposit/withdrawal) and
 * {@code SAV-RESERVE} (source of interest credits). Their balances are not constrained.</p>
 *
 * <p>Transfer rows are immutable: POSTED and VOIDED settlements are new rows referencing the
 * original PENDING row via {@code pending_id}. The unique index
 * {@code sav_transfer_settled_uidx(pending_id)} enforces at-most-one settlement per PENDING.</p>
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

  private static final String DDL_TRANSFER =
      """
      CREATE TABLE IF NOT EXISTS sav_transfer (
        id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        kind              VARCHAR(24) NOT NULL,
        debit_account_id  UUID        NOT NULL REFERENCES sav_account(id),
        credit_account_id UUID        NOT NULL REFERENCES sav_account(id),
        amount_minor      BIGINT      NOT NULL,
        currency_code     VARCHAR(3)  NOT NULL,
        phase             VARCHAR(16) NOT NULL DEFAULT 'POSTED',
        pending_id        UUID        REFERENCES sav_transfer(id),
        idempotency_key   UUID        UNIQUE,
        ref_mid           BIGINT,
        ref_request_id    BIGINT,
        linked_batch_id   UUID,
        memo              VARCHAR(256),
        created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
      """;

  private static final String DDL_IDX_DEBIT =
      "CREATE INDEX IF NOT EXISTS sav_transfer_debit_idx"
          + " ON sav_transfer (debit_account_id, created_at DESC)";
  private static final String DDL_IDX_CREDIT =
      "CREATE INDEX IF NOT EXISTS sav_transfer_credit_idx"
          + " ON sav_transfer (credit_account_id, created_at DESC)";

  /** Enforces at-most-one settlement (POSTED or VOIDED) per PENDING transfer. */
  static final String SETTLED_UIDX = "sav_transfer_settled_uidx";
  private static final String DDL_IDX_SETTLED =
      "CREATE UNIQUE INDEX IF NOT EXISTS "
          + SETTLED_UIDX
          + " ON sav_transfer (pending_id) WHERE pending_id IS NOT NULL";

  // ── System accounts ─────────────────────────────────────────────────────────

  private static final String ACCOUNT_NO_EXTERNAL = "SAV-EXTERNAL";
  private static final String ACCOUNT_NO_RESERVE  = "SAV-RESERVE";

  private static final String UPSERT_SYSTEM_ACCOUNT =
      "INSERT INTO sav_account (owner_mid, account_no, kind, currency_code)"
          + " VALUES (0, ?, 'RESERVE', 'VND') ON CONFLICT (account_no) DO NOTHING";

  private static final String SELECT_SYSTEM_ACCOUNT_ID =
      "SELECT id FROM sav_account WHERE account_no = ?";

  // ── DML ─────────────────────────────────────────────────────────────────────

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

  private static final String SELECT_TRANSFER_COLS =
      "id, kind, debit_account_id, credit_account_id, amount_minor, currency_code,"
          + " phase, pending_id, idempotency_key, ref_mid, ref_request_id, linked_batch_id,"
          + " memo, created_at";

  private static final String SELECT_TRANSFER_BY_ID =
      "SELECT " + SELECT_TRANSFER_COLS + " FROM sav_transfer WHERE id = ?";

  private static final String SELECT_TRANSFER_BY_IDEMPOTENCY =
      "SELECT " + SELECT_TRANSFER_COLS + " FROM sav_transfer WHERE idempotency_key = ?";

  private static final String INSERT_TRANSFER =
      "INSERT INTO sav_transfer"
          + " (kind, debit_account_id, credit_account_id, amount_minor, currency_code,"
          + "  phase, pending_id, idempotency_key, ref_mid, ref_request_id, linked_batch_id, memo)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id, created_at";

  /** credits_posted += amount */
  private static final String INC_CREDITS_POSTED =
      "UPDATE sav_account SET credits_posted = credits_posted + ?, version = version + 1 WHERE id = ?";

  /** debits_posted += amount */
  private static final String INC_DEBITS_POSTED =
      "UPDATE sav_account SET debits_posted = debits_posted + ?, version = version + 1 WHERE id = ?";

  /** debits_pending += amount */
  private static final String INC_DEBITS_PENDING =
      "UPDATE sav_account SET debits_pending = debits_pending + ?, version = version + 1 WHERE id = ?";

  /** debits_pending -= amount, debits_posted += amount (PENDING → POSTED) */
  private static final String SETTLE_PENDING =
      "UPDATE sav_account"
          + " SET debits_pending = debits_pending - ?,"
          + "     debits_posted  = debits_posted  + ?,"
          + "     version = version + 1"
          + " WHERE id = ?";

  /** debits_pending -= amount (PENDING → VOIDED) */
  private static final String VOID_PENDING =
      "UPDATE sav_account"
          + " SET debits_pending = debits_pending - ?,"
          + "     version = version + 1"
          + " WHERE id = ?";

  private static final String CLOSE_ACCOUNT =
      "UPDATE sav_account SET flags = flags | ?, closed_at = NOW(), version = version + 1"
          + " WHERE id = ? AND owner_mid = ?";

  private static final String SELECT_STATEMENT =
      "SELECT " + SELECT_TRANSFER_COLS + " FROM sav_transfer"
          + " WHERE (debit_account_id = ? OR credit_account_id = ?) AND created_at < ?"
          + " ORDER BY created_at DESC LIMIT ?";

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
              cmd.idempotencyKey(), cmd.refMid(), cmd.refRequestId(), null,
              cmd.memo());
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
          if (acct.isTermLocked()) throw new TermLockedException(acct.id(), acct.maturityAt());
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
              cmd.idempotencyKey(), cmd.refMid(), cmd.refRequestId(), null,
              cmd.memo());
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
            // Lock in UUID order to prevent deadlock between concurrent accruals.
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
                    + " account="
                    + accountId);
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
        ps.setObject(2, accountId);
        ps.setTimestamp(3, Timestamp.from(before));
        ps.setInt(4, limit);
        try (ResultSet rs = ps.executeQuery()) {
          List<SavTransfer> out = new ArrayList<>();
          while (rs.next()) out.add(transferFromRs(rs));
          return out;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("statement failed: account=" + accountId, e);
    }
  }

  // ── Internal helpers ────────────────────────────────────────────────────────

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
            // Unique index violation on pending_id → already settled by a concurrent caller.
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
        st.execute(DDL_TRANSFER);
        st.execute(DDL_IDX_DEBIT);
        st.execute(DDL_IDX_CREDIT);
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
        if (!rs.next()) {
          throw new IllegalStateException("system account not found: " + accountNo);
        }
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
    lockAccount(c, id); // discard result; only need the row lock
  }

  private SavTransfer readTransfer(Connection c, UUID id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT_TRANSFER_BY_ID)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new IllegalStateException("transfer not found: " + id);
        return transferFromRs(rs);
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
    try (PreparedStatement ps = c.prepareStatement(INSERT_TRANSFER)) {
      ps.setString(1, kind.name());
      ps.setObject(2, debitId);
      ps.setObject(3, creditId);
      ps.setLong(4, amountMinor);
      ps.setString(5, currencyCode);
      ps.setString(6, phase.name());
      setNullableUuid(ps, 7, pendingId);
      setNullableUuid(ps, 8, idempotencyKey);
      setNullableLong(ps, 9, refMid);
      setNullableLong(ps, 10, refRequestId);
      setNullableUuid(ps, 11, linkedBatchId);
      if (memo != null) ps.setString(12, memo); else ps.setNull(12, Types.VARCHAR);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        UUID id = rs.getObject(1, UUID.class);
        Instant createdAt = rs.getTimestamp(2).toInstant();
        return new SavTransfer(
            id, kind, debitId, creditId, amountMinor, currencyCode,
            phase, pendingId, idempotencyKey, refMid, refRequestId, linkedBatchId,
            memo, createdAt);
      }
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
        PreparedStatement ps = c.prepareStatement(SELECT_TRANSFER_BY_IDEMPOTENCY)) {
      ps.setObject(1, key);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? transferFromRs(rs) : null;
      }
    }
  }

  private static void validateTransferable(SavAccount acct) {
    if (acct.isClosed()) throw new AccountClosedException(acct.id());
    if (acct.isFrozen()) throw new AccountFrozenException(acct.id());
  }

  private static SavAccount accountFromRs(ResultSet rs) throws SQLException {
    return new SavAccount(
        rs.getObject(1, UUID.class),           // id
        rs.getLong(2),                         // owner_mid
        rs.getString(3),                       // account_no
        SavAccountKind.valueOf(rs.getString(4)), // kind
        rs.getString(5),                       // currency_code
        rs.getLong(6),                         // debits_pending
        rs.getLong(7),                         // debits_posted
        rs.getLong(8),                         // credits_pending
        rs.getLong(9),                         // credits_posted
        rs.getInt(10),                         // flags
        (Integer) rs.getObject(11),            // interest_rate_bps (nullable)
        toInstant(rs.getTimestamp(12)),         // maturity_at
        rs.getTimestamp(13).toInstant(),        // opened_at
        toInstant(rs.getTimestamp(14)),         // closed_at
        rs.getLong(15));                       // version
  }

  private static SavTransfer transferFromRs(ResultSet rs) throws SQLException {
    return new SavTransfer(
        rs.getObject(1, UUID.class),                        // id
        SavTransferKind.valueOf(rs.getString(2)),            // kind
        rs.getObject(3, UUID.class),                        // debit_account_id
        rs.getObject(4, UUID.class),                        // credit_account_id
        rs.getLong(5),                                      // amount_minor
        rs.getString(6),                                    // currency_code
        SavTransferPhase.valueOf(rs.getString(7)),          // phase
        rs.getObject(8, UUID.class),                        // pending_id
        rs.getObject(9, UUID.class),                        // idempotency_key
        (Long) rs.getObject(10),                            // ref_mid
        (Long) rs.getObject(11),                            // ref_request_id
        rs.getObject(12, UUID.class),                       // linked_batch_id
        rs.getString(13),                                   // memo
        rs.getTimestamp(14).toInstant());                   // created_at
  }

  private static Instant toInstant(Timestamp ts) {
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
