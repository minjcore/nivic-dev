package dev.nivic.coa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL-backed {@link FundFlowLedger}.
 *
 * <p>Schema uses three tables (prefix {@code coa_}):</p>
 * <ul>
 *   <li>{@code coa_account}   — Chart of Accounts with running balance.</li>
 *   <li>{@code coa_trans}     — Journal header (idempotency via {@code ref_id UNIQUE}).</li>
 *   <li>{@code coa_trans_data}— Bút toán lines (debit / credit per account).</li>
 * </ul>
 *
 * <p>Every {@code postJournal} call locks accounts in {@code code} order (prevents deadlocks),
 * validates balance, inserts header + lines, then updates each account balance atomically.</p>
 */
public final class JdbcFundFlowLedger implements FundFlowLedger {

  // ── DDL ──────────────────────────────────────────────────────────────────────

  private static final String DDL_ACCOUNT = """
      CREATE TABLE IF NOT EXISTS coa_account (
        code          VARCHAR(10)  PRIMARY KEY,
        name          VARCHAR(256) NOT NULL,
        kind          VARCHAR(16)  NOT NULL,
        balance_minor BIGINT       NOT NULL DEFAULT 0,
        version       BIGINT       NOT NULL DEFAULT 0
      )
      """;

  private static final String DDL_TRANS = """
      CREATE TABLE IF NOT EXISTS coa_trans (
        id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
        ref_id     VARCHAR(128) UNIQUE,
        memo       VARCHAR(512),
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      )
      """;

  private static final String DDL_TRANS_DATA = """
      CREATE TABLE IF NOT EXISTS coa_trans_data (
        trans_id      UUID        NOT NULL REFERENCES coa_trans(id),
        line_no       SMALLINT    NOT NULL,
        account_code  VARCHAR(10) NOT NULL REFERENCES coa_account(code),
        debit_minor   BIGINT      NOT NULL DEFAULT 0,
        credit_minor  BIGINT      NOT NULL DEFAULT 0,
        currency_code VARCHAR(3)  NOT NULL DEFAULT 'VND',
        PRIMARY KEY (trans_id, line_no)
      )
      """;

  private static final String DDL_IDX_DATA_ACCOUNT =
      "CREATE INDEX IF NOT EXISTS coa_trans_data_account_idx ON coa_trans_data (account_code)";

  // ── COA seed data ─────────────────────────────────────────────────────────────

  private static final Object[][] ACCOUNTS = {
      // code,   name,                           kind
      {"1111", "TK Vietinbank Chuyên dùng",      "ASSET"},
      {"1112", "TK Napas Clearing",               "ASSET"},
      {"1113", "TK VPBank - QR/POS",              "ASSET"},
      {"2110", "Wallet Balance - User",           "LIABILITY"},
      {"2120", "Wallet Balance Merchant",         "LIABILITY"},
      {"2130", "Ký quỹ - Đối tác Chi hộ",         "LIABILITY"},
      {"3100", "Transit - Nạp tiền",              "TRANSIT"},
      {"3200", "Transit - Rút tiền",              "TRANSIT"},
      {"3300", "Transit - Chuyển tiền nội bộ",    "TRANSIT"},
      {"3400", "Transit - IBFT",                  "TRANSIT"},
      {"3500", "Transit - Thanh toán",            "TRANSIT"},
      {"3600", "Transit - Chi Lương",             "TRANSIT"},
      {"3700", "Transit - Chi hộ",                "TRANSIT"},
      {"3800", "Transit - Clearing",              "TRANSIT"},
      {"3810", "Transit - Settlement Outbound",   "TRANSIT"},
      {"3820", "Transit - MDR Holdback",          "TRANSIT"},
      {"4110", "Doanh thu Phí nạp tiền",          "REVENUE"},
      {"4120", "Doanh thu Phí rút tiền",          "REVENUE"},
      {"4130", "Doanh thu Phí chuyển tiền",       "REVENUE"},
      {"4140", "Doanh thu Phí MDR",               "REVENUE"},
      {"4150", "Doanh thu Phí Chi Lương/Chi hộ",  "REVENUE"},
      {"5100", "Chi phí Phí NH / Napas",          "EXPENSE"},
      {"6000", "Vốn chủ sở hữu",                  "EQUITY"},
  };

