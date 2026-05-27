# Multi-tenant Mcs (Shopify-like) / Đa tenant kiểu Shopify

**EN:** **One platform, many shops.** Every **Mc** runs on the **same** **Mcs** codebase and **Wire** rails; tenants differ by **`mid`**, secrets, slug/domain, and config—not by forking the payment engine per partner.

**VI:** **Một nền tảng, nhiều cửa hàng.** Mỗi **Mc** dùng **chung** code **Mcs** và đường ray **Wire**; khác nhau ở **`mid`**, secret, slug/domain, config—**không** deploy engine riêng từng đối tác.

Terminology: **Mc** (*Mờ Cê*), **Mcs** (*Mờ C S*) — [Wire ≠ MoMo](wire-vs-momo.md).

---

## Repo: `Merchants/` = Mcs host / Thư mục `Merchants/` = Mcs

**EN:** In this monorepo, **[`Merchants/`](../Merchants/)** is the **Mcs** service: one Go process, one SQLite/Postgres tenant DB, HTTP for **all Mc** shops. The folder name says “Merchants” (plural) because it hosts **many tenants**, not one merchant.

**VI:** Trong monorepo, **[`Merchants/`](../Merchants/)** là service **Mcs**: một process Go, một DB, HTTP cho **mọi Mc**. Tên thư mục “Merchants” = **nhiều tenant**, không phải một shop.

| Path | Role in multi-tenant |
|------|----------------------|
| [`Merchants/`](../Merchants/) | **Mcs** — orders, slug pages, `pay/`, chat, loyalty, deeplink helpers |
| [`wire-android/`](../wire-android/) | **Wire** app — same binary; opens **Mc** by `mid` / deeplink |
| [`saving/`](../saving/) | **Wire Server** (TCP) — `payment_intents`, ledger per `mid` |
| [`java/`](../java/) | **Core** (optional path) — `merchant_config`, `wallet_mid_secret` per `mid` |

```
nivic-dev/
  Merchants/     ← Mcs (multi-tenant Mc API + web entry)
  wire-android/  ← Wire host app
  saving/        ← Wire TCP + payment_intents
  java/          ← Sevlet wallet Core (per-mid secrets)
```

**EN:** You do **not** add `Merchants/shop-a/` and `Merchants/shop-b/` per Mc—tenancy is **data** (`mid`, slug), not **folders**.

**VI:** **Không** tạo `Merchants/shop-a/`, `Merchants/shop-b/` cho từng Mc—tenant nằm ở **dữ liệu** (`mid`, slug), không phải **thư mục**.

---

## GitHub Pages mental model / Mô hình GitHub Pages

| GitHub Pages | Mcs + Mc |
|--------------|----------|
| One **Pages** platform (GitHub infra) | One **Mcs** process ([`Merchants/`](../Merchants/)) |
| Many sites, **one codebase** to serve them | Many **Mc**, **one** `handlers.go` / `page.go` |
| `username.github.io` or `org.github.io` | `{slug}.nivic.dev` (tenant by subdomain) |
| **Custom domain** → same Pages backend | **Custom domain** → `GetByDomain` → same Mcs |
| Content isolated per **repo / owner** | Data isolated per **`mid`** (orders, menu, token) |
| You don’t fork GitHub for each site | You don’t fork **Mcs** for each Mc |

**EN:** Like GitHub Pages: **one host**, **many front doors** (subdomain or CNAME), **shared engine**. Mc “sites” are rows + routes, not separate deployables.

**VI:** Giống **GitHub Pages**: **một host**, **nhiều cửa** (subdomain hoặc CNAME), **engine chung**. “Site” của Mc là **dữ liệu + route**, không phải deploy riêng từng shop.

---

## Shopify mental model / Mô hình Shopify

| Shopify | Nivic / Wire |
|---------|----------------|
| One Shopify platform | One **Mcs** host + one **Wire Server** + one **Wire** super app |
| Many **stores** | Many **Mc** tenants (`mid`) |
| `myshop.shopify.com` or custom domain | `slug.nivic.dev` or **custom domain** → same HTML/API, different `mid` |
| Store theme / catalog per shop | Menu, orders, loyalty **per `mid`** in Mcs DB |
| Checkout still Shopify’s rails | Pay still **Wire TCP** + shared idempotency rules |

