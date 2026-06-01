package dev.nivic.saving;

import java.util.UUID;

public final class AccountFrozenException extends RuntimeException {

  public AccountFrozenException(UUID accountId) {
    super("account frozen: " + accountId);
  }
}
