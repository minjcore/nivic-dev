package dev.nivic.ledger;

import dev.nivic.sevlet.SevletWalletPayload;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Currency;
import java.util.Objects;
import javax.sql.DataSource;

/** PostgreSQL-backed append-only ledger table {@code wallet_ledger}. */
public final class JdbcWalletLedger implements WalletLedger {

  private static final String DDL =
      """
      CREATE TABLE IF NOT EXISTS wallet_ledger (
        mid BIGINT NOT NULL,
        request_id BIGINT NOT NULL,
        order_id BIGINT NOT NULL,
        input BIGINT NOT NULL,
        amount_minor BIGINT NOT NULL,
        debit INTEGER NOT NULL,
        credit INTEGER NOT NULL,
        currency_code VARCHAR(3) NOT NULL,
        extra_data BYTEA NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (mid, request_id)
      )
      """;

  private static final String INSERT =
      "INSERT INTO wallet_ledger (mid, request_id, order_id, input, amount_minor, debit, credit,"
          + " currency_code, extra_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private final DataSource dataSource;
  private volatile boolean tableEnsured;

  public JdbcWalletLedger(DataSource dataSource) {
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
  public void append(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    try {
      ensureTable();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(INSERT)) {
        ps.setLong(1, payload.mid());
        ps.setLong(2, payload.requestId());
        ps.setLong(3, payload.orderId());
        ps.setLong(4, payload.command());
        ps.setLong(5, payload.amount());
        ps.setInt(6, payload.debit());
        ps.setInt(7, payload.credit());
        ps.setString(8, currency.getCurrencyCode());
        ps.setBytes(9, payload.extraData());
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          "ledger append failed: mid="
              + payload.mid()
              + " requestId="
              + payload.requestId(),
          e);
    }
  }
}
