-- led_payment — order-payment phase (JdbcPaymentLedger); WAL companion, no journal lines

CREATE TABLE IF NOT EXISTS led_payment (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  input BIGINT NOT NULL,
  amount_minor BIGINT NOT NULL,
  debit INTEGER,
  credit INTEGER,
  currency_code VARCHAR(3) NOT NULL,
  extra_data BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE led_payment IS 'Initial row (nullable debit/credit) or upsert after settle; ON CONFLICT updates command/amount/accounts/extra, preserves order_id and created_at.';
COMMENT ON COLUMN led_payment.input IS 'Wire command opcode (u64).';
COMMENT ON COLUMN led_payment.order_id IS 'Authoritative from initial insert; not overwritten by appendAfterWallet upsert.';
COMMENT ON COLUMN led_payment.amount_minor IS 'Amount in ISO 4217 minor units for currency_code.';
COMMENT ON COLUMN led_payment.debit IS 'Unset until settle/replay; no accounts at order-payment phase.';
COMMENT ON COLUMN led_payment.credit IS 'Unset until settle/replay.';
