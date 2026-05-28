#include "handlers.h"
#include "registry.h"
#include "apns.h"
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <limits.h>
#include <pthread.h>
#include "crypto_compat.h"
#include <openssl/evp.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

/* ─── Maintenance mode ───────────────────────────────────────────────────── */

#include <signal.h>
static volatile sig_atomic_t g_maintenance = 0;
void maintenance_set(int on)  { g_maintenance = on ? 1 : 0; }
int  maintenance_get(void)    { return g_maintenance; }

/* ─── Push helper: registry first, APNs fallback ────────────────────────── */
void push_or_apns(DB *db, uint32_t mid,
                          const uint8_t *buf, size_t len,
                          const char *title, const char *apns_body) {
    if (registry_push(mid, buf, len) == 0) return;
    char tok[128] = "";
    if (db_push_token_get(db, mid, tok, sizeof(tok)) == 0 && tok[0])
        apns_notify_async(tok, title, apns_body);
}

/* ─── Forward declarations ───────────────────────────────────────────────── */
static void gateway_notify_async(const char *gateway_order_id, uint32_t paid_by);
static uint64_t fnv64(const uint8_t *data, int len);

/* ─── Session table ──────────────────────────────────────────────────────── */

#define SESSION_MAX 512

typedef struct {
    uint8_t  token[32];
    uint32_t mid;
    time_t   expires;
} Session;

struct SessionTable {
    Session         entries[SESSION_MAX];
    pthread_mutex_t mu;
};

SessionTable *session_table_new(void) {
    SessionTable *st = calloc(1, sizeof(SessionTable));
    pthread_mutex_init(&st->mu, NULL);
    return st;
}

void session_table_free(SessionTable *st) {
    pthread_mutex_destroy(&st->mu);
    free(st);
}

static void st_create(SessionTable *st, uint32_t mid, uint8_t out[32]) {
    arc4random_buf(out, 32);
    time_t now = time(NULL);
    pthread_mutex_lock(&st->mu);
    /* Find free or oldest slot */
    int slot = 0;
    time_t oldest = LONG_MAX;
    for (int i = 0; i < SESSION_MAX; i++) {
        if (st->entries[i].mid == 0) { slot = i; break; }
        if (st->entries[i].expires < oldest) {
            oldest = st->entries[i].expires;
            slot = i;
        }
    }
    memcpy(st->entries[slot].token, out, 32);
    st->entries[slot].mid     = mid;
    st->entries[slot].expires = now + WIRE_TOKEN_TTL_SEC;
    pthread_mutex_unlock(&st->mu);
}

static uint32_t st_lookup(SessionTable *st, const uint8_t token[32]) {
    time_t now = time(NULL);
    uint32_t mid = 0;
    pthread_mutex_lock(&st->mu);
    for (int i = 0; i < SESSION_MAX; i++) {
        if (st->entries[i].mid != 0 &&
            st->entries[i].expires > now &&
            memcmp(st->entries[i].token, token, 32) == 0) {
            mid = st->entries[i].mid;
            st->entries[i].expires = now + WIRE_TOKEN_TTL_SEC;
            break;
        }
    }
    pthread_mutex_unlock(&st->mu);
    return mid;
}

/* Extends TTL for token. Returns new remaining seconds, or -1 if not found/expired. */
static int32_t st_renew(SessionTable *st, const uint8_t token[32]) {
    time_t now = time(NULL);
    int32_t remaining = -1;
    pthread_mutex_lock(&st->mu);
    for (int i = 0; i < SESSION_MAX; i++) {
        if (st->entries[i].mid != 0 &&
            st->entries[i].expires > now &&
            memcmp(st->entries[i].token, token, 32) == 0) {
            st->entries[i].expires = now + WIRE_TOKEN_TTL_SEC;
            remaining = (int32_t)WIRE_TOKEN_TTL_SEC;
            break;
        }
    }
    pthread_mutex_unlock(&st->mu);
    return remaining;
}

static int st_is_online(SessionTable *st, uint32_t mid) {
    time_t now = time(NULL);
    int found = 0;
    pthread_mutex_lock(&st->mu);
    for (int i = 0; i < SESSION_MAX; i++) {
        if (st->entries[i].mid == mid && st->entries[i].expires > now) {
            found = 1; break;
        }
    }
    pthread_mutex_unlock(&st->mu);
    return found;
}

int st_list_sessions(SessionTable *st, SessionInfo *out, int max) {
    time_t now = time(NULL);
    int n = 0;
    pthread_mutex_lock(&st->mu);
    for (int i = 0; i < SESSION_MAX && n < max; i++) {
        if (st->entries[i].mid != 0 && st->entries[i].expires > now) {
            out[n].mid          = st->entries[i].mid;
            out[n].expires_in_s = (int32_t)(st->entries[i].expires - now);
            n++;
        }
    }
    pthread_mutex_unlock(&st->mu);
    return n;
}

void st_kill_mid(SessionTable *st, uint32_t mid) {
    pthread_mutex_lock(&st->mu);
    for (int i = 0; i < SESSION_MAX; i++) {
        if (st->entries[i].mid == mid)
            memset(&st->entries[i], 0, sizeof(Session));
    }
    pthread_mutex_unlock(&st->mu);
}

static void st_destroy(SessionTable *st, const uint8_t token[32]) {
    /* Resolve token → mid first, then wipe ALL sessions for that mid.
     * "Logout everywhere" — guarantees st_is_online(mid) returns 0 after. */
    pthread_mutex_lock(&st->mu);
    uint32_t mid = 0;
    for (int i = 0; i < SESSION_MAX; i++) {
        if (memcmp(st->entries[i].token, token, 32) == 0) {
            mid = st->entries[i].mid;
            break;
        }
    }
    if (mid != 0) {
        for (int i = 0; i < SESSION_MAX; i++) {
            if (st->entries[i].mid == mid)
                memset(&st->entries[i], 0, sizeof(Session));
        }
    }
    pthread_mutex_unlock(&st->mu);
}

/* ─── Byte helpers ───────────────────────────────────────────────────────── */

