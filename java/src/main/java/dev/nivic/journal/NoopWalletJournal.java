package dev.nivic.journal;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Currency;
import java.util.Objects;

/** No-op journal ({@code journalStorage=skip}). */
public final class NoopWalletJournal implements WalletJournal {

  @Override
  public void append(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
  }
}
