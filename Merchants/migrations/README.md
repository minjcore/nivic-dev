# Mcs Postgres migrations

Apply when Mcs uses PostgreSQL instead of SQLite (`orders` in [`store.go`](../store.go)).

```bash
psql "$DATABASE_URL" -f migrations/001_tenant_bills.up.sql
```

Before each request/transaction, set tenant context (see [`repository/merchant.go`](../repository/merchant.go)):

```sql
SELECT set_config('app.current_tenant_id', '<mid>', true);
```

App DB user must **not** have `BYPASSRLS`. Map columns:

| `tenant_bills` | SQLite `orders` / Wire |
|----------------|-------------------------|
| `tenant_id` | `mid` |
| `bill_number` | `request_id` or internal bill id |
| `gateway_order_id` | `orders.id` (oid string) |
| `status` | `pending` / `paid` |
