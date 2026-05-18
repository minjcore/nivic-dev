package dev.nivic.payment;

import dev.nivic.journal.WalletJournal;
import dev.nivic.ledger.WalletLedger;
import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Currency;
import java.util.Objects;

/**
 * Persists an accepted wallet payload to {@link WalletLedger} and double-entry {@link WalletJournal}.
 * Callers that also use {@link dev.nivic.ledger.PaymentLedger#appendAfterWallet} should invoke it
 * after {@link #record} so {@code payment_ledger} commits in a separate transaction.
 */
public final class LedgerService {

  private final WalletLedger ledger;
  private final WalletJournal journal;

  public LedgerService(WalletLedger ledger, WalletJournal journal) {
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.journal = Objects.requireNonNull(journal, "journal");
  }

  /** Writes one ledger row and matching journal entry + lines. */
  public void record(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    ledger.append(payload, currency);
    journal.append(payload, currency);
  }
}
