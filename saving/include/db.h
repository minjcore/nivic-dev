#pragma once
#include <stdint.h>
#include <stddef.h>
#include <pthread.h>
#include <libpq-fe.h>

/*
 * ══════════════════════════════════════════════════════════════════════════
 *  SAVING  —  PostgreSQL persistence layer
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Schema (auto-created on db_open):
 *
 *   accounts          — one row per registered ID
 *   guardians          — up to 3 guardian links per account
 *   recovery_requests  — open device-switch requests
 *   wallet_idempotency — (mid, request_id) dedup   ← from Java JdbcIdempotencyGate
 *   wallet_ledger      — append-only audit trail    ← from Java JdbcWalletLedger
 *
 *  Thread safety: all public functions are serialised under DB.mu.
 *  For higher concurrency, swap in a proper connection pool (pgBouncer).
 * ══════════════════════════════════════════════════════════════════════════
 */

typedef struct {
    PGconn     *conn;
    pthread_mutex_t mu;
} DB;

/* ─── Lifecycle ──────────────────────────────────────────────────────────── */

/* conninfo: libpq connection string, e.g. "dbname=saving host=localhost" */
int  db_open(DB *db, const char *conninfo);
void db_close(DB *db);

/* ─── Accounts ───────────────────────────────────────────────────────────── */

/* password_hash: raw 32-byte SHA-256. Returns 0 on success. */
int db_account_create(DB *db, uint32_t id, const uint8_t *password_hash);

/* Returns 1 if id exists, 0 if not, -1 on error. */
int db_account_exists(DB *db, uint32_t id);

/* Fills hash[32]. Returns 0 on success, -1 on error / not found. */
int db_account_get_hash(DB *db, uint32_t id, uint8_t *hash);

/* Returns balance in VND minor units, or -1 on error. */
int64_t db_account_balance(DB *db, uint32_t id);

/* ─── Transfer ───────────────────────────────────────────────────────────── *
 *
 *  Atomic CTE: debit sender + credit receiver in one round-trip.
 *  Returns:
 *    0  = success
 *   -1  = sender not found or insufficient balance
 *   -2  = receiver not found
 *   -3  = other DB error
 *
 * ─────────────────────────────────────────────────────────────────────────── */
int db_transfer(DB *db, uint32_t from_id, uint32_t to_id, uint64_t amount);

/* ─── Idempotency  (ported from Java JdbcIdempotencyGate) ───────────────── *
 *
 *  INSERT (mid, request_id, order_id) ON CONFLICT DO NOTHING.
 *  Returns:
 *    1  = first claim  (proceed with operation)
 *    0  = duplicate    (already handled — replay the cached response)
 *   -1  = DB error
 *
 * ─────────────────────────────────────────────────────────────────────────── */
int db_idempotency_claim(DB *db, uint64_t mid, uint64_t request_id, uint64_t order_id);

/* ─── Ledger  (ported from Java JdbcWalletLedger) ───────────────────────── *
 *
 *  Append-only audit row.  Never updated after insert.
 *  Returns 0 on success, -1 on error.
 *
 * ─────────────────────────────────────────────────────────────────────────── */
int db_ledger_append(DB *db,
                     uint64_t mid, uint64_t request_id, uint64_t order_id,
                     uint64_t command, uint64_t amount_minor,
                     const uint8_t *extra_data, uint16_t extra_len);

/* ─── Guardians ──────────────────────────────────────────────────────────── */

/* Returns count (0-3) or -1 on error. */
int db_guardian_count(DB *db, uint32_t account_id);

/* Returns 0 on success, -1 if already at 3, -2 on error. */
int db_guardian_add(DB *db, uint32_t account_id, uint32_t guardian_id);

/* Fills ids[0..2], returns count (0-3) or -1 on error. */
int db_guardian_list(DB *db, uint32_t account_id, uint32_t ids[3]);

/* ─── Transfer history ───────────────────────────────────────────────────── */

typedef struct {
    int      direction;    /* 0 = sent, 1 = received */
    uint32_t counterpart;
    uint64_t amount;
} TxEntry;

/* Record a completed transfer. Call after db_transfer succeeds. */
int db_record_transfer(DB *db, uint32_t from_id, uint32_t to_id, uint64_t amount);

/* Fill out[0..max_count-1] sorted newest-first. Returns count or -1 on error. */
int db_history(DB *db, uint32_t account_id, TxEntry *out, int max_count);

/* ─── TOTP Enrollments ───────────────────────────────────────────────────── */

/* Upsert customer TOTP secret for a merchant. secret must be 20 bytes. */
int db_totp_enroll(DB *db, uint32_t merchant_id, uint32_t customer_id,
                   const uint8_t *secret);

/* Fill secret_out[20]. Returns 0 on success, -1 if not found / error. */
int db_totp_get_secret(DB *db, uint32_t merchant_id, uint32_t customer_id,
                       uint8_t *secret_out);

/* ─── Payment Intents ────────────────────────────────────────────────────── */

typedef struct {
    uint64_t amount;
    int      status;   /* 0=pending, 1=settled */
} IntentInfo;

/* Insert intent, idempotency key (mid, request_id).
 * Returns 1=created, 0=already exists (replay OK), -1=error. */
int db_intent_create(DB *db, uint32_t mid, uint64_t request_id,
                     uint64_t order_id, uint64_t amount);

/* Fill *out. Returns 0 on success, -1 if not found / error. */
int db_intent_get(DB *db, uint32_t mid, uint64_t request_id, IntentInfo *out);

/* Mark intent as settled. Returns 0 on success, -1 on error. */
int db_intent_settle(DB *db, uint32_t mid, uint64_t request_id);

/* ─── Merchant registry ──────────────────────────────────────────────────── */

/* Upsert merchant record. Returns 1=inserted, 0=updated, -1=error. */
int db_merchant_register(DB *db, uint32_t mid, const char *name);

/* Returns 1 if mid is a registered merchant, 0 if not, -1 on error. */
int db_merchant_exists(DB *db, uint32_t mid);

/* ─── Social Recovery ────────────────────────────────────────────────────── */

/* Opens (or resets) a recovery request. Returns 0 on success, -1 on error. */
int db_recovery_open(DB *db, uint32_t account_id);

/* Records one guardian approval.
 * Returns current approval count, -1 if guardian_id is not a guardian, -2 on error. */
int db_recovery_approve(DB *db, uint32_t account_id, uint32_t guardian_id);

/* Returns 1 if ≥ 2 approvals exist, 0 if not, -1 on error. */
int db_recovery_is_complete(DB *db, uint32_t account_id);

/* Deletes the open recovery request. */
void db_recovery_close(DB *db, uint32_t account_id);
