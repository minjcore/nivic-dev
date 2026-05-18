package dev.nivic.payment;

import dev.nivic.command.WalletInputOp;
import dev.nivic.ledger.PaymentIntentAppendCtx;
import dev.nivic.ledger.PaymentLedger;
import dev.nivic.merchant.MerchantDisabledException;
import dev.nivic.payment.disruptor.WalletPersistDisruptor;
import dev.nivic.sevlet.SevletWalletPayload;
import dev.nivic.sevlet.idempotency.IdempotencyGate;
import dev.nivic.sevlet.secret.MidProfile;
import dev.nivic.sevlet.secret.MidSecretResolver;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Currency;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/**
 * After HMAC verification, {@link #claimAndPersist(byte[], SevletWalletPayload)} runs idempotency,
 * then WAL; ledger/journal only for {@link PersistKind#FULL_POST} and {@link
 * PersistKind#CONFIRM_SETTLE}.
 */
public final class WalletAcceptService implements AutoCloseable {

  private static final SecureRandom RNG = new SecureRandom();
  private static final int MAX_INTENT_TTL_MINUTES = 24 * 60 * 14;

  private final IdempotencyGate idempotency;
  private final WalService wal;
  private final LedgerService ledgerService;
  private final PaymentLedger paymentLedger;
  private final Currency ledgerCurrency;
  private final MidSecretResolver midSecrets;
  private final WalletPersistDisruptor persistRing;
  private final int intentTtlMinutes;

  public WalletAcceptService(
      IdempotencyGate idempotency,
      WalService wal,
      LedgerService ledgerService,
      PaymentLedger paymentLedger,
      Currency ledgerCurrency,
      MidSecretResolver midSecrets,
      WalletPersistDisruptor persistRing,
      int intentTtlMinutes) {
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.wal = Objects.requireNonNull(wal, "wal");
    this.ledgerService = Objects.requireNonNull(ledgerService, "ledgerService");
    this.paymentLedger = Objects.requireNonNull(paymentLedger, "paymentLedger");
    this.ledgerCurrency = Objects.requireNonNull(ledgerCurrency, "ledgerCurrency");
    this.midSecrets = midSecrets;
    this.persistRing = persistRing;
    this.intentTtlMinutes = intentTtlMinutes <= 0 ? 15 : intentTtlMinutes;
  }

  /** @deprecated use {@link #WalletAcceptService(..., int)} with explicit TTL. */
  public WalletAcceptService(
      IdempotencyGate idempotency,
      WalService wal,
      LedgerService ledgerService,
      PaymentLedger paymentLedger,
      Currency ledgerCurrency,
      MidSecretResolver midSecrets,
      WalletPersistDisruptor persistRing) {
    this(
        idempotency,
        wal,
        ledgerService,
        paymentLedger,
        ledgerCurrency,
        midSecrets,
        persistRing,
        15);
  }

  /**
   * @return duplicate when {@code (mid, requestId)} was already claimed (caller should respond
   *     {@code 409})
   */
  public AcceptResult claimAndPersist(byte[] raw, SevletWalletPayload payload) {
    Objects.requireNonNull(raw, "raw");
    Objects.requireNonNull(payload, "payload");
    MidProfile profile = null;
    if (midSecrets != null) {
      profile = midSecrets.requireProfile(payload.mid());
      if (!profile.merchantConfig().enabled()) {
        throw new MerchantDisabledException(payload.mid());
      }
    }
    boolean orderPaymentMode = profile != null && profile.orderPaymentMode();
    boolean strictOrderIdGate =
        orderPaymentMode && payload.command() == WalletInputOp.TRANSFER;
    if (!idempotency.claimFirst(
        payload.mid(), payload.requestId(), payload.orderId(), strictOrderIdGate)) {
      return AcceptResult.idempotentDuplicate();
    }
    long cmd = payload.command();
    if (cmd == WalletInputOp.CONFIRM_PAYMENT) {
      ConfirmPayloadParser.validateExtra(payload.extraData());
      persist(raw, payload, PersistKind.CONFIRM_SETTLE, PaymentIntentAppendCtx.NONE);
      return AcceptResult.ok();
    }
    if (cmd == WalletInputOp.REJECT_PAYMENT) {
      ConfirmPayloadParser.validateExtra(payload.extraData());
      persist(raw, payload, PersistKind.REJECT_INTENT, PaymentIntentAppendCtx.NONE);
      return AcceptResult.ok();
    }
    if (strictOrderIdGate) {
      paymentLedger.requireNoConflictingOpenIntent(
          payload.mid(), payload.orderId(), payload.requestId());
    }
    boolean fullPost = !orderPaymentMode;
    PersistKind kind = fullPost ? PersistKind.FULL_POST : PersistKind.ORDER_INTENT;
    PaymentIntentAppendCtx ctx = PaymentIntentAppendCtx.NONE;
    PaymentIntentAck ack = null;
    if (kind == PersistKind.ORDER_INTENT) {
      int ttl = effectiveIntentTtlMinutes(profile);
      byte[] challenge = new byte[32];
      RNG.nextBytes(challenge);
      Instant exp = Instant.now().plus(ttl, ChronoUnit.MINUTES);
      ctx = PaymentIntentAppendCtx.orderIntent(ttl, challenge);
      ack = new PaymentIntentAck(Base64.getEncoder().encodeToString(challenge), exp);
    }
    persist(raw, payload, kind, ctx);
    return ack == null ? AcceptResult.ok() : AcceptResult.okWithIntentAck(ack);
  }

  private int effectiveIntentTtlMinutes(MidProfile profile) {
    int base = intentTtlMinutes;
    if (profile != null) {
      base = profile.merchantConfig().intentTtlMinutes().orElse(base);
    }
    if (base <= 0) {
      base = 15;
    }
    return Math.min(base, MAX_INTENT_TTL_MINUTES);
  }

  private void persist(byte[] raw, SevletWalletPayload payload, PersistKind kind, PaymentIntentAppendCtx ctx) {
    if (persistRing != null) {
      try {
        persistRing.publishPersist(raw, payload, kind, ctx);
      } catch (CompletionException e) {
        Throwable c = e.getCause();
        if (c instanceof Error err) {
          throw err;
        }
        if (c instanceof RuntimeException re) {
          throw re;
        }
        throw new IllegalStateException(c);
      }
    } else {
      try {
        wal.append(raw);
        switch (kind) {
          case FULL_POST -> {
            ledgerService.record(payload, ledgerCurrency);
            paymentLedger.appendAfterWallet(payload, ledgerCurrency);
          }
          case ORDER_INTENT -> paymentLedger.append(payload, ledgerCurrency, ctx);
          case CONFIRM_SETTLE -> {
            ledgerService.record(payload, ledgerCurrency);
            paymentLedger.settleIntentByOrder(payload, ledgerCurrency);
          }
          case REJECT_INTENT -> paymentLedger.rejectIntentByOrder(payload, ledgerCurrency);
          default -> throw new IllegalStateException("unknown kind: " + kind);
        }
      } catch (RuntimeException e) {
        throw e;
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (persistRing != null) {
      persistRing.shutdown();
    }
    wal.close();
  }
}
