-- One open intent per (mid, order_id) for INITIAL / AWAITING_CONFIRM
CREATE UNIQUE INDEX IF NOT EXISTS led_payment_uidx_open_mid_order
  ON led_payment (mid, order_id)
  WHERE intent_status IN ('INITIAL', 'AWAITING_CONFIRM');
