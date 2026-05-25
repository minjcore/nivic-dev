/*
 * admin.c — lightweight HTTP admin panel (port 7475 by default)
 *
 * Single-page web UI with: Dashboard stats, Sessions list/kill,
 * Account lookup, Cash In / Cash Out.
 *
 * Auth: every /api/... request must carry:
 *   Authorization: Bearer <ADMIN_PASSWORD>
 *
 * The HTML login form stores the password in memory (not persisted)
 * and sends it as a Bearer token with each API call.
 */

#include "admin.h"
#include "db.h"
#include "handlers.h"
#include "registry.h"
#include "wire.h"
#include "crypto_compat.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <limits.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>

/* ─── Global context ─────────────────────────────────────────────────────── */

typedef struct {
    DB           *db;
    SessionTable *st;
} ACtx;

static ACtx g;

/* ─── Admin sessions (in-memory, TTL 1h) ────────────────────────────────── */

#define ADMIN_SESS_MAX 32
#define ADMIN_SESS_TTL 3600

typedef struct {
    char   token[65];    /* 64 hex chars + NUL */
    char   username[64];
    time_t expires;
} AdminSess;

static AdminSess       admin_sess[ADMIN_SESS_MAX];
static pthread_mutex_t admin_sess_mu = PTHREAD_MUTEX_INITIALIZER;

static void admin_sess_create(const char *username, char out[65]) {
    uint8_t raw[32];
    arc4random_buf(raw, 32);
    for (int i = 0; i < 32; i++) snprintf(out + i*2, 3, "%02x", raw[i]);
    out[64] = '\0';

    time_t now = time(NULL);
    pthread_mutex_lock(&admin_sess_mu);
    int slot = 0; time_t oldest = LONG_MAX;
    for (int i = 0; i < ADMIN_SESS_MAX; i++) {
        if (admin_sess[i].expires <= now) { slot = i; break; }
        if (admin_sess[i].expires < oldest) { oldest = admin_sess[i].expires; slot = i; }
    }
    memcpy(admin_sess[slot].token, out, 65);
    strncpy(admin_sess[slot].username, username, 63);
    admin_sess[slot].expires = now + ADMIN_SESS_TTL;
    pthread_mutex_unlock(&admin_sess_mu);
}

static int admin_sess_valid(const char *token) {
    if (!token || strlen(token) != 64) return 0;
    time_t now = time(NULL);
    int found = 0;
    pthread_mutex_lock(&admin_sess_mu);
    for (int i = 0; i < ADMIN_SESS_MAX; i++) {
        if (admin_sess[i].expires > now &&
            memcmp(admin_sess[i].token, token, 64) == 0) { found = 1; break; }
    }
    pthread_mutex_unlock(&admin_sess_mu);
    return found;
}

/* ─── HTTP response helpers ─────────────────────────────────────────────── */

static void http_send(int fd, int status,
                      const char *ct, const char *body, int blen) {
    const char *ss = (status == 200) ? "OK" :
                     (status == 400) ? "Bad Request" :
                     (status == 401) ? "Unauthorized" :
                     (status == 404) ? "Not Found" : "Internal Server Error";
    char hdr[512];
    int n = snprintf(hdr, sizeof(hdr),
        "HTTP/1.1 %d %s\r\n"
        "Content-Type: %s; charset=utf-8\r\n"
        "Content-Length: %d\r\n"
        "Access-Control-Allow-Origin: *\r\n"
        "Connection: close\r\n\r\n",
        status, ss, ct, blen);
    write(fd, hdr, n);
    if (blen > 0 && body) write(fd, body, blen);
}

static void json_ok(int fd, const char *body) {
    http_send(fd, 200, "application/json", body, (int)strlen(body));
}

static void json_err(int fd, int status, const char *msg) {
    char b[256];
    int n = snprintf(b, sizeof(b), "{\"error\":\"%s\"}", msg);
    http_send(fd, status, "application/json", b, n);
}

/* ─── Auth check ─────────────────────────────────────────────────────────── */

static int authed(const char *req) {
    const char *p = strstr(req, "\r\nAuthorization: Bearer ");
    if (!p) return 0;
    p += 24;
    const char *e = strpbrk(p, "\r\n");
    if (!e) return 0;
    int rlen = (int)(e - p);
    if (rlen != 64) return 0;
    char tok[65]; memcpy(tok, p, 64); tok[64] = '\0';
    return admin_sess_valid(tok);
}

/* ─── Tiny JSON field parsers ────────────────────────────────────────────── */

