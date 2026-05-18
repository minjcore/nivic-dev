-- One open intent per (mid, order_id); matches PaymentLedger.requireNoConflictingOpenIntent / CoreLedgerStatus.isOpenForConfirmation().
CREATE UNIQUE INDEX IF NOT EXISTS payment_ledger_uidx_open_mid_order
  ON payment_ledger (mid, order_id)
  WHERE intent_status IN ('INITIAL','AWAITING_CONFIRM');
