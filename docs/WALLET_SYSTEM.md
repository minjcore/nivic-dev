# Wallet System Design

## Overview
Wallet là layer quản lý tiền giữa User/Merchant và Blockchain/Bank. **Tất cả chuyển tiền phải qua Wallet**.

```
Blockchain      Wallet System          Bank
   ↓               ↓↓↓                  ↓
Deposit    →  User/Merchant   →    Withdrawal
           →  Settlement      →
           →  Hot Wallet      →
```

---

## 1. Wallet Types

### User Wallet
- **Owner**: Cá nhân người dùng
- **Balance**: USDT, USDC, ETH, BTC, VND (multi-currency)
- **Usage**: Nhận tiền, chuyển đi, thanh toán
- **Limits**: KYC/AML tier-based

### Merchant Wallet
- **Owner**: Cửa hàng/Doanh nghiệp
- **Balance**: Tổng doanh thu theo tiền tệ
- **Usage**: Nhận thanh toán, settlement hàng ngày
- **Features**: Split/batching, auto-settlement

### Hot Wallet (Operational)
- **Owner**: Platform
- **Purpose**: Tạm giữ tiền trước settlement
- **Balance**: Tổng tiền sẽ rút
- **Security**: Multisig, cold store backup

### Transit Wallet (Internal)
- **Owner**: Platform
- **Purpose**: FX conversion, internal transfers
- **Balance**: Balancing account (giống 3500 ledger)

---

## 2. Wallet Entity

```java
record Wallet(
    long id,                          // Snowflake ID
    String uid,                       // User/Merchant ID
    String walletType,                // USER, MERCHANT, HOT, TRANSIT
    String status,                    // ACTIVE, FROZEN, CLOSED
    long balanceMinor,                // Current balance (wei/satoshi)
    String currencyCode,              // USDT, VND, etc.
    String accountCode,               // COA reference: 1200, 2200, etc
    Instant createdAt,
    Instant lastActivityAt
)
```

---

## 3. Core Operations

### A → B Transfer Flow

```
┌─────────────────────────────────────┐
│ Step 1: User A initiates transfer   │
│ - Debit A's Wallet (1200)           │
│ - Credit Transit (3500)             │
└─────────────────────────────────────┘
                ↓
┌─────────────────────────────────────┐
│ Step 2: FX Conversion (if needed)   │
│ - Debit Transit-FX (3510)           │
│ - Credit Transit (3500)             │
└─────────────────────────────────────┘
                ↓
┌─────────────────────────────────────┐
│ Step 3: Credit B's Wallet (1200)    │
│ - Debit Transit (3500)              │
│ - Credit B's Wallet (1200)          │
└─────────────────────────────────────┘
                ↓
┌─────────────────────────────────────┐
│ Status: CONFIRMED (complete)        │
│ Can now withdraw/spend              │
└─────────────────────────────────────┘
```

### Key Principle: NO DIRECT BLOCKCHAIN
✅ **Allowed**: Wallet A → Wallet B (all tracked in ledger)
❌ **Forbidden**: Direct A → B on blockchain (would bypass ledger)

---

## 4. Database Schema

```sql
-- Wallet table
CREATE TABLE wallet (
  id                BIGINT PRIMARY KEY,
  uid               VARCHAR(128) NOT NULL,     -- user/merchant ID
  wallet_type       VARCHAR(16)  NOT NULL,     -- USER, MERCHANT, HOT, TRANSIT
  status            VARCHAR(16)  NOT NULL,     -- ACTIVE, FROZEN, CLOSED
  balance_minor     BIGINT       NOT NULL DEFAULT 0,
  currency_code     VARCHAR(10)  NOT NULL,
  account_code      VARCHAR(10)  NOT NULL,     -- COA account
  version           BIGINT       NOT NULL DEFAULT 0,
  created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
  last_activity_at  TIMESTAMP
);

-- Transfer transaction
CREATE TABLE wallet_transfer (
  id                BIGINT PRIMARY KEY,
  from_wallet_id    BIGINT NOT NULL REFERENCES wallet(id),
  to_wallet_id      BIGINT NOT NULL REFERENCES wallet(id),
  amount_minor      BIGINT NOT NULL,
  currency_code     VARCHAR(10) NOT NULL,
  status            VARCHAR(16) NOT NULL,      -- PENDING, POSTED, CONFIRMED
  transaction_id    BIGINT UNIQUE,             -- coa_trans reference
  ref_id            VARCHAR(128) UNIQUE,       -- idempotency key
  memo              VARCHAR(512),
  created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
  confirmed_at      TIMESTAMP
);

-- Hold (freeze balance during transfer)
CREATE TABLE wallet_hold (
  id                BIGINT PRIMARY KEY,
  wallet_id         BIGINT NOT NULL REFERENCES wallet(id),
  transfer_id       BIGINT NOT NULL REFERENCES wallet_transfer(id),
  amount_minor      BIGINT NOT NULL,
  status            VARCHAR(16) NOT NULL,      -- ACTIVE, RELEASED, CAPTURED
  created_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);
```

