# Settlement Integration Testing Guide

## Manual Testing via curl

### 1. Create Merchant Wallet

```bash
curl -X POST http://localhost:8095/api/wallets \
  -H "Content-Type: application/json" \
  -d '{
    "uid": "merchant-001",
    "walletType": "MERCHANT",
    "currency": "USDT",
    "accountCode": "2200"
  }'

# Response:
# {
#   "id": 1717500000001,
#   "uid": "merchant-001",
#   "walletType": "MERCHANT",
#   "status": "ACTIVE",
#   "balanceMinor": 0,
#   "currencyCode": "USDT",
#   "accountCode": "2200",
#   "version": 0,
#   "createdAt": "2026-06-04T15:45:00.123Z",
#   "lastActivityAt": null
# }
```

### 2. Add Balance to Wallet (via SQL)

```sql
UPDATE wallet SET balance_minor = 1000000000 WHERE uid = 'merchant-001';
-- 1000 USDT (1000 * 10^18)
```

### 3. Initiate Settlement

```bash
curl -X POST http://localhost:8095/api/settlement/wallet/initiate \
  -H "Content-Type: application/json" \
  -d '{
    "walletId": 1717500000001,
    "amountMinor": 1000000000,
    "type": "MERCHANT",
    "currency": "USDT",
    "destination": "vietcombank"
  }'

# Response:
# {
#   "id": 1717500000002,
#   "walletId": 1717500000001,
#   "settlementType": "MERCHANT",
#   "status": "PENDING",
#   "amountMinor": 1000000000,
#   "currency": "USDT",
#   "destinationBank": "vietcombank",
#   "bankTransactionId": null,
#   "transactionId": null,
#   "walletHoldId": null,
#   "createdAt": "2026-06-04T15:45:30.123Z",
#   "confirmedAt": null
# }
```

### 4. Hold Wallet Balance

```bash
curl -X POST http://localhost:8095/api/settlement/wallet/1717500000002/hold

# Response:
# {
#   "id": 1717500000002,
#   "status": "HOLD",
#   "walletHoldId": 1717500000003,
#   ...
# }
```

### 5. Post to Ledger

```bash
# Simulate posting a journal transaction
curl -X POST "http://localhost:8095/api/settlement/wallet/1717500000002/post?transactionId=9876543210" 

# Response:
# {
#   "id": 1717500000002,
#   "status": "POSTED",
#   "transactionId": 9876543210,
#   ...
# }
```

### 6. Execute Settlement (Initiate Bank Transfer)

```bash
curl -X POST "http://localhost:8095/api/settlement/1717500000002/execute?bankTransactionId=SWIFT-001"

# Response:
# {
#   "id": 1717500000002,
#   "status": "EXECUTING",
#   "bankTransactionId": "SWIFT-001",
#   ...
# }
```

### 7. Confirm Settlement (Webhook Callback)

```bash
curl -X POST "http://localhost:8095/api/settlement/1717500000002/confirm?bankTransactionId=SWIFT-001-CONFIRMED"

# Response:
# {
#   "id": 1717500000002,
#   "status": "CONFIRMED",
#   "confirmedAt": "2026-06-04T15:46:00.123Z",
#   ...
# }
```

### 8. Check Wallet Balance After Settlement

```bash
curl http://localhost:8095/api/wallets/1717500000001/balance

# Response:
# {
#   "total": 0,           # Captured from hold
#   "held": 0,
#   "available": 0
# }
```

---

## Complete Flow Verification

### Success Case (Merchant Daily Settlement)

```bash
# 1. Create wallet and add balance
curl -X POST http://localhost:8095/api/wallets \
  -H "Content-Type: application/json" \
  -d '{"uid": "test-merchant", "walletType": "MERCHANT", "currency": "USDT", "accountCode": "2200"}'

# Note the wallet ID from response (e.g., 1717500000001)
WALLET_ID=1717500000001

# Add balance via SQL
psql -U postgres -d gtelpay_new -c "UPDATE wallet SET balance_minor = 1000000000 WHERE id = $WALLET_ID"

# 2. Initiate settlement
RESPONSE=$(curl -s -X POST http://localhost:8095/api/settlement/wallet/initiate \
  -H "Content-Type: application/json" \
  -d "{\"walletId\": $WALLET_ID, \"amountMinor\": 1000000000, \"type\": \"MERCHANT\", \"currency\": \"USDT\", \"destination\": \"vietcombank\"}")

SETTLEMENT_ID=$(echo $RESPONSE | jq '.id')

# 3. Execute full flow
curl -X POST http://localhost:8095/api/settlement/wallet/$SETTLEMENT_ID/hold
curl -X POST http://localhost:8095/api/settlement/wallet/$SETTLEMENT_ID/post?transactionId=$(($(date +%s)000))
curl -X POST http://localhost:8095/api/settlement/$SETTLEMENT_ID/execute?bankTransactionId=SWIFT-$(date +%s)
curl -X POST http://localhost:8095/api/settlement/$SETTLEMENT_ID/confirm?bankTransactionId=SWIFT-$(date +%s)-CONFIRMED

# 4. Verify settlement is CONFIRMED
curl http://localhost:8095/api/settlement/$SETTLEMENT_ID | jq '.status'
# Output: "CONFIRMED"
```

