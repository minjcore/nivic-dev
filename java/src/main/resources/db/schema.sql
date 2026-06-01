-- Sevlet wallet — PostgreSQL schema (matches JDBC ensureTable DDL in the WAR).
--
-- Table prefixes:
--   led_*   — ledger projections (append-only wallet row, payment intent lifecycle)
--   acct_*  — accounting (double-entry journal, account holds)
--   wallet_* / merchant_* — secrets, idempotency, platform config
--
-- Monolithic apply:
--   psql "$JDBC_URL" -f src/main/resources/db/schema.sql
-- Modular apply (same objects, numeric order):
--   psql ... -f db/schema/01_wallet_mid_secret.sql … 11_rename_legacy_led_acct_prefixes.sql

-- HMAC secrets and per-mid payment flags (JdbcMidSecretResolver).
CREATE TABLE IF NOT EXISTS wallet_mid_secret (
  mid BIGINT NOT NULL PRIMARY KEY,
  secret_key BYTEA NOT NULL,
  payment_check_order BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE wallet_mid_secret IS 'Per-merchant HMAC. payment_check_order = order payment: enforce order_id on retries, WAL only (no immediate ledger/journal).';
COMMENT ON COLUMN wallet_mid_secret.mid IS 'merchant_id — same value as wire field mid for merchant-signed payloads.';
COMMENT ON COLUMN wallet_mid_secret.payment_check_order IS 'True: order phase — same order_id on duplicate (mid,request_id); persist raw to WAL only until transaction replay. False: full persist in one step.';

-- Optional per-mid UI / limits (JdbcMidSecretResolver LEFT JOINs this table).
CREATE TABLE IF NOT EXISTS merchant_config (
  mid BIGINT NOT NULL PRIMARY KEY,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  intent_ttl_minutes INTEGER,
  display_name VARCHAR(256)
);

COMMENT ON TABLE merchant_config IS 'Optional; see dev.nivic.merchant.MerchantConfig.';
COMMENT ON COLUMN merchant_config.mid IS 'merchant_id — matches wallet_mid_secret.mid and wire mid.';
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

-- Order-payment intent (JdbcPaymentLedger); written when payment_check_order mid accepts without immediate journal.
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
  intent_status VARCHAR(32),
  expires_at TIMESTAMPTZ,
  confirmed_at TIMESTAMPTZ,
  confirm_challenge BYTEA,
  cancel_reason VARCHAR(512),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE led_payment IS 'Payment intent ledger: initial row or upsert after led_wallet settle; ON CONFLICT keeps order_id and created_at.';
COMMENT ON COLUMN led_payment.input IS 'Wire command opcode (u64).';
COMMENT ON COLUMN led_payment.order_id IS 'From initial insert; appendAfterWallet does not replace.';
COMMENT ON COLUMN led_payment.amount_minor IS 'Amount in ISO 4217 minor units for currency_code.';
COMMENT ON COLUMN led_payment.debit IS 'Unset until settle/replay; no accounts at order-payment phase.';
COMMENT ON COLUMN led_payment.credit IS 'Unset until settle/replay.';
COMMENT ON COLUMN led_payment.intent_status IS 'See dev.nivic.ledger.CoreLedgerStatus (VARCHAR = Enum.name()); null = legacy row.';
COMMENT ON COLUMN led_payment.confirm_challenge IS '32-byte value echoed in CONFIRM / REJECT extraData.';

-- At most one open order-payment intent per (mid, order_id); aligns with CoreLedgerStatus.isOpenForConfirmation().
CREATE UNIQUE INDEX IF NOT EXISTS led_payment_uidx_open_mid_order
  ON led_payment (mid, order_id)
  WHERE intent_status IN ('INITIAL','AWAITING_CONFIRM');

-- Soft holds for order intents (JdbcAccountHoldStore).
CREATE TABLE IF NOT EXISTS acct_account_hold (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  account_id INTEGER NOT NULL,
  amount_minor BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE acct_account_hold IS 'Reserved amount against account_id until intent completes or is released.';

-- Double-entry journal header (JdbcWalletJournal).
CREATE TABLE IF NOT EXISTS acct_journal_entry (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  input BIGINT NOT NULL,
  currency_code VARCHAR(3) NOT NULL,
  extra_data BYTEA NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE acct_journal_entry IS 'Journal voucher header; lines in acct_journal_line.';

CREATE TABLE IF NOT EXISTS acct_journal_line (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  line_no SMALLINT NOT NULL,
  account INTEGER NOT NULL,
  debit_minor BIGINT NOT NULL,
  credit_minor BIGINT NOT NULL,
  PRIMARY KEY (mid, request_id, line_no),
  CONSTRAINT acct_journal_line_entry_fk
    FOREIGN KEY (mid, request_id)
    REFERENCES acct_journal_entry (mid, request_id)
);

COMMENT ON TABLE acct_journal_line IS 'Balanced lines: debit account / credit account for wire amount.';

-- ─────────────────────────────────────────────────────────────────────────────
-- Savings ledger (TigerBeetle-style: Accounts + immutable Transfers)
--   sav_*   prefix
--
-- Balance invariant (credit-normal): available = credits_posted - debits_posted - debits_pending
-- Transfer rows are never updated after INSERT.
-- Two system accounts (owner_mid=0, kind='RESERVE') are upserted by JdbcSavingLedger.ensureTables():
--   SAV-EXTERNAL  — placeholder for the wallet side of deposit/withdrawal
--   SAV-RESERVE   — source of interest credits (debit side of INTEREST transfers)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS sav_account (
  id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_mid         BIGINT      NOT NULL,
  account_no        VARCHAR(32) NOT NULL UNIQUE,
  kind              VARCHAR(16) NOT NULL,          -- DEMAND | TERM | RESERVE
  currency_code     VARCHAR(3)  NOT NULL DEFAULT 'VND',
  debits_pending    BIGINT      NOT NULL DEFAULT 0,
  debits_posted     BIGINT      NOT NULL DEFAULT 0,
  credits_pending   BIGINT      NOT NULL DEFAULT 0,
  credits_posted    BIGINT      NOT NULL DEFAULT 0,
  flags             INTEGER     NOT NULL DEFAULT 0, -- 0x01 CLOSED | 0x02 TERM_LOCKED | 0x04 FROZEN
  interest_rate_bps INTEGER,                        -- basis points, e.g. 650 = 6.5%/year; null for DEMAND
  maturity_at       TIMESTAMPTZ,                    -- null for DEMAND
  opened_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  closed_at         TIMESTAMPTZ,
  version           BIGINT      NOT NULL DEFAULT 0  -- incremented on every balance update
);

COMMENT ON TABLE sav_account IS 'Savings account with TigerBeetle 4-counter balance model.';
COMMENT ON COLUMN sav_account.flags IS '0x01=CLOSED, 0x02=TERM_LOCKED (before maturity), 0x04=FROZEN';

-- Transfer header: lifecycle metadata only (no amount, no account refs).
CREATE TABLE IF NOT EXISTS sav_trans (
  id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  kind              VARCHAR(24) NOT NULL,           -- DEPOSIT | WITHDRAWAL | INTEREST | FEE | PENALTY
  phase             VARCHAR(16) NOT NULL DEFAULT 'POSTED', -- PENDING | POSTED | VOIDED
  pending_id        UUID        REFERENCES sav_trans(id), -- non-null for POSTED/VOIDED settlement rows
  idempotency_key   UUID        UNIQUE,             -- client-supplied; server deduplicates
  ref_mid           BIGINT,                         -- link to wallet mid
  ref_request_id    BIGINT,                         -- link to wallet request_id
  linked_batch_id   UUID,                           -- groups atomic-batch transfers (interest accrual)
  memo              VARCHAR(256),
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE sav_trans IS 'Immutable transfer header; rows are never updated after INSERT.';
COMMENT ON COLUMN sav_trans.pending_id IS 'References the PENDING transfer this row settles (POSTED) or cancels (VOIDED).';

-- Bút toán (double-entry journal lines): one row per leg of each transfer.
-- Standard two-leg transfer: line 1 = debit side, line 2 = credit side.
CREATE TABLE IF NOT EXISTS sav_trans_data (
  trans_id          UUID        NOT NULL REFERENCES sav_trans(id),
  line_no           SMALLINT    NOT NULL,           -- 1 = debit leg, 2 = credit leg
  account_id        UUID        NOT NULL REFERENCES sav_account(id),
  debit_minor       BIGINT      NOT NULL DEFAULT 0,
  credit_minor      BIGINT      NOT NULL DEFAULT 0,
  currency_code     VARCHAR(3)  NOT NULL,
  PRIMARY KEY (trans_id, line_no)
);

COMMENT ON TABLE sav_trans_data IS 'Bút toán: double-entry lines for each transfer. Balanced: sum(debit_minor) = sum(credit_minor) per trans_id.';

-- Lookup transfers by account (either debit or credit side).
CREATE INDEX IF NOT EXISTS sav_trans_data_account_idx
  ON sav_trans_data (account_id);

-- At most one settlement (POSTED or VOIDED) per PENDING transfer.
CREATE UNIQUE INDEX IF NOT EXISTS sav_trans_settled_uidx
  ON sav_trans (pending_id) WHERE pending_id IS NOT NULL;
