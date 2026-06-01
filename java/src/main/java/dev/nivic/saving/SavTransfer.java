package dev.nivic.saving;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable transfer record (read from {@code sav_transfer}).
 *
 * <p>Rows are never updated after INSERT. Two-phase transfers link via {@link #pendingId}:
 * a POSTED or VOIDED row references the original PENDING row.</p>
 */
public record SavTransfer(
    UUID id,
    SavTransferKind kind,
    UUID debitAccountId,
    UUID creditAccountId,
    long amountMinor,
    String currencyCode,
    SavTransferPhase phase,
    UUID pendingId,
    UUID idempotencyKey,
    Long refMid,
    Long refRequestId,
    UUID linkedBatchId,
    String memo,
    Instant createdAt) {

  public SavTransfer {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(debitAccountId, "debitAccountId");
    Objects.requireNonNull(creditAccountId, "creditAccountId");
    Objects.requireNonNull(currencyCode, "currencyCode");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
