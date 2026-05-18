#pragma once
#include <stdint.h>
#include <stddef.h>

/*
 * ══════════════════════════════════════════════════════════════════════════
 *  SAVING  —  WIRE PROTOCOL  v1
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Transport : TCP (persistent connection per client)
 *  Byte order: big-endian on the wire
 *  Auth      : one-time LOGIN → 32-byte session token for all subsequent ops
 *  Push      : server streams EVT_* frames to the client on the same socket
 *
 * ──────────────────────────────────────────────────────────────────────────
 *  FRAME LAYOUT
 * ──────────────────────────────────────────────────────────────────────────
 *
 *   ┌──────────┬────────┬──────────┬──────────────────┬─────────────┐
 *   │ len  4 B │ type 1B│ seq   4 B│ body  (len-41) B │  sig   32 B │
 *   └──────────┴────────┴──────────┴──────────────────┴─────────────┘
 *
 *   len  — total frame size, including the len field itself
 *   type — WIRE_TYPE_* constant (1 byte)
 *   seq  — monotonic sequence number (client sets it; server mirrors it)
 *   body — message-specific payload (see per-type layout below)
 *   sig  — HMAC-SHA256( len || type || seq || body , SERVER_SECRET )
 *
 *   Minimum frame: 4 + 1 + 4 + 0 + 32 = 41 bytes.
 *   Maximum frame: WIRE_MAX_FRAME bytes.
 *
 * ──────────────────────────────────────────────────────────────────────────
 *  MESSAGE TYPES
 * ──────────────────────────────────────────────────────────────────────────
 *
 *  Client → Server (REQUEST range 0x01–0x3F)
 *
 *    0x01  PING            body: –
 *    0x02  LOGIN           body: [ mid 4B ][ pw_hash 32B ]
 *    0x03  LOGOUT          body: [ token 32B ]
 *    0x10  CREATE_ACCOUNT  body: [ mid 4B ][ pw_hash 32B ]
 *    0x11  TRANSFER        body: [ token 32B ][ to 4B ][ amount 8B ]
 *                        ACK extra: [ after_balance 8B ]
 *    0x12  GET_BALANCE     body: [ token 32B ]
 *                        ACK extra: [ balance 8B ][ pending 8B ][ available 8B ][ version 8B ]
 *    0x13  ADD_GUARDIAN    body: [ token 32B ][ guardian_id 4B ]
 *    0x14  RECOVERY_REQ    body: [ mid 4B ]        ← no token (new device)
 *    0x15  RECOVERY_APPROVE body: [ token 32B ][ target_id 4B ]
 *
 *  Server → Client (RESPONSE range 0x80–0xBF, mirrors client seq)
 *
 *    0x80  PONG            body: –
 *    0x81  LOGIN_ACK       body: [ code 1B ][ token 32B ]  (code 0 = ok)
 *    0x82  ACK             body: [ code 1B ][ data ... ]   (generic ok/err)
 *
 *  Server → Client (PUSH/EVENT range 0xC0–0xFF, seq = 0)
 *
 *    0xC0  EVT_TRANSFER_IN  body: [ from 4B ][ amount 8B ][ balance 8B ]
 *    0xC1  EVT_RECOVERY_REQ body: [ account_id 4B ]        (guardian must act)
 *    0xC2  EVT_RECOVERY_OK  body: [ account_id 4B ]        (your recovery granted)
 *    0xC3  EVT_GUARDIAN_ADD body: [ account_id 4B ]        (someone added you)
 *
 * ──────────────────────────────────────────────────────────────────────────
 *  RESPONSE CODES  (1 byte inside LOGIN_ACK / ACK body)
 * ──────────────────────────────────────────────────────────────────────────
 *
 *    0x00  OK
 *    0x01  ERR_BAD_FRAME      malformed or wrong size
 *    0x02  ERR_BAD_SIG        HMAC mismatch
 *    0x03  ERR_ID_TAKEN       account ID already registered
 *    0x04  ERR_ID_RESERVED    ID in VIP block (< 16 777 216)
 *    0x05  ERR_NOT_FOUND      account / guardian not found
 *    0x06  ERR_BAD_PASSWORD   wrong credentials
 *    0x07  ERR_BAD_TOKEN      session expired or invalid
 *    0x08  ERR_LOW_BALANCE    not enough funds
 *    0x09  ERR_GUARDIAN_FULL  already has 3 guardians
 *    0x0A  ERR_NOT_GUARDIAN   caller is not a guardian of target
 *    0x0B  ERR_NEED_GUARDIANS need ≥ 2 guardians before recovery
 *    0xFF  ERR_INTERNAL
 *
 * ══════════════════════════════════════════════════════════════════════════
 */

/* ─── Account ID range ───────────────────────────────────────────────────── */
#define SAVING_ID_VIP_MAX   16777215u
#define SAVING_ID_USER_MIN  16777216u
#define SAVING_ID_MAX       4294967295u

