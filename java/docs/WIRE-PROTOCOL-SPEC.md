# Wire Protocol Specification

**Version:** 1.0  
**Status:** ACTIVE  
**Date:** 2026-06-03  
**Server Port:** 7474 (C server, binary TCP)  
**Encoding:** Big-endian, no padding

---

## Overview

Binary wire protocol for Android Kotlin/Compose client ↔ C server (:7474) communication.

**Design Goals:**
- Minimal payload (binary, not JSON)
- Low latency (structured message framing)
- Idempotency (ref_id deduplication in Java ledger)
- Transactional integrity (double-entry validation in Java)

---

## Frame Structure

```
┌──────────────────────────────────────────────────────┐
│ FRAME FORMAT (big-endian)                            │
├──────────────────────────────────────────────────────┤
│ Byte(s)  │ Field          │ Type    │ Description    │
├──────────┼────────────────┼─────────┼────────────────┤
│ 0-3      │ frame_length   │ uint32  │ Total bytes    │
│          │                │         │ (excl. this    │
│          │                │         │  header)       │
├──────────┼────────────────┼─────────┼────────────────┤
│ 4        │ message_type   │ uint8   │ See msg types  │
├──────────┼────────────────┼─────────┼────────────────┤
│ 5-12     │ correlation_id │ uint64  │ Request track, │
│          │                │         │ echoed in resp  │
├──────────┼────────────────┼─────────┼────────────────┤
│ 13-N     │ payload        │ bytes   │ Message-type   │
│          │                │         │ specific       │
├──────────┼────────────────┼─────────┼────────────────┤
│ N-N+7    │ checksum       │ uint64  │ CRC64-ECMA     │
│          │                │         │ (payload only) │
└──────────┴────────────────┴─────────┴────────────────┘

Total Frame Size = 4 + 1 + 8 + payload_length + 8
Minimum Frame = 21 bytes (empty payload)
```

---

## Message Types

```
Message Type  │ Code │ Direction  │ Payload
──────────────┼──────┼────────────┼─────────────────────────
PING          │ 0x00 │ Both       │ (empty)
PONG          │ 0x01 │ Both       │ (empty)
AUTH          │ 0x02 │ C ← A      │ AuthRequest
AUTH_RESPONSE │ 0x03 │ C → A      │ AuthResponse
TRANSACTION   │ 0x10 │ C ← A      │ TxnRequest
TXN_RESPONSE  │ 0x11 │ C → A      │ TxnResponse
BALANCE_QUERY │ 0x20 │ C ← A      │ BalanceRequest
BALANCE_RESP  │ 0x21 │ C → A      │ BalanceResponse
ERROR         │ 0xFF │ C → A      │ ErrorPayload
```

---

## Field Encoding

### Primitive Types

```
Type        │ Encoding          │ Size (bytes)
────────────┼───────────────────┼──────────────
uint8       │ 1 byte            │ 1
uint16      │ Big-endian        │ 2
uint32      │ Big-endian        │ 4
uint64      │ Big-endian        │ 8
int64       │ Two's complement  │ 8
string      │ [len:uint16][utf8]│ 2 + len
bytes       │ [len:uint16][raw] │ 2 + len
decimal     │ [mantissa:int64]  │ 8
            │ [exponent:int8]   │ 1
            │ (value = m*10^e)  │ Total: 9
```

### Composite Types

**String:**
```
┌─────────┬────────────┐
│ uint16  │ UTF-8 text │
│ length  │            │
└─────────┴────────────┘
Max length: 65,535 bytes
```

**Decimal (for amounts):**
```
┌────────┬──────────┐
│ int64  │ int8     │
│mantissa│exponent  │
└────────┴──────────┘
Example: 150.50 VND = mantissa=15050, exponent=-2
(allows fixed-point without floating-point rounding)
```

---

## Messages

### 1. AUTH (0x02) — Authentication Request

**Sent by:** Android client → C server

```
┌──────────┬────────────┬──────────────────────┐
│ Field    │ Type       │ Notes                │
├──────────┼────────────┼──────────────────────┤
│ user_id  │ string     │ E.g., "user123"      │
│ device_id│ string     │ Android device UDID  │
│ timestamp│ uint64     │ Unix milliseconds    │
│ token    │ bytes      │ HMAC-SHA256(user_id  │
│          │            │ + device_id +       │
│          │            │ timestamp, secret)   │
└──────────┴────────────┴──────────────────────┘

Example Payload (hex):
  00 05 75 73 65 72 31 32 33            [user_id="user123"]
  00 0C 64 65 76 69 63 65 5F 61 62 63 ... [device_id="device_abc..."]
  00 00 01 7F 59 AB CD EF               [timestamp]
  00 20 [32 bytes HMAC]                 [token]
```

