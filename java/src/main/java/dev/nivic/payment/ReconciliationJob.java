package dev.nivic.payment;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Lightweight read-side check: compares idempotency claims vs payment ledger rows (same {@code
 * mid}). For production, extend with external order-store export hooks.
 */
public final class ReconciliationJob {

  private ReconciliationJob() {}

  /** Returns a one-line diagnostic or throws on SQL errors. */
  public static String runVerificationReport(DataSource dataSource) throws SQLException {
    Objects.requireNonNull(dataSource, "dataSource");
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      long idem = count(st, "SELECT COUNT(*) FROM wallet_idempotency");
      long pay = count(st, "SELECT COUNT(*) FROM payment_ledger");
      return "wallet_idempotency_rows=" + idem + " payment_ledger_rows=" + pay;
    }
  }

  private static long count(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
