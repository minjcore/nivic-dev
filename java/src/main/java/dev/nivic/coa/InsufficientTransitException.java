package dev.nivic.coa;

public final class InsufficientTransitException extends RuntimeException {

  public InsufficientTransitException(String accountCode, long balance, long requested) {
    super("insufficient transit balance: account=" + accountCode
        + " balance=" + balance + " requested=" + requested);
  }
}