static inline uint32_t rd32(const uint8_t *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
static inline uint64_t rd64(const uint8_t *p) {
    return ((uint64_t)p[0]<<56)|((uint64_t)p[1]<<48)|((uint64_t)p[2]<<40)|
           ((uint64_t)p[3]<<32)|((uint64_t)p[4]<<24)|((uint64_t)p[5]<<16)|
           ((uint64_t)p[6]<<8 )| (uint64_t)p[7];
}
static inline void wr32(uint8_t *p, uint32_t v) {
    p[0]=v>>24; p[1]=v>>16; p[2]=v>>8; p[3]=v;
}
static inline void wr64(uint8_t *p, uint64_t v) {
    p[0]=v>>56; p[1]=v>>48; p[2]=v>>40; p[3]=v>>32;
    p[4]=v>>24; p[5]=v>>16; p[6]=v>>8;  p[7]=v;
}

/* ─── Response helpers ───────────────────────────────────────────────────── */

static void send_ack(int fd, uint32_t seq, uint8_t code,
                     const uint8_t *extra, uint16_t extra_len) {
    uint8_t buf[WIRE_MAX_FRAME];
    size_t n = wire_ack(seq, code, extra, extra_len, buf, sizeof(buf));
    if (n > 0) wire_send_raw(fd, buf, n);
}

static void send_login_ack(int fd, uint32_t seq, uint8_t code,
                           const uint8_t *token) {
    uint8_t body[33];
    body[0] = code;
    uint16_t body_len = 1;
    if (token) { memcpy(body + 1, token, 32); body_len = 33; }
    uint8_t buf[WIRE_MAX_FRAME];
    size_t n = wire_frame_encode(WIRE_LOGIN_ACK, seq, body, body_len,
                                 buf, sizeof(buf));
    if (n > 0) wire_send_raw(fd, buf, n);
}

/* ─── Individual handlers ────────────────────────────────────────────────── */

static void handle_ping(int fd, const WireFrame *f) {
    uint8_t buf[WIRE_MAX_FRAME];
    size_t n = wire_frame_encode(WIRE_PONG, f->seq, NULL, 0, buf, sizeof(buf));
    if (n > 0) wire_send_raw(fd, buf, n);
}

/* LOGIN  body: [mid 4B][pw_hash 32B] */
static void handle_login(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 36) {
        send_login_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL); return;
    }
    uint32_t mid           = rd32(f->body);
    const uint8_t *pw_hash = f->body + 4;

    uint8_t stored[32];
    if (db_account_get_hash(db, mid, stored) != 0) {
        send_login_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL); return;
    }
    if (memcmp(stored, pw_hash, 32) != 0) {
        send_login_ack(fd, f->seq, WIRE_ERR_BAD_PASSWORD, NULL); return;
    }

    uint8_t token[32];
    st_create(st, mid, token);
    registry_add(mid, fd);
    send_login_ack(fd, f->seq, WIRE_OK, token);
}

/* LOGOUT  body: [token 32B] */
static void handle_logout(SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len >= 32) st_destroy(st, f->body);
    registry_remove(fd);
    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* RENEW_SESSION  body: [token 32B]
 * ACK extra: [remaining_s 4B] — seconds remaining until next expiry (= TTL after renewal). */
static void handle_renew_session(SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 32) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    int32_t remaining = st_renew(st, f->body);
    if (remaining < 0) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return;
    }
    uint8_t extra[4];
    extra[0] = (remaining >> 24) & 0xFF;
    extra[1] = (remaining >> 16) & 0xFF;
    extra[2] = (remaining >>  8) & 0xFF;
    extra[3] = (remaining      ) & 0xFF;
    send_ack(fd, f->seq, WIRE_OK, extra, 4);
}

/* CREATE_ACCOUNT  body: [mid 4B][pw_hash 32B] */
static void handle_create_account(DB *db, int fd, const WireFrame *f) {
    if (f->body_len < 36) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t id            = rd32(f->body);
    const uint8_t *pw_hash = f->body + 4;

    if (id < SAVING_ID_USER_MIN || id > SAVING_ID_MAX) {
        send_ack(fd, f->seq, WIRE_ERR_ID_RESERVED, NULL, 0); return;
    }
    if (db_account_exists(db, id)) {
        send_ack(fd, f->seq, WIRE_ERR_ID_TAKEN, NULL, 0); return;
    }
    if (db_account_create(db, id, pw_hash) != 0) {
        send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return;
    }
    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* TRANSFER  body: [token 32B][to_id 4B][amount 8B][ref 8B]
 * ACK extra:     [txn_id 8B][after_balance 8B] */
static void handle_transfer(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 52) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid    = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }
    uint32_t debit  = mid;
    uint32_t credit = rd32(f->body + 32);
    uint64_t amount = rd64(f->body + 36);
    uint64_t ref    = rd64(f->body + 44);

    int claim = db_idempotency_claim(db, (uint64_t)debit, ref, 0);
    if (claim == 0) { send_ack(fd, f->seq, WIRE_OK, NULL, 0); return; }
    if (claim <  0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    int64_t after_bal = 0, txn_id = 0;
    int rc = db_transfer(db, debit, credit, amount, 0, &after_bal, &txn_id);
    if (rc == -1) { send_ack(fd, f->seq, WIRE_ERR_LOW_BALANCE, NULL, 0); return; }
    if (rc == -2) { send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND,   NULL, 0); return; }
    if (rc != 0)  { send_ack(fd, f->seq, WIRE_ERR_INTERNAL,    NULL, 0); return; }

    /* Push EVT_TRANSFER_IN to recipient; APNs fallback if offline */
    int64_t new_bal = db_account_balance(db, credit);
    if (new_bal >= 0) {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      debit);
        wr64(body + 4,  amount);
        wr64(body + 12, (uint64_t)new_bal);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_IN, 0, body, 20,
                                     evt, sizeof(evt));
        if (n > 0) {
            char ab[80];
            snprintf(ab, sizeof(ab), "+%llu \xe2\x82\xab t\xe1\xbb\xab #%u",
                     (unsigned long long)amount, (unsigned)debit);
            push_or_apns(db, credit, evt, n, "Nh\xe1\xba\xadn ti\xe1\xbb\x81n", ab);
        }
    }

    /* Push EVT_TRANSFER_OUT to sender's other sessions if online */
    {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      credit);
        wr64(body + 4,  amount);
        wr64(body + 12, (uint64_t)after_bal);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_OUT, 0, body, 20,
                                     evt, sizeof(evt));
        if (n > 0) registry_push(debit, evt, n);
    }

    uint8_t extra[16];
    wr64(extra,     (uint64_t)txn_id);
    wr64(extra + 8, (uint64_t)after_bal);
    send_ack(fd, f->seq, WIRE_OK, extra, 16);
}

