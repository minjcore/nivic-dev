-- Soft hold on debit account for order intents (released on settle / expire / reject)

CREATE TABLE IF NOT EXISTS wallet_account_hold (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  account_id INTEGER NOT NULL,
  amount_minor BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE wallet_account_hold IS 'Reserved amount against account_id until intent completes or is released.';
