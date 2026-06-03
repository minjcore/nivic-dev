package dev.nivic.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nivic.config.ApplicationProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Builds a pooled {@link javax.sql.DataSource} for PostgreSQL (JDBC 4.2 driver + HikariCP).
 *
 * <p>Overview, URL examples, and env var table: {@link dev.nivic.db the package documentation};
 * servlet wiring: {@link PostgresContextListener}.</p>
 */
public final class Postgres {

  /** Default {@link com.zaxxer.hikari.HikariConfig#setConnectionTimeout(long)} (ms). */
  public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000L;

  /** Default {@link com.zaxxer.hikari.HikariConfig#setIdleTimeout(long)} (ms). */
  public static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000L;

  /** Default {@link com.zaxxer.hikari.HikariConfig#setMaxLifetime(long)} (ms). */
  public static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000L;

  /** {@link com.zaxxer.hikari.HikariConfig#setPoolName(String)} prefix; a unique suffix is added. */
  public static final String DEFAULT_POOL_NAME_PREFIX = "nivic-pg-";

  private Postgres() {}

  /**
   * Immutable settings for {@link #open(Config)}.
   *
   * @param jdbcUrl JDBC URL including {@code jdbc:postgresql:} scheme. Typical form: {@code
   *     jdbc:postgresql://host:5432/dbname?sslmode=require}. User info in the URL is discouraged;
   *     use {@code username} and {@code password}.
   * @param username Postgres role
   * @param password secret for {@code username}
   * @param maximumPoolSize upper bound on concurrent connections held by this pool; see {@link
   *     com.zaxxer.hikari.HikariConfig#setMaximumPoolSize(int)}
   */
  public record Config(String jdbcUrl, String username, String password, int maximumPoolSize) {
    public Config {
      Objects.requireNonNull(jdbcUrl, "jdbcUrl");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(password, "password");
      jdbcUrl = jdbcUrl.trim();
      if (jdbcUrl.isBlank()) {
        throw new IllegalArgumentException("jdbcUrl is blank");
      }
      if (!jdbcUrl.startsWith("jdbc:postgresql:") && !jdbcUrl.startsWith("jdbc:postgres:")) {
        throw new IllegalArgumentException(
            "jdbcUrl should start with jdbc:postgresql: or jdbc:postgres:, got: " + jdbcUrl);
      }
      if (maximumPoolSize < 1) {
        throw new IllegalArgumentException("maximumPoolSize must be >= 1");
      }
    }

    /**
     * Builds a config from the current process environment.
     *
     * <p>Required: {@code JDBC_URL}, {@code JDBC_USER}, {@code JDBC_PASSWORD}. Optional: {@code
     * JDBC_POOL_SIZE} (positive integer; default {@code 10}). {@link NumberFormatException} if
     * {@code JDBC_POOL_SIZE} is not an integer.</p>
     *
     * <p>Each variable is resolved by {@link ApplicationProperties#resolve(String)}: OS environment,
     * then Java system property ({@code -DJDBC_URL=...}), then {@code application.properties} on the
     * classpath.</p>
     */
    public static Config fromEnvironment() {
      return new Config(
          getenvRequired("JDBC_URL"),
          getenvRequired("JDBC_USER"),
          getenvRequired("JDBC_PASSWORD"),
          poolSizeFromEnv());
    }

    private static int poolSizeFromEnv() {
      String raw = envOrProperty("JDBC_POOL_SIZE");
      if (raw == null || raw.isBlank()) {
        return 10;
      }
      int n = Integer.parseInt(raw.trim());
      if (n < 1) {
        throw new IllegalArgumentException("JDBC_POOL_SIZE must be >= 1");
      }
      return n;
    }

    private static String getenvRequired(String name) {
      String v = envOrProperty(name);
      if (v == null || v.isBlank()) {
        throw new IllegalStateException(
            "missing or empty: "
                + name
                + " (env, -D"
                + name
                + "=..., or application.properties)");
      }
      return v;
    }

    private static String envOrProperty(String name) {
      return ApplicationProperties.resolve(name);
    }
  }

  /**
   * Creates a new pool. The returned {@link HikariDataSource} is {@link AutoCloseable}; call
   * {@link HikariDataSource#close()} when the application stops so idle connections are closed.
   *
   * <p>Hikari settings applied in addition to URL/credentials/pool size:</p>
   *
   * <ul>
   *   <li>{@link #DEFAULT_CONNECTION_TIMEOUT_MS} &mdash; max wait when the pool is exhausted
   *   <li>{@link #DEFAULT_IDLE_TIMEOUT_MS} &mdash; idle connection retirement
   *   <li>{@link #DEFAULT_MAX_LIFETIME_MS} &mdash; cap connection age (helps with Postgres-side
   *       timeouts, load balancers)
   *   <li>Explicit {@link org.postgresql.Driver} class so the driver registers before first
   *       connection in edge classloader setups
   * </ul>
   */
  public static HikariDataSource open(Config config) {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(config.jdbcUrl());
    hc.setUsername(config.username());
    hc.setPassword(config.password());
    hc.setMaximumPoolSize(config.maximumPoolSize());

    hc.setConnectionTimeout(DEFAULT_CONNECTION_TIMEOUT_MS);
    hc.setIdleTimeout(DEFAULT_IDLE_TIMEOUT_MS);
    hc.setMaxLifetime(DEFAULT_MAX_LIFETIME_MS);

    hc.setPoolName(DEFAULT_POOL_NAME_PREFIX + Integer.toHexString(System.identityHashCode(hc)));
    hc.setDriverClassName(org.postgresql.Driver.class.getName());

    return new HikariDataSource(hc);
  }

  /**
   * Borrow one connection and run {@link Connection#isValid(int)}. Use during startup to surface
   * bad URLs, auth, or firewall issues immediately.
   *
   * @param dataSource any JDBC {@link DataSource} (e.g. Hikari pool)
   * @param timeoutSeconds maximum seconds for the validity check; driver-dependent
   * @throws SQLException if no connection can be opened or validity fails
   */
  public static void verifyConnectivity(DataSource dataSource, int timeoutSeconds)
      throws SQLException {
    Objects.requireNonNull(dataSource, "dataSource");
    if (timeoutSeconds < 0) {
      throw new IllegalArgumentException("timeoutSeconds must be >= 0");
    }
    try (Connection c = dataSource.getConnection()) {
      if (!c.isValid(timeoutSeconds)) {
        throw new SQLException("Connection.isValid(" + timeoutSeconds + ") returned false");
      }
    }
  }

  /** Same as {@link #verifyConnectivity(DataSource, int) verifyConnectivity(ds, 5)}. */
  public static void verifyConnectivity(DataSource dataSource) throws SQLException {
    verifyConnectivity(dataSource, 5);
  }
}
