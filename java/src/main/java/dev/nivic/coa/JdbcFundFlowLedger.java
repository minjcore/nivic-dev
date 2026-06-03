package dev.nivic.coa;

import dev.nivic.coa.cmd.*;
import dev.nivic.coa.error.*;
import dev.nivic.coa.mc.Proposal;
import dev.nivic.coa.mc.ProposalStatus;
import dev.nivic.coa.mc.ProposeJournalCmd;
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

  /** For DBs created before currency_code existed. */
  private static final String DDL_ACCOUNT_CCY_ALTER =
      "ALTER TABLE coa_account ADD COLUMN IF NOT EXISTS currency_code VARCHAR(3) NOT NULL DEFAULT 'VND'";

  /** Extend currency_code to support USDT, ETH, BTC (4+ char codes). */
  private static final String DDL_ACCOUNT_CCY_EXTEND =
      "ALTER TABLE coa_account ALTER COLUMN currency_code TYPE VARCHAR(10)";

  private static final String DDL_ACCOUNT = """
      CREATE TABLE IF NOT EXISTS coa_account (
        code          VARCHAR(10)  PRIMARY KEY,
        name          VARCHAR(256) NOT NULL,
        kind          VARCHAR(16)  NOT NULL,
        currency_code VARCHAR(3)   NOT NULL DEFAULT 'VND',
        balance_minor BIGINT       NOT NULL DEFAULT 0,
        version       BIGINT       NOT NULL DEFAULT 0
      )
      """;

  /** Constraint name for the balance-sign rule (defense-in-depth chống âm). */
  static final String BALANCE_CHECK = "coa_account_balance_chk";

  /**
   * Frozen rule ở tầng DB: số dư không được sang chiều sai theo bản chất tài khoản.
   * NOT VALID để thêm an toàn lên DB có sẵn (không quét lại hàng cũ; vẫn enforce mọi write mới).
   */
  private static final String DDL_ACCOUNT_CHECK =
      "ALTER TABLE coa_account ADD CONSTRAINT " + BALANCE_CHECK + " CHECK ("
          + " CASE kind"
          + "   WHEN 'LIABILITY' THEN balance_minor <= 0"
          + "   WHEN 'TRANSIT'   THEN balance_minor <= 0"
          + "   WHEN 'REVENUE'   THEN balance_minor <= 0"
          + "   WHEN 'EXPENSE'   THEN balance_minor >= 0"
          + "   ELSE TRUE"
          + " END) NOT VALID";

  // ── Frozen rules (DB triggers) — đóng băng sổ nhật ký ───────────────────────

  /** Append-only: cấm sửa/xoá bút toán đã ghi. */
  private static final String DDL_FN_FORBID = """
      CREATE OR REPLACE FUNCTION coa_forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN
        RAISE EXCEPTION 'coa journal is append-only: % on % is forbidden', TG_OP, TG_TABLE_NAME;
      END $$
      """;

  /** Deferred: tại COMMIT, mọi trans_id phải cân theo TỪNG currency (double-entry đa tệ tầng DB). */
  private static final String DDL_FN_BALANCED = """
      CREATE OR REPLACE FUNCTION coa_check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
      DECLARE bad RECORD;
      BEGIN
        SELECT currency_code, SUM(debit_minor) dr, SUM(credit_minor) cr
          INTO bad FROM coa_trans_data WHERE trans_id = NEW.trans_id
          GROUP BY currency_code HAVING SUM(debit_minor) <> SUM(credit_minor) LIMIT 1;
        IF FOUND THEN
          RAISE EXCEPTION 'unbalanced journal % in %: debit=% credit=%',
            NEW.trans_id, bad.currency_code, bad.dr, bad.cr USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
      END $$
      """;

  /** Triggers: drop-then-create (idempotent). Constraint trigger phải DROP+CREATE. */
  private static final String[] DDL_TRIGGERS = {
      "DROP TRIGGER IF EXISTS coa_trans_no_mutation ON coa_trans",
      "CREATE TRIGGER coa_trans_no_mutation BEFORE UPDATE OR DELETE ON coa_trans"
          + " FOR EACH ROW EXECUTE FUNCTION coa_forbid_mutation()",
      "DROP TRIGGER IF EXISTS coa_trans_data_no_mutation ON coa_trans_data",
      "CREATE TRIGGER coa_trans_data_no_mutation BEFORE UPDATE OR DELETE ON coa_trans_data"
          + " FOR EACH ROW EXECUTE FUNCTION coa_forbid_mutation()",
      "DROP TRIGGER IF EXISTS coa_trans_data_balanced ON coa_trans_data",
      "CREATE CONSTRAINT TRIGGER coa_trans_data_balanced AFTER INSERT ON coa_trans_data"
          + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION coa_check_balanced()",
  };

  private static final String DDL_TRANS = """
      CREATE TABLE IF NOT EXISTS coa_trans (
        id           BIGINT       GENERATED BY DEFAULT AS IDENTITY (START WITH 500000000) PRIMARY KEY,
        ref_id       VARCHAR(128) UNIQUE,
        memo         VARCHAR(512),
        reverses_ref VARCHAR(128),
        created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
      )
      """;

  /** For DBs created before reverses_ref existed. */
  private static final String DDL_TRANS_ALTER =
      "ALTER TABLE coa_trans ADD COLUMN IF NOT EXISTS reverses_ref VARCHAR(128)";

  private static final String DDL_TRANS_DATA = """
      CREATE TABLE IF NOT EXISTS coa_trans_data (
        trans_id      BIGINT      NOT NULL REFERENCES coa_trans(id),
        line_no       SMALLINT    NOT NULL,
        account_code  VARCHAR(10) NOT NULL REFERENCES coa_account(code),
        debit_minor   BIGINT      NOT NULL DEFAULT 0,
        credit_minor  BIGINT      NOT NULL DEFAULT 0,
        currency_code VARCHAR(3)  NOT NULL DEFAULT 'VND',
        party_mid     BIGINT,
        PRIMARY KEY (trans_id, line_no)
      )
      """;

  /** For DBs created before party_mid existed. */
  private static final String DDL_TRANS_DATA_ALTER =
      "ALTER TABLE coa_trans_data ADD COLUMN IF NOT EXISTS party_mid BIGINT";

  /** Extend currency_code to support USDT, ETH, BTC (4+ char codes). */
  private static final String DDL_TRANS_DATA_CCY_EXTEND =
      "ALTER TABLE coa_trans_data ALTER COLUMN currency_code TYPE VARCHAR(10)";

  private static final String DDL_IDX_DATA_ACCOUNT =
      "CREATE INDEX IF NOT EXISTS coa_trans_data_account_idx ON coa_trans_data (account_code)";

  // ── Maker-checker staging tables ──────────────────────────────────────────────
  private static final String DDL_PROPOSAL = """
      CREATE TABLE IF NOT EXISTS coa_proposal (
        id              BIGINT       GENERATED BY DEFAULT AS IDENTITY (START WITH 700000000) PRIMARY KEY,
        ref_id          VARCHAR(128) UNIQUE,
        memo            VARCHAR(512),
        maker_id        VARCHAR(64)  NOT NULL,
        status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
        checker_id      VARCHAR(64),
        reason          VARCHAR(512),
        posted_trans_id BIGINT,
        created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
        decided_at      TIMESTAMPTZ
      )
      """;
  private static final String DDL_PROPOSAL_LINE = """
      CREATE TABLE IF NOT EXISTS coa_proposal_line (
        proposal_id  BIGINT      NOT NULL REFERENCES coa_proposal(id),
        line_no      SMALLINT    NOT NULL,
        account_code VARCHAR(10) NOT NULL,
        debit_minor  BIGINT      NOT NULL DEFAULT 0,
        credit_minor BIGINT      NOT NULL DEFAULT 0,
        party_mid    BIGINT,
        PRIMARY KEY (proposal_id, line_no)
      )
      """;
  private static final String DDL_IDX_PROPOSAL_STATUS =
      "CREATE INDEX IF NOT EXISTS coa_proposal_status_idx ON coa_proposal (status, created_at DESC)";

  private static final String INSERT_PROPOSAL =
      "INSERT INTO coa_proposal (ref_id, memo, maker_id) VALUES (?, ?, ?) RETURNING id, created_at";
  private static final String INSERT_PROPOSAL_LINE =
      "INSERT INTO coa_proposal_line (proposal_id, line_no, account_code, debit_minor, credit_minor, party_mid)"
          + " VALUES (?, ?, ?, ?, ?, ?)";
  private static final String SELECT_PROPOSAL_ID_BY_REF =
      "SELECT id FROM coa_proposal WHERE ref_id = ?";
  private static final String SELECT_PROPOSAL_COLS =
      "id, ref_id, memo, maker_id, status, checker_id, reason, posted_trans_id, created_at, decided_at";
  private static final String SELECT_PROPOSAL =
      "SELECT " + SELECT_PROPOSAL_COLS + " FROM coa_proposal WHERE id = ?";
  private static final String SELECT_PROPOSAL_FOR_UPDATE = SELECT_PROPOSAL + " FOR UPDATE";
  private static final String SELECT_PROPOSAL_LINES =
      "SELECT line_no, account_code, debit_minor, credit_minor, party_mid FROM coa_proposal_line"
          + " WHERE proposal_id = ? ORDER BY line_no";
  private static final String UPDATE_PROPOSAL_DECIDE =
      "UPDATE coa_proposal SET status = ?, checker_id = ?, reason = ?, posted_trans_id = ?,"
          + " decided_at = NOW() WHERE id = ?";
  private static final String SELECT_PENDING_PROPOSALS =
      "SELECT id FROM coa_proposal WHERE status = 'PENDING' ORDER BY created_at DESC";

  /** Subsidiary-ledger index: per-account, per-party balance scans (e.g. wallet balance of a user). */
  private static final String DDL_IDX_DATA_PARTY =
      "CREATE INDEX IF NOT EXISTS coa_trans_data_party_idx"
          + " ON coa_trans_data (account_code, party_mid) WHERE party_mid IS NOT NULL";

  /** Natural balance of one party on one (credit-normal) control account = Σcredit − Σdebit. */
  private static final String SELECT_PARTY_BALANCE =
      "SELECT COALESCE(SUM(credit_minor - debit_minor), 0) FROM coa_trans_data"
          + " WHERE account_code = ? AND party_mid = ?";

  /** Control account this subledger reconciles against (Wallet Balance - User). */
  static final String WALLET_CONTROL = "2110";

  // ── COA seed data ─────────────────────────────────────────────────────────────

  private static final Object[][] ACCOUNTS = {
      // code,   name,                           kind
      {"1111", "TK Vietinbank Chuyên dùng",      "ASSET"},
      {"1112", "TK Napas Clearing",               "ASSET"},
      {"1113", "TK VPBank - QR/POS",              "ASSET"},
      {"1200", "Crypto FX Buffer",                "ASSET"},
      {"1300", "Merchant Balance (Crypto)",       "ASSET"},
      {"2100", "User Payable",                    "LIABILITY"},
      {"2110", "Wallet Balance - User",           "LIABILITY"},
      {"2120", "Wallet Balance Merchant",         "LIABILITY"},
      {"2130", "Ký quỹ - Đối tác Chi hộ",         "LIABILITY"},
      {"2140", "Tiền gửi tiết kiệm",              "LIABILITY"},
      {"2200", "Settlement Pending",              "LIABILITY"},
      {"2300", "FX Payable",                      "LIABILITY"},
      {"3100", "Transit - Nạp tiền",              "TRANSIT"},
      {"3200", "Transit - Rút tiền",              "TRANSIT"},
      {"3300", "Transit - Chuyển tiền nội bộ",    "TRANSIT"},
      {"3400", "Transit - IBFT",                  "TRANSIT"},
      {"3501", "Transit - USDT Convert",          "TRANSIT"},
      {"3502", "Transit - Merchant Receive",      "TRANSIT"},
      {"3510", "Transit - FX Conversion",         "TRANSIT"},
      {"3700", "Transit - Chi hộ",                "TRANSIT"},
      {"3800", "Transit - Clearing",              "TRANSIT"},
      {"3810", "Transit - Settlement Outbound",   "TRANSIT"},
      {"3820", "Transit - MDR Holdback",          "TRANSIT"},
      {"4100", "FX Gain",                         "REVENUE"},
      {"4110", "Doanh thu Phí nạp tiền",          "REVENUE"},
      {"4120", "Doanh thu Phí rút tiền",          "REVENUE"},
      {"4130", "Doanh thu Phí chuyển tiền",       "REVENUE"},
      {"4140", "Doanh thu Phí MDR",               "REVENUE"},
      {"4150", "Doanh thu Phí Chi Lương/Chi hộ",  "REVENUE"},
      {"4170", "Lãi chênh lệch tỷ giá",           "REVENUE"},
      {"5100", "Custody Fee",                     "EXPENSE"},
      {"5200", "Chi phí lãi tiền gửi",            "EXPENSE"},
      {"5300", "Lỗ chênh lệch tỷ giá",            "EXPENSE"},
      {"4200", "FX Loss",                         "EXPENSE"},
      {"6000", "Vốn chủ sở hữu",                  "EQUITY"},
      {"6100", "Lợi nhuận giữ lại",               "EQUITY"},
  };

  /** Tài khoản ngoại tệ + vị thế FX (currency ≠ VND). {code, name, kind, ccy}. */
  private static final Object[][] FX_ACCOUNTS = {
      {"1100", "Crypto - USDT",           "ASSET",   "USDT"},
      {"1101", "Crypto - ETH",            "ASSET",   "ETH"},
      {"1102", "Crypto - BTC",            "ASSET",   "BTC"},
      {"1121", "TK ngân hàng USD",        "ASSET",   "USD"},
      {"1920", "Vị thế FX - VND",         "ASSET",   "VND"},
      {"1921", "Vị thế FX - USD",         "ASSET",   "USD"},
      {"2150", "Ví ngoại tệ User - USD",  "LIABILITY", "USD"},
      {"3500", "Transit - Crypto Receive", "TRANSIT", "USDT"},
      {"3600", "Transit - Settlement TX",  "TRANSIT", "USDT"},
  };

  /** Control account cho tiền gửi tiết kiệm (subledger = sav_account per-user). */
  static final String SAVINGS_CONTROL = "2140";
  /** Chi phí lãi tiền gửi tiết kiệm. */
  static final String SAVINGS_INTEREST_EXPENSE = "5200";
  /** Vị thế FX (giữ số dư mở khi đổi tiền). */
  static final String FX_POSITION_VND = "1920";
  static final String FX_POSITION_USD = "1921";
  /** Lãi/lỗ chênh lệch tỷ giá (khi đánh giá lại vị thế). */
  static final String FX_GAIN = "4170";
  static final String FX_LOSS = "5300";

  /** Retained-earnings account closing entries flow into. */
  private static final String RETAINED_EARNINGS = "6100";

  private static final String UPSERT_ACCOUNT =
      "INSERT INTO coa_account (code, name, kind, currency_code) VALUES (?, ?, ?, ?)"
          + " ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, kind = EXCLUDED.kind, currency_code = EXCLUDED.currency_code";

  // ── DML ───────────────────────────────────────────────────────────────────────

  private static final String SELECT_ACCOUNT_FOR_UPDATE =
      "SELECT code, name, kind, currency_code, balance_minor, version FROM coa_account WHERE code = ? FOR UPDATE";

  private static final String SELECT_ACCOUNT =
      "SELECT code, name, kind, currency_code, balance_minor, version FROM coa_account WHERE code = ?";

  private static final String UPDATE_BALANCE =
      "UPDATE coa_account SET balance_minor = balance_minor + ?, version = version + 1 WHERE code = ?";

  private static final String SELECT_REF_ID =
      "SELECT id FROM coa_trans WHERE ref_id = ?";

  private static final String INSERT_TRANS =
      "INSERT INTO coa_trans (ref_id, memo, reverses_ref) VALUES (?, ?, ?) RETURNING id, created_at";

  /** Count reversals already posted against a given original ref. */
  private static final String COUNT_REVERSALS =
      "SELECT COUNT(*) FROM coa_trans WHERE reverses_ref = ?";

  /** Revenue + expense accounts with a non-zero balance (for period close). */
  private static final String SELECT_PNL_BALANCES =
      "SELECT code, kind, balance_minor FROM coa_account"
          + " WHERE kind IN ('REVENUE','EXPENSE') AND balance_minor <> 0 ORDER BY code";

  private static final String INSERT_TRANS_DATA =
      "INSERT INTO coa_trans_data (trans_id, line_no, account_code, debit_minor, credit_minor, party_mid, currency_code)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?)";

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
          new JournalLine("2110", 0L, netUser).withParty(cmd.mid()),
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
          new JournalLine("2110", cmd.totalDebit(), 0L).withParty(cmd.mid()),
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
          new JournalLine("2110", cmd.totalDebit(), 0L).withParty(cmd.mid()),
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
      lines.add(new JournalLine("2110", 0L, cmd.amountMinor()).withParty(cmd.mid()));
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
          new JournalLine("2110", cmd.totalDebit(), 0L).withParty(cmd.mid()),
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
  public CoaTrans receiveQrPos(QrPosReceiveCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      // 4-leg entry: DR 1113 amount / CR 3500 amount / DR 5100 vpbankCost / CR 1113 vpbankCost
      // Net 1113 = amount − vpbankCost
      List<JournalLine> lines = List.of(
          new JournalLine("1113", cmd.amountMinor(), 0L),
          new JournalLine("3500", 0L, cmd.amountMinor()),
          new JournalLine("5100", cmd.vpbankCost(), 0L),
          new JournalLine("1113", 0L, cmd.vpbankCost()));

      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo()
              : "QR/POS — nhận: " + cmd.amountMinor() + " phí VPBank: " + cmd.vpbankCost());
    } catch (SQLException e) {
      throw new IllegalStateException("receiveQrPos failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans creditMerchantQrPos(QrPosCreditMerchantCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.settleRef());
      if (existing != null) return existing;

      long transitBalance = getBalance("3500");
      if (transitBalance > -cmd.amountMinor()) {
        throw new InsufficientTransitException("3500", transitBalance, cmd.amountMinor());
      }

      // DR 3500 (amount) / CR 2120 (amount)
      List<JournalLine> lines = List.of(
          new JournalLine("3500", cmd.amountMinor(), 0L),
          new JournalLine("2120", 0L, cmd.amountMinor()));

      return postJournal(lines, cmd.settleRef(),
          cmd.memo() != null ? cmd.memo()
              : "QR/POS — ghi ví merchant: " + cmd.amountMinor());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("creditMerchantQrPos failed: " + cmd.settleRef(), e);
    }
  }

  // ── Thanh Toán Bằng Ví (Wallet Payment) ──────────────────────────────────────

  @Override
  public CoaTrans initWalletPayment(WalletPaymentInitCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      // 2110 credit-normal: balance < 0 khi user có tiền
      long walletBalance = getBalance("2110");
      if (walletBalance > -cmd.amount()) {
        throw new InsufficientWalletException("2110", walletBalance, cmd.amount());
      }

      // DR 2110 / CR 3500
      List<JournalLine> lines = List.of(
          new JournalLine("2110", cmd.amount(), 0L).withParty(cmd.mid()),
          new JournalLine("3500", 0L, cmd.amount()));

      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo() : "Thanh toán ví — trừ ví user: " + cmd.amount());
    } catch (InsufficientWalletException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("initWalletPayment failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans settleWalletPayment(WalletPaymentSettleCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.settleRef());
      if (existing != null) return existing;

      long transitBalance = getBalance("3500");
      if (transitBalance > -cmd.amount()) {
        throw new InsufficientTransitException("3500", transitBalance, cmd.amount());
      }

      // DR 3500 / CR 2120
      List<JournalLine> lines = List.of(
          new JournalLine("3500", cmd.amount(), 0L),
          new JournalLine("2120", 0L, cmd.amount()));

      return postJournal(lines, cmd.settleRef(),
          cmd.memo() != null ? cmd.memo() : "Thanh toán ví — ghi ví merchant: " + cmd.amount());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("settleWalletPayment failed: " + cmd.settleRef(), e);
    }
  }

  // ── Chi Hộ (Disbursement on Behalf) ──────────────────────────────────────────

  @Override
  public CoaTrans prefundDisbursement(DisbursementPrefundCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.prefundRef());
      if (existing != null) return existing;

      // DR 1111 / CR 2130
      List<JournalLine> lines = List.of(
          new JournalLine("1111", cmd.amount(), 0L),
          new JournalLine("2130", 0L, cmd.amount()));

      return postJournal(lines, cmd.prefundRef(),
          cmd.memo() != null ? cmd.memo() : "Chi hộ — pre-fund ký quỹ: " + cmd.amount());
    } catch (SQLException e) {
      throw new IllegalStateException("prefundDisbursement failed: " + cmd.prefundRef(), e);
    }
  }

  @Override
  public CoaTrans initDisbursement(DisbursementInitCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      // 2130 credit-normal: balance < 0 khi đối tác còn ký quỹ
      long escrowBalance = getBalance("2130");
      if (escrowBalance > -cmd.totalDebit()) {
        throw new InsufficientEscrowException("2130", escrowBalance, cmd.totalDebit());
      }

      // DR 2130 (amount + fee) / CR 3700 (amount + fee)
      List<JournalLine> lines = List.of(
          new JournalLine("2130", cmd.totalDebit(), 0L),
          new JournalLine("3700", 0L, cmd.totalDebit()));

      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo()
              : "Chi hộ — trừ ký quỹ: " + cmd.amount() + " phí: " + cmd.fee());
    } catch (InsufficientEscrowException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("initDisbursement failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans settleDisbursement(DisbursementSettleCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.settleRef());
      if (existing != null) return existing;

      long transitBalance = getBalance("3700");
      if (transitBalance > -cmd.totalTransitRelease()) {
        throw new InsufficientTransitException("3700", transitBalance, cmd.totalTransitRelease());
      }

      // DR 3700 (amount+fee) / DR 5100 (napasCost) / CR 4150 (fee) / CR 1112 (amount+napasCost)
      // CR 4150 skipped when fee = 0 (3 legs)
      List<JournalLine> lines = new java.util.ArrayList<>();
      lines.add(new JournalLine("3700", cmd.totalTransitRelease(), 0L));
      lines.add(new JournalLine("5100", cmd.napasCost(), 0L));
      if (cmd.fee() > 0) {
        lines.add(new JournalLine("4150", 0L, cmd.fee()));
      }
      lines.add(new JournalLine("1112", 0L, cmd.napasOutflow()));

      return postJournal(lines, cmd.settleRef(),
          cmd.memo() != null ? cmd.memo()
              : "Chi hộ — Napas gửi: " + cmd.amount()
              + " phí: " + cmd.fee() + " Napas cost: " + cmd.napasCost());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("settleDisbursement failed: " + cmd.settleRef(), e);
    }
  }

  // ── Chi Lương (Payroll Disbursement) ─────────────────────────────────────────

  @Override
  public CoaTrans initPayroll(PayrollInitCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      // 2120 credit-normal: balance < 0 when merchant has money
      long merchantBalance = getBalance("2120");
      if (merchantBalance > -cmd.totalLock()) {
        throw new InsufficientWalletException("2120", merchantBalance, cmd.totalLock());
      }

      // DR 2120 (amount + totalFee) / CR 3600 (amount + totalFee)
      List<JournalLine> lines = List.of(
          new JournalLine("2120", cmd.totalLock(), 0L),
          new JournalLine("3600", 0L, cmd.totalLock()));

      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo()
              : "Chi lương — lock: " + cmd.amount() + " phí: " + cmd.totalFee()
              + " (" + cmd.employeeCount() + " NV)");
    } catch (InsufficientWalletException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("initPayroll failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans disbursePayroll(PayrollDisburseCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.disburseRef());
      if (existing != null) return existing;

      long transitBalance = getBalance("3600");
      if (transitBalance > -cmd.totalTransitRelease()) {
        throw new InsufficientTransitException("3600", transitBalance, cmd.totalTransitRelease());
      }

      // DR 3600 (amount+totalFee) / DR 5100 (napasCost) / CR 4150 (totalFee) / CR 1112 (amount+napasCost)
      // CR 4150 skipped when totalFee = 0 (3 legs)
      List<JournalLine> lines = new java.util.ArrayList<>();
      lines.add(new JournalLine("3600", cmd.totalTransitRelease(), 0L));
      lines.add(new JournalLine("5100", cmd.napasCost(), 0L));
      if (cmd.totalFee() > 0) {
        lines.add(new JournalLine("4150", 0L, cmd.totalFee()));
      }
      lines.add(new JournalLine("1112", 0L, cmd.napasOutflow()));

      return postJournal(lines, cmd.disburseRef(),
          cmd.memo() != null ? cmd.memo()
              : "Chi lương — Napas bulk: " + cmd.amount()
              + " phí: " + cmd.totalFee() + " Napas cost: " + cmd.napasCost());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("disbursePayroll failed: " + cmd.disburseRef(), e);
    }
  }

  // ── EOD Settlement & Clearing ─────────────────────────────────────────────────

  @Override
  public CoaTrans eodInitClearing(EodClearingInitCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.clearingRef());
      if (existing != null) return existing;

      // 2120 credit-normal: balance < 0 when merchant has money
      long merchantBalance = getBalance("2120");
      if (merchantBalance > -cmd.totalAmount()) {
        throw new InsufficientWalletException("2120", merchantBalance, cmd.totalAmount());
      }

      // DR 2120 / CR 3800
      List<JournalLine> lines = List.of(
          new JournalLine("2120", cmd.totalAmount(), 0L),
          new JournalLine("3800", 0L, cmd.totalAmount()));

      return postJournal(lines, cmd.clearingRef(),
          cmd.memo() != null ? cmd.memo()
              : "EOD Clearing — lock merchant: " + cmd.totalAmount());
    } catch (InsufficientWalletException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("eodInitClearing failed: " + cmd.clearingRef(), e);
    }
  }

  @Override
  public CoaTrans eodReconcile(EodReconcileCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.reconcileRef());
      if (existing != null) return existing;

      long clearingBalance = getBalance("3800");
      if (clearingBalance > -cmd.totalAmount()) {
        throw new InsufficientTransitException("3800", clearingBalance, cmd.totalAmount());
      }

      // DR 3800 total / CR 3820 mdr / CR 3810 net
      List<JournalLine> lines = new java.util.ArrayList<>();
      lines.add(new JournalLine("3800", cmd.totalAmount(), 0L));
      lines.add(new JournalLine("3820", 0L, cmd.mdrAmount()));
      lines.add(new JournalLine("3810", 0L, cmd.netAmount()));

      return postJournal(lines, cmd.reconcileRef(),
          cmd.memo() != null ? cmd.memo()
              : "EOD Reconcile — MDR: " + cmd.mdrAmount() + " net: " + cmd.netAmount());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("eodReconcile failed: " + cmd.reconcileRef(), e);
    }
  }

  @Override
  public CoaTrans eodRecognizeMdr(EodRecognizeMdrCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.mdrRef());
      if (existing != null) return existing;

      long mdrHoldback = getBalance("3820");
      if (mdrHoldback > -cmd.mdrAmount()) {
        throw new InsufficientTransitException("3820", mdrHoldback, cmd.mdrAmount());
      }

      // DR 3820 / CR 4140
      List<JournalLine> lines = List.of(
          new JournalLine("3820", cmd.mdrAmount(), 0L),
          new JournalLine("4140", 0L, cmd.mdrAmount()));

      return postJournal(lines, cmd.mdrRef(),
          cmd.memo() != null ? cmd.memo() : "EOD MDR revenue: " + cmd.mdrAmount());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("eodRecognizeMdr failed: " + cmd.mdrRef(), e);
    }
  }

  @Override
  public CoaTrans eodSettleOutbound(EodSettleOutboundCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.settleRef());
      if (existing != null) return existing;

      long settlementBalance = getBalance("3810");
      if (settlementBalance > -cmd.netAmount()) {
        throw new InsufficientTransitException("3810", settlementBalance, cmd.netAmount());
      }

      // DR 3810 net / DR 5100 napasCost / CR 1112 (net + napasCost)
      List<JournalLine> lines = new java.util.ArrayList<>();
      lines.add(new JournalLine("3810", cmd.netAmount(), 0L));
      lines.add(new JournalLine("5100", cmd.napasCost(), 0L));
      lines.add(new JournalLine("1112", 0L, cmd.napasOutflow()));

      return postJournal(lines, cmd.settleRef(),
          cmd.memo() != null ? cmd.memo()
              : "EOD Settlement Outbound — net: " + cmd.netAmount() + " Napas: " + cmd.napasCost());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("eodSettleOutbound failed: " + cmd.settleRef(), e);
    }
  }

  @Override
  public CoaTrans eodRejectSettlement(EodRejectSettlementCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.rejectRef());
      if (existing != null) return existing;

      // Validate: 3810 và 3820 phải còn đủ credit chưa được settle
      long settlementBalance = getBalance("3810");
      if (settlementBalance > -cmd.netAmount()) {
        throw new InsufficientTransitException("3810", settlementBalance, cmd.netAmount());
      }
      long mdrHoldback = getBalance("3820");
      if (mdrHoldback > -cmd.mdrAmount()) {
        throw new InsufficientTransitException("3820", mdrHoldback, cmd.mdrAmount());
      }

      // DR 3810 net / DR 3820 mdr / CR 2120 total
      List<JournalLine> lines = new java.util.ArrayList<>();
      lines.add(new JournalLine("3810", cmd.netAmount(), 0L));
      lines.add(new JournalLine("3820", cmd.mdrAmount(), 0L));
      lines.add(new JournalLine("2120", 0L, cmd.totalRefund()));

      return postJournal(lines, cmd.rejectRef(),
          cmd.memo() != null ? cmd.memo()
              : "EOD Reject — hoàn về merchant: " + cmd.totalRefund());
    } catch (InsufficientTransitException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("eodRejectSettlement failed: " + cmd.rejectRef(), e);
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
  public long walletBalance(long mid) {
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_PARTY_BALANCE)) {
        ps.setString(1, WALLET_CONTROL);
        ps.setLong(2, mid);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getLong(1); // Σ(credit − debit) = natural money the user holds
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("walletBalance failed: mid=" + mid, e);
    }
  }

  @Override
  public CoaTrans fxExchange(FxExchangeCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      // VND leg (balanced trong VND) + USD leg (balanced trong USD), bắc cầu qua vị thế FX.
      // buyUsd=true: chi VND (CR 1111), nhận USD (DR 1121). buyUsd=false: ngược lại.
      List<JournalLine> lines = new ArrayList<>();
      if (cmd.buyUsd()) {
        lines.add(new JournalLine(FX_POSITION_VND, cmd.vndAmount(), 0L).inCurrency("VND"));
        lines.add(new JournalLine("1111", 0L, cmd.vndAmount()).inCurrency("VND"));
        lines.add(new JournalLine("1121", cmd.usdAmount(), 0L).inCurrency("USD"));
        lines.add(new JournalLine(FX_POSITION_USD, 0L, cmd.usdAmount()).inCurrency("USD"));
      } else {
        lines.add(new JournalLine("1111", cmd.vndAmount(), 0L).inCurrency("VND"));
        lines.add(new JournalLine(FX_POSITION_VND, 0L, cmd.vndAmount()).inCurrency("VND"));
        lines.add(new JournalLine(FX_POSITION_USD, cmd.usdAmount(), 0L).inCurrency("USD"));
        lines.add(new JournalLine("1121", 0L, cmd.usdAmount()).inCurrency("USD"));
      }
      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo()
              : "FX " + (cmd.buyUsd() ? "mua" : "bán") + " USD: " + cmd.vndAmount()
              + " VND ↔ " + cmd.usdAmount() + " USD");
    } catch (SQLException e) {
      throw new IllegalStateException("fxExchange failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans fxRevalue(FxRevalueCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;

      // Vị thế USD nắm giữ (1921 credit-normal: balance âm khi long USD).
      long usdPosMinor = -getBalance(FX_POSITION_USD); // dương = USD đang giữ (minor, 2 chữ số thập phân)
      if (usdPosMinor == 0) throw new NothingToRevalueException("no open FX position (1921 = 0)");

      long current1920 = getBalance(FX_POSITION_VND);
      // Giá trị thị trường VND của vị thế USD = usd × rate (usd có 2 chữ số thập phân).
      long targetVnd = Math.multiplyExact(usdPosMinor, cmd.rateVndPerUsd()) / 100;
      long delta = targetVnd - current1920;
      if (delta == 0) throw new NothingToRevalueException("rate unchanged — nothing to revalue");

      List<JournalLine> lines;
      if (delta > 0) {
        // Lãi: nâng vị thế VND lên giá thị trường, ghi nhận doanh thu chênh lệch tỷ giá.
        lines = List.of(
            new JournalLine(FX_POSITION_VND, delta, 0L),
            new JournalLine(FX_GAIN, 0L, delta));
      } else {
        // Lỗ: giảm vị thế VND, ghi nhận chi phí chênh lệch tỷ giá.
        lines = List.of(
            new JournalLine(FX_LOSS, -delta, 0L),
            new JournalLine(FX_POSITION_VND, 0L, -delta));
      }
      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo()
              : "FX revalue @" + cmd.rateVndPerUsd() + ": " + (delta > 0 ? "lãi " : "lỗ ")
              + Math.abs(delta) + "đ");
    } catch (NothingToRevalueException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("fxRevalue failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans mirrorWalletTransfer(long payerAcct, long payeeAcct, long amount, String ref, String memo) {
    if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
    if (payerAcct == payeeAcct) throw new IllegalArgumentException("payer == payee");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(ref);
      if (existing != null) return existing;
      // CR payee TRƯỚC, DR payer SAU: cùng tác động lên 2110, net 0, nhưng đặt CR trước để
      // số dư tổng 2110 (credit-normal, luôn ≤ 0) không bao giờ tạm vượt 0 → không vướng CHECK chống âm.
      List<JournalLine> lines = List.of(
          new JournalLine(WALLET_CONTROL, 0L, amount).withParty(payeeAcct),
          new JournalLine(WALLET_CONTROL, amount, 0L).withParty(payerAcct));
      return postJournal(lines, ref,
          memo != null ? memo : "Mirror ví: " + payerAcct + "→" + payeeAcct + " " + amount);
    } catch (SQLException e) {
      throw new IllegalStateException("mirrorWalletTransfer failed: " + ref, e);
    }
  }

  @Override
  public long savingsBalance(long mid) {
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_PARTY_BALANCE)) {
        ps.setString(1, SAVINGS_CONTROL);
        ps.setLong(2, mid);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getLong(1);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("savingsBalance failed: mid=" + mid, e);
    }
  }

  @Override
  public CoaTrans savingsDeposit(SavingsDepositCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;
      if (walletBalance(cmd.mid()) < cmd.amount()) {
        throw new InsufficientWalletException(WALLET_CONTROL, walletBalance(cmd.mid()), cmd.amount());
      }
      // DR 2110(mid) / CR 2140(mid) — tái phân loại nợ ví → tiết kiệm
      List<JournalLine> lines = List.of(
          new JournalLine(WALLET_CONTROL, cmd.amount(), 0L).withParty(cmd.mid()),
          new JournalLine(SAVINGS_CONTROL, 0L, cmd.amount()).withParty(cmd.mid()));
      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo() : "Gửi tiết kiệm (ví→TK): " + cmd.amount());
    } catch (InsufficientWalletException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("savingsDeposit failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans savingsWithdraw(SavingsWithdrawCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;
      if (savingsBalance(cmd.mid()) < cmd.amount()) {
        throw new InsufficientWalletException(SAVINGS_CONTROL, savingsBalance(cmd.mid()), cmd.amount());
      }
      // DR 2140(mid) / CR 2110(mid)
      List<JournalLine> lines = List.of(
          new JournalLine(SAVINGS_CONTROL, cmd.amount(), 0L).withParty(cmd.mid()),
          new JournalLine(WALLET_CONTROL, 0L, cmd.amount()).withParty(cmd.mid()));
      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo() : "Rút tiết kiệm (TK→ví): " + cmd.amount());
    } catch (InsufficientWalletException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("savingsWithdraw failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans savingsInterest(SavingsInterestCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.requestRef());
      if (existing != null) return existing;
      // DR 5200 (chi phí lãi) / CR 2140(mid)
      List<JournalLine> lines = List.of(
          new JournalLine(SAVINGS_INTEREST_EXPENSE, cmd.amount(), 0L),
          new JournalLine(SAVINGS_CONTROL, 0L, cmd.amount()).withParty(cmd.mid()));
      return postJournal(lines, cmd.requestRef(),
          cmd.memo() != null ? cmd.memo() : "Lãi tiền gửi tiết kiệm: " + cmd.amount());
    } catch (SQLException e) {
      throw new IllegalStateException("savingsInterest failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans postCryptoDeposit(dev.nivic.coa.cmd.CryptoDepositCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      CoaTrans existing = findByRefId(cmd.refId());
      if (existing != null) return existing;
      // Step 1: DR 1100 (Crypto Received) / CR 3500 (Transit - crypto receive)
      List<JournalLine> lines = List.of(
          new JournalLine("1100", cmd.amountMinor(), 0L).inCurrency(cmd.currency()),
          new JournalLine("3500", 0L, cmd.amountMinor()).inCurrency(cmd.currency()));
      String memo = String.format("Crypto deposit: %s tx=%s block=%d",
          cmd.currency(), cmd.txHash(), cmd.blockHeight());
      return postJournal(lines, cmd.refId(), memo);
    } catch (SQLException e) {
      throw new IllegalStateException("postCryptoDeposit failed: " + cmd.refId(), e);
    }
  }

  @Override
  public CoaTrans findTrans(long transId) {
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_TRANS_WITH_LINES)) {
        ps.setLong(1, transId);
        try (ResultSet rs = ps.executeQuery()) {
          return collectTrans(rs);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("findTrans failed: " + transId, e);
    }
  }

  @Override
  public CoaTrans findTransByRefId(String refId) {
    Objects.requireNonNull(refId, "refId");
    try {
      ensureSchema();
      return findByRefId(refId);
    } catch (SQLException e) {
      throw new IllegalStateException("findTransByRefId failed: " + refId, e);
    }
  }

  @Override
  public CoaTrans closePeriod(PeriodCloseCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      // Idempotent retry.
      CoaTrans existing = findByRefId(cmd.closeRef());
      if (existing != null) return existing;

      // Read all revenue/expense balances.
      List<JournalLine> lines = new ArrayList<>();
      long totalRevenue = 0, totalExpense = 0;
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_PNL_BALANCES);
          ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          String code = rs.getString("code");
          String kind = rs.getString("kind");
          long bal = rs.getLong("balance_minor");
          if ("REVENUE".equals(kind)) {
            long natural = -bal;                       // credit-normal
            lines.add(new JournalLine(code, natural, 0L)); // DR revenue → 0
            totalRevenue += natural;
          } else { // EXPENSE
            long natural = bal;                        // debit-normal
            lines.add(new JournalLine(code, 0L, natural)); // CR expense → 0
            totalExpense += natural;
          }
        }
      }
      if (lines.isEmpty()) throw new NothingToCloseException();

      // Balance into retained earnings (6100): profit → CR, loss → DR.
      long net = totalRevenue - totalExpense;
      if (net > 0)      lines.add(new JournalLine(RETAINED_EARNINGS, 0L, net));
      else if (net < 0) lines.add(new JournalLine(RETAINED_EARNINGS, -net, 0L));

      String memo = cmd.memo() != null ? cmd.memo()
          : "Khoá sổ — lãi/lỗ thuần: " + (totalRevenue - totalExpense);
      return postJournal(lines, cmd.closeRef(), memo);
    } catch (NothingToCloseException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("closePeriod failed: " + cmd.closeRef(), e);
    }
  }

  // ── Maker-checker ─────────────────────────────────────────────────────────────

  @Override
  public Proposal propose(ProposeJournalCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    long dr = cmd.lines().stream().mapToLong(ProposeJournalCmd.EntryLine::debitMinor).sum();
    long cr = cmd.lines().stream().mapToLong(ProposeJournalCmd.EntryLine::creditMinor).sum();
    if (dr != cr) throw new IllegalArgumentException("proposal not balanced: DR=" + dr + " CR=" + cr);
    try {
      ensureSchema();
      Long existing = proposalIdByRef(cmd.requestRef());
      if (existing != null) return findProposal(existing);

      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          long id;
          try (PreparedStatement ps = c.prepareStatement(INSERT_PROPOSAL)) {
            ps.setString(1, cmd.requestRef());
            if (cmd.memo() != null) ps.setString(2, cmd.memo()); else ps.setNull(2, Types.VARCHAR);
            ps.setString(3, cmd.makerId());
            try (ResultSet rs = ps.executeQuery()) { rs.next(); id = rs.getLong(1); }
          }
          int lineNo = 1;
          for (ProposeJournalCmd.EntryLine l : cmd.lines()) {
            try (PreparedStatement ps = c.prepareStatement(INSERT_PROPOSAL_LINE)) {
              ps.setObject(1, id);
              ps.setInt(2, lineNo++);
              ps.setString(3, l.accountCode());
              ps.setLong(4, l.debitMinor());
              ps.setLong(5, l.creditMinor());
              if (l.partyMid() != null) ps.setLong(6, l.partyMid()); else ps.setNull(6, Types.BIGINT);
              ps.executeUpdate();
            }
          }
          c.commit();
          return findProposal(id);
        } catch (SQLException | RuntimeException e) {
          try { c.rollback(); } catch (SQLException ignored) {}
          throw e;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("propose failed: " + cmd.requestRef(), e);
    }
  }

  @Override
  public CoaTrans approve(long proposalId, String checkerId) {
    Objects.requireNonNull(checkerId, "checkerId");
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          Proposal p = lockProposal(c, proposalId);
          if (p == null) throw new ProposalNotFoundException(proposalId);
          if (p.status() != ProposalStatus.PENDING)
            throw new ProposalStateException("proposal already " + p.status() + ": " + proposalId);
          if (checkerId.equals(p.makerId()))
            throw new SegregationOfDutiesException(checkerId);

          List<JournalLine> lines = new ArrayList<>(p.lines().size());
          for (Proposal.Line l : p.lines()) {
            lines.add(new JournalLine(l.accountCode(), l.debitMinor(), l.creditMinor(), l.partyMid()));
          }
          CoaTrans posted = postJournalTx(c, lines, p.refId(), p.memo(), null);
          try (PreparedStatement ps = c.prepareStatement(UPDATE_PROPOSAL_DECIDE)) {
            ps.setString(1, ProposalStatus.APPROVED.name());
            ps.setString(2, checkerId);
            ps.setNull(3, Types.VARCHAR);
            ps.setObject(4, posted.id());
            ps.setObject(5, proposalId);
            ps.executeUpdate();
          }
          c.commit();
          return posted;
        } catch (SQLException e) {
          try { c.rollback(); } catch (SQLException ignored) {}
          if ("23514".equals(e.getSQLState()) && String.valueOf(e.getMessage()).contains(BALANCE_CHECK)) {
            throw new NegativeBalanceException("balance-sign rule violated on approve: " + e.getMessage());
          }
          throw e;
        } catch (RuntimeException e) {
          try { c.rollback(); } catch (SQLException ignored) {}
          throw e;
        }
      }
    } catch (ProposalNotFoundException | ProposalStateException | SegregationOfDutiesException
        | NegativeBalanceException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("approve failed: " + proposalId, e);
    }
  }

  @Override
  public Proposal reject(long proposalId, String checkerId, String reason) {
    Objects.requireNonNull(checkerId, "checkerId");
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          Proposal p = lockProposal(c, proposalId);
          if (p == null) throw new ProposalNotFoundException(proposalId);
          if (p.status() != ProposalStatus.PENDING)
            throw new ProposalStateException("proposal already " + p.status() + ": " + proposalId);
          if (checkerId.equals(p.makerId()))
            throw new SegregationOfDutiesException(checkerId);

          try (PreparedStatement ps = c.prepareStatement(UPDATE_PROPOSAL_DECIDE)) {
            ps.setString(1, ProposalStatus.REJECTED.name());
            ps.setString(2, checkerId);
            if (reason != null) ps.setString(3, reason); else ps.setNull(3, Types.VARCHAR);
            ps.setNull(4, Types.OTHER);
            ps.setObject(5, proposalId);
            ps.executeUpdate();
          }
          c.commit();
          return findProposal(proposalId);
        } catch (SQLException | RuntimeException e) {
          try { c.rollback(); } catch (SQLException ignored) {}
          throw e;
        }
      }
    } catch (ProposalNotFoundException | ProposalStateException | SegregationOfDutiesException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("reject failed: " + proposalId, e);
    }
  }

  @Override
  public Proposal findProposal(long proposalId) {
    try {
      ensureSchema();
      try (Connection c = dataSource.getConnection()) {
        Proposal p = readProposal(c, proposalId, false);
        return p;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("findProposal failed: " + proposalId, e);
    }
  }

  @Override
  public List<Proposal> pendingProposals() {
    try {
      ensureSchema();
      List<Proposal> out = new ArrayList<>();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_PENDING_PROPOSALS);
          ResultSet rs = ps.executeQuery()) {
        List<Long> ids = new ArrayList<>();
        while (rs.next()) ids.add(rs.getLong(1));
        for (long id : ids) out.add(readProposal(c, id, false));
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException("pendingProposals failed", e);
    }
  }

  private Long proposalIdByRef(String ref) throws SQLException {
    if (ref == null) return null;
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_PROPOSAL_ID_BY_REF)) {
      ps.setString(1, ref);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : null;
      }
    }
  }

  private Proposal lockProposal(Connection c, long id) throws SQLException {
    return readProposalRow(c, id, true);
  }

  private Proposal readProposal(Connection c, long id, boolean forUpdate) throws SQLException {
    return readProposalRow(c, id, forUpdate);
  }

  private Proposal readProposalRow(Connection c, long id, boolean forUpdate) throws SQLException {
    Proposal header;
    try (PreparedStatement ps = c.prepareStatement(forUpdate ? SELECT_PROPOSAL_FOR_UPDATE : SELECT_PROPOSAL)) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return null;
        header = new Proposal(
            rs.getLong("id"),
            rs.getString("ref_id"),
            rs.getString("memo"),
            rs.getString("maker_id"),
            ProposalStatus.valueOf(rs.getString("status")),
            rs.getString("checker_id"),
            rs.getString("reason"),
            (Long) rs.getObject("posted_trans_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
            List.of());
      }
    }
    List<Proposal.Line> lines = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(SELECT_PROPOSAL_LINES)) {
      ps.setLong(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          lines.add(new Proposal.Line(rs.getInt("line_no"), rs.getString("account_code"),
              rs.getLong("debit_minor"), rs.getLong("credit_minor"), (Long) rs.getObject("party_mid")));
        }
      }
    }
    return new Proposal(header.id(), header.refId(), header.memo(), header.makerId(), header.status(),
        header.checkerId(), header.reason(), header.postedTransId(), header.createdAt(),
        header.decidedAt(), lines);
  }

  @Override
  public CoaTrans reverse(ReversalCmd cmd) {
    Objects.requireNonNull(cmd, "cmd");
    try {
      ensureSchema();
      // Idempotent retry: same reversalRef already posted → return it.
      CoaTrans existing = findByRefId(cmd.reversalRef());
      if (existing != null) return existing;

      // Load the original transaction with its bút toán lines.
      CoaTrans original = findByRefId(cmd.originalRef());
      if (original == null) throw new TransactionNotFoundException(cmd.originalRef());

      // Guard: already reversed by a different reversalRef.
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(COUNT_REVERSALS)) {
        ps.setString(1, cmd.originalRef());
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          if (rs.getLong(1) > 0) throw new AlreadyReversedException(cmd.originalRef());
        }
      }

      // Build inverse legs: swap debit ↔ credit for every line.
      List<JournalLine> inverse = new ArrayList<>(original.lines().size());
      for (CoaTransLine l : original.lines()) {
        inverse.add(new JournalLine(l.accountCode(), l.creditMinor(), l.debitMinor()));
      }

      String memo = cmd.memo() != null ? cmd.memo()
          : "Hoàn tiền giao dịch: " + cmd.originalRef();
      return postJournal(inverse, cmd.reversalRef(), memo, cmd.originalRef());
    } catch (TransactionNotFoundException | AlreadyReversedException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException("reverse failed: " + cmd.originalRef(), e);
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
    return postJournal(lines, refId, memo, null);
  }

  private CoaTrans postJournal(List<JournalLine> lines, String refId, String memo, String reversesRef)
      throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        CoaTrans t = postJournalTx(c, lines, refId, memo, reversesRef);
        c.commit();
        return t;
      } catch (SQLException e) {
        try { c.rollback(); } catch (SQLException ignored) {}
        if ("23514".equals(e.getSQLState()) && String.valueOf(e.getMessage()).contains(BALANCE_CHECK)) {
          throw new dev.nivic.coa.error.NegativeBalanceException(
              "balance-sign rule violated (ref=" + refId + "): " + e.getMessage());
        }
        throw e;
      } catch (RuntimeException e) {
        try { c.rollback(); } catch (SQLException ignored) {}
        throw e;
      }
    }
  }

  /**
   * Posts a balanced journal within an existing transaction (no commit/rollback — caller manages).
   * Reused by {@link #postJournal} and by maker-checker approval so posting + workflow update
   * commit atomically.
   */
  private CoaTrans postJournalTx(Connection c, List<JournalLine> lines, String refId,
      String memo, String reversesRef) throws SQLException {
    // Balance per currency (multi-currency: Σdebit = Σcredit within each currency).
    Map<String, long[]> perCcy = new LinkedHashMap<>(); // ccy → [dr, cr]
    for (JournalLine l : lines) {
      long[] dc = perCcy.computeIfAbsent(l.currency(), k -> new long[2]);
      dc[0] += l.debitMinor();
      dc[1] += l.creditMinor();
    }
    for (var e : perCcy.entrySet()) {
      if (e.getValue()[0] != e.getValue()[1]) {
        throw new IllegalArgumentException("journal not balanced in " + e.getKey()
            + ": DR=" + e.getValue()[0] + " CR=" + e.getValue()[1] + " ref=" + refId);
      }
    }
    // Lock accounts in code order to prevent deadlocks.
    List<String> codesOrdered = lines.stream()
        .map(JournalLine::accountCode).distinct().sorted().toList();
    Map<String, CoaAccount> locked = new LinkedHashMap<>();
    for (String code : codesOrdered) locked.put(code, lockAccount(c, code));
    // Currency-match: a line's currency must equal its account's currency (mono-currency accounts).
    for (JournalLine l : lines) {
      CoaAccount a = locked.get(l.accountCode());
      if (!a.currencyCode().equals(l.currency())) {
        throw new IllegalArgumentException("currency mismatch: account " + l.accountCode()
            + " is " + a.currencyCode() + " but line is " + l.currency());
      }
    }

    long transId;
    Instant createdAt;
    try (PreparedStatement ps = c.prepareStatement(INSERT_TRANS)) {
      if (refId != null)       ps.setString(1, refId);       else ps.setNull(1, Types.VARCHAR);
      if (memo  != null)       ps.setString(2, memo);        else ps.setNull(2, Types.VARCHAR);
      if (reversesRef != null) ps.setString(3, reversesRef); else ps.setNull(3, Types.VARCHAR);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        transId   = rs.getLong(1);
        createdAt = rs.getTimestamp(2).toInstant();
      }
    }
    List<CoaTransLine> resultLines = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      JournalLine l = lines.get(i);
      int lineNo = i + 1;
      try (PreparedStatement ps = c.prepareStatement(INSERT_TRANS_DATA)) {
        ps.setLong(1, transId);
        ps.setInt(2, lineNo);
        ps.setString(3, l.accountCode());
        ps.setLong(4, l.debitMinor());
        ps.setLong(5, l.creditMinor());
        if (l.partyMid() != null) ps.setLong(6, l.partyMid()); else ps.setNull(6, Types.BIGINT);
        ps.setString(7, l.currency());
        ps.executeUpdate();
      }
      try (PreparedStatement ps = c.prepareStatement(UPDATE_BALANCE)) {
        ps.setLong(1, l.netDelta());
        ps.setString(2, l.accountCode());
        ps.executeUpdate();
      }
      CoaAccount acct = locked.get(l.accountCode());
      resultLines.add(new CoaTransLine(lineNo, l.accountCode(), acct.name(),
          l.debitMinor(), l.creditMinor(), l.currency()));
    }
    return new CoaTrans(transId, refId, memo, createdAt, resultLines);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private void ensureSchema() throws SQLException {
    if (schemaEnsured) return;
    synchronized (this) {
      if (schemaEnsured) return;
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_ACCOUNT);
        st.execute(DDL_ACCOUNT_CCY_ALTER);
        st.execute(DDL_ACCOUNT_CCY_EXTEND);
        st.execute(DDL_TRANS);
        st.execute(DDL_TRANS_ALTER);
        st.execute(DDL_TRANS_DATA);
        st.execute(DDL_TRANS_DATA_CCY_EXTEND);
        st.execute(DDL_TRANS_DATA_ALTER);
        st.execute(DDL_IDX_DATA_ACCOUNT);
        st.execute(DDL_IDX_DATA_PARTY);
        st.execute(DDL_PROPOSAL);
        st.execute(DDL_PROPOSAL_LINE);
        st.execute(DDL_IDX_PROPOSAL_STATUS);
      }
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(UPSERT_ACCOUNT)) {
        for (Object[] row : ACCOUNTS) {
          ps.setString(1, (String) row[0]);
          ps.setString(2, (String) row[1]);
          ps.setString(3, (String) row[2]);
          ps.setString(4, "VND");
          ps.addBatch();
        }
        for (Object[] row : FX_ACCOUNTS) {
          ps.setString(1, (String) row[0]);
          ps.setString(2, (String) row[1]);
          ps.setString(3, (String) row[2]);
          ps.setString(4, (String) row[3]);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      // Frozen rule: balance-sign CHECK. Idempotent — ignore "already exists" (42710).
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_ACCOUNT_CHECK);
      } catch (SQLException e) {
        if (!"42710".equals(e.getSQLState())) throw e;
      }
      // Frozen rules: append-only + deferred double-entry triggers (idempotent).
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_FN_FORBID);
        st.execute(DDL_FN_BALANCED);
        for (String ddl : DDL_TRIGGERS) st.execute(ddl);
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
        long id = rs.getLong(1);
        return findTrans(id);
      }
    }
  }

  private static CoaTrans collectTrans(ResultSet rs) throws SQLException {
    long id = 0;
    String refId = null, memo = null;
    Instant createdAt = null;
    List<CoaTransLine> lines = new ArrayList<>();
    boolean hasData = false;
    while (rs.next()) {
      if (!hasData) {
        id        = rs.getLong(1);
        refId     = rs.getString(2);
        memo      = rs.getString(3);
        createdAt = rs.getTimestamp(4).toInstant();
        hasData = true;
      }
      lines.add(new CoaTransLine(
          rs.getInt(5),      // line_no
          rs.getString(6),   // account_code
          rs.getString(7),   // account name
          rs.getLong(8),     // debit_minor
          rs.getLong(9),     // credit_minor
          "VND"));
    }
    if (!hasData) return null;
    return new CoaTrans(id, refId, memo, createdAt, lines);
  }

  private static CoaAccount accountFromRs(ResultSet rs) throws SQLException {
    return new CoaAccount(
        rs.getString(1),                           // code
        rs.getString(2),                           // name
        CoaAccountKind.valueOf(rs.getString(3)),   // kind
        rs.getString(4),                           // currency_code
        rs.getLong(5),                             // balance_minor
        rs.getLong(6));                            // version
  }

  /** Internal line record used only within postJournal. */
  private record JournalLine(String accountCode, long debitMinor, long creditMinor,
      Long partyMid, String currency) {
    /** Default: no party, VND. */
    JournalLine(String accountCode, long debitMinor, long creditMinor) {
      this(accountCode, debitMinor, creditMinor, null, "VND");
    }
    /** With party, VND. */
    JournalLine(String accountCode, long debitMinor, long creditMinor, Long partyMid) {
      this(accountCode, debitMinor, creditMinor, partyMid, "VND");
    }
    long netDelta() { return debitMinor - creditMinor; }
    JournalLine withParty(Long mid) {
      return new JournalLine(accountCode, debitMinor, creditMinor, mid, currency);
    }
    JournalLine inCurrency(String ccy) {
      return new JournalLine(accountCode, debitMinor, creditMinor, partyMid, ccy);
    }
  }
}
