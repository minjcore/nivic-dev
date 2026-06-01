package dev.nivic.coa.report;

import java.util.Objects;

/**
 * Một dòng trong Bảng cân đối thử (Trial Balance): mỗi tài khoản hiển thị số dư ở đúng cột
 * tự nhiên — số dư debit ({@code balance_minor > 0}) vào cột Nợ, số dư credit vào cột Có.
 */
public record TrialBalanceRow(
    String code,
    String name,
    String kind,
    long debitMinor,
    long creditMinor) {

  public TrialBalanceRow {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
  }

  /** Dựng từ balance_minor (= ΣDR − ΣCR): dương → cột Nợ, âm → cột Có. */
  public static TrialBalanceRow of(String code, String name, String kind, long balanceMinor) {
    long dr = balanceMinor > 0 ? balanceMinor : 0L;
    long cr = balanceMinor < 0 ? -balanceMinor : 0L;
    return new TrialBalanceRow(code, name, kind, dr, cr);
  }
}
