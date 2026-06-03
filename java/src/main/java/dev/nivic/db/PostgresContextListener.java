package dev.nivic.db;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Opens {@link Postgres#open(Postgres.Config)} at web-app startup using {@link
 * Postgres.Config#fromEnvironment()}, verifies connectivity, stores the pool in {@link
 * ServletContext}, and closes it on undeploy.
 *
 * <p>Registration: include this class on the classpath and either rely on {@link WebListener}
 * scanning (metadata-complete false) or declare {@code
 * &lt;listener-class&gt;dev.nivic.db.PostgresContextListener&lt;/listener-class&gt;} in {@code
 * web.xml}.
 *
 * <p>Retrieve the pool in servlets or filters:</p>
 *
 * <pre>{@code
 * DataSource ds = PostgresContextListener.getDataSource(servletContext);
 * try (var c = ds.getConnection()) {
 *   // ...
 * }
 * }</pre>
 *
 * <p>If {@link Postgres#verifyConnectivity(DataSource)} fails, {@link
 * ServletContextListener#contextInitialized(ServletContextEvent)} throws {@link IllegalStateException}
 * so the container fails deployment (fail-fast).</p>
 */
@WebListener
public final class PostgresContextListener implements ServletContextListener {

  private static final Logger LOG = LogManager.getLogger(PostgresContextListener.class);

  /** Key for {@link ServletContext#setAttribute(String, Object)} (and {@code getAttribute}). */
  public static final String DATA_SOURCE_ATTRIBUTE = PostgresContextListener.class.getName() + ".dataSource";

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    ServletContext ctx = sce.getServletContext();
    Postgres.Config cfg = Postgres.Config.fromEnvironment();
    HikariDataSource ds = Postgres.open(cfg);
    try {
      Postgres.verifyConnectivity(ds);
    } catch (SQLException e) {
      ds.close();
      throw new IllegalStateException("PostgreSQL connectivity check failed", e);
    }
    ctx.setAttribute(DATA_SOURCE_ATTRIBUTE, ds);
    LOG.info("PostgreSQL pool started: {}", ds.getPoolName());
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    ServletContext ctx = sce.getServletContext();
    Object raw = ctx.getAttribute(DATA_SOURCE_ATTRIBUTE);
    if (raw instanceof HikariDataSource ds) {
      ctx.removeAttribute(DATA_SOURCE_ATTRIBUTE);
      ds.close();
      LOG.info("PostgreSQL pool closed");
    }
  }

  /**
   * @return the shared {@link DataSource}
   * @throws IllegalStateException if the listener has not run or the attribute was cleared
   */
  public static DataSource getDataSource(ServletContext servletContext) {
    Object raw = servletContext.getAttribute(DATA_SOURCE_ATTRIBUTE);
    if (!(raw instanceof DataSource ds)) {
      throw new IllegalStateException(
          "No DataSource under attribute " + DATA_SOURCE_ATTRIBUTE + "; was the listener configured?");
    }
    return ds;
  }
}
