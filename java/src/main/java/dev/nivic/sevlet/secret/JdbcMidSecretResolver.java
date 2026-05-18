package dev.nivic.sevlet.secret;

import dev.nivic.merchant.MerchantConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Loads {@code secret_key}, {@code payment_check_order}, and optional {@code merchant_config} from
 * {@code wallet_mid_secret} LEFT JOIN {@code merchant_config}.
 */
public final class JdbcMidSecretResolver implements MidSecretResolver {

  private static final String DDL_WALLET =
      """
      CREATE TABLE IF NOT EXISTS wallet_mid_secret (
        mid BIGINT NOT NULL PRIMARY KEY,
        secret_key BYTEA NOT NULL,
        payment_check_order BOOLEAN NOT NULL DEFAULT FALSE
      )
      """;

  private static final String DDL_MERCHANT =
      """
      CREATE TABLE IF NOT EXISTS merchant_config (
        mid BIGINT NOT NULL PRIMARY KEY,
        enabled BOOLEAN NOT NULL DEFAULT TRUE,
        intent_ttl_minutes INTEGER,
        display_name VARCHAR(256)
      )
      """;

  private static final String SELECT =
      "SELECT w.secret_key, COALESCE(w.payment_check_order, FALSE),"
          + " COALESCE(m.enabled, TRUE), m.intent_ttl_minutes, m.display_name"
          + " FROM wallet_mid_secret w"
          + " LEFT JOIN merchant_config m ON m.mid = w.mid"
          + " WHERE w.mid = ?";

  private final DataSource dataSource;
  private volatile boolean tableEnsured;

  public JdbcMidSecretResolver(DataSource dataSource) {
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
        st.execute(DDL_WALLET);
        st.execute(
            "ALTER TABLE wallet_mid_secret ADD COLUMN IF NOT EXISTS payment_check_order BOOLEAN NOT"
                + " NULL DEFAULT FALSE");
        st.execute(DDL_MERCHANT);
      }
      tableEnsured = true;
    }
  }

  @Override
  public MidProfile requireProfile(long mid) {
    try {
      ensureTable();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT)) {
        ps.setLong(1, mid);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            throw new UnknownMidException(mid);
          }
          byte[] key = rs.getBytes(1);
          boolean orderPaymentMode = rs.getBoolean(2);
          Boolean enabled = (Boolean) rs.getObject(3);
          Integer ttl = (Integer) rs.getObject(4);
          String display = rs.getString(5);
          MerchantConfig mc = MerchantConfig.fromNullableRow(enabled, ttl, display);
          return new MidProfile(key, orderPaymentMode, mc);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("mid profile lookup failed for mid=" + mid, e);
    }
  }
}
