package dev.nivic.coa.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nivic.coa.cmd.*;
import dev.nivic.coa.JdbcFundFlowLedger;
import dev.nivic.coa.report.BalanceSheet;
import dev.nivic.coa.report.FundFlowReports;
import dev.nivic.coa.report.ProfitAndLoss;
import dev.nivic.coa.report.TrialBalance;
import dev.nivic.db.Postgres;
import dev.nivic.saving.AccrueInterestCmd;
import dev.nivic.saving.DepositCmd;
import dev.nivic.saving.JdbcSavingLedger;
import dev.nivic.saving.OpenAccountCmd;
import dev.nivic.saving.SavAccount;
import dev.nivic.saving.SavInterestCalc;
import dev.nivic.saving.SavingLedger;
import dev.nivic.saving.WithdrawalCmd;
import java.util.UUID;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Standalone control-plane demo for the GtelPay fund-flow ledger.
 *
 * <p>Serves a realtime dashboard of {@code coa_account} balances plus buttons to fire each
 * fund-flow scenario, so balances visibly move as journals post. Uses the JDK built-in
 * {@link HttpServer} (no servlet container) and the existing {@link Postgres} pool.</p>
 *
 * <p>Run:
 * <pre>
 *   JDBC_URL=jdbc:postgresql://localhost:5432/gtelpay_new \
 *   JDBC_USER=postgres JDBC_PASSWORD=postgres \
 *   mvn exec:java -Dexec.mainClass=dev.nivic.coa.demo.ControlPlaneServer
 * </pre>
 * Then open http://localhost:8090/</p>
 */
public final class ControlPlaneServer {

  private static final int PORT = Integer.getInteger("CONTROL_PLANE_PORT", 8095);

  /** owner_mid dùng cho mọi sổ tiết kiệm tạo từ control-plane. */
  private static final long DEMO_SAVING_MID = 1001L;

  private final DataSource ds;
  private final JdbcFundFlowLedger ledger;
  private final FundFlowReports reports;
  private final SavingLedger saving;

  private ControlPlaneServer(DataSource ds) {
    this.ds = ds;
    this.ledger = new JdbcFundFlowLedger(ds);
    this.reports = new FundFlowReports(ds);
    this.saving = new JdbcSavingLedger(ds);
  }

  public static void main(String[] args) throws Exception {
    DataSource ds = Postgres.open(Postgres.Config.fromEnvironment());
    Postgres.verifyConnectivity(ds);
    ControlPlaneServer app = new ControlPlaneServer(ds);
    // Touch each ledger once to ensure schema + seed exist.
    app.ledger.isDoubleEntryBalanced();
    app.saving.findAccount(UUID.randomUUID()); // triggers savings ensureTables + system accounts

    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
    server.createContext("/", app::handleIndex);
    server.createContext("/api/state", app::handleState);
    server.createContext("/api/demo/", app::handleDemo);
    server.createContext("/api/reset", app::handleReset);
    server.createContext("/api/trans", app::handleTrans);
    server.setExecutor(null);
    server.start();

    System.out.println();
    System.out.println("  GtelPay Control Plane");
    System.out.println("  ---------------------");
    System.out.println("  Dashboard:  http://localhost:" + PORT + "/");
    System.out.println("  State API:  http://localhost:" + PORT + "/api/state");
    System.out.println("  Press Ctrl+C to stop.");
    System.out.println();
  }

  // ── Handlers ─────────────────────────────────────────────────────────────────