  private static final String UPSERT_ACCOUNT =
      "INSERT INTO coa_account (code, name, kind) VALUES (?, ?, ?)"
          + " ON CONFLICT (code) DO NOTHING";

  // ── DML ───────────────────────────────────────────────────────────────────────

  private static final String SELECT_ACCOUNT_FOR_UPDATE =
      "SELECT code, name, kind, balance_minor, version FROM coa_account WHERE code = ? FOR UPDATE";

  private static final String SELECT_ACCOUNT =
      "SELECT code, name, kind, balance_minor, version FROM coa_account WHERE code = ?";

  private static final String UPDATE_BALANCE =
      "UPDATE coa_account SET balance_minor = balance_minor + ?, version = version + 1 WHERE code = ?";

  private static final String SELECT_REF_ID =
      "SELECT id FROM coa_trans WHERE ref_id = ?";

  private static final String INSERT_TRANS =
      "INSERT INTO coa_trans (ref_id, memo) VALUES (?, ?) RETURNING id, created_at";

  private static final String INSERT_TRANS_DATA =
      "INSERT INTO coa_trans_data (trans_id, line_no, account_code, debit_minor, credit_minor)"
          + " VALUES (?, ?, ?, ?, ?)";

  private static final String SELECT_BALANCE =
      "SELECT balance_minor FROM coa_account WHERE code = ?";

  private static final String SELECT_TRANS_WITH_LINES =
      "SELECT t.id, t.ref_id, t.memo, t.created_at,"
          + "  d.line_no, d.account_code, a.name, d.debit_minor, d.credit_minor"
          + " FROM coa_trans t"
          + " JOIN coa_trans_data d ON t.id = d.trans_id"
          + " JOIN coa_account    a ON d.account_code = a.code"
          + " WHERE t.id = ?"
          + " ORDER BY d.line_no";

  private static final String CHECK_DOUBLE_ENTRY =
      "SELECT"
          + "  COALESCE(SUM(debit_minor),  0) AS total_dr,"
          + "  COALESCE(SUM(credit_minor), 0) AS total_cr"
          + " FROM coa_trans_data";

  // ── State ─────────────────────────────────────────────────────────────────────

  private final DataSource dataSource;
  private volatile boolean schemaEnsured;