**EN:** You do **not** ship a custom payment binary per Mc. You ship **tenancy** on shared code.

**VI:** **Không** build binary thanh toán riêng từng **Mc**. Bạn bán **tenant** trên **một** codebase.

---

## What is shared vs per Mc / Chung gì, riêng gì

| Shared (one ruleset) | Per Mc (tenant) |
|----------------------|-----------------|
| Mcs HTTP routes (`/merchants/{mid}/...`, `/pay/...`) | Row in `merchants` + orders scoped by `mid` |
| Wire protocol (`CREATE_INTENT`, `CONFIRM_INTENT`, …) | `mid` on wire = tenant id |
| Idempotency `(mid, request_id)` | Merchant token, Ed25519 keypair |
| `payment_intents` schema | Rows keyed by `mid` |
| Wire app binary (FrontStoreSheet, Search) | Which stall opens = which `mid` / slug |
| Java Core wire + WAL (when used) | `wallet_mid_secret`, `merchant_config` per `mid` |

Code: slug routing [`Merchants/handlers.go`](../Merchants/handlers.go) (`*.nivic.dev`, custom domain); config [`09_merchant_config.sql`](../java/src/main/resources/db/schema/09_merchant_config.sql).

---

## Postgres + RLS (optional Mcs DB) / Postgres + RLS (DB Mcs tùy chọn)

**EN:** Today Mcs uses **SQLite** (`orders` keyed by `mid`). For Postgres, use **`tenant_bills`** with **Row-Level Security** so each Mc session only touches its rows—even if application code omits `WHERE tenant_id`.

**VI:** Hiện Mcs dùng **SQLite** (`orders` theo `mid`). Khi lên **Postgres**, dùng **`tenant_bills`** + **RLS** để mỗi phiên Mc chỉ đụng dữ liệu của mình.

| Artifact | Path |
|----------|------|
| Migration up/down | [`Merchants/migrations/001_tenant_bills.up.sql`](../Merchants/migrations/001_tenant_bills.up.sql), [`.down.sql`](../Merchants/migrations/001_tenant_bills.down.sql) |
| Go repository (after CORE `queryOrderStatus`) | [`Merchants/repository/merchant.go`](../Merchants/repository/merchant.go) |

Flow: Wire App USER POST proof → Mcs verifies with CORE → `UpdateBillToPaid(tenantID, billNumber)` inside transaction with `set_config('app.current_tenant_id', …)`.

See also [Wire payment + multi-tenant](architecture/wire-payment-multitenant.md).

---

## How a new Mc joins / Mc mới vào hệ

1. **Onboard** → Mcs assigns **`mid`**, **token**, **slug** (e.g. `https://{slug}.nivic.dev`).
2. Optional **custom domain** (CNAME) → same middleware, `GetByDomain`.
3. **Mc** appears as a **stall / mini-app** inside Wire (`FrontStoreSheet`); same app build for all users.
4. Payments use the **same** intent/ledger rules; only numbers and secrets change.

---

## Relation to other docs / Liên hệ doc khác

- §3 in [Product principles](PRODUCT_PRINCIPLES.md) — many stalls, one ruleset
- [ADR 003: `mid` / merchant_id](adr/003-neo-bank-mid-and-merchant-id.md) — single brain, tenant id on wire
- [Wire ≠ MoMo](wire-vs-momo.md) — superapp, not per-Mc apps
- [Payment flow: MiniApp](payment-flow-miniapp.md) — one server, many `payment_intents` rows per `mid`

---

## One line for partners / Một cây cho đối tác

**EN:** “Shopify-like” = **multi-tenant Mcs**: same product, many **Mc** shops, isolated by **`mid`** and policy—not MoMo-style separate wallet products per merchant.

**VI:** “Giống Shopify” = **Mcs đa tenant**: cùng sản phẩm, nhiều shop **Mc**, tách bằng **`mid`** và policy—không phải mỗi **Mc** một ví/app riêng kiểu MoMo.

**GitHub Pages:** “Giống Pages” = **`slug.nivic.dev`** hoặc domain riêng trỏ về **cùng Mcs**; khác **Mc** vì **slug/mid**, không vì server khác.
