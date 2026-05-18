-- payment_ledger — order-payment phase (JdbcPaymentLedger); WAL companion, no journal lines

CREATE TABLE IF NOT EXISTS payment_ledger (
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
  intent_status VARCHAR(32),
  expires_at TIMESTAMPTZ,
  confirmed_at TIMESTAMPTZ,
  confirm_challenge BYTEA,
  cancel_reason VARCHAR(512),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE payment_ledger IS 'Initial row (nullable debit/credit) or upsert after settle; ON CONFLICT updates command/amount/accounts/extra, preserves order_id and created_at.';
COMMENT ON COLUMN payment_ledger.input IS 'Wire command opcode (u64).';
COMMENT ON COLUMN payment_ledger.order_id IS 'Authoritative from initial insert; not overwritten by appendAfterWallet upsert.';
COMMENT ON COLUMN payment_ledger.amount_minor IS 'Amount in ISO 4217 minor units for currency_code.';
COMMENT ON COLUMN payment_ledger.debit IS 'Unset until settle/replay; no accounts at order-payment phase.';
COMMENT ON COLUMN payment_ledger.credit IS 'Unset until settle/replay.';
