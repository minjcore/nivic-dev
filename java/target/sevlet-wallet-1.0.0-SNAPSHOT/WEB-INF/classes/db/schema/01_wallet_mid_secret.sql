-- wallet_mid_secret — HMAC + mid policy (JdbcMidSecretResolver)

CREATE TABLE IF NOT EXISTS wallet_mid_secret (
  mid BIGINT NOT NULL PRIMARY KEY,
  secret_key BYTEA NOT NULL,
  payment_check_order BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE wallet_mid_secret IS 'Per-merchant HMAC. payment_check_order = order payment: WAL only until replay; enforce order_id on retries.';
COMMENT ON COLUMN wallet_mid_secret.mid IS 'merchant_id — same value as wire field mid for merchant-signed payloads.';
COMMENT ON COLUMN wallet_mid_secret.payment_check_order IS 'Order payment phase: no immediate ledger/journal; transaction from WAL later.';

-- Optional per-mid overrides: db/schema/09_merchant_config.sql (enabled, intent_ttl_minutes, display_name).
