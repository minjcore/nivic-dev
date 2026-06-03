# Technical Requirements Document (TRD)

**Accounting Service (Backend)**

| Field | Value |
|-------|-------|
| Version | 1.0 |
| Status | Draft |
| Author | Engineering |
| Date | June 2026 |

**Related:** [`core.foundation.md`](./core.foundation.md) — domain boundaries, GtelPay fund flow, `trans` / `trans_data` design.

---

## 1. Overview

### 1.1 Purpose

The Accounting Service provides a centralized financial ledger system for recording, tracking, reconciling, and reporting financial transactions across products and business units.

The service acts as the source of truth for all accounting entries and ensures:

- Double-entry bookkeeping
- Auditability
- Financial accuracy
- Historical traceability
- Reporting consistency

### 1.2 Goals

**Business Goals**

- Maintain accurate financial records
- Support financial reporting
- Enable reconciliation workflows
- Support multiple currencies
- Support accounting periods and closing

**Technical Goals**

- Immutable ledger
- Strong consistency for postings
- Complete audit trail
- Horizontal scalability
- High availability
- Idempotent transaction processing

---

## 2. Scope

### In Scope

- Chart of accounts
- Journal entries
- Double-entry posting
- Account balances
- Period management
- Reconciliation
- Audit logs
- Reporting APIs
- Multi-currency support

### Out of Scope (v1)

- Tax calculation
- Payroll
- Billing
- Invoice generation
- Budget planning
- Financial forecasting

---

## 3. Functional Requirements

### FR-1 Chart of Accounts

The system shall support creation and management of accounts.

**Account Types**

- Asset
- Liability
- Equity
- Revenue
- Expense

**Account Fields**

| Field | Type |
|-------|------|
| account_id | UUID |
| code | String |
| name | String |
| type | Enum |
| parent_account_id | UUID |
| currency | String |
| active | Boolean |
| created_at | Timestamp |

**Requirements**

- Account code must be unique
- Hierarchical accounts supported
- Account deactivation supported
- Account deletion prohibited after use

### FR-2 Journal Entries

A journal entry represents a business event.

**Fields**

| Field | Type |
|-------|------|
| journal_entry_id | UUID |
| reference_id | String |
| description | String |
| posting_date | Date |
| status | Enum |
| created_by | User |
| created_at | Timestamp |

**Status**

- Draft
- Posted
- Reversed

### FR-3 Journal Lines

Each journal entry contains multiple lines.

**Fields**

| Field | Type |
|-------|------|
| line_id | UUID |
| journal_entry_id | UUID |
| account_id | UUID |
| debit_amount | Decimal |
| credit_amount | Decimal |
| currency | String |

**Validation**

- Debit ≥ 0
- Credit ≥ 0
- One side must be zero

### FR-4 Double Entry Accounting

The service must enforce:

```
SUM(debits) = SUM(credits)
```

for every journal entry.

Posting must fail if the equation does not balance.

### FR-5 Ledger Posting

Posting creates immutable ledger records.

**Requirements**

- Atomic operation
- ACID transaction
- No partial posting
- Ledger entries immutable

**Ledger Entry Fields**

| Field | Type |
|-------|------|
| ledger_entry_id | UUID |
| journal_entry_id | UUID |
| account_id | UUID |
| amount | Decimal |
| direction | Debit/Credit |
| posting_timestamp | Timestamp |

### FR-6 Reversals

Journal entries cannot be edited after posting.

Instead:

- Create reversing entry
- Link to original entry
- Maintain audit chain

### FR-7 Account Balances

System shall provide:

**Current Balance**

- Real-time balance calculation.

**Historical Balance**

- Balance as-of specific timestamp.

**Trial Balance**

- Balances by account.

### FR-8 Accounting Periods

**Period States**

- Open
- Closed
- Locked

**Rules**

| State | Rule |
|-------|------|
| Open | Posting allowed |
| Closed | Posting prohibited |
| Locked | Administrative changes prohibited |

### FR-9 Multi-Currency Support

**Requirements**

Store:

- Transaction currency
- Functional currency
- Exchange rate

**Example**

```json
{
  "transaction_currency": "EUR",
  "transaction_amount": 100,
  "functional_currency": "USD",
  "exchange_rate": 1.08
}
```

**Historical Rates**

Rates used during posting must be immutable.

### FR-10 Reconciliation

Support reconciliation of:

- Bank transactions
- Payment processor settlements
- External accounting systems

**States**

- Unmatched
- Matched
- Reconciled

### FR-11 Audit Logging

Every change must generate audit records.

**Track**

- User
- Timestamp
- Before state
- After state
- Action

**Actions**

- Create
- Update
- Post
- Reverse
- Close period

---

## 4. Non-Functional Requirements

### NFR-1 Availability

Target: **99.95%** monthly uptime.

### NFR-2 Consistency

Posting operations require:

- Strong consistency
- No eventual consistency for ledger writes

### NFR-3 Durability

Once posted:

- No data loss accepted
- Transactions must survive node failures

### NFR-4 Scalability

| Metric | Target |
|--------|--------|
| Journal Posts | 2,000/sec |
| Balance Reads | 20,000/sec |
| Reporting Reads | 5,000/sec |

### NFR-5 Latency

| Operation | Target |
|-----------|--------|
| Posting | P95 < 300ms |
| Balance Queries | P95 < 100ms |
| Reporting Queries | P95 < 2s |

