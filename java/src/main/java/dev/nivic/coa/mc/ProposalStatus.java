package dev.nivic.coa.mc;

/** Vòng đời đề xuất bút toán (maker-checker). */
public enum ProposalStatus {
  /** Maker đã tạo, chờ duyệt — chưa ảnh hưởng số dư. */
  PENDING,
  /** Checker đã duyệt → bút toán đã post vào sổ cái. */
  APPROVED,
  /** Checker từ chối — không bao giờ post. */
  REJECTED
}
