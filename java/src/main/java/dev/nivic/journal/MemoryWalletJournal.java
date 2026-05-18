package dev.nivic.journal;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory journal for dev/tests. */
public final class MemoryWalletJournal implements WalletJournal {

  private final CopyOnWriteArrayList<JournalRecord> records = new CopyOnWriteArrayList<>();

  @Override
  public void append(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    long amt = payload.amount();
    byte[] extra = payload.extraData();
    JournalEntry head =
        new JournalEntry(
            payload.mid(),
            payload.requestId(),
            payload.orderId(),
            payload.command(),
            currency.getCurrencyCode(),
            Arrays.copyOf(extra, extra.length));
    JournalLine l1 =
        new JournalLine(payload.mid(), payload.requestId(), 1, payload.debit(), amt, 0L);
    JournalLine l2 =
        new JournalLine(payload.mid(), payload.requestId(), 2, payload.credit(), 0L, amt);
    records.add(new JournalRecord(head, List.of(l1, l2)));
  }

  public List<JournalRecord> snapshot() {
    return new ArrayList<>(records);
  }

  public record JournalRecord(JournalEntry entry, List<JournalLine> lines) {}

  public record JournalEntry(
      long mid,
      long requestId,
      long orderId,
      long command,
      String currencyCode,
      byte[] extraData) {}

  public record JournalLine(
      long mid,
      long requestId,
      int lineNo,
      int account,
      long debitMinor,
      long creditMinor) {}
}
