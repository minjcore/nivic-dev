package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type opsHandler struct {
	store   *Store
	token   string
	wireURL string
	wireM2M string
}

func (h *opsHandler) register(mux *http.ServeMux) {
	mux.HandleFunc("/", h.dispatch)
}

func (h *opsHandler) dispatch(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path

	if path == "/" || path == "" {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, opsHTML)
		return
	}

	if path == "/api/login" {
		h.handleLogin(w, r)
		return
	}

	if !h.auth(r) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		fmt.Fprint(w, `{"error":"unauthorized"}`)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	switch {
	case path == "/api/overview" && r.Method == http.MethodGet:
		h.handleOverview(w)
	case path == "/api/merchants" && r.Method == http.MethodGet:
		h.handleListMerchants(w)
	case strings.HasPrefix(path, "/api/merchants/") && r.Method == http.MethodPost:
		h.handleMerchantAction(w, r, path)
	case path == "/api/settlement" && r.Method == http.MethodGet:
		h.handleSettlement(w, r)
	case path == "/api/reconcile" && r.Method == http.MethodGet:
		h.handleReconcile(w, r)
	case strings.HasPrefix(path, "/api/wire/"):
		h.proxyWire(w, r, strings.TrimPrefix(path, "/api/wire"))
	default:
		http.NotFound(w, r)
	}
}

func (h *opsHandler) auth(r *http.Request) bool {
	v := r.Header.Get("Authorization")
	return h.token != "" && strings.TrimPrefix(v, "Bearer ") == h.token
}

func (h *opsHandler) handleLogin(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	var req struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Token != h.token {
		w.WriteHeader(http.StatusUnauthorized)
		fmt.Fprint(w, `{"ok":false}`)
		return
	}
	fmt.Fprint(w, `{"ok":true}`)
}

func (h *opsHandler) handleOverview(w http.ResponseWriter) {
	ov, err := h.store.Overview()
	if err != nil {
		http.Error(w, `{"error":"db"}`, 500)
		return
	}
	json.NewEncoder(w).Encode(ov)
}

func (h *opsHandler) handleListMerchants(w http.ResponseWriter) {
	rows, err := h.store.ListMerchants()
	if err != nil {
		http.Error(w, `{"error":"db"}`, 500)
		return
	}
	if rows == nil {
		rows = []MerchantOpsRow{}
	}
	json.NewEncoder(w).Encode(rows)
}

func (h *opsHandler) handleMerchantAction(w http.ResponseWriter, r *http.Request, path string) {
	parts := strings.Split(strings.TrimPrefix(path, "/api/merchants/"), "/")
	if len(parts) != 2 {
		http.Error(w, `{"error":"bad path"}`, 400)
		return
	}
	mid64, err := strconv.ParseUint(parts[0], 10, 32)
	if err != nil {
		http.Error(w, `{"error":"bad mid"}`, 400)
		return
	}
	mid := uint32(mid64)
	var status string
	switch parts[1] {
	case "suspend":
		status = "suspended"
	case "activate":
		status = "active"
	default:
		http.Error(w, `{"error":"unknown action"}`, 400)
		return
	}
	if err := h.store.SetMerchantStatus(mid, status); err != nil {
		http.Error(w, `{"error":"db"}`, 500)
		return
	}
	fmt.Fprintf(w, `{"ok":true,"status":"%s"}`, status)
}

func (h *opsHandler) handleSettlement(w http.ResponseWriter, r *http.Request) {
	fromStr := r.URL.Query().Get("from")
	toStr := r.URL.Query().Get("to")
	if fromStr == "" || toStr == "" {
		http.Error(w, `{"error":"from and to required"}`, 400)
		return
	}
	loc := time.Now().Location()
	fromT, err1 := time.ParseInLocation("2006-01-02", fromStr, loc)
	toT, err2 := time.ParseInLocation("2006-01-02", toStr, loc)
	if err1 != nil || err2 != nil {
		http.Error(w, `{"error":"invalid date format"}`, 400)
		return
	}
	toT = toT.Add(24 * time.Hour)

	rows, err := h.store.Settlement(fromT.UnixMilli(), toT.UnixMilli())
	if err != nil {
		http.Error(w, `{"error":"db"}`, 500)
		return
	}
	if rows == nil {
		rows = []SettlementRow{}
	}
	json.NewEncoder(w).Encode(map[string]any{"rows": rows})
}

