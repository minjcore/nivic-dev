# Ops — platform control plane

HTTP admin for cross-merchant operations: overview, merchant suspend/activate, settlement, reconcile, and proxy to **Saving Wire admin** (`WIRE_ADMIN_URL`).

Ops does **not** run its own migrations; it reads the **Merchants** Postgres database (`merchants`, `orders`, …). Run migrate once via Merchants `OpenStore` before starting Ops.

## Quick start (local)

```bash
# 1) Postgres + schema (from repo root)
chmod +x Ops/dev-ops.sh
./Ops/dev-ops.sh migrate

# 2) Start server (foreground, default :9010)
export OPS_TOKEN=dev-ops-test
./Ops/dev-ops.sh start

# 3) In another terminal — API smoke test
./Ops/dev-ops.sh test

# Or one shot: migrate + background server + test
./Ops/dev-ops.sh all
```

Open **http://localhost:9010/** → login with token `dev-ops-test` (or your `OPS_TOKEN`).

## Environment

| Variable | Default | Purpose |
|----------|---------|---------|
| `MERCHANTS_DB` | `postgres://merchants:merchants123@localhost/merchants?sslmode=disable` | Merchants Postgres DSN |
| `OPS_ADDR` | `:9010` | Listen address |
| `OPS_TOKEN` | `dev-ops-test` (use `dev-ops-test` locally; binary default is `change-me-in-production`) | Bearer token for `/api/*` |
| `WIRE_ADMIN_URL` | `http://localhost:7475` | Saving admin HTTP (proxy `/api/wire/*`) |
| `WIRE_M2M_TOKEN` | _(empty)_ | Optional M2M token for Wire admin |

Align `MERCHANTS_DB` with [`Merchants/merchants.kson`](../Merchants/merchants.kson) `db.dsn`.

## Optional dependencies

- **Saving admin** on `7475` for Wire tabs in the UI (`/api/wire/...` proxy).
- **Merchants** on `8090` is not required for Ops DB reads, but you need merchants/orders data for non-empty dashboards.

## API (after login)

- `POST /api/login` — body `{"token":"<OPS_TOKEN>"}`
- `GET /api/overview` — `Authorization: Bearer <OPS_TOKEN>`
- `GET /api/merchants`
- `POST /api/merchants/{mid}/suspend|activate`
- `GET /api/settlement?from=<ms>&to=<ms>`
- `GET /api/reconcile?from=<ms>&to=<ms>`
