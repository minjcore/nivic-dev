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

-- ─────────────────────────────────────────────────────────────────────────────
-- GtelPay Fund Flow Ledger — Chart of Accounts (COA)
--   coa_*   prefix
--
-- Balance convention: balance_minor = Σdebit − Σcredit
--   ASSET / EXPENSE (debit-normal): positive balance = healthy
--   LIABILITY / REVENUE / EQUITY / TRANSIT (credit-normal): negative = healthy
--   TRANSIT accounts must always be 0 after each complete flow
--
-- JdbcFundFlowLedger.ensureSchema() seeds coa_account on first startup.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS coa_account (
  code          VARCHAR(10)  PRIMARY KEY,
  name          VARCHAR(256) NOT NULL,
  kind          VARCHAR(16)  NOT NULL,  -- ASSET|LIABILITY|TRANSIT|REVENUE|EXPENSE|EQUITY
  balance_minor BIGINT       NOT NULL DEFAULT 0,
  version       BIGINT       NOT NULL DEFAULT 0
);

-- Frozen rule (defense-in-depth chống âm): số dư không sang chiều sai theo bản chất tài khoản.
-- LIABILITY/TRANSIT/REVENUE: balance ≤ 0 (ví/transit/doanh thu không ngược chiều).
-- EXPENSE: balance ≥ 0. ASSET (1112/1113 outflow) & EQUITY (6100 lỗ): không ràng buộc.
-- NOT VALID: thêm an toàn lên DB có sẵn (vẫn enforce mọi write mới).
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'coa_account_balance_chk') THEN
    ALTER TABLE coa_account ADD CONSTRAINT coa_account_balance_chk CHECK (
      CASE kind
        WHEN 'LIABILITY' THEN balance_minor <= 0
        WHEN 'TRANSIT'   THEN balance_minor <= 0
        WHEN 'REVENUE'   THEN balance_minor <= 0
        WHEN 'EXPENSE'   THEN balance_minor >= 0
        ELSE TRUE
      END
    ) NOT VALID;
  END IF;
END $$;

COMMENT ON TABLE  coa_account               IS 'Chart of Accounts with running balance. balance_minor = Σdebit − Σcredit.';
COMMENT ON COLUMN coa_account.kind          IS 'ASSET/EXPENSE=debit-normal; LIABILITY/REVENUE/EQUITY/TRANSIT=credit-normal';
COMMENT ON COLUMN coa_account.balance_minor IS 'Σdebit − Σcredit across all posted transactions.';

-- Journal header: one row per business event (nạp/rút/IBFT/…).
CREATE TABLE IF NOT EXISTS coa_trans (
  id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  ref_id     VARCHAR(128) UNIQUE,      -- idempotency / external bank reference
  memo       VARCHAR(512),
  created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE coa_trans        IS 'Journal entry header. ref_id used for idempotency.';
COMMENT ON COLUMN coa_trans.ref_id IS 'Unique external reference (bank ref, confirm ref, …).';

-- Bút toán lines: balanced per trans_id (Σdebit_minor = Σcredit_minor).
CREATE TABLE IF NOT EXISTS coa_trans_data (
  trans_id      UUID        NOT NULL REFERENCES coa_trans(id),
  line_no       SMALLINT    NOT NULL,
  account_code  VARCHAR(10) NOT NULL REFERENCES coa_account(code),
  debit_minor   BIGINT      NOT NULL DEFAULT 0,
  credit_minor  BIGINT      NOT NULL DEFAULT 0,
  currency_code VARCHAR(3)  NOT NULL DEFAULT 'VND',
  party_mid     BIGINT,      -- analytic dimension: ví/đối tác (subledger của control account, vd 2110)
  PRIMARY KEY (trans_id, line_no)
);

COMMENT ON TABLE coa_trans_data IS 'Bút toán: double-entry lines. Invariant: Σdebit_minor = Σcredit_minor per trans_id.';
COMMENT ON COLUMN coa_trans_data.party_mid IS 'Sổ chi tiết: số dư ví user X = Σ(credit−debit) dòng 2110 có party_mid=X; tổng mọi party = số dư 2110.';

CREATE INDEX IF NOT EXISTS coa_trans_data_account_idx ON coa_trans_data (account_code);

-- Subsidiary-ledger scans (số dư ví theo party).
CREATE INDEX IF NOT EXISTS coa_trans_data_party_idx
  ON coa_trans_data (account_code, party_mid) WHERE party_mid IS NOT NULL;

-- ── Maker-checker (4-eyes) — staging đề xuất bút toán ───────────────────────
-- Maker tạo đề xuất (PENDING, chưa đụng số dư); Checker (≠ maker) duyệt → post vào
-- sổ cái (sinh coa_trans), hoặc từ chối. Bảng workflow nên CHO PHÉP update (khác
-- coa_trans append-only).
CREATE TABLE IF NOT EXISTS coa_proposal (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  ref_id          VARCHAR(128) UNIQUE,
  memo            VARCHAR(512),
  maker_id        VARCHAR(64)  NOT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING|APPROVED|REJECTED
  checker_id      VARCHAR(64),
  reason          VARCHAR(512),
  posted_trans_id UUID,                                     -- coa_trans sinh khi APPROVED
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  decided_at      TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS coa_proposal_line (
  proposal_id  UUID        NOT NULL REFERENCES coa_proposal(id),
  line_no      SMALLINT    NOT NULL,
  account_code VARCHAR(10) NOT NULL,
  debit_minor  BIGINT      NOT NULL DEFAULT 0,
  credit_minor BIGINT      NOT NULL DEFAULT 0,
  party_mid    BIGINT,
  PRIMARY KEY (proposal_id, line_no)
);

CREATE INDEX IF NOT EXISTS coa_proposal_status_idx ON coa_proposal (status, created_at DESC);

-- ── Frozen rules (triggers) — đóng băng sổ nhật ký ──────────────────────────
-- 1) Append-only: cấm UPDATE/DELETE bút toán đã ghi (TRUNCATE admin vẫn cho phép).
CREATE OR REPLACE FUNCTION coa_forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'coa journal is append-only: % on % is forbidden', TG_OP, TG_TABLE_NAME;
END $$;

DROP TRIGGER IF EXISTS coa_trans_no_mutation ON coa_trans;
CREATE TRIGGER coa_trans_no_mutation BEFORE UPDATE OR DELETE ON coa_trans
  FOR EACH ROW EXECUTE FUNCTION coa_forbid_mutation();

DROP TRIGGER IF EXISTS coa_trans_data_no_mutation ON coa_trans_data;
CREATE TRIGGER coa_trans_data_no_mutation BEFORE UPDATE OR DELETE ON coa_trans_data
  FOR EACH ROW EXECUTE FUNCTION coa_forbid_mutation();

-- 2) Deferred double-entry: tại COMMIT, mỗi trans_id phải có Σdebit = Σcredit.
CREATE OR REPLACE FUNCTION coa_check_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE dr BIGINT; cr BIGINT;
BEGIN
  SELECT COALESCE(SUM(debit_minor),0), COALESCE(SUM(credit_minor),0)
    INTO dr, cr FROM coa_trans_data WHERE trans_id = NEW.trans_id;
  IF dr <> cr THEN
    RAISE EXCEPTION 'unbalanced journal %: debit=% credit=%', NEW.trans_id, dr, cr
      USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;