### 2. AUTH_RESPONSE (0x03) — Authentication Response

**Sent by:** C server → Android client

```
┌──────────┬────────┬───────────────────────┐
│ Field    │ Type   │ Notes                 │
├──────────┼────────┼───────────────────────┤
│ status   │ uint8  │ 0=OK, 1=FAIL, 2=RETRY│
│ session_id│ bytes │ (if status=0)         │
│           │       │ 32-byte opaque token  │
│ expire_at│ uint64 │ Unix milliseconds     │
│ message  │ string │ "OK" or error reason  │
└──────────┴────────┴───────────────────────┘
```

### 3. TRANSACTION (0x10) — Transaction Request

**Sent by:** Android client → C server

```
┌──────────────┬─────────┬──────────────────────────┐
│ Field        │ Type    │ Notes                    │
├──────────────┼─────────┼──────────────────────────┤
│ txn_type     │ uint8   │ See Transaction Types    │
│ ref_id       │ string  │ Idempotency key          │
│ from_account │ string  │ Account code (10 chars)  │
│ to_account   │ string  │ (for transfer/payment)   │
│ amount       │ decimal │ In minor units (cents)   │
│ currency     │ string  │ "VND", "USD", etc.       │
│ memo         │ string  │ Optional metadata        │
│ party_mid    │ uint64  │ Merchant ID (opt.)       │
│ timestamp    │ uint64  │ Client-side Unix ms      │
└──────────────┴─────────┴──────────────────────────┘
```

**Transaction Types (txn_type):**
```
Code │ Type              │ From → To Pattern
─────┼───────────────────┼─────────────────────────────
0x01 │ TOP_UP            │ topup_transit → user_wallet
0x02 │ WITHDRAWAL        │ user_wallet → withdrawal_transit
0x03 │ TRANSFER          │ user_wallet → user_wallet
0x04 │ PAYMENT           │ user_wallet → merchant_wallet
0x05 │ SETTLEMENT        │ settlement_transit → bank
0x06 │ REVERSAL          │ (reverse any above)
```

**Example Payload (TRANSFER):**
```
01 02                                [txn_type=TRANSFER]
00 10 61 62 63 31 32 33 34 35 36 37 [ref_id="abc1234567"]
00 0A 31 31 31 31 30 30 30 30 30 31 [from_account="1111000001"]
00 0A 32 32 32 32 30 30 30 30 30 31 [to_account="2222000001"]
00 00 00 00 00 00 27 10              [amount mantissa=10000]
FF                                   [exponent=-2 (100.00)]
00 03 56 4E 44                       [currency="VND"]
00 08 54 72 61 6E 73 66 65 72       [memo="Transfer"]
00 00 00 00 00 00 00 00              [party_mid=0 (none)]
00 00 01 7F 59 AB CD EF              [timestamp]
```

### 4. TXN_RESPONSE (0x11) — Transaction Response

**Sent by:** C server → Android client

```
┌──────────────┬─────────┬──────────────────────────┐
│ Field        │ Type    │ Notes                    │
├──────────────┼─────────┼──────────────────────────┤
│ status       │ uint8   │ 0=SUCCESS, 1=PENDING,    │
│              │         │ 2=FAIL, 3=DUPLICATE      │
│ trans_id     │ uint64  │ Java ledger trans_id     │
│ ref_id       │ string  │ Echo back client ref_id  │
│ message      │ string  │ "OK" or error details    │
│ balance      │ decimal │ Updated wallet balance   │
│ timestamp    │ uint64  │ Server-side Unix ms      │
└──────────────┴─────────┴──────────────────────────┘

Status Codes:
  0 = SUCCESS (double-entry posted, committed)
  1 = PENDING (posted but not yet settled)
  2 = FAIL (validation error, not posted)
  3 = DUPLICATE (ref_id already seen, returned cached result)
```

### 5. BALANCE_QUERY (0x20) — Balance Request

**Sent by:** Android client → C server

```
┌──────────────┬────────┬───────────────────┐
│ Field        │ Type   │ Notes             │
├──────────────┼────────┼───────────────────┤
│ account_code │ string │ "1111000001", etc │
│ timestamp    │ uint64 │ Query timestamp   │
└──────────────┴────────┴───────────────────┘
```

