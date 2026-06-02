package dev.nivic.coa;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable journal transaction: header from {@code coa_trans} + bút toán lines from
 * {@code coa_trans_data JOIN coa_account}.
 *
 * <p>{@code id} là BIGINT (identity, start 500000000) — gọn, hiển thị/gõ tay được. Khoá nghiệp vụ
 * client dùng là {@code refId} (idempotency); {@code id} chỉ là surrogate nội bộ.</p>
 */
public record CoaTrans(
    long id,
    String refId,
    String memo,
    Instant createdAt,
    List<CoaTransLine> lines) {

  public CoaTrans {
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(lines, "lines");
    lines = List.copyOf(lines);
  }

  public long debitTotal()  { return lines.stream().mapToLong(CoaTransLine::debitMinor).sum(); }
  public long creditTotal() { return lines.stream().mapToLong(CoaTransLine::creditMinor).sum(); }

  /** Double-entry invariant: every posted transaction must be balanced. */
  public boolean isBalanced() { return debitTotal() == creditTotal(); }
}
