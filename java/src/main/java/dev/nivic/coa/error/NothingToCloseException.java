package dev.nivic.coa.error;

public final class NothingToCloseException extends RuntimeException {

  public NothingToCloseException() {
    super("nothing to close: no revenue or expense balances");
  }
}
