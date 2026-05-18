-- wallet_journal_line — lines referencing entry (JdbcWalletJournal); apply after 04_wallet_journal_entry.sql

CREATE TABLE IF NOT EXISTS wallet_journal_line (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  line_no SMALLINT NOT NULL,
  account INTEGER NOT NULL,
  debit_minor BIGINT NOT NULL,
  credit_minor BIGINT NOT NULL,
  PRIMARY KEY (mid, request_id, line_no),
  CONSTRAINT wallet_journal_line_entry_fk
    FOREIGN KEY (mid, request_id)
    REFERENCES wallet_journal_entry (mid, request_id)
);

COMMENT ON TABLE wallet_journal_line IS 'Balanced lines: debit account / credit account for wire amount.';