// ─── Reconciliation ───────────────────────────────────────────────────────────

type WireIntentRaw struct {
	MID            uint32 `json:"mid"`
	RequestID      uint64 `json:"request_id"`
	Amount         uint64 `json:"amount"`
	Status         int    `json:"status"`
	GatewayOrderID string `json:"gateway_order_id"`
}

type ReconRow struct {
	OrderID       string `json:"order_id,omitempty"`
	MID           uint32 `json:"mid"`
	MerchantName  string `json:"merchant_name,omitempty"`
	WireRequestID uint64 `json:"wire_request_id"`
	OrderAmount   uint64 `json:"order_amount,omitempty"`
	DiscountPts   int64  `json:"discount_pts,omitempty"`
	WireAmount    uint64 `json:"wire_amount,omitempty"`
	WireStatus    int    `json:"wire_status"`
	Status        string `json:"status"`
	PaidAt        int64  `json:"paid_at,omitempty"`
}

type ReconResult struct {
	Rows        []ReconRow `json:"rows"`
	TotalOK     int        `json:"total_ok"`
	TotalIssues int        `json:"total_issues"`
}

func (h *opsHandler) handleReconcile(w http.ResponseWriter, r *http.Request) {
	fromStr := r.URL.Query().Get("from")
	toStr := r.URL.Query().Get("to")
	if fromStr == "" || toStr == "" {
		http.Error(w, `{"error":"from and to required"}`, 400)
		return
	}
	loc := time.Now().Location()
	fromT, err1 := time.ParseInLocation("2006-01-02", fromStr, loc)
	toT, err2 := time.ParseInLocation("2006-01-02", toStr, loc)
	if err1 != nil || err2 != nil {
		http.Error(w, `{"error":"invalid date format"}`, 400)
		return
	}
	toT = toT.Add(24 * time.Hour)

	orders, err := h.store.OrdersForRecon(fromT.UnixMilli(), toT.UnixMilli())
	if err != nil {
		http.Error(w, `{"error":"db"}`, 500)
		return
	}

	wireIntents, err := h.fetchWireIntents(fromStr, toStr)
	if err != nil {
		http.Error(w, `{"error":"wire unavailable"}`, 502)
		return
	}

	result := buildReconResult(orders, wireIntents)
	json.NewEncoder(w).Encode(result)
}

