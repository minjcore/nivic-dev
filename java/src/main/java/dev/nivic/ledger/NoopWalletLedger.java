package dev.nivic.ledger;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Currency;
import java.util.Objects;

/** Ledger implementation that does nothing (e.g. {@code ledgerStorage=skip}). */
public final class NoopWalletLedger implements WalletLedger {

  @Override
  public void append(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
  }
}
