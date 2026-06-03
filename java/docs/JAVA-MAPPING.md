# Java Implementation Mapping to core.foundation.md

## Overview

This document maps the `core.foundation.md` design to actual Java source code in `/java/src/main/java/dev/nivic/coa/`.

---

## 1. Core.Foundation → Java Packages

| Design | Package | Files |
|--------|---------|-------|
| `core.foundation` | `dev.nivic.coa` | `CoaAccount`, `CoaAccountKind`, `CoaTrans`, `CoaTransLine` |
| API Response Envelope | `dev.nivic.coa.api` | `ApiResponse`, `FundFlowApi`, `FundFlowServlet` |
| Error Handling | `dev.nivic.coa.error` | `*Exception.java` (11 types) |
| Utility | `dev.nivic.coa.*` | `FundFlowLedger` interface, `JdbcFundFlowLedger` impl |

---

## 2. Chart of Accounts (COA) → CoaAccountKind

**Design Section 6 (Chart of Accounts)** maps to `CoaAccountKind.java`:

```java
public enum CoaAccountKind {
  ASSET,      // Nhóm 1 — Tài sản (1111, 1112, 1113)
  LIABILITY,  // Nhóm 2 — Nợ phải trả (2110, 2120, 2130)
  TRANSIT,    // Nhóm 3 — Transit (3100–3820, luôn = 0)
  REVENUE,    // Nhóm 4 — Doanh thu (4110–4170)
  EXPENSE,    // Nhóm 5 — Chi phí (5100, 5200, 5300)
  EQUITY      // Nhóm 6 — Vốn chủ sở hữu (6000, 6100)
}
```

**Balance Convention** (`CoaAccount.java:30-42`):
- **ASSET/EXPENSE (debit-normal):** `balanceMinor > 0` is healthy
- **LIABILITY/REVENUE/EQUITY/TRANSIT (credit-normal):** `balanceMinor < 0` is healthy
- **TRANSIT invariant:** Must return to 0 after each complete flow

```java
public boolean isDebitNormal() {
  return kind == CoaAccountKind.ASSET || kind == CoaAccountKind.EXPENSE;
}
public long naturalBalance() {
  return isDebitNormal() ? balanceMinor : -balanceMinor;
}
public boolean isTransitClear() {
  return kind == CoaAccountKind.TRANSIT && balanceMinor == 0;
}
```

---

## 3. Transaction Model → CoaTrans & CoaTransLine

**Design Section 2 (Owned tables: `trans`, `trans_data`)** maps to:

### CoaTrans.java (Transaction Header)
```java
public record CoaTrans(
    long id,              // BIGINT, 500000000+
    String refId,         // Idempotency key (business_ref in design)
    String memo,
    Instant createdAt,
    List<CoaTransLine> lines
) {
  public long debitTotal()  { /* sum of all debit lines */ }
  public long creditTotal() { /* sum of all credit lines */ }
  public boolean isBalanced() { return debitTotal() == creditTotal(); }
}
```

### CoaTransLine.java (Transaction Detail)
```java
public record CoaTransLine(
    int lineNo,              // Line sequence (1, 2, 3, ...)
    String accountCode,      // "1111", "2110", "3100", etc.
    String accountName,      // "TK Vietinbank", "Wallet Balance — User", ...
    long debitMinor,         // Always ≥ 0
    long creditMinor,        // Always ≥ 0 (never both debit & credit on same line)
    String currencyCode      // "VND", "USD"
) {
  public boolean isDebit()  { return debitMinor  > 0; }
  public boolean isCredit() { return creditMinor > 0; }
  public long netDelta() { return debitMinor - creditMinor; }
}
```

**Key Invariant:** `trans.debitTotal() == trans.creditTotal()` (double-entry constraint)

---

## 4. Account Model → CoaAccount

**Design Section 3 (Owned tables: `account`)** maps to:

