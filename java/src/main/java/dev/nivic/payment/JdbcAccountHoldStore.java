package dev.nivic.payment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

public final class JdbcAccountHoldStore implements AccountHoldStore {

  private static final String DDL =
      """
      CREATE TABLE IF NOT EXISTS wallet_account_hold (
        mid BIGINT NOT NULL,
        request_id BIGINT NOT NULL,
        account_id INTEGER NOT NULL,
        amount_minor BIGINT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (mid, request_id)
      )
      """;

  private static final String INSERT =
      "INSERT INTO wallet_account_hold (mid, request_id, account_id, amount_minor) VALUES (?,?,?,?)";

  private static final String DELETE =
      "DELETE FROM wallet_account_hold WHERE mid = ? AND request_id = ?";

  private final DataSource dataSource;
  private volatile boolean tableEnsured;

  public JdbcAccountHoldStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  private void ensureTable() throws SQLException {
    if (tableEnsured) {
      return;
    }
    synchronized (this) {
      if (tableEnsured) {
        return;
      }
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL);
      }
      tableEnsured = true;
    }
  }

  @Override
  public void placeHold(
      Connection c, int debitAccount, long amountMinor, long mid, long requestId)
      throws SQLException {
    ensureTable();
    try (PreparedStatement ps = c.prepareStatement(INSERT)) {
      ps.setLong(1, mid);
      ps.setLong(2, requestId);
      ps.setInt(3, debitAccount);
      ps.setLong(4, amountMinor);
      ps.executeUpdate();
    }
  }

  @Override
  public void releaseHold(Connection c, long mid, long requestId) throws SQLException {
    ensureTable();
    try (PreparedStatement ps = c.prepareStatement(DELETE)) {
      ps.setLong(1, mid);
      ps.setLong(2, requestId);
      ps.executeUpdate();
    }
  }
}
