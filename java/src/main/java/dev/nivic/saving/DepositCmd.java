package dev.nivic.saving;

import java.util.Objects;
import java.util.UUID;

public record DepositCmd(
    UUID creditAccountId,
    long amountMinor,
    String currencyCode,
    UUID idempotencyKey,
    Long refMid,
    Long refRequestId,
    String memo) {

  public DepositCmd {
    Objects.requireNonNull(creditAccountId, "creditAccountId");
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
  }
}
