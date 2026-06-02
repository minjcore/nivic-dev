package dev.nivic.saving;

import java.time.Instant;

public final class TermLockedException extends RuntimeException {

  public TermLockedException(long accountId, Instant maturityAt) {
    super("term deposit locked until " + maturityAt + ": account=" + accountId);
  }
}
