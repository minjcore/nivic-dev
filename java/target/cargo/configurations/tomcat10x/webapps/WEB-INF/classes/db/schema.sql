-- Sevlet wallet — PostgreSQL schema (matches JDBC ensureTable DDL in the WAR).
-- Monolithic apply:
--   psql "$JDBC_URL" -f src/main/resources/db/schema.sql
-- Modular apply (same objects, numeric order):
--   psql ... -f db/schema/01_wallet_mid_secret.sql
--   ... through 09_merchant_config.sql

-- HMAC secrets and per-mid payment flags (JdbcMidSecretResolver).
CREATE TABLE IF NOT EXISTS wallet_mid_secret (
  mid BIGINT NOT NULL PRIMARY KEY,
  secret_key BYTEA NOT NULL,
  payment_check_order BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE wallet_mid_secret IS 'Per-merchant HMAC. payment_check_order = order payment: enforce order_id on retries, WAL only (no immediate ledger/journal).';
COMMENT ON COLUMN wallet_mid_secret.payment_check_order IS 'True: order phase — same order_id on duplicate (mid,request_id); persist raw to WAL only until transaction replay. False: full persist in one step.';

-- Optional per-mid UI / limits (JdbcMidSecretResolver LEFT JOINs this table).
CREATE TABLE IF NOT EXISTS merchant_config (
  mid BIGINT NOT NULL PRIMARY KEY,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  intent_ttl_minutes INTEGER,
  display_name VARCHAR(256)
);

COMMENT ON TABLE merchant_config IS 'Optional; see dev.nivic.merchant.MerchantConfig.';
COMMENT ON COLUMN merchant_config.enabled IS 'False rejects wallet traffic for this mid (after HMAC).';
COMMENT ON COLUMN merchant_config.intent_ttl_minutes IS 'Order-intent TTL override; null = servlet default.';

-- Idempotency claims (JdbcIdempotencyGate).
CREATE TABLE IF NOT EXISTS wallet_idempotency (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  order_id BIGINT,
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE wallet_idempotency IS 'Dedupe (mid, request_id). order_id used for order-payment mids (compare on duplicate).';
COMMENT ON COLUMN wallet_idempotency.order_id IS 'First-seen orderId; mismatched retry under order-payment mode → 409.';

-- Append-only ledger row per accepted payload (JdbcWalletLedger).
CREATE TABLE IF NOT EXISTS wallet_ledger (
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

COMMENT ON TABLE wallet_ledger IS 'One row per accepted Sevlet wallet message.';
COMMENT ON COLUMN wallet_ledger.input IS 'Wire command opcode (u64).';
COMMENT ON COLUMN wallet_ledger.amount_minor IS 'Amount in ISO 4217 minor units for currency_code.';

-- Order-payment intent (JdbcPaymentLedger); written when payment_check_order mid accepts without immediate journal.
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

COMMENT ON TABLE payment_ledger IS 'Initial intent and/or upsert after wallet_ledger settle; ON CONFLICT keeps order_id and created_at.';
COMMENT ON COLUMN payment_ledger.input IS 'Wire command opcode (u64).';
COMMENT ON COLUMN payment_ledger.order_id IS 'From initial insert; appendAfterWallet does not replace.';
COMMENT ON COLUMN payment_ledger.amount_minor IS 'Amount in ISO 4217 minor units for currency_code.';
COMMENT ON COLUMN payment_ledger.debit IS 'Unset until settle/replay; no accounts at order-payment phase.';
COMMENT ON COLUMN payment_ledger.credit IS 'Unset until settle/replay.';
COMMENT ON COLUMN payment_ledger.intent_status IS 'See dev.nivic.ledger.CoreLedgerStatus (VARCHAR = Enum.name()); null = legacy row.';
COMMENT ON COLUMN payment_ledger.confirm_challenge IS '32-byte value echoed in CONFIRM / REJECT extraData.';

-- At most one open order-payment intent per (mid, order_id); aligns with CoreLedgerStatus.isOpenForConfirmation().
CREATE UNIQUE INDEX IF NOT EXISTS payment_ledger_uidx_open_mid_order
  ON payment_ledger (mid, order_id)
  WHERE intent_status IN ('INITIAL','AWAITING_CONFIRM');

-- Soft holds for order intents (JdbcAccountHoldStore).
CREATE TABLE IF NOT EXISTS wallet_account_hold (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  account_id INTEGER NOT NULL,
  amount_minor BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (mid, request_id)
);

-- Double-entry journal header (JdbcWalletJournal).
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
