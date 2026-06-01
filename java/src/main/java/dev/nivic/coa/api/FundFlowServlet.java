package dev.nivic.coa.api;

import dev.nivic.db.PostgresContextListener;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * REST endpoint for the GtelPay fund-flow ledger, mounted at {@code /api/fundflow/*}.
 *
 * <p>Thin adapter: resolves the shared {@link javax.sql.DataSource} from
 * {@link PostgresContextListener}, delegates to {@link FundFlowApi}, and writes the status + JSON.
 * Requires {@code PostgresContextListener} (JDBC_URL/USER/PASSWORD env) — for gtelpay_new point
 * {@code JDBC_URL=jdbc:postgresql://.../gtelpay_new}.</p>
 *
 * <p>Examples:
 * <pre>
 *   POST /api/fundflow/topup/receive   {"amountMinor":100000,"bankRef":"BANK-1"}
 *   POST /api/fundflow/topup/confirm   {"amountMinor":100000,"feeMinor":1000,"confirmRef":"CONF-1"}
 *   GET  /api/fundflow/trans?ref=BANK-1
 *   GET  /api/fundflow/reports/trial
 *   GET  /api/fundflow/health
 * </pre>
 */
@WebServlet(name = "FundFlowServlet", urlPatterns = "/api/fundflow/*")
public final class FundFlowServlet extends HttpServlet {

  private static final int MAX_BODY = 64 * 1024;

  private volatile FundFlowApi api;

  private FundFlowApi api() {
    FundFlowApi a = api;
    if (a == null) {
      synchronized (this) {
        a = api;
        if (a == null) {
          a = new FundFlowApi(PostgresContextListener.getDataSource(getServletContext()));
          api = a;
        }
      }
    }
    return a;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    dispatch("GET", req, resp);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    dispatch("POST", req, resp);
  }

  private void dispatch(String method, HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    String path = req.getPathInfo(); // part after /api/fundflow
    String body = null;
    if ("POST".equals(method)) {
      if (req.getContentLengthLong() > MAX_BODY) { resp.sendError(413); return; }
      byte[] raw = req.getInputStream().readNBytes(MAX_BODY + 1);
      if (raw.length > MAX_BODY) { resp.sendError(413); return; }
      body = new String(raw, StandardCharsets.UTF_8);
    }

    ApiResponse r;
    try {
      r = api().handle(method, path, req.getQueryString(), body);
    } catch (RuntimeException e) {
      r = ApiResponse.error(500, "INTERNAL", String.valueOf(e.getMessage()));
    }

    resp.setStatus(r.status());
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType("application/json; charset=UTF-8");
    resp.getOutputStream().write(r.json().getBytes(StandardCharsets.UTF_8));
  }
}