/* GET_BALANCE  body: [token 32B]
 * ACK extra:  [balance 8B][pending 8B][available_balance 8B][version 8B] */
static void handle_get_balance(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 32) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    BalanceDetail detail;
    if (db_account_balance_detail(db, mid, &detail) != 0) {
        send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return;
    }

    uint8_t extra[32];
    wr64(extra,      (uint64_t)detail.balance);
    wr64(extra +  8, (uint64_t)detail.pending);
    wr64(extra + 16, (uint64_t)detail.available_balance);
    wr64(extra + 24, (uint64_t)detail.version);
    send_ack(fd, f->seq, WIRE_OK, extra, 32);
}

/* ADD_GUARDIAN  body: [token 32B][guardian_id 4B] */
static void handle_add_guardian(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 36) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid         = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }
    uint32_t guardian_id = rd32(f->body + 32);

    if (!db_account_exists(db, guardian_id)) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
    }
    int rc = db_guardian_add(db, mid, guardian_id);
    if (rc == -1) { send_ack(fd, f->seq, WIRE_ERR_GUARDIAN_FULL, NULL, 0); return; }
    if (rc != 0)  { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }
    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* RECOVERY_REQ  body: [mid 4B] */
static void handle_recovery_req(DB *db, int fd, const WireFrame *f) {
    if (f->body_len < 4) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t id = rd32(f->body);

    if (!db_account_exists(db, id)) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
    }
    if (db_guardian_count(db, id) < 2) {
        send_ack(fd, f->seq, WIRE_ERR_NEED_GUARDIANS, NULL, 0); return;
    }
    db_recovery_open(db, id);

    uint32_t guardians[3] = {0};
    int n = db_guardian_list(db, id, guardians);
    uint8_t extra[12];
    for (int i = 0; i < n; i++) wr32(extra + i * 4, guardians[i]);
    send_ack(fd, f->seq, WIRE_OK, extra, (uint16_t)(n * 4));
}

/* RECOVERY_APPROVE  body: [token 32B][target_id 4B] */
static void handle_recovery_approve(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 36) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t guardian_id = st_lookup(st, f->body);
    if (!guardian_id) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }
    uint32_t target_id = rd32(f->body + 32);

    int approvals = db_recovery_approve(db, target_id, guardian_id);
    if (approvals < 0) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_GUARDIAN, NULL, 0); return;
    }

    if (db_recovery_is_complete(db, target_id)) {
        db_recovery_close(db, target_id);
        uint8_t extra[4];
        wr32(extra, target_id);
        send_ack(fd, f->seq, WIRE_OK, extra, 4);
    } else {
        uint8_t extra[1] = { (uint8_t)approvals };
        send_ack(fd, f->seq, WIRE_OK, extra, 1);
    }
}

/* GET_HISTORY  body: [token 32B]
 * ACK extra:  [count 1B][direction 1B | counterpart 4B | amount 8B | after_balance 8B] × count */
static void handle_get_history(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 32) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    int64_t before_id = (f->body_len >= 40) ? (int64_t)rd64(f->body + 32) : 0;

    TxEntry entries[20];
    int n = db_history(db, mid, entries, 20, before_id);
    if (n < 0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    /* Pack: [count 1B][dir 1B + counterpart 4B + amount 8B + after_bal 8B]×n + [next_cursor 8B] */
    uint8_t extra[1 + 20 * 21 + 8];
    extra[0] = (uint8_t)n;
    for (int i = 0; i < n; i++) {
        uint8_t *e = extra + 1 + i * 21;
        e[0] = (uint8_t)entries[i].direction;
        wr32(e + 1,  entries[i].counterpart);
        wr64(e + 5,  entries[i].amount);
        wr64(e + 13, (uint64_t)entries[i].after_balance);
    }
    /* next_cursor = txn_id of the oldest (last) entry; 0 if empty or last page */
    int64_t cursor = (n > 0) ? entries[n - 1].txn_id : 0;
    wr64(extra + 1 + n * 21, (uint64_t)cursor);
    send_ack(fd, f->seq, WIRE_OK, extra, (uint16_t)(1 + n * 21 + 8));
}

/* ─── TOTP verify (HMAC-SHA256, 30s window ±1 step) ─────────────────────── */

static int totp_verify(const uint8_t *secret, uint32_t code) {
    time_t now = time(NULL);
    for (int delta = -1; delta <= 1; delta++) {
        uint64_t T = (uint64_t)(now / 30) + (uint64_t)delta;
        uint8_t msg[8];
        for (int i = 7; i >= 0; i--) { msg[i] = T & 0xff; T >>= 8; }
        uint8_t hmac[CC_SHA256_DIGEST_LENGTH];
        CCHmac(kCCHmacAlgSHA256, secret, 20, msg, 8, hmac);
        int offset = hmac[CC_SHA256_DIGEST_LENGTH - 1] & 0x0f;
        uint32_t otp = ((uint32_t)(hmac[offset]     & 0x7f) << 24) |
                       ((uint32_t) hmac[offset + 1]         << 16) |
                       ((uint32_t) hmac[offset + 2]         <<  8) |
                        (uint32_t) hmac[offset + 3];
        if (otp % 1000000 == code) return 1;
    }
    return 0;
}