  public JdbcFundFlowLedger(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  // ── Public API ────────────────────────────────────────────────────────────────

  @Override
  public CoaTrans receiveTopUp(TopUpReceiveCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.bankRef());
      if (existing != null) return existing;

      // DR 1111 / CR 3100
      List<JournalLine> lines = List.of(
          new JournalLine("1111", cmd.amountMinor(), 0L),
          new JournalLine("3100", 0L, cmd.amountMinor()));

      return postJournal(lines, cmd.bankRef(),
          cmd.memo() != null ? cmd.memo() : "Nạp tiền — NH nhận: " + cmd.amountMinor());
    } catch (SQLException e) {
      throw new IllegalStateException("receiveTopUp failed: " + cmd.bankRef(), e);
    }
  }

  @Override
  public CoaTrans confirmTopUp(TopUpConfirmCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.confirmRef());
      if (existing != null) return existing;

      // Validate transit balance (debit-side of 3100 must have accumulated credit to release)
      long transitBalance = getBalance("3100");
      // 3100 is credit-normal: balance = debit−credit < 0 when funded
      // releasing: we will DR 3100 by amountMinor → balance moves toward 0
      // check: |balance| >= amountMinor  ⟺  -balance >= amountMinor  ⟺  balance <= -amountMinor
      if (transitBalance > -cmd.amountMinor()) {
        throw new InsufficientTransitException("3100", transitBalance, cmd.amountMinor());
      }

      long netUser = cmd.amountMinor() - cmd.feeMinor();
      // DR 3100 / CR 2110 (net) / CR 4110 (fee)
      List<JournalLine> lines = List.of(
          new JournalLine("3100", cmd.amountMinor(), 0L),
          new JournalLine("2110", 0L, netUser),
          new JournalLine("4110", 0L, cmd.feeMinor()));

      return postJournal(lines, cmd.confirmRef(),
          cmd.memo() != null ? cmd.memo()
              : "Nạp tiền — xác nhận: " + cmd.amountMinor() + " phí: " + cmd.feeMinor());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("confirmTopUp failed: " + cmd.confirmRef(), e);
    }
  }

  @Override
  public CoaTrans initWithdraw(WithdrawInitCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      // Validate: 2110 phải có đủ số dư để trừ (amount + fee)
      // 2110 credit-normal: balance < 0 khi user có tiền
      // cần: -balance >= totalDebit ⟺ balance <= -totalDebit
      long walletBalance = getBalance("2110");
      if (walletBalance > -cmd.totalDebit()) {
        throw new InsufficientWalletException("2110", walletBalance, cmd.totalDebit());
      }

      // DR 2110 (amount + fee) / CR 3200 (amount + fee)
      List<JournalLine> lines = List.of(
          new JournalLine("2110", cmd.totalDebit(), 0L),
          new JournalLine("3200", 0L, cmd.totalDebit()));

      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo()
              : "Rút tiền — trừ ví: " + cmd.amountMinor() + " phí: " + cmd.feeMinor());
    } catch (InsufficientWalletException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("initWithdraw failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans settleWithdraw(WithdrawSettleCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.settleRef());
      if (existing != null) return existing;

      // Validate: 3200 phải có đủ credit để release
      // 3200 credit-normal: balance < 0 khi transit đang hold tiền
      // cần: -balance >= totalTransitRelease ⟺ balance <= -totalTransitRelease
      long transitBalance = getBalance("3200");
      if (transitBalance > -cmd.totalTransitRelease()) {
        throw new InsufficientTransitException("3200", transitBalance, cmd.totalTransitRelease());
      }

      // DR 3200 (amount + fee) / CR 1111 (amount) / CR 4120 (fee)
      List<JournalLine> lines = new java.util.ArrayList<>();
      lines.add(new JournalLine("3200", cmd.totalTransitRelease(), 0L));
      lines.add(new JournalLine("1111", 0L, cmd.amountMinor()));
      if (cmd.feeMinor() > 0) {
        lines.add(new JournalLine("4120", 0L, cmd.feeMinor()));
      }

      return postJournal(lines, cmd.settleRef(),
          cmd.memo() != null ? cmd.memo()
              : "Rút tiền — NH chuyển: " + cmd.amountMinor() + " phí: " + cmd.feeMinor());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("settleWithdraw failed: " + cmd.settleRef(), e);
    }
  }

  @Override
  public CoaTrans initInternalTransfer(InternalTransferInitCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      long walletBalance = getBalance("2110");
      if (walletBalance > -cmd.totalDebit()) {
        throw new InsufficientWalletException("2110", walletBalance, cmd.totalDebit());
      }

      // DR 2110 (amount + fee) / CR 3300 (amount + fee)
      List<JournalLine> lines = List.of(
          new JournalLine("2110", cmd.totalDebit(), 0L),
          new JournalLine("3300", 0L, cmd.totalDebit()));

      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo()
              : "Chuyển tiền nội bộ — trừ ví: " + cmd.amountMinor() + " phí: " + cmd.feeMinor());
    } catch (InsufficientWalletException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("initInternalTransfer failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans settleInternalTransfer(InternalTransferSettleCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.settleRef());
      if (existing != null) return existing;

      long transitBalance = getBalance("3300");
      if (transitBalance > -cmd.totalTransitRelease()) {
        throw new InsufficientTransitException("3300", transitBalance, cmd.totalTransitRelease());
      }

      // DR 3300 (amount + fee) / CR 2110 (amount) / CR 4130 (fee)
      List<JournalLine> lines = new java.util.ArrayList<>();
      lines.add(new JournalLine("3300", cmd.totalTransitRelease(), 0L));
      lines.add(new JournalLine("2110", 0L, cmd.amountMinor()));
      if (cmd.feeMinor() > 0) {
        lines.add(new JournalLine("4130", 0L, cmd.feeMinor()));
      }

      return postJournal(lines, cmd.settleRef(),
          cmd.memo() != null ? cmd.memo()
              : "Chuyển tiền nội bộ — cộng ví: " + cmd.amountMinor() + " phí: " + cmd.feeMinor());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("settleInternalTransfer failed: " + cmd.settleRef(), e);
    }
  }

  @Override
  public CoaTrans initIbftTransfer(IbftInitCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      long walletBalance = getBalance("2110");
      if (walletBalance > -cmd.totalDebit()) {
        throw new InsufficientWalletException("2110", walletBalance, cmd.totalDebit());
      }

      // DR 2110 (amount + fee) / CR 3400 (amount + fee)
      List<JournalLine> lines = List.of(
          new JournalLine("2110", cmd.totalDebit(), 0L),
          new JournalLine("3400", 0L, cmd.totalDebit()));

      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo()
              : "IBFT — trừ ví: " + cmd.amountMinor() + " phí: " + cmd.feeMinor());
    } catch (InsufficientWalletException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("initIbftTransfer failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans settleIbftTransfer(IbftSettleCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.settleRef());
      if (existing != null) return existing;

      long transitBalance = getBalance("3400");
      if (transitBalance > -cmd.totalTransitRelease()) {
        throw new InsufficientTransitException("3400", transitBalance, cmd.totalTransitRelease());
      }

      // 4-leg balanced entry:
      // DR 3400 (amount+fee) / DR 5100 (napasCost) / CR 1112 (amount+napasCost) / CR 4130 (fee)
      List<JournalLine> lines = new java.util.ArrayList<>();
      lines.add(new JournalLine("3400", cmd.totalTransitRelease(), 0L));
      lines.add(new JournalLine("5100", cmd.napasCost(), 0L));
      lines.add(new JournalLine("1112", 0L, cmd.napasOutflow()));
      if (cmd.feeMinor() > 0) {
        lines.add(new JournalLine("4130", 0L, cmd.feeMinor()));
      }

      return postJournal(lines, cmd.settleRef(),
          cmd.memo() != null ? cmd.memo()
              : "IBFT — Napas: " + cmd.amountMinor() + " phí: " + cmd.feeMinor()
              + " Napas cost: " + cmd.napasCost());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("settleIbftTransfer failed: " + cmd.settleRef(), e);
    }
  }

  @Override
  public long getBalance(String accountCode) {
    Objects.requireNonNull(accountCode, "accountCode");
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_BALANCE)) {
        ps.setString(1, accountCode);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) throw new IllegalArgumentException("unknown account: " + accountCode);
          return rs.getLong(1);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("getBalance failed: " + accountCode, e);
    }
  }

  @Override
  public CoaTrans findTrans(UUID transId) {
    Objects.requireNonNull(transId, "transId");
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_TRANS_WITH_LINES)) {
        ps.setObject(1, transId);
        try (ResultSet rs = ps.executeQuery()) {
          return collectTrans(rs);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("findTrans failed: " + transId, e);
    }
  }

  @Override
  public boolean isDoubleEntryBalanced() {
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(CHECK_DOUBLE_ENTRY);
          ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong("total_dr") == rs.getLong("total_cr");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("isDoubleEntryBalanced failed", e);
    }
  }

  // ── Core posting engine ───────────────────────────────────────────────────────

  /**
   * Posts a balanced journal atomically:
   * 1. Validates Σdebit = Σcredit.
   * 2. Locks accounts in code order (deadlock prevention).
   * 3. Inserts coa_trans header + coa_trans_data lines.
   * 4. Updates each account's balance_minor.
   */
  private CoaTrans postJournal(List<JournalLine> lines, String refId, String memo)
      throws SQLException {
    // Pre-flight: must be balanced
    long totalDr = lines.stream().mapToLong(JournalLine::debitMinor).sum();
    long totalCr = lines.stream().mapToLong(JournalLine::creditMinor).sum();
    if (totalDr != totalCr) {
      throw new IllegalArgumentException(
          "journal not balanced: DR=" + totalDr + " CR=" + totalCr + " ref=" + refId);
    }

    // Lock accounts in code order to prevent deadlocks
    List<String> codesOrdered = lines.stream()
        .map(JournalLine::accountCode)
        .distinct()
        .sorted()
        .toList();

    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        // Acquire row locks
        Map<String, CoaAccount> locked = new LinkedHashMap<>();
        for (String code : codesOrdered) {
          locked.put(code, lockAccount(c, code));
        }

        // Insert header
        UUID transId;
        Instant createdAt;
        try (PreparedStatement ps = c.prepareStatement(INSERT_TRANS)) {
          if (refId != null) ps.setString(1, refId); else ps.setNull(1, Types.VARCHAR);
          if (memo  != null) ps.setString(2, memo);  else ps.setNull(2, Types.VARCHAR);
          try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            transId   = rs.getObject(1, UUID.class);
            createdAt = rs.getTimestamp(2).toInstant();
          }
        }

        // Insert lines + update balances
        List<CoaTransLine> resultLines = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
          JournalLine l = lines.get(i);
          int lineNo = i + 1;
          try (PreparedStatement ps = c.prepareStatement(INSERT_TRANS_DATA)) {
            ps.setObject(1, transId);
            ps.setInt(2, lineNo);
            ps.setString(3, l.accountCode());
            ps.setLong(4, l.debitMinor());
            ps.setLong(5, l.creditMinor());
            ps.executeUpdate();
          }
          try (PreparedStatement ps = c.prepareStatement(UPDATE_BALANCE)) {
            ps.setLong(1, l.netDelta()); // debit − credit
            ps.setString(2, l.accountCode());
            ps.executeUpdate();
          }
          CoaAccount acct = locked.get(l.accountCode());
          resultLines.add(new CoaTransLine(lineNo, l.accountCode(), acct.name(),
              l.debitMinor(), l.creditMinor(), "VND"));
        }

        c.commit();
        return new CoaTrans(transId, refId, memo, createdAt, resultLines);
      } catch (SQLException | RuntimeException e) {
        try { c.rollback(); } catch (SQLException ignored) {}
        throw e;
      }
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private void ensureSchema() throws SQLException {
    if (schemaEnsured) return;
    synchronized (this) {
      if (schemaEnsured) return;
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_ACCOUNT);
        st.execute(DDL_TRANS);
        st.execute(DDL_TRANS_DATA);
        st.execute(DDL_IDX_DATA_ACCOUNT);
      }
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(UPSERT_ACCOUNT)) {
        for (Object[] row : ACCOUNTS) {
          ps.setString(1, (String) row[0]);
          ps.setString(2, (String) row[1]);
          ps.setString(3, (String) row[2]);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      schemaEnsured = true;
    }
  }

  private CoaAccount lockAccount(Connection c, String code) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(SELECT_ACCOUNT_FOR_UPDATE)) {
      ps.setString(1, code);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("unknown account: " + code);
        return accountFromRs(rs);
      }
    }
  }

  private CoaTrans findByRefId(String refId) throws SQLException {
    if (refId == null) return null;
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_REF_ID)) {
      ps.setString(1, refId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        UUID id = rs.getObject(1, UUID.class);
        return findTrans(id);
      }
    }
  }

  private static CoaTrans collectTrans(ResultSet rs) throws SQLException {
    UUID id = null;
    String refId = null, memo = null;
    Instant createdAt = null;
    List<CoaTransLine> lines = new ArrayList<>();
    while (rs.next()) {
      if (id == null) {
        id        = rs.getObject(1, UUID.class);
        refId     = rs.getString(2);
        memo      = rs.getString(3);
        createdAt = rs.getTimestamp(4).toInstant();
      }
      lines.add(new CoaTransLine(
          rs.getInt(5),      // line_no
          rs.getString(6),   // account_code
          rs.getString(7),   // account name
          rs.getLong(8),     // debit_minor
          rs.getLong(9),     // credit_minor
          "VND"));
    }
    if (id == null) return null;
    return new CoaTrans(id, refId, memo, createdAt, lines);
  }

  private static CoaAccount accountFromRs(ResultSet rs) throws SQLException {
    return new CoaAccount(
        rs.getString(1),                           // code
        rs.getString(2),                           // name
        CoaAccountKind.valueOf(rs.getString(3)),   // kind
        rs.getLong(4),                             // balance_minor
        rs.getLong(5));                            // version
  }

  /** Internal line record used only within postJournal. */
  private record JournalLine(String accountCode, long debitMinor, long creditMinor) {
    long netDelta() { return debitMinor - creditMinor; }
  }
}
