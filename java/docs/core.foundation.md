# Core Platform Design Document

**Author:** Cao Khang Đoàn  
**Version:** 0.3  
**Last updated:** 2026-06-03  
**Scope:** `10_core/` — `core.foundation`, `core.accounting` (GtelPay Fund Flow)

| Version | Date       | Author         | Reviewer | Ghi chú |
|---------|------------|----------------|----------|---------|
| 0.1     | 2026-06-03 | Cao Khang Đoàn | —        | Bản đầu |

---

## Introduction

This document has two parts: (1) `core.foundation` — shared base layer; (2) `core.accounting` — accounting design including **GtelPay Fund Flow** (COA, trans/trans_data, use cases). Use it for module design, code review, and separating wallet / accounting domains.

---

## 1. Overview

`core.foundation` is the **shared** bottom layer of the `core` library. `core.wallet`, `core.accounting`, and API modules may import it; foundation has no wallet or accounting business logic.

**In foundation:** request (`PageRequest`, sort, filter), response (envelope `code`, `message`, `data`), error (`ErrorCode`, `BaseException`), page (`PageResult`), util (id, time, hash — pure functions, Java).

**Not in foundation:** entity, repository, service, controller, HTTP binding, DB/cache/MQ.

---

## 2. Architecture placement

```
     Application (API, deploy)
              │
     ┌────────┴────────┐
     ▼                 ▼
  core.wallet      core.accounting
 (wallet tables)  (trans tables)
     │                 │
     └────────┬────────┘
              ▼
       core.foundation
              │
              ▼
             Java
```

- HTTP binding and security → Application.
- Business data read/write → `core.wallet` or `core.accounting`.
- Shared library (`core.foundation`) — POJO, envelope, paging, error, util.

Service returns `PageResult`; Application wraps `ApiResponse` and returns JSON to the client.

---

## 3. Wallet and accounting

| | core.wallet | core.accounting |
|---|-------------|-----------------|
| Owns tables | `wallet`, `wallet_balance`, `wallet_tx`, … | `account`, `trans`, `trans_data`, … |
| Must not access | `trans`, `trans_data` tables | Wallet tables, wallet balances |

Each domain core owns all tables for its domain. No shared entity/repository between the two cores.

**May share:** `core.foundation` (request, response, error, page, util).

**Must not share:** DB tables, entities, repositories — even on one physical database, keep logic split by core.

Wallet ↔ accounting sync: **API or event** (saga, outbox); no cross-core repository `JOIN`.

---

## 4. Components

### Request

Input POJOs: `page`, `size`, `sort`, `direction`, `keyword` (per API). HTTP mapping belongs to Application — no HTTP binding in foundation.

### Response

Envelope: `code`, `message`, `data`, optional `timestamp`. One JSON shape for all APIs.

### Error

`ErrorCode` + `BaseException`. Application maps to HTTP status; foundation defines codes and base exceptions only.

### Page

`PageRequest` — paging/sort input. `PageResult<T>` — `content`, `total`, `page`, `size`.

### Util

Id/UUID, time, hash/encode, simple string validation. Pure functions, no DI container.

**Suggested packages:**

```
core.foundation
├── request
├── response
├── exception
├── page
└── util
```

---

## 5. Foundation diagram

```
┌──────────────────────────────────────────┐
│            core.foundation               │
├──────────────────────────────────────────┤
│  Request   → PageRequest, SortParam      │
│  Response  → ApiResponse<T>              │
│  Error     → ErrorCode, BaseException    │
│  Page      → PageResult<T>               │
│  Util      → Id, Date, Hash              │
└──────────────────────────────────────────┘
                      │
                      ▼
                   JDK only
```

---

## 6. Dependency rules

```
Application  ──►  core.*  ──►  foundation  ──►  JDK
```

- Application, `core.wallet`, `core.accounting` may import foundation.
- foundation must not import entity, service, or repository.
- foundation must not use SQL, ORM, Redis, Kafka, or web libraries.
- No cross-import of repositories between wallet and accounting.
- Foundation unit tests do not require running the full application.

---

## 7. Example flow