static uint32_t jgetu32(const char *s, const char *key) {
    char k[64]; snprintf(k, sizeof(k), "\"%s\":", key);
    const char *p = strstr(s, k);
    if (!p) return 0;
    p += strlen(k);
    while (*p == ' ') p++;
    return (uint32_t)strtoul(p, NULL, 10);
}

static uint64_t jgetu64(const char *s, const char *key) {
    char k[64]; snprintf(k, sizeof(k), "\"%s\":", key);
    const char *p = strstr(s, k);
    if (!p) return 0;
    p += strlen(k);
    while (*p == ' ') p++;
    return strtoull(p, NULL, 10);
}

static void jgetstr(const char *s, const char *key, char *out, int max) {
    char k[64]; snprintf(k, sizeof(k), "\"%s\":", key);
    const char *p = strstr(s, k);
    out[0] = '\0';
    if (!p) return;
    p += strlen(k);
    while (*p == ' ') p++;
    if (*p != '"') return;
    p++;
    const char *e = strchr(p, '"');
    if (!e) return;
    int n = (int)(e - p);
    if (n >= max) n = max - 1;
    memcpy(out, p, n); out[n] = '\0';
}

/* ─── API handlers ───────────────────────────────────────────────────────── */

static void h_login(int fd, const char *body) {
    char username[64] = "", password[256] = "";
    jgetstr(body, "username", username, sizeof(username));
    jgetstr(body, "password", password, sizeof(password));
    if (!username[0] || !password[0]) {
        json_err(fd, 400, "missing username or password"); return;
    }
    uint8_t pw_hash[32];
    saving_sha256(password, strlen(password), pw_hash);
    if (db_admin_user_verify(g.db, username, pw_hash) != 0) {
        json_err(fd, 401, "invalid credentials"); return;
    }
    char token[65];
    admin_sess_create(username, token);
    char b[256];
    snprintf(b, sizeof(b), "{\"token\":\"%s\",\"username\":\"%s\"}", token, username);
    json_ok(fd, b);
}

static void h_stats(int fd) {
    AdminStats s = {0};
    db_admin_stats(g.db, &s);
    SessionInfo sess[512];
    int ns = st_list_sessions(g.st, sess, 512);
    char b[256];
    snprintf(b, sizeof(b),
        "{\"active_sessions\":%d,\"total_txns\":%lld,"
        "\"total_volume\":%lld,\"account_count\":%lld}",
        ns, (long long)s.total_txns,
        (long long)s.total_volume, (long long)s.account_count);
    json_ok(fd, b);
}

static void h_sessions(int fd) {
    SessionInfo sess[512];
    int n = st_list_sessions(g.st, sess, 512);
    char b[16384];
    int off = snprintf(b, sizeof(b), "{\"sessions\":[");
    for (int i = 0; i < n && off < (int)sizeof(b) - 128; i++) {
        off += snprintf(b + off, sizeof(b) - off,
            "%s{\"mid\":%u,\"expires_in_s\":%d}",
            i ? "," : "", sess[i].mid, sess[i].expires_in_s);
    }
    off += snprintf(b + off, sizeof(b) - off, "]}");
    json_ok(fd, b);
}

static void h_kill(int fd, const char *body) {
    uint32_t mid = jgetu32(body, "mid");
    if (!mid) { json_err(fd, 400, "bad mid"); return; }
    st_kill_mid(g.st, mid);
    json_ok(fd, "{\"ok\":true}");
}

static void h_account(int fd, const char *query) {
    const char *p = strstr(query, "uid=");
    if (!p) { json_err(fd, 400, "missing uid"); return; }
    uint32_t uid = (uint32_t)strtoul(p + 4, NULL, 10);
    if (!uid) { json_err(fd, 400, "bad uid"); return; }

    int64_t bal = db_account_balance(g.db, uid);
    if (bal < 0) { json_err(fd, 404, "not found"); return; }

    TxEntry hist[20];
    int nh = db_history(g.db, uid, hist, 20);
    if (nh < 0) nh = 0;

    char b[16384];
    int off = snprintf(b, sizeof(b),
        "{\"balance\":%lld,\"history\":[", (long long)bal);
    for (int i = 0; i < nh && off < (int)sizeof(b) - 256; i++) {
        off += snprintf(b + off, sizeof(b) - off,
            "%s{\"direction\":%d,\"counterpart\":%u,"
            "\"amount\":%llu,\"after_balance\":%lld}",
            i ? "," : "",
            hist[i].direction, hist[i].counterpart,
            (unsigned long long)hist[i].amount,
            (long long)hist[i].after_balance);
    }
    off += snprintf(b + off, sizeof(b) - off, "]}");
    json_ok(fd, b);
}