/* ENROLL_TOTP  body: [merchant_token 32B][customer_id 4B][secret 20B] */
/* REGISTER_MERCHANT body: [token 32B][name N bytes] */
static void handle_register_merchant(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 32) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    size_t name_len = f->body_len - 32;
    char name[256];
    if (name_len >= sizeof(name)) name_len = sizeof(name) - 1;
    memcpy(name, f->body + 32, name_len);
    name[name_len] = '\0';

    if (db_merchant_register(db, mid, name) < 0) {
        send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return;
    }
    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

static void handle_enroll_totp(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 56) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t merchant_id = st_lookup(st, f->body);
    if (!merchant_id) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    if (db_merchant_exists(db, merchant_id) != 1) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_MERCHANT, NULL, 0); return;
    }

    uint32_t customer_id    = rd32(f->body + 32);
    const uint8_t *secret   = f->body + 36;

    if (db_totp_enroll(db, merchant_id, customer_id, secret) != 0) {
        send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return;
    }
    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* CREATE_INTENT  body: [merchant_token 32B][request_id 8B][order_id 8B][amount 8B][gateway_order_id N bytes]
 *
 * 2-step check after HMAC (done by parser):
 *   1. Idempotency gate  — (mid, request_id): exact-replay guard
 *   2. Order dedup       — (mid, order_id):   same order already has an intent → return it
 */
static void handle_create_intent(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 56) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    if (db_merchant_exists(db, mid) != 1) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_MERCHANT, NULL, 0); return;
    }

    uint64_t request_id = rd64(f->body + 32);
    uint64_t order_id   = rd64(f->body + 40);
    uint64_t amount     = rd64(f->body + 48);

    /* ── Step 1: idempotency (mid, request_id) ───────────────────────────── */
    int claim = db_idempotency_claim(db, (uint64_t)mid, request_id, order_id);
    if (claim == 0) { send_ack(fd, f->seq, WIRE_OK, NULL, 0); return; }
    if (claim <  0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    /* ── Step 2: order dedup (mid, order_id) — same order, new request ──── */
    IntentInfo existing;
    if (db_intent_find_by_order(db, mid, order_id, &existing) == 0) {
        if (existing.status == 1) {
            send_ack(fd, f->seq, WIRE_ERR_INTENT_SETTLED, NULL, 0); return;
        }
        uint8_t extra[21];
        extra[0] = 0;   /* 0 = pending intent already exists */
        wr32(extra + 1,  mid);
        wr64(extra + 5,  request_id);
        wr64(extra + 13, existing.amount);
        send_ack(fd, f->seq, WIRE_OK, extra, 21);
        return;
    }

    /* ── Create new intent ───────────────────────────────────────────────── */
    char gateway_order_id[256] = "";
    if (f->body_len > 56) {
        size_t n = f->body_len - 56;
        if (n >= sizeof(gateway_order_id)) n = sizeof(gateway_order_id) - 1;
        memcpy(gateway_order_id, f->body + 56, n);
        gateway_order_id[n] = '\0';
    }

    int rc = db_intent_create(db, mid, request_id, order_id, amount, gateway_order_id);
    if (rc < 0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    /* [status 1B][mid 4B][request_id 8B][amount 8B] — client builds QR from this */
    uint8_t extra[21];
    extra[0] = 1;   /* 1 = newly created */
    wr32(extra + 1,  mid);
    wr64(extra + 5,  request_id);
    wr64(extra + 13, amount);
    send_ack(fd, f->seq, WIRE_OK, extra, 21);
}

/* PAY_INTENT  body: [customer_token 32B][merchant_id 4B][request_id 8B][totp_code 4B] */
static void handle_pay_intent(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 48) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t customer_id = st_lookup(st, f->body);
    if (!customer_id) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }
    uint32_t merchant_id = rd32(f->body + 32);
    uint64_t request_id  = rd64(f->body + 36);
    uint32_t totp_code   = rd32(f->body + 44);

    /* Verify TOTP secret enrolled for this merchant↔customer pair */
    uint8_t secret[20];
    if (db_totp_get_secret(db, merchant_id, customer_id, secret) != 0) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
    }
    if (!totp_verify(secret, totp_code)) {
        send_ack(fd, f->seq, WIRE_ERR_TOTP_INVALID, NULL, 0); return;
    }

    /* Load intent */
    IntentInfo intent;
    if (db_intent_get(db, merchant_id, request_id, &intent) != 0) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
    }
    if (intent.status != 0) {
        send_ack(fd, f->seq, WIRE_ERR_INTENT_SETTLED, NULL, 0); return;
    }

    uint32_t debit  = customer_id;
    uint32_t credit = merchant_id;
    int64_t after_cust = 0;
    int rc = db_transfer(db, debit, credit, intent.amount, 1, &after_cust, NULL);
    if (rc == -1) { send_ack(fd, f->seq, WIRE_ERR_LOW_BALANCE, NULL, 0); return; }
    if (rc == -2) { send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND,   NULL, 0); return; }
    if (rc != 0)  { send_ack(fd, f->seq, WIRE_ERR_INTERNAL,    NULL, 0); return; }
    db_intent_settle(db, merchant_id, request_id);

    /* Sổ cái: append audit row for this payment */
    uint8_t extra[4];
    extra[0] = (merchant_id >> 24) & 0xff;
    extra[1] = (merchant_id >> 16) & 0xff;
    extra[2] = (merchant_id >>  8) & 0xff;
    extra[3] =  merchant_id        & 0xff;
    db_ledger_append(db, customer_id, request_id, (uint64_t)merchant_id,
                     0x21 /* PAY_INTENT */, intent.amount, extra, 4);

    /* Notify Merchant Gateway to mark order as paid */
    gateway_notify_async(intent.gateway_order_id, customer_id);

    /* Push EVT_TRANSFER_IN to merchant; APNs fallback if offline */
    int64_t merch_bal = db_account_balance(db, merchant_id);
    if (merch_bal >= 0) {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      customer_id);
        wr64(body + 4,  intent.amount);
        wr64(body + 12, (uint64_t)merch_bal);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_IN, 0, body, 20,
                                     evt, sizeof(evt));
        if (n > 0) {
            char ab[80];
            snprintf(ab, sizeof(ab), "+%llu \xe2\x82\xab t\xe1\xbb\xab #%u",
                     (unsigned long long)intent.amount, (unsigned)customer_id);
            push_or_apns(db, merchant_id, evt, n, "Nh\xe1\xba\xadn ti\xe1\xbb\x81n", ab);
        }
    }

    /* Push EVT_INTENT_PAID: [request_id 8B][customer_id 4B][amount 8B] */
    {
        uint8_t ip_body[20], ip_evt[WIRE_MAX_FRAME];
        wr64(ip_body,      request_id);
        wr32(ip_body + 8,  customer_id);
        wr64(ip_body + 12, intent.amount);
        size_t ip_n = wire_frame_encode(WIRE_EVT_INTENT_PAID, 0, ip_body, 20,
                                        ip_evt, sizeof(ip_evt));
        if (ip_n > 0) {
            char ab[80];
            snprintf(ab, sizeof(ab), "+%llu \xe2\x82\xab t\xe1\xbb\xab #%u",
                     (unsigned long long)intent.amount, (unsigned)customer_id);
            push_or_apns(db, merchant_id, ip_evt, ip_n,
                         "\xc4\x90\xc6\xa1n h\xc3\xa0ng \xc4\x91\xc6\xb0\xe1\xbb\xa3"
                         "c thanh to\xc3\xa1n", ab);
        }
    }

    /* Push EVT_TRANSFER_OUT to customer's other sessions */
    {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      merchant_id);
        wr64(body + 4,  intent.amount);
        wr64(body + 12, (uint64_t)after_cust);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_OUT, 0, body, 20,
                                     evt, sizeof(evt));
        if (n > 0) registry_push(customer_id, evt, n);
    }

    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* Verify Ed25519 signature using OpenSSL EVP (available on both macOS brew + Linux). */
