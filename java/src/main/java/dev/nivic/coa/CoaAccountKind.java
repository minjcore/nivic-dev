package dev.nivic.coa;

public enum CoaAccountKind {
  ASSET,      // Tài sản — debit-normal (1xxx)
  LIABILITY,  // Nợ phải trả — credit-normal (2xxx)
  TRANSIT,    // Trung gian, luôn về 0 sau mỗi luồng (3xxx)
  REVENUE,    // Doanh thu — credit-normal (4xxx)
  EXPENSE,    // Chi phí — debit-normal (5xxx)
  EQUITY      // Vốn chủ sở hữu — credit-normal (6xxx)
}
