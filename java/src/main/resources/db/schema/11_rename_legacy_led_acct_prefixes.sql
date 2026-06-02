-- Upgrade path for DBs created before led_* / acct_* prefixes (idempotent).

ALTER TABLE IF EXISTS wallet_ledger RENAME TO led_wallet;
ALTER TABLE IF EXISTS payment_ledger RENAME TO led_payment;
ALTER INDEX IF EXISTS payment_ledger_uidx_open_mid_order RENAME TO led_payment_uidx_open_mid_order;
ALTER TABLE IF EXISTS wallet_journal_entry RENAME TO acct_journal_entry;
ALTER TABLE IF EXISTS wallet_journal_line RENAME TO acct_journal_line;
ALTER TABLE IF EXISTS wallet_account_hold RENAME TO acct_account_hold;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'wallet_journal_line_entry_fk'
  ) THEN
    ALTER TABLE acct_journal_line RENAME CONSTRAINT wallet_journal_line_entry_fk TO acct_journal_line_entry_fk;
  END IF;
END $$;
