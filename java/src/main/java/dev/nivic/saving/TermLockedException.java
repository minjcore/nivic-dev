package dev.nivic.saving;

import java.time.Instant;
import java.util.UUID;

public final class TermLockedException extends RuntimeException {

  public TermLockedException(UUID accountId, Instant maturityAt) {
    super("term deposit locked until " + maturityAt + ": account=" + accountId);
  }
}
