# Sevlet Wallet — Nivic Dev

Digital wallet payment processing engine (Java 21, Jakarta Servlet 6.0, PostgreSQL).

## Quick Start

```bash
# Không cần DB — chạy in-memory ngay
./dev-start.sh

# Gửi test payment (terminal 2)
python3 -c "
import struct, urllib.request
body = struct.pack('>3x qqqqq ii 32s', 0, 1, 100, 200, 50000, 1, 2, b'\x00'*32)
req = urllib.request.Request('http://localhost:8080/sevlet/wallet/payload', data=body)
print(urllib.request.urlopen(req).read().decode())
"
```

## Build Pipeline

```bash
./build.sh     # 5 phases: gencode → hot-path test → all tests → gendocs → package
```

| Phase | Lệnh | Output |
|-------|------|--------|
| 1. Code Gen | `./gen.sh` | `DbSchema.java` — constants từ SQL schema |
| 2. Hot-Path | `mvn test -Dtest.groups=hot-path` | 10 tests, < 1s |
| 3. All Tests | `mvn test` | 14 tests |
| 4. Doc Gen | `./gendocs.sh` | `docs/generated/schema.md`, `packages.md`, `hot-path.md` |
| 5. Package | `mvn package -DskipTests` | `target/sevlet-wallet-1.0.0-SNAPSHOT.war` |

## Hot-Path Tests

`@Tag("hot-path")` — 10 tests end-to-end, không cần DB:

| Test | Mô tả |
|------|-------|
| `fullPostTransfer` | TRANSFER → HMAC → idempotency → WAL → ledger → journal |
| `reversalSamePathAsTransfer` | REVERSAL opcode same path |
| `orderIntentThenConfirm` | Order intent → challenge → CONFIRM → settle |
| `orderIntentThenReject` | Order intent → challenge → REJECT → cancelled |
| `idempotencyDuplicateRejected` | 409 on retry |
| `badHmacRejected` | 401 on corrupted signature |
| `unknownMidRejected` | 403 on unknown merchant |
| `twoPhaseCrossMidRejection` | orderId mismatch throws |
| `extraDataWithinLimits` | Boundary check |
| `minWireLengthEnforced` | Minimum wire size enforced |

```bash
# Fast feedback — chạy trước khi commit
mvn test -Dtest.groups=hot-path
```

## Dev Server (in-memory, no PostgreSQL)

```bash
./dev-start.sh
# → http://localhost:8080/sevlet/wallet/payload
```

Tất cả storage mode `memory`, không cần DB, WAL ghi vào `java.io.tmpdir`.

## Code Generation

```bash
./gen.sh
# → src/main/java/dev/nivic/db/DbSchema.java
```

Parse `src/main/resources/db/schema.sql` → constants type-safe:
```java
DbSchema.WALLET_LEDGER           // "wallet_ledger"
DbSchema.WALLET_LEDGER_.AMOUNT_MINOR  // "amount_minor"
```

## Documentation Generation

```bash
./gendocs.sh
# → docs/generated/schema.md      — bảng + comments
# → docs/generated/packages.md    — package tree
# → docs/generated/hot-path.md    — flow + test commands
```

## Database (production)

Cần PostgreSQL, copy `.env.example` → `.env`:

```bash
cp .env.example .env
# Sửa JDBC_URL, JDBC_USER, JDBC_PASSWORD

# Apply schema:
psql "$JDBC_URL" -f src/main/resources/db/schema.sql

# (Tuỳ chọn) Tạo mid đầu tiên — mid=1, HMAC key 32 byte toàn 0 (chỉ dev):
psql "$JDBC_URL" -f src/main/resources/db/seed/01_first_mid.sql

# Chạy với DB:
./dev-server.sh
# hoặc: mvn clean package cargo:run
```

## API

### `POST /sevlet/wallet/payload`

Binary body (SevletWalletCodec format), `Content-Type: application/octet-stream`.

**Wire format:**

```
| pad(3) | command(8) | mid(8) | requestId(8) | orderId(8) | amount(8) | debit(4) | credit(4) | extraData(N) | sig(32) |
|        |<----------------------- HMAC-SHA256 input -------------------------->|
```

