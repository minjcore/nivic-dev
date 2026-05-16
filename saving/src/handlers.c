#include "handlers.h"
#include "registry.h"
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <limits.h>
#include <pthread.h>

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

static void st_destroy(SessionTable *st, const uint8_t token[32]) {
    pthread_mutex_lock(&st->mu);
    for (int i = 0; i < SESSION_MAX; i++) {
        if (memcmp(st->entries[i].token, token, 32) == 0) {
            memset(&st->entries[i], 0, sizeof(Session));
            break;
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

/* TRANSFER  body: [token 32B][to_id 4B][amount 8B] */
static void handle_transfer(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 44) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid    = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }
    uint32_t to_id  = rd32(f->body + 32);
    uint64_t amount = rd64(f->body + 36);

    /* Idempotency key: (mid, seq) */
    int claim = db_idempotency_claim(db, (uint64_t)mid, (uint64_t)f->seq, 0);
    if (claim == 0) { send_ack(fd, f->seq, WIRE_OK, NULL, 0); return; }
    if (claim <  0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    int rc = db_transfer(db, mid, to_id, amount);
    if (rc == -1) { send_ack(fd, f->seq, WIRE_ERR_LOW_BALANCE, NULL, 0); return; }
    if (rc == -2) { send_ack(fd, f->seq, WIRE_ERR_NOT_FOUND,   NULL, 0); return; }
    if (rc != 0)  { send_ack(fd, f->seq, WIRE_ERR_INTERNAL,    NULL, 0); return; }

    db_record_transfer(db, mid, to_id, amount);

    /* Push EVT_TRANSFER_IN to recipient if online */
    int64_t new_bal = db_account_balance(db, to_id);
    if (new_bal >= 0) {
        uint8_t body[20], evt[WIRE_MAX_FRAME];
        wr32(body,     mid);
        wr64(body + 4, amount);
        wr64(body + 12, (uint64_t)new_bal);
        size_t n = wire_frame_encode(WIRE_EVT_TRANSFER_IN, 0, body, 20,
                                     evt, sizeof(evt));
        if (n > 0) registry_push(to_id, evt, n);
    }

    send_ack(fd, f->seq, WIRE_OK, NULL, 0);
}

/* GET_BALANCE  body: [token 32B] */
static void handle_get_balance(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 32) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    int64_t bal = db_account_balance(db, mid);
    if (bal < 0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    uint8_t extra[8];
    wr64(extra, (uint64_t)bal);
    send_ack(fd, f->seq, WIRE_OK, extra, 8);
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
 * ACK extra:  [count 1B][direction 1B | counterpart 4B | amount 8B] × count  */
static void handle_get_history(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    if (f->body_len < 32) {
        send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0); return;
    }
    uint32_t mid = st_lookup(st, f->body);
    if (!mid) { send_ack(fd, f->seq, WIRE_ERR_BAD_TOKEN, NULL, 0); return; }

    TxEntry entries[20];
    int n = db_history(db, mid, entries, 20);
    if (n < 0) { send_ack(fd, f->seq, WIRE_ERR_INTERNAL, NULL, 0); return; }

    /* Pack: [count 1B][per entry: direction 1B + counterpart 4B + amount 8B] */
    uint8_t extra[1 + 20 * 13];
    extra[0] = (uint8_t)n;
    for (int i = 0; i < n; i++) {
        uint8_t *e = extra + 1 + i * 13;
        e[0] = (uint8_t)entries[i].direction;
        wr32(e + 1, entries[i].counterpart);
        wr64(e + 5, entries[i].amount);
    }
    send_ack(fd, f->seq, WIRE_OK, extra, (uint16_t)(1 + n * 13));
}

/* ─── Dispatch ───────────────────────────────────────────────────────────── */

void handle_frame(DB *db, SessionTable *st, int fd, const WireFrame *f) {
    switch (f->type) {
        case WIRE_PING:             handle_ping(fd, f);                           break;
        case WIRE_LOGIN:            handle_login(db, st, fd, f);                  break;
        case WIRE_LOGOUT:           handle_logout(st, fd, f);                     break;
        case WIRE_CREATE_ACCOUNT:   handle_create_account(db, fd, f);             break;
        case WIRE_TRANSFER:         handle_transfer(db, st, fd, f);               break;
        case WIRE_GET_BALANCE:      handle_get_balance(db, st, fd, f);            break;
        case WIRE_ADD_GUARDIAN:     handle_add_guardian(db, st, fd, f);           break;
        case WIRE_RECOVERY_REQ:     handle_recovery_req(db, fd, f);               break;
        case WIRE_RECOVERY_APPROVE: handle_recovery_approve(db, st, fd, f);       break;
        case WIRE_GET_HISTORY:      handle_get_history(db, st, fd, f);            break;
        default:
            send_ack(fd, f->seq, WIRE_ERR_BAD_FRAME, NULL, 0);
    }
}