1. Client: `GET /items?page=0&size=20&sort=createdAt,desc`
2. Application maps query → `PageRequest`, calls service.
3. Service in `core.wallet` / `core.accounting` queries that core's tables → `PageResult`.
4. Application: `ApiResponse.ok(pageResult)` → JSON.

Foundation is used in steps 2–4; it does not open DB connections.

---

## 8. Checklist for adding code to foundation

- Reused in **two or more** modules?
- No entity, no DB/cache access?
- Pure unit tests, no need to run the app?
- Class name not tied to a domain (Wallet, Trans…) — unless it belongs in `core.wallet` / `core.accounting`?

If any check fails → put code in the domain core or Application. Do not put `trans` tables in `core.wallet`.

---

## Conclusion

`core.foundation` is the **shared library (foundation)** for the system — request/response envelope, paging, errors, util; no domain tables. `core.wallet` and `core.accounting` keep separate tables and business logic; only foundation is shared. Clear boundaries reduce cross-dependencies and make each domain easier to maintain and deploy.

---

# core.accounting — accounting design

## Introduction

This part describes the `core.accounting` module: accounts, transactions (`trans` / `trans_data`), and posting rules within the accounting domain.

**Reference:** GtelPay Fund Flow (mẫu 100,000đ/giao dịch | phí dịch vụ 1,000đ | phí Napas/NH 500đ).

**In core.accounting:** entities, repositories, and services for `account`, `trans`, `trans_data`, and related tables.

**Not in core.accounting:** wallet balance mutations (`2110`, `2120`, `2130`) — those belong to `core.wallet`. Accounting records the trans side; wallet syncs via **API or event (saga/outbox)**.

---

## 1. Goals

- Record `trans_data` lines (debit/credit) under one `trans`.
- Query balances by accounting account from `trans` / `trans_data`.
- Period close / reporting (if required by the business).
- Maintain Chart of Accounts (COA): Tài sản, Nợ phải trả, Transit, Doanh thu, Chi phí, Vốn.
- Enforce balance invariants; zero-out transit accounts after each use case.
- EOD Settlement & Clearing as independent batch process.

---

## 2. Owned tables

| Table | Role |
|-------|------|
| `account` | Chart of accounts (code, name, type) |
| `trans` | One business transaction: use_case, business_ref, time, status; header for a balanced posting (sum DR = sum CR) |
| `trans_data` | Line per account: `trans_id`, account_code, DR/CR, amount |

All writes go through repositories inside `core.accounting` only.

---

## 3. Luồng ghi sổ (trong `core.accounting`)

1. Nhận yêu cầu ghi sổ: `use_case`, `business_ref`, các dòng DR/CR.
2. Validate COA, kỳ mở/đóng, `sum(DR) = sum(CR)`, transit về 0 khi hoàn tất use case.
3. Lưu một `trans` + các dòng `trans_data` (immutable sau chốt kỳ).
4. Use case cần cập nhật ví: sau khi `status = POSTED`, thông báo sang `core.wallet` (event hoặc API) — **không** import repository wallet.

---

## 4. Rules

- Access only tables listed in section 2.
- `trans_data` lines are **immutable** after period close (adjust via reversing/additional `trans` — if the business requires it).
- Every use case must leave its transit account at **zero** before completion.
- Settlement & Clearing runs as **independent EOD batch** — not inline with payment.
- `core.accounting` must **not** import wallet repositories; sync via event/API only.
- External integrations → Application or dedicated integration layer.

---

## 5. Balance invariants

| # | Invariant |
|---|-----------|
| 1 | `(1111 + 1112 + 1113) = (2110 + 2120 + 2130)` — bank assets = total wallet liabilities |
| 2 | `TK NH thực = Tổng Wallet Balance` |
| 3 | Each transit account (3100–3820) returns to **0** after its use case completes |

Initial capital (Vốn TGTT) is a buffer; day-to-day ops run on user deposits.

---

## 6. Chart of Accounts (COA)

### 6.1 NHÓM 1 — TÀI SẢN

| Mã TK | Tên Tài Khoản |
|-------|---------------|
| 1111 | TK Vietinbank — Chuyên dùng |
| 1112 | TK Napas Clearing |
| 1113 | TK VPBank — QR/POS |