### 6. BALANCE_RESP (0x21) — Balance Response

**Sent by:** C server → Android client

```
┌──────────────┬─────────┬──────────────────────────┐
│ Field        │ Type    │ Notes                    │
├──────────────┼─────────┼──────────────────────────┤
│ status       │ uint8   │ 0=OK, 1=NOT_FOUND, etc   │
│ account_code │ string  │ Echo account requested   │
│ balance      │ decimal │ Minor units (cents/etc)  │
│ held         │ decimal │ Amount in transit        │
│ available    │ decimal │ balance - held           │
│ currency     │ string  │ "VND", etc.              │
│ as_of        │ uint64  │ Server timestamp         │
└──────────────┴─────────┴──────────────────────────┘
```

### 7. ERROR (0xFF) — Error Response

**Sent by:** C server → Android client (on any error)

```
┌──────────────┬────────┬───────────────────────┐
│ Field        │ Type   │ Notes                 │
├──────────────┼────────┼───────────────────────┤
│ error_code   │ uint32 │ See Error Codes       │
│ message      │ string │ Human-readable reason │
│ correlation_id│uint64 │ Links to request      │
└──────────────┴────────┴───────────────────────┘

Error Codes:
  0x0001 = AUTH_FAILED (invalid credentials)
  0x0002 = SESSION_EXPIRED (re-authenticate)
  0x0003 = INVALID_MESSAGE (malformed frame)
  0x0004 = CHECKSUM_ERROR (corrupted payload)
  0x0005 = INSUFFICIENT_BALANCE (topup/transfer)
  0x0006 = ACCOUNT_NOT_FOUND (invalid account code)
  0x0007 = ACCOUNT_CLOSED (status != OPEN)
  0x0008 = DUPLICATE_TXN (ref_id collision, try again)
  0x0009 = JAVA_ERROR (ledger exception, see message)
  0xFFFF = UNKNOWN_ERROR (catch-all)
```

---

## Session Management

**Authentication Flow:**

```
Android                          C Server                Java Ledger
  │                               │                           │
  ├─ AUTH(user_id, token) ───────>│                           │
  │                               ├─ Validate token          │
  │                               ├─ Generate session_id      │
  │<──── AUTH_RESPONSE ────────────┤                           │
  │     (session_id, expire_at)    │                           │
  │                               │                           │
  ├─ TXN(session_id, ref_id) ────>│                           │
  │                               ├─ Validate session_id      │
  │                               ├─ Parse TxnRequest ───────>│
  │                               │                    (REST   │
  │                               │                     call)  │
  │                               │<─ TxnResponse ────────────┤
  │<──── TXN_RESPONSE ─────────────┤                           │
  │     (trans_id, status)         │                           │
```

**Session Expiry:** 
- Issued with 24-hour TTL
- Client must re-AUTH if session_expired error received
- C server validates session_id on each transaction

---

## Idempotency & Deduplication

**Reference ID (ref_id):**
- Generated by Android client (e.g., UUID or `user_id + nanotime`)
- Java ledger stores in `coa_trans.ref_id` (UNIQUE constraint)
- If duplicate ref_id received within 5 minutes:
  - C server caches result (TxnResponse with status=3 DUPLICATE)
  - Java ledger returns cached trans_id
  - No double-posting of transactions

**Cache Key:** `ref_id + transaction_type`  
**Retention:** 5 minutes (or configurable)

---

## Double-Entry Validation

All transactions posted to Java ledger as 2+ ledger lines:

**TRANSFER (user_wallet → user_wallet):**
```
Line 1: Debit  from_account   amount
Line 2: Credit to_account     amount
```

**TOP_UP (topup_transit → user_wallet):**
```
Line 1: Debit  topup_transit       amount
Line 2: Credit user_wallet_subacct amount
```

**PAYMENT (user_wallet → merchant_wallet):**
```
Line 1: Debit  user_wallet         amount
Line 2: Credit merchant_wallet     amount
Line 3: Debit  merchant_wallet     fee (if applicable)
Line 4: Credit payment_fee_income  fee
```

C server receives binary TxnRequest, constructs 2+ ledger lines, sends to Java REST API:
```
POST http://localhost:8090/api/coa/transaction
{
  "ref_id": "abc1234567",
  "lines": [
    { "account_code": "1111000001", "debit_minor": 10000, "credit_minor": 0 },
    { "account_code": "2222000001", "debit_minor": 0, "credit_minor": 10000 }
  ]
}
```