static void h_cashin(int fd, const char *body) {
    uint32_t uid    = jgetu32(body, "uid");
    uint64_t amount = jgetu64(body, "amount");
    char ref[128];  jgetstr(body, "ref", ref, sizeof(ref));

    if (!uid || !amount || !ref[0]) { json_err(fd, 400, "bad params"); return; }
    if (!db_account_exists(g.db, uid)) { json_err(fd, 404, "account not found"); return; }

    int64_t after = 0;
    if (db_admin_cash_in(g.db, uid, amount, &after) != 0) {
        json_err(fd, 500, "db error"); return;
    }

    /* Push EVT_CASH_IN to customer if online */
    uint8_t pb[16], evt[WIRE_MAX_FRAME];
    uint64_t amt64 = amount, bal64 = (uint64_t)after;
    pb[0]=(amt64>>56)&0xFF; pb[1]=(amt64>>48)&0xFF;
    pb[2]=(amt64>>40)&0xFF; pb[3]=(amt64>>32)&0xFF;
    pb[4]=(amt64>>24)&0xFF; pb[5]=(amt64>>16)&0xFF;
    pb[6]=(amt64>> 8)&0xFF; pb[7]=(amt64    )&0xFF;
    pb[8]=(bal64>>56)&0xFF; pb[9]=(bal64>>48)&0xFF;
    pb[10]=(bal64>>40)&0xFF;pb[11]=(bal64>>32)&0xFF;
    pb[12]=(bal64>>24)&0xFF;pb[13]=(bal64>>16)&0xFF;
    pb[14]=(bal64>> 8)&0xFF;pb[15]=(bal64    )&0xFF;
    size_t n = wire_frame_encode(WIRE_EVT_CASH_IN, 0, pb, 16, evt, sizeof(evt));
    if (n > 0) registry_push(uid, evt, n);

    char b[128];
    snprintf(b, sizeof(b), "{\"ok\":true,\"after_balance\":%lld}", (long long)after);
    json_ok(fd, b);
}

static void h_admin_users_list(int fd) {
    char buf[4096];
    if (db_admin_user_list(g.db, buf, sizeof(buf)) != 0) {
        json_err(fd, 500, "db error"); return;
    }
    char out[4160];
    snprintf(out, sizeof(out), "{\"admins\":%s}", buf);
    json_ok(fd, out);
}

static void h_admin_users_create(int fd, const char *body) {
    char username[64] = "", password[256] = "";
    jgetstr(body, "username", username, sizeof(username));
    jgetstr(body, "password", password, sizeof(password));
    if (!username[0] || !password[0]) {
        json_err(fd, 400, "missing username or password"); return;
    }
    uint8_t pw_hash[32];
    saving_sha256(password, strlen(password), pw_hash);
    if (db_admin_user_upsert(g.db, username, pw_hash) != 0) {
        json_err(fd, 500, "db error"); return;
    }
    json_ok(fd, "{\"ok\":true}");
}

static void h_admin_users_delete(int fd, const char *body) {
    char username[64] = "";
    jgetstr(body, "username", username, sizeof(username));
    if (!username[0]) { json_err(fd, 400, "missing username"); return; }
    if (db_admin_user_delete(g.db, username) != 0) {
        json_err(fd, 500, "db error"); return;
    }
    json_ok(fd, "{\"ok\":true}");
}

static void h_export(int fd, const char *query) {
    char from_date[16] = "", to_date[16] = "";

    const char *p = strstr(query, "from=");
    if (p) { p += 5; int i=0; while(*p&&*p!='&'&&i<10) from_date[i++]=*p++; }
    p = strstr(query, "to=");
    if (p) { p += 3;  int i=0; while(*p&&*p!='&'&&i<10) to_date[i++]=*p++;  }

    if (!from_date[0] || !to_date[0]) {
        json_err(fd, 400, "missing from/to (YYYY-MM-DD)"); return;
    }

    int rows = 0;
    char *csv = db_export_transfers_csv(g.db, from_date, to_date, &rows);
    if (!csv) { json_err(fd, 500, "db error"); return; }

    char fname[64];
    snprintf(fname, sizeof(fname), "transfers_%s_%s.csv", from_date, to_date);

    int csv_len = (int)strlen(csv);
    char hdr[512];
    int n = snprintf(hdr, sizeof(hdr),
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/csv; charset=utf-8\r\n"
        "Content-Disposition: attachment; filename=\"%s\"\r\n"
        "Content-Length: %d\r\n"
        "Access-Control-Allow-Origin: *\r\n"
        "Connection: close\r\n\r\n",
        fname, csv_len);
    write(fd, hdr, n);
    write(fd, csv, csv_len);
    free(csv);
}

