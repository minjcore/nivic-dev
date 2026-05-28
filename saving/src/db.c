#include "db.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <arpa/inet.h>   /* htonl / htons for binary params */

/* ══════════════════════════════════════════════════════════════════════════
 *  Helpers
 * ══════════════════════════════════════════════════════════════════════════ */

/* Lock / unlock convenience */
#define DB_LOCK(db)   pthread_mutex_lock(&(db)->mu)
#define DB_UNLOCK(db) pthread_mutex_unlock(&(db)->mu)

/* PostgreSQL wire protocol uses network byte order for binary int8 */
static uint64_t pg_int8(uint64_t v) {
    uint64_t hi = htonl((uint32_t)(v >> 32));
    uint64_t lo = htonl((uint32_t)(v & 0xFFFFFFFF));
    return (lo << 32) | hi;
}

/* Read big-endian uint64 from binary result value */
static inline uint64_t from_be64(const uint8_t *p) {
    return ((uint64_t)p[0]<<56)|((uint64_t)p[1]<<48)|((uint64_t)p[2]<<40)|
           ((uint64_t)p[3]<<32)|((uint64_t)p[4]<<24)|((uint64_t)p[5]<<16)|
           ((uint64_t)p[6]<<8 )| (uint64_t)p[7];
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Schema
 * ══════════════════════════════════════════════════════════════════════════ */

static const char SCHEMA[] =
    /* Core accounts */
    "CREATE TABLE IF NOT EXISTS accounts ("
    "  id            BIGINT PRIMARY KEY,"
    "  password_hash BYTEA  NOT NULL,"
    "  balance     BIGINT      NOT NULL DEFAULT 0,"
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");"

    /* Guardian links */
    "CREATE TABLE IF NOT EXISTS guardians ("
    "  account_id  BIGINT      NOT NULL,"
    "  guardian_id BIGINT      NOT NULL,"
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
    "  PRIMARY KEY (account_id, guardian_id)"
    ");"
    "ALTER TABLE guardians ADD COLUMN IF NOT EXISTS create_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"

    /* Open device-switch requests */
    "CREATE TABLE IF NOT EXISTS recovery_requests ("
    "  account_id  BIGINT      PRIMARY KEY,"
    "  approvals   TEXT        NOT NULL DEFAULT '',"  /* comma-separated guardian IDs */
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");"
    "ALTER TABLE recovery_requests ADD COLUMN IF NOT EXISTS create_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"

    /* Idempotency gate — ported from Java JdbcIdempotencyGate */
    "CREATE TABLE IF NOT EXISTS wallet_idempotency ("
    "  mid         BIGINT      NOT NULL,"
    "  request_id  BIGINT      NOT NULL,"
    "  order_id    BIGINT,"
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
    "  PRIMARY KEY (mid, request_id)"
    ");"
    "ALTER TABLE wallet_idempotency ADD COLUMN IF NOT EXISTS create_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"

    /* Append-only ledger — ported from Java JdbcWalletLedger */
    "CREATE TABLE IF NOT EXISTS wallet_ledger ("
    "  mid          BIGINT NOT NULL,"
    "  request_id   BIGINT NOT NULL,"
    "  order_id     BIGINT NOT NULL,"
    "  command      BIGINT NOT NULL,"
    "  amount_minor BIGINT NOT NULL,"
    "  extra_data   BYTEA  NOT NULL,"
    "  create_time   TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
    "  PRIMARY KEY (mid, request_id)"
    ");"

    /* Queryable transfer log (both directions per row) */
    "CREATE TABLE IF NOT EXISTS transfers ("
    "  id         BIGSERIAL PRIMARY KEY,"
    "  from_id    BIGINT NOT NULL,"
    "  to_id      BIGINT NOT NULL,"
    "  amount     BIGINT NOT NULL,"
    "  type        SMALLINT    NOT NULL DEFAULT 0,"   /* 0=transfer, 1=payment */
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");"
    "ALTER TABLE transfers ADD COLUMN IF NOT EXISTS type        SMALLINT    NOT NULL DEFAULT 0;"
    "ALTER TABLE transfers ADD COLUMN IF NOT EXISTS create_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"
    "CREATE INDEX IF NOT EXISTS transfers_from_idx ON transfers(from_id, create_time DESC);"
    "CREATE INDEX IF NOT EXISTS transfers_to_idx   ON transfers(to_id,   create_time DESC);"

    /* TOTP secrets enrolled by merchants for customers */
    "CREATE TABLE IF NOT EXISTS totp_enrollments ("
    "  merchant_id BIGINT      NOT NULL,"
    "  customer_id BIGINT      NOT NULL,"
    "  secret      BYTEA       NOT NULL,"
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
    "  update_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
    "  PRIMARY KEY (merchant_id, customer_id)"
    ");"
    "ALTER TABLE totp_enrollments ADD COLUMN IF NOT EXISTS create_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"
    "ALTER TABLE totp_enrollments ADD COLUMN IF NOT EXISTS update_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"

    /* Payment intents created by merchants */
    "CREATE TABLE IF NOT EXISTS payment_intents ("
    "  mid              BIGINT   NOT NULL,"
    "  request_id       BIGINT   NOT NULL,"
    "  order_id         BIGINT   NOT NULL,"
    "  amount           BIGINT   NOT NULL,"
    "  status           SMALLINT NOT NULL DEFAULT 0,"
    "  gateway_order_id TEXT        NOT NULL DEFAULT '',"
    "  create_time      TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
    "  update_time      TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
    "  PRIMARY KEY (mid, request_id)"
    ");"
    "ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS gateway_order_id TEXT        NOT NULL DEFAULT '';"
    "ALTER TABLE payment_intents ADD COLUMN IF NOT EXISTS update_time      TIMESTAMPTZ NOT NULL DEFAULT NOW();"

    /* Merchant registry — mid mirrors the Wire account ID */
    "CREATE TABLE IF NOT EXISTS merchants ("
    "  mid         BIGINT      PRIMARY KEY,"
    "  name        TEXT        NOT NULL,"
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
    "  update_time TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");"
    "ALTER TABLE merchants ADD COLUMN IF NOT EXISTS create_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"
    "ALTER TABLE merchants ADD COLUMN IF NOT EXISTS update_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"
    "ALTER TABLE merchants ADD COLUMN IF NOT EXISTS pubkey      BYTEA;"

    /* Balance cache — separated from accounts so profile updates don't lock balance rows */
    "CREATE TABLE IF NOT EXISTS balances ("
    "  account_id  BIGINT      PRIMARY KEY REFERENCES accounts(id),"
    "  balance     BIGINT      NOT NULL DEFAULT 0,"
    "  version     BIGINT      NOT NULL DEFAULT 0,"
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");"
    "ALTER TABLE balances ADD COLUMN IF NOT EXISTS create_time TIMESTAMPTZ NOT NULL DEFAULT NOW();"

    /* Web admin panel user accounts */
    "CREATE TABLE IF NOT EXISTS admins ("
    "  username      TEXT        PRIMARY KEY,"
    "  password_hash BYTEA       NOT NULL,"
    "  create_time   TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");"

    /* Admin action audit trail */
    "CREATE TABLE IF NOT EXISTS admin_audit ("
    "  id          BIGSERIAL   PRIMARY KEY,"
    "  username    TEXT        NOT NULL,"
    "  action      TEXT        NOT NULL,"
    "  target_uid  BIGINT      NOT NULL,"
    "  amount      BIGINT      NOT NULL,"
    "  ref         TEXT        NOT NULL DEFAULT '',"
    "  create_time TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");"
    "CREATE TABLE IF NOT EXISTS push_tokens ("
    "  mid          BIGINT PRIMARY KEY,"
    "  device_token TEXT   NOT NULL,"
    "  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");"

    "CREATE TABLE IF NOT EXISTS qr_settled_refs ("
    "  ref        TEXT        PRIMARY KEY,"
    "  mid        BIGINT      NOT NULL,"
    "  settled_at TIMESTAMPTZ NOT NULL DEFAULT NOW()"
    ");";

