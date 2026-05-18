package dev.nivic.ledger;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Objects;

/** In-process ledger for dev/tests (not durable across restarts). */
public final class MemoryWalletLedger implements WalletLedger {

  private final CopyOnWriteArrayList<LedgerEntry> entries = new CopyOnWriteArrayList<>();

  @Override
  public void append(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    byte[] extra = payload.extraData();
    entries.add(
        new LedgerEntry(
            payload.command(),
            payload.mid(),
            payload.requestId(),
            payload.orderId(),
            payload.amount(),
            payload.debit(),
            payload.credit(),
            currency.getCurrencyCode(),
            Arrays.copyOf(extra, extra.length)));
  }

  /** Snapshot for assertions or debugging (copy). */
  public List<LedgerEntry> snapshot() {
    return new ArrayList<>(entries);
  }

  public record LedgerEntry(
      long command,
      long mid,
      long requestId,
      long orderId,
      long amountMinor,
      int debit,
      int credit,
      String currencyCode,
      byte[] extraData) {}
}
