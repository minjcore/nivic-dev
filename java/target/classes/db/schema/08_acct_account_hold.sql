-- acct_account_hold — soft holds during order intents (JdbcAccountHoldStore)

CREATE TABLE IF NOT EXISTS acct_account_hold (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  account_id INTEGER NOT NULL,
  amount_minor BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE acct_account_hold IS 'Reserved amount against account_id until intent completes or is released.';
