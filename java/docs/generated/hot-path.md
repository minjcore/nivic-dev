# Hot Path: Payment Acceptance Flow

The critical production path for every payment:

```
Client → POST binary → SevletWalletPayloadServlet
  → BinaryBodyReader (cap: maxBodyBytes)
  → SevletWalletCodec.decode (wire format → payload record)
  → ExtraDataPolicy.validateLength (cap: maxExtraDataBytes)
  → WalletVerificationService.verify
    → JdbcMidSecretResolver.requireProfile(mid) → secretKey
    → SevletWalletHmac.verify(raw, secretKey) ← HMAC-SHA256
  → WalletAcceptService.claimAndPersist(raw, payload)
    → MidProfile check (enabled?)
    → IdempotencyGate.claimFirst(mid, requestId, orderId, orderPaymentMode)
    → [if duplicate] return 409 CONFLICT
    → WalService.append(raw)  ← crash-safe WAL first
    → [if immediate settle]
      → LedgerService.record(payload, currency)
        → WalletLedger.append(payload, currency)
        → WalletJournal.append(payload, currency)  ← double-entry
      → PaymentLedger.appendAfterWallet(payload, currency)
    → [if order intent]
      → PaymentLedger.append(payload, currency, ctx)  ← intent with challenge
      → return challenge in JSON
    → [if confirm]
      → ConfirmPayloadParser.validateExtra(extraData)
      → LedgerService.record(payload, currency)
      → PaymentLedger.settleIntentByOrder(payload, currency)
  → JSON response (200 OK)
```

## Test Commands

| Command | Purpose |
|---------|---------|
| `./build.sh` | Full pipeline: gencode → hot-path tests → all tests → gendocs → package |
| `mvn test -Dtest.groups=hot-path` | Only hot-path tests (fast feedback) |
| `mvn test` | All tests |
| `./gen.sh` | Regenerate Java constants from SQL schema |
| `./gendocs.sh` | Regenerate documentation |

## Tag Hierarchy

- `@Tag("hot-path")` → critical payment flow tests (run FIRST)
- All other tests → unit/component tests (run after hot-path passes)
