package dev.nivic.coa;

import dev.nivic.coa.error.*;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * JDBC implementation of AccountManager.
 * Manages account metadata, hierarchy, status, periods.
 */
public class JdbcAccountManager implements AccountManager {

  private final String dataSourceUrl;
  private final String user;
  private final String password;

  public JdbcAccountManager(String url, String user, String password) {
    this.dataSourceUrl = url;
    this.user = user;
    this.password = password;
  }

  @Override
  public Optional<CoaAccountExt> findByCode(String code) {
    String sql = """
        SELECT code, name, kind, currency_code, balance_minor, version,
               parent_code, status, description, created_at, updated_at, created_by, updated_by
        FROM coa_account_ext WHERE code = ? AND status != 'ARCHIVED'
        """;
    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, code);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapResultToAccount(rs));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find account: " + code, e);
    }
    return Optional.empty();
  }

  @Override
  public Optional<CoaAccountExt> findByName(String name) {
    String sql = """
        SELECT code, name, kind, currency_code, balance_minor, version,
               parent_code, status, description, created_at, updated_at, created_by, updated_by
        FROM coa_account_ext WHERE name = ? AND status != 'ARCHIVED'
        """;
    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, name);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapResultToAccount(rs));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find account by name: " + name, e);
    }
    return Optional.empty();
  }

  @Override
  public List<CoaAccountExt> findByKind(CoaAccountKind kind) {
    String sql = """
        SELECT code, name, kind, currency_code, balance_minor, version,
               parent_code, status, description, created_at, updated_at, created_by, updated_by
        FROM coa_account_ext WHERE kind = ? AND status != 'ARCHIVED'
        ORDER BY code
        """;
    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, kind.name());
      try (ResultSet rs = ps.executeQuery()) {
        List<CoaAccountExt> accounts = new ArrayList<>();
        while (rs.next()) {
          accounts.add(mapResultToAccount(rs));
        }
        return accounts;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find accounts by kind: " + kind, e);
    }
  }

  @Override
  public List<CoaAccountExt> findByCurrency(String currency) {
    String sql = """
        SELECT code, name, kind, currency_code, balance_minor, version,
               parent_code, status, description, created_at, updated_at, created_by, updated_by
        FROM coa_account_ext WHERE currency_code = ? AND status != 'ARCHIVED'
        ORDER BY code
        """;
    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, currency);
      try (ResultSet rs = ps.executeQuery()) {
        List<CoaAccountExt> accounts = new ArrayList<>();
        while (rs.next()) {
          accounts.add(mapResultToAccount(rs));
        }
        return accounts;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find accounts by currency: " + currency, e);
    }
  }

  @Override
  public List<CoaAccountExt> findAll() {
    String sql = """
        SELECT code, name, kind, currency_code, balance_minor, version,
               parent_code, status, description, created_at, updated_at, created_by, updated_by
        FROM coa_account_ext WHERE status != 'ARCHIVED'
        ORDER BY code
        """;
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery(sql)) {
        List<CoaAccountExt> accounts = new ArrayList<>();
        while (rs.next()) {
          accounts.add(mapResultToAccount(rs));
        }
        return accounts;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to fetch all accounts", e);
    }
  }

  @Override
  public Optional<CoaAccountExt> findParent(String accountCode) {
    Optional<CoaAccountExt> account = findByCode(accountCode);
    if (account.isEmpty() || account.get().parentCode().isEmpty()) {
      return Optional.empty();
    }
    return findByCode(account.get().parentCode().get());
  }

  @Override
  public List<CoaAccountExt> findChildren(String parentCode) {
    String sql = """
        SELECT code, name, kind, currency_code, balance_minor, version,
               parent_code, status, description, created_at, updated_at, created_by, updated_by
        FROM coa_account_ext WHERE parent_code = ? AND status != 'ARCHIVED'
        ORDER BY code
        """;
    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, parentCode);
      try (ResultSet rs = ps.executeQuery()) {
        List<CoaAccountExt> children = new ArrayList<>();
        while (rs.next()) {
          children.add(mapResultToAccount(rs));
        }
        return children;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find children of: " + parentCode, e);
    }
  }

  @Override
  public List<CoaAccountExt> findDescendants(String parentCode) {
    List<CoaAccountExt> descendants = new ArrayList<>();
    List<CoaAccountExt> directChildren = findChildren(parentCode);
    descendants.addAll(directChildren);
    for (CoaAccountExt child : directChildren) {
      descendants.addAll(findDescendants(child.code()));
    }
    return descendants;
  }

  @Override
  public CoaAccountExt createAccount(
      String code, String name, CoaAccountKind kind, String currency,
      Optional<String> parentCode, String description, String createdBy) {

    // Validate code is unique
    if (findByCode(code).isPresent()) {
      throw new AccountAlreadyExistsException(code);
    }

    // Validate parent exists if specified
    if (parentCode.isPresent()) {
      if (!findByCode(parentCode.get()).isPresent()) {
        throw new InvalidAccountHierarchyException("Parent account not found: " + parentCode.get());
      }
    }

    String sql = """
        INSERT INTO coa_account_ext
          (code, name, kind, currency_code, balance_minor, version,
           parent_code, status, description, created_at, updated_at, created_by, updated_by)
        VALUES (?, ?, ?, ?, 0, 1, ?, 'ACTIVE', ?, ?, ?, ?, ?)
        """;

    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, code);
      ps.setString(2, name);
      ps.setString(3, kind.name());
      ps.setString(4, currency);
      ps.setString(5, parentCode.orElse(null));
      ps.setString(6, description);
      ps.setTimestamp(7, Timestamp.from(Instant.now()));
      ps.setTimestamp(8, Timestamp.from(Instant.now()));
      ps.setString(9, createdBy);
      ps.setString(10, createdBy);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to create account: " + code, e);
    }

    return findByCode(code).orElseThrow(() ->
        new RuntimeException("Failed to retrieve created account: " + code));
  }

  @Override
  public CoaAccountExt deactivate(String accountCode, String deactivatedBy) {
    CoaAccountExt account = findByCode(accountCode)
        .orElseThrow(() -> new AccountNotFoundException(accountCode));

    if (!account.isActive()) {
      throw new AccountActiveException(accountCode);
    }

    if (!findChildren(accountCode).isEmpty()) {
      throw new AccountHasDescendantsException(accountCode);
    }

    String sql = """
        UPDATE coa_account_ext SET status = 'INACTIVE', updated_at = ?, updated_by = ?
        WHERE code = ?
        """;

    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(Instant.now()));
      ps.setString(2, deactivatedBy);
      ps.setString(3, accountCode);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to deactivate account: " + accountCode, e);
    }

    return findByCode(accountCode).orElseThrow();
  }

  @Override
  public CoaAccountExt activate(String accountCode, String activatedBy) {
    CoaAccountExt account = findByCode(accountCode)
        .orElseThrow(() -> new AccountNotFoundException(accountCode));

    String sql = """
        UPDATE coa_account_ext SET status = 'ACTIVE', updated_at = ?, updated_by = ?
        WHERE code = ?
        """;

    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(Instant.now()));
      ps.setString(2, activatedBy);
      ps.setString(3, accountCode);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to activate account: " + accountCode, e);
    }

    return findByCode(accountCode).orElseThrow();
  }

  @Override
  public CoaAccountExt archive(String accountCode, String archivedBy) {
    CoaAccountExt account = findByCode(accountCode)
        .orElseThrow(() -> new AccountNotFoundException(accountCode));

    if (account.balanceMinor() != 0) {
      throw new AccountHasBalanceException(accountCode, account.balanceMinor());
    }

    String sql = """
        UPDATE coa_account_ext SET status = 'ARCHIVED', updated_at = ?, updated_by = ?
        WHERE code = ?
        """;

    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(Instant.now()));
      ps.setString(2, archivedBy);
      ps.setString(3, accountCode);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to archive account: " + accountCode, e);
    }

    return findByCode(accountCode).orElseThrow();
  }

  @Override
  public Optional<AccountingPeriod> currentPeriod() {
    String sql = """
        SELECT period_start, period_end, status, created_at, closed_at, closed_by, closing_trans_id
        FROM accounting_periods WHERE status = 'OPEN' ORDER BY period_end DESC LIMIT 1
        """;
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery(sql)) {
        if (rs.next()) {
          return Optional.of(mapResultToPeriod(rs));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to fetch current period", e);
    }
    return Optional.empty();
  }

  @Override
  public Optional<AccountingPeriod> findPeriodFor(LocalDate date) {
    String sql = """
        SELECT period_start, period_end, status, created_at, closed_at, closed_by, closing_trans_id
        FROM accounting_periods
        WHERE period_start <= ? AND period_end >= ?
        LIMIT 1
        """;
    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setDate(1, java.sql.Date.valueOf(date));
      ps.setDate(2, java.sql.Date.valueOf(date));
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return Optional.of(mapResultToPeriod(rs));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find period for date: " + date, e);
    }
    return Optional.empty();
  }

  @Override
  public AccountingPeriod createPeriod(LocalDate start, LocalDate end, String createdBy) {
    String sql = """
        INSERT INTO accounting_periods
          (period_start, period_end, status, created_at, closed_at, closed_by, closing_trans_id)
        VALUES (?, ?, 'OPEN', ?, NULL, NULL, -1)
        """;

    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setDate(1, java.sql.Date.valueOf(start));
      ps.setDate(2, java.sql.Date.valueOf(end));
      ps.setTimestamp(3, Timestamp.from(Instant.now()));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to create period: " + start + " to " + end, e);
    }

    return findPeriodFor(end)
        .orElseThrow(() -> new RuntimeException("Failed to retrieve created period"));
  }

  @Override
  public AccountingPeriod closePeriod(LocalDate periodEnd, long closingTransId, String closedBy) {
    String sql = """
        UPDATE accounting_periods SET status = 'CLOSED', closed_at = ?, closed_by = ?, closing_trans_id = ?
        WHERE period_end = ?
        """;

    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setTimestamp(1, Timestamp.from(Instant.now()));
      ps.setString(2, closedBy);
      ps.setLong(3, closingTransId);
      ps.setDate(4, java.sql.Date.valueOf(periodEnd));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to close period: " + periodEnd, e);
    }

    return findPeriodFor(periodEnd)
        .orElseThrow(() -> new RuntimeException("Failed to retrieve closed period"));
  }

  @Override
  public AccountingPeriod lockPeriod(LocalDate periodEnd) {
    String sql = """
        UPDATE accounting_periods SET status = 'LOCKED'
        WHERE period_end = ?
        """;

    try (Connection conn = getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setDate(1, java.sql.Date.valueOf(periodEnd));
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to lock period: " + periodEnd, e);
    }

    return findPeriodFor(periodEnd)
        .orElseThrow(() -> new RuntimeException("Failed to retrieve locked period"));
  }

  @Override
  public void validatePostingAllowed(String accountCode) throws IllegalStateException {
    CoaAccountExt account = findByCode(accountCode)
        .orElseThrow(() -> new AccountNotFoundException(accountCode));

    if (!account.isActive()) {
      throw new AccountInactiveException(accountCode);
    }

    Optional<AccountingPeriod> period = currentPeriod();
    if (period.isEmpty() || !period.get().canPost()) {
      throw new PeriodClosedException(LocalDate.now());
    }
  }

  @Override
  public void initializeDefaultCOA(String initializedBy) {
    if (isInitialized()) {
      return; // Idempotent: already initialized
    }

    // Initialize GtelPay Chart of Accounts (from core.foundation.md)
    createIfNotExists("1111", "TK Vietinbank — Chuyên dùng", CoaAccountKind.ASSET, "VND",
        Optional.empty(), "Main bank account", initializedBy);
    createIfNotExists("1112", "TK Napas Clearing", CoaAccountKind.ASSET, "VND",
        Optional.empty(), "Napas clearing account", initializedBy);
    createIfNotExists("1113", "TK VPBank — QR/POS", CoaAccountKind.ASSET, "VND",
        Optional.empty(), "VPBank for QR/POS", initializedBy);
    createIfNotExists("1920", "FX Position VND", CoaAccountKind.ASSET, "VND",
        Optional.empty(), "Multi-currency FX position VND", initializedBy);
    createIfNotExists("1921", "FX Position USD", CoaAccountKind.ASSET, "USD",
        Optional.empty(), "Multi-currency FX position USD", initializedBy);

    createIfNotExists("2110", "Wallet Balance — User", CoaAccountKind.LIABILITY, "VND",
        Optional.empty(), "Control account for user wallets", initializedBy);
    createIfNotExists("2120", "Wallet Balance — Merchant", CoaAccountKind.LIABILITY, "VND",
        Optional.empty(), "Control account for merchant wallets", initializedBy);
    createIfNotExists("2130", "Ký quỹ — Đối tác Chi hộ", CoaAccountKind.LIABILITY, "VND",
        Optional.empty(), "Escrow for disbursement partners", initializedBy);
    createIfNotExists("2140", "Savings Balance", CoaAccountKind.LIABILITY, "VND",
        Optional.empty(), "Control account for savings", initializedBy);

    // Transit accounts (must return to 0 after each flow)
    createIfNotExists("3100", "Transit — Nạp tiền", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "Deposit in-flight", initializedBy);
    createIfNotExists("3200", "Transit — Rút tiền", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "Withdrawal in-flight", initializedBy);
    createIfNotExists("3300", "Transit — Chuyển tiền nội bộ", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "Internal transfer in-flight", initializedBy);
    createIfNotExists("3400", "Transit — IBFT", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "IBFT inter-bank in-flight", initializedBy);
    createIfNotExists("3500", "Transit — Thanh toán", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "Payment in-flight", initializedBy);
    createIfNotExists("3600", "Transit — Chi Lương", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "Payroll disbursement in-flight", initializedBy);
    createIfNotExists("3700", "Transit — Chi hộ", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "Disbursement on behalf in-flight", initializedBy);
    createIfNotExists("3800", "Transit — Clearing", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "EOD clearing in-flight", initializedBy);
    createIfNotExists("3810", "Transit — Settlement Outbound", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "Settlement outbound in-flight", initializedBy);
    createIfNotExists("3820", "Transit — MDR Holdback", CoaAccountKind.TRANSIT, "VND",
        Optional.empty(), "MDR holdback in-flight", initializedBy);

    // Revenue accounts
    createIfNotExists("4110", "Phí nạp", CoaAccountKind.REVENUE, "VND",
        Optional.empty(), "Deposit fee revenue", initializedBy);
    createIfNotExists("4120", "Phí rút", CoaAccountKind.REVENUE, "VND",
        Optional.empty(), "Withdrawal fee revenue", initializedBy);
    createIfNotExists("4130", "Phí chuyển", CoaAccountKind.REVENUE, "VND",
        Optional.empty(), "Transfer fee revenue", initializedBy);
    createIfNotExists("4140", "Phí MDR", CoaAccountKind.REVENUE, "VND",
        Optional.empty(), "Merchant discount rate revenue", initializedBy);
    createIfNotExists("4150", "Phí Chi Lương / Chi hộ", CoaAccountKind.REVENUE, "VND",
        Optional.empty(), "Payroll/disbursement fee revenue", initializedBy);
    createIfNotExists("4170", "Lãi FX", CoaAccountKind.REVENUE, "VND",
        Optional.empty(), "FX gain", initializedBy);

    // Expense accounts
    createIfNotExists("5100", "Chi phí Phí NH / Napas", CoaAccountKind.EXPENSE, "VND",
        Optional.empty(), "Bank/Napas fees", initializedBy);
    createIfNotExists("5200", "Chi phí Lãi", CoaAccountKind.EXPENSE, "VND",
        Optional.empty(), "Interest expense on deposits", initializedBy);
    createIfNotExists("5300", "Lỗ FX", CoaAccountKind.EXPENSE, "VND",
        Optional.empty(), "FX loss", initializedBy);

    // Equity accounts
    createIfNotExists("6000", "Vốn chủ sở hữu", CoaAccountKind.EQUITY, "VND",
        Optional.empty(), "Owner capital / charter capital", initializedBy);
    createIfNotExists("6100", "Lợi nhuận giữ lại", CoaAccountKind.EQUITY, "VND",
        Optional.empty(), "Retained earnings", initializedBy);
  }

  private void createIfNotExists(String code, String name, CoaAccountKind kind, String currency,
                                  Optional<String> parentCode, String description, String createdBy) {
    if (findByCode(code).isEmpty()) {
      createAccount(code, name, kind, currency, parentCode, description, createdBy);
    }
  }

  @Override
  public boolean isInitialized() {
    String sql = "SELECT COUNT(*) FROM coa_account_ext";
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery(sql)) {
        if (rs.next()) {
          return rs.getLong(1) > 0;
        }
      }
    } catch (SQLException e) {
      return false; // Table doesn't exist yet
    }
    return false;
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private Connection getConnection() throws SQLException {
    return DriverManager.getConnection(dataSourceUrl, user, password);
  }

  private CoaAccountExt mapResultToAccount(ResultSet rs) throws SQLException {
    String parentCode = rs.getString("parent_code");
    return new CoaAccountExt(
        rs.getString("code"),
        rs.getString("name"),
        CoaAccountKind.valueOf(rs.getString("kind")),
        rs.getString("currency_code"),
        rs.getLong("balance_minor"),
        rs.getLong("version"),
        parentCode == null ? Optional.empty() : Optional.of(parentCode),
        AccountStatus.valueOf(rs.getString("status")),
        rs.getString("description"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        rs.getString("created_by"),
        rs.getString("updated_by")
    );
  }

  private AccountingPeriod mapResultToPeriod(ResultSet rs) throws SQLException {
    Timestamp closedAtTs = rs.getTimestamp("closed_at");
    return new AccountingPeriod(
        rs.getDate("period_start").toLocalDate(),
        rs.getDate("period_end").toLocalDate(),
        PeriodStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("created_at").toInstant(),
        closedAtTs == null ? null : closedAtTs.toInstant(),
        rs.getString("closed_by"),
        rs.getLong("closing_trans_id")
    );
  }
}
