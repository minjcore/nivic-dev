package dev.nivic.saving;

import java.util.UUID;

public final class InsufficientFundsException extends RuntimeException {

  public InsufficientFundsException(UUID accountId, long available, long requested) {
    super(
        "insufficient funds: account="
            + accountId
            + " available="
            + available
            + " requested="
            + requested);
  }
}
