package dev.nivic.saving;

import java.util.UUID;

public final class AccountClosedException extends RuntimeException {

  public AccountClosedException(UUID accountId) {
    super("account closed: " + accountId);
  }
}
