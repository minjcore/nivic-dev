package dev.nivic.coa.mc;

import java.util.List;
import java.util.Objects;

/**
 * Maker đề xuất một bút toán cân (chưa post). Checker (≠ maker) duyệt thì mới vào sổ cái.
 * Generic: mọi nghiệp vụ đều quy về một tập dòng nợ/có cân bằng.
 */
public record ProposeJournalCmd(
    String makerId,
    String requestRef,
    String memo,
    List<EntryLine> lines) {

  public ProposeJournalCmd {
    Objects.requireNonNull(makerId, "makerId");
    Objects.requireNonNull(requestRef, "requestRef");
    Objects.requireNonNull(lines, "lines");
    if (lines.size() < 2) throw new IllegalArgumentException("journal needs >= 2 lines");
    lines = List.copyOf(lines);
  }

  /** Một dòng đề xuất: tài khoản + nợ/có + party (tuỳ chọn). */
  public record EntryLine(String accountCode, long debitMinor, long creditMinor, Long partyMid) {
    public EntryLine {
      Objects.requireNonNull(accountCode, "accountCode");
      if (debitMinor < 0 || creditMinor < 0) throw new IllegalArgumentException("amounts >= 0");
      if ((debitMinor == 0) == (creditMinor == 0)) {
        throw new IllegalArgumentException("each line is debit XOR credit");
      }
    }
    public EntryLine(String accountCode, long debitMinor, long creditMinor) {
      this(accountCode, debitMinor, creditMinor, null);
    }
  }
}
