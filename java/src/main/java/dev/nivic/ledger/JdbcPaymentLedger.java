package dev.nivic.ledger;

import dev.nivic.payment.AccountHoldStore;
import dev.nivic.payment.ConfirmPayloadParser;
import dev.nivic.payment.NoopAccountHoldStore;
import dev.nivic.sevlet.SevletWalletPayload;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * PostgreSQL-backed {@code payment_ledger}: initial {@link #append} then {@link #appendAfterWallet}
 * upsert keeps {@code order_id} from the first row. Order intents add TTL, challenge, and optional
 * {@link AccountHoldStore} rows in the same transaction as the intent insert.
 */
public final class JdbcPaymentLedger implements PaymentLedger {

  private static final String DDL_BASE =
      """
      CREATE TABLE IF NOT EXISTS payment_ledger (
        mid BIGINT NOT NULL,
        request_id BIGINT NOT NULL,
        order_id BIGINT NOT NULL,
        input BIGINT NOT NULL,
        amount_minor BIGINT NOT NULL,
        debit INTEGER,
        credit INTEGER,
        currency_code VARCHAR(3) NOT NULL,
        extra_data BYTEA NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (mid, request_id)
      )
      """;

  private static final String INSERT_FULL =
      "INSERT INTO payment_ledger (mid, request_id, order_id, input, amount_minor, debit, credit,"
          + " currency_code, extra_data, intent_status, expires_at, confirm_challenge) VALUES (?, ?,"
          + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String INSERT_LEGACY =
      "INSERT INTO payment_ledger (mid, request_id, order_id, input, amount_minor, debit, credit,"
          + " currency_code, extra_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String ST_SETTLED = CoreLedgerStatus.SETTLED.name();
  private static final String ST_CANCELLED = CoreLedgerStatus.CANCELLED.name();
  private static final String SQL_IN_OPEN =
      CoreLedgerStatus.sqlInList(CoreLedgerStatus::isOpenForConfirmation);

  /** Partial unique index; keep in sync with {@code schema/10_payment_ledger_open_order_unique.sql}. */
  static final String PAYMENT_LEDGER_UIDX_OPEN_MID_ORDER = "payment_ledger_uidx_open_mid_order";

  private static final String SELECT_OPEN_ORDER_CONFLICT =
      "SELECT request_id FROM payment_ledger WHERE mid = ? AND order_id = ? AND request_id <> ?"
          + " AND intent_status IN ("
          + SQL_IN_OPEN
          + ") LIMIT 1";

  private static final String UPSERT_AFTER_WALLET =
      "INSERT INTO payment_ledger (mid, request_id, order_id, input, amount_minor, debit, credit,"
          + " currency_code, extra_data, intent_status, expires_at, confirm_challenge, confirmed_at) VALUES"
          + " (?, ?, ?, ?, ?, ?, ?, ?, ?, '"
          + ST_SETTLED
          + "', NULL, NULL, NOW())"
          + " ON CONFLICT (mid, request_id) DO UPDATE SET"
          + " input = EXCLUDED.input,"
          + " amount_minor = EXCLUDED.amount_minor,"
          + " debit = EXCLUDED.debit,"
          + " credit = EXCLUDED.credit,"
          + " currency_code = EXCLUDED.currency_code,"
          + " extra_data = EXCLUDED.extra_data,"
          + " intent_status = '"
          + ST_SETTLED
          + "',"
          + " confirmed_at = COALESCE(payment_ledger.confirmed_at, NOW())";

  private static final String SETTLE_BY_ORDER =
      "UPDATE payment_ledger SET"
          + " input = ?,"
          + " amount_minor = ?,"
          + " debit = ?,"
          + " credit = ?,"
          + " currency_code = ?,"
          + " extra_data = ?,"
          + " intent_status = '"
          + ST_SETTLED
          + "',"
          + " confirmed_at = NOW(),"
          + " cancel_reason = NULL"
          + " WHERE mid = ? AND order_id = ?"
          + " AND intent_status IN ("
          + SQL_IN_OPEN
          + ")"
          + " AND confirm_challenge = ?"
          + " RETURNING request_id";

  private static final String REJECT_BY_ORDER =
      "UPDATE payment_ledger SET"
          + " intent_status = '"
          + ST_CANCELLED
          + "',"
          + " cancel_reason = 'USER_REJECT',"
          + " confirmed_at = NULL"
          + " WHERE mid = ? AND order_id = ?"
          + " AND intent_status IN ("
          + SQL_IN_OPEN
          + ")"
          + " AND confirm_challenge = ?"
          + " RETURNING request_id";

  private final DataSource dataSource;
  private final AccountHoldStore holdStore;
  private volatile boolean tableEnsured;

  public JdbcPaymentLedger(DataSource dataSource) {
    this(dataSource, NoopAccountHoldStore.INSTANCE);
  }

  public JdbcPaymentLedger(DataSource dataSource, AccountHoldStore holdStore) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.holdStore = Objects.requireNonNull(holdStore, "holdStore");
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
        st.execute(DDL_BASE);
        st.execute("ALTER TABLE payment_ledger ALTER COLUMN debit DROP NOT NULL");
        st.execute("ALTER TABLE payment_ledger ALTER COLUMN credit DROP NOT NULL");
        st.execute("ALTER TABLE payment_ledger ADD COLUMN IF NOT EXISTS intent_status VARCHAR(32)");
        st.execute("ALTER TABLE payment_ledger ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ");
        st.execute("ALTER TABLE payment_ledger ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ");
        st.execute("ALTER TABLE payment_ledger ADD COLUMN IF NOT EXISTS confirm_challenge BYTEA");
        st.execute("ALTER TABLE payment_ledger ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(512)");
        st.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS "
                + PAYMENT_LEDGER_UIDX_OPEN_MID_ORDER
                + " ON payment_ledger (mid, order_id) WHERE intent_status IN ("
                + SQL_IN_OPEN
                + ")");
      }
      tableEnsured = true;
    }
  }

  private static boolean isOpenOrderUniqueViolation(SQLException e) {
    return "23505".equals(e.getSQLState())
        && e.getMessage() != null
        && e.getMessage().contains(PAYMENT_LEDGER_UIDX_OPEN_MID_ORDER);
  }

  @Override
  public void requireNoConflictingOpenIntent(long mid, long orderId, long requestId) {
    try {
      ensureTable();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(SELECT_OPEN_ORDER_CONFLICT)) {
        ps.setLong(1, mid);
        ps.setLong(2, orderId);
        ps.setLong(3, requestId);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            throw new OrderIdConflictException(mid, orderId, rs.getLong(1));
          }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          "payment_ledger open-order check failed: mid=" + mid + " orderId=" + orderId, e);
    }
  }

  @Override
  public void append(SevletWalletPayload payload, Currency currency, PaymentIntentAppendCtx ctx) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(ctx, "ctx");
    try {
      ensureTable();
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          if (ctx.isOrderIntent()) {
            try (PreparedStatement ps = c.prepareStatement(INSERT_FULL)) {
              ps.setLong(1, payload.mid());
              ps.setLong(2, payload.requestId());
              ps.setLong(3, payload.orderId());
              ps.setLong(4, payload.command());
              ps.setLong(5, payload.amount());
              ps.setNull(6, Types.INTEGER);
              ps.setNull(7, Types.INTEGER);
              ps.setString(8, currency.getCurrencyCode());
              ps.setBytes(9, payload.extraData());
              ps.setString(10, CoreLedgerStatus.defaultForNewIntentRow().name());
              ps.setTimestamp(
                  11,
                  Timestamp.from(Instant.now().plus(ctx.ttlMinutes(), ChronoUnit.MINUTES)));
              ps.setBytes(12, ctx.confirmChallenge());
              ps.executeUpdate();
            }
            if (payload.debit() > 0 && payload.amount() > 0L) {
              holdStore.placeHold(c, payload.debit(), payload.amount(), payload.mid(), payload.requestId());
            }
          } else {
            try (PreparedStatement ps = c.prepareStatement(INSERT_LEGACY)) {
              ps.setLong(1, payload.mid());
              ps.setLong(2, payload.requestId());
              ps.setLong(3, payload.orderId());
              ps.setLong(4, payload.command());
              ps.setLong(5, payload.amount());
              ps.setNull(6, Types.INTEGER);
              ps.setNull(7, Types.INTEGER);
              ps.setString(8, currency.getCurrencyCode());
              ps.setBytes(9, payload.extraData());
              ps.executeUpdate();
            }
          }
          c.commit();
        } catch (SQLException e) {
          c.rollback();
          if (ctx.isOrderIntent() && isOpenOrderUniqueViolation(e)) {
            throw new OrderIdConflictException(payload.mid(), payload.orderId(), -1L);
          }
          throw e;
        }
      }
    } catch (OrderIdConflictException e) {
      throw e;
    } catch (SQLException e) {
      throw new IllegalStateException(
          "payment_ledger append failed: mid="
              + payload.mid()
              + " requestId="
              + payload.requestId(),
          e);
    }
  }

  @Override
  public void appendAfterWallet(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    try {
      ensureTable();
      try (Connection c = dataSource.getConnection();
          PreparedStatement ps = c.prepareStatement(UPSERT_AFTER_WALLET)) {
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
          "payment_ledger appendAfterWallet failed: mid="
              + payload.mid()
              + " requestId="
              + payload.requestId(),
          e);
    }
  }

  @Override
  public void settleIntentByOrder(SevletWalletPayload confirmPayload, Currency currency) {
    Objects.requireNonNull(confirmPayload, "confirmPayload");
    Objects.requireNonNull(currency, "currency");
    byte[] ch = ConfirmPayloadParser.challenge(confirmPayload.extraData());
    try {
      ensureTable();
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          long intentRequestId;
          try (PreparedStatement ps = c.prepareStatement(SETTLE_BY_ORDER)) {
            ps.setLong(1, confirmPayload.command());
            ps.setLong(2, confirmPayload.amount());
            ps.setInt(3, confirmPayload.debit());
            ps.setInt(4, confirmPayload.credit());
            ps.setString(5, currency.getCurrencyCode());
            ps.setBytes(6, confirmPayload.extraData());
            ps.setLong(7, confirmPayload.mid());
            ps.setLong(8, confirmPayload.orderId());
            ps.setBytes(9, ch);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw new IllegalStateException(
                    "confirm settle failed: no matching intent for mid="
                        + confirmPayload.mid()
                        + " orderId="
                        + confirmPayload.orderId());
              }
              intentRequestId = rs.getLong(1);
            }
          }
          holdStore.releaseHold(c, confirmPayload.mid(), intentRequestId);
          c.commit();
        } catch (SQLException e) {
          c.rollback();
          throw e;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("settleIntentByOrder failed", e);
    }
  }

  @Override
  public void rejectIntentByOrder(SevletWalletPayload rejectPayload, Currency currency) {
    Objects.requireNonNull(rejectPayload, "rejectPayload");
    Objects.requireNonNull(currency, "currency");
    byte[] ch = ConfirmPayloadParser.challenge(rejectPayload.extraData());
    try {
      ensureTable();
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          long intentRequestId;
          try (PreparedStatement ps = c.prepareStatement(REJECT_BY_ORDER)) {
            ps.setLong(1, rejectPayload.mid());
            ps.setLong(2, rejectPayload.orderId());
            ps.setBytes(3, ch);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                throw new IllegalStateException(
                    "reject failed: no matching intent for mid="
                        + rejectPayload.mid()
                        + " orderId="
                        + rejectPayload.orderId());
              }
              intentRequestId = rs.getLong(1);
            }
          }
          holdStore.releaseHold(c, rejectPayload.mid(), intentRequestId);
          c.commit();
        } catch (SQLException e) {
          c.rollback();
          throw e;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("rejectIntentByOrder failed", e);
    }
  }
}
