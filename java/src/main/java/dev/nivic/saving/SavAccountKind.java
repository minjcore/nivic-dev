package dev.nivic.saving;

public enum SavAccountKind {
  /** Flexible savings — withdraw anytime. */
  DEMAND,
  /** Fixed-term deposit — locked until {@code maturity_at}. */
  TERM,
  /** System account (EXTERNAL placeholder, RESERVE for interest). Not user-facing. */
  RESERVE
}