static void h_cashout(int fd, const char *body) {
    uint32_t uid    = jgetu32(body, "uid");
    uint64_t amount = jgetu64(body, "amount");
    char ref[128];  jgetstr(body, "ref", ref, sizeof(ref));

    if (!uid || !amount || !ref[0]) { json_err(fd, 400, "bad params"); return; }
    if (!db_account_exists(g.db, uid)) { json_err(fd, 404, "account not found"); return; }

    int64_t after = 0;
    if (db_admin_cash_out(g.db, uid, amount, &after) != 0) {
        json_err(fd, 400, "insufficient balance"); return;
    }
    char b[128];
    snprintf(b, sizeof(b), "{\"ok\":true,\"after_balance\":%lld}", (long long)after);
    json_ok(fd, b);
}

/* ─── Embedded HTML admin panel ─────────────────────────────────────────── */
/*
 * Single-page app. HTML attributes use single quotes to avoid escaping.
 * JS uses ES6 arrow functions, template literals, and single-quote strings.
 */
static const char ADMIN_HTML[] =
"<!DOCTYPE html><html><head><meta charset='utf-8'><title>Saving Admin</title><style>"
"*{box-sizing:border-box;margin:0;padding:0}"
"body{font-family:monospace;background:#1a1a2e;color:#e0e0e0;font-size:14px}"
".hdr{background:#16213e;padding:12px 20px;display:flex;align-items:center;gap:12px;border-bottom:2px solid #0f3460}"
".hdr h1{font-size:14px;color:#e94560;letter-spacing:3px}"
".auth{display:flex;gap:8px;align-items:center;margin-left:auto}"
"input,select{background:#0f3460;border:1px solid #333;color:#e0e0e0;padding:6px 10px;"
"border-radius:3px;outline:none;font-family:monospace;font-size:13px}"
"input:focus,select:focus{border-color:#e94560}"
"button{background:#e94560;color:#fff;border:none;padding:6px 14px;border-radius:3px;"
"cursor:pointer;font-family:monospace;font-size:13px}"
"button:hover{background:#c73652}"
".tabs{display:flex;background:#16213e;border-bottom:1px solid #0f3460}"
".tab{padding:10px 20px;cursor:pointer;border-bottom:3px solid transparent;font-size:13px;color:#888}"
".tab.on{border-bottom-color:#e94560;color:#e94560}"
".pg{padding:20px;display:none}.pg.on{display:block}"
".grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-bottom:20px}"
".card{background:#16213e;padding:16px;border-radius:6px;border:1px solid #0f3460}"
".ct{font-size:11px;color:#555;text-transform:uppercase;letter-spacing:1px}"
".cv{font-size:22px;font-weight:bold;color:#e94560;margin-top:8px}"
"table{width:100%;border-collapse:collapse}"
"th,td{padding:9px 12px;text-align:left;border-bottom:1px solid #0f3460;font-size:13px}"
"th{color:#555;font-weight:normal;font-size:11px;text-transform:uppercase}"
".sec{background:#16213e;padding:20px;border-radius:6px;border:1px solid #0f3460;margin-bottom:16px}"
".sec h2{font-size:11px;color:#e94560;margin-bottom:16px;text-transform:uppercase;letter-spacing:2px}"
"label{display:block;font-size:11px;color:#666;margin-bottom:4px;text-transform:uppercase}"
".fg{margin-bottom:12px}.fg input,.fg select{width:100%}"
".ok{background:#1a3a1a;border:1px solid #4caf50;color:#4caf50;padding:8px 12px;"
"border-radius:3px;margin-bottom:12px}"
".er{background:#3a1a1a;border:1px solid #e94560;color:#e94560;padding:8px 12px;"
"border-radius:3px;margin-bottom:12px}"
".kb{background:#8b1a1a;font-size:11px;padding:3px 8px}"
"</style></head><body>"
"<div class='hdr'><h1>SAVING ADMIN</h1>"
"<div class='auth'>"
"<input id='un' type='text' placeholder='username' style='width:110px'>"
"<input id='pw' type='password' placeholder='password' style='width:130px'>"
"<button id='cb'>Login</button>"
"<span id='cs' style='font-size:12px;color:#888'></span>"
"</div></div>"
"<div class='tabs'>"
"<div class='tab on' id='t0'>Dashboard</div>"
"<div class='tab' id='t1'>Sessions</div>"
"<div class='tab' id='t2'>Account</div>"
"<div class='tab' id='t3'>Cash Ops</div>"
"<div class='tab' id='t4'>Export</div>"
"<div class='tab' id='t5'>Admins</div>"
"</div>"
"<div id='pg-dash' class='pg on'>"
"<div class='grid'>"
"<div class='card'><div class='ct'>Active Sessions</div><div class='cv' id='s0'>-</div></div>"
"<div class='card'><div class='ct'>Total Accounts</div><div class='cv' id='s1'>-</div></div>"
"<div class='card'><div class='ct'>Total Txns</div><div class='cv' id='s2'>-</div></div>"
"<div class='card'><div class='ct'>Volume (VND)</div><div class='cv' id='s3'>-</div></div>"
"</div>"
"<p style='color:#555;font-size:12px'>Auto-refreshes every 6s after connecting.</p>"
"</div>"
"<div id='pg-sess' class='pg'>"
"<div class='sec'>"
"<h2>Active Sessions <button id='rb' style='font-size:11px;padding:3px 8px;margin-left:6px'>Refresh</button></h2>"
"<table><thead><tr><th>MID</th><th>Expires In</th><th></th></tr></thead>"
"<tbody id='stb'></tbody></table>"
"</div></div>"
"<div id='pg-acct' class='pg'>"
"<div class='sec'>"
"<h2>Account Lookup</h2>"
"<div style='display:flex;gap:8px;margin-bottom:16px'>"
"<input id='lu' type='number' placeholder='UID' style='width:160px'>"
"<button id='sb'>Search</button></div>"
"<div id='ar'></div>"
"</div></div>"
"<div id='pg-cash' class='pg'>"
"<div class='sec' style='max-width:440px'>"
"<h2>Cash Operation</h2>"
"<div id='cr'></div>"
"<div class='fg'><label>Operation</label>"
"<select id='cop'>"
"<option value='cashin'>Cash In (Deposit to user)</option>"
"<option value='cashout'>Cash Out (Withdraw from user)</option>"
"</select></div>"
"<div class='fg'><label>UID</label><input id='cu' type='number'></div>"
"<div class='fg'><label>Amount (VND)</label><input id='ca' type='number'></div>"
"<div class='fg'><label>Reference</label><input id='cref' type='text' placeholder='ADM-2024-001'></div>"
"<button id='xb' style='width:100%'>Execute</button>"
"</div></div>"
"<div id='pg-exp' class='pg'>"
"<div class='sec' style='max-width:480px'>"
"<h2>Export Transfers CSV</h2>"
"<p style='color:#666;font-size:12px;margin-bottom:16px'>Thoi gian theo gio ICT (UTC+7). Toi da 100,000 dong.</p>"
"<div id='er2'></div>"
"<div class='fg'><label>Tu ngay</label><input id='ef' type='date' style='width:100%'></div>"
"<div class='fg'><label>Den ngay</label><input id='et' type='date' style='width:100%'></div>"
"<button id='dlb' style='width:100%'>Download CSV</button>"
"<div id='einfo' style='margin-top:12px;font-size:12px;color:#555'></div>"
"</div></div>"
"<div id='pg-adm' class='pg'>"
"<div class='sec' style='max-width:480px'>"
"<h2>Create Admin User</h2>"
"<div id='admr'></div>"
"<div class='fg'><label>Username</label><input id='anu' type='text' style='width:100%' autocomplete='off'></div>"
"<div class='fg'><label>Password</label><input id='anp' type='password' style='width:100%' autocomplete='new-password'></div>"
"<button id='anb' style='width:100%'>Create / Update</button>"
"</div>"
"<div class='sec' style='margin-top:16px'>"
"<h2>Admin Users <button id='arb' style='font-size:11px;padding:3px 8px;margin-left:6px'>Refresh</button></h2>"
"<table><thead><tr><th>Username</th><th>Created</th><th></th></tr></thead>"
"<tbody id='atb'></tbody></table>"
"</div></div>"
"<script>"
"var P='',ar=null;"
"var fmt=function(n){return Number(n).toLocaleString('vi-VN');};"
"function api(path,o){"
"  o=o||{};"
"  return fetch('/api'+path,Object.assign({"
"    headers:{'Authorization':'Bearer '+P,'Content-Type':'application/json'}"
"  },o)).then(function(r){if(!r.ok)throw new Error(r.status);return r.json();});"
"}"
"function conn(){"
"  var u=document.getElementById('un').value;"
"  var pw=document.getElementById('pw').value;"
"  var s=document.getElementById('cs');"
"  if(!u||!pw){s.textContent='enter username & password';s.style.color='#e94560';return;}"
"  s.textContent='logging in...';"
"  fetch('/api/login',{method:'POST',"
"    headers:{'Content-Type':'application/json'},"
"    body:JSON.stringify({username:u,password:pw})"
"  }).then(function(r){return r.json();})"
"  .then(function(d){"
"    if(!d.token)throw new Error('no token');"
"    P=d.token;"
"    s.textContent='logged in as '+d.username;"
"    s.style.color='#4caf50';"
"    if(ar)clearInterval(ar);"
"    ar=setInterval(loadStats,6000);"
"    loadStats();"
"  }).catch(function(){"
"    s.textContent='auth failed';"
"    s.style.color='#e94560';"
"  });"
"}"
"function loadStats(){"
"  return api('/stats').then(function(d){"
"    document.getElementById('s0').textContent=d.active_sessions;"
"    document.getElementById('s1').textContent=fmt(d.account_count);"
"    document.getElementById('s2').textContent=fmt(d.total_txns);"
"    document.getElementById('s3').textContent=fmt(d.total_volume);"
"  });"
"}"
"var TABS=['dash','sess','acct','cash','exp','adm'];"
"function tab(n){"
"  TABS.forEach(function(t,i){"
"    document.getElementById('pg-'+t).className='pg'+(t===n?' on':'');"
"    document.getElementById('t'+i).className='tab'+(t===n?' on':'');"
"  });"
"  if(n==='sess')loadSess();"
"  if(n==='adm')loadAdmins();"
"}"
"function loadSess(){"
"  api('/sessions').then(function(d){"
"    document.getElementById('stb').innerHTML=d.sessions.map(function(s){"
"      return '<tr><td>'+s.mid+'</td><td>'+s.expires_in_s+'s</td>'"
"            +'<td><button class=\"kb\" onclick=\"kill('+s.mid+')\">Kill</button></td></tr>';"
"    }).join('');"
"  });"
"}"
"function kill(m){"
"  if(!confirm('Kill all sessions for MID '+m+'?'))return;"
"  api('/sessions/kill',{method:'POST',body:JSON.stringify({mid:m})})"
"    .then(function(){loadSess();});"
"}"
"var dirs=['sent','recv','pay-sent','pay-recv','cash_in','cash_out'];"
"function lookup(){"
"  var u=document.getElementById('lu').value;"
"  if(!u)return;"
"  api('/account?uid='+u).then(function(d){"
"    var h=d.history.map(function(x){"
"      return '<tr><td>'+(dirs[x.direction]||x.direction)+'</td>'"
"            +'<td>'+x.counterpart+'</td>'"
"            +'<td>'+fmt(x.amount)+'</td>'"
"            +'<td>'+fmt(x.after_balance)+'</td></tr>';"
"    }).join('');"
"    document.getElementById('ar').innerHTML="
"      '<div style=\"margin-bottom:10px\">Balance: <strong style=\"color:#e94560\">'"
"      +fmt(d.balance)+' VND</strong></div>'"
"      +'<table><thead><tr><th>Dir</th><th>Counterpart</th><th>Amount</th><th>After</th></tr></thead>'"
"      +'<tbody>'+h+'</tbody></table>';"
"  }).catch(function(){"
"    document.getElementById('ar').innerHTML='<div class=\"er\">Account not found</div>';"
"  });"
"}"
"function doCash(){"
"  var op=document.getElementById('cop').value;"
"  var uid=Number(document.getElementById('cu').value);"
"  var amount=Number(document.getElementById('ca').value);"
"  var ref=document.getElementById('cref').value;"
"  if(!uid||!amount||!ref){alert('Fill all fields');return;}"
"  api('/'+op,{method:'POST',body:JSON.stringify({uid:uid,amount:amount,ref:ref})})"
"    .then(function(d){"
"      document.getElementById('cr').innerHTML="
"        '<div class=\"ok\">OK - after_balance: '+fmt(d.after_balance)+' VND</div>';"
"    }).catch(function(e){"
"      document.getElementById('cr').innerHTML="
"        '<div class=\"er\">Error: '+e.message+'</div>';"
"    });"
"}"
"document.getElementById('cb').onclick=conn;"
"document.getElementById('pw').onkeydown=function(e){if(e.key==='Enter')conn();};"
"document.getElementById('un').onkeydown=function(e){if(e.key==='Enter')document.getElementById('pw').focus();};"
"document.getElementById('rb').onclick=loadSess;"
"document.getElementById('sb').onclick=lookup;"
"document.getElementById('xb').onclick=doCash;"
"document.getElementById('lu').onkeydown=function(e){if(e.key==='Enter')lookup();};"
"document.getElementById('t0').onclick=function(){tab('dash');};"
"document.getElementById('t1').onclick=function(){tab('sess');};"
"document.getElementById('t2').onclick=function(){tab('acct');};"
"document.getElementById('t3').onclick=function(){tab('cash');};"
"document.getElementById('t4').onclick=function(){tab('exp');};"
"document.getElementById('t5').onclick=function(){tab('adm');};"
"document.getElementById('arb').onclick=loadAdmins;"
"document.getElementById('anb').onclick=function(){"
"  var u=document.getElementById('anu').value.trim();"
"  var p=document.getElementById('anp').value;"
"  var r=document.getElementById('admr');"
"  if(!u||!p){r.innerHTML='<div class=\"er\">Fill username and password</div>';return;}"
"  api('/admins',{method:'POST',body:JSON.stringify({username:u,password:p})})"
"    .then(function(){"
"      r.innerHTML='<div class=\"ok\">User saved.</div>';"
"      document.getElementById('anu').value='';"
"      document.getElementById('anp').value='';"
"      loadAdmins();"
"    }).catch(function(e){"
"      r.innerHTML='<div class=\"er\">Error: '+e.message+'</div>';"
"    });"
"};"
"function loadAdmins(){"
"  api('/admins').then(function(d){"
"    document.getElementById('atb').innerHTML=d.admins.map(function(a){"
"      return '<tr><td>'+a.username+'</td><td>'+a.create_time+'</td>'"
"            +'<td><button class=\"kb\" onclick=\"delAdmin(\\'' +a.username+ '\\')\">Delete</button></td></tr>';"
"    }).join('');"
"  });"
"}"
"function delAdmin(u){"
"  if(!confirm('Delete admin \\'' +u+ '\\'?'))return;"
"  api('/admins/delete',{method:'POST',body:JSON.stringify({username:u})})"
"    .then(function(){loadAdmins();});"
"}"
"document.getElementById('dlb').onclick=function(){"
"  var from=document.getElementById('ef').value;"
"  var to=document.getElementById('et').value;"
"  if(!from||!to){alert('Chon ngay');return;}"
"  document.getElementById('einfo').textContent='Dang tai...';"
"  fetch('/api/export?from='+from+'&to='+to,{"
"    headers:{'Authorization':'Bearer '+P}"
"  }).then(function(r){"
"    if(!r.ok)throw new Error(r.status);"
"    return r.blob();"
"  }).then(function(b){"
"    var a=document.createElement('a');"
"    a.href=URL.createObjectURL(b);"
"    a.download='transfers_'+from+'_'+to+'.csv';"
"    a.click();"
"    document.getElementById('einfo').textContent='Da tai xong.';"
"  }).catch(function(e){"
"    document.getElementById('er2').innerHTML='<div class=\"er\">Loi: '+e.message+'</div>';"
"    document.getElementById('einfo').textContent='';"
"  });"
"};"
"</script></body></html>";

