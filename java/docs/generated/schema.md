# Database Schema

Generated from `src/main/resources/db/schema.sql`.


## `wallet_mid_secret`
**Description:** Per-merchant HMAC. payment_check_order = order payment: enforce order_id on retries, WAL only (no immediate ledger/journal).

| Column | Type | Description |
|--------|------|-------------|
| `payment_check_order` | | True: order phase — same order_id on duplicate (mid,request_id); persist raw to WAL only until transaction replay. False: full persist in one step. |

## `merchant_config`
**Description:** Optional; see dev.nivic.merchant.MerchantConfig.

| Column | Type | Description |
|--------|------|-------------|
| `enabled` | | False rejects wallet traffic for this mid (after HMAC). |
| `intent_ttl_minutes` | | Order-intent TTL override; null = servlet default. |

## `wallet_idempotency`
**Description:** Dedupe (mid, request_id). order_id used for order-payment mids (compare on duplicate).

| Column | Type | Description |
|--------|------|-------------|
| `order_id` | | First-seen orderId; mismatched retry under order-payment mode → 409. |

## `wallet_ledger`
**Description:** One row per accepted Sevlet wallet message.

| Column | Type | Description |
|--------|------|-------------|
| `input` | | Wire command opcode (u64). |
| `amount_minor` | | Amount in ISO 4217 minor units for currency_code. |

## `payment_ledger`
**Description:** Initial intent and/or upsert after wallet_ledger settle; ON CONFLICT keeps order_id and created_at.

| Column | Type | Description |
|--------|------|-------------|
| `input` | | Wire command opcode (u64). |
| `order_id` | | From initial insert; appendAfterWallet does not replace. |
| `amount_minor` | | Amount in ISO 4217 minor units for currency_code. |
| `debit` | | Unset until settle/replay; no accounts at order-payment phase. |
| `credit` | | Unset until settle/replay. |
| `intent_status` | | See dev.nivic.ledger.CoreLedgerStatus (VARCHAR = Enum.name()); null = legacy row. |
| `confirm_challenge` | | 32-byte value echoed in CONFIRM / REJECT extraData. |

## `wallet_account_hold`

## `wallet_journal_entry`
**Description:** Journal voucher header; lines in wallet_journal_line.

| Column | Type | Description |
|--------|------|-------------|

## `wallet_journal_line`
**Description:** Balanced lines: debit account / credit account for wire amount.

| Column | Type | Description |
|--------|------|-------------|
