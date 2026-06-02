package dev.nivic.saving;

import java.util.Objects;

public record DepositCmd(
    long creditAccountId,
    long amountMinor,
    String currencyCode,
    Long idempotencyKey,
    Long refMid,
    Long refRequestId,
    String memo) {

  public DepositCmd {
    Objects.requireNonNull(creditAccountId, "creditAccountId");
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
  }
}