/* ─── Request dispatcher ─────────────────────────────────────────────────── */

static void dispatch(int fd) {
    char buf[8192];
    int  total = 0;

    /* Read until \r\n\r\n or buffer full */
    while (total < (int)sizeof(buf) - 1) {
        int n = (int)read(fd, buf + total, sizeof(buf) - 1 - total);
        if (n <= 0) return;
        total += n;
        buf[total] = '\0';
        if (strstr(buf, "\r\n\r\n")) break;
    }

    char method[8], path[256];
    if (sscanf(buf, "%7s %255s", method, path) != 2) return;

    /* Split path from query string */
    char query[256] = "";
    char *q = strchr(path, '?');
    if (q) { *q = '\0'; strncpy(query, q + 1, sizeof(query) - 1); }

    /* HTML page — no auth required */
    if (strcmp(method, "GET") == 0 && strcmp(path, "/") == 0) {
        http_send(fd, 200, "text/html",
                  ADMIN_HTML, (int)sizeof(ADMIN_HTML) - 1);
        return;
    }

    /* Login endpoint — no auth required */
    if (strcmp(path, "/api/login") == 0 && strcmp(method, "POST") == 0) {
        /* Read POST body inline (before auth check) */
        char lbody[1024] = "";
        const char *cl = strstr(buf, "Content-Length: ");
        if (cl) {
            int clen = (int)atol(cl + 16);
            if (clen > 0 && clen < (int)sizeof(lbody)) {
                const char *hend = strstr(buf, "\r\n\r\n");
                if (hend) {
                    hend += 4;
                    int have = (int)(buf + total - hend);
                    if (have > 0) memcpy(lbody, hend, have);
                    int rem = clen - have;
                    while (rem > 0) {
                        int n = (int)read(fd, lbody + clen - rem, rem);
                        if (n <= 0) break; rem -= n;
                    }
                    lbody[clen] = '\0';
                }
            }
        }
        h_login(fd, lbody);
        return;
    }

    /* All /api/... require auth */
    if (!authed(buf)) { json_err(fd, 401, "unauthorized"); return; }

    /* Read POST body */
    char body[4096] = "";
    if (strcmp(method, "POST") == 0) {
        const char *cl = strstr(buf, "Content-Length: ");
        if (cl) {
            int clen = (int)atol(cl + 16);
            if (clen > 0 && clen < (int)sizeof(body)) {
                const char *hend = strstr(buf, "\r\n\r\n");
                if (hend) {
                    hend += 4;
                    int have = (int)(buf + total - hend);
                    if (have > 0) memcpy(body, hend, have);
                    int remaining = clen - have;
                    while (remaining > 0) {
                        int n = (int)read(fd, body + clen - remaining, remaining);
                        if (n <= 0) break;
                        remaining -= n;
                    }
                    body[clen] = '\0';
                }
            }
        }
    }

    /* Route */
    if      (strcmp(path, "/api/stats")         == 0) h_stats(fd);
    else if (strcmp(path, "/api/sessions")      == 0 &&
             strcmp(method, "GET")              == 0) h_sessions(fd);
    else if (strcmp(path, "/api/sessions/kill") == 0) h_kill(fd, body);
    else if (strcmp(path, "/api/account")       == 0) h_account(fd, query);
    else if (strcmp(path, "/api/cashin")        == 0) h_cashin(fd, body);
    else if (strcmp(path, "/api/cashout")       == 0) h_cashout(fd, body);
    else if (strcmp(path, "/api/export")        == 0) h_export(fd, query);
    else if (strcmp(path, "/api/admins")        == 0 &&
             strcmp(method, "GET")              == 0) h_admin_users_list(fd);
    else if (strcmp(path, "/api/admins")        == 0 &&
             strcmp(method, "POST")             == 0) h_admin_users_create(fd, body);
    else if (strcmp(path, "/api/admins/delete") == 0) h_admin_users_delete(fd, body);
    else json_err(fd, 404, "not found");
}

