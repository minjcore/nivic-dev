package dev.nivic.coa.error;

/** Đề xuất đã được quyết định (APPROVED/REJECTED) — không thể duyệt/từ chối lại. */
public final class ProposalStateException extends RuntimeException {
  public ProposalStateException(String message) {
    super(message);
  }
}
