/**
 * Application services (HTTP path → persistence) live under {@code dev.nivic.payment}, not in this
 * package. Use:
 *
 * <ul>
 *   <li>{@link dev.nivic.payment.WalletAcceptService} — idempotency, WAL, ledger / payment intent
 *   <li>{@link dev.nivic.payment.WalletVerificationService} — HMAC verification before accept
 *   <li>{@link dev.nivic.payment.LedgerService} — wallet ledger + journal
 *   <li>{@link dev.nivic.payment.WalService} — append-only WAL
 *   <li>{@link dev.nivic.payment.ReconciliationJob} — scheduled reconciliation
 * </ul>
 *
 * <p>LMAX Disruptor ring: {@link dev.nivic.payment.disruptor.WalletPersistDisruptor}.</p>
 */
package dev.nivic.service;
