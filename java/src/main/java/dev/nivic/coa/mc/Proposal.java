package dev.nivic.coa.mc;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Đề xuất bút toán chờ duyệt (read model của {@code coa_proposal} + lines).
 * Khi APPROVED, {@link #postedTransId} trỏ tới bút toán bất biến đã sinh trên sổ cái
 * ({@code null} khi chưa duyệt). {@code id} là BIGINT identity.
 */
public record Proposal(
    long id,
    String refId,
    String memo,
    String makerId,
    ProposalStatus status,
    String checkerId,
    String reason,
    Long postedTransId,
    Instant createdAt,
    Instant decidedAt,
    List<Line> lines) {

  public Proposal {
    Objects.requireNonNull(makerId, "makerId");
    Objects.requireNonNull(status, "status");
    lines = lines == null ? List.of() : List.copyOf(lines);
  }

  public long debitTotal()  { return lines.stream().mapToLong(Line::debitMinor).sum(); }
  public long creditTotal() { return lines.stream().mapToLong(Line::creditMinor).sum(); }
  public boolean isBalanced() { return debitTotal() == creditTotal(); }

  public record Line(int lineNo, String accountCode, long debitMinor, long creditMinor, Long partyMid) {}
}
