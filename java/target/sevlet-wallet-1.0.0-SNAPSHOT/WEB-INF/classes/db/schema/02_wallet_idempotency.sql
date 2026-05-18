-- wallet_idempotency — first-success (mid, request_id) (JdbcIdempotencyGate)

CREATE TABLE IF NOT EXISTS wallet_idempotency (
  mid BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  order_id BIGINT,
  PRIMARY KEY (mid, request_id)
);

COMMENT ON TABLE wallet_idempotency IS 'Dedupe (mid, request_id); order_id for order-payment mids.';
COMMENT ON COLUMN wallet_idempotency.order_id IS 'Stored orderId; duplicate with different orderId rejects when mid is order-payment.';