### Failure & Retry Case

```bash
WALLET_ID=1717500000002
SETTLEMENT_ID=1717500000003

# 1. Initiate → Hold → Post → Execute
curl -X POST http://localhost:8095/api/settlement/wallet/initiate \
  -H "Content-Type: application/json" \
  -d "{\"walletId\": $WALLET_ID, \"amountMinor\": 500000000, \"type\": \"MERCHANT\", \"currency\": \"USDT\", \"destination\": \"vietcombank\"}"

curl -X POST http://localhost:8095/api/settlement/wallet/$SETTLEMENT_ID/hold
curl -X POST http://localhost:8095/api/settlement/wallet/$SETTLEMENT_ID/post?transactionId=$(($(date +%s)000))
curl -X POST http://localhost:8095/api/settlement/$SETTLEMENT_ID/execute?bankTransactionId=SWIFT-FAIL

# 2. Simulate failure
curl -X POST http://localhost:8095/api/settlement/wallet/$SETTLEMENT_ID/fail?reason="Bank+account+closed"

# Verify status is FAILED_BANK
curl http://localhost:8095/api/settlement/$SETTLEMENT_ID | jq '.status'
# Output: "FAILED_BANK"

# 3. Retry
curl -X POST http://localhost:8095/api/settlement/wallet/$SETTLEMENT_ID/retry

# Verify status is back to PENDING
curl http://localhost:8095/api/settlement/$SETTLEMENT_ID | jq '.status'
# Output: "PENDING"
```

---

## Query Endpoints

### List Pending Settlements

```bash
curl http://localhost:8095/api/settlement/pending | jq '.[] | {id, status, amountMinor}'
```

### List Settlements by Wallet

```bash
curl http://localhost:8095/api/settlement/wallet/1717500000001/list | jq '.[] | {id, status}'
```

### List Settlements by Status

```bash
curl http://localhost:8095/api/settlement/status/CONFIRMED | jq '.[] | {id, amountMinor}'
```

---

## Expected Database State

### After Successful Settlement

```sql
-- Settlement record
SELECT id, status, amount_minor, bank_transaction_id FROM settlement 
WHERE id = 1717500000002;

-- Wallet balance remains same (hold was captured)
SELECT id, balance_minor, (
  SELECT COALESCE(SUM(amount_minor), 0) 
  FROM wallet_hold 
  WHERE wallet_id = wallet.id AND status = 'ACTIVE'
) as held_amount
FROM wallet WHERE id = 1717500000001;
-- balance_minor: 0
-- held_amount: 0

-- Wallet hold record shows CAPTURED
SELECT id, status FROM wallet_hold WHERE transfer_id = 1717500000002;
-- status: CAPTURED
```

### After Failed Settlement (Before Retry)

```sql
-- Settlement status is FAILED_BANK
SELECT id, status FROM settlement WHERE id = 1717500000003;
-- status: FAILED_BANK

-- Wallet hold is RELEASED
SELECT id, status FROM wallet_hold WHERE transfer_id = 1717500000003;
-- status: RELEASED

-- Wallet balance restored (hold removed from calculation)
SELECT balance_minor - COALESCE(SUM(amount_minor), 0) as available
FROM wallet w LEFT JOIN wallet_hold h ON w.id = h.wallet_id AND h.status = 'ACTIVE'
WHERE w.id = 1717500000002
GROUP BY w.id;
-- available: 500000000 (restored)
```

---

## Troubleshooting

### Settlement Returns 404
**Issue:** Settlement endpoint not found
**Solution:** Ensure `SettlementManager` bean is registered in `LedgerConfig` and Spring is scanning the ledger package

### Wallet Hold Not Created
**Issue:** `walletHoldId` is null after `hold()` call
**Solution:** Check `wallet_hold` table exists; verify `JdbcWalletManager` initialization completed

### "Insufficient Balance" Error
**Issue:** Settlement initiate fails even with balance
**Solution:** Check balance was added correctly; verify BIGINT vs INT in wallet schema

### Bank Transaction ID Not Persisted
**Issue:** `bankTransactionId` is null after execute/confirm
**Solution:** Pass `bankTransactionId` query parameter; verify UPDATE query in execute/confirm methods
