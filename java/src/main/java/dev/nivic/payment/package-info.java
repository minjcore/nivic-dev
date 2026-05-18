/**
 * Internal <strong>payment</strong> pipeline: WAL, idempotency handoff, ledger/journal, payment
 * ledger intents, holds, reconciliation, and the LMAX Disruptor persist ring. For <strong>external
 * banking</strong> protocols (e.g. ISO 8583 host adapters), use {@code dev.nivic.bank}.
 */
package dev.nivic.payment;