- `command`: 0=TRANSFER, 1=CONFIRM, 2=REJECT, 3=REVERSAL
- `mid`: **merchant_id** when the signing party is a merchant (same as `wallet_mid_secret.mid`); holder users use the same wire field as wallet party id (`MidConventions`).
- `amount`: ISO 4217 minor units (50000 = $500.00)
- `sig`: 32-byte HMAC-SHA256
- Min wire size: 83 bytes (extraData rỗng; `PREFIX_BEFORE_EXTRA_LEN` + `sig`)

**Response:** JSON 200 OK / 400/401/403/409/413.

## Architecture

```
POST /sevlet/wallet/payload
  → BinaryBodyReader (maxBodyBytes)
  → SevletWalletCodec.decode
  → ExtraDataPolicy.validateLength
  → WalletVerificationService.verify (HMAC-SHA256)
  → WalletAcceptService.claimAndPersist
    → IdempotencyGate (dedup mid+requestId)
    → WalService.append (file WAL, crash-safe)
    → LedgerService.record (wallet_ledger + journal)
    → PaymentLedger (appendAfterWallet / settleIntentByOrder)
```

Packages: `sevlet` (HTTP), `payment` (business), `wal` (WAL), `ledger`/`journal` (accounting), `command` (opcodes), `money` (value types), `party` (neo-bank `mid` conventions), `wal` (WAL signing).

## Neo-bank domain alignment

Canonical write-up: [**ADR 003**](../docs/adr/003-neo-bank-mid-and-merchant-id.md) (single brain, `mid` / `merchant_id`, treasury, bootstrap seed, idempotency caveat).

**Single brain (source of truth).** All money movement is decided in one place: verify → idempotency → WAL → `wallet_ledger` / journal / `payment_ledger` (see `WalletAcceptService`). Do not maintain a second “balance” or payment-hub ledger that can disagree with the core append path. Future bank rails or adapters should only **submit commands** into this pipeline, not own customer liability truth.

**One `mid` namespace.** End-users, merchants, and the internal **treasury / omnibus** leg are all identified by a `long` `mid` (see `dev.nivic.party.MidConventions`, `PartyKind`). **Merchant rows:** `mid` **is** `merchant_id` (DB + wire). In this scope the product **is** the bank: there is **no** separate external-bank integration layer—treasury is just **one reserved `mid`** (`TREASURY_MID`, same ledger mechanics as a merchant leg; system-signed only, not a second schema).

- **P2P:** debit/credit reference two user mids (or user ↔ user).
- **Leg vs treasury:** when cash-in/out or omnibus is modeled, one leg uses `MidConventions.TREASURY_MID` alongside holder mids.

**Deferred renames (migration backlog).** Renaming `payment_ledger` → `payment_transaction`, or renaming `merchant` types/packages → `party`, is intentionally **not** done here—requires coordinated SQL + JDBC + codegen (`DbSchema`). Track when you schedule a migration window.

**Known limitation: idempotency before WAL.** `wallet_idempotency` claims `(mid, requestId)` before `WalService.append`. If WAL or ledger projection fails after the insert, clients may see **5xx** while retries get **409 Conflict**—there is no automatic replay yet. Mitigations: WAL-backed replay workers, reconciliation jobs, or restructuring claim order (trade-offs documented with `WalletAcceptService.claimAndPersist`).

## File Reference

| File | Purpose |
|------|---------|
| `build.sh` | Central build: gencode → test → gendocs → package |
| `gen.sh` | SQL schema → Java constants |
| `gendocs.sh` | Source → markdown docs |
| `dev-start.sh` | Dev server (in-memory) |
| `dev-server.sh` | Dev server (PostgreSQL) |
| `src/main/java/.../cli/DevServer.java` | Embedded Undertow, all-memory |
| `src/test/java/.../payment/HotPathTest.java` | 10 hot-path tests |
| `src/main/java/.../party/MidConventions.java` | Neo-bank `mid` bands + treasury id |
| `src/main/java/.../db/DbSchema.java` | Generated table/column constants |
| `src/main/resources/db/schema.sql` | Full PostgreSQL DDL |
| `src/main/resources/db/seed/01_first_mid.sql` | Bootstrap mid=1 + dev HMAC key |
| `docs/generated/` | Auto-generated docs |