static int ed25519_verify(const uint8_t pubkey[32], const uint8_t sig[64],
                          const uint8_t *msg, size_t msg_len) {
    EVP_PKEY *pkey = EVP_PKEY_new_raw_public_key(EVP_PKEY_ED25519, NULL, pubkey, 32);
    if (!pkey) return -1;
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (!ctx) { EVP_PKEY_free(pkey); return -1; }
    int ok = (EVP_DigestVerifyInit(ctx, NULL, NULL, NULL, pkey) == 1) &&
             (EVP_DigestVerify(ctx, sig, 64, msg, msg_len) == 1);
    EVP_MD_CTX_free(ctx);
    EVP_PKEY_free(pkey);
    return ok ? 0 : -1;
}

/* CONFIRM_INTENT  body (standard):  [customer_token 32B][merchant_id 4B][request_id 8B]       = 44B
 *                body (signed QR):  [customer_token 32B][merchant_id 4B][request_id 8B]
 *                                   [amount 8B][sig 64B]                                       = 116B
 *
 * Signed variant skips db_intent_get — merchant signed the QR locally offline.
 * Signed msg = mid(4BE) || amount(8BE) || request_id(8BE)  (20 bytes fixed)
 */
static void handle_confirm_intent(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 44) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t customer_id = st_lookup(st, f->body);
    if (!customer_id) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }
    uint32_t merchant_id = rd32(f->body + 32);
    uint64_t request_id  = rd64(f->body + 36);

    uint64_t pay_amount;

    if (f->body_len >= 116) {
        /* ── Signed QR path: verify Ed25519, no pre-registered intent needed ── */
        uint64_t amount_from_qr = rd64(f->body + 44);
        const uint8_t *sig      = f->body + 52;

        uint8_t pubkey[32];
        if (db_merchant_pubkey_get(db, merchant_id, pubkey) != 0) {
            send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
        }

        /* signed msg = mid(4BE) || amount(8BE) || request_id(8BE) */
        uint8_t msg[20];
        wr32(msg,      merchant_id);
        wr64(msg + 4,  amount_from_qr);
        wr64(msg + 12, request_id);

        if (ed25519_verify(pubkey, sig, msg, 20) != 0) {
            send_ack(fd, f->seq, WIRE_ERR_BAD_SIG, NULL, 0); return;
        }
        pay_amount = amount_from_qr;
    } else {
        /* ── Standard path: look up pre-registered intent ── */
        IntentInfo intent;
        if (db_intent_get(db, merchant_id, request_id, &intent) != 0) {
            send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
        }
        if (intent.status != 0) {
            send_ack(fd, f->seq, WIRE_ERR_INTENT_SETTLED, NULL, 0); return;
        }
        pay_amount = intent.amount;
    }

    int64_t after_cust = 0, txn_id = 0;
    int rc = db_transfer(db, customer_id, merchant_id, pay_amount, 1, &after_cust, &txn_id);
    if (rc == -1) { send_ack(fd, f->seq, WIRE_ERR_LOW_BALANCE, NULL, 0); return; }
    if (rc == -2) { send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND,   NULL, 0); return; }
    if (rc != 0)  { send_ack(fd, f->seq, WIRE_ERR_INTERNAL,    NULL, 0); return; }

    if (f->body_len < 116) {
        /* Standard path: settle intent + gateway callback */
        IntentInfo settled_intent;
        if (db_intent_get(db, merchant_id, request_id, &settled_intent) == 0)
            gateway_notify_async(settled_intent.gateway_order_id, customer_id);
        db_intent_settle(db, merchant_id, request_id);
    }

    /* Push EVT_TRANSFER_IN to merchant; APNs fallback if offline */
    int64_t merch_bal = db_account_balance(db, merchant_id);
    if (merch_bal >= 0) {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      customer_id);
        wr64(body + 4,  pay_amount);
        wr64(body + 12, (uint64_t)merch_bal);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_IN, 0, body, 20, evt, sizeof(evt));
        if (n > 0) {
            char ab[80];
            snprintf(ab, sizeof(ab), "+%llu \xe2\x82\xab t\xe1\xbb\xab #%u",
                     (unsigned long long)pay_amount, (unsigned)customer_id);
            push_or_apns(db, merchant_id, evt, n, "Nh\xe1\xba\xadn ti\xe1\xbb\x81n", ab);
        }
    }

    /* Push EVT_INTENT_PAID: [request_id 8B][customer_id 4B][amount 8B] */
    {
        uint8_t ip_body[20], ip_evt[WIRE_MAX_FRAME];
        wr64(ip_body,      request_id);
        wr32(ip_body + 8,  customer_id);
        wr64(ip_body + 12, pay_amount);
        size_t ip_n = wire_frame_encode(WIRE_EVT_INTENT_PAID, 0, ip_body, 20,
                                        ip_evt, sizeof(ip_evt));
        if (ip_n > 0) {
            char ab[80];
            snprintf(ab, sizeof(ab), "+%llu \xe2\x82\xab t\xe1\xbb\xab #%u",
                     (unsigned long long)pay_amount, (unsigned)customer_id);
            push_or_apns(db, merchant_id, ip_evt, ip_n,
                         "\xc4\x90\xc6\xa1n h\xc3\xa0ng \xc4\x91\xc6\xb0\xe1\xbb\xa3"
                         "c thanh to\xc3\xa1n", ab);
        }
    }

    /* Push EVT_TRANSFER_OUT to customer's other sessions */
    {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      merchant_id);
        wr64(body + 4,  pay_amount);
        wr64(body + 12, (uint64_t)after_cust);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_OUT, 0, body, 20,
                                     evt, sizeof(evt));
        if (n > 0) registry_push(customer_id, evt, n);
    }

    /* ACK extra: [txn_id 8B][after_balance 8B] — app relays txn_id to Merchants for pull-verify */
    uint8_t extra[16];
    wr64(extra,     (uint64_t)txn_id);
    wr64(extra + 8, (uint64_t)after_cust);
    send_ack(fd, f->seq, WIRE_OK, extra, 16);
}

