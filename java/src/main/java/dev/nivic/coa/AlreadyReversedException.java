package dev.nivic.coa;

public final class AlreadyReversedException extends RuntimeException {

  public AlreadyReversedException(String originalRef) {
    super("transaction already reversed: ref=" + originalRef);
  }
}
