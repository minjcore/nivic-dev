package dev.nivic.saving;

import java.util.Objects;
import java.util.UUID;

/**
 * Pre-computed interest credit for one savings account.
 * Interest rate arithmetic (days elapsed, compounding) belongs in the caller.
 */
public record AccrueInterestCmd(
    UUID savAccountId,
    long amountMinor,
    String currencyCode,
    UUID linkedBatchId) {

  public AccrueInterestCmd {
    Objects.requireNonNull(savAccountId, "savAccountId");
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
  }
}
