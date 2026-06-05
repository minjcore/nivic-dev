package dev.nivic.ledger;

import dev.nivic.coa.FundFlowLedger;
import dev.nivic.bank.BankGateway;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class JdbcSettlementManager implements SettlementManager {
  private final DataSource dataSource;
  private final WalletManager walletManager;
  private final FundFlowLedger fundFlowLedger;
  private final BankGateway bankGateway;
  private volatile boolean schemaEnsured = false;
  private final java.util.concurrent.atomic.AtomicLong idSequence =
      new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1000);

  private static final String DDL_SETTLEMENT = """
      CREATE TABLE IF NOT EXISTS settlement (
        id                  BIGINT       PRIMARY KEY,
        wallet_id           BIGINT       NOT NULL REFERENCES wallet(id),
        settlement_type     VARCHAR(16)  NOT NULL,
        status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
        amount_minor        BIGINT       NOT NULL,
        currency            VARCHAR(10)  NOT NULL,
        destination_bank    VARCHAR(32),
        bank_transaction_id VARCHAR(128),
        transaction_id      BIGINT UNIQUE,
        wallet_hold_id      BIGINT REFERENCES wallet_hold(id),
        created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
        confirmed_at        TIMESTAMP
      )
      """;

  public JdbcSettlementManager(
      DataSource dataSource, WalletManager walletManager,
      FundFlowLedger fundFlowLedger, BankGateway bankGateway
  ) {
    this.dataSource = dataSource;
    this.walletManager = walletManager;
    this.fundFlowLedger = fundFlowLedger;
    this.bankGateway = bankGateway;
    try {
      ensureSchema();
    } catch (Exception e) {
      schemaEnsured = true;
    }
  }

  @Override
  public Settlement initiate(long walletId, long amountMinor, String type, String currency, String destination) {
    ensureSchema();
    long id = idSequence.incrementAndGet();

    // Pessimistic locking: Lock wallet and create settlement in same transaction
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        // Lock wallet row with SELECT ... FOR UPDATE
        Wallet wallet;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id, uid, wallet_type, status, balance_minor, currency_code, account_code, version, created_at, last_activity_at "
                + "FROM wallet WHERE id = ? FOR UPDATE"
        )) {
          ps.setLong(1, walletId);
          try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
              throw new RuntimeException("Wallet not found: " + walletId);
            }
            wallet = mapWallet(rs);
          }
        }

        if (wallet.balanceMinor() < amountMinor) {
          throw new RuntimeException("Insufficient balance");
        }

        // Create settlement (still holding lock)
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO settlement "
                + "(id, wallet_id, settlement_type, status, amount_minor, currency, destination_bank) "
                + "VALUES (?, ?, ?, 'PENDING', ?, ?, ?)"
        )) {
          ps.setLong(1, id);
          ps.setLong(2, walletId);
          ps.setString(3, type);
          ps.setLong(4, amountMinor);
          ps.setString(5, currency);
          ps.setString(6, destination);
          ps.executeUpdate();
        }

        c.commit();
        return new Settlement(
            id, walletId, type, "PENDING", amountMinor, currency,
            destination, null, null, null, Instant.now(), null
        );
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initiate settlement: " + e.getMessage(), e);
    }
  }

  private Wallet mapWallet(ResultSet rs) throws SQLException {
    return new Wallet(
        rs.getLong("id"),
        rs.getString("uid"),
        rs.getString("wallet_type"),
        rs.getString("status"),
        rs.getLong("balance_minor"),
        rs.getString("currency_code"),
        rs.getString("account_code"),
        rs.getLong("version"),
        rs.getObject("created_at", java.sql.Timestamp.class).toInstant(),
        rs.getObject("last_activity_at", java.sql.Timestamp.class) != null ?
            rs.getObject("last_activity_at", java.sql.Timestamp.class).toInstant() : null
    );
  }

  @Override
  public void hold(long settlementId) {
    ensureSchema();
    Settlement settlement = get(settlementId)
        .orElseThrow(() -> new RuntimeException("Settlement not found: " + settlementId));

    if (!settlement.isPending()) {
      throw new RuntimeException("Settlement must be PENDING to hold");
    }

    // Create a virtual wallet_transfer to reference in wallet_hold
    long transferId = System.currentTimeMillis();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "INSERT INTO wallet_transfer "
                + "(id, from_wallet_id, to_wallet_id, amount_minor, currency_code, status, ref_id) "
                + "VALUES (?, ?, ?, ?, ?, 'PENDING', ?)"
        )) {
      ps.setLong(1, transferId);
      ps.setLong(2, settlement.walletId());
      ps.setLong(3, settlement.walletId());  // Virtual transfer (self)
      ps.setLong(4, settlement.amountMinor());
      ps.setString(5, settlement.currency());
      ps.setString(6, "SETTLEMENT-" + settlementId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to create settlement transfer", e);
    }

    // Create hold on wallet
    long holdId = System.currentTimeMillis();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "INSERT INTO wallet_hold (id, wallet_id, transfer_id, amount_minor, status) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE')"
        )) {
      ps.setLong(1, holdId);
      ps.setLong(2, settlement.walletId());
      ps.setLong(3, transferId);  // Reference the virtual transfer
      ps.setLong(4, settlement.amountMinor());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to hold balance", e);
    }

    // Update settlement status
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE settlement SET status = 'HOLD', wallet_hold_id = ? WHERE id = ?"
        )) {
      ps.setLong(1, holdId);
      ps.setLong(2, settlementId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to update settlement status", e);
    }
  }

  @Override
  public void post(long settlementId, long transactionId) {
    ensureSchema();
    Settlement settlement = get(settlementId)
        .orElseThrow(() -> new RuntimeException("Settlement not found: " + settlementId));

    if (!settlement.isHeld()) {
      throw new RuntimeException("Settlement must be HELD to post");
    }

    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE settlement SET status = 'POSTED', transaction_id = ? WHERE id = ?"
        )) {
      ps.setLong(1, transactionId);
      ps.setLong(2, settlementId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to post settlement", e);
    }
  }

  @Override
  public void execute(long settlementId, String bankTransactionId) throws SettlementException {
    ensureSchema();
    Settlement settlement = get(settlementId)
        .orElseThrow(() -> new SettlementException("Settlement not found: " + settlementId));

    if (!settlement.isPosted()) {
      throw new SettlementException("Settlement must be POSTED to execute");
    }

    try {
      // For now, just update status to EXECUTING
      // In real implementation, would call bankGateway.initiateTransfer()
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(
              "UPDATE settlement SET status = 'EXECUTING', bank_transaction_id = ? WHERE id = ?"
          )) {
        ps.setString(1, bankTransactionId);
        ps.setLong(2, settlementId);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new SettlementException("Failed to execute settlement", e);
    }
  }

  @Override
  public void confirm(long settlementId, String bankTransactionId) {
    ensureSchema();
    Settlement settlement = get(settlementId)
        .orElseThrow(() -> new RuntimeException("Settlement not found: " + settlementId));

    if (!settlement.isExecuting()) {
      throw new RuntimeException("Settlement must be EXECUTING to confirm");
    }

    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE settlement SET status = 'CONFIRMED', bank_transaction_id = ?, confirmed_at = NOW() WHERE id = ?"
        )) {
      ps.setString(1, bankTransactionId);
      ps.setLong(2, settlementId);
      ps.executeUpdate();

      // Capture hold (deduct from wallet balance)
      if (settlement.walletHoldId() != null) {
        releaseHold(settlement.walletHoldId());
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to confirm settlement", e);
    }
  }

  @Override
  public void fail(long settlementId, String reason) {
    ensureSchema();
    Settlement settlement = get(settlementId)
        .orElseThrow(() -> new RuntimeException("Settlement not found: " + settlementId));

    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE settlement SET status = 'FAILED_BANK' WHERE id = ?"
        )) {
      ps.setLong(1, settlementId);
      ps.executeUpdate();

      // Release hold (restore balance)
      if (settlement.walletHoldId() != null) {
        releaseHoldById(settlement.walletHoldId());
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to mark settlement as failed", e);
    }
  }

  @Override
  public void retry(long settlementId) {
    ensureSchema();
    Settlement settlement = get(settlementId)
        .orElseThrow(() -> new RuntimeException("Settlement not found: " + settlementId));

    if (!settlement.isFailed()) {
      throw new RuntimeException("Can only retry failed settlements");
    }

    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE settlement SET status = 'PENDING', bank_transaction_id = NULL WHERE id = ?"
        )) {
      ps.setLong(1, settlementId);
      ps.executeUpdate();

      // Re-hold balance
      hold(settlementId);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to retry settlement", e);
    }
  }

  @Override
  public Optional<Settlement> get(long settlementId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT * FROM settlement WHERE id = ?")) {
      ps.setLong(1, settlementId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return Optional.of(mapSettlement(rs));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get settlement", e);
    }
    return Optional.empty();
  }

  @Override
  public List<Settlement> listPending() {
    return listByStatus("PENDING");
  }

  @Override
  public List<Settlement> listByWallet(long walletId) {
    ensureSchema();
    List<Settlement> result = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM settlement WHERE wallet_id = ? ORDER BY created_at DESC"
        )) {
      ps.setLong(1, walletId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.add(mapSettlement(rs));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to list settlements by wallet", e);
    }
    return result;
  }

  @Override
  public List<Settlement> listByStatus(String status) {
    ensureSchema();
    List<Settlement> result = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM settlement WHERE status = ? ORDER BY created_at DESC"
        )) {
      ps.setString(1, status);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.add(mapSettlement(rs));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to list settlements by status", e);
    }
    return result;
  }

  private void releaseHold(long holdId) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE wallet_hold SET status = 'CAPTURED' WHERE id = ?"
        )) {
      ps.setLong(1, holdId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to release hold", e);
    }
  }

  private void releaseHoldById(long holdId) {
    releaseHold(holdId);
  }

  private Settlement mapSettlement(ResultSet rs) throws SQLException {
    return new Settlement(
        rs.getLong("id"),
        rs.getLong("wallet_id"),
        rs.getString("settlement_type"),
        rs.getString("status"),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        rs.getString("destination_bank"),
        rs.getString("bank_transaction_id"),
        rs.getLong("transaction_id") > 0 ? rs.getLong("transaction_id") : null,
        rs.getLong("wallet_hold_id") > 0 ? rs.getLong("wallet_hold_id") : null,
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("confirmed_at") != null ? rs.getTimestamp("confirmed_at").toInstant() : null
    );
  }

  private void ensureSchema() {
    if (schemaEnsured) return;
    synchronized (this) {
      if (schemaEnsured) return;
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_SETTLEMENT);
        schemaEnsured = true;
      } catch (SQLException e) {
        throw new RuntimeException("Failed to ensure settlement schema", e);
      }
    }
  }
}