### 6.2 NHÓM 2 — NỢ PHẢI TRẢ

| Mã TK | Tên Tài Khoản |
|-------|---------------|
| 2110 | Wallet Balance — User |
| 2120 | Wallet Balance — Merchant |
| 2130 | Ký quỹ — Đối tác Chi hộ |

### 6.3 NHÓM 3 — TRANSIT

| Mã TK | Tên Tài Khoản | Use case |
|-------|---------------|----------|
| 3100 | Transit — Nạp tiền | §8 Deposit |
| 3200 | Transit — Rút tiền | §9 Withdraw |
| 3300 | Transit — Chuyển tiền nội bộ | §10 Internal |
| 3400 | Transit — IBFT | §11 IBFT |
| 3500 | Transit — Thanh toán | §12, §13 Payment |
| 3600 | Transit — Chi Lương | §14 Payroll |
| 3700 | Transit — Chi hộ | §15 Disbursement |
| 3800 | Transit — Clearing | §16 Settlement EOD |
| 3810 | Transit — Settlement Outbound | §16 Settlement EOD |
| 3820 | Transit — MDR Holdback | §16 Settlement EOD |

### 6.4 NHÓM 4 — DOANH THU

4110 Phí nạp | 4120 Phí rút | 4130 Phí chuyển | 4140 Phí MDR | 4150 Phí Chi Lương / Chi hộ

### 6.5 NHÓM 5 — CHI PHÍ

5100 Chi phí Phí NH / Napas

### 6.6 NHÓM 6 — VỐN

6000 Vốn chủ sở hữu

---

## 7. System initialization (before go-live)

| Step | Account | DR/CR | Amount |
|------|---------|-------|--------|
| Chuyển vốn vào TK Vietinbank | 1111 | DR | 1,000,000,000 |
| Ghi nhận vốn điều lệ | 6000 | CR | 1,000,000,000 |
| Chuyển vốn vào TK VPBank | 1113 | DR | 500,000,000 |
| Ghi nhận vốn điều lệ | 6000 | CR | 500,000,000 |
| Nạp quỹ TK Napas Clearing | 1112 | DR | 500,000,000 |
| Ghi nhận vốn điều lệ | 6000 | CR | 500,000,000 |

---

## 8. Use case — Nạp tiền (Deposit)

**Mẫu:** 100,000đ gốc + 1,000đ phí nạp → user nhận **99,000đ** vào ví.

### 8.1 Bút toán (accounting)

| Step | Actor | Account | DR/CR | Amount |
|------|-------|---------|-------|--------|
| 1 | NH | 1111 | DR | 100,000 |
| 2 | NH | 3100 | CR | 100,000 |
| 3 | NH | 3100 | DR | 100,000 |
| 4 | User | 2110 | CR | 99,000 |
| 5 | User | 2110 | DR | 1,000 |
| 6 | User | 4110 | CR | 1,000 |

**Result:** `1111 +100,000` | `2110 +99,000` | `4110 +1,000` | Transit 3100 = 0

### 8.2 Trạng thái giao dịch (`trans.status`)

| Status | Ý nghĩa |
|--------|---------|
| `PENDING` | NH báo tiền vào, ghi transit 3100, chưa cộng ví |
| `POSTED` | Xác nhận OK, cộng ví + ghi phí, transit 3100 = 0 |
| `FAILED` | Hủy / không khớp — reverse `trans` nếu đã ghi bước 1–2 |

### 8.3 Luồng tổng quan (2 phase)

```
User     Vietinbank    Application      core.accounting    core.wallet      DB
  |            |              |                  |                |              |
  |-- CK 100k->|              |                  |                |              |
  |            |-- webhook -->|                  |                |              |
  |            |              | Phase A: ghi PENDING             |              |
  |            |              |----------------->|                |              |
  |            |              |                  | 1111 DR 100k     |------------->|
  |            |              |                  | 3100 CR 100k     |              |
  |            |              | map VA → userId  |                |              |
  |            |              | Phase B: ghi POSTED              |              |
  |            |              |----------------->|                |              |
  |            |              |                  | 3100 DR, 2110,4110|              |
  |            |              |                  | 3100 = 0         |              |
  |            |              | thông báo cộng ví|---------------->| +99k net     |
  |<-- notify -|              |                  |                |              |
```

