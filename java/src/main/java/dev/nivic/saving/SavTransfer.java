package dev.nivic.saving;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable transfer header ({@code sav_trans}) with its accounting lines ({@code sav_trans_data}).
 *
 * <p>The header carries lifecycle metadata (kind, phase, pending link, idempotency).
 * The {@link #lines} carry the double-entry bút toán: line 1 = debit side, line 2 = credit side.
 * Rows in {@code sav_trans} are never updated after INSERT.</p>
 */
public record SavTransfer(
    long id,
    SavTransferKind kind,
    SavTransferPhase phase,
    Long pendingId,
    Long idempotencyKey,
    Long refMid,
    Long refRequestId,
    Long linkedBatchId,
    String memo,
    Instant createdAt,
    List<SavTransLine> lines) {

  public SavTransfer {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(lines, "lines");
    lines = List.copyOf(lines);
  }

  /** Account debited in this transfer (line with debit_minor > 0). */
  public Long debitAccountId() {
    return lines.stream()
        .filter(l -> l.debitMinor() > 0)
        .map(SavTransLine::accountId)
        .findFirst()
        .orElse(null);
  }

  /** Account credited in this transfer (line with credit_minor > 0). */
  public Long creditAccountId() {
    return lines.stream()
        .filter(l -> l.creditMinor() > 0)
        .map(SavTransLine::accountId)
        .findFirst()
        .orElse(null);
  }

  /** Transfer amount (sum of debit sides). */
  public long amountMinor() {
    return lines.stream().mapToLong(SavTransLine::debitMinor).sum();
  }

  /** Currency from the first line. */
  public String currencyCode() {
    return lines.isEmpty() ? null : lines.get(0).currencyCode();
  }
}
