# nivic-dev documentation

- [Product principles (EN / VI)](PRODUCT_PRINCIPLES.md)
- [Wire ≠ MoMo (superapp vs wallet redirect)](wire-vs-momo.md)
- [Payment flow: MiniApp → Wire → Mcs](payment-flow-miniapp.md)
- [Multi-tenant Mcs (Shopify-like)](multi-tenant-mcs.md) — `Merchants/` = Mcs host; Postgres RLS: `Merchants/migrations/001_tenant_bills.up.sql`
- [Wire payment + multi-tenant (canonical)](architecture/wire-payment-multitenant.md) — QR → CORE → USER POST → queryOrderStatus
- [Nivic analytics pipeline (SDD)](architecture/nivic-analytics-pipeline.md) — WAL / dual CORE → ClickHouse → Ops Query API
- [Wire payment frame reference](protocol/wire-payment-frames.md)
- [Deterministic focus (Core replay & money)](deterministic-focus.md)
- [ADR 001: Sevlet wallet wire vs compact mental model](adr/001-sevlet-wallet-wire.md)
- [ADR 002: Search boundary với Meilisearch](adr/002-search-boundary-meilisearch.md)
- [ADR 003: Neo-bank domain — single brain, `mid`, and `merchant_id`](adr/003-neo-bank-mid-and-merchant-id.md) — servlet rail
- [ADR 004: Dual CORE rails — `java/` + `saving/`](adr/004-dual-core-rails-java-and-saving.md)
- [ADR 005: Ví vs Sổ cái (Wallet vs Accounting)](adr/005-wallet-vs-accounting-boundary.md)
- [WAL → analytics (ClickHouse / Scylla)](analytics/wal-to-clickhouse.md)
