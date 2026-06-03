package dev.nivic.saving;

import java.util.Objects;

/**
 * Pre-computed interest credit for one savings account.
 * Interest rate arithmetic (days elapsed, compounding) belongs in the caller.
 */
public record AccrueInterestCmd(
    long savAccountId,
    long amountMinor,
    String currencyCode,
    Long linkedBatchId) {

  public AccrueInterestCmd {
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    Objects.requireNonNull(currencyCode, "currencyCode");
  }
}
