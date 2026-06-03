# Crypto Top-Up Flow — Double-Entry Accounting Design

## Overview

GtelPay accepts crypto deposits (USDT, ETH, BTC) and converts them to local currency (VND). The flow manages custody, FX conversion, and settlement with full audit trail.

## Chart of Accounts for Crypto

### Asset Accounts (Debit-Normal)

```
1100-USDT         ASSET    USDT    | On-chain custody: hot + cold wallet
1101-ETH          ASSET    ETH     | Ethereum custody
1102-BTC          ASSET    BTC     | Bitcoin custody
1200-CRYPTO-FIAT  ASSET    VND     | Exchange buffer (temporary FX holding)
1300-MERCHANT-BAL ASSET    VND     | Merchant account balance (after topup)
```

### Liability Accounts (Credit-Normal)

```
2100-USER-PAYABLE LIABILITY VND    | Outstanding balance to merchants (accrual)
2200-SETTLEMENT   LIABILITY VND    | Pending on-chain settlement tx
2300-FX-PAYABLE   LIABILITY VND    | FX loss/gain accrual
```

### Transit Accounts (Must Clear = 0)

```
3500-CRYPTO-RECV  TRANSIT   USDT   | Crypto received, pending ledger post
3501-USDT-CONVERT TRANSIT   VND    | USDT conversion bridge (debit VND, credit USDT)
3502-MERCHANT-REC TRANSIT   VND    | Merchant receiving converted balance
3510-FX-CONVERSION TRANSIT  VND    | FX rate adjustment (gain/loss)
3600-SETTLEMENT-TX TRANSIT  USDT   | On-chain settlement transaction state
```

### Revenue/Expense Accounts

```
4100-FX-GAIN      REVENUE   VND    | Favorable exchange rate gains
4200-FX-LOSS      EXPENSE   VND    | Unfavorable exchange rate losses
5100-CUSTODY-FEE  EXPENSE   VND    | Platform custody/processing fee
```

## Event Schema

### CryptoDepositInitiated

```json
{
  "event_id": 1234567890,
  "event_type": "CRYPTO_DEPOSIT_INITIATED",
  "timestamp": 1654321098000,
  "source": "blockchain-listener",
  "user_id": "merchant-abc",
  "correlation_id": 5001,
  "data": {
    "deposit_id": "dep-001-usdt",
    "merchant_id": "mid-123",
    "crypto_amount": 1000000000,           // 1000 USDT in smallest unit
    "crypto_currency": "USDT",
    "crypto_network": "ethereum",          // or "polygon", "optimism"
    "tx_hash": "0xabc...",
    "from_address": "0xuser...",
    "to_address": "0xgtel-hot-wallet",
    "block_confirmations": 12,
    "exchange_rate_snapshot": {            // FX rate at moment of detection
      "usdt_to_vnd": 24500,                // 1 USDT = 24,500 VND
      "rate_timestamp": 1654321098000
    }
  },
  "retry_count": 0
}
```

### CryptoDepositConfirmed

```json
{
  "event_id": 1234567891,
  "event_type": "CRYPTO_DEPOSIT_CONFIRMED",
  "timestamp": 1654321099000,
  "source": "blockchain-listener",
  "user_id": "merchant-abc",
  "correlation_id": 5001,
  "data": {
    "deposit_id": "dep-001-usdt",
    "crypto_amount": 1000000000,
    "crypto_currency": "USDT",
    "final_block_height": 18500000,
    "final_confirmations": 20
  },
  "retry_count": 0
}
```

### CryptoDepositConverted

```json
{
  "event_id": 1234567892,
  "event_type": "CRYPTO_DEPOSIT_CONVERTED",
  "timestamp": 1654321100000,
  "source": "saving-gateway",
  "user_id": "merchant-abc",
  "correlation_id": 5001,
  "data": {
    "deposit_id": "dep-001-usdt",
    "crypto_amount": 1000000000,           // 1000 USDT
    "crypto_currency": "USDT",
    "vnd_amount": 24500000000,             // 24,500,000 VND (minor units)
    "exchange_rate_used": 24500,           // FX rate applied
    "fx_rate_timestamp": 1654321098000,
    "custody_fee_minor": 0,                // Platform fee (if any)
    "net_credit_vnd": 24500000000
  },
  "retry_count": 0
}
```

## Transaction Flow

### Step 1: CRYPTO_DEPOSIT_CONFIRMED → Ledger Receipt

