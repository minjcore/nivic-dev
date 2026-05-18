-- wallet_journal_entry — double-entry header (JdbcWalletJournal)

CREATE TABLE IF NOT EXISTS wallet_journal_entry (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  input BIGINT NOT NULL,
  currency_code VARCHAR(3) NOT NULL,
  extra_data BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE wallet_journal_entry IS 'Journal voucher header; lines in wallet_journal_line.';
