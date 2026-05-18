/**
 * JDBC access to PostgreSQL using a {@link com.zaxxer.hikari.HikariDataSource HikariCP} pool.
 *
 * <h2>Quick start</h2>
 *
 * <p>For a one-off script or test you may open and close the pool around a block:</p>
 *
 * <pre>{@code
 * var cfg =
 *     new Postgres.Config(
 *         "jdbc:postgresql://localhost:5432/app",
 *         "app_user",
 *         "secret",
 *         10);
 * try (var ds = Postgres.open(cfg)) {
 *   Postgres.verifyConnectivity(ds);
 *   try (var c = ds.getConnection();
 *        var st = c.prepareStatement("SELECT 1")) {
 *     try (var rs = st.executeQuery()) {
 *       rs.next();
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>In a long-running server, keep the pool open for the process lifetime and use {@link
 * dev.nivic.db.PostgresContextListener} (or equivalent) to tie it to application startup/shutdown.
 * </p>
 *
 * <h2>Configuration ({@link dev.nivic.db.Postgres.Config#fromEnvironment()})</h2>
 *
 * <p>Keys are resolved in order: OS environment variable, Java system property ({@code -DKEY=}),
 * then {@code application.properties} on the classpath (see {@link
 * dev.nivic.config.ApplicationProperties}).</p>
 *
 * <table border="1" summary="Configuration keys">
 *   <caption>Variables for the JDBC pool</caption>
 *   <tr><th>Name</th><th>Required</th><th>Description</th></tr>
 *   <tr>
 *     <td>{@code JDBC_URL}</td>
 *     <td>yes</td>
 *     <td>Full JDBC URL, normally {@code jdbc:postgresql://host:port/dbname}. SSL and driver
 *         options are appended as query parameters (see PostgreSQL JDBC docs).</td>
 *   </tr>
 *   <tr>
 *     <td>{@code JDBC_USER}</td>
 *     <td>yes</td>
 *     <td>Database role used for every connection in the pool.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code JDBC_PASSWORD}</td>
 *     <td>yes</td>
 *     <td>Password for {@code JDBC_USER}. Load from a secret store in production; never commit to
 *         git.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code JDBC_POOL_SIZE}</td>
 *     <td>no</td>
 *     <td>{@link com.zaxxer.hikari.HikariConfig#setMaximumPoolSize(int) maximumPoolSize}, default
 *         {@code 10}. Must stay below server {@code max_connections} minus other clients.</td>
 *   </tr>
 * </table>
 *
 * <h2>Lifecycle and threads</h2>
 *
 * <p>The pool is thread-safe. Each call to {@link javax.sql.DataSource#getConnection()} borrows
 * a physical connection (or waits up to the configured connection acquisition timeout). Always use
 * try-with-resources on {@link java.sql.Connection}, {@link java.sql.Statement}, and {@link
 * java.sql.ResultSet} so they are closed promptly and returned to the pool.</p>
 *
 * <p>Call {@link com.zaxxer.hikari.HikariDataSource#close()} when the application exits or the web
 * app is undeployed so sockets and server sessions are released.</p>
 *
 * <h2>TLS</h2>
 *
 * <p>For remote Postgres, prefer TLS. Example fragment: {@code
 * ?sslmode=verify-full&amp;sslrootcert=/path/to/ca.crt}. Modes and file parameters depend on your
 * infra; do not put secrets in logs.</p>
 */
package dev.nivic.db;