/* ══════════════════════════════════════════════════════════════════════════
 *  Lifecycle
 * ══════════════════════════════════════════════════════════════════════════ */

int db_open(DB *db, const char *conninfo) {
    db->conn = PQconnectdb(conninfo);
    if (PQstatus(db->conn) != CONNECTION_OK) {
        fprintf(stderr, "[db] connect failed: %s\n", PQerrorMessage(db->conn));
        PQfinish(db->conn);
        return -1;
    }
    pthread_mutex_init(&db->mu, NULL);

    PGresult *r = PQexec(db->conn, SCHEMA);
    if (PQresultStatus(r) != PGRES_COMMAND_OK) {
        fprintf(stderr, "[db] schema error: %s\n", PQerrorMessage(db->conn));
        PQclear(r);
        return -1;
    }
    PQclear(r);

    /* Migration: seed initial balances into transfers so balance is computed
     * purely from SUM(transfers).  Runs once per account that has a non-zero
     * balance column and no existing seed row (type=99).  After this the
     * Seed balances table from SUM of all transfers for accounts not yet present. */
    static const char SEED_SQL[] =
        "INSERT INTO balances (account_id, balance, version)"
        " SELECT a.id,"
        "   CAST(COALESCE(SUM(CASE WHEN t.to_id = a.id THEN t.amount ELSE -t.amount END), 0) AS BIGINT),"
        "   COUNT(t.id)"
        " FROM accounts a"
        " LEFT JOIN transfers t ON t.from_id = a.id OR t.to_id = a.id"
        " GROUP BY a.id"
        " ON CONFLICT (account_id) DO NOTHING";
    PGresult *rs = PQexec(db->conn, SEED_SQL);
    if (PQresultStatus(rs) != PGRES_COMMAND_OK)
        fprintf(stderr, "[db] seed balances: %s\n", PQerrorMessage(db->conn));
    PQclear(rs);

    return 0;
}

