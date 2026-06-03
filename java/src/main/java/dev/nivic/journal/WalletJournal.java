package dev.nivic.journal;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Currency;

/**
 * Double-entry journal for accepted Sevlet wallet payloads: one header row and two balanced lines
 * (debit {@link SevletWalletPayload#debit()}, credit {@link SevletWalletPayload#credit()}).
 */
public interface WalletJournal {

  void append(SevletWalletPayload payload, Currency currency);
}
