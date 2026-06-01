package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Đảo ngược (hoàn tiền) một giao dịch đã ghi sổ.
 *
 * <p>Không xoá giao dịch gốc — post một bút toán bù trừ với debit↔credit hoán đổi cho từng dòng,
 * giữ nguyên audit trail. {@code originalRef} là {@code ref_id} của giao dịch cần đảo;
 * {@code reversalRef} là khoá idempotency cho chính lần đảo này.</p>
 */
public record ReversalCmd(
    String originalRef,
    String reversalRef,
    String memo) {

  public ReversalCmd {
    Objects.requireNonNull(originalRef, "originalRef");
    Objects.requireNonNull(reversalRef, "reversalRef");
    if (originalRef.equals(reversalRef)) {
      throw new IllegalArgumentException("reversalRef must differ from originalRef");
    }
  }
}
