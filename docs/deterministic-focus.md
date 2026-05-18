# Deterministic focus (Core)

**Goal:** the same **ordered** accepted inputs reproduce the same **financial projection** (ledger, payment intent rows, journal lines) without hidden ambient state.

## What we treat as deterministic

1. **Append-only WAL** — byte records in order; replay is the source of truth for “what Core accepted.”
2. **Money on the wire** — amounts as **integer minor units** + ISO currency code (`Money`, `SevletWalletPayload#amount`); no `float` / `double` for balances.
3. **Idempotency keys** — `(mid, request_id)` (and order-payment rules on `order_id`) so retries do not double-post.
4. **Explicit command routing** — opcode → `WalletInputCommands`; behavior tabled, not inferred from side effects.
5. **Ledger / journal writes** — driven from decoded payload + configured currency; same payload sequence → same rows (modulo schema migrations).

## What is intentionally *not* deterministic (documented exceptions)

1. **Intent confirm challenge** — `SecureRandom` bytes; security property, not replay identity.
2. **Clocks** — `expires_at`, reconciliation windows; bounded by policy, not bitwise replay of timestamps across machines.
3. **External I/O** — DB sequence `created_at` default `NOW()`, host time; **semantic** replay matches, **wall-clock** literals may differ unless frozen in tests.

## Engineering habits

- Prefer **pure functions** for amount math and command validation; push I/O to the edges (`WalletAcceptService`, JDBC).
- Tests: **fixed** `Currency`, payloads, and ordering; avoid `Instant.now()` in assertions unless injected or faked.
- New features: ask “**can a second process replay the WAL and get the same balances?**” If no, isolate that path (e.g. bank host, fraud score) outside the deterministic core.

## Related docs

- [ADR 001: Sevlet wallet wire](adr/001-sevlet-wallet-wire.md)
- [WAL → analytics](analytics/wal-to-clickhouse.md) (downstream may be lossy; Core replay stays strict)
