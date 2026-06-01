package dev.nivic.coa;

public final class TransactionNotFoundException extends RuntimeException {

  public TransactionNotFoundException(String ref) {
    super("transaction not found: ref=" + ref);
  }
}
