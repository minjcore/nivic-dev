package dev.nivic.ledger;

import dev.nivic.sevlet.SevletWalletPayload;
import java.util.Currency;

/**
 * Append-only <strong>order-payment</strong> projection ({@code payment_ledger}): intent accepted
 * into Core with idempotency, without {@link dev.nivic.ledger.WalletLedger} / journal until settle/replay.
 *
 * <p>{@link #append} stores intent without accounts; {@link #appendAfterWallet} runs after
 * {@code wallet_ledger}/journal in a <strong>separate</strong> JDBC transaction (settled row with
 * {@code debit}/{@code credit}). On upsert, {@code order_id} stays the value from {@link #append}
 * when that row already exists.</p>
 *
 * <p>Confirm/reject settle rows keyed by {@code (mid, order_id)} with matching {@code
 * confirm_challenge}; intent rows remain keyed by {@code (mid, request_id)}.</p>
 */
public interface PaymentLedger {

  /**
   * Initial / intent row keyed by {@code (mid, requestId)}: command, amount, currency, {@code
   * extraData}; {@code debit}/{@code credit} columns left unset until settle.
   */
  void append(SevletWalletPayload payload, Currency currency, PaymentIntentAppendCtx ctx);

  default void append(SevletWalletPayload payload, Currency currency) {
    append(payload, currency, PaymentIntentAppendCtx.NONE);
  }

  /**
   * After {@link dev.nivic.ledger.WalletLedger} and {@link dev.nivic.journal.WalletJournal} commits: upserts
   * {@code payment_ledger} with {@code debit}/{@code credit} in a new connection. If a row already
   * exists (initial {@link #append}), {@code order_id} is left unchanged; only the settle fields
   * update.
   */
  void appendAfterWallet(SevletWalletPayload payload, Currency currency);

  /** Updates intent to {@link CoreLedgerStatus#SETTLED} for {@code (mid, order_id)} when challenge matches. */
  void settleIntentByOrder(SevletWalletPayload confirmPayload, Currency currency);

  /** Cancels intent ({@link CoreLedgerStatus#CANCELLED}) when challenge matches; releases any hold. */
  void rejectIntentByOrder(SevletWalletPayload rejectPayload, Currency currency);

  /**
   * After idempotency on {@code (mid, request_id)}: rejects when another <strong>open</strong> row
   * in {@code payment_ledger} already uses the same {@code (mid, order_id)} with a different {@code
   * request_id}. Callers should invoke this only when starting a new order-payment intent (e.g.
   * after {@code claimFirst} and before {@link #append} for that intent), not for confirm/reject.
   *
   * @throws OrderIdConflictException when a conflicting open intent exists
   */
  void requireNoConflictingOpenIntent(long mid, long orderId, long requestId);
}