/* GET_MERCHANT_HISTORY  body: [merchant_token 32B]
 * ACK extra: [count 1B][customer_id 4B | amount 8B | after_balance 8B]xN
 * Returns up to 20 most recent C2M payments received by the merchant. */
static void handle_get_merchant_history(DB *db, SessionTable *st,
                                        int fd, const WireFrame *f) {
    if (f->body_len < 32) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    if (db_merchant_exists(db, mid) != 1) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_MERCHANT, NULL, 0); return;
    }

    int64_t before_id = (f->body_len >= 40) ? (int64_t)rd64(f->body + 32) : 0;

    MerchantTxEntry entries[20];
    int n = db_merchant_history(db, mid, entries, 20, before_id);
    if (n < 0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    /* Pack: [count 1B][customer_id 4B + amount 8B + after_bal 8B]×n + [next_cursor 8B] */
    uint8_t extra[1 + 20 * 20 + 8];
    extra[0] = (uint8_t)n;
    for (int i = 0; i < n; i++) {
        uint8_t *e = extra + 1 + i * 20;
        wr32(e,      entries[i].customer_id);
        wr64(e + 4,  entries[i].amount);
        wr64(e + 12, (uint64_t)entries[i].after_balance);
    }
    int64_t cursor = (n > 0) ? entries[n - 1].txn_id : 0;
    wr64(extra + 1 + n * 20, (uint64_t)cursor);
    send_ack(fd, f->seq, WIRE_OK, extra, (uint16_t)(1 + n * 20 + 8));
}

/* TOTP_CHARGE  body: [merchant_token 32B][customer_uid 4B][totp_code 4B][amount 8B]
 * Any registered merchant may charge a customer via their TOTP code.
 * Merchant scans customer QR (saving://totp-pay?uid=X&code=6DIGITS&amount=A),
 * then sends this frame with the 6-digit RFC 6238 code.
 */
static void handle_totp_charge(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 48) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t merchant_id = st_lookup(st, f->body);
    if (!merchant_id) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    if (db_merchant_exists(db, merchant_id) != 1) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_MERCHANT, NULL, 0); return;
    }

    uint32_t customer_id = rd32(f->body + 32);
    uint32_t totp_code   = rd32(f->body + 36);
    uint64_t amount      = rd64(f->body + 40);

    uint8_t secret[20];
    if (db_totp_get_secret(db, merchant_id, customer_id, secret) != 0) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
    }
    if (!totp_verify(secret, totp_code)) {
        send_ack(fd, f->seq, WIRE_ERR_TOTP_INVALID, NULL, 0); return;
    }

    uint32_t debit  = customer_id;
    uint32_t credit = merchant_id;
    int64_t after_cust = 0;
    int rc = db_transfer(db, debit, credit, amount, 1, &after_cust, NULL);
    if (rc == -1) { send_ack(fd, f->seq, WIRE_ERR_LOW_BALANCE, NULL, 0); return; }
    if (rc == -2) { send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND,   NULL, 0); return; }
    if (rc != 0)  { send_ack(fd, f->seq, WIRE_ERR_INTERNAL,    NULL, 0); return; }

    /* Push EVT_TRANSFER_IN to merchant if online */
    int64_t merch_bal = db_account_balance(db, credit);
    if (merch_bal >= 0) {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      debit);
        wr64(body + 4,  amount);
        wr64(body + 12, (uint64_t)merch_bal);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_IN, 0, body, 20, evt, sizeof(evt));
        if (n > 0) registry_push(credit, evt, n);
    }

    /* Push EVT_TOTP_CHARGED to customer; APNs fallback if offline */
    {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      credit);
        wr64(body + 4,  amount);
        wr64(body + 12, (uint64_t)after_cust);
        size_t n = wire_frame_encode(WIRE_EVT_TOTP_CHARGED, 0, body, 20, evt, sizeof(evt));
        if (n > 0) {
            char ab[80];
            snprintf(ab, sizeof(ab), "-%llu \xe2\x82\xab t\xe1\xba\xa1i #%u",
                     (unsigned long long)amount, (unsigned)credit);
            push_or_apns(db, debit, evt, n, "Thanh to\xc3\xa1n QR", ab);
        }
    }

    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* CASH_OUT  body: [bank_token 32B][from_uid 4B][amount 8B][cashout_id N bytes]
 * Caller must be a bank entity (uid 1-999). Debits from_uid and credits bank.
 * cashout_id is hashed for idempotency — safe to retry. */
