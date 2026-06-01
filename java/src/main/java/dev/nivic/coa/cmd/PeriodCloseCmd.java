package dev.nivic.coa.cmd;

import java.util.Objects;

/**
 * Khoá sổ cuối kỳ: kết chuyển toàn bộ doanh thu (4xxx) và chi phí (5xxx) về Lợi nhuận giữ lại (6100).
 *
 * <p>Sau khi khoá, mọi tài khoản kết quả (4xxx/5xxx) về 0; lãi/lỗ thuần dồn vào 6100.
 * {@code closeRef} là khoá idempotency cho lần khoá sổ này (vd "CLOSE-2026-Q1").</p>
 */
public record PeriodCloseCmd(
    String closeRef,
    String memo) {

  public PeriodCloseCmd {
    Objects.requireNonNull(closeRef, "closeRef");
  }
}
