# Wire ≠ MoMo / Wire không phải MoMo

**EN:** **Wire** is a **super app**: each **Mc** gets a **mini-app inside Wire** (`FrontStoreSheet`), not a separate checkout app. **MoMo-style** wallets redirect the user to a third-party app and back to the **Mc** site—two worlds. We deliberately do **not** copy that mental model.

**VI:** **Wire** là **siêu ứng dụng**: **Mc** có **mini-app bên trong Wire** (`FrontStoreSheet`), không cần app riêng. Mô hình **kiểu MoMo** đưa user sang app ví thứ ba rồi quay lại web **Mc**—hai thế giới tách. Chúng ta **cố ý không** theo lói mòm đó.

---

## Terminology / Thuật ngữ

| Short | Full | Meaning |
|-------|------|---------|
| **Mc** | Merchant | One merchant tenant on the platform (wire `mid`, stall in Wire, orders, menu). **VI đọc:** *Mờ Cê*. |
| **Mcs** | Merchants | The **Merchants host** service: HTTP API, slug pages (`*.nivic.dev`), pay pages, loyalty/chat APIs. Code: [`Merchants/`](../Merchants/). **VI đọc:** *Mờ C S*. |

**EN:** In product docs we use **Mc** / **Mcs** to shorten prose. Repo paths and types may still say `Merchant` / `Merchants`.

**VI:** Trong doc sản phẩm dùng **Mc** (*Mờ Cê*) / **Mcs** (*Mờ C S*) cho gọn. Đường dẫn code và tên type vẫn có thể là `Merchant` / `Merchants`.

---

## One sentence / Một câu

**EN:** **Mc** websites and `*.nivic.dev` are **entry points**; the real experience happens **inside Wire**.

**VI:** Website **Mc** và `*.nivic.dev` chỉ là **cửa vào**; trải nghiệm thật xảy ra **trong Wire**.

---

## MoMo-style vs Wire-style / So sánh

| Topic | MoMo-style (familiar e-wallet) | Wire-style (this product) |
|--------|--------------------------------|---------------------------|
| Who owns payment UX | Third-party wallet app | **Wire app** (super app shell) |
| Mc app | Often separate site + optional SDK | **Mini-app** in Wire (`FrontStoreSheet`)—**Mc** does not ship their own payment app |
| Role of Mc website | Checkout / redirect hub | **Entry** (`bmap`, `slug.nivic.dev`, custom domain)—pull user **into** Wire |
| After pay | User often returns to browser / Mc tab | User stays **in Wire** (confirm sheet, history, other mini-apps) |
| QR at counter | Scan → pay in wallet | Still supported via **`saving://pay?pr=...`** (legacy / counter)—**not** the primary “web → superapp” story |

---

## User journey (target) / Hành trình (mục tiêu)

```mermaid
flowchart LR
  WebEntry["Web slug.nivic.dev or link"]
  Deeplink["saving deeplink or https entry"]
  WireApp["Wire app"]
  FrontStore["FrontStoreSheet mini-app"]
  PayConfirm["PaymentConfirmSheet"]
  WebEntry --> Deeplink --> WireApp --> FrontStore --> PayConfirm
```

**EN:** Ideal path: web or link opens Wire → **Mc** stall → user confirms payment without leaving the super app.

**VI:** Đường lý tưởng: web/link mở Wire → sạp **Mc** → user xác nhận thanh toán không cần ra khỏi siêu app.

---

## Deeplink contract / Hợp đồng deeplink

