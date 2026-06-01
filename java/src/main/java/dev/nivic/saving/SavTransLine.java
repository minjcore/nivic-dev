package dev.nivic.saving;

import java.util.Objects;
import java.util.UUID;

/** One accounting line in {@code sav_trans_data}: debit or credit side of a transfer. */
public record SavTransLine(
    UUID accountId,
    long debitMinor,
    long creditMinor,
    String currencyCode) {

  public SavTransLine {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(currencyCode, "currencyCode");
  }
}
