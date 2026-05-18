package dev.nivic.ledger;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * All lifecycle values persisted in {@code payment_ledger.intent_status}. Declaration order defines
 * progression; {@link #EXPIRED} and everything after it are {@linkplain #isTerminal() terminal}.
 *
 * <p>New intent rows use {@link #defaultForNewIntentRow()} — the smallest ordinal among
 * non-terminal states, not a scattered string literal in SQL or JDBC.</p>
 */
public enum CoreLedgerStatus {
  INITIAL,
  AWAITING_CONFIRM,
  EXPIRED,
  SETTLED,
  CANCELLED;

  /**
   * Terminal rung starts at {@link #EXPIRED}; keep new “in-flight” states declared <b>before</b>
   * {@link #EXPIRED} so they participate in {@link #isOpenForConfirmation()} automatically.
   */
  public boolean isTerminal() {
    return ordinal() >= EXPIRED.ordinal();
  }

  public boolean isOpenForConfirmation() {
    return !isTerminal();
  }

  /** Default row state for a newly accepted intent (min ordinal among non-terminal states). */
  public static CoreLedgerStatus defaultForNewIntentRow() {
    return Arrays.stream(values())
        .filter(s -> !s.isTerminal())
        .min(Comparator.comparingInt(Enum::ordinal))
        .orElseThrow(IllegalStateException::new);
  }

  /** SQL {@code IN} list fragment, e.g. {@code 'INITIAL','AWAITING_CONFIRM'} — no hand-maintained list. */
  public static String sqlInList(java.util.function.Predicate<CoreLedgerStatus> predicate) {
    return Arrays.stream(values())
        .filter(predicate)
        .map(s -> "'" + s.name().replace("'", "''") + "'")
        .collect(Collectors.joining(","));
  }
}