Java validates double-entry invariant (Σ debits = Σ credits) before posting.

---

## Checksum Validation

**CRC64-ECMA** (polynomial: 0x42F0E1EBA9EA3693)

```
Checksum(payload) = CRC64_ECMA(message_type || correlation_id || payload)
```

- Appended to every frame (8 bytes)
- C server validates on receive
- If checksum fails, respond with ERROR(0x0004)

---

## Examples

### Example 1: TRANSFER Transaction

**Android → C Server:**
```
Frame Header:
  00 00 00 5F            [frame_length = 95 bytes]
  10                     [message_type = TRANSACTION]
  00 00 00 00 12AB CD EF [correlation_id]
  
Payload (TxnRequest):
  02                     [txn_type = TRANSFER]
  00 10 61 62 63 31 32 33 34 35 36 37 38 39 30 31
                         [ref_id = "abc1234567890"]
  00 0A 31 31 31 31 30 30 30 30 30 31
                         [from_account = "1111000001"]
  00 0A 32 32 32 32 30 30 30 30 30 31
                         [to_account = "2222000001"]
  00 00 00 00 00 00 27 10 FF
                         [amount = 10000, exponent = -2 (100.00)]
  00 03 56 4E 44         [currency = "VND"]
  00 08 54 72 61 6E 73 66 65 72
                         [memo = "Transfer"]
  00 00 00 00 00 00 00 00
                         [party_mid = 0]
  00 00 01 7F 59 AB CD EF
                         [timestamp]

CRC64:
  12 AB CD EF 56 78 90 AB [checksum]
```

**C Server → Android:**
```
Frame Header:
  00 00 00 3F            [frame_length = 63 bytes]
  11                     [message_type = TXN_RESPONSE]
  00 00 00 00 12AB CD EF [correlation_id (echoed)]

Payload (TxnResponse):
  00                     [status = SUCCESS]
  00 00 00 00 00 00 00 64
                         [trans_id = 100 (from Java ledger)]
  00 10 61 62 63 31 32 33 34 35 36 37 38 39 30 31
                         [ref_id = "abc1234567890" (echo)]
  00 02 4F 4B            [message = "OK"]
  FF FF FF FF FF FF 2C B0 FF
                         [balance = 5000000, exponent = -2 (50000.00 VND)]
  00 00 01 7F 59 AB CD EF
                         [timestamp]

CRC64:
  AB CD EF 12 34 56 78 90
```

---

## Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| Frame parsing latency | < 100 μs | C server overhead |
| TxnRequest → TxnResponse | < 500 ms | Includes Java HTTP call |
| Balance query | < 100 ms | Via Disruptor in Java |
| Session validation | < 10 μs | In-memory lookup |
| Checksum validation | < 50 μs | CRC64 on payload |

---

## Security Considerations

1. **Authentication:** HMAC-SHA256(user_id + device_id + timestamp, secret)
2. **Session Tokens:** 32-byte opaque random (not user_id directly)
3. **Checksums:** CRC64 detects corruption; use TLS for eavesdropping protection
4. **Idempotency:** Prevents double-posting via ref_id deduplication
5. **Rate Limiting:** C server should throttle by session_id (e.g., 100 req/min)

---

## Implementation Notes

**C Server Responsibilities:**
1. Parse binary frame (frame_length, message_type, correlation_id, payload, checksum)
2. Validate checksum (reject if mismatch → ERROR 0x0004)
3. Route message_type to handler (AUTH, TRANSACTION, BALANCE_QUERY, etc.)
4. Maintain session cache (session_id → user_id + expiry)
5. Construct TxnRequest → 2+ double-entry ledger lines
6. Call Java REST API (POST /api/coa/transaction with ledger lines)
7. Serialize response back to binary (TxnResponse with correlation_id)

**Java Server (Existing):**
- REST endpoint: `POST /api/coa/transaction` (already exists)
- Accept ledger lines JSON
- Validate double-entry invariant
- Insert into coa_trans + coa_trans_data
- Return trans_id + status
- C server translates to binary TxnResponse

---

## Future Extensions

- **Streaming Mode:** For high-frequency traders (e.g., settlement runs)
- **Compression:** Optional gzip on frame payload for large batches
- **TLS 1.3:** Encrypted wire protocol variant (prod requirement)
- **Rate Limiting Headers:** Tokens/quota in each response frame

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-06-03 | Initial spec (TRANSFER, TOP_UP, PAYMENT, BALANCE_QUERY) |

