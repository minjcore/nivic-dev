package dev.nivic.payment.disruptor;

import dev.nivic.ledger.PaymentIntentAppendCtx;
import dev.nivic.payment.PersistKind;
import dev.nivic.sevlet.SevletWalletPayload;
import java.util.concurrent.CompletableFuture;

/** Ring-buffer slot for {@link WalletPersistDisruptor}; cleared after each handler run. */
public final class WalletRingEvent {

  byte[] rawCopy;
  SevletWalletPayload payload;
  CompletableFuture<Void> done;
  PersistKind kind;
  PaymentIntentAppendCtx intentCtx;

  void clear() {
    rawCopy = null;
    payload = null;
    done = null;
    kind = null;
    intentCtx = null;
  }
}