static void handle_cash_out(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 44) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t bank_mid = st_lookup(st, f->body);
    if (!bank_mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    if (bank_mid > 999) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_MERCHANT, NULL, 0); return;
    }

    uint32_t debit  = rd32(f->body + 32);
    uint32_t credit = bank_mid;
    uint64_t amount = rd64(f->body + 36);

    if (!db_account_exists(db, debit)) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
    }

    uint64_t idem_key;
    if (f->body_len > 44) {
        idem_key = fnv64(f->body + 44, f->body_len - 44);
    } else {
        idem_key = (uint64_t)f->seq;
    }

    int claim = db_idempotency_claim(db, (uint64_t)credit, idem_key, 1);
    if (claim == 0) { send_ack(fd, f->seq, WIRE_OK, NULL, 0); return; }
    if (claim <  0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    int64_t after_bal = 0;
    int rc = db_transfer(db, debit, credit, amount, 3, &after_bal, NULL);
    if (rc == -1) { send_ack(fd, f->seq, WIRE_ERR_LOW_BALANCE, NULL, 0); return; }
    if (rc == -2) { send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND,   NULL, 0); return; }
    if (rc != 0)  { send_ack(fd, f->seq, WIRE_ERR_INTERNAL,    NULL, 0); return; }

    /* Push EVT_CASH_OUT to customer if online */
    if (after_bal >= 0) {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      credit);
        wr64(body + 4,  amount);
        wr64(body + 12, (uint64_t)after_bal);
        size_t n = wire_frame_encode(WIRE_EVT_CASH_OUT, 0, body, 20,
                                     evt, sizeof(evt));
        if (n > 0) registry_push(debit, evt, n);
    }

    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* GET_MERCHANT_INFO  body: [token 32B][merchant_id 4B]
 * Any authenticated user can query a merchant's public name. */
static void handle_get_merchant_info(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 36) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    uint32_t merchant_id = rd32(f->body + 32);

    char name[256];
    if (db_merchant_get_name(db, merchant_id, name, sizeof(name)) != 0) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND, NULL, 0); return;
    }

    uint16_t name_len = (uint16_t)strlen(name);
    send_ack(fd, f->seq, WIRE_OK, (const uint8_t *)name, name_len);
}

/* LIST_INTENTS  body: [merchant_token 32B]
 * Returns up to 10 pending (status=0) intents for the calling merchant,
 * newest first.  ACK extra: [count 1B][request_id 8B | amount 8B] x count */
static void handle_list_intents(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 32) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    if (db_merchant_exists(db, mid) != 1) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_MERCHANT, NULL, 0); return;
    }

    IntentSummary intents[10];
    int n = db_intent_list(db, mid, intents, 10);
    if (n < 0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    /* Pack: [count 1B][request_id 8B + amount 8B] x n */
    uint8_t extra[1 + 10 * 16];
    extra[0] = (uint8_t)n;
    for (int i = 0; i < n; i++) {
        uint8_t *e = extra + 1 + i * 16;
        wr64(e,     intents[i].request_id);
        wr64(e + 8, intents[i].amount);
    }
    send_ack(fd, f->seq, WIRE_OK, extra, (uint16_t)(1 + n * 16));
}

/* ─── Gateway notification ───────────────────────────────────────────────── */

/* Fire-and-forget: POST /orders/{gateway_order_id}/confirm to localhost:8090 */
static void *gateway_confirm_thread(void *arg) {
    char *s = (char *)arg;           /* "ORDER_ID|PAID_BY" */
    char *sep = strchr(s, '|');
    if (!sep) { free(s); return NULL; }
    *sep = '\0';
    const char *order_id = s;
    int paid_by = atoi(sep + 1);

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { free(s); return NULL; }

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_port        = htons(8090);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
        char body[64];
        int blen = snprintf(body, sizeof(body), "{\"paid_by\":%d}", paid_by);
        char req[512];
        int rlen = snprintf(req, sizeof(req),
            "POST /orders/%s/confirm HTTP/1.1\r\n"
            "Host: localhost\r\n"
            "Content-Type: application/json\r\n"
            "Content-Length: %d\r\n"
            "Connection: close\r\n"
            "\r\n%s",
            order_id, blen, body);
        (void)write(fd, req, rlen);
    }
    close(fd);
    free(s);
    return NULL;
}

static void gateway_notify_async(const char *gateway_order_id, uint32_t paid_by) {
    if (!gateway_order_id || gateway_order_id[0] == '\0') return;
    char *arg = malloc(strlen(gateway_order_id) + 32);
    if (!arg) return;
    sprintf(arg, "%s|%u", gateway_order_id, paid_by);
    pthread_t t;
    if (pthread_create(&t, NULL, gateway_confirm_thread, arg) == 0)
        pthread_detach(t);
    else
        free(arg);
}

/* CASH_IN  body: [bank_token 32B][to_uid 4B][amount 8B][topup_id N bytes]
 * Caller must be logged in as a bank entity (uid 1-999).
 * Credits to_uid and records a CASH_IN transfer (type=2).
 * topup_id is hashed for idempotency — safe to retry.
 */
static uint64_t fnv64(const uint8_t *data, int len) {
    uint64_t h = 14695981039346656037ULL;
    for (int i = 0; i < len; i++)
        h = (h ^ data[i]) * 1099511628211ULL;
    return h;
}

