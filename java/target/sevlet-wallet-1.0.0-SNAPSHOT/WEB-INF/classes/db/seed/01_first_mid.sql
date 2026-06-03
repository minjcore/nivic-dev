-- First merchant_id / wire mid (bootstrap). Run after schema.sql.
--
-- mid = 1 — merchant_id = 1 (wallet_mid_secret.mid); also USER band per MidConventions for holders.
-- secret_key = 32 zero bytes — DEV ONLY; replace before any shared/staging/prod use.
--
-- psql "$JDBC_URL" -f src/main/resources/db/schema.sql
-- psql "$JDBC_URL" -f src/main/resources/db/seed/01_first_mid.sql

INSERT INTO wallet_mid_secret (mid, secret_key, payment_check_order)
VALUES (1, decode(repeat('00', 32), 'hex'), FALSE)
ON CONFLICT (mid) DO NOTHING;

INSERT INTO merchant_config (mid, enabled, display_name)
VALUES (1, TRUE, 'first_mid')
ON CONFLICT (mid) DO NOTHING;
