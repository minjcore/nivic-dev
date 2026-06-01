package dev.nivic.saving;

import java.time.Instant;
import java.util.Objects;

public record OpenAccountCmd(
    long ownerMid,
    SavAccountKind kind,
    String currencyCode,
    Integer interestRateBps,
    Instant maturityAt,
    int initialFlags) {

  public OpenAccountCmd {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (kind == SavAccountKind.TERM && maturityAt == null) {
      throw new IllegalArgumentException("TERM account requires maturityAt");
    }
  }

  public static OpenAccountCmd demand(long ownerMid, String currencyCode) {
    return new OpenAccountCmd(ownerMid, SavAccountKind.DEMAND, currencyCode, null, null, 0);
  }

  public static OpenAccountCmd term(
      long ownerMid, String currencyCode, int interestRateBps, Instant maturityAt) {
    return new OpenAccountCmd(
        ownerMid,
        SavAccountKind.TERM,
        currencyCode,
        interestRateBps,
        maturityAt,
        SavAccountFlags.TERM_LOCKED);
  }
}
