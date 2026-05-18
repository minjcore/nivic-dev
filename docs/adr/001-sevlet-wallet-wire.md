# ADR 001: Sevlet wallet wire vs compact mental model

## Status

Accepted

## Context

Internal discussions sometimes use a minimal frame:

`command(8) | mid(8) | requestId(8) | order_id(8) | amount(8)  | debit(4) | credit(4) | extraData | sig(32)`

The real on-the-wire layout is defined in code by `SevletWalletCodec` and differs in field order, extra fields, and where the MAC sits.

## Decision

Treat **`SevletWalletCodec`** ([`java/src/main/java/dev/nivic/sevlet/SevletWalletCodec.java`](../../java/src/main/java/dev/nivic/sevlet/SevletWalletCodec.java)) as the single source of truth. Summaries may use the mental model only when explicitly labeled as abbreviated.

### Actual wire layout (big-endian)

| Region | Bytes | Notes |
|--------|------:|--------|
| Header padding | 3 | Zeros; **not** included in HMAC input |
| `command` | 8 | First authenticated field; selects `WalletInputOp` |
| `mid` | 8 | Tenant key for HMAC secret lookup; for merchants equals **`merchant_id`** (see [ADR 003](003-neo-bank-mid-and-merchant-id.md)) |
| `requestId` | 8 | Idempotency with `mid` |
| `orderId` | 8 | Order-payment / strict duplicate checks |
| `amount` | 8 | Minor units per configured ledger currency |
| `debit` | 4 | Account index |
| `credit` | 4 | Account index |
| `extraData` | 0…N | Opaque; MAC covers from `command` through last `extraData` byte |
| `sig` | 32 | HMAC-SHA256(secret, signed region); trailing tail |

Fixed prefix before `extraData`: **51 bytes** (`PREFIX_BEFORE_EXTRA_LEN`). Minimum total wire with empty `extraData`: **51 + 32 = 83 bytes**.

### Mental model vs wire

- The mental model omits **`command`**, **`orderId`**, and the **3-byte pad**.
- It often shows **`sig` before `extraData`**; on wire, **`extraData` precedes `sig`**, and the MAC authenticates `command` … `extraData` only (excluding the leading pad).

### Cache line note

A common CPU L1/L2 cache line is **64 bytes**. The fixed head (51 B) plus empty `extraData` plus `sig` (**83 B** minimum) already spans **more than one** cache line. Claims that the whole frame fits one line apply only to **hypothetical shorter layouts**, not this codec. Hot-path isolation (Disruptor sequences vs payload blobs, padding between shards) remains a separate engineering concern; see [`docs/analytics/wal-to-clickhouse.md`](../analytics/wal-to-clickhouse.md) for persistence/analytics boundaries.

### Persistence mapping

- JDBC: `payment_ledger` / `wallet_ledger` store numeric columns plus `extra_data BYTEA`; order intents use `confirm_challenge` (32 B) echoed in CONFIRM `extraData` (see `ConfirmPayloadParser`).
- In-memory tests: `MemoryPaymentLedger` uses Java collections, not a packed ring struct.

## Related

- `extraData` length cap and profile constants: [`ExtraDataPolicy`](../../java/src/main/java/dev/nivic/sevlet/ExtraDataPolicy.java); servlet init `maxExtraDataBytes`.
- CONFIRM/REJECT `extraData` v0 prefix + optional tail: [`ConfirmPayloadParser`](../../java/src/main/java/dev/nivic/payment/ConfirmPayloadParser.java).
- Analytics path sketch: [`docs/analytics/wal-to-clickhouse.md`](../analytics/wal-to-clickhouse.md).

## Consequences

- Documentation and diagrams must not equate “64 B struct” with this wire format without qualification.
- Client generators and fuzzers must follow `SevletWalletCodec` offsets and HMAC rules.
