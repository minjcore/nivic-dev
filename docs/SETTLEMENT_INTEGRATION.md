# Settlement Integration: Wallet → Bank/Blockchain

## Overview

Settlement bridges **Wallet System** ↔ **Bank** / **Blockchain**:

```
User Wallet (1200)     Merchant Wallet (2200)     Hot Wallet (3500)
     ↓                         ↓                          ↓
  Withdraw            Daily Settlement              Rebalance
     ↓                         ↓                          ↓
Blockchain         Bank Transfer (SWIFT/ACH)    Cold Storage
```

---

## 1. Settlement Types

### Type 1: Merchant Daily Settlement
**Flow:** Merchant Wallet → Bank Account → Confirmed

```
Merchant earning 1000 USDT daily
├─ Wallet holds 1000 USDT (locked for settlement)
├─ Execute settlement:
│  ├─ Convert USDT → VND (ledger FX transaction)
│  ├─ Post to ledger: Merchant Wallet → Bank Account
│  └─ Initiate bank transfer via SWIFT/ACH
├─ Bank confirms receipt
└─ Mark settlement CONFIRMED
```

**State Machine:**
```
PENDING (merchant has 1000 USDT available)
  ↓
HOLD_REQUESTED (wallet holds 1000 USDT)
  ↓
POSTED (ledger updated, bank initiated)
  ↓
EXECUTING (bank processing)
  ↓
CONFIRMED (money in merchant bank account)
  ↓ (if bank fails)
FAILED_BANK (ledger is safe, wallet released)
```

### Type 2: User Crypto Withdrawal
**Flow:** User Wallet → Blockchain Wallet → Withdrawal

```
User wants 5 BTC from wallet
├─ Wallet holds 5 BTC (locked for withdrawal)
├─ Execute:
│  ├─ Post ledger: User Wallet → Crypto-Out Transit
│  └─ Initiate blockchain transfer to user address
├─ Blockchain confirms (6 blocks)
└─ Mark CONFIRMED
```

### Type 3: Hot Wallet Rebalancing
**Flow:** Hot Wallet → Cold Storage / Cold Storage → Hot Wallet

```
Hot wallet drops below threshold
├─ Trigger rebalance
├─ Move funds to cold storage
├─ Update ledger (Hot Wallet → Cold Storage Transit)
└─ Confirm once blockchain settled
```

---

## 2. Settlement Entity

```java
record Settlement(
    long id,
    long walletId,                 // Source wallet
    String settlementType,         // MERCHANT, WITHDRAWAL, REBALANCE
    String status,                 // PENDING → HOLD → POSTED → EXECUTING → CONFIRMED
    long amountMinor,
    String currency,
    String destinationBank,        // Bank code (SWIFT, ACH, LOCAL)
    String bankTransactionId,      // SWIFT/ACH/Blockchain tx hash
    long transactionId,            // coa_trans.id reference
    Instant createdAt,
    Instant confirmedAt
)
```

---

## 3. Settlement Flow Details

### Step 1: Initiate Settlement
```java
Settlement initiate(long walletId, long amountMinor, String type, String destination)
├─ Validate wallet has sufficient balance
├─ Call walletManager.holdBalance(walletId, settlementId, amount)
├─ Create settlement record (status=PENDING)
└─ Return settlement
```

### Step 2: Execute Settlement
```java
void execute(long settlementId)
├─ Lock settlement (prevent concurrent execution)
├─ Calculate FX conversion (if needed)
├─ Post ledger:
│  ├─ Debit source wallet (1200 or 2200)
│  ├─ Credit destination (Bank account 1150 or Blockchain 3500)
│  └─ Transaction ID = coa_trans.id
├─ Initiate bank/blockchain transfer
├─ Update status = EXECUTING
└─ Return transfer result
```

### Step 3: Confirm Settlement (Webhook)
```java
void confirm(long settlementId, String bankTransactionId)
├─ Verify settlement status = EXECUTING
├─ Update bankTransactionId
├─ Release hold: walletManager.captureHold(settlementId)
├─ Update status = CONFIRMED
└─ Log confirmation for audit
```

### Step 4: Handle Failure
```java
void fail(long settlementId, String reason)
├─ Settlement status = FAILED_BANK (not FAILED)
├─ Release hold: walletManager.releaseHold(settlementId)
├─ Wallet balance restored (hold released)
├─ Ledger remains POSTED (can be reversed manually if needed)
└─ Alert operations team
```

---

## 4. Database Schema

```sql
-- Settlement (integrates with wallet_transfer)
CREATE TABLE settlement (
  id                  BIGINT       PRIMARY KEY,
  wallet_id           BIGINT       NOT NULL REFERENCES wallet(id),
  settlement_type     VARCHAR(16)  NOT NULL,  -- MERCHANT, WITHDRAWAL, REBALANCE
  status              VARCHAR(16)  NOT NULL,  -- PENDING, HOLD, POSTED, EXECUTING, CONFIRMED
  amount_minor        BIGINT       NOT NULL,
  currency            VARCHAR(10)  NOT NULL,
  destination_bank    VARCHAR(32),            -- Bank code or blockchain
  bank_transaction_id VARCHAR(128),           -- SWIFT/ACH/Blockchain tx hash
  transaction_id      BIGINT UNIQUE,          -- coa_trans.id
  wallet_hold_id      BIGINT REFERENCES wallet_hold(id),
  created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
  confirmed_at        TIMESTAMP
);

-- Bank account registry
CREATE TABLE bank_account (
  id              BIGINT       PRIMARY KEY,
  wallet_id       BIGINT       NOT NULL REFERENCES wallet(id),
  account_number  VARCHAR(34)  NOT NULL,
  bank_code       VARCHAR(16)  NOT NULL,     -- SWIFT, ACH, LOCAL
  account_holder  VARCHAR(256),
  status          VARCHAR(16)  NOT NULL,     -- ACTIVE, INACTIVE
  verified_at     TIMESTAMP,
  created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- Blockchain address (for withdrawals)
CREATE TABLE blockchain_address (
  id              BIGINT       PRIMARY KEY,
  wallet_id       BIGINT       NOT NULL REFERENCES wallet(id),
  currency        VARCHAR(10)  NOT NULL,     -- BTC, ETH, etc
  address         VARCHAR(256) NOT NULL,
  status          VARCHAR(16)  NOT NULL,     -- ACTIVE, ARCHIVED
  verified_at     TIMESTAMP,
  created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);
```

