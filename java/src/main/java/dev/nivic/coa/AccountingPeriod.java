package dev.nivic.coa;

import java.time.LocalDate;
import java.time.Instant;
import java.util.Objects;

public enum PeriodStatus {
  OPEN,
  CLOSED,
  LOCKED
}

/**
 * Accounting period: controls when postings are allowed.
 * OPEN: posting allowed
 * CLOSED: posting prohibited, period-end close executed
 * LOCKED: administrative changes prohibited
 */
public record AccountingPeriod(
    LocalDate periodStart,
    LocalDate periodEnd,
    PeriodStatus status,
    Instant createdAt,
    Instant closedAt,           // null if still open
    String closedBy,            // userId who closed
    long closingTransId) {      // trans_id of closing entry

  public AccountingPeriod {
    Objects.requireNonNull(periodStart, "periodStart");
    Objects.requireNonNull(periodEnd, "periodEnd");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");

    if (!periodStart.isBefore(periodEnd)) {
      throw new IllegalArgumentException("periodStart must be before periodEnd");
    }

    // If CLOSED, must have closedAt and closedBy
    if (status == PeriodStatus.CLOSED && closedAt == null) {
      throw new IllegalArgumentException("Closed period must have closedAt");
    }
  }

  public boolean isOpen() {
    return status == PeriodStatus.OPEN;
  }

  public boolean canPost() {
    return status == PeriodStatus.OPEN;
  }

  public AccountingPeriod close(long closingTransId, String closedByUserId) {
    return new AccountingPeriod(
        periodStart, periodEnd, PeriodStatus.CLOSED,
        createdAt, Instant.now(), closedByUserId, closingTransId);
  }

  public AccountingPeriod lock() {
    return new AccountingPeriod(
        periodStart, periodEnd, PeriodStatus.LOCKED,
        createdAt, closedAt, closedBy, closingTransId);
  }

  public boolean contains(LocalDate date) {
    return !date.isBefore(periodStart) && !date.isAfter(periodEnd);
  }
}