| Deeplink | Purpose (plain language) | Repo status |
|----------|--------------------------|-------------|
| `saving://store?mid=X` | Open **store** inside Wire | Parser in [`QRScanSheet.kt`](../wire-android/app/src/main/kotlin/app/saving/wire/ui/QRScanSheet.kt) (`StorePayload`); **not wired** on cold start |
| `saving://intent?mid=X&rid=Y&amount=Z` (+ optional `oid`) | Open store with **order ready** → confirm | **Implemented** — **Mc** QR, [`PaymentConfirmSheet.kt`](../wire-android/app/src/main/kotlin/app/saving/wire/ui/PaymentConfirmSheet.kt) |
| `saving://pay?pr=...` | **Counter / signed QR** — scan and pay | **Legacy** — **Mcs** [`handlers.go`](../Merchants/handlers.go), scan → `payMerchant` |
| `https://host/pay/{order_id}` | Web pay page (Shopify-style) | **Implemented** — mobile redirect from **Mcs** [`page.go`](../Merchants/page.go) |

**EN:** For **web → superapp**, prefer **`store`** or **`intent`**, not **`pay?pr`** as the primary “Open in Saving” link.

**VI:** Với **web → superapp**, ưu tiên **`store`** hoặc **`intent`**, không dùng **`pay?pr`** làm link chính “Mở trong Saving”.

---

## Two technical stacks (do not confuse) / Hai stack (đừng nhầm)

| Stack | Transport | Used by Wire app today? | Used by Java Core? |
|--------|-----------|-------------------------|---------------------|
| **Wire TCP** | `wire.nivic.dev:7474`, `WireFrame` | Yes — `createIntent`, `confirmIntent`, `transfer` | No |
| **Sevlet wallet** | HTTP binary + HMAC, WAL, `payment_ledger` | No (Android/iOS use Wire TCP) | Yes — see [ADR 003](adr/003-neo-bank-mid-and-merchant-id.md), [deterministic focus](deterministic-focus.md) |

**EN:** Partner docs about “order payment” and idempotency on `(mid, request_id)` describe the **Java Core** path. The **Wire app** **Mc** flow today is mostly **Wire protocol + Mcs HTTP**, not the servlet POST body.

**VI:** Tài liệu đối tác về order-payment và idempotency `(mid, request_id)` mô tả **Core Java**. App **Wire** với **Mc** hôm nay chủ yếu **Wire protocol + Mcs HTTP**, chưa phải servlet binary.

---

## Roadmap: current vs target / Hiện tại vs mục tiêu

Honest gaps—**not implemented in this doc PR**; listed so readers do not assume superapp web is finished.

| Gap | Current behavior | Target |
|-----|------------------|--------|
| Web mobile after order | [`page.go`](../Merchants/page.go) redirects to `https://.../pay/{order_id}` | Prefer `saving://intent?...` or `saving://store?mid=...` |
| App cold start | [`MainActivity.kt`](../wire-android/app/src/main/kotlin/app/saving/wire/MainActivity.kt) does not handle `ACTION_VIEW` | Parse `intent.data` → `FrontStoreSheet` |
| `StorePayload` | Defined, unused outside parser | Wire scanner + cold start |
| Mcs API | `qr_url: saving://pay?pr=...` in create order / pay page | Web superapp links use `intent` / `store`; keep `pay?pr` for counter QR |

**Suggested implementation order when coding:**

1. Cold-start deeplink in Wire Android (and iOS if applicable).
2. **Mcs** web mobile: emit `intent` or `store` after `POST /public/{mid}/order`.
3. Keep `saving://pay?pr=...` for in-store QR and POS APIs.

---

## Read more / Đọc thêm

- [Payment flow: MiniApp → Wire → Mcs](payment-flow-miniapp.md) — shorter narrative; see [architecture/wire-payment-multitenant.md](architecture/wire-payment-multitenant.md) for full spec
- [Product principles](PRODUCT_PRINCIPLES.md) — Super App vs wallet path (§11)
- [ADR 001: Sevlet wallet wire](adr/001-sevlet-wallet-wire.md)
- [ADR 003: Neo-bank `mid`](adr/003-neo-bank-mid-and-merchant-id.md)
- [Deterministic focus](deterministic-focus.md)
- [Downstream event contract](downstream-event-contract.md)
