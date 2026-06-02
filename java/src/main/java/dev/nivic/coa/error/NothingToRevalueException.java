package dev.nivic.coa.error;

/** Không có vị thế FX mở hoặc tỷ giá không đổi — không có gì để đánh giá lại. */
public final class NothingToRevalueException extends RuntimeException {
  public NothingToRevalueException(String message) {
    super(message);
  }
}
