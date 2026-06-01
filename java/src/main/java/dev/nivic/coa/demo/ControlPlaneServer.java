package dev.nivic.coa.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nivic.coa.DisbursementInitCmd;
import dev.nivic.coa.DisbursementPrefundCmd;
import dev.nivic.coa.DisbursementSettleCmd;
import dev.nivic.coa.EodClearingInitCmd;
import dev.nivic.coa.EodReconcileCmd;
import dev.nivic.coa.EodRecognizeMdrCmd;
import dev.nivic.coa.EodSettleOutboundCmd;
import dev.nivic.coa.IbftInitCmd;
import dev.nivic.coa.IbftSettleCmd;
import dev.nivic.coa.InternalTransferInitCmd;
import dev.nivic.coa.InternalTransferSettleCmd;
import dev.nivic.coa.JdbcFundFlowLedger;
import dev.nivic.coa.PayrollDisburseCmd;
import dev.nivic.coa.PayrollInitCmd;
import dev.nivic.coa.QrPosCreditMerchantCmd;
import dev.nivic.coa.QrPosReceiveCmd;
import dev.nivic.coa.TopUpConfirmCmd;
import dev.nivic.coa.TopUpReceiveCmd;
import dev.nivic.coa.WalletPaymentInitCmd;
import dev.nivic.coa.WalletPaymentSettleCmd;
import dev.nivic.coa.WithdrawInitCmd;
import dev.nivic.coa.WithdrawSettleCmd;
import dev.nivic.db.Postgres;
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

  private final DataSource ds;
  private final JdbcFundFlowLedger ledger;

  private ControlPlaneServer(DataSource ds) {
    this.ds = ds;
    this.ledger = new JdbcFundFlowLedger(ds);
  }

  public static void main(String[] args) throws Exception {
    DataSource ds = Postgres.open(Postgres.Config.fromEnvironment());
    Postgres.verifyConnectivity(ds);
    ControlPlaneServer app = new ControlPlaneServer(ds);
    // Touch the ledger once to ensure schema + COA seed exist.
    app.ledger.isDoubleEntryBalanced();

    HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
    server.createContext("/", app::handleIndex);
    server.createContext("/api/state", app::handleState);
    server.createContext("/api/demo/", app::handleDemo);
    server.createContext("/api/reset", app::handleReset);
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
      st.execute("TRUNCATE coa_trans_data, coa_trans CASCADE");
      st.execute("UPDATE coa_account SET balance_minor = 0, version = 0");
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
    sb.append("]}");
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
        #toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:var(--card);
          border:1px solid var(--blue);border-radius:8px;padding:10px 18px;opacity:0;transition:.3s;pointer-events:none}
        #toast.show{opacity:1}
      </style></head><body>
      <header>
        <h1>⚡ GtelPay Control Plane</h1>
        <span id="badge" class="badge ok">checking…</span>
        <span style="color:var(--mut);font-size:12px;margin-left:auto" id="clock"></span>
      </header>
      <div class="wrap">
        <div id="groups"></div>
        <div class="side">
          <div class="panel">
            <h2>Chạy nghiệp vụ</h2>
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
              <button class="wide reset" onclick="reset()">⟲ Reset toàn bộ</button>
            </div>
          </div>
          <div class="panel">
            <h2>Bút toán gần nhất</h2>
            <div id="recent"></div>
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
          const rc=document.getElementById('recent');
          rc.innerHTML=r.recent.length?'':'<span style="color:var(--mut);font-size:12px">Chưa có giao dịch</span>';
          for(const t of r.recent){
            const d=document.createElement('div'); d.className='tx';
            const at=t.at?new Date(t.at).toLocaleTimeString('vi-VN'):'';
            d.innerHTML='<div class="memo">'+(t.memo||t.ref||'')+'</div>'+
              '<div class="meta"><span>'+at+'</span><span class="amt">'+fmt(t.amount)+' đ</span></div>';
            rc.appendChild(d);
          }
          document.getElementById('clock').textContent='cập nhật '+new Date().toLocaleTimeString('vi-VN');
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
