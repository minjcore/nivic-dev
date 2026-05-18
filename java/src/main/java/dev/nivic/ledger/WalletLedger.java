package dev.nivic.ledger;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Currency;

/**
 * Append-only accounting ledger for accepted Sevlet wallet payloads (after idempotency and WAL).
 */
public interface WalletLedger {

  /**
   * Persists one row keyed by {@code (mid, requestId)}. {@code currency} interprets wire {@code
   * amount} as minor units ({@link SevletWalletPayload#amountAsMoney(Currency)}).
   */
  void append(SevletWalletPayload payload, Currency currency);
}
