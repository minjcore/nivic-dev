package dev.nivic.payment.disruptor;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import dev.nivic.ledger.PaymentIntentAppendCtx;
import dev.nivic.ledger.PaymentLedger;
import dev.nivic.payment.LedgerService;
import dev.nivic.payment.PersistKind;
import dev.nivic.payment.WalService;
import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Arrays;
import java.util.Currency;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * LMAX Disruptor ring: single consumer appends WAL then applies {@link PersistKind} — full post,
 * order intent, confirm settle, or reject. Use {@link #publishPersist} (replaces legacy boolean
 * recordLedger API).
 */
public final class WalletPersistDisruptor {

  private final Disruptor<WalletRingEvent> disruptor;
  private final RingBuffer<WalletRingEvent> ringBuffer;
  private final WalService wal;
  private final LedgerService ledger;
  private final PaymentLedger paymentLedger;
  private final Currency ledgerCurrency;

  public WalletPersistDisruptor(
      WalService wal,
      LedgerService ledger,
      PaymentLedger paymentLedger,
      Currency ledgerCurrency,
      int ringBufferSize) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.paymentLedger = Objects.requireNonNull(paymentLedger, "paymentLedger");
    this.ledgerCurrency = Objects.requireNonNull(ledgerCurrency, "ledgerCurrency");
    int size = normalizeRingSize(ringBufferSize);
    this.disruptor =
        new Disruptor<>(
            WalletRingEvent::new,
            size,
            DaemonThreadFactory.INSTANCE,
            ProducerType.MULTI,
            new BlockingWaitStrategy());
    disruptor.handleEventsWith(this::onEvent);
    this.ringBuffer = disruptor.start();
  }

  private void onEvent(WalletRingEvent event, long sequence, boolean endOfBatch) {
    CompletableFuture<Void> done = event.done;
    try {
      wal.append(event.rawCopy);
      switch (event.kind) {
        case FULL_POST -> {
          ledger.record(event.payload, ledgerCurrency);
          paymentLedger.appendAfterWallet(event.payload, ledgerCurrency);
        }
        case ORDER_INTENT ->
            paymentLedger.append(
                event.payload, ledgerCurrency, Objects.requireNonNullElse(event.intentCtx, PaymentIntentAppendCtx.NONE));
        case CONFIRM_SETTLE -> {
          ledger.record(event.payload, ledgerCurrency);
          paymentLedger.settleIntentByOrder(event.payload, ledgerCurrency);
        }
        case REJECT_INTENT -> paymentLedger.rejectIntentByOrder(event.payload, ledgerCurrency);
        default -> throw new IllegalStateException("unknown kind: " + event.kind);
      }
      done.complete(null);
    } catch (Throwable t) {
      done.completeExceptionally(t);
    } finally {
      event.clear();
    }
  }

  /**
   * Copies {@code raw} on the calling thread, then waits until the consumer finishes.
   *
   * @throws java.util.concurrent.CompletionException on WAL / ledger failure
   */
  public void publishPersist(
      byte[] raw, SevletWalletPayload payload, PersistKind kind, PaymentIntentAppendCtx intentCtx) {
    Objects.requireNonNull(raw, "raw");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(kind, "kind");
    CompletableFuture<Void> done = new CompletableFuture<>();
    byte[] copy = Arrays.copyOf(raw, raw.length);
    PaymentIntentAppendCtx ctx = intentCtx == null ? PaymentIntentAppendCtx.NONE : intentCtx;
    ringBuffer.publishEvent(
        (event, sequence, buffer) -> {
          event.rawCopy = buffer;
          event.payload = payload;
          event.done = done;
          event.kind = kind;
          event.intentCtx = ctx;
        },
        copy);
    done.join();
  }

  /** Stops consumer threads after in-flight events complete (call before closing {@link WalService}). */
  public void shutdown() {
    disruptor.shutdown();
  }

  /** Next power-of-two in {@code [8, 2^24]}, suitable for the ring buffer. */
  public static int normalizeRingSize(int requested) {
    if (requested < 8) {
      return 8;
    }
    if (requested > 1 << 24) {
      return 1 << 24;
    }
    int n = Integer.highestOneBit(requested);
    if (n == requested) {
      return n;
    }
    return n << 1;
  }
}