static void handle_cash_in(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 44) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t bank_mid = st_lookup(st, f->body);
    if (!bank_mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    /* Only bank entities (uid 1–999) may call CASH_IN */
    if (bank_mid > 999) {
        send_ack(fd, f->seq, WIRE_ERR_NOT_MERCHANT, NULL, 0); return;
    }

    uint32_t debit  = bank_mid;
    uint32_t credit = rd32(f->body + 32);
    uint64_t amount = rd64(f->body + 36);

    /* Idempotency: hash of topup_id bytes (or fall back to seq) */
    uint64_t idem_key;
    if (f->body_len > 44) {
        idem_key = fnv64(f->body + 44, f->body_len - 44);
    } else {
        idem_key = (uint64_t)f->seq;
    }

    int claim = db_idempotency_claim(db, (uint64_t)debit, idem_key, 0);
    if (claim == 0) { send_ack(fd, f->seq, WIRE_OK, NULL, 0); return; }
    if (claim <  0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    int rc = db_transfer(db, debit, credit, amount, 2, NULL, NULL);
    if (rc == -1) { send_ack(fd, f->seq, WIRE_ERR_LOW_BALANCE, NULL, 0); return; }
    if (rc == -2) { send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND,   NULL, 0); return; }
    if (rc != 0)  { send_ack(fd, f->seq, WIRE_ERR_INTERNAL,    NULL, 0); return; }

    /* Push EVT_TRANSFER_IN to recipient; APNs fallback if offline */
    int64_t new_bal = db_account_balance(db, credit);
    if (new_bal >= 0) {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,      debit);
        wr64(body + 4,  amount);
        wr64(body + 12, (uint64_t)new_bal);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_IN, 0, body, 20,
                                     evt, sizeof(evt));
        if (n > 0) {
            char ab[80];
            snprintf(ab, sizeof(ab), "+%llu \xe2\x82\xab v\xe1\xbb\xado t\xc3\xa0i kho\xe1\xba\xa3n",
                     (unsigned long long)amount);
            push_or_apns(db, credit, evt, n, "N\xe1\xba\xa1p ti\xe1\xbb\x81n", ab);
        }
    }

    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* REGISTER_PUSH_TOKEN  body: [token 32B][device_token N bytes] */
static void handle_register_push_token(DB *db, SessionTable *st,
                                        int fd, const WireFrame *f) {
    if (f->body_len < 33) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    /* device_token is the remaining bytes after the session token (hex string) */
    char tok[128] = "";
    uint16_t tlen = f->body_len - 32;
    if (tlen >= sizeof(tok)) tlen = sizeof(tok) - 1;
    memcpy(tok, f->body + 32, tlen);
    tok[tlen] = '\0';

    if (db_push_token_upsert(db, mid, tok) != 0) {
        send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return;
    }
    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* ─── Dispatch ───────────────────────────────────────────────────────────── */

void handle_frame(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    /* Maintenance mode — reject everything except housekeeping commands. */
    if (g_maintenance) {
        switch (f->type) {
            case WIRE_PING:
            case WIRE_LOGIN:
            case WIRE_LOGOUT:
            case WIRE_RENEW_SESSION:
                break;
            default:
                send_ack(fd, f->seq, WIRE_ERR_MAINTENANCE, NULL, 0);
                return;
        }
    }

    /* Money-movement frames require mid=1 (clearing account) to be online.
     * This is the "branch must be open" invariant: no transactions without
     * a teller (mid=1 session) present. */
    switch (f->type) {
        case WIRE_TRANSFER:
        case WIRE_CREATE_INTENT:
        case WIRE_PAY_INTENT:
        case WIRE_CONFIRM_INTENT:
        case WIRE_CASH_IN:
        case WIRE_TOTP_CHARGE:
        case WIRE_CASH_OUT:
            if (!st_is_online(st, 1)) {
                send_ack(fd, f->seq, WIRE_ERR_SYSTEM_OFFLINE, NULL, 0);
                return;
            }
            break;
        default:
            break;
    }
    switch (f->type) {
        case WIRE_PING:             handle_ping(fd, f);                           break;
        case WIRE_LOGIN:            handle_login(db, st, fd, f);                  break;
        case WIRE_LOGOUT:           handle_logout(st, fd, f);                     break;
        case WIRE_RENEW_SESSION:    handle_renew_session(st, fd, f);              break;
        case WIRE_CREATE_ACCOUNT:   handle_create_account(db, fd, f);             break;
        case WIRE_TRANSFER:         handle_transfer(db, st, fd, f);               break;
        case WIRE_GET_BALANCE:      handle_get_balance(db, st, fd, f);            break;
        case WIRE_ADD_GUARDIAN:     handle_add_guardian(db, st, fd, f);           break;
        case WIRE_RECOVERY_REQ:     handle_recovery_req(db, fd, f);               break;
        case WIRE_RECOVERY_APPROVE: handle_recovery_approve(db, st, fd, f);       break;
        case WIRE_GET_HISTORY:      handle_get_history(db, st, fd, f);            break;
        case WIRE_REGISTER_MERCHANT: handle_register_merchant(db, st, fd, f);     break;
        case WIRE_ENROLL_TOTP:      handle_enroll_totp(db, st, fd, f);            break;
        case WIRE_CREATE_INTENT:    handle_create_intent(db, st, fd, f);          break;
        case WIRE_PAY_INTENT:       handle_pay_intent(db, st, fd, f);             break;
        case WIRE_CASH_IN:            handle_cash_in(db, st, fd, f);              break;
        case WIRE_TOTP_CHARGE:        handle_totp_charge(db, st, fd, f);          break;
        case WIRE_CASH_OUT:           handle_cash_out(db, st, fd, f);             break;
        case WIRE_GET_MERCHANT_INFO:  handle_get_merchant_info(db, st, fd, f);    break;
        case WIRE_LIST_INTENTS:           handle_list_intents(db, st, fd, f);              break;
        case WIRE_GET_MERCHANT_HISTORY:   handle_get_merchant_history(db, st, fd, f);     break;
        case WIRE_CONFIRM_INTENT:         handle_confirm_intent(db, st, fd, f);           break;
        case WIRE_REGISTER_PUSH_TOKEN:    handle_register_push_token(db, st, fd, f);      break;
        default:
            send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0);
    }
}
