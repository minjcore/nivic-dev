package dev.nivic.coa.error;

/** Người duyệt trùng người tạo — vi phạm nguyên tắc 4-eyes (segregation of duties). */
public final class SegregationOfDutiesException extends RuntimeException {
  public SegregationOfDutiesException(String who) {
    super("segregation of duties: checker must differ from maker (" + who + ")");
  }
}
