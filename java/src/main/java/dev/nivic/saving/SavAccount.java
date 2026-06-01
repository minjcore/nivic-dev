package dev.nivic.saving;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Savings account value object (read from {@code sav_account}).
 *
 * <p>Credit-normal account: available = credits_posted − debits_posted − debits_pending.</p>
 */
public record SavAccount(
    UUID id,
    long ownerMid,
    String accountNo,
    SavAccountKind kind,
    String currencyCode,
    long debitsPending,
    long debitsPosted,
    long creditsPending,
    long creditsPosted,
    int flags,
    Integer interestRateBps,
    Instant maturityAt,
    Instant openedAt,
    Instant closedAt,
    long version) {

  public SavAccount {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(accountNo, "accountNo");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(currencyCode, "currencyCode");
    Objects.requireNonNull(openedAt, "openedAt");
  }

  /** Funds available for withdrawal (always ≥ 0 if invariants hold). */
  public long availableBalance() {
    return creditsPosted - debitsPosted - debitsPending;
  }

  public boolean isClosed()     { return SavAccountFlags.isClosed(flags); }
  public boolean isTermLocked() { return SavAccountFlags.isTermLocked(flags); }
  public boolean isFrozen()     { return SavAccountFlags.isFrozen(flags); }
}
