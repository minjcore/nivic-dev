package dev.nivic.coa.error;

public final class TransactionNotFoundException extends RuntimeException {

  public TransactionNotFoundException(String ref) {
    super("transaction not found: ref=" + ref);
  }
}
