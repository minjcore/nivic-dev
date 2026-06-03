-- led_wallet — append-only row per accept (JdbcWalletLedger)

CREATE TABLE IF NOT EXISTS led_wallet (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  input BIGINT NOT NULL,
  amount_minor BIGINT NOT NULL,
  debit INTEGER NOT NULL,
  credit INTEGER NOT NULL,
  currency_code VARCHAR(3) NOT NULL,
  extra_data BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE led_wallet IS 'One row per accepted Sevlet wallet message (ledger projection).';
COMMENT ON COLUMN led_wallet.input IS 'Wire command opcode (u64).';
COMMENT ON COLUMN led_wallet.amount_minor IS 'Amount in ISO 4217 minor units for currency_code.';