---

## 5. Ledger Integration

### Merchant Settlement Journal Entry
```
Merchant wallet: 2200 (LIABILITY, credit-normal)
Bank account: 1150 (ASSET, debit-normal)

Entry:
  DR 1150 (Bank Vietcombank)         / CR 2200 (Merchant Wallet)
  1,000,000 VND                       / 1,000,000 VND
  
Ref: settlement-{settlementId}
Memo: Daily settlement: {merchant-name} 1000 USDT → 25,000,000 VND
```

### User Withdrawal Journal Entry
```
User wallet: 1200 (ASSET, debit-normal)
Blockchain output: 3500 (TRANSIT, credit-normal)

Entry:
  DR 3500 (Crypto Out Transit)       / CR 1200 (User Wallet)
  5 BTC                              / 5 BTC
  
Ref: settlement-{settlementId}
Memo: Withdrawal: 5 BTC to {user-address}
```

---

## 6. REST API

```bash
# Initiate settlement
POST /api/settlement/initiate
{
  "walletId": 123,
  "type": "MERCHANT",
  "amountMinor": 1000000000,
  "currency": "USDT",
  "destination": "vietcombank"
}
→ {id: 456, status: "PENDING", ...}

# Execute settlement (posts to ledger, initiates bank/blockchain)
POST /api/settlement/{id}/execute
→ {transactionId: 789, bankTransactionId: "SWIFT-123", status: "EXECUTING"}

# Confirm settlement (bank/blockchain webhook callback)
POST /api/settlement/{id}/confirm?bankTransactionId=SWIFT-123&hash=0x1234
→ {status: "CONFIRMED"}

# Get settlement status
GET /api/settlement/{id}
→ {id, status, amountMinor, currency, bankTransactionId, confirmedAt}

# List pending settlements
GET /api/settlement/pending
→ [{id, status, amountMinor, destination}...]

# Retry failed settlement
POST /api/settlement/{id}/retry
→ {status: "HOLD_REQUESTED", ...}
```

---

## 7. Wallet Hold Integration

Settlement uses **wallet holds** to prevent double-settlement:

```
Settlement Request
  ↓
Hold wallet balance (via walletManager.holdBalance)
  ↓
Execute settlement (post ledger, initiate transfer)
  ↓
Bank confirms → Capture hold (walletManager.captureHold)
  ↓
Settlement CONFIRMED
```

If bank fails → Release hold → Wallet balance restored → Retry later

---

## 8. State Machine Diagram

```
┌─────────────┐
│   PENDING   │ ← Initiation, wallet has balance
└──────┬──────┘
       │ [hold requested]
       ↓
┌──────────────┐
│ HOLD_REQUESTED│ ← Hold wallet balance
└──────┬───────┘
       │ [execute]
       ↓
┌──────────────┐
│    POSTED    │ ← Ledger entry created
└──────┬───────┘
       │ [initiate transfer]
       ↓
┌──────────────┐
│  EXECUTING   │ ← Bank/Blockchain processing
└──────┬───────┘
       │
   ┌───┴──────────┐
   │              │
   ↓ [confirmed]  ↓ [failed]
┌──────────────┐ ┌──────────────┐
│  CONFIRMED   │ │ FAILED_BANK  │
└──────────────┘ └──────┬───────┘
                        │ [hold released]
                        ↓ [can retry]
                   ┌──────────────┐
                   │   PENDING    │
                   └──────────────┘
```

---

## 9. Failure Handling

### Bank Transfer Fails
```
Settlement status = EXECUTING
Bank returns error
  ↓
Update settlement.status = FAILED_BANK
Release hold: walletManager.releaseHold(settlementId)
  ↓
Wallet balance restored (hold removed)
Ledger transaction remains (marked for manual review)
Alert operations team
  ↓
Merchant can retry settlement
```

### Blockchain Withdrawal Fails
```
Settlement status = EXECUTING
Blockchain tx reverted (not enough gas, invalid address, etc)
  ↓
Detect failure (polling, webhook, timeout)
Update settlement.status = FAILED_BANK
Release hold
  ↓
User can retry with different address or amount
```

---

## 10. Compliance & Audit

- ✅ All settlements in ledger (immutable audit trail)
- ✅ Ref_id links settlement → journal transaction
- ✅ Bank transaction IDs recorded (for reconciliation)
- ✅ Timestamps track full lifecycle
- ✅ Failed settlements preserved (not deleted)
- ✅ Holds prevent over-commitment during processing

---

## 11. Implementation Order

**Phase 1: Core Settlement**
- Settlement entity + JDBC CRUD
- Hold integration with WalletManager
- Ledger posting logic

**Phase 2: Bank Integration**
- BankGateway integration
- Settlement controller endpoints
- Webhook callback handling

**Phase 3: Blockchain Integration**
- Blockchain address registry
- Withdrawal settlement flow
- Blockchain listener for confirmations

**Phase 4: Operations**
- Settlement status dashboard
- Manual retry / reverse operations
- Reconciliation reports
