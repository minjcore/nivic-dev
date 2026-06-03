# ADR 003: Neo-bank domain — single brain, `mid`, and `merchant_id`

## Status

Accepted

## Context

Product framing treats the wallet engine as a **single bank boundary** (no separate external-bank integration in this codebase). Discussions converged on:

- One **source of truth** for money movement (verify → idempotency → WAL → ledger / payment projection).
- One numeric **`mid` namespace** on the wire for holders, merchants, and an internal **treasury** leg.
- For **merchants**, the wire field **`mid` is the same value as `merchant_id`** in persistence (`wallet_mid_secret.mid`, `merchant_config.mid`).

## Decision

### 0. Rail scope

This ADR describes the **servlet CORE rail** ([`java/`](../../java/)). The repo also has a **Wire TCP CORE rail** ([`saving/`](../../saving/)) with the same product role (money, intents, `mid`) but different transport and tables. See [ADR 004: Dual CORE rails](004-dual-core-rails-java-and-saving.md).

“Single brain” below means **one authoritative pipeline per rail** — not that servlet and saving share one database or one ledger table.

### 1. Single brain (SoT) — servlet rail

All customer-liability truth on the **servlet rail** flows through one pipeline:

`SevletWalletPayloadServlet` → `WalletVerificationService` → `WalletAcceptService.claimAndPersist` → `WalService` → `LedgerService` / `PaymentLedger`.

Do **not** maintain a second authoritative balance or payment hub ledger **on this rail** that can diverge from this path. Future servlet-side extensions submit **commands** into the same pipeline; they do not own SoT. (Wire-app money on the **saving rail** is documented in ADR 004.)

Code: [`SevletWalletPayloadServlet.java`](../../java/src/main/java/dev/nivic/sevlet/SevletWalletPayloadServlet.java), [`WalletAcceptService.java`](../../java/src/main/java/dev/nivic/payment/WalletAcceptService.java).

### 2. `mid` on the wire

| Party | Wire `mid` | Notes |
|-------|------------|--------|
| Merchant (signing tenant) | **`merchant_id`** | Equals `wallet_mid_secret.mid`; HMAC secret is keyed by this id. |
| Holder user | Wallet party id | Same wire field; not named `merchant_id` in product language. |
| Treasury / omnibus | `MidConventions.TREASURY_MID` (`Long.MAX_VALUE`) | Reserved; system-signed only; same ledger mechanics as other legs. |

Classification helpers: [`PartyKind`](../../java/src/main/java/dev/nivic/party/PartyKind.java), [`MidConventions`](../../java/src/main/java/dev/nivic/party/MidConventions.java).

Payload record: [`SevletWalletPayload`](../../java/src/main/java/dev/nivic/sevlet/SevletWalletPayload.java). Codec: [`SevletWalletCodec`](../../java/src/main/java/dev/nivic/sevlet/SevletWalletCodec.java). Wire layout detail: [ADR 001](001-sevlet-wallet-wire.md).

### 3. Persistence mapping

| Concept | Tables / notes |
|---------|----------------|
| Merchant secret + order mode | `wallet_mid_secret` (`mid` = `merchant_id`) |
| Optional merchant UI / flags | `merchant_config` (`mid` = `merchant_id`) |
| Ledger (posted movement) | `led_wallet` (+ `acct_journal_*` if enabled) |
| Intents / two-phase payment | `led_payment` (optional rename to `payment_transaction` — separate backlog) |

Column comments on `mid`: [`01_wallet_mid_secret.sql`](../../java/src/main/resources/db/schema/01_wallet_mid_secret.sql), [`09_merchant_config.sql`](../../java/src/main/resources/db/schema/09_merchant_config.sql).

### 4. Bootstrap first merchant

Seed script (dev-only default secret): [`01_first_mid.sql`](../../java/src/main/resources/db/seed/01_first_mid.sql) — `merchant_id` / wire `mid` = **1**. See also [`java/README.md`](../../java/README.md) (Database + Neo-bank section).

### 5. Known limitation: idempotency before WAL

`wallet_idempotency` claims `(mid, request_id)` before `WalService.append`. If WAL or projection fails after the claim, retries may see **409** while the first attempt returned **5xx**. Mitigation backlog: WAL replay worker, reconciliation, or reordering claim vs append (trade-offs). See `WalletAcceptService.claimAndPersist`.

## Related

- [ADR 004: Dual CORE rails (java + saving)](004-dual-core-rails-java-and-saving.md)
- [ADR 001: Wire layout vs mental model](001-sevlet-wallet-wire.md)
- [Product principles](../PRODUCT_PRINCIPLES.md) — WAL / ledger narrative
- Generated schema: `java/docs/generated/schema.md` (after `./gendocs.sh`)

## Consequences

- Documentation and APIs should say **`merchant_id`** when the signing party is a merchant; **`mid`** remains the wire field name.
- Deployers must allocate real merchant ids in the **merchant band** (`MidConventions.MERCHANT_BAND_MIN` …) if they rely on `PartyKind.MERCHANT`; demo / seed `mid = 1` is treated as **USER** band by default conventions.
- Renames (`payment_ledger` → `payment_transaction`, `merchant` package → `party`) remain **out of scope** until a scheduled DB migration.