DROP TRIGGER IF EXISTS coa_trans_data_balanced ON coa_trans_data;
CREATE CONSTRAINT TRIGGER coa_trans_data_balanced AFTER INSERT ON coa_trans_data
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION coa_check_balanced();

-- ── COA seed — 23 accounts from GtelPay Fund Flow document ───────────────────
INSERT INTO coa_account (code, name, kind) VALUES
  -- Nhóm 1: Tài sản
  ('1111', 'TK Vietinbank Chuyên dùng',      'ASSET'),
  ('1112', 'TK Napas Clearing',               'ASSET'),
  ('1113', 'TK VPBank - QR/POS',              'ASSET'),
  -- Nhóm 2: Nợ phải trả
  ('2110', 'Wallet Balance - User',           'LIABILITY'),
  ('2120', 'Wallet Balance Merchant',         'LIABILITY'),
  ('2130', 'Ký quỹ - Đối tác Chi hộ',         'LIABILITY'),
  ('2140', 'Tiền gửi tiết kiệm',              'LIABILITY'),
  -- Nhóm 3: Transit
  ('3100', 'Transit - Nạp tiền',              'TRANSIT'),
  ('3200', 'Transit - Rút tiền',              'TRANSIT'),
  ('3300', 'Transit - Chuyển tiền nội bộ',    'TRANSIT'),
  ('3400', 'Transit - IBFT',                  'TRANSIT'),
  ('3500', 'Transit - Thanh toán',            'TRANSIT'),
  ('3600', 'Transit - Chi Lương',             'TRANSIT'),
  ('3700', 'Transit - Chi hộ',                'TRANSIT'),
  ('3800', 'Transit - Clearing',              'TRANSIT'),
  ('3810', 'Transit - Settlement Outbound',   'TRANSIT'),
  ('3820', 'Transit - MDR Holdback',          'TRANSIT'),
  -- Nhóm 4: Doanh thu
  ('4110', 'Doanh thu Phí nạp tiền',          'REVENUE'),
  ('4120', 'Doanh thu Phí rút tiền',          'REVENUE'),
  ('4130', 'Doanh thu Phí chuyển tiền',       'REVENUE'),
  ('4140', 'Doanh thu Phí MDR',               'REVENUE'),
  ('4150', 'Doanh thu Phí Chi Lương/Chi hộ',  'REVENUE'),
  -- Nhóm 5: Chi phí
  ('5100', 'Chi phí Phí NH / Napas',          'EXPENSE'),
  ('5200', 'Chi phí lãi tiền gửi',            'EXPENSE'),
  -- Nhóm 6: Vốn
  ('6000', 'Vốn chủ sở hữu',                  'EQUITY'),
  ('6100', 'Lợi nhuận giữ lại',               'EQUITY')
ON CONFLICT (code) DO NOTHING;