**Command:** `CryptoDepositConfirmCmd(depositId, cryptoAmount, cryptoCurrency, txHash)`

**Bút Toán (Journal Entry):**
```
Line 1: DR 1100-USDT           (Crypto asset received)
        CR 3500-CRYPTO-RECV    (Transit: pending ledger post)
        Amount: 1000 USDT
        
Memo: "Crypto deposit confirmed: {deposit_id} from {tx_hash}"
```

**Invariant Check:**
- Crypto amount > 0
- Account 3500 must clear after conversion
- Block confirmations ≥ 12 (configurable)

---

### Step 2: FX Conversion & Merchant Credit

**Command:** `CryptoToFiatConvertCmd(depositId, cryptoAmount, cryptoCode, fxRateSnapshot)`

**Bút Toán (FX Conversion + Credit):**

Assume: 1000 USDT @ 24,500 VND/USDT = 24,500,000 VND

```
Line 1: DR 1200-CRYPTO-FIAT    (Exchange buffer; debit VND)
        CR 3500-CRYPTO-RECV    (Clear transit; credit USDT)
        Amount: 24,500,000 VND (equivalent)
        
Line 2: DR 2100-USER-PAYABLE   (Accrual: owes merchant cash)
        CR 1200-CRYPTO-FIAT    (Transfer to payable)
        Amount: 24,500,000 VND
        
Line 3: DR 1300-MERCHANT-BAL   (Post to merchant wallet)
        CR 2100-USER-PAYABLE   (Settle accrual)
        Amount: 24,500,000 VND
        
Memo: "Crypto conversion: {deposit_id} 1000 USDT @ 24,500 VND"
```

**Transit Verification:**
- After Step 1: `3500-CRYPTO-RECV` = +1000 USDT (needs clearing)
- After Step 2, Line 1: `3500-CRYPTO-RECV` = 0 ✓
- All other transit accounts must remain = 0

---

### Step 3: On-Chain Settlement (Custodian → Wallet Rebalance)

**Command:** `CryptoSettlementInitCmd(cryptoCode, coldWalletAddress, amount)`

For operational efficiency, GtelPay keeps:
- **Hot wallet** (1100-USDT): Liquid for immediate redemptions
- **Cold storage** (1100-USDT): Vault for long-term custody

**Bút Toán (Rebalance from Hot → Cold):**

```
Line 1: DR 3600-SETTLEMENT-TX  (Transit: pending on-chain confirmation)
        CR 1100-USDT           (Debit hot wallet USDT)
        Amount: 100 USDT (moving to cold)
        
Memo: "Settlement TX to cold wallet: {tx_hash} pending {confirmation} confirmations"
```

Once on-chain confirmed:

```
Line 1: DR 1100-USDT           (Rebalance: hot → cold, both are 1100)
        CR 3600-SETTLEMENT-TX  (Clear transit)
        Amount: 100 USDT
        
Memo: "Settlement confirmed: {tx_hash} block {block_height}"
```

---

### Step 4: FX Revaluation (End-of-Day or Periodic)

**Command:** `CryptoRevaluationCmd(cryptoCode, currentFxRate, rateTimestamp)`

If actual market rate diverges from purchase rate:

**Example:** 1000 USDT initially @ 24,500, now trading @ 24,700 (+200 VND per unit = +200,000 total gain)

**Bút Toán (Mark-to-Market Adjustment):**

```
Line 1: DR 1100-USDT           (Asset: revalue upward)
        CR 4100-FX-GAIN        (Revenue: record gain)
        Amount: 200,000 VND equivalent
        
OR (if loss):

Line 1: DR 4200-FX-LOSS        (Expense: record loss)
        CR 1100-USDT           (Asset: revalue downward)
        Amount: loss_amount VND equivalent
        
Memo: "FX revaluation: USDT 24,500 → 24,700; snapshot {rate_timestamp}"
```

---

## Custody & Compliance

### Cold Storage Accounting

Cold storage is **not a separate asset**; both hot and cold are accounts within `1100-USDT`. Physical location is tracked in the subledger:

```sql
-- Subledger for 1100-USDT
account_code: 1100-USDT
party_id: NULL (company-owned crypto)
location: 'hot-wallet' | 'cold-vault' | 'exchange'
address: '0xgtel...' | 'hardware-signer' | 'custodian-account'
```

### Audit Trail