---

## 5. State Machine

### Transfer States
```
PENDING
  └─→ POSTED (ledger entry created)
       └─→ CONFIRMED (both wallets settled)
       └─→ FAILED (rejected at any step)
```

### Wallet Status
```
ACTIVE    → Normal operations
FROZEN    → No transfer out (but deposits OK)
CLOSED    → No operations
```

---

## 6. COA Integration

### Account Mapping
```
1200 = User Wallet (ASSET, debit-normal)
2200 = Merchant Wallet Payable (LIABILITY, credit-normal)
3500 = Transit (TRANSIT, credit-normal)
3510 = Transit-FX (TRANSIT, credit-normal)
```

**Double-Entry for A→B Transfer:**
```
DR 3500 (Transit)        / CR 1200-A (Wallet A)      [debit A]
DR 1200-B (Wallet B)     / CR 3500 (Transit)         [credit B]
```

---

## 7. REST API

### Wallet Operations
```
GET    /api/wallets/{uid}                    # Get wallet
GET    /api/wallets/{uid}/balance            # Current balance
GET    /api/wallets/{uid}/transactions       # History

POST   /api/wallets/{uid}/transfer           # A→B transfer
       {to_uid, amount, currency, memo}

GET    /api/wallets/{uid}/holds              # Frozen amounts
GET    /api/wallets/{uid}/pending            # Pending transfers
```

### Transfer Status
```
GET    /api/transfers/{id}                   # Transfer details
POST   /api/transfers/{id}/confirm           # Confirm completion
POST   /api/transfers/{id}/cancel            # Cancel if pending
```

---

## 8. Key Features

### 1. **Hold/Reserve Balance**
- When transfer initiated → amount is HELD (not available)
- If transfer fails → hold is RELEASED
- If transfer succeeds → hold is CAPTURED

### 2. **Idempotency**
- `ref_id` (unique constraint) prevents duplicate transfers
- Retry-safe: same ref_id = same transfer

### 3. **Multi-Currency**
- Each wallet = single currency
- FX conversion uses dedicated Transit-FX account
- Exchange rate: stored in currency table

### 4. **Settlement Workflow**
- User Wallet → Merchant Wallet (payment)
- Merchant Wallet → Bank Account (daily settlement)
- Hot Wallet management (maintain minimum balance)

### 5. **Compliance**
- All transfers tracked in ledger (audit trail)
- KYC/AML checks before transfer
- Velocity limits per wallet type
- Transaction history immutable

---

## 9. Implementation Order

1. **Phase 1**: Core Wallet infrastructure
   - Wallet entity + CRUD
   - JDBC implementation
   - COA integration

2. **Phase 2**: Transfer operations
   - Transfer engine (A→B flow)
   - Hold management
   - Ledger posting

3. **Phase 3**: API + State Management
   - REST endpoints
   - Status transitions
   - Webhook confirmations

4. **Phase 4**: Advanced Features
   - FX conversion
   - Batch settlement
   - Compliance checks

---

## 10. Example: User A sends 100 USDT to User B

**Request:**
```json
POST /api/wallets/user-a/transfer
{
  "to_uid": "user-b",
  "amount": "100000000000000000",
  "currency": "USDT",
  "memo": "Payment for service"
}
```

**Ledger entries created:**
```
Transaction 1 (Debit A):
  DR 3500-USDT    100 USDT
  CR 1200-A-USDT  100 USDT
  
Transaction 2 (Credit B):
  DR 1200-B-USDT  100 USDT
  CR 3500-USDT    100 USDT
```

**Result:**
- User A wallet: -100 USDT (HELD during transfer)
- Transit: +100 USDT → -100 USDT (pass-through)
- User B wallet: +100 USDT
- Both ledgers balanced ✓

---

## 11. Security Considerations

- **Private Keys**: Not stored in wallet (use Vault/HSM)
- **Balance Lock**: Row-level locking during transfer
- **Audit Trail**: Immutable transaction history
- **Rate Limiting**: Per wallet per hour/day
- **2FA**: For transfers > threshold amount