func (h *opsHandler) fetchWireIntents(from, to string) ([]WireIntentRaw, error) {
	if h.wireURL == "" {
		return nil, fmt.Errorf("wire not configured")
	}
	target := h.wireURL + "/api/intents?from=" + from + "&to=" + to
	req, err := http.NewRequest(http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	if h.wireM2M != "" {
		req.Header["X-M2M-Token"] = []string{h.wireM2M}
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var wrapper struct {
		Intents []WireIntentRaw `json:"intents"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&wrapper); err != nil {
		return nil, err
	}
	return wrapper.Intents, nil
}

func buildReconResult(orders []MerchantsOrderForRecon, wireIntents []WireIntentRaw) ReconResult {
	type key struct{ mid, rid uint64 }

	wmap := make(map[key]WireIntentRaw, len(wireIntents))
	for _, wi := range wireIntents {
		wmap[key{uint64(wi.MID), wi.RequestID}] = wi
	}

	var rows []ReconRow
	seen := make(map[key]bool)

	for _, o := range orders {
		row := ReconRow{
			OrderID:       o.ID,
			MID:           o.MID,
			MerchantName:  o.MerchantName,
			WireRequestID: o.WireRequestID,
			OrderAmount:   o.Amount,
			DiscountPts:   o.DiscountPts,
			PaidAt:        o.PaidAt,
		}
		if o.WireRequestID == 0 {
			row.Status = "MERCHANTS_ONLY"
		} else {
			k := key{uint64(o.MID), o.WireRequestID}
			wi, found := wmap[k]
			if !found {
				row.Status = "MERCHANTS_ONLY"
			} else {
				seen[k] = true
				row.WireAmount = wi.Amount
				row.WireStatus = wi.Status
				expected := o.Amount - uint64(o.DiscountPts)
				switch {
				case wi.Status == 0:
					row.Status = "WIRE_PENDING"
				case wi.Amount != expected:
					row.Status = "AMOUNT_MISMATCH"
				default:
					row.Status = "OK"
				}
			}
		}
		rows = append(rows, row)
	}

	for _, wi := range wireIntents {
		k := key{uint64(wi.MID), wi.RequestID}
		if !seen[k] {
			rows = append(rows, ReconRow{
				MID:           wi.MID,
				WireRequestID: wi.RequestID,
				WireAmount:    wi.Amount,
				WireStatus:    wi.Status,
				Status:        "WIRE_ONLY",
			})
		}
	}

	var ok, issues int
	for _, rr := range rows {
		if rr.Status == "OK" {
			ok++
		} else {
			issues++
		}
	}
	if rows == nil {
		rows = []ReconRow{}
	}
	return ReconResult{Rows: rows, TotalOK: ok, TotalIssues: issues}
}

func (h *opsHandler) proxyWire(w http.ResponseWriter, r *http.Request, rest string) {
	if h.wireURL == "" {
		http.Error(w, `{"error":"wire admin not configured"}`, 503)
		return
	}
	target := h.wireURL + "/api" + rest
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}
	var bodyReader io.Reader
	if r.Method == http.MethodPost {
		bodyReader = r.Body
	}
	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, bodyReader)
	if err != nil {
		http.Error(w, `{"error":"proxy build failed"}`, 500)
		return
	}
	if h.wireM2M != "" {
		req.Header["X-M2M-Token"] = []string{h.wireM2M} // bypass canonicalization (Wire C server is case-sensitive)
	}
	if r.Method == http.MethodPost {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		http.Error(w, `{"error":"wire unreachable"}`, 502)
		return
	}
	defer resp.Body.Close()
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)
}

// ─── SPA ─────────────────────────────────────────────────────────────────────

const opsHTML = `<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Control Plane</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#0d1117;color:#e6edf3;font-family:-apple-system,'Segoe UI',monospace;font-size:13px;height:100vh}
#login{position:fixed;inset:0;background:#0d1117;display:flex;align-items:center;justify-content:center;z-index:100}
.lbox{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:40px;width:300px}
.lbox h1{font-size:16px;font-weight:700;margin-bottom:24px;color:#58a6ff;font-family:monospace;letter-spacing:1px}
input{width:100%;background:#0d1117;border:1px solid #30363d;color:#e6edf3;padding:9px 12px;border-radius:6px;font-size:13px;margin-bottom:12px;outline:none}
input:focus{border-color:#58a6ff}
button{cursor:pointer;border:none;border-radius:6px;font-size:12px;padding:8px 14px;font-weight:600}
.bp{background:#238636;color:#fff}.bp:hover{background:#2ea043}
.bs{background:#21262d;color:#e6edf3;border:1px solid #30363d}.bs:hover{background:#30363d}
.br{background:#da3633;color:#fff}.br:hover{background:#b91c1c}
#main{display:none;flex-direction:column;height:100vh}
.hdr{background:#161b22;border-bottom:1px solid #30363d;padding:0 20px;display:flex;align-items:center;gap:12px;height:46px;flex-shrink:0}
.logo{color:#58a6ff;font-family:monospace;font-weight:700;font-size:13px}
.tabs{display:flex;background:#161b22;border-bottom:1px solid #30363d;padding:0 20px;flex-shrink:0}
.tab{padding:10px 14px;color:#8b949e;border-bottom:2px solid transparent;cursor:pointer;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.6px}
.tab.on{color:#58a6ff;border-bottom-color:#58a6ff}
.pg{flex:1;overflow-y:auto;padding:20px;display:none}
.pg.on{display:block}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:10px;margin-bottom:20px}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:14px}
.cl{color:#8b949e;font-size:10px;text-transform:uppercase;letter-spacing:.5px;margin-bottom:5px}
.cv{font-size:22px;font-weight:700;font-family:monospace}
.cv.g{color:#3fb950}.cv.b{color:#58a6ff}.cv.r{color:#e94560}
.sec{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:16px;margin-bottom:14px}
.sh{display:flex;align-items:center;margin-bottom:12px}
.sh h2{font-size:12px;font-weight:700;color:#e6edf3;text-transform:uppercase;letter-spacing:.5px;flex:1}
table{width:100%;border-collapse:collapse}
th{text-align:left;color:#8b949e;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.5px;padding:6px 8px;border-bottom:1px solid #30363d}
td{padding:7px 8px;border-bottom:1px solid #21262d;color:#e6edf3;font-size:12px}
tr:last-child td{border-bottom:none}
tr:hover td{background:#1c2128}
.bdg{display:inline-block;padding:1px 7px;border-radius:10px;font-size:10px;font-weight:700}
.ok{background:#1a4b2e;color:#3fb950}.off{background:#3d1c1c;color:#e94560}
.er{color:#e94560;font-size:12px;margin-top:6px}
.fr{display:flex;gap:8px;align-items:center;margin-bottom:12px;flex-wrap:wrap}
.fr input{margin-bottom:0;width:auto;flex:none}
.fr label{color:#8b949e;font-size:11px}
.muted{color:#8b949e;font-size:11px}
</style>
</head>
<body>
<div id="login">
  <div class="lbox">
    <h1>⬡ CONTROL PLANE</h1>
    <input id="pw" type="password" placeholder="Ops token" autocomplete="current-password">
    <button class="bp" id="lb" style="width:100%">Đăng nhập</button>
    <div id="le" class="er"></div>
  </div>
</div>
<div id="main">
  <div class="hdr">
    <span class="logo">⬡ CONTROL PLANE</span>
    <span style="flex:1"></span>
    <button class="bs" style="font-size:11px;padding:4px 10px" id="lgb">Logout</button>
  </div>
  <div class="tabs">
    <div class="tab on" id="t0" onclick="tab('dash')">Dashboard</div>
    <div class="tab" id="t1" onclick="tab('merch')">Merchants</div>
    <div class="tab" id="t2" onclick="tab('settle')">Settlement</div>
    <div class="tab" id="t3" onclick="tab('recon')">Reconcile</div>
    <div class="tab" id="t4" onclick="tab('wire')">Wire</div>
  </div>
  <div class="pg on" id="pg-dash">
    <div class="cards" id="ov"></div>
  </div>
  <div class="pg" id="pg-merch">
    <div class="sec">
      <div class="sh"><h2>Merchants</h2>
        <button class="bs" style="font-size:10px;padding:3px 8px" onclick="loadMerch()">Refresh</button>
      </div>
      <table><thead><tr>
        <th>MID</th><th>Name</th><th>Slug</th><th>Status</th>
        <th>Orders</th><th>Volume</th><th>Created</th><th></th>
      </tr></thead><tbody id="mtb"></tbody></table>
    </div>
  </div>
  <div class="pg" id="pg-settle">
    <div class="sec">
      <h2 style="font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.5px;margin-bottom:14px">Settlement Report</h2>
      <div class="fr">
        <label>From</label><input id="sf" type="date" style="width:140px">
        <label>To</label><input id="st" type="date" style="width:140px">
        <button class="bp" style="padding:7px 14px" onclick="loadSettle()">Tạo báo cáo</button>
      </div>
      <div id="ss" class="muted" style="margin-bottom:10px"></div>
      <table><thead><tr>
        <th>MID</th><th>Merchant</th><th>Orders</th><th>Volume (VND)</th><th>Avg Order</th>
      </tr></thead><tbody id="stb"></tbody></table>
    </div>
  </div>
  <div class="pg" id="pg-recon">
    <div class="sec">
      <h2 style="font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.5px;margin-bottom:14px">Reconciliation Report</h2>
      <div class="fr">
        <label>From</label><input id="rf" type="date" style="width:140px">
        <label>To</label><input id="rt" type="date" style="width:140px">
        <button class="bp" style="padding:7px 14px" onclick="loadRecon()">Reconcile</button>
      </div>
      <div id="rs" class="muted" style="margin-bottom:10px"></div>
      <table><thead><tr>
        <th>Status</th><th>Order ID</th><th>MID</th><th>Merchant</th>
        <th>Order Amt</th><th>Wire Amt</th><th>Request ID</th><th>Paid At</th>
      </tr></thead><tbody id="rtb"></tbody></table>
    </div>
  </div>
  <div class="pg" id="pg-wire">
    <div class="cards" id="wc"></div>
    <div class="sec">
      <div class="sh"><h2>Active Sessions</h2>
        <button class="bs" style="font-size:10px;padding:3px 8px" onclick="loadWSess()">Refresh</button>
      </div>
      <table><thead><tr><th>MID</th><th>Expires In</th><th></th></tr></thead>
      <tbody id="wstb"></tbody></table>
    </div>
  </div>
</div>
<script>
var T='';
var fmt=function(n){return Number(n).toLocaleString('vi-VN');};
var fmtD=function(s){return s?new Date(s*1000).toLocaleDateString('vi-VN'):'';};
function api(path,o){
  return fetch('/api'+path,Object.assign({
    headers:{'Authorization':'Bearer '+T,'Content-Type':'application/json'}
  },o||{})).then(function(r){if(!r.ok)throw new Error(r.status);return r.json();});
}
document.getElementById('lb').onclick=login;
document.getElementById('pw').onkeydown=function(e){if(e.key==='Enter')login();};
function login(){
  var pw=document.getElementById('pw').value;
  fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"token":"'+pw+'"}'})
  .then(function(r){return r.json();}).then(function(d){
    if(!d.ok)throw new Error();
    T=pw;
    document.getElementById('login').style.display='none';
    document.getElementById('main').style.display='flex';
    loadDash();
  }).catch(function(){document.getElementById('le').textContent='Sai token';});
}
document.getElementById('lgb').onclick=function(){
  T='';document.getElementById('login').style.display='flex';
  document.getElementById('main').style.display='none';
};
var TABS=['dash','merch','settle','recon','wire'];
function tab(n){
  TABS.forEach(function(t,i){
    document.getElementById('pg-'+t).className='pg'+(t===n?' on':'');
    document.getElementById('t'+i).className='tab'+(t===n?' on':'');
  });
  if(n==='merch')loadMerch();
  if(n==='wire'){loadWStats();loadWSess();}
}
function card(lbl,val,cls){
  return '<div class="card"><div class="cl">'+lbl+'</div><div class="cv'+(cls?' '+cls:'')+'">'+(val===undefined||val===null?'0':val)+'</div></div>';
}
function loadDash(){
  api('/overview').then(function(d){
    document.getElementById('ov').innerHTML=
      card('Merchants',d.total_merchants,'b')+
      card('Active',d.active_merchants,'g')+
      card('Suspended',d.total_merchants-d.active_merchants,'r')+
      card('Today Orders',d.today_orders,'')+
      card('Today Volume',fmt(d.today_volume)+' ₫','g')+
      card('All-time Orders',d.total_orders,'')+
      card('All-time Volume',fmt(d.total_volume)+' ₫','b');
  }).catch(function(){});
}
function loadMerch(){
  api('/merchants').then(function(d){
    document.getElementById('mtb').innerHTML=d.map(function(m){
      var st=m.status==='active'
        ?'<span class="bdg ok">active</span>'
        :'<span class="bdg off">suspended</span>';
      var btn=m.status==='active'
        ?'<button class="br" style="font-size:10px;padding:2px 8px" onclick="setStatus('+m.mid+',\'suspend\')">Suspend</button>'
        :'<button class="bp" style="font-size:10px;padding:2px 8px" onclick="setStatus('+m.mid+',\'activate\')">Activate</button>';
      return '<tr><td>'+m.mid+'</td><td>'+m.name+'</td><td class="muted">'+(m.slug||'—')+'</td>'
            +'<td>'+st+'</td><td>'+m.order_count+'</td>'
            +'<td>'+fmt(m.total_volume)+'</td>'
            +'<td class="muted">'+fmtD(m.created_at)+'</td><td>'+btn+'</td></tr>';
    }).join('');
  }).catch(function(){});
}
function setStatus(mid,action){
  api('/merchants/'+mid+'/'+action,{method:'POST'}).then(loadMerch).catch(function(){});
}
(function(){
  var now=new Date();
  function iso(d){return d.toISOString().slice(0,10);}
  document.getElementById('st').value=iso(now);
  document.getElementById('sf').value=iso(new Date(now-7*86400000));
  document.getElementById('rt').value=iso(now);
  document.getElementById('rf').value=iso(new Date(now-7*86400000));
})();
function loadSettle(){
  var f=document.getElementById('sf').value,t=document.getElementById('st').value;
  if(!f||!t)return;
  api('/settlement?from='+f+'&to='+t).then(function(d){
    var rows=d.rows||[];
    var tot=0,tov=0;rows.forEach(function(r){tot+=r.order_count;tov+=r.volume;});
    document.getElementById('ss').textContent=
      rows.length+' merchants · '+tot+' orders · '+fmt(tov)+' VND';
    document.getElementById('stb').innerHTML=rows.map(function(r){
      return '<tr><td>'+r.mid+'</td><td>'+r.name+'</td><td>'+r.order_count+'</td>'
            +'<td>'+fmt(r.volume)+'</td><td>'+fmt(r.avg_order)+'</td></tr>';
    }).join('');
  }).catch(function(){});
}
var RECON_COLORS={'OK':'#3fb950','MERCHANTS_ONLY':'#e94560','WIRE_ONLY':'#e3a005','AMOUNT_MISMATCH':'#e3a005','WIRE_PENDING':'#8b949e'};
function loadRecon(){
  var f=document.getElementById('rf').value,t=document.getElementById('rt').value;
  if(!f||!t)return;
  document.getElementById('rs').textContent='Loading...';
  api('/reconcile?from='+f+'&to='+t).then(function(d){
    var rows=d.rows||[];
    document.getElementById('rs').innerHTML=
      '<span style="color:#3fb950">✓ '+d.total_ok+' matched</span>'
      +(d.total_issues?' &nbsp; <span style="color:#e94560">⚠ '+d.total_issues+' issues</span>':'');
    var STATUS_LABEL={'OK':'OK','MERCHANTS_ONLY':'No Wire Intent','WIRE_ONLY':'No Merchant Order','AMOUNT_MISMATCH':'Amount Mismatch','WIRE_PENDING':'Wire Pending'};
    document.getElementById('rtb').innerHTML=rows.map(function(r){
      var c=RECON_COLORS[r.status]||'#8b949e';
      var lbl=STATUS_LABEL[r.status]||r.status;
      var ts=r.paid_at?new Date(r.paid_at).toLocaleString('vi-VN'):'—';
      return '<tr>'
        +'<td><span class="bdg" style="background:'+c+'22;color:'+c+'">'+lbl+'</span></td>'
        +'<td class="muted" style="font-size:10px">'+(r.order_id?r.order_id.slice(0,12)+'…':'—')+'</td>'
        +'<td>'+r.mid+'</td>'
        +'<td>'+(r.merchant_name||'—')+'</td>'
        +'<td>'+fmt(r.order_amount)+'</td>'
        +'<td>'+fmt(r.wire_amount)+'</td>'
        +'<td class="muted" style="font-size:10px">'+r.wire_request_id+'</td>'
        +'<td class="muted">'+ts+'</td>'
        +'</tr>';
    }).join('');
  }).catch(function(e){document.getElementById('rs').textContent='Error: '+e.message;});
}
function loadWStats(){
  api('/wire/stats').then(function(d){
    document.getElementById('wc').innerHTML=
      card('Active Sessions',d.active_sessions,'b')+
      card('Accounts',fmt(d.account_count),'')+
      card('Total Txns',fmt(d.total_txns),'')+
      card('Total Volume',fmt(d.total_volume)+' ₫','g');
  }).catch(function(){
    document.getElementById('wc').innerHTML='<div class="er" style="margin-bottom:12px">Wire admin unreachable</div>';
  });
}
function loadWSess(){
  api('/wire/sessions').then(function(d){
    document.getElementById('wstb').innerHTML=(d.sessions||[]).map(function(s){
      return '<tr><td>'+s.mid+'</td><td class="muted">'+s.expires_in_s+'s</td>'
            +'<td><button class="br" style="font-size:10px;padding:2px 8px" onclick="killS('+s.mid+')">Kill</button></td></tr>';
    }).join('');
  }).catch(function(){document.getElementById('wstb').innerHTML='';});
}
function killS(mid){
  if(!confirm('Kill sessions for MID '+mid+'?'))return;
  api('/wire/sessions/kill',{method:'POST',body:'{"mid":'+mid+'}'}).then(loadWSess).catch(function(){});
}
</script>
</body>
</html>`