```java
public record CoaAccount(
    String code,              // "1111", "2110", "3100", etc.
    String name,              // Full account name in Vietnamese
    CoaAccountKind kind,      // ASSET, LIABILITY, TRANSIT, REVENUE, EXPENSE, EQUITY
    String currencyCode,      // "VND", "USD"
    long balanceMinor,        // balance_minor = Σ(debit) − Σ(credit)
    long version              // Optimistic lock version
) {
  public boolean isDebitNormal() { /* ASSET or EXPENSE */ }
  public long naturalBalance() { /* signed, always positive when healthy */ }
  public boolean isTransitClear() { /* TRANSIT kind && balanceMinor == 0 */ }
}
```

---

## 5. Use Cases → FundFlowLedger Operations

**Design Section 8–16 (Use Cases)** maps to `FundFlowLedger.java` interface:

### Use Case 8 — Nạp tiền (Deposit)

| Design Step | Java Method | Command |
|------------|-------------|---------|
| Phase A: Webhook NH ghi PENDING | `receiveTopUp()` | `TopUpReceiveCmd` |
| Phase B: Confirm → POSTED | `confirmTopUp()` | `TopUpConfirmCmd` |

```java
CoaTrans receiveTopUp(TopUpReceiveCmd cmd);
  // Posts: DR 1111 / CR 3100

CoaTrans confirmTopUp(TopUpConfirmCmd cmd);
  // Posts: DR 3100 / CR 2110 / CR 4110
```

### Use Case 9 — Rút tiền (Withdraw)

```java
CoaTrans initWithdraw(WithdrawInitCmd cmd);
  // Step 1: DR 2110 (amount+fee) / CR 3200 (transit)

CoaTrans settleWithdraw(WithdrawSettleCmd cmd);
  // Step 2: DR 3200 (amount+fee) / CR 1111 (amount) / CR 4120 (fee)
```

### Use Case 10 — Chuyển ví → ví (Internal Transfer)

```java
CoaTrans initInternalTransfer(InternalTransferInitCmd cmd);
  // Step 1: DR 2110 (payer, amount+fee) / CR 3300

CoaTrans settleInternalTransfer(InternalTransferSettleCmd cmd);
  // Step 2: DR 3300 / CR 2110 (payee, amount) / CR 4130 (fee)
```

### Use Case 11 — IBFT (Liên ngân hàng)

```java
CoaTrans initIbftTransfer(IbftInitCmd cmd);
  // Step 1: DR 2110 (amount+fee) / CR 3400

CoaTrans settleIbftTransfer(IbftSettleCmd cmd);
  // Step 2: DR 3400 / CR 1112 / CR 4130 (fee) / DR 5100 (Napas cost)
```

### Use Case 12 — QR/POS Payment

```java
CoaTrans receiveQrPos(QrPosReceiveCmd cmd);
  // Step 1: DR 1113 / CR 3500 / (fee: DR 5100 / CR 1113)

CoaTrans creditMerchantQrPos(QrPosCreditMerchantCmd cmd);
  // Step 2: DR 3500 / CR 2120 (merchant)
```

### Use Case 13 — Thanh toán bằng ví (Wallet Payment)

```java
CoaTrans initWalletPayment(WalletPaymentInitCmd cmd);
  // Step 1: DR 2110 (user) / CR 3500

CoaTrans settleWalletPayment(WalletPaymentSettleCmd cmd);
  // Step 2: DR 3500 / CR 2120 (merchant)
```

### Use Case 14 — Chi Lương (Payroll)

```java
CoaTrans initPayroll(PayrollInitCmd cmd);
  // Step 1: DR 2120 (merchant, amount+fee) / CR 3600

CoaTrans disbursePayroll(PayrollDisburseCmd cmd);
  // Step 2: DR 3600 / CR 1112 / CR 4150 (fee) / DR 5100 (Napas cost)
```

### Use Case 15 — Chi hộ (Disbursement)

