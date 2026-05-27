# Wire payment frames (reference) / Khung gói tin thanh toán Wire

Companion to [`../architecture/wire-payment-multitenant.md`](../architecture/wire-payment-multitenant.md). Opcode values match `saving/include/wire.h` and `wire-android/.../WireFrame.kt`.

## Frame envelope

| Field | Size | Endian | Notes |
|-------|------|--------|-------|
| `packet_length` | 4 | BE | `body_len + 32` |
| `packet_type` | 1 | — | Command |
| `sequence` | 4 | BE | Request/response pairing |
| `body` | variable | — | Command-specific |
| `hmac` | 32 | — | HMAC-SHA256 over header+body (see `WireFrame.kt`) |

## Payment-related opcodes (implemented)

| Hex | Symbol | Body layout |
|-----|--------|-------------|
| `0x20` | CREATE_INTENT | `[merchant_token:32][request_id:8][order_id:8][amount:8][gateway_order_id bytes…]` |
| `0x21` | PAY_INTENT | `[customer_token:32][merchant_id:4][request_id:8][totp_code:4]` |
| `0x29` | CONFIRM_INTENT | `[customer_token:32][merchant_id:4][request_id:8]` |
| `0x82` | ACK | `[code:1][payload…]` |

## Planned: USER_POST_PROOF (example layout)

Not wired in `saving` yet — use HTTP `POST /orders/{oid}/confirm` until added.

| Offset | Field | Size |
|--------|-------|------|
| 0 | `intent_key` | 16 |
| 16 | `tenant_id` | 4 |
| 20 | `bill_id` | 8 |
| 28 | `amount` | 8 |
| 36 | `core_sig` | 64 |

Suggested `packet_type`: `0x2A` (reserve; do not collide with production opcodes without updating all clients).