Every crypto event generates:
1. **CoaTrans** (journal entry in ledger)
2. **Event log** (blockchain listener confirms)
3. **Subledger detail** (crypto wallet address, block number)
4. **FX snapshot** (rate at time of transaction)

---

## Error Handling & Reversals

### Double-Spend or Unconfirmed

If block reorg or double-spend detected:

```
Reverse CoaTrans from Step 1:

Line 1: CR 1100-USDT           (Reverse asset debit)
        DR 3500-CRYPTO-RECV    (Reverse transit credit)
        Amount: 1000 USDT
        
Memo: "Reversal: {deposit_id} unconfirmed/replaced; block reorg detected at height {height}"
```

Event issued: `CRYPTO_DEPOSIT_REVERSED`

### Partial Conversion Failure

If merchant rejects delivery or off-chain settlement fails:

```
Reverse Step 2 & 3; keep Step 1 (crypto is already on-chain held).
Issue event: CRYPTO_DEPOSIT_ON_HOLD
Manual review required for refund or reattempt.
```

---

## Reconciliation

### Daily Close Procedure

1. **Blockchain Reconciliation**
   - Fetch all UTXOs/ERC20 balances for hot/cold wallets
   - Confirm total matches `(1100-USDT debit - 1100-USDT credit)`

2. **FX Revaluation**
   - Query market rate for USDT, ETH, BTC
   - Post mark-to-market adjustments

3. **Settlement Confirmation**
   - Verify pending `3600-SETTLEMENT-TX` confirmations
   - Advance to settled status

4. **Period Close**
   - All transit accounts (`35xx`, `36xx`) must = 0
   - `2100-USER-PAYABLE` should = 0 (all topups complete or pending)
   - `1300-MERCHANT-BAL` should match actual merchant wallet balance

---

## Implementation Checklist

- [ ] Add crypto account codes to COA seed data
- [ ] Create `CryptoDepositConfirmCmd` handler
- [ ] Create `CryptoToFiatConvertCmd` handler
- [ ] Create `CryptoSettlementInitCmd` handler
- [ ] Create `CryptoRevaluationCmd` handler
- [ ] Blockchain listener (separate service; listens to Ethereum, Polygon, Bitcoin)
- [ ] FX rate oracle (integrate with CoinGecko or similar)
- [ ] Crypto settlement validator (on-chain confirmation checker)
- [ ] Merchant reconciliation report (settle merchant balance daily)
- [ ] Period close validator (check transit account = 0)

---

## Example: End-to-End Flow

**Scenario:** Merchant deposits 1000 USDT, receives VND credit

```
T=0s   Blockchain event: 0xabc... sends 1000 USDT to gtel hot wallet
       → Listener emits: CryptoDepositInitiated(1000 USDT, rate=24,500)
       
T=1m   12 confirmations reached
       → Listener emits: CryptoDepositConfirmed(1000 USDT)
       → Ledger posts Step 1 (DR 1100 / CR 3500)
       ✓ 3500 now has +1000 USDT
       
T=2m   Gateway triggers conversion
       → Ledger posts Step 2 (DR 1200 / CR 3500; then DR 2100 / CR 1200; then DR 1300 / CR 2100)
       ✓ 3500 cleared to 0
       ✓ 1300 now +24,500,000 VND
       ✓ Merchant wallet credited
       
T=5m   Hot wallet rebalances (move 100 USDT to cold)
       → Ledger posts Step 3 lines (DR/CR 1100, using 3600 as transit)
       ✓ 1100-USDT unchanged in total, but location subledger updated
       
T=EOD  FX revaluation (USDT now @ 24,700)
       → Ledger posts Step 4 (DR 1100 / CR 4100)
       ✓ FX gain of 200,000 VND recognized
```

---

## Security & Compliance Considerations

1. **Multi-Sig Custody**
   - Cold storage requires 2-of-3 signing (documented in subledger)
   - Each settlement transaction in `3600-SETTLEMENT-TX`

2. **AML/KYC**
   - Merchant deposit limits enforced before Step 1 posts
   - Regulatory reports generated from period close data

3. **Insurance & Liability**
   - All crypto assets insured by Coincover/Ledger Vault
   - Insurance premium paid from `5100-CUSTODY-FEE`

4. **Audit & Tax**
   - All FX gains/losses in `41xx` / `42xx` for tax reporting
   - Period close reconciliation proves no loss of funds