  private void handleIndex(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "method not allowed"); return; }
    send(ex, 200, "text/html; charset=utf-8", INDEX_HTML);
  }

  private void handleState(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "method not allowed"); return; }
    try {
      send(ex, 200, "application/json; charset=utf-8", buildStateJson());
    } catch (SQLException e) {
      send(ex, 500, "application/json", "{\"error\":" + jsonStr(e.getMessage()) + "}");
    }
  }

  private void handleReset(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "method not allowed"); return; }
    try (Connection c = ds.getConnection(); var st = c.createStatement()) {
      // COA fund-flow
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
      // Savings (separate subsystem): drop journals + user accounts, keep system accounts
      st.execute("TRUNCATE sav_trans_data, sav_trans CASCADE");
      st.execute("DELETE FROM sav_account WHERE owner_mid <> 0");
      st.execute("UPDATE sav_account SET debits_pending = 0, debits_posted = 0,"
          + " credits_pending = 0, credits_posted = 0, version = 0 WHERE owner_mid = 0");
      send(ex, 200, "application/json", "{\"ok\":true}");
    } catch (SQLException e) {
      send(ex, 500, "application/json", "{\"error\":" + jsonStr(e.getMessage()) + "}");
    }
  }

  private void handleDemo(HttpExchange ex) throws IOException {
    if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "method not allowed"); return; }
    String flow = ex.getRequestURI().getPath().substring("/api/demo/".length());
    String ref = "DEMO-" + System.nanoTime();
    try {
      String msg = runFlow(flow, ref);
      send(ex, 200, "application/json", "{\"ok\":true,\"flow\":" + jsonStr(flow) + ",\"msg\":" + jsonStr(msg) + "}");
    } catch (RuntimeException e) {
      send(ex, 400, "application/json", "{\"ok\":false,\"flow\":" + jsonStr(flow) + ",\"error\":" + jsonStr(e.getMessage()) + "}");
    }
  }

  /**
   * Tra cứu một giao dịch COA theo trans_id (UUID) hoặc ref_id, trả về trạng thái + bút toán.
   * {@code GET /api/trans?id=<uuid>} hoặc {@code /api/trans?ref=<ref_id>}.
   */
  private void handleTrans(HttpExchange ex) throws IOException {
    if (!"GET".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "method not allowed"); return; }
    String q = ex.getRequestURI().getQuery();
    String id = paramOf(q, "id");
    String ref = paramOf(q, "ref");
    try {
      dev.nivic.coa.CoaTrans t = null;
      if (id != null && !id.isBlank()) {
        t = ledger.findTrans(UUID.fromString(id.trim()));
      } else if (ref != null && !ref.isBlank()) {
        t = ledger.findTransByRefId(ref.trim());
      }
      if (t == null) {
        send(ex, 404, "application/json", "{\"found\":false}");
        return;
      }
      send(ex, 200, "application/json; charset=utf-8", transJson(t));
    } catch (IllegalArgumentException e) {
      send(ex, 400, "application/json", "{\"found\":false,\"error\":" + jsonStr("id không hợp lệ (UUID)") + "}");
    } catch (RuntimeException e) {
      send(ex, 500, "application/json", "{\"found\":false,\"error\":" + jsonStr(e.getMessage()) + "}");
    }
  }

  private static String transJson(dev.nivic.coa.CoaTrans t) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"found\":true")
      .append(",\"id\":").append(jsonStr(t.id().toString()))
      .append(",\"ref\":").append(jsonStr(t.refId()))
      .append(",\"memo\":").append(jsonStr(t.memo()))
      .append(",\"at\":").append(jsonStr(String.valueOf(t.createdAt())))
      .append(",\"debitTotal\":").append(t.debitTotal())
      .append(",\"creditTotal\":").append(t.creditTotal())
      // "đủ bút toán": cân bằng DR=CR và có ít nhất 2 chân
      .append(",\"balanced\":").append(t.isBalanced())
      .append(",\"status\":").append(jsonStr(t.isBalanced() && t.lines().size() >= 2 ? "SUCCESS" : "INVALID"))
      .append(",\"lines\":[");
    boolean first = true;
    for (dev.nivic.coa.CoaTransLine l : t.lines()) {
      if (!first) sb.append(',');
      first = false;
      sb.append('{')
        .append("\"lineNo\":").append(l.lineNo()).append(',')
        .append("\"account\":").append(jsonStr(l.accountCode())).append(',')
        .append("\"name\":").append(jsonStr(l.accountName())).append(',')
        .append("\"debit\":").append(l.debitMinor()).append(',')
        .append("\"credit\":").append(l.creditMinor())
        .append('}');
    }
    sb.append("]}");
    return sb.toString();
  }

  private static String paramOf(String query, String key) {
    if (query == null) return null;
    for (String kv : query.split("&")) {
      int eq = kv.indexOf('=');
      if (eq > 0 && kv.substring(0, eq).equals(key)) {
        return java.net.URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  /** Sổ tiết kiệm user mới nhất chưa đóng (flag CLOSED = 0x01); null nếu chưa có. */
  private UUID currentSavingAccount() {
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM sav_account WHERE owner_mid <> 0 AND (flags & 1) = 0"
                + " ORDER BY opened_at DESC LIMIT 1")) {
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1, UUID.class) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("currentSavingAccount failed", e);
    }
  }

  /** Runs a canned scenario (full multi-step flow) so balances move visibly. */
  private String runFlow(String flow, String ref) {
    return switch (flow) {
      case "topup" -> {
        ledger.receiveTopUp(new TopUpReceiveCmd(100_000L, ref + "-R", null));
        ledger.confirmTopUp(new TopUpConfirmCmd(100_000L, 1_000L, ref + "-C", null));
        yield "Nạp 100,000đ (phí 1,000đ) → ví user +99,000đ";
      }
      case "withdraw" -> {
        ledger.initWithdraw(new WithdrawInitCmd(50_000L, 1_000L, ref + "-I", null));
        ledger.settleWithdraw(new WithdrawSettleCmd(50_000L, 1_000L, ref + "-S", null));
        yield "Rút 50,000đ (phí 1,000đ)";
      }
      case "internal" -> {
        ledger.initInternalTransfer(new InternalTransferInitCmd(30_000L, 1_000L, ref + "-I", null));
        ledger.settleInternalTransfer(new InternalTransferSettleCmd(30_000L, 1_000L, ref + "-S", null));
        yield "Chuyển nội bộ 30,000đ (phí 1,000đ)";
      }
      case "ibft" -> {
        ledger.initIbftTransfer(new IbftInitCmd(40_000L, 1_000L, ref + "-I", null));
        ledger.settleIbftTransfer(new IbftSettleCmd(40_000L, 1_000L, 500L, ref + "-S", null));
        yield "IBFT 40,000đ (phí 1,000đ, Napas 500đ)";
      }
      case "qrpos" -> {
        ledger.receiveQrPos(new QrPosReceiveCmd(80_000L, 500L, ref + "-R", null));
        ledger.creditMerchantQrPos(new QrPosCreditMerchantCmd(80_000L, ref + "-C", null));
        yield "QR/POS 80,000đ (phí VPBank 500đ) → ví merchant";
      }
      case "walletpay" -> {
        ledger.initWalletPayment(new WalletPaymentInitCmd(60_000L, ref + "-I", null));
        ledger.settleWalletPayment(new WalletPaymentSettleCmd(60_000L, ref + "-S", null));
        yield "Thanh toán ví 60,000đ → ví merchant";
      }
      case "payroll" -> {
        ledger.initPayroll(new PayrollInitCmd(500_000L, 5_000L, 5, ref + "-I", null));
        ledger.disbursePayroll(new PayrollDisburseCmd(500_000L, 5_000L, 2_500L, ref + "-D", null));
        yield "Chi lương 5 NV × 100,000đ (phí 5,000đ, Napas 2,500đ)";
      }
      case "disbursement" -> {
        ledger.prefundDisbursement(new DisbursementPrefundCmd(200_000L, ref + "-PF", null));
        ledger.initDisbursement(new DisbursementInitCmd(100_000L, 1_000L, ref + "-I", null));
        ledger.settleDisbursement(new DisbursementSettleCmd(100_000L, 1_000L, 500L, ref + "-S", null));
        yield "Chi hộ 100,000đ (pre-fund 200,000đ, phí 1,000đ, Napas 500đ)";
      }
      case "eod" -> {
        long merchant = -ledger.getBalance("2120"); // số tiền merchant đang giữ
        if (merchant <= 0) yield "Không có số dư merchant (2120) để settle";
        long mdr = Math.max(1L, merchant / 100); // ~1%
        long net = merchant - mdr;
        long napas = 500L;
        ledger.eodInitClearing(new EodClearingInitCmd(merchant, ref + "-CLR", null));
        ledger.eodReconcile(new EodReconcileCmd(merchant, mdr, ref + "-REC", null));
        ledger.eodRecognizeMdr(new EodRecognizeMdrCmd(mdr, ref + "-MDR", null));
        ledger.eodSettleOutbound(new EodSettleOutboundCmd(net, napas, ref + "-OUT", null));
        yield "EOD settle " + fmt(merchant) + "đ (MDR " + fmt(mdr) + "đ, Napas " + fmt(napas) + "đ)";
      }
      case "close" -> {
        var pl = reports.profitAndLoss();
        ledger.closePeriod(new PeriodCloseCmd(ref + "-CLOSE", null));
        yield "Khoá sổ — kết chuyển lãi/lỗ " + fmt(pl.netProfit()) + "đ → 6100";
      }
      // ── Savings ──
      case "sav_open" -> {
        SavAccount a = saving.openAccount(OpenAccountCmd.demand(DEMO_SAVING_MID, "VND"));
        yield "Mở sổ tiết kiệm " + a.accountNo();
      }
      case "sav_deposit" -> {
        UUID id = currentSavingAccount();
        if (id == null) id = saving.openAccount(OpenAccountCmd.demand(DEMO_SAVING_MID, "VND")).id();
        saving.deposit(new DepositCmd(id, 1_000_000L, "VND", null, null, null, "Gửi tiết kiệm"));
        yield "Gửi tiết kiệm 1,000,000đ";
      }
      case "sav_withdraw" -> {
        UUID id = currentSavingAccount();
        if (id == null) yield "Chưa có sổ tiết kiệm — bấm Gửi tiền trước";
        saving.withdrawal(new WithdrawalCmd(id, 300_000L, "VND", false, null, null, null, "Rút tiết kiệm"));
        yield "Rút tiết kiệm 300,000đ";
      }
      case "sav_interest" -> {
        UUID id = currentSavingAccount();
        if (id == null) yield "Chưa có sổ tiết kiệm để tính lãi";
        long bal = saving.findAccount(id).availableBalance();
        long interest = SavInterestCalc.compute(bal, 650, 30); // 6.5%/năm × 30 ngày
        if (interest <= 0) yield "Số dư quá nhỏ, lãi 30 ngày = 0";
        saving.accrueInterest(java.util.List.of(
            new AccrueInterestCmd(id, interest, "VND", UUID.randomUUID())));
        yield "Tính lãi 30 ngày @6.5%: +" + fmt(interest) + "đ";
      }
      default -> throw new IllegalArgumentException("unknown flow: " + flow);
    };
  }

  // ── State JSON ─────────────────────────────────────────────────────────────────

  private String buildStateJson() throws SQLException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"balanced\":").append(ledger.isDoubleEntryBalanced());
    sb.append(",\"accounts\":[");
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT code, name, kind, balance_minor FROM coa_account ORDER BY code");
        ResultSet rs = ps.executeQuery()) {
      boolean first = true;
      while (rs.next()) {
        if (!first) sb.append(',');
        first = false;
        String kind = rs.getString("kind");
        long bal = rs.getLong("balance_minor");
        boolean debitNormal = "ASSET".equals(kind) || "EXPENSE".equals(kind);
        long natural = debitNormal ? bal : -bal;
        sb.append('{')
          .append("\"code\":").append(jsonStr(rs.getString("code"))).append(',')
          .append("\"name\":").append(jsonStr(rs.getString("name"))).append(',')
          .append("\"kind\":").append(jsonStr(kind)).append(',')
          .append("\"balance\":").append(bal).append(',')
          .append("\"natural\":").append(natural)
          .append('}');
      }
    }
    sb.append("],\"recent\":[");
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT t.id, t.ref_id, t.memo, t.created_at, COALESCE(SUM(d.debit_minor),0) amt"
                + " FROM coa_trans t JOIN coa_trans_data d ON t.id = d.trans_id"
                + " GROUP BY t.id, t.ref_id, t.memo, t.created_at"
                + " ORDER BY t.created_at DESC LIMIT 15");
        ResultSet rs = ps.executeQuery()) {
      boolean first = true;
      while (rs.next()) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{')
          .append("\"ref\":").append(jsonStr(rs.getString("ref_id"))).append(',')
          .append("\"memo\":").append(jsonStr(rs.getString("memo"))).append(',')
          .append("\"amount\":").append(rs.getLong("amt")).append(',')
          .append("\"at\":").append(jsonStr(String.valueOf(rs.getTimestamp("created_at").toInstant())))
          .append('}');
      }
    }
    sb.append("]");

    // Savings accounts (sav_account, user-owned only).
    sb.append(",\"savings\":[");
    try (Connection c = ds.getConnection();
        PreparedStatement ps = c.prepareStatement(
            "SELECT account_no, kind, (credits_posted - debits_posted - debits_pending) AS avail,"
                + " debits_pending, flags FROM sav_account"
                + " WHERE owner_mid <> 0 ORDER BY opened_at");
        ResultSet rs = ps.executeQuery()) {
      boolean first = true;
      while (rs.next()) {
        if (!first) sb.append(',');
        first = false;
        sb.append('{')
          .append("\"accountNo\":").append(jsonStr(rs.getString("account_no"))).append(',')
          .append("\"kind\":").append(jsonStr(rs.getString("kind"))).append(',')
          .append("\"available\":").append(rs.getLong("avail")).append(',')
          .append("\"pending\":").append(rs.getLong("debits_pending")).append(',')
          .append("\"closed\":").append((rs.getInt("flags") & 1) != 0)
          .append('}');
      }
    }
    sb.append("]");

    // Reports derived from coa_account.
    TrialBalance tb = reports.trialBalance();
    BalanceSheet bs = reports.balanceSheet();
    ProfitAndLoss pl = reports.profitAndLoss();
    sb.append(",\"reports\":{")
      .append("\"trial\":{")
        .append("\"totalDebit\":").append(tb.totalDebit()).append(',')
        .append("\"totalCredit\":").append(tb.totalCredit()).append(',')
        .append("\"balanced\":").append(tb.isBalanced())
      .append("},\"sheet\":{")
        .append("\"assets\":").append(bs.assets()).append(',')
        .append("\"liabilities\":").append(bs.liabilities()).append(',')
        .append("\"equity\":").append(bs.equity()).append(',')
        .append("\"netIncome\":").append(bs.netIncome()).append(',')
        .append("\"transit\":").append(bs.transit()).append(',')
        .append("\"balanced\":").append(bs.isBalanced())
      .append("},\"pnl\":{")
        .append("\"revenue\":").append(pl.totalRevenue()).append(',')
        .append("\"expense\":").append(pl.totalExpense()).append(',')
        .append("\"net\":").append(pl.netProfit())
      .append("}}");

    sb.append("}");
    return sb.toString();
  }

  // ── Util ─────────────────────────────────────────────────────────────────────

  private static String fmt(long v) {
    return String.format("%,d", v);
  }

  private static String jsonStr(String s) {
    if (s == null) return "null";
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      switch (ch) {
        case '"'  -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default   -> b.append(ch);
      }
    }
    return b.append('"').toString();
  }

  private static void send(HttpExchange ex, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  // ── Embedded dashboard ─────────────────────────────────────────────────────────

  private static final String INDEX_HTML = """
      <!doctype html>
      <html lang="vi"><head><meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>GtelPay · Control Plane</title>
      <style>
        :root{--bg:#0d1117;--card:#161b22;--line:#30363d;--txt:#e6edf3;--mut:#8b949e;
              --green:#3fb950;--red:#f85149;--blue:#58a6ff;--amber:#d29922}
        *{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--txt);
          font:14px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif}
        header{padding:16px 24px;border-bottom:1px solid var(--line);display:flex;
          align-items:center;gap:16px;position:sticky;top:0;background:var(--bg);z-index:5}
        h1{font-size:18px;margin:0;font-weight:600}
        .badge{padding:4px 12px;border-radius:999px;font-weight:600;font-size:13px}
        .ok{background:rgba(63,185,80,.15);color:var(--green);border:1px solid var(--green)}
        .bad{background:rgba(248,81,73,.15);color:var(--red);border:1px solid var(--red)}
        .wrap{display:grid;grid-template-columns:1fr 360px;gap:24px;padding:24px;max-width:1400px;margin:0 auto}
        @media(max-width:980px){.wrap{grid-template-columns:1fr}}
        .group{margin-bottom:24px}
        .group h2{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:var(--mut);
          margin:0 0 10px;font-weight:600}
        .cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px}
        .acct{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px 14px}
        .acct .code{font-size:12px;color:var(--mut);font-family:ui-monospace,monospace}
        .acct .nm{font-size:12px;color:var(--txt);margin:2px 0 8px;min-height:32px}
        .acct .bal{font-size:20px;font-weight:700;font-variant-numeric:tabular-nums}
        .acct .bal.pos{color:var(--green)} .acct .bal.zero{color:var(--mut)} .acct .bal.neg{color:var(--amber)}
        .acct.flash{animation:fl .8s ease}
        @keyframes fl{0%{border-color:var(--blue);box-shadow:0 0 0 2px rgba(88,166,255,.4)}100%{}}
        .side{position:sticky;top:80px;align-self:start}
        .panel{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px;margin-bottom:16px}
        .panel h2{font-size:13px;margin:0 0 12px;font-weight:600}
        .btns{display:grid;grid-template-columns:1fr 1fr;gap:8px}
        button{background:#21262d;color:var(--txt);border:1px solid var(--line);border-radius:8px;
          padding:9px 10px;font-size:13px;cursor:pointer;transition:.15s}
        button:hover{background:#30363d;border-color:var(--blue)}
        button.wide{grid-column:1/3}
        button.reset{border-color:var(--red);color:var(--red)} button.reset:hover{background:rgba(248,81,73,.12)}
        .tx{font-size:12px;border-top:1px solid var(--line);padding:8px 0}
        .tx:first-child{border-top:0}
        .tx .memo{color:var(--txt)} .tx .meta{color:var(--mut);display:flex;justify-content:space-between;margin-top:2px}
        .tx .amt{color:var(--blue);font-variant-numeric:tabular-nums}
        .rpt{font-size:13px}
        .rpt h3{font-size:12px;text-transform:uppercase;letter-spacing:.06em;color:var(--mut);
          margin:14px 0 6px;font-weight:600}
        .rpt h3:first-child{margin-top:0}
        .rpt .row{display:flex;justify-content:space-between;padding:3px 0;font-variant-numeric:tabular-nums}
        .rpt .row .lbl{color:var(--mut)}
        .rpt .row.tot{border-top:1px solid var(--line);margin-top:4px;padding-top:6px;font-weight:600}
        .rpt .row .v.pos{color:var(--green)} .rpt .row .v.neg{color:var(--amber)}
        .rpt .eq{font-size:11px;color:var(--mut);margin-top:6px;font-family:ui-monospace,monospace}
        .rpt .chk{font-size:11px;margin-top:4px}
        .rpt .chk.ok{color:var(--green)} .rpt .chk.bad{color:var(--red)}
        .sav{font-size:12px;border-top:1px solid var(--line);padding:8px 0;display:flex;
          justify-content:space-between;align-items:center}
        .sav:first-child{border-top:0}
        .sav .no{font-family:ui-monospace,monospace;color:var(--txt)}
        .sav .k{font-size:10px;color:var(--mut)}
        .sav .av{font-variant-numeric:tabular-nums;color:var(--green);font-weight:600}
        .sav .pend{font-size:10px;color:var(--amber)}
        .txr{font-size:12px}
        .txr .st{display:inline-block;padding:3px 10px;border-radius:999px;font-weight:600;font-size:12px}
        .txr .st.ok{background:rgba(63,185,80,.15);color:var(--green);border:1px solid var(--green)}
        .txr .st.bad{background:rgba(248,81,73,.15);color:var(--red);border:1px solid var(--red)}
        .txr table{width:100%;border-collapse:collapse;margin-top:8px}
        .txr th,.txr td{text-align:right;padding:3px 4px;font-variant-numeric:tabular-nums}
        .txr th:first-child,.txr td:first-child{text-align:left;font-family:ui-monospace,monospace}
        .txr th{color:var(--mut);font-weight:600;border-bottom:1px solid var(--line)}
        .txr .meta{color:var(--mut);margin-top:6px;font-size:11px}
        .tx{cursor:pointer} .tx:hover .memo{color:var(--blue)}
        #toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:var(--card);
          border:1px solid var(--blue);border-radius:8px;padding:10px 18px;opacity:0;transition:.3s;pointer-events:none}
        #toast.show{opacity:1}
        .tabs{display:flex;gap:8px;padding:12px 24px 0;max-width:1400px;margin:0 auto}
        .tab{background:var(--card);border:1px solid var(--line);border-bottom:none;
          border-radius:10px 10px 0 0;padding:10px 18px;cursor:pointer;font-size:14px;
          font-weight:600;color:var(--mut)}
        .tab.active{color:var(--txt);border-color:var(--blue);background:#1b2230}
        .savcards{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px}
      </style></head><body>
      <header>
        <h1>⚡ GtelPay Control Plane</h1>
        <span id="badge" class="badge ok">checking…</span>
        <span style="color:var(--mut);font-size:12px;margin-left:auto" id="clock"></span>
      </header>
      <div class="tabs">
        <div class="tab active" id="tab-coa" onclick="showTab('coa')">⚖️ Sổ kế toán</div>
        <div class="tab" id="tab-sav" onclick="showTab('sav')">🏦 Hệ Ví / Tiết kiệm</div>
      </div>

      <!-- ── HỆ KẾ TOÁN ── -->
      <div class="wrap" id="pane-coa">
        <div id="groups"></div>
        <div class="side">
          <div class="panel">
            <h2>Chạy nghiệp vụ kế toán</h2>
            <div class="btns">
              <button onclick="run('topup')">Nạp tiền</button>
              <button onclick="run('withdraw')">Rút tiền</button>
              <button onclick="run('internal')">Chuyển nội bộ</button>
              <button onclick="run('ibft')">IBFT</button>
              <button onclick="run('qrpos')">QR/POS</button>
              <button onclick="run('walletpay')">Thanh toán ví</button>
              <button onclick="run('payroll')">Chi lương</button>
              <button onclick="run('disbursement')">Chi hộ</button>
              <button class="wide" onclick="run('eod')">EOD Settlement &amp; Clearing</button>
              <button class="wide" onclick="run('close')">📕 Khoá sổ cuối kỳ</button>
              <button class="wide reset" onclick="reset()">⟲ Reset toàn bộ</button>
            </div>
          </div>
          <div class="panel">
            <h2>Tra cứu giao dịch</h2>
            <div style="display:flex;gap:8px">
              <input id="txq" placeholder="trans_id (UUID) hoặc ref_id"
                style="flex:1;background:#0d1117;border:1px solid var(--line);border-radius:8px;
                color:var(--txt);padding:8px 10px;font-size:12px;font-family:ui-monospace,monospace"
                onkeydown="if(event.key==='Enter')lookupTx()">
              <button onclick="lookupTx()">Tra</button>
            </div>
            <div id="txresult" style="margin-top:12px"></div>
          </div>
          <div class="panel">
            <h2>Báo cáo kế toán</h2>
            <div id="reports" class="rpt"></div>
          </div>
          <div class="panel">
            <h2>Bút toán gần nhất</h2>
            <div id="recent"></div>
          </div>
        </div>
      </div>

      <!-- ── HỆ VÍ / TIẾT KIỆM ── -->
      <div class="wrap" id="pane-sav" style="display:none;grid-template-columns:1fr 360px">
        <div>
          <div class="group"><h2>Sổ tiết kiệm (sav_account · per-user)</h2>
            <div id="savcards" class="savcards"></div>
          </div>
        </div>
        <div class="side">
          <div class="panel">
            <h2>Nghiệp vụ tiết kiệm</h2>
            <div class="btns">
              <button onclick="run('sav_open')">Mở sổ</button>
              <button onclick="run('sav_deposit')">Gửi tiền</button>
              <button onclick="run('sav_withdraw')">Rút tiền</button>
              <button onclick="run('sav_interest')">Tính lãi</button>
              <button class="wide reset" onclick="reset()">⟲ Reset toàn bộ</button>
            </div>
            <p style="color:var(--mut);font-size:12px;margin:12px 0 0">
              Phân hệ độc lập, sổ riêng (account + trans + trans_data), không trộn COA.</p>
          </div>
        </div>
      </div>
      <div id="toast"></div>
      <script>
        const GROUPS=[['1','Tài sản'],['2','Nợ phải trả'],['3','Transit'],['4','Doanh thu'],['5','Chi phí'],['6','Vốn']];
        const fmt=n=>new Intl.NumberFormat('vi-VN').format(n);
        let prev={};
        async function load(){
          let r; try{r=await(await fetch('/api/state')).json();}catch(e){return;}
          const b=document.getElementById('badge');
          b.textContent=r.balanced?'✓ Double-entry balanced':'✗ UNBALANCED';
          b.className='badge '+(r.balanced?'ok':'bad');
          const g=document.getElementById('groups'); g.innerHTML='';
          for(const [pfx,label] of GROUPS){
            const accts=r.accounts.filter(a=>a.code[0]===pfx); if(!accts.length)continue;
            const sec=document.createElement('div'); sec.className='group';
            sec.innerHTML='<h2>Nhóm '+pfx+' — '+label+'</h2>';
            const cards=document.createElement('div'); cards.className='cards';
            for(const a of accts){
              const v=a.natural, cls=v>0?'pos':(v===0?'zero':'neg');
              const card=document.createElement('div'); card.className='acct';
              if(prev[a.code]!==undefined&&prev[a.code]!==a.balance) card.classList.add('flash');
              card.innerHTML='<div class="code">'+a.code+'</div><div class="nm">'+a.name+'</div>'+
                '<div class="bal '+cls+'">'+fmt(v)+'<span style="font-size:12px;color:var(--mut)"> đ</span></div>';
              cards.appendChild(card); prev[a.code]=a.balance;
            }
            sec.appendChild(cards); g.appendChild(sec);
          }
          renderReports(r.reports);
          renderSavings(r.savings);
          const rc=document.getElementById('recent');
          rc.innerHTML=r.recent.length?'':'<span style="color:var(--mut);font-size:12px">Chưa có giao dịch</span>';
          for(const t of r.recent){
            const d=document.createElement('div'); d.className='tx';
            const at=t.at?new Date(t.at).toLocaleTimeString('vi-VN'):'';
            d.title='Bấm để tra cứu bút toán';
            d.onclick=()=>{ if(t.ref){ document.getElementById('txq').value=t.ref; lookupTx(); } };
            d.innerHTML='<div class="memo">'+(t.memo||t.ref||'')+'</div>'+
              '<div class="meta"><span>'+at+'</span><span class="amt">'+fmt(t.amount)+' đ</span></div>';
            rc.appendChild(d);
          }
          document.getElementById('clock').textContent='cập nhật '+new Date().toLocaleTimeString('vi-VN');
        }
        function row(lbl,val,opts){
          opts=opts||{};
          const cls=opts.tot?'row tot':'row';
          const vcls=opts.sign?(val>0?'v pos':(val<0?'v neg':'v')):'v';
          return '<div class="'+cls+'"><span class="lbl">'+lbl+'</span>'+
                 '<span class="'+vcls+'">'+fmt(val)+' đ</span></div>';
        }
        function renderReports(rp){
          const el=document.getElementById('reports');
          if(!rp){el.innerHTML='';return;}
          const t=rp.trial, s=rp.sheet, p=rp.pnl;
          let h='';
          // Trial Balance
          h+='<h3>Bảng cân đối thử</h3>';
          h+=row('Tổng Nợ',t.totalDebit);
          h+=row('Tổng Có',t.totalCredit);
          h+='<div class="chk '+(t.balanced?'ok':'bad')+'">'+(t.balanced?'✓ ΣNợ = ΣCó':'✗ lệch')+'</div>';
          // Balance Sheet
          h+='<h3>Bảng cân đối kế toán</h3>';
          h+=row('Tài sản (1xxx)',s.assets);
          h+=row('Nợ phải trả (2xxx)',s.liabilities);
          h+=row('Vốn (6xxx)',s.equity);
          h+=row('Lãi/lỗ kỳ này',s.netIncome,{sign:true});
          if(s.transit!==0) h+=row('Transit (3xxx)',s.transit,{sign:true});
          h+='<div class="eq">TS '+fmt(s.assets)+' = Nợ '+fmt(s.liabilities)+
             ' + Vốn '+fmt(s.equity)+' + Lãi '+fmt(s.netIncome)+
             (s.transit!==0?' + Transit '+fmt(s.transit):'')+'</div>';
          h+='<div class="chk '+(s.balanced?'ok':'bad')+'">'+(s.balanced?'✓ Phương trình cân':'✗ lệch')+'</div>';
          // P&L
          h+='<h3>Kết quả kinh doanh</h3>';
          h+=row('Doanh thu (4xxx)',p.revenue);
          h+=row('Chi phí (5xxx)',p.expense);
          h+=row('Lãi/lỗ thuần',p.net,{tot:true,sign:true});
          el.innerHTML=h;
        }
        function renderSavings(list){
          const el=document.getElementById('savcards');
          if(!list||!list.length){el.innerHTML='<span style="color:var(--mut);font-size:12px">Chưa có sổ tiết kiệm — bấm Mở sổ / Gửi tiền</span>';return;}
          let h='';
          for(const s of list){
            const cls=s.available>0?'pos':(s.available===0?'zero':'neg');
            h+='<div class="acct"><div class="code">'+s.accountNo+'</div>'+
               '<div class="nm">'+s.kind+(s.closed?' · ĐÃ ĐÓNG':'')+'</div>'+
               '<div class="bal '+cls+'">'+fmt(s.available)+'<span style="font-size:12px;color:var(--mut)"> đ</span></div>'+
               (s.pending?'<div class="pend" style="font-size:11px;color:var(--amber);margin-top:4px">giữ (pending) '+fmt(s.pending)+' đ</div>':'')+
               '</div>';
          }
          el.innerHTML=h;
        }
        function showTab(t){
          document.getElementById('pane-coa').style.display = t==='coa'?'grid':'none';
          document.getElementById('pane-sav').style.display = t==='sav'?'grid':'none';
          document.getElementById('tab-coa').classList.toggle('active', t==='coa');
          document.getElementById('tab-sav').classList.toggle('active', t==='sav');
        }
        async function lookupTx(){
          const q=document.getElementById('txq').value.trim();
          const out=document.getElementById('txresult');
          if(!q){out.innerHTML='';return;}
          const isUuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(q);
          const url='/api/trans?'+(isUuid?'id=':'ref=')+encodeURIComponent(q);
          let r; try{r=await(await fetch(url)).json();}catch(e){out.innerHTML='<span class="chk bad">lỗi mạng</span>';return;}
          if(!r.found){out.innerHTML='<span class="txr"><span class="st bad">NOT FOUND</span> '+
            (r.error?'<span style="color:var(--mut)">'+r.error+'</span>':'')+'</span>';return;}
          const ok=r.status==='SUCCESS';
          let h='<div class="txr"><span class="st '+(ok?'ok':'bad')+'">'+
            (ok?'✓ SUCCESS — đủ bút toán':'✗ '+r.status)+'</span>';
          h+='<table><tr><th>TK</th><th>Tên</th><th>Nợ</th><th>Có</th></tr>';
          for(const l of r.lines){
            h+='<tr><td>'+l.account+'</td><td style="text-align:left;color:var(--mut)">'+l.name+'</td>'+
               '<td>'+(l.debit?fmt(l.debit):'')+'</td><td>'+(l.credit?fmt(l.credit):'')+'</td></tr>';
          }
          h+='<tr><td colspan="2" style="text-align:left;color:var(--mut)">Tổng</td>'+
             '<td>'+fmt(r.debitTotal)+'</td><td>'+fmt(r.creditTotal)+'</td></tr></table>';
          h+='<div class="meta">'+(r.ref||'')+(r.memo?' · '+r.memo:'')+'</div>';
          h+='<div class="chk '+(r.balanced?'ok':'bad')+'">'+
             (r.balanced?'✓ ΣNợ = ΣCó = '+fmt(r.debitTotal):'✗ lệch bút toán')+'</div></div>';
          out.innerHTML=h;
        }
        async function run(flow){
          const r=await(await fetch('/api/demo/'+flow,{method:'POST'})).json();
          toast(r.ok?('✓ '+r.msg):('✗ '+r.error)); load();
        }
        async function reset(){
          if(!confirm('Xoá toàn bộ giao dịch và reset balance về 0?'))return;
          await fetch('/api/reset',{method:'POST'}); toast('✓ Đã reset'); prev={}; load();
        }
        function toast(m){const t=document.getElementById('toast');t.textContent=m;t.classList.add('show');
          clearTimeout(t._t);t._t=setTimeout(()=>t.classList.remove('show'),2600);}
        load(); setInterval(load,1000);
      </script>
      </body></html>
      """;
}
