package dev.nivic.ledger;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class JdbcWalletManager implements WalletManager {
  private final DataSource dataSource;
  private volatile boolean schemaEnsured = false;

  private static final String DDL_WALLET = """
      CREATE TABLE IF NOT EXISTS wallet (
        id                BIGINT       PRIMARY KEY,
        uid               VARCHAR(128) NOT NULL,
        wallet_type       VARCHAR(16)  NOT NULL,
        status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
        balance_minor     BIGINT       NOT NULL DEFAULT 0,
        currency_code     VARCHAR(10)  NOT NULL,
        account_code      VARCHAR(10)  NOT NULL,
        version           BIGINT       NOT NULL DEFAULT 0,
        created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
        last_activity_at  TIMESTAMP,
        UNIQUE (uid, currency_code)
      )
      """;

  private static final String DDL_TRANSFER = """
      CREATE TABLE IF NOT EXISTS wallet_transfer (
        id                BIGINT       PRIMARY KEY,
        from_wallet_id    BIGINT       NOT NULL REFERENCES wallet(id),
        to_wallet_id      BIGINT       NOT NULL REFERENCES wallet(id),
        amount_minor      BIGINT       NOT NULL,
        currency_code     VARCHAR(10)  NOT NULL,
        status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
        transaction_id    BIGINT       UNIQUE,
        ref_id            VARCHAR(128) UNIQUE,
        memo              VARCHAR(512),
        created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
        confirmed_at      TIMESTAMP
      )
      """;

  private static final String DDL_HOLD = """
      CREATE TABLE IF NOT EXISTS wallet_hold (
        id                BIGINT       PRIMARY KEY,
        wallet_id         BIGINT       NOT NULL REFERENCES wallet(id),
        transfer_id       BIGINT       NOT NULL REFERENCES wallet_transfer(id),
        amount_minor      BIGINT       NOT NULL,
        status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
        created_at        TIMESTAMP    NOT NULL DEFAULT NOW()
      )
      """;

  public JdbcWalletManager(DataSource dataSource) {
    this.dataSource = dataSource;
    try {
      ensureSchema();
    } catch (Exception e) {
      // Schema might already exist, continue
      schemaEnsured = true;
    }
  }

  @Override
  public Wallet createWallet(String uid, String walletType, String currency, String accountCode) {
    ensureSchema();
    long id = System.currentTimeMillis();
    try (Connection c = dataSource.getConnection()) {
      // Insert wallet
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO wallet (id, uid, wallet_type, status, currency_code, account_code) "
              + "VALUES (?, ?, ?, 'ACTIVE', ?, ?) ON CONFLICT (uid, currency_code) DO NOTHING"
      )) {
        ps.setLong(1, id);
        ps.setString(2, uid);
        ps.setString(3, walletType);
        ps.setString(4, currency);
        ps.setString(5, accountCode);
        ps.executeUpdate();
      }
      // Now fetch it back
      try (PreparedStatement ps = c.prepareStatement("SELECT * FROM wallet WHERE uid = ? AND currency_code = ?")) {
        ps.setString(1, uid);
        ps.setString(2, currency);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) return mapWallet(rs);
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to create wallet for " + uid, e);
    }
    throw new RuntimeException("Failed to create wallet");
  }

  @Override
  public Optional<Wallet> getWallet(long walletId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT * FROM wallet WHERE id = ?")) {
      ps.setLong(1, walletId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return Optional.of(mapWallet(rs));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get wallet", e);
    }
    return Optional.empty();
  }

  @Override
  public Optional<Wallet> findByUid(String uid, String currency) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM wallet WHERE uid = ? AND currency_code = ?"
        )) {
      ps.setString(1, uid);
      ps.setString(2, currency);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return Optional.of(mapWallet(rs));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find wallet for " + uid, e);
    }
    return Optional.empty();
  }

  @Override
  public void updateStatus(long walletId, String status) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE wallet SET status = ?, version = version + 1 WHERE id = ?"
        )) {
      ps.setString(1, status);
      ps.setLong(2, walletId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to update wallet status", e);
    }
  }

  @Override
  public WalletTransfer initiateTransfer(
      long fromWalletId, long toWalletId, long amountMinor,
      String currency, String refId, String memo
  ) {
    ensureSchema();
    long transferId = System.currentTimeMillis();

    // Check if transfer already exists (idempotency)
    var existing = getTransferByRefId(refId);
    if (existing.isPresent()) return existing.get();

    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "INSERT INTO wallet_transfer "
                + "(id, from_wallet_id, to_wallet_id, amount_minor, currency_code, ref_id, memo, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')"
        )) {
      ps.setLong(1, transferId);
      ps.setLong(2, fromWalletId);
      ps.setLong(3, toWalletId);
      ps.setLong(4, amountMinor);
      ps.setString(5, currency);
      ps.setString(6, refId);
      ps.setString(7, memo);
      ps.executeUpdate();

      // Hold balance immediately
      holdBalance(fromWalletId, transferId, amountMinor);

      return new WalletTransfer(
          transferId, fromWalletId, toWalletId, amountMinor, currency,
          "PENDING", null, refId, memo, Instant.now(), null
      );
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initiate transfer", e);
    }
  }

  @Override
  public Optional<WalletTransfer> getTransfer(long transferId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT * FROM wallet_transfer WHERE id = ?")) {
      ps.setLong(1, transferId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return Optional.of(mapTransfer(rs));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get transfer", e);
    }
    return Optional.empty();
  }

  @Override
  public Optional<WalletTransfer> getTransferByRefId(String refId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT * FROM wallet_transfer WHERE ref_id = ?")) {
      ps.setString(1, refId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return Optional.of(mapTransfer(rs));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find transfer by refId", e);
    }
    return Optional.empty();
  }

  @Override
  public void postTransfer(long transferId, long transactionId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE wallet_transfer SET status = 'POSTED', transaction_id = ? WHERE id = ?"
        )) {
      ps.setLong(1, transactionId);
      ps.setLong(2, transferId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to post transfer", e);
    }
  }

  @Override
  public void confirmTransfer(long transferId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE wallet_transfer SET status = 'CONFIRMED', confirmed_at = NOW() WHERE id = ?"
        )) {
      ps.setLong(1, transferId);
      ps.executeUpdate();
      captureHold(transferId);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to confirm transfer", e);
    }
  }

  @Override
  public void holdBalance(long walletId, long transferId, long amountMinor) {
    ensureSchema();
    long holdId = System.currentTimeMillis();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "INSERT INTO wallet_hold (id, wallet_id, transfer_id, amount_minor, status) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE')"
        )) {
      ps.setLong(1, holdId);
      ps.setLong(2, walletId);
      ps.setLong(3, transferId);
      ps.setLong(4, amountMinor);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to hold balance", e);
    }
  }

  @Override
  public void releaseHold(long transferId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE wallet_hold SET status = 'RELEASED' WHERE transfer_id = ?"
        )) {
      ps.setLong(1, transferId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to release hold", e);
    }
  }

  @Override
  public void captureHold(long transferId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "UPDATE wallet_hold SET status = 'CAPTURED' WHERE transfer_id = ?"
        )) {
      ps.setLong(1, transferId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to capture hold", e);
    }
  }

  @Override
  public long getAvailableBalance(long walletId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT balance_minor - COALESCE(SUM(amount_minor), 0) as available "
                + "FROM wallet LEFT JOIN wallet_hold ON wallet.id = wallet_hold.wallet_id "
                + "WHERE wallet.id = ? AND wallet_hold.status = 'ACTIVE' GROUP BY wallet.id"
        )) {
      ps.setLong(1, walletId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong("available");
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get available balance", e);
    }
    return 0;
  }

  @Override
  public long getHeldBalance(long walletId) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(SUM(amount_minor), 0) as held FROM wallet_hold "
                + "WHERE wallet_id = ? AND status = 'ACTIVE'"
        )) {
      ps.setLong(1, walletId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getLong("held");
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get held balance", e);
    }
    return 0;
  }

  @Override
  public List<WalletTransfer> getPendingTransfers(long walletId) {
    ensureSchema();
    List<WalletTransfer> result = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT * FROM wallet_transfer WHERE (from_wallet_id = ? OR to_wallet_id = ?) "
                + "AND status = 'PENDING' ORDER BY created_at DESC"
        )) {
      ps.setLong(1, walletId);
      ps.setLong(2, walletId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) result.add(mapTransfer(rs));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to get pending transfers", e);
    }
    return result;
  }

  private Wallet mapWallet(ResultSet rs) throws SQLException {
    return new Wallet(
        rs.getLong("id"), rs.getString("uid"), rs.getString("wallet_type"),
        rs.getString("status"), rs.getLong("balance_minor"),
        rs.getString("currency_code"), rs.getString("account_code"),
        rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("last_activity_at") != null ?
            rs.getTimestamp("last_activity_at").toInstant() : null
    );
  }

  private WalletTransfer mapTransfer(ResultSet rs) throws SQLException {
    return new WalletTransfer(
        rs.getLong("id"), rs.getLong("from_wallet_id"), rs.getLong("to_wallet_id"),
        rs.getLong("amount_minor"), rs.getString("currency_code"),
        rs.getString("status"),
        rs.getLong("transaction_id") > 0 ? rs.getLong("transaction_id") : null,
        rs.getString("ref_id"), rs.getString("memo"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("confirmed_at") != null ?
            rs.getTimestamp("confirmed_at").toInstant() : null
    );
  }

  private void ensureSchema() {
    if (schemaEnsured) return;
    synchronized (this) {
      if (schemaEnsured) return;
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_WALLET);
        st.execute(DDL_TRANSFER);
        st.execute(DDL_HOLD);
        schemaEnsured = true;
      } catch (SQLException e) {
        throw new RuntimeException("Failed to ensure wallet schema", e);
      }
    }
  }
}