/* ─── Frame constants ────────────────────────────────────────────────────── */
#define WIRE_SIG_SIZE       32
#define WIRE_FRAME_OVERHEAD 41          /* 4+1+4+32 */
#define WIRE_MAX_FRAME      4096
#define WIRE_MAX_BODY       (WIRE_MAX_FRAME - WIRE_FRAME_OVERHEAD)

/* ─── Message types ──────────────────────────────────────────────────────── */
#define WIRE_PING            0x01
#define WIRE_LOGIN           0x02
#define WIRE_LOGOUT          0x03
#define WIRE_CREATE_ACCOUNT  0x10
#define WIRE_TRANSFER        0x11
#define WIRE_GET_BALANCE     0x12
#define WIRE_ADD_GUARDIAN    0x13
#define WIRE_RECOVERY_REQ    0x14
#define WIRE_RECOVERY_APPROVE 0x15
#define WIRE_GET_HISTORY     0x16

/* ─── Payment Intent commands ────────────────────────────────────────────── */
/* REGISTER_MERCHANT body: [token 32B][name N bytes]                          */
/* ENROLL_TOTP       body: [merchant_token 32B][customer_id 4B][secret 20B]   */
/* CREATE_INTENT     body: [merchant_token 32B][request_id 8B][order_id 8B][amount 8B] */
/* PAY_INTENT        body: [customer_token 32B][merchant_id 4B][request_id 8B][totp_code 4B] */
#define WIRE_CASH_IN           0x24   /* body: [bank_token 32B][to_uid 4B][amount 8B][topup_id N bytes] */
#define WIRE_REGISTER_MERCHANT 0x23
#define WIRE_ENROLL_TOTP     0x22
#define WIRE_CREATE_INTENT   0x20
#define WIRE_PAY_INTENT      0x21

#define WIRE_PONG            0x80
#define WIRE_LOGIN_ACK       0x81
#define WIRE_ACK             0x82

#define WIRE_EVT_TRANSFER_IN  0xC0
#define WIRE_EVT_RECOVERY_REQ 0xC1
#define WIRE_EVT_RECOVERY_OK  0xC2
#define WIRE_EVT_GUARDIAN_ADD 0xC3

/* ─── Response codes ─────────────────────────────────────────────────────── */
#define WIRE_OK                0x00
#define WIRE_ERR_BAD_FRAME     0x01
#define WIRE_ERR_BAD_SIG       0x02
#define WIRE_ERR_ID_TAKEN      0x03
#define WIRE_ERR_ID_RESERVED   0x04
#define WIRE_ERR_NOT_FOUND     0x05
#define WIRE_ERR_BAD_PASSWORD  0x06
#define WIRE_ERR_BAD_TOKEN     0x07
#define WIRE_ERR_LOW_BALANCE   0x08
#define WIRE_ERR_GUARDIAN_FULL 0x09
#define WIRE_ERR_NOT_GUARDIAN  0x0A
#define WIRE_ERR_NEED_GUARDIANS 0x0B
#define WIRE_ERR_TOTP_INVALID  0x0C
#define WIRE_ERR_INTENT_SETTLED 0x0D
#define WIRE_ERR_NOT_MERCHANT  0x0E
#define WIRE_ERR_INTERNAL      0xFF

/* ─── Session token ──────────────────────────────────────────────────────── */
#define WIRE_TOKEN_SIZE     32
#define WIRE_TOKEN_TTL_SEC  900         /* 15 minutes idle expiry */

/* ─── In-memory parsed frame ─────────────────────────────────────────────── */
typedef struct {
    uint32_t len;
    uint8_t  type;
    uint32_t seq;
    uint8_t  body[WIRE_MAX_BODY];
    uint16_t body_len;
} WireFrame;

/* ─── API ────────────────────────────────────────────────────────────────── */

/* Parse raw bytes from TCP stream into *f.
 * Returns WIRE_OK or WIRE_ERR_*. */
int wire_frame_parse(const uint8_t *buf, size_t len, WireFrame *f);

/* Encode a frame into buf.  Returns total bytes written, 0 on error. */
size_t wire_frame_encode(uint8_t type, uint32_t seq,
                         const uint8_t *body, uint16_t body_len,
                         uint8_t *buf, size_t buf_size);

/* Convenience: encode a 1-byte code ACK. */
size_t wire_ack(uint32_t seq, uint8_t code,
                const uint8_t *extra, uint16_t extra_len,
                uint8_t *buf, size_t buf_size);

/* Read exactly one frame from fd (blocking).
 * Returns WIRE_OK, WIRE_ERR_BAD_FRAME on parse error, -1 on I/O error. */
int wire_recv_frame(int fd, WireFrame *f);

/* Write a raw encoded frame to fd. Returns 0 on success, -1 on error. */
int wire_send_raw(int fd, const uint8_t *buf, size_t len);
