package dev.nivic.saving;

public final class InsufficientFundsException extends RuntimeException {

  public InsufficientFundsException(long accountId, long available, long requested) {
    super(
        "insufficient funds: account="
            + accountId
            + " available="
            + available
            + " requested="
            + requested);
  }
}