```java
CoaTrans prefundDisbursement(DisbursementPrefundCmd cmd);
  // Pre-fund: DR 1111 / CR 2130 (escrow)

CoaTrans initDisbursement(DisbursementInitCmd cmd);
  // Step 1: DR 2130 (escrow, amount+fee) / CR 3700

CoaTrans settleDisbursement(DisbursementSettleCmd cmd);
  // Step 2: DR 3700 / CR 1112 / CR 4150 (fee) / DR 5100 (Napas cost)
```

### Use Case 16 — Settlement & Clearing (EOD)

```java
CoaTrans eodInitClearing(EodClearingInitCmd cmd);
  // Step 1: DR 2120 (total) / CR 3800

CoaTrans eodReconcile(EodReconcileCmd cmd);
  // Step 2: DR 3800 / CR 3820 (MDR) / CR 3810 (net)

CoaTrans eodRecognizeMdr(EodRecognizeMdrCmd cmd);
  // Step 3: DR 3820 / CR 4140 (revenue)

CoaTrans eodSettleOutbound(EodSettleOutboundCmd cmd);
  // Step 4: DR 3810 / CR 1112 / DR 5100 (Napas cost)

CoaTrans eodRejectSettlement(EodRejectSettlementCmd cmd);
  // Exception: DR 3810 / DR 3820 / CR 2120
```

---

## 6. Wallet Integration → Mirror & Subsidiary Ledger

**Design Section 3** (Wallet ↔ Accounting sync):

```java
// Mirror a wallet transfer into GL (wallet is separate subsidiary ledger)
CoaTrans mirrorWalletTransfer(
    long payerAcct,      // mid of user A
    long payeeAcct,      // mid of user B
    long amount,         // transfer amount
    String ref,          // idempotency key (e.g., "WAL:mid:requestId")
    String memo
);
// Posts: DR 2110(payerAcct) / CR 2110(payeeAcct)

// Query wallet balance (subsidiary ledger of control account 2110)
long walletBalance(long mid);
  // Returns: sum(credit − debit) on 2110 lines where party_mid = mid

// Query savings balance (subsidiary ledger of control account 2140)
long savingsBalance(long mid);

// Savings deposit: wallet → tiết kiệm reclassification
CoaTrans savingsDeposit(SavingsDepositCmd cmd);
  // Posts: DR 2110(mid) / CR 2140(mid)

// Savings interest accrual
CoaTrans savingsInterest(SavingsInterestCmd cmd);
  // Posts: DR 5200 (expense) / CR 2140(mid)
```

**Key principle:** Wallet balances stay in subsidiary ledger (controlled by wallet service); GL only records debits/credits to 2110/2140 control accounts. No cross-core `JOIN`.

---

## 7. Multi-Currency → FX Operations

**Design Extension (Multi-currency support):**

```java
CoaTrans fxExchange(FxExchangeCmd cmd);
  // VND ↔ USD exchange via FX position accounts (1920 VND / 1921 USD)

CoaTrans fxRevalue(FxRevalueCmd cmd);
  // Mark-to-market revaluation: DR 1920 / CR 4170 (gain) or DR 5300 / CR 1920 (loss)
```

---

## 8. Error Handling → Exception Hierarchy

| Design Scenario | Java Exception |
|----------------|----------------|
| Insufficient wallet balance | `InsufficientWalletException` |
| Insufficient transit account | `InsufficientTransitException` |
| Insufficient escrow (2130) | `InsufficientEscrowException` |
| Negative balance (invariant violation) | `NegativeBalanceException` |
| Nothing to close (period close) | `NothingToCloseException` |
| Nothing to revalue (FX) | `NothingToRevalueException` |
| Transaction not found | `TransactionNotFoundException` |
| Already reversed | `AlreadyReversedException` |
| Maker-checker constraints | `SegregationOfDutiesException` |
| Proposal state error | `ProposalStateException` |
| Proposal not found | `ProposalNotFoundException` |

---

## 9. Maker-Checker (Segregation of Duties)

**Design Extension (4-eyes control):**

