-- merchant_config — per-mid display and payment behaviour (joined in JdbcMidSecretResolver)

CREATE TABLE IF NOT EXISTS merchant_config (
  mid BIGINT NOT NULL PRIMARY KEY,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  intent_ttl_minutes INTEGER,
  display_name VARCHAR(256)
);

COMMENT ON TABLE merchant_config IS 'Optional overrides per merchant (mid); absent row = defaults.';
COMMENT ON COLUMN merchant_config.enabled IS 'When false, wallet requests for this mid are rejected after HMAC.';
COMMENT ON COLUMN merchant_config.intent_ttl_minutes IS 'Override order-intent TTL; null = use servlet default.';
COMMENT ON COLUMN merchant_config.display_name IS 'Optional label for ops / future API responses.';
