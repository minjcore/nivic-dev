package dev.nivic.saving;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Full read-model for one transfer: header from {@code sav_trans} + enriched bút toán lines
 * from {@code sav_trans_data JOIN sav_account}.
 *
 * <p>A well-formed transfer always has {@link #isBalanced()} == {@code true}.</p>
 */
public record SavTransView(
    UUID id,
    SavTransferKind kind,
    SavTransferPhase phase,
    UUID pendingId,
    UUID idempotencyKey,
    Long refMid,
    Long refRequestId,
    UUID linkedBatchId,
    String memo,
    Instant createdAt,
    List<SavTransLineView> lines) {

  public SavTransView {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(lines, "lines");
    lines = List.copyOf(lines);
  }

  public long debitTotal()  { return lines.stream().mapToLong(SavTransLineView::debitMinor).sum(); }
  public long creditTotal() { return lines.stream().mapToLong(SavTransLineView::creditMinor).sum(); }

  /** Double-entry invariant: tổng debit = tổng credit. */
  public boolean isBalanced() { return debitTotal() == creditTotal(); }
}
