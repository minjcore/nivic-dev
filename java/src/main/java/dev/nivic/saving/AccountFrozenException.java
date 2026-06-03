package dev.nivic.saving;

public final class AccountFrozenException extends RuntimeException {

  public AccountFrozenException(long accountId) {
    super("account frozen: " + accountId);
  }
}