### NFR-6 Security

**Authentication**

- OAuth2
- OIDC
- Service-to-service JWT

**Authorization**

- RBAC

**Roles**

- Accountant
- Auditor
- Admin
- ReadOnly

---

## 5. Data Model

### Accounts

```
accounts
--------
id
code
name
type
parent_id
currency
active
created_at
```

### Journal Entries

```
journal_entries
---------------
id
reference_id
description
status
posting_date
created_at
```

### Journal Lines

```
journal_lines
-------------
id
journal_entry_id
account_id
debit_amount
credit_amount
currency
```

### Ledger Entries

```
ledger_entries
--------------
id
journal_entry_id
account_id
amount
direction
currency
posting_timestamp
```

### Accounting Periods

```
accounting_periods
------------------
id
period_start
period_end
status
```

---

## 6. API Requirements

### Create Journal Entry

`POST /v1/journal-entries`

**Request:**

```json
{
  "reference_id": "PAYMENT-123",
  "description": "Customer payment"
}
```

### Add Journal Line

`POST /v1/journal-entries/{id}/lines`

### Post Entry

`POST /v1/journal-entries/{id}/post`

**Validation:**

- Entry balanced
- Period open
- Accounts active

### Reverse Entry

`POST /v1/journal-entries/{id}/reverse`

### Get Account Balance

`GET /v1/accounts/{id}/balance`

### Trial Balance

`GET /v1/reports/trial-balance`

---

## 7. Event Architecture

Publish domain events after successful posting.

**Events**

- JournalPosted
- JournalReversed
- AccountCreated
- PeriodClosed
- PeriodOpened
- ReconciliationCompleted

**Example**

```json
{
  "event_type": "JournalPosted",
  "journal_entry_id": "uuid",
  "posted_at": "timestamp"
}
```

---

## 8. Storage Requirements

### Primary Database

**Recommended:** PostgreSQL

**Requirements**

- ACID transactions
- Row-level locking
- Point-in-time recovery

### Read Models

**Optional**

- PostgreSQL replicas
- Elasticsearch for reporting

### Cache

**Optional:** Redis

**Use for**

- Balance snapshots
- Metadata

**Never cache posting transactions.**

---

## 9. Reporting Requirements

Generate:

- General Ledger
- Trial Balance
- Balance Sheet
- Income Statement
- Account Activity
- Period Activity

Reports must support:

- Date ranges
- Currency filters
- Account filters
- Export (CSV, XLSX)

---

## 10. Observability

**Metrics**

- Journal posting rate
- Posting failures
- Reconciliation lag
- Balance query latency
- Event publishing latency

**Logging**

- Structured JSON logs
- Correlation IDs
- Request tracing

**Tracing**

- OpenTelemetry

---

## 11. Disaster Recovery

| Metric | Target |
|--------|--------|
| RPO | < 5 minutes |
| RTO | < 30 minutes |

**Requirements**

- Daily backups
- PITR enabled
- Multi-region backup copies

---

## 12. Recommended Architecture

For a modern fintech-grade accounting system:

```
                +------------------+
                | Payment Service  |
                +---------+--------+
                          |
                          v
                +------------------+
                | Accounting API   |
                +---------+--------+
                          |
                          v
                +------------------+
                | Posting Engine   |
                +---------+--------+
                          |
             +------------+-------------+
             |                          |
             v                          v
    +----------------+       +------------------+
    | PostgreSQL     |       | Event Bus        |
    | Ledger Store   |       | Kafka/NATS       |
    +----------------+       +------------------+
             |
             v
    +----------------+
    | Reporting      |
    | Read Models    |
    +----------------+
```

### Design recommendation

Model the system as an **immutable ledger from day one**. Avoid storing balances as the source of truth. Store only postings in the ledger and derive balances from them (optionally with snapshots for performance). This simplifies audits, reconciliation, corrections, and regulatory compliance.

---

## 13. Alignment with `core.foundation` (GtelPay)

This TRD uses generic ledger terminology. The platform design in [`core.foundation.md`](./core.foundation.md) maps as follows for implementation in `core.accounting`:

| TRD (this document) | `core.foundation` / GtelPay |
|---------------------|-----------------------------|
| `journal_entries` | `trans` (`use_case`, `business_ref`, `status`, time) |
| `journal_lines` | `trans_data` (`account_code`, DR/CR, amount) |
| Draft → Posted | e.g. deposit **PENDING** → **POSTED** (two-phase with transit **3100**) |
| `reference_id` | `business_ref` (idempotent key, e.g. bank ref) |
| Reversal | Additional `trans` with reversed lines; no edit after close |
| Wallet credit | **Out of scope** for accounting service — event/API to `core.wallet` after POSTED |
| COA account types | Asset, Liability, Transit, Revenue, Expense, Equity (§6 COA in foundation) |

**Invariant (foundation):** every use case leaves transit accounts at **zero** when complete; `sum(DR) = sum(CR)` per `trans`.

**Idempotency (foundation):** duplicate `business_ref` / webhook must not double-post.

---

## Conclusion

The Accounting Service TRD defines domain model, ledger architecture, consistency, APIs, integrations, compliance-oriented auditability, and operational targets before implementation. Implement `core.accounting` against this TRD while honoring boundaries and use cases documented in `core.foundation.md`.
