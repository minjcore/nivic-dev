package dev.nivic.saving;

public final class AccountClosedException extends RuntimeException {

  public AccountClosedException(long accountId) {
    super("account closed: " + accountId);
  }
}
