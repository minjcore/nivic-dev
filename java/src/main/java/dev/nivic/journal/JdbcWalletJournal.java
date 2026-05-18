package dev.nivic.journal;

import dev.nivic.sevlet.SevletWalletPayload;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Currency;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * PostgreSQL double-entry journal: {@code wallet_journal_entry} + {@code wallet_journal_line}
 * (balanced debit/credit for wire {@code amount}).
 */
public final class JdbcWalletJournal implements WalletJournal {

  private static final String DDL_ENTRY =
      """
      CREATE TABLE IF NOT EXISTS wallet_journal_entry (
        mid BIGINT NOT NULL,
        request_id BIGINT NOT NULL,
        order_id BIGINT NOT NULL,
        input BIGINT NOT NULL,
        currency_code VARCHAR(3) NOT NULL,
        extra_data BYTEA NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        PRIMARY KEY (mid, request_id)
      )
      """;

  private static final String DDL_LINE =
      """
      CREATE TABLE IF NOT EXISTS wallet_journal_line (
        mid BIGINT NOT NULL,
        request_id BIGINT NOT NULL,
        line_no SMALLINT NOT NULL,
        account INTEGER NOT NULL,
        debit_minor BIGINT NOT NULL,
        credit_minor BIGINT NOT NULL,
        PRIMARY KEY (mid, request_id, line_no),
        CONSTRAINT wallet_journal_line_entry_fk
          FOREIGN KEY (mid, request_id)
          REFERENCES wallet_journal_entry (mid, request_id)
      )
      """;

  private static final String INSERT_ENTRY =
      "INSERT INTO wallet_journal_entry (mid, request_id, order_id, input, currency_code,"
          + " extra_data) VALUES (?, ?, ?, ?, ?, ?)";

  private static final String INSERT_LINE =
      "INSERT INTO wallet_journal_line (mid, request_id, line_no, account, debit_minor,"
          + " credit_minor) VALUES (?, ?, ?, ?, ?, ?)";

  private final DataSource dataSource;
  private volatile boolean tablesEnsured;

  public JdbcWalletJournal(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  private void ensureTables() throws SQLException {
    if (tablesEnsured) {
      return;
    }
    synchronized (this) {
      if (tablesEnsured) {
        return;
      }
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_ENTRY);
        st.execute(DDL_LINE);
      }
      tablesEnsured = true;
    }
  }

  @Override
  public void append(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    long amount = payload.amount();
    try {
      ensureTables();
    } catch (SQLException e) {
      throw new IllegalStateException("journal DDL failed", e);
    }

    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (PreparedStatement ps = c.prepareStatement(INSERT_ENTRY)) {
          ps.setLong(1, payload.mid());
          ps.setLong(2, payload.requestId());
          ps.setLong(3, payload.orderId());
          ps.setLong(4, payload.command());
          ps.setString(5, currency.getCurrencyCode());
          ps.setBytes(6, payload.extraData());
          ps.executeUpdate();
        }
        insertLine(c, payload.mid(), payload.requestId(), 1, payload.debit(), amount, 0L);
        insertLine(c, payload.mid(), payload.requestId(), 2, payload.credit(), 0L, amount);
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(
          "journal append failed: mid="
              + payload.mid()
              + " requestId="
              + payload.requestId(),
          e);
    }
  }

  private static void insertLine(
      Connection c, long mid, long requestId, int lineNo, int account, long debit, long credit)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(INSERT_LINE)) {
      ps.setLong(1, mid);
      ps.setLong(2, requestId);
      ps.setInt(3, lineNo);
      ps.setInt(4, account);
      ps.setLong(5, debit);
      ps.setLong(6, credit);
      ps.executeUpdate();
    }
  }
}
