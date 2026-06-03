-- Order intent lifecycle + user confirm challenge (extends led_payment)

ALTER TABLE led_payment ADD COLUMN IF NOT EXISTS intent_status VARCHAR(32);
ALTER TABLE led_payment ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE led_payment ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ;
ALTER TABLE led_payment ADD COLUMN IF NOT EXISTS confirm_challenge BYTEA;
ALTER TABLE led_payment ADD COLUMN IF NOT EXISTS cancel_reason VARCHAR(512);

COMMENT ON COLUMN led_payment.intent_status IS 'dev.nivic.ledger.CoreLedgerStatus.name(); null for legacy rows.';
COMMENT ON COLUMN led_payment.expires_at IS 'When INITIAL intent auto-expires if not confirmed.';
COMMENT ON COLUMN led_payment.confirm_challenge IS '32-byte secret user must echo in CONFIRM extraData.';
