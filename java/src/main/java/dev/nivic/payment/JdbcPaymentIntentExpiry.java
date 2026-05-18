package dev.nivic.payment;

import dev.nivic.ledger.CoreLedgerStatus;
import dev.nivic.wal.InternalWalEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Marks non-terminal intents past {@code expires_at} as {@link CoreLedgerStatus#EXPIRED}, releases
 * holds, appends internal WAL. Open states come from {@link CoreLedgerStatus}, not hardcoded SQL
 * strings.
 */
public final class JdbcPaymentIntentExpiry {

  private static final String ST_EXPIRED = CoreLedgerStatus.EXPIRED.name();
  private static final String SQL_IN_OPEN =
      CoreLedgerStatus.sqlInList(CoreLedgerStatus::isOpenForConfirmation);

  private static final String SELECT =
      "SELECT mid, request_id FROM payment_ledger WHERE intent_status IN ("
          + SQL_IN_OPEN
          + ")"
          + " AND expires_at IS NOT NULL AND expires_at < NOW() LIMIT 500";

  private static final String MARK =
      "UPDATE payment_ledger SET intent_status = '"
          + ST_EXPIRED
          + "', cancel_reason = 'TTL' WHERE mid = ?"
          + " AND request_id = ? AND intent_status IN ("
          + SQL_IN_OPEN
          + ")";

  private JdbcPaymentIntentExpiry() {}

  public static int runOnce(DataSource dataSource, WalService wal, AccountHoldStore holdStore)
      throws SQLException {
    Objects.requireNonNull(dataSource, "dataSource");
    Objects.requireNonNull(wal, "wal");
    Objects.requireNonNull(holdStore, "holdStore");
    List<long[]> rows = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        rows.add(new long[] {rs.getLong(1), rs.getLong(2)});
      }
    }
    int done = 0;
    for (long[] pair : rows) {
      long mid = pair[0];
      long requestId = pair[1];
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          int n;
          try (PreparedStatement up = c.prepareStatement(MARK)) {
            up.setLong(1, mid);
            up.setLong(2, requestId);
            n = up.executeUpdate();
          }
          if (n == 1) {
            holdStore.releaseHold(c, mid, requestId);
            c.commit();
            wal.append(InternalWalEvent.encodeExpired(mid, requestId));
            done++;
          } else {
            c.rollback();
          }
        } catch (SQLException e) {
          c.rollback();
          throw e;
        }
      }
    }
    return done;
  }
}
