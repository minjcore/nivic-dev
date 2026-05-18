package dev.nivic.ledger;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Currency;
import java.util.Objects;

/** No-op payment ledger (e.g. {@code paymentLedgerStorage=skip}). */
public final class NoopPaymentLedger implements PaymentLedger {

  @Override
  public void append(SevletWalletPayload payload, Currency currency, PaymentIntentAppendCtx ctx) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(ctx, "ctx");
  }

  @Override
  public void appendAfterWallet(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
  }

  @Override
  public void settleIntentByOrder(SevletWalletPayload confirmPayload, Currency currency) {
    Objects.requireNonNull(confirmPayload, "confirmPayload");
    Objects.requireNonNull(currency, "currency");
  }

  @Override
  public void rejectIntentByOrder(SevletWalletPayload rejectPayload, Currency currency) {
    Objects.requireNonNull(rejectPayload, "rejectPayload");
    Objects.requireNonNull(currency, "currency");
  }

  @Override
  public void requireNoConflictingOpenIntent(long mid, long orderId, long requestId) {}
}