void db_close(DB *db) {
    PQfinish(db->conn);
    pthread_mutex_destroy(&db->mu);
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Accounts
 * ══════════════════════════════════════════════════════════════════════════ */

int db_account_create(DB *db, uint32_t id, const uint8_t *password_hash) {
    static const char SQL[] =
        "WITH ins AS ("
        "  INSERT INTO accounts (id, password_hash) VALUES ($1, $2) RETURNING id"
        ")"
        "INSERT INTO balances (account_id) SELECT id FROM ins";

    uint64_t id_be = pg_int8((uint64_t)id);

    const char *vals[2]  = { (char *)&id_be, (char *)password_hash };
    int         lens[2]  = { 8, 32 };
    int         fmts[2]  = { 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int ok = (PQresultStatus(r) == PGRES_COMMAND_OK);
    if (!ok) fprintf(stderr, "[db] create account: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return ok ? 0 : -1;
}

int db_account_exists(DB *db, uint32_t id) {
    static const char SQL[] =
        "SELECT 1 FROM accounts WHERE id = $1 LIMIT 1";

    uint64_t id_be = pg_int8((uint64_t)id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int found = (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) ? 1 : 0;
    PQclear(r);
    return found;
}

int db_account_get_hash(DB *db, uint32_t id, uint8_t *hash) {
    static const char SQL[] =
        "SELECT password_hash FROM accounts WHERE id = $1";

    uint64_t id_be = pg_int8((uint64_t)id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 1);  /* binary result */
    DB_UNLOCK(db);

    int rc = -1;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        int len = PQgetlength(r, 0, 0);
        if (len == 32) {
            memcpy(hash, PQgetvalue(r, 0, 0), 32);
            rc = 0;
        }
    }
    PQclear(r);
    return rc;
}

int64_t db_account_balance(DB *db, uint32_t id) {
    static const char SQL[] =
        "SELECT balance FROM balances WHERE account_id = $1";

    uint64_t id_be = pg_int8((uint64_t)id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    int64_t bal = -1;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0)
        bal = (int64_t)from_be64((const uint8_t *)PQgetvalue(r, 0, 0));
    PQclear(r);
    return bal;
}

int db_account_balance_detail(DB *db, uint32_t id, BalanceDetail *out) {
    static const char SQL[] =
        "SELECT"
        "  b.balance,"
        "  CAST(COALESCE((SELECT SUM(amount) FROM payment_intents"
        "                 WHERE mid = $1 AND status = 0), 0) AS BIGINT),"
        "  b.version"
        " FROM balances b WHERE b.account_id = $1";

    uint64_t id_be = pg_int8((uint64_t)id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    int rc = -1;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        out->balance = (int64_t)from_be64((const uint8_t *)PQgetvalue(r, 0, 0));
        out->pending = (int64_t)from_be64((const uint8_t *)PQgetvalue(r, 0, 1));
        out->version = (int64_t)from_be64((const uint8_t *)PQgetvalue(r, 0, 2));
        out->available_balance = out->balance - out->pending;
        if (out->available_balance < 0) out->available_balance = 0;
        rc = 0;
    }
    PQclear(r);
    return rc;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Transfer — atomic CTE (single round-trip, no deadlock)
 *
 *  Logic mirrors Java MoneyTransfers but in SQL:
 *    1. Debit sender (check balance ≥ amount)
 *    2. Credit receiver
 *    Both fail together if sender has insufficient funds or receiver missing.
 * ══════════════════════════════════════════════════════════════════════════ */

int db_transfer(DB *db, uint32_t from_id, uint32_t to_id, uint64_t amount, int type,
                int64_t *after_out, int64_t *txn_id_out) {
    /* Atomic transfer using CTEs — no UPDATE to accounts.balance.
     * Balance is the running SUM of the transfers log.
     * DB_LOCK serialises all calls so the SUM check is race-free.
     *
     * Returns: 0  ok
     *         -1  sender low balance or not found
     *         -2  receiver not found
     *         -3  internal error
     */
    /* Atomically debit sender, credit receiver, append audit row.
     * upd_to depends on upd_from so receiver is never credited on sender failure. */
    static const char SQL[] =
        "WITH"
        "  upd_from AS ("
        "    UPDATE balances SET balance = balance - $1, version = version + 1"
        "    WHERE account_id = $2 AND balance >= $1"
        "    RETURNING balance AS after_bal"
        "  ),"
        "  upd_to AS ("
        "    UPDATE balances SET balance = balance + $1, version = version + 1"
        "    WHERE account_id = $3"
        "      AND EXISTS (SELECT 1 FROM upd_from)"
        "    RETURNING 1"
        "  ),"
        "  ins AS ("
        "    INSERT INTO transfers (from_id, to_id, amount, type)"
        "    SELECT $2, $3, $1, $4"
        "    WHERE EXISTS (SELECT 1 FROM upd_from) AND EXISTS (SELECT 1 FROM upd_to)"
        "    RETURNING id"
        "  )"
        "SELECT"
        "  (SELECT after_bal FROM upd_from)                       AS after_bal,"
        "  EXISTS (SELECT 1 FROM balances WHERE account_id = $3) AS recv_exists,"
        "  (SELECT id FROM ins)                                   AS txn_id";

    uint64_t amount_be  = pg_int8(amount);
    uint64_t from_id_be = pg_int8((uint64_t)from_id);
    uint64_t to_id_be   = pg_int8((uint64_t)to_id);
    uint16_t type_be    = htons((uint16_t)type);

    const char *vals[4] = { (char *)&amount_be, (char *)&from_id_be,
                            (char *)&to_id_be,  (char *)&type_be };
    int         lens[4] = { 8, 8, 8, 2 };
    int         fmts[4] = { 1, 1, 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 4, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    /* col0 = after_bal (NULL if sender had insufficient balance or no row)
     * col1 = recv_exists (bool)
     * col2 = txn_id — the new transfers.id (NULL if insert did not happen) */
    int rc = -3;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        int inserted    = !PQgetisnull(r, 0, 2);
        int recv_exists = !PQgetisnull(r, 0, 1) && PQgetvalue(r, 0, 1)[0];
        if (inserted) {
            rc = 0;
            if (after_out)
                *after_out  = (int64_t)from_be64((const uint8_t *)PQgetvalue(r, 0, 0));
            if (txn_id_out)
                *txn_id_out = (int64_t)from_be64((const uint8_t *)PQgetvalue(r, 0, 2));
        } else if (!recv_exists) rc = -2;
        else                     rc = -1;  /* low balance */
    }
    if (rc == -3) fprintf(stderr, "[db] transfer: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return rc;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Idempotency gate  (ported from Java JdbcIdempotencyGate)
 *
 *  INSERT (mid, request_id, order_id) ON CONFLICT DO NOTHING.
 *  Returns 1 (first claim), 0 (duplicate), -1 (error).
 * ══════════════════════════════════════════════════════════════════════════ */

int db_idempotency_claim(DB *db, uint64_t mid, uint64_t request_id, uint64_t order_id) {
    static const char SQL[] =
        "INSERT INTO wallet_idempotency (mid, request_id, order_id)"
        " VALUES ($1, $2, $3)"
        " ON CONFLICT (mid, request_id) DO NOTHING";

    uint64_t mid_be = pg_int8(mid);
    uint64_t rid_be = pg_int8(request_id);
    uint64_t oid_be = pg_int8(order_id);

    const char *vals[3] = { (char *)&mid_be, (char *)&rid_be, (char *)&oid_be };
    int         lens[3] = { 8, 8, 8 };
    int         fmts[3] = { 1, 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 3, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int rc = -1;
    if (PQresultStatus(r) == PGRES_COMMAND_OK) {
        char *affected = PQcmdTuples(r);
        rc = (affected && affected[0] == '1') ? 1 : 0;
    } else {
        fprintf(stderr, "[db] idempotency_claim: %s\n", PQerrorMessage(db->conn));
    }
    PQclear(r);
    return rc;
}

/* Returns 1 if ref was newly claimed (proceed), 0 if duplicate (reject). */
int db_qr_ref_claim(DB *db, const char *ref, uint32_t mid) {
    static const char SQL[] =
        "INSERT INTO qr_settled_refs (ref, mid)"
        " VALUES ($1, $2)"
        " ON CONFLICT (ref) DO NOTHING";

    uint64_t mid_be = pg_int8(mid);
    const char *vals[2] = { ref,              (char *)&mid_be };
    int         lens[2] = { (int)strlen(ref),  8              };
    int         fmts[2] = { 0,                 1              };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int rc = -1;
    if (PQresultStatus(r) == PGRES_COMMAND_OK) {
        char *affected = PQcmdTuples(r);
        rc = (affected && affected[0] == '1') ? 1 : 0;
    } else {
        fprintf(stderr, "[db] qr_ref_claim: %s\n", PQerrorMessage(db->conn));
    }
    PQclear(r);
    return rc;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Ledger  (ported from Java JdbcWalletLedger)
 *
 *  Append-only: every accepted command leaves an immutable audit row.
 * ══════════════════════════════════════════════════════════════════════════ */

int db_ledger_append(DB *db,
                     uint64_t mid, uint64_t request_id, uint64_t order_id,
                     uint64_t command, uint64_t amount_minor,
                     const uint8_t *extra_data, uint16_t extra_len) {
    static const char SQL[] =
        "INSERT INTO wallet_ledger"
        "  (mid, request_id, order_id, command, amount_minor, extra_data)"
        "  VALUES ($1, $2, $3, $4, $5, $6)"
        "  ON CONFLICT DO NOTHING";   /* idempotency already checked, this is just safety */

    uint64_t mid_be = pg_int8(mid);
    uint64_t rid_be = pg_int8(request_id);
    uint64_t oid_be = pg_int8(order_id);
    uint64_t cmd_be = pg_int8(command);
    uint64_t amt_be = pg_int8(amount_minor);

    const char *vals[6] = {
        (char *)&mid_be, (char *)&rid_be, (char *)&oid_be,
        (char *)&cmd_be, (char *)&amt_be,
        (char *)extra_data
    };
    int lens[6] = { 8, 8, 8, 8, 8, (int)extra_len };
    int fmts[6] = { 1, 1, 1, 1, 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 6, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int ok = (PQresultStatus(r) == PGRES_COMMAND_OK);
    if (!ok) fprintf(stderr, "[db] ledger_append: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return ok ? 0 : -1;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Guardians
 * ══════════════════════════════════════════════════════════════════════════ */

int db_guardian_count(DB *db, uint32_t account_id) {
    static const char SQL[] =
        "SELECT COUNT(*) FROM guardians WHERE account_id = $1";

    uint64_t id_be = pg_int8((uint64_t)account_id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    int cnt = -1;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        const char *raw = PQgetvalue(r, 0, 0);
        uint64_t v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (uint8_t)raw[i];
        cnt = (int)v;
    }
    PQclear(r);
    return cnt;
}

int db_guardian_add(DB *db, uint32_t account_id, uint32_t guardian_id) {
    if (db_guardian_count(db, account_id) >= 3) return -1;

    static const char SQL[] =
        "INSERT INTO guardians (account_id, guardian_id) VALUES ($1, $2)"
        " ON CONFLICT DO NOTHING";

    uint64_t aid_be = pg_int8((uint64_t)account_id);
    uint64_t gid_be = pg_int8((uint64_t)guardian_id);
    const char *vals[2] = { (char *)&aid_be, (char *)&gid_be };
    int         lens[2] = { 8, 8 };
    int         fmts[2] = { 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int ok = (PQresultStatus(r) == PGRES_COMMAND_OK);
    PQclear(r);
    return ok ? 0 : -2;
}

int db_guardian_list(DB *db, uint32_t account_id, uint32_t ids[3]) {
    static const char SQL[] =
        "SELECT guardian_id FROM guardians WHERE account_id = $1 LIMIT 3";

    uint64_t id_be = pg_int8((uint64_t)account_id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    int n = 0;
    if (PQresultStatus(r) == PGRES_TUPLES_OK) {
        int rows = PQntuples(r);
        for (int i = 0; i < rows && i < 3; i++) {
            const char *raw = PQgetvalue(r, i, 0);
            uint64_t v = 0;
            for (int j = 0; j < 8; j++) v = (v << 8) | (uint8_t)raw[j];
            ids[n++] = (uint32_t)v;
        }
    }
    PQclear(r);
    return n;
}

static int is_guardian(DB *db, uint32_t account_id, uint32_t guardian_id) {
    uint32_t ids[3] = {0};
    int n = db_guardian_list(db, account_id, ids);
    for (int i = 0; i < n; i++)
        if (ids[i] == guardian_id) return 1;
    return 0;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Social Recovery
 * ══════════════════════════════════════════════════════════════════════════ */

int db_recovery_open(DB *db, uint32_t account_id) {
    static const char SQL[] =
        "INSERT INTO recovery_requests (account_id, approvals) VALUES ($1, '')"
        " ON CONFLICT (account_id) DO UPDATE SET approvals = ''";

    uint64_t id_be = pg_int8((uint64_t)account_id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int ok = (PQresultStatus(r) == PGRES_COMMAND_OK);
    PQclear(r);
    return ok ? 0 : -1;
}

int db_recovery_approve(DB *db, uint32_t account_id, uint32_t guardian_id) {
    if (!is_guardian(db, account_id, guardian_id)) return -1;

    /* Read existing approvals */
    static const char SEL[] =
        "SELECT approvals FROM recovery_requests WHERE account_id = $1";

    uint64_t id_be = pg_int8((uint64_t)account_id);
    const char *vals1[1] = { (char *)&id_be };
    int         lens1[1] = { 8 };
    int         fmts1[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SEL, 1, NULL, vals1, lens1, fmts1, 0);

    char approvals[256] = "";
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        const char *txt = PQgetvalue(r, 0, 0);
        if (txt) snprintf(approvals, sizeof(approvals), "%s", txt);
    }
    PQclear(r);

    /* Check if already approved */
    char needle[20];
    snprintf(needle, sizeof(needle), "%u", guardian_id);
    if (strstr(approvals, needle)) {
        DB_UNLOCK(db);
        int cnt = (approvals[0] != '\0') ? 1 : 0;
        for (char *p = approvals; *p; p++) if (*p == ',') cnt++;
        return cnt;
    }

    /* Append guardian_id */
    char updated[256];
    if (approvals[0])
        snprintf(updated, sizeof(updated), "%s,%u", approvals, guardian_id);
    else
        snprintf(updated, sizeof(updated), "%u", guardian_id);

    static const char UPD[] =
        "UPDATE recovery_requests SET approvals = $1 WHERE account_id = $2";
    const char *vals2[2] = { updated, (char *)&id_be };
    int         lens2[2] = { (int)strlen(updated), 8 };
    int         fmts2[2] = { 0, 1 };  /* text, binary */

    PGresult *r2 = PQexecParams(db->conn, UPD, 2, NULL, vals2, lens2, fmts2, 0);
    DB_UNLOCK(db);
    PQclear(r2);

    int cnt = 1;
    for (char *p = updated; *p; p++) if (*p == ',') cnt++;
    return cnt;
}

int db_recovery_is_complete(DB *db, uint32_t account_id) {
    static const char SQL[] =
        "SELECT approvals FROM recovery_requests WHERE account_id = $1";

    uint64_t id_be = pg_int8((uint64_t)account_id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int complete = 0;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        const char *txt = PQgetvalue(r, 0, 0);
        if (txt && txt[0]) {
            int cnt = 1;
            for (const char *p = txt; *p; p++) if (*p == ',') cnt++;
            if (cnt >= 2) complete = 1;
        }
    }
    PQclear(r);
    return complete;
}

void db_recovery_close(DB *db, uint32_t account_id) {
    static const char SQL[] =
        "DELETE FROM recovery_requests WHERE account_id = $1";

    uint64_t id_be = pg_int8((uint64_t)account_id);
    const char *vals[1] = { (char *)&id_be };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);
    PQclear(r);
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Transfer history
 * ══════════════════════════════════════════════════════════════════════════ */

int db_record_transfer(DB *db, uint32_t from_id, uint32_t to_id, uint64_t amount, int type) {
    static const char SQL[] =
        "INSERT INTO transfers (from_id, to_id, amount, type) VALUES ($1, $2, $3, $4)";

    uint64_t f  = pg_int8((uint64_t)from_id);
    uint64_t t  = pg_int8((uint64_t)to_id);
    uint64_t a  = pg_int8(amount);
    uint16_t tp = htons((uint16_t)type);

    const char *vals[4] = { (char *)&f, (char *)&t, (char *)&a, (char *)&tp };
    int         lens[4] = { 8, 8, 8, 2 };
    int         fmts[4] = { 1, 1, 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 4, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int ok = (PQresultStatus(r) == PGRES_COMMAND_OK);
    if (!ok) fprintf(stderr, "[db] record_transfer: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return ok ? 0 : -1;
}

int db_history(DB *db, uint32_t account_id, TxEntry *out, int max_count, int64_t before_id) {
    /* Window SUM over all rows (incl. seed type=99) gives correct running balance.
     * Outer WHERE type != 99 hides seed rows; id < before_id implements cursor. */
    static const char SQL[] =
        "SELECT id, from_id, to_id, amount, type, after_balance FROM ("
        "  SELECT id, from_id, to_id, amount, type, create_time,"
        "    CAST(SUM(CASE WHEN to_id = $1 THEN amount ELSE -amount END)"
        "         OVER (ORDER BY id ASC ROWS UNBOUNDED PRECEDING) AS BIGINT) AS after_balance"
        "  FROM transfers"
        "  WHERE from_id = $1 OR to_id = $1"
        ") t WHERE type != 99 AND ($3::BIGINT = 0 OR id < $3::BIGINT)"
        " ORDER BY create_time DESC LIMIT $2";

    uint64_t acct_be   = pg_int8((uint64_t)account_id);
    uint64_t cursor_be = pg_int8((uint64_t)before_id);
    char     limit_str[8];
    snprintf(limit_str, sizeof(limit_str), "%d", max_count);

    const char *vals[3] = { (char *)&acct_be, limit_str, (char *)&cursor_be };
    int         lens[3] = { 8, 0, 8 };
    int         fmts[3] = { 1, 0, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 3, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK) {
        fprintf(stderr, "[db] history: %s\n", PQerrorMessage(db->conn));
        PQclear(r);
        return -1;
    }

    int n = PQntuples(r);
    if (n > max_count) n = max_count;

    for (int i = 0; i < n; i++) {
        int64_t  txn_id   = (int64_t)from_be64((uint8_t *)PQgetvalue(r, i, 0));
        uint64_t from     = from_be64((uint8_t *)PQgetvalue(r, i, 1));
        uint64_t to       = from_be64((uint8_t *)PQgetvalue(r, i, 2));
        uint64_t amt      = from_be64((uint8_t *)PQgetvalue(r, i, 3));
        uint8_t *tp       = (uint8_t *)PQgetvalue(r, i, 4);
        int      type     = (tp[0] << 8) | tp[1];
        int64_t  after_bal = (int64_t)from_be64((uint8_t *)PQgetvalue(r, i, 5));

        int sent = (from == (uint64_t)account_id);
        int kind;
        switch (type) {
            case 1:  kind = sent ? 2 : 3; break;
            case 2:  kind = 4;            break;
            case 3:  kind = 5;            break;
            default: kind = sent ? 0 : 1; break;
        }
        out[i].txn_id        = txn_id;
        out[i].direction     = kind;
        out[i].counterpart   = (uint32_t)(sent ? to : from);
        out[i].amount        = amt;
        out[i].after_balance = after_bal;
    }

    PQclear(r);
    return n;
}

int db_merchant_history(DB *db, uint32_t mid, MerchantTxEntry *out, int max_count, int64_t before_id) {
    /* Window over all merchant rows gives correct running balance.
     * Outer WHERE keeps only C2M payments received; id < before_id is cursor. */
    static const char SQL[] =
        "SELECT id, from_id, amount, after_balance FROM ("
        "  SELECT id, from_id, to_id, amount, type, create_time,"
        "    CAST(SUM(CASE WHEN to_id = $1 THEN amount ELSE -amount END)"
        "         OVER (ORDER BY id ASC ROWS UNBOUNDED PRECEDING) AS BIGINT) AS after_balance"
        "  FROM transfers"
        "  WHERE from_id = $1 OR to_id = $1"
        ") t WHERE type = 1 AND to_id = $1 AND ($3::BIGINT = 0 OR id < $3::BIGINT)"
        " ORDER BY create_time DESC LIMIT $2";

    uint64_t mid_be    = pg_int8((uint64_t)mid);
    uint64_t cursor_be = pg_int8((uint64_t)before_id);
    char     limit_str[8];
    snprintf(limit_str, sizeof(limit_str), "%d", max_count);

    const char *vals[3] = { (char *)&mid_be, limit_str, (char *)&cursor_be };
    int         lens[3] = { 8, 0, 8 };
    int         fmts[3] = { 1, 0, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 3, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK) {
        fprintf(stderr, "[db] merchant_history: %s\n", PQerrorMessage(db->conn));
        PQclear(r); return -1;
    }

    int n = PQntuples(r);
    if (n > max_count) n = max_count;

    for (int i = 0; i < n; i++) {
        out[i].txn_id        = (int64_t) from_be64((uint8_t *)PQgetvalue(r, i, 0));
        out[i].customer_id   = (uint32_t)from_be64((uint8_t *)PQgetvalue(r, i, 1));
        out[i].amount        =           from_be64((uint8_t *)PQgetvalue(r, i, 2));
        out[i].after_balance = (int64_t) from_be64((uint8_t *)PQgetvalue(r, i, 3));
    }

    PQclear(r);
    return n;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  TOTP Enrollments
 * ══════════════════════════════════════════════════════════════════════════ */

int db_totp_enroll(DB *db, uint32_t merchant_id, uint32_t customer_id,
                   const uint8_t *secret) {
    static const char SQL[] =
        "INSERT INTO totp_enrollments (merchant_id, customer_id, secret) "
        "VALUES ($1, $2, $3) "
        "ON CONFLICT (merchant_id, customer_id) DO UPDATE SET secret = EXCLUDED.secret, update_time = NOW()";

    uint64_t mid = pg_int8((uint64_t)merchant_id);
    uint64_t cid = pg_int8((uint64_t)customer_id);

    const char *vals[3] = { (char *)&mid, (char *)&cid, (char *)secret };
    int         lens[3] = { 8, 8, 20 };
    int         fmts[3] = { 1, 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 3, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    int ok = (PQresultStatus(r) == PGRES_COMMAND_OK);
    if (!ok) fprintf(stderr, "[db] totp_enroll: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return ok ? 0 : -1;
}

int db_totp_get_secret(DB *db, uint32_t merchant_id, uint32_t customer_id,
                       uint8_t *secret_out) {
    static const char SQL[] =
        "SELECT secret FROM totp_enrollments WHERE merchant_id = $1 AND customer_id = $2";

    uint64_t mid = pg_int8((uint64_t)merchant_id);
    uint64_t cid = pg_int8((uint64_t)customer_id);

    const char *vals[2] = { (char *)&mid, (char *)&cid };
    int         lens[2] = { 8, 8 };
    int         fmts[2] = { 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK || PQntuples(r) == 0) {
        PQclear(r);
        return -1;
    }

    int slen = PQgetlength(r, 0, 0);
    if (slen != 20) { PQclear(r); return -1; }
    memcpy(secret_out, PQgetvalue(r, 0, 0), 20);
    PQclear(r);
    return 0;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Payment Intents
 * ══════════════════════════════════════════════════════════════════════════ */

int db_intent_create(DB *db, uint32_t mid, uint64_t request_id,
                     uint64_t order_id, uint64_t amount,
                     const char *gateway_order_id) {
    static const char SQL[] =
        "INSERT INTO payment_intents (mid, request_id, order_id, amount, gateway_order_id) "
        "VALUES ($1, $2, $3, $4, $5) ON CONFLICT (mid, request_id) DO NOTHING";

    uint64_t m  = pg_int8((uint64_t)mid);
    uint64_t ri = pg_int8(request_id);
    uint64_t oi = pg_int8(order_id);
    uint64_t am = pg_int8(amount);
    const char *goid = gateway_order_id ? gateway_order_id : "";

    const char *vals[5] = { (char *)&m, (char *)&ri, (char *)&oi, (char *)&am, goid };
    int         lens[5] = { 8, 8, 8, 8, (int)strlen(goid) };
    int         fmts[5] = { 1, 1, 1, 1, 0 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 5, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_COMMAND_OK) {
        fprintf(stderr, "[db] intent_create: %s\n", PQerrorMessage(db->conn));
        PQclear(r);
        return -1;
    }

    char *affected = PQcmdTuples(r);
    int created = (affected && affected[0] == '1');
    PQclear(r);
    return created ? 1 : 0;
}

int db_intent_get(DB *db, uint32_t mid, uint64_t request_id, IntentInfo *out) {
    static const char SQL[] =
        "SELECT amount, status, gateway_order_id FROM payment_intents WHERE mid = $1 AND request_id = $2";

    uint64_t m  = pg_int8((uint64_t)mid);
    uint64_t ri = pg_int8(request_id);

    const char *vals[2] = { (char *)&m, (char *)&ri };
    int         lens[2] = { 8, 8 };
    int         fmts[2] = { 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK || PQntuples(r) == 0) {
        PQclear(r);
        return -1;
    }

    out->amount = from_be64((uint8_t *)PQgetvalue(r, 0, 0));
    /* SMALLINT binary result is 2 bytes big-endian */
    uint8_t *sp = (uint8_t *)PQgetvalue(r, 0, 1);
    out->status = (sp[0] << 8) | sp[1];
    /* gateway_order_id is text (format 0) */
    const char *goid = PQgetvalue(r, 0, 2);
    size_t glen = goid ? strlen(goid) : 0;
    if (glen >= sizeof(out->gateway_order_id)) glen = sizeof(out->gateway_order_id) - 1;
    if (goid) memcpy(out->gateway_order_id, goid, glen);
    out->gateway_order_id[glen] = '\0';
    PQclear(r);
    return 0;
}

int db_intent_find_by_order(DB *db, uint32_t mid, uint64_t order_id, IntentInfo *out) {
    static const char SQL[] =
        "SELECT amount, status, gateway_order_id FROM payment_intents "
        "WHERE mid = $1 AND order_id = $2 ORDER BY create_time DESC LIMIT 1";

    uint64_t m  = pg_int8((uint64_t)mid);
    uint64_t oi = pg_int8(order_id);

    const char *vals[2] = { (char *)&m, (char *)&oi };
    int         lens[2] = { 8, 8 };
    int         fmts[2] = { 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK || PQntuples(r) == 0) {
        PQclear(r);
        return -1;
    }

    out->amount = from_be64((uint8_t *)PQgetvalue(r, 0, 0));
    uint8_t *sp = (uint8_t *)PQgetvalue(r, 0, 1);
    out->status = (sp[0] << 8) | sp[1];
    const char *goid = PQgetvalue(r, 0, 2);
    size_t glen = goid ? strlen(goid) : 0;
    if (glen >= sizeof(out->gateway_order_id)) glen = sizeof(out->gateway_order_id) - 1;
    if (goid) memcpy(out->gateway_order_id, goid, glen);
    out->gateway_order_id[glen] = '\0';
    PQclear(r);
    return 0;
}

int db_intent_settle(DB *db, uint32_t mid, uint64_t request_id) {
    static const char SQL[] =
        "UPDATE payment_intents SET status = 1, update_time = NOW() "
        "WHERE mid = $1 AND request_id = $2 AND status = 0";

    uint64_t m  = pg_int8((uint64_t)mid);
    uint64_t ri = pg_int8(request_id);

    const char *vals[2] = { (char *)&m, (char *)&ri };
    int         lens[2] = { 8, 8 };
    int         fmts[2] = { 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_COMMAND_OK) {
        fprintf(stderr, "[db] intent_settle: %s\n", PQerrorMessage(db->conn));
        PQclear(r);
        return -1;
    }

    char *affected = PQcmdTuples(r);
    int settled = (affected && affected[0] == '1');
    PQclear(r);
    return settled ? 0 : -1;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Merchant registry
 * ══════════════════════════════════════════════════════════════════════════ */

int db_merchant_register(DB *db, uint32_t mid, const char *name) {
    static const char SQL[] =
        "INSERT INTO merchants (mid, name) VALUES ($1, $2) "
        "ON CONFLICT (mid) DO UPDATE SET name = EXCLUDED.name, update_time = NOW()";

    uint64_t m = pg_int8((uint64_t)mid);
    const char *vals[2] = { (char *)&m, name };
    int         lens[2] = { 8, (int)strlen(name) };
    int         fmts[2] = { 1, 0 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_COMMAND_OK) {
        fprintf(stderr, "[db] merchant_register: %s\n", PQerrorMessage(db->conn));
        PQclear(r);
        return -1;
    }
    char *aff = PQcmdTuples(r);
    int created = (aff && aff[0] == '1');
    PQclear(r);
    return created ? 1 : 0;
}

int db_merchant_exists(DB *db, uint32_t mid) {
    static const char SQL[] = "SELECT 1 FROM merchants WHERE mid = $1";

    uint64_t m = pg_int8((uint64_t)mid);
    const char *vals[1] = { (char *)&m };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK) {
        PQclear(r);
        return -1;
    }
    int found = (PQntuples(r) > 0);
    PQclear(r);
    return found;
}

int db_merchant_get_name(DB *db, uint32_t mid, char *name_out, size_t name_max) {
    static const char SQL[] = "SELECT name FROM merchants WHERE mid = $1";

    uint64_t m = pg_int8((uint64_t)mid);
    const char *vals[1] = { (char *)&m };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK || PQntuples(r) == 0) {
        PQclear(r);
        return -1;
    }
    const char *name = PQgetvalue(r, 0, 0);
    size_t n = name ? strlen(name) : 0;
    if (n >= name_max) n = name_max - 1;
    if (name) memcpy(name_out, name, n);
    name_out[n] = '\0';
    PQclear(r);
    return 0;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Intent listing
 * ══════════════════════════════════════════════════════════════════════════ */

char *db_intents_range(DB *db,
                       const char *from_date, const char *to_date,
                       int *count_out) {
    static const char SQL[] =
        "SELECT mid, request_id, amount, status, gateway_order_id"
        " FROM payment_intents"
        " WHERE (create_time AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE >= $1::DATE"
        "   AND (create_time AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE <= $2::DATE"
        " ORDER BY create_time DESC LIMIT 10000";

    const char *vals[2] = { from_date, to_date };
    int         lens[2] = { 0, 0 };
    int         fmts[2] = { 0, 0 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK) {
        fprintf(stderr, "[db] intents_range: %s\n", PQerrorMessage(db->conn));
        PQclear(r);
        return NULL;
    }

    int n = PQntuples(r);
    if (count_out) *count_out = n;

    int cap = 64 + n * 150;
    if (cap < 4096) cap = 4096;
    char *buf = malloc(cap);
    if (!buf) { PQclear(r); return NULL; }

    int off = 0;
    buf[off++] = '[';

    for (int i = 0; i < n; i++) {
        if (off + 300 > cap) {
            cap *= 2;
            char *nb = realloc(buf, cap);
            if (!nb) { free(buf); PQclear(r); return NULL; }
            buf = nb;
        }

        const char *mid_s = PQgetvalue(r, i, 0);
        const char *rid_s = PQgetvalue(r, i, 1);
        const char *amt_s = PQgetvalue(r, i, 2);
        const char *sts_s = PQgetvalue(r, i, 3);
        const char *goid  = PQgetvalue(r, i, 4);

        char esc[512] = "";
        int ei = 0;
        for (int k = 0; goid[k] && ei < 508; k++) {
            if (goid[k] == '"' || goid[k] == '\\') esc[ei++] = '\\';
            esc[ei++] = goid[k];
        }

        if (i > 0) buf[off++] = ',';
        off += snprintf(buf + off, cap - off,
            "{\"mid\":%s,\"request_id\":%s,\"amount\":%s,\"status\":%s,\"gateway_order_id\":\"%s\"}",
            mid_s, rid_s, amt_s, sts_s, esc);
    }

    if (off + 2 > cap) {
        cap += 4;
        char *nb = realloc(buf, cap);
        if (!nb) { free(buf); PQclear(r); return NULL; }
        buf = nb;
    }
    buf[off++] = ']';
    buf[off]   = '\0';

    PQclear(r);
    return buf;
}

/* ══════════════════════════════════════════════════════════════════════════
 *  Admin stats & cash ops
 * ══════════════════════════════════════════════════════════════════════════ */

char *db_export_transfers_csv(DB *db,
                              const char *from_date, const char *to_date,
                              int *rows_out) {
    /* Filter by ICT (UTC+7); label mid=1 as Bank, merchants by name, rest as User */
    static const char SQL[] =
        "SELECT t.id, t.from_id,"
        "  CASE WHEN t.from_id <= 999 THEN 'Bank'"
        "       ELSE COALESCE(mf.name, 'User') END AS from_label,"
        "  t.to_id,"
        "  CASE WHEN t.to_id <= 999 THEN 'Bank'"
        "       ELSE COALESCE(mt.name, 'User') END AS to_label,"
        "  t.amount, t.type,"
        "  TO_CHAR(t.create_time AT TIME ZONE 'Asia/Ho_Chi_Minh','YYYY-MM-DD HH24:MI:SS') AS ts"
        " FROM transfers t"
        " LEFT JOIN merchants mf ON mf.mid = t.from_id"
        " LEFT JOIN merchants mt ON mt.mid = t.to_id"
        " WHERE (t.create_time AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE >= $1::DATE"
        "   AND (t.create_time AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE <= $2::DATE"
        " ORDER BY t.id ASC LIMIT 100000";

    const char *vals[2] = { from_date, to_date };
    int         lens[2] = { 0, 0 };
    int         fmts[2] = { 0, 0 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK) {
        fprintf(stderr, "[db] export_transfers: %s\n", PQerrorMessage(db->conn));
        PQclear(r);
        return NULL;
    }

    int n = PQntuples(r);
    if (rows_out) *rows_out = n;

    static const char *TYPES[] = { "transfer","payment","cash_in","cash_out" };

    /* Header + n rows * ~120 chars; realloc if needed */
    int cap = 256 + n * 120;
    if (cap < 16384) cap = 16384;
    char *buf = malloc(cap);
    if (!buf) { PQclear(r); return NULL; }

    int off = snprintf(buf, cap,
        "id,from_id,from_label,to_id,to_label,amount,type,type_name,create_time_ict\n");

    for (int i = 0; i < n; i++) {
        if (off + 200 > cap) {
            cap *= 2;
            char *nb = realloc(buf, cap);
            if (!nb) { break; }
            buf = nb;
        }
        int tp = atoi(PQgetvalue(r, i, 6));
        const char *tname = (tp >= 0 && tp <= 3) ? TYPES[tp] : "?";
        off += snprintf(buf + off, cap - off,
            "%s,%s,\"%s\",%s,\"%s\",%s,%s,%s,%s\n",
            PQgetvalue(r, i, 0),  /* id */
            PQgetvalue(r, i, 1),  /* from_id */
            PQgetvalue(r, i, 2),  /* from_label */
            PQgetvalue(r, i, 3),  /* to_id */
            PQgetvalue(r, i, 4),  /* to_label */
            PQgetvalue(r, i, 5),  /* amount */
            PQgetvalue(r, i, 6),  /* type */
            tname,
            PQgetvalue(r, i, 7)); /* create_time_ict */
    }

    PQclear(r);
    return buf;
}

int db_admin_user_upsert(DB *db, const char *username, const uint8_t *password_hash) {
    static const char SQL[] =
        "INSERT INTO admins (username, password_hash) VALUES ($1, $2) "
        "ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash";
    const char *vals[2] = { username, (const char *)password_hash };
    int         lens[2] = { (int)strlen(username), 32 };
    int         fmts[2] = { 0, 1 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);
    int ok = PQresultStatus(r) == PGRES_COMMAND_OK;
    if (!ok) fprintf(stderr, "[db] admin_user_upsert: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return ok ? 0 : -1;
}

int db_admin_user_verify(DB *db, const char *username, const uint8_t *password_hash) {
    static const char SQL[] =
        "SELECT password_hash FROM admins WHERE username = $1";
    const char *vals[1] = { username };
    int         lens[1] = { (int)strlen(username) };
    int         fmts[1] = { 0 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);
    if (PQresultStatus(r) != PGRES_TUPLES_OK || PQntuples(r) == 0) {
        PQclear(r); return -1;
    }
    int len = PQgetlength(r, 0, 0);
    int ok  = (len == 32 && memcmp(PQgetvalue(r, 0, 0), password_hash, 32) == 0);
    PQclear(r);
    return ok ? 0 : -1;
}

int db_admin_user_list(DB *db, char *out_json, int buf_size) {
    static const char SQL[] =
        "SELECT username, TO_CHAR(create_time AT TIME ZONE 'Asia/Ho_Chi_Minh',"
        "'YYYY-MM-DD HH24:MI:SS') FROM admins ORDER BY create_time ASC";
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 0, NULL, NULL, NULL, NULL, 0);
    DB_UNLOCK(db);
    if (PQresultStatus(r) != PGRES_TUPLES_OK) {
        PQclear(r); return -1;
    }
    int n = PQntuples(r);
    int off = snprintf(out_json, buf_size, "[");
    for (int i = 0; i < n && off < buf_size - 128; i++) {
        off += snprintf(out_json + off, buf_size - off,
            "%s{\"username\":\"%s\",\"create_time\":\"%s\"}",
            i ? "," : "", PQgetvalue(r, i, 0), PQgetvalue(r, i, 1));
    }
    snprintf(out_json + off, buf_size - off, "]");
    PQclear(r);
    return 0;
}

int db_admin_user_delete(DB *db, const char *username) {
    static const char SQL[] = "DELETE FROM admins WHERE username = $1";
    const char *vals[1] = { username };
    int         lens[1] = { (int)strlen(username) };
    int         fmts[1] = { 0 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);
    int ok = PQresultStatus(r) == PGRES_COMMAND_OK;
    PQclear(r);
    return ok ? 0 : -1;
}

int db_admin_audit_log(DB *db, const char *username, const char *action,
                       uint32_t target_uid, uint64_t amount, const char *ref) {
    static const char SQL[] =
        "INSERT INTO admin_audit (username, action, target_uid, amount, ref)"
        " VALUES ($1, $2, $3, $4, $5)";
    uint64_t uid = pg_int8((uint64_t)target_uid);
    uint64_t amt = pg_int8(amount);
    const char *vals[5] = { username, action, (char *)&uid, (char *)&amt, ref };
    int         lens[5] = { (int)strlen(username), (int)strlen(action), 8, 8, (int)strlen(ref) };
    int         fmts[5] = { 0, 0, 1, 1, 0 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 5, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);
    int ok = PQresultStatus(r) == PGRES_COMMAND_OK;
    if (!ok) fprintf(stderr, "[db] audit_log: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return ok ? 0 : -1;
}

char *db_admin_audit_list(DB *db, int limit) {
    static const char SQL[] =
        "SELECT username, action, target_uid, amount, ref,"
        " TO_CHAR(create_time AT TIME ZONE 'Asia/Ho_Chi_Minh','YYYY-MM-DD HH24:MI:SS')"
        " FROM admin_audit ORDER BY create_time DESC LIMIT $1";
    char limit_str[8];
    snprintf(limit_str, sizeof(limit_str), "%d", limit);
    const char *vals[1] = { limit_str };
    int         lens[1] = { 0 };
    int         fmts[1] = { 0 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);
    if (PQresultStatus(r) != PGRES_TUPLES_OK) {
        fprintf(stderr, "[db] audit_list: %s\n", PQerrorMessage(db->conn));
        PQclear(r); return NULL;
    }
    int n = PQntuples(r);
    int bufsz = 64 + n * 320;
    char *buf = malloc(bufsz);
    if (!buf) { PQclear(r); return NULL; }
    int off = snprintf(buf, bufsz, "[");
    for (int i = 0; i < n && off < bufsz - 512; i++) {
        off += snprintf(buf + off, bufsz - off,
            "%s{\"username\":\"%s\",\"action\":\"%s\","
            "\"target_uid\":%s,\"amount\":%s,"
            "\"ref\":\"%s\",\"time\":\"%s\"}",
            i ? "," : "",
            PQgetvalue(r, i, 0), PQgetvalue(r, i, 1),
            PQgetvalue(r, i, 2), PQgetvalue(r, i, 3),
            PQgetvalue(r, i, 4), PQgetvalue(r, i, 5));
    }
    snprintf(buf + off, bufsz - off, "]");
    PQclear(r);
    return buf;
}

int db_admin_stats(DB *db, AdminStats *out) {
    static const char SQL[] =
        "SELECT "
        " (SELECT COUNT(*)::BIGINT FROM transfers),"
        " (SELECT COALESCE(SUM(amount),0)::BIGINT FROM transfers),"
        " (SELECT COUNT(*)::BIGINT FROM accounts)";

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 0, NULL, NULL, NULL, NULL, 1);
    DB_UNLOCK(db);

    int rc = -1;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        out->total_txns    = (int64_t)from_be64((uint8_t *)PQgetvalue(r, 0, 0));
        out->total_volume  = (int64_t)from_be64((uint8_t *)PQgetvalue(r, 0, 1));
        out->account_count = (int64_t)from_be64((uint8_t *)PQgetvalue(r, 0, 2));
        rc = 0;
    }
    PQclear(r);
    return rc;
}

int db_admin_cash_in(DB *db, uint32_t to_uid, uint64_t amount, int64_t *after_out) {
    static const char SQL[] =
        "WITH upd AS ("
        "  UPDATE balances SET balance = balance + $1, version = version + 1"
        "  WHERE account_id = $2 RETURNING balance"
        "),"
        "ins AS ("
        "  INSERT INTO transfers (from_id, to_id, amount, type)"
        "  SELECT 1, $2, $1, 2 FROM upd"
        ")"
        "SELECT balance FROM upd";

    uint64_t a = pg_int8(amount);
    uint64_t t = pg_int8((uint64_t)to_uid);
    const char *v[2] = { (char *)&a, (char *)&t };
    int         l[2] = { 8, 8 };
    int         f[2] = { 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, v, l, f, 1);
    DB_UNLOCK(db);

    int rc = -1;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        if (after_out) *after_out = (int64_t)from_be64((uint8_t *)PQgetvalue(r, 0, 0));
        rc = 0;
    } else fprintf(stderr, "[db] admin_cash_in: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return rc;
}

int db_admin_cash_out(DB *db, uint32_t from_uid, uint64_t amount, int64_t *after_out) {
    static const char SQL[] =
        "WITH upd AS ("
        "  UPDATE balances SET balance = balance - $1, version = version + 1"
        "  WHERE account_id = $2 AND balance >= $1 RETURNING balance"
        "),"
        "ins AS ("
        "  INSERT INTO transfers (from_id, to_id, amount, type)"
        "  SELECT $2, 1, $1, 3 FROM upd"
        ")"
        "SELECT balance FROM upd";

    uint64_t a  = pg_int8(amount);
    uint64_t fr = pg_int8((uint64_t)from_uid);
    const char *v[2] = { (char *)&a, (char *)&fr };
    int         l[2] = { 8, 8 };
    int         f[2] = { 1, 1 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, v, l, f, 1);
    DB_UNLOCK(db);

    int rc = -1;
    if (PQresultStatus(r) == PGRES_TUPLES_OK && PQntuples(r) > 0) {
        if (after_out) *after_out = (int64_t)from_be64((uint8_t *)PQgetvalue(r, 0, 0));
        rc = 0;
    }
    PQclear(r);
    return rc;
}

int db_intent_list(DB *db, uint32_t mid, IntentSummary *out, int max_count) {
    static const char SQL[] =
        "SELECT request_id, order_id, amount"
        " FROM payment_intents"
        " WHERE mid = $1 AND status = 0"
        " ORDER BY create_time DESC LIMIT $2";

    uint64_t m = pg_int8((uint64_t)mid);
    char     limit_str[8];
    snprintf(limit_str, sizeof(limit_str), "%d", max_count);

    const char *vals[2] = { (char *)&m, limit_str };
    int         lens[2] = { 8, 0 };
    int         fmts[2] = { 1, 0 };

    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 1);
    DB_UNLOCK(db);

    if (PQresultStatus(r) != PGRES_TUPLES_OK) {
        fprintf(stderr, "[db] intent_list: %s\n", PQerrorMessage(db->conn));
        PQclear(r);
        return -1;
    }

    int n = PQntuples(r);
    if (n > max_count) n = max_count;
    for (int i = 0; i < n; i++) {
        out[i].request_id = from_be64((uint8_t *)PQgetvalue(r, i, 0));
        out[i].order_id   = from_be64((uint8_t *)PQgetvalue(r, i, 1));
        out[i].amount     = from_be64((uint8_t *)PQgetvalue(r, i, 2));
    }
    PQclear(r);
    return n;
}

/* ─── APNs push tokens ───────────────────────────────────────────────────── */

int db_push_token_upsert(DB *db, uint32_t mid, const char *device_token) {
    static const char SQL[] =
        "INSERT INTO push_tokens(mid, device_token, updated_at) "
        "VALUES($1, $2, NOW()) "
        "ON CONFLICT(mid) DO UPDATE SET device_token=EXCLUDED.device_token, "
        "                               updated_at=NOW()";
    uint64_t mid64 = pg_int8((uint64_t)mid);
    const char *vals[2] = { (char *)&mid64, device_token };
    int         lens[2] = { 8, (int)strlen(device_token) };
    int         fmts[2] = { 1, 0 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);
    int ok = (PQresultStatus(r) == PGRES_COMMAND_OK);
    if (!ok) fprintf(stderr, "[db] push_token_upsert: %s\n", PQerrorMessage(db->conn));
    PQclear(r);
    return ok ? 0 : -1;
}

int db_push_token_get(DB *db, uint32_t mid, char *out, size_t outlen) {
    static const char SQL[] =
        "SELECT device_token FROM push_tokens WHERE mid=$1";
    uint64_t mid64 = pg_int8((uint64_t)mid);
    const char *vals[1] = { (char *)&mid64 };
    int         lens[1] = { 8 };
    int         fmts[1] = { 1 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, vals, lens, fmts, 0);
    DB_UNLOCK(db);
    if (PQresultStatus(r) != PGRES_TUPLES_OK || PQntuples(r) == 0) {
        PQclear(r); return -1;
    }
    const char *tok = PQgetvalue(r, 0, 0);
    strncpy(out, tok, outlen - 1);
    out[outlen - 1] = '\0';
    PQclear(r);
    return 0;
}

/* ─── Single txn lookup ──────────────────────────────────────────────────── */

int db_merchant_pubkey_set(DB *db, uint32_t mid, const uint8_t pubkey[32]) {
    static const char SQL[] =
        "UPDATE merchants SET pubkey = $1 WHERE mid = $2";
    uint64_t mid_be = pg_int8((uint64_t)mid);
    const char *pvals[2] = { (char *)pubkey, (char *)&mid_be };
    const int   plens[2] = { 32, 8 };
    const int   pfmts[2] = { 1, 1 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 2, NULL, pvals, plens, pfmts, 1);
    DB_UNLOCK(db);
    int ok = (PQresultStatus(r) == PGRES_COMMAND_OK);
    PQclear(r);
    return ok ? 0 : -1;
}

int db_merchant_pubkey_get(DB *db, uint32_t mid, uint8_t pubkey_out[32]) {
    static const char SQL[] =
        "SELECT pubkey FROM merchants WHERE mid = $1 AND pubkey IS NOT NULL";
    uint64_t mid_be = pg_int8((uint64_t)mid);
    const char *pvals[1] = { (char *)&mid_be };
    const int   plens[1] = { 8 };
    const int   pfmts[1] = { 1 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, pvals, plens, pfmts, 1);
    DB_UNLOCK(db);
    if (PQresultStatus(r) != PGRES_TUPLES_OK || PQntuples(r) == 0 ||
        PQgetlength(r, 0, 0) != 32) {
        PQclear(r); return -1;
    }
    memcpy(pubkey_out, PQgetvalue(r, 0, 0), 32);
    PQclear(r);
    return 0;
}

int db_txn_get(DB *db, int64_t txn_id, TxnDetail *out) {
    static const char SQL[] =
        "SELECT from_id, to_id, amount, type FROM transfers WHERE id = $1";
    uint64_t id_be = pg_int8((uint64_t)txn_id);
    const char *pvals[1] = { (char *)&id_be };
    const int   plens[1] = { 8 };
    const int   pfmts[1] = { 1 };
    DB_LOCK(db);
    PGresult *r = PQexecParams(db->conn, SQL, 1, NULL, pvals, plens, pfmts, 1);
    DB_UNLOCK(db);
    if (PQresultStatus(r) != PGRES_TUPLES_OK || PQntuples(r) == 0) {
        PQclear(r); return -1;
    }
    out->txn_id  = txn_id;
    out->from_id = (uint32_t)from_be64((const uint8_t *)PQgetvalue(r, 0, 0));
    out->to_id   = (uint32_t)from_be64((const uint8_t *)PQgetvalue(r, 0, 1));
    out->amount  = from_be64((const uint8_t *)PQgetvalue(r, 0, 2));
    const uint8_t *tp = (const uint8_t *)PQgetvalue(r, 0, 3);
    out->type    = (int)((tp[0] << 8) | tp[1]);
    PQclear(r);
    return 0;
}
