package dev.nivic.saving;

/** Bit-mask constants for {@code sav_account.flags}. */
public final class SavAccountFlags {

  /** No new transfers accepted. Set by {@link dev.nivic.saving.SavingLedger#closeAccount}. */
  public static final int CLOSED = 0x01;

  /**
   * Withdrawal blocked until {@code maturity_at}. Set when a TERM account is opened; cleared
   * by the maturity job (or by {@link dev.nivic.saving.SavingLedger#closeAccount} after maturity).
   */
  public static final int TERM_LOCKED = 0x02;

  /** All transfers blocked (compliance / fraud freeze). */
  public static final int FROZEN = 0x04;

  private SavAccountFlags() {}

  public static boolean isClosed(int flags)     { return (flags & CLOSED)      != 0; }
  public static boolean isTermLocked(int flags) { return (flags & TERM_LOCKED) != 0; }
  public static boolean isFrozen(int flags)     { return (flags & FROZEN)      != 0; }
}
