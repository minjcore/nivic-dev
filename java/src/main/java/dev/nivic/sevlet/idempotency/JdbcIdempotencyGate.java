package dev.nivic.sevlet.idempotency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/** PostgreSQL-backed idempotency using {@code INSERT ... ON CONFLICT DO NOTHING}. */
public final class JdbcIdempotencyGate implements IdempotencyGate {

  private static final String DDL =
      """
      CREATE TABLE IF NOT EXISTS wallet_idempotency (
        mid BIGINT NOT NULL,
        request_id BIGINT NOT NULL,
        order_id BIGINT,
        PRIMARY KEY (mid, request_id)
      )
      """;

  private static final String INSERT =
      "INSERT INTO wallet_idempotency (mid, request_id, order_id) VALUES (?, ?, ?) "
          + "ON CONFLICT (mid, request_id) DO NOTHING";

  private static final String SELECT_ORDER =
      "SELECT order_id FROM wallet_idempotency WHERE mid = ? AND request_id = ?";

  private final DataSource dataSource;
  private volatile boolean tableEnsured;

  public JdbcIdempotencyGate(DataSource dataSource) {
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
        st.execute("ALTER TABLE wallet_idempotency ADD COLUMN IF NOT EXISTS order_id BIGINT");
      }
      tableEnsured = true;
    }
  }

  @Override
  public boolean claimFirst(long mid, long requestId, long orderId, boolean orderPaymentMode) {
    try {
      ensureTable();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(INSERT)) {
        ps.setLong(1, mid);
        ps.setLong(2, requestId);
        ps.setLong(3, orderId);
        int inserted = ps.executeUpdate();
        if (inserted > 0) {
          return true;
        }
      }
      if (!orderPaymentMode) {
        return false;
      }
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_ORDER)) {
        ps.setLong(1, mid);
        ps.setLong(2, requestId);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return false;
          }
          Long boxed = (Long) rs.getObject(1);
          long stored = boxed == null ? 0L : boxed;
          if (stored != orderId) {
            throw new OrderIdMismatchException(mid, requestId, stored, orderId);
          }
        }
      }
      return false;
    } catch (SQLException e) {
      throw new IllegalStateException(
          "idempotency claim failed for mid=" + mid + " requestId=" + requestId, e);
    }
  }
}