```java
Proposal propose(ProposeJournalCmd cmd);
  // Maker proposes → status = PENDING (not posted)

CoaTrans approve(long proposalId, String checkerId);
  // Checker reviews & approves → posts to GL (atomic)

Proposal reject(long proposalId, String checkerId, String reason);
  // Checker rejects → status = REJECTED

Proposal findProposal(long proposalId);
List<Proposal> pendingProposals();
  // Query pending approvals
```

**Constraint:** `checkerId != makerId` (segregation enforced)

---

## 10. Reversal & Audit Trail

**Design Section 8.5 (Error handling):**

```java
CoaTrans reverse(ReversalCmd cmd);
  // Reverse an existing transaction by posting opposite debits/credits
  // Original trans stays in DB (audit trail)
  // New trans linked via reverses_ref
  // Idempotent on reversalRef
```

---

## 11. Period Close & Khoá Sổ

**Design Extension (Year-end closing):**

```java
CoaTrans closePeriod(PeriodCloseCmd cmd);
  // Period-end close: transfer all 4xxx/5xxx balances to 6100 (retained earnings)
  // Posts one journal: DR all 4xxx / CR all 5xxx / balance to 6100
  // After close: 4xxx/5xxx = 0, 6100 += net profit
```

---

## 12. Reporting & Balance Sheet

**Design Extension (Financial reporting):**

Located in `dev.nivic.coa.report/`:
- `TrialBalance` — sum of all accounts
- `BalanceSheet` — Assets vs. (Liabilities + Equity)
- `ProfitAndLoss` — Revenue minus Expenses
- `CashFlowStatement` — Cash inflows/outflows
- `FundFlowReports` — Custom reconciliation reports

```java
boolean isDoubleEntryBalanced();
  // Platform sanity check: Σ(all debits) == Σ(all credits)
```

---

## 13. API Layer → HTTP Response Envelope

**Design Section 1 (Response envelope):**

```java
public record ApiResponse(int status, String json) {
  public static ApiResponse ok(String json)   { return new ApiResponse(200, json); }
  public static ApiResponse created(String j)  { return new ApiResponse(201, j); }
  public static ApiResponse error(int status, String code, String message) {
    return new ApiResponse(status, "{\"error\":..., \"message\":...}");
  }
}
```

**Controllers:**
- `FundFlowApi` — Command handler (business logic routing)
- `FundFlowServlet` — HTTP servlet (request/response binding)

---

## 14. Implementation: JdbcFundFlowLedger

`JdbcFundFlowLedger.java` is the concrete implementation of `FundFlowLedger` interface:

- **Transactional:** Every method posts exactly one transaction (possibly multi-line) atomically
- **Idempotent:** Checks `refId` (business reference) before insert; returns existing `trans` if duplicate
- **Balanced:** Validates `debitTotal() == creditTotal()` before posting
- **Balance update:** Atomically updates `coa_account.balance_minor` for each touched account
- **Error handling:** Throws exceptions on insufficient balance, constraint violations, etc.

---

## Summary: Design → Code Mapping

| Design Concept | Java Class/Package |
|---|---|
| Core Layer | `dev.nivic.coa.*` |
| Accounts | `CoaAccount` + `CoaAccountKind` enum |
| Transactions | `CoaTrans` (header) + `CoaTransLine` (detail) |
| Operations | `FundFlowLedger` interface + `cmd/*` commands |
| Balance Convention | `CoaAccount.isDebitNormal()`, `naturalBalance()` |
| Double-Entry Invariant | `CoaTrans.isBalanced()` |
| Transit Clearance | `CoaAccount.isTransitClear()` |
| Idempotency | `refId` field + checks in `JdbcFundFlowLedger` |
| Wallet Subsidiary | `walletBalance(mid)`, `mirrorWalletTransfer()` |
| Error Handling | `error/*` exceptions + boundary validation |
| Reporting | `report/*` classes + `isDoubleEntryBalanced()` |
| Maker-Checker | `mc/Proposal`, `approve()`, `reject()` |
| Audit Trail | Original trans + reversal via `reverse()` |

