package dev.nivic.coa;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

public class JdbcCurrencyManager implements CurrencyManager {
  private final DataSource dataSource;
  private volatile boolean schemaEnsured = false;

  private static final String DDL_CURRENCY = """
      CREATE TABLE IF NOT EXISTS currency (
        code            VARCHAR(10)  PRIMARY KEY,
        name            VARCHAR(128) NOT NULL,
        symbol          VARCHAR(5)   NOT NULL,
        decimal_places  SMALLINT     NOT NULL DEFAULT 0,
        type            VARCHAR(16)  NOT NULL,
        blockchain      VARCHAR(32),
        active          BOOLEAN      NOT NULL DEFAULT true,
        created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
      )
      """;

  private static final String INSERT_CURRENCY =
      "INSERT INTO currency (code, name, symbol, decimal_places, type, blockchain, active) "
          + "VALUES (?, ?, ?, ?, ?, ?, true) ON CONFLICT (code) DO UPDATE "
          + "SET name = EXCLUDED.name, symbol = EXCLUDED.symbol, active = true";

  private static final String SELECT_CURRENCY = "SELECT * FROM currency WHERE code = ?";

  private static final String SELECT_ACTIVE = "SELECT * FROM currency WHERE active = true ORDER BY code";

  private static final String UPDATE_ACTIVE = "UPDATE currency SET active = ? WHERE code = ?";

  public JdbcCurrencyManager(DataSource dataSource) {
    this.dataSource = dataSource;
    ensureSchema();
    seedInitialCurrencies();
  }

  @Override
  public Currency register(String code, String name, String symbol, int decimalPlaces, String type, String blockchain) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(INSERT_CURRENCY)) {
      ps.setString(1, code.toUpperCase());
      ps.setString(2, name);
      ps.setString(3, symbol);
      ps.setInt(4, decimalPlaces);
      ps.setString(5, type.toUpperCase());
      if (blockchain != null) ps.setString(6, blockchain.toUpperCase()); else ps.setNull(6, Types.VARCHAR);
      ps.executeUpdate();
      return find(code).orElseThrow();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to register currency: " + code, e);
    }
  }

  @Override
  public Optional<Currency> find(String code) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_CURRENCY)) {
      ps.setString(1, code.toUpperCase());
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return Optional.of(new Currency(
              rs.getString("code"),
              rs.getString("name"),
              rs.getString("symbol"),
              rs.getInt("decimal_places"),
              rs.getString("type"),
              rs.getString("blockchain"),
              rs.getBoolean("active")
          ));
        }
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to find currency: " + code, e);
    }
    return Optional.empty();
  }

  @Override
  public Set<Currency> listActive() {
    ensureSchema();
    Set<Currency> result = new LinkedHashSet<>();
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(SELECT_ACTIVE)) {
      while (rs.next()) {
        result.add(new Currency(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("symbol"),
            rs.getInt("decimal_places"),
            rs.getString("type"),
            rs.getString("blockchain"),
            rs.getBoolean("active")
        ));
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to list active currencies", e);
    }
    return result;
  }

  @Override
  public void activate(String code) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(UPDATE_ACTIVE)) {
      ps.setBoolean(1, true);
      ps.setString(2, code.toUpperCase());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to activate currency: " + code, e);
    }
  }

  @Override
  public void deactivate(String code) {
    ensureSchema();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(UPDATE_ACTIVE)) {
      ps.setBoolean(1, false);
      ps.setString(2, code.toUpperCase());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to deactivate currency: " + code, e);
    }
  }

  private void ensureSchema() {
    if (schemaEnsured) return;
    synchronized (this) {
      if (schemaEnsured) return;
      try (Connection c = dataSource.getConnection();
          Statement st = c.createStatement()) {
        st.execute(DDL_CURRENCY);
        schemaEnsured = true;
      } catch (SQLException e) {
        throw new RuntimeException("Failed to ensure currency schema", e);
      }
    }
  }

  private void seedInitialCurrencies() {
    try {
      register("USDT", "Tether", "$", 18, "CRYPTO", "ETHEREUM");
      register("USDC", "USD Coin", "$", 18, "CRYPTO", "ETHEREUM");
      register("ETH", "Ethereum", "Ξ", 18, "CRYPTO", "ETHEREUM");
      register("BTC", "Bitcoin", "₿", 8, "CRYPTO", "BITCOIN");
      register("VND", "Vietnamese Dong", "₫", 0, "FIAT", null);
      register("USD", "United States Dollar", "$", 2, "FIAT", null);
    } catch (RuntimeException e) {
      // Currencies may already exist, that's ok
    }
  }
}