/* ─── Accept loop (runs in its own thread) ───────────────────────────────── */

static void *admin_thread(void *arg) {
    int sfd = (int)(intptr_t)arg;
    for (;;) {
        struct sockaddr_in addr;
        socklen_t alen = sizeof(addr);
        int fd = accept(sfd, (struct sockaddr *)&addr, &alen);
        if (fd < 0) {
            if (errno == EINTR) continue;
            break;
        }
        struct timeval tv = { .tv_sec = 5 };
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        dispatch(fd);
        close(fd);
    }
    return NULL;
}

/* ─── Startup ────────────────────────────────────────────────────────────── */

int admin_start(DB *db, SessionTable *st, uint16_t port, const char *password) {
    g.db = db;
    g.st = st;

    /* Seed default admin from env (or legacy password param) */
    const char *user = getenv("ADMIN_USER");
    if (!user || !user[0]) user = "admin";
    const char *pw = getenv("ADMIN_PASSWORD");
    if (!pw || !pw[0]) pw = password;
    if (pw && pw[0]) {
        uint8_t ph[32];
        saving_sha256(pw, strlen(pw), ph);
        db_admin_user_upsert(db, user, ph);
    }

    int sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) { perror("[admin] socket"); return -1; }

    int yes = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    struct sockaddr_in addr = {
        .sin_family      = AF_INET,
        .sin_addr.s_addr = INADDR_ANY,
        .sin_port        = htons(port)
    };
    if (bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("[admin] bind"); close(sfd); return -1;
    }
    if (listen(sfd, 8) < 0) {
        perror("[admin] listen"); close(sfd); return -1;
    }

    fprintf(stderr, "[admin] HTTP admin panel on :%u  (login: %s)\n", port, user);

    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_create(&tid, &attr, admin_thread, (void *)(intptr_t)sfd);
    pthread_attr_destroy(&attr);
    return 0;
}
