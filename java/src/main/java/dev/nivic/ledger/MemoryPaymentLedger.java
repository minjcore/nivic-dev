package dev.nivic.ledger;

import dev.nivic.payment.ConfirmPayloadParser;
import dev.nivic.sevlet.SevletWalletPayload;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process {@link PaymentLedger} for dev/tests (not durable across restarts). */
public final class MemoryPaymentLedger implements PaymentLedger {

  private final CopyOnWriteArrayList<PaymentEntry> entries = new CopyOnWriteArrayList<>();

  @Override
  public void append(SevletWalletPayload payload, Currency currency, PaymentIntentAppendCtx ctx) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(ctx, "ctx");
    byte[] extra = payload.extraData();
    CoreLedgerStatus st = null;
    Instant exp = null;
    byte[] ch = null;
    if (ctx.isOrderIntent()) {
      st = CoreLedgerStatus.defaultForNewIntentRow();
      exp = Instant.now().plus(ctx.ttlMinutes(), ChronoUnit.MINUTES);
      ch = ctx.confirmChallenge();
    }
    entries.add(
        new PaymentEntry(
            payload.command(),
            payload.mid(),
            payload.requestId(),
            payload.orderId(),
            payload.amount(),
            null,
            null,
            currency.getCurrencyCode(),
            Arrays.copyOf(extra, extra.length),
            st,
            exp,
            ch == null ? null : Arrays.copyOf(ch, ch.length),
            null));
  }

  @Override
  public void appendAfterWallet(SevletWalletPayload payload, Currency currency) {
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(currency, "currency");
    byte[] extra = payload.extraData();
    long orderId = payload.orderId();
    for (int i = 0; i < entries.size(); i++) {
      PaymentEntry e = entries.get(i);
      if (e.mid() == payload.mid() && e.requestId() == payload.requestId()) {
        orderId = e.orderId();
        break;
      }
    }
    var settled =
        new PaymentEntry(
            payload.command(),
            payload.mid(),
            payload.requestId(),
            orderId,
            payload.amount(),
            payload.debit(),
            payload.credit(),
            currency.getCurrencyCode(),
            Arrays.copyOf(extra, extra.length),
            CoreLedgerStatus.SETTLED,
            null,
            null,
            Instant.now());
    for (int i = 0; i < entries.size(); i++) {
      PaymentEntry e = entries.get(i);
      if (e.mid() == payload.mid() && e.requestId() == payload.requestId()) {
        entries.set(i, settled);
        return;
      }
    }
    entries.add(settled);
  }

  @Override
  public void settleIntentByOrder(SevletWalletPayload confirmPayload, Currency currency) {
    Objects.requireNonNull(confirmPayload, "confirmPayload");
    Objects.requireNonNull(currency, "currency");
    byte[] ch = ConfirmPayloadParser.challenge(confirmPayload.extraData());
    for (int i = 0; i < entries.size(); i++) {
      PaymentEntry e = entries.get(i);
      if (e.mid() == confirmPayload.mid()
          && e.orderId() == confirmPayload.orderId()
          && e.intentStatus() != null
          && e.intentStatus().isOpenForConfirmation()
          && e.confirmChallenge() != null
          && Arrays.equals(e.confirmChallenge(), ch)) {
        entries.set(
            i,
            new PaymentEntry(
                confirmPayload.command(),
                e.mid(),
                e.requestId(),
                e.orderId(),
                confirmPayload.amount(),
                confirmPayload.debit(),
                confirmPayload.credit(),
                currency.getCurrencyCode(),
                Arrays.copyOf(confirmPayload.extraData(), confirmPayload.extraData().length),
                CoreLedgerStatus.SETTLED,
                e.expiresAt(),
                e.confirmChallenge(),
                Instant.now()));
        return;
      }
    }
    throw new IllegalStateException("no intent to settle for order");
  }

  @Override
  public void requireNoConflictingOpenIntent(long mid, long orderId, long requestId) {
    for (PaymentEntry e : entries) {
      if (e.mid() == mid
          && e.orderId() == orderId
          && e.requestId() != requestId
          && e.intentStatus() != null
          && e.intentStatus().isOpenForConfirmation()) {
        throw new OrderIdConflictException(mid, orderId, e.requestId());
      }
    }
  }

  @Override
  public void rejectIntentByOrder(SevletWalletPayload rejectPayload, Currency currency) {
    Objects.requireNonNull(rejectPayload, "rejectPayload");
    Objects.requireNonNull(currency, "currency");
    byte[] ch = ConfirmPayloadParser.challenge(rejectPayload.extraData());
    for (int i = 0; i < entries.size(); i++) {
      PaymentEntry e = entries.get(i);
      if (e.mid() == rejectPayload.mid()
          && e.orderId() == rejectPayload.orderId()
          && e.intentStatus() != null
          && e.intentStatus().isOpenForConfirmation()
          && e.confirmChallenge() != null
          && Arrays.equals(e.confirmChallenge(), ch)) {
        entries.set(
            i,
            new PaymentEntry(
                e.command(),
                e.mid(),
                e.requestId(),
                e.orderId(),
                e.amountMinor(),
                e.debit(),
                e.credit(),
                e.currencyCode(),
                e.extraData(),
                CoreLedgerStatus.CANCELLED,
                e.expiresAt(),
                e.confirmChallenge(),
                e.confirmedAt()));
        return;
      }
    }
    throw new IllegalStateException("no intent to reject for order");
  }

  /** Snapshot for assertions or debugging (copy). */
  public List<PaymentEntry> snapshot() {
    return new ArrayList<>(entries);
  }

  public record PaymentEntry(
      long command,
      long mid,
      long requestId,
      long orderId,
      long amountMinor,
      Integer debit,
      Integer credit,
      String currencyCode,
      byte[] extraData,
      CoreLedgerStatus intentStatus,
      Instant expiresAt,
      byte[] confirmChallenge,
      Instant confirmedAt) {}
}