Phase A: webhook NH → `trans.status = PENDING`, transit **3100** giữ 100k.  
Phase B: confirm → `POSTED`, **3100 = 0**, cộng ví net 99k (sau bút toán §8.1 bước 3–6).

### 8.4 Trạng thái DB

#### Sau phase A (`PENDING`)

| `trans` | |
|---------|--|
| `use_case` | `DEPOSIT` |
| `business_ref` | mã giao dịch NH (unique) |
| `status` | `PENDING` |

| `trans_data` | account | DR | CR |
|--------------|---------|----|----|
| line 1 | 1111 | 100,000 | 0 |
| line 2 | 3100 | 0 | 100,000 |

#### Sau phase B (`POSTED`)

| `trans` | |
|---------|--|
| `status` | `POSTED` |

| `trans_data` (thêm bước 3–6) | account | DR | CR |
|-------------------------------|---------|----|----|
| | 3100 | 100,000 | 0 |
| | 2110 | 0 | 99,000 |
| | 2110 | 1,000 | 0 |
| | 4110 | 0 | 1,000 |

Ví user: `wallet_balance += 99,000` (chỉ sau POSTED; đồng bộ qua event/API, không JOIN repo accounting).

### 8.5 Idempotent & lỗi

| Tình huống | Xử lý |
|------------|--------|
| Webhook trùng `bankRef` | Return existing `transId`, không insert |
| Confirm khi `status=POSTED` | No-op |
| Không map được VA → user | Giữ `PENDING`, 3100 giữ 100k, ops manual |
| Wallet credit fail sau POSTED | Retry consumer; **không** sửa `trans_data` — reconcile job |
| NH báo sai số tiền | Không confirm; reverse bước A bằng `trans` đảo dấu |

### 8.6 Invariant sau khi POSTED

```
1111  += 100,000
2110  +=  99,000   (mirror wallet_balance user)
4110  +=   1,000
3100  =  0
wallet_balance(user) = 99,000 (net)
```

### 8.7 Kết luận

Nạp tiền GtelPay là luồng **hai phase** gắn với transit **3100**:

1. **Phase A (PENDING):** Webhook NH ghi **1111** DR và **3100** CR — chưa cộng ví, chưa ghi **4110**.
2. **Phase B (POSTED):** Map VA → user, ghi bước 3–6 §8.1 → **3100 = 0**, **2110** net +99k, **4110** +1k phí → cộng ví 99k.

| Khía cạnh | Quyết định |
|-----------|------------|
| Ranh giới | Accounting sở hữu `trans`/`trans_data`; wallet sở hữu số dư — đồng bộ event/API, không JOIN repo |
| Idempotent | `business_ref` = mã giao dịch NH — webhook/confirm trùng không double-post |
| Transit | **3100 = 0** khi POSTED |
| Lỗi sau POSTED | Không sửa `trans_data`; retry đồng bộ ví hoặc reconcile |

---

## 9. Use case — Rút tiền (Withdraw)

101,000đ trừ ví (100,000đ gốc + 1,000đ phí)

| Step | Actor | Account | DR/CR | Amount |
|------|-------|---------|-------|--------|
| 1 | User | 2110 | DR | 101,000 |
| 2 | User | 3200 | CR | 101,000 |
| 3 | NH | 3200 | DR | 100,000 |
| 4 | NH | 1111 | CR | 100,000 |
| 5 | NH | 3200 | DR | 1,000 |
| 6 | NH | 4120 | CR | 1,000 |

**Result:** `2110 -101,000` | `1111 -100,000` | `4120 +1,000` | Transit 3200 = 0

---

## 10. Use case — Chuyển ví → ví (Internal)

| Step | Actor | Account | DR/CR | Amount |
|------|-------|---------|-------|--------|
| 1 | User A | 2110 | DR | 101,000 |
| 2 | User A | 3300 | CR | 101,000 |
| 3 | User B | 3300 | DR | 100,000 |
| 4 | User B | 2110 | CR | 100,000 |
| 5 | User A | 3300 | DR | 1,000 |
| 6 | User A | 4130 | CR | 1,000 |

**Result:** Transit 3300 = 0 | No bank movement

---

## 11. Use case — IBFT (Liên ngân hàng)

| Step | Actor | Account | DR/CR | Amount |
|------|-------|---------|-------|--------|
| 1 | User | 2110 | DR | 101,000 |
| 2 | User | 3400 | CR | 101,000 |
| 3 | User | 3400 | DR | 1,000 |
| 4 | User | 4130 | CR | 1,000 |
| 5 | NH | 3400 | DR | 100,000 |
| 6 | NH | 1112 | CR | 100,000 |
| 8 | NH | 5100 | DR | 500 |
| 9 | NH | 1112 | CR | 500 |

**Result:** Transit 3400 = 0 | Net profit +500

---

## 12. Use case — Thanh toán QR/POS

Settlement về NH merchant → §16 EOD.

| Step | Actor | Account | DR/CR | Amount |
|------|-------|---------|-------|--------|
| 1 | NH | 1113 | DR | 100,000 |
| 2 | NH | 3500 | CR | 100,000 |
| 3 | NH | 5100 | DR | 500 |
| 4 | NH | 1113 | CR | 500 |
| 5 | Merchant | 3500 | DR | 100,000 |
| 6 | Merchant | 2120 | CR | 100,000 |

**Result:** Transit 3500 = 0 | `2120` chờ Settlement

---

## 13. Use case — Thanh toán bằng ví

| Step | Actor | Account | DR/CR | Amount |
|------|-------|---------|-------|--------|
| 1 | User | 2110 | DR | 100,000 |
| 2 | User | 3500 | CR | 100,000 |
| 3 | Merchant | 3500 | DR | 100,000 |
| 4 | Merchant | 2120 | CR | 100,000 |

**Result:** Transit 3500 = 0 | `2120` chờ Settlement

---

## 14. Use case — Chi lương

5 NV × 100,000đ + 5,000đ phí

| Step | Actor | Account | DR/CR | Amount |
|------|-------|---------|-------|--------|
| 1 | Merchant | 2120 | DR | 505,000 |
| 2 | Merchant | 3600 | CR | 505,000 |
| 3 | Merchant | 3600 | DR | 5,000 |
| 4 | Merchant | 4150 | CR | 5,000 |
| 5 | NH | 3600 | DR | 500,000 |
| 6 | NH | 1112 | CR | 500,000 |
| 8 | NH | 5100 | DR | 2,500 |
| 9 | NH | 1112 | CR | 2,500 |

**Result:** Transit 3600 = 0 | Net profit +2,500

---

## 15. Use case — Chi hộ

**Pre-fund:** 1111 DR 100,000 → 2130 CR 100,000

**Disbursement:** 2130 DR 101,000 → 3700 CR → 1112 CR 100,000 + phí 4150/5100

**Result:** Transit 3700 = 0

---

## 16. Use case — Settlement & Clearing (EOD)

```
2120 → 3800 (lock) → 3820 (MDR) + 3810 (net) → 1112 → NH Merchant
```

| Step | Account | DR/CR | Amount |
|------|---------|-------|--------|
| Lock merchant | 2120 | DR | 200,000 |
| Hold clearing | 3800 | CR | 200,000 |
| Split MDR | 3800 DR 2,000 → 3820 CR 2,000 → 4140 CR 2,000 |
| Settlement | 3810 DR 198,000 → 1112 CR 198,500 (incl. Napas fee 5100) |

**Result:** All transit = 0 | `2120` = 0 after settlement

Exception path: hoàn 3810/3820 → 3800 → 2120 nếu đối soát không khớp.

---

## Conclusion

`core.accounting` owns accounting end-to-end: COA, `trans` / `trans_data`, transit accounts, and EOD settlement (GtelPay Fund Flow). Wallet balances stay in `core.wallet`; accounting syncs via events, never cross-repository JOIN. Depends on shared library (`core.foundation`).
