package dev.nivic.party;

/**
 * Neo-bank alignment: one {@code mid} namespace for holders, merchants, and internal treasury. Bands
 * are deployer conventions — adjust here if product allocates mids differently.
 *
 * <p>For {@link PartyKind#MERCHANT} actors, {@code mid} is the {@code merchant_id} (same key as {@code
 * wallet_mid_secret.mid} and product tables).
 *
 * <p>Treasury uses a single reserved {@link #TREASURY_MID} so ledger legs (debit/credit account ids
 * as mids) can reference the omnibus without a separate "Bank" integration table.
 */
public final class MidConventions {

  /**
   * Sole reserved mid for the platform treasury / omnibus leg. Not assigned to human-facing
   * merchants or users. Signed requests using this mid should be core/system-only (no end-user HMAC
   * secret).
   */
  public static final long TREASURY_MID = Long.MAX_VALUE;

  /**
   * Inclusive start of user/holder band (positive mids). Matches the default bootstrap row in {@code
   * db/seed/01_first_mid.sql}.
   */
  public static final long USER_BAND_MIN = 1L;

  /**
   * Inclusive end of user band; {@link #MERCHANT_BAND_MIN} begins immediately after. Keep README/demo
   * mids (e.g. 1, 42) in this range.
   */
  public static final long USER_BAND_MAX = 999_999_999L;

  /** Inclusive start of merchant band (large mids). */
  public static final long MERCHANT_BAND_MIN = 1_000_000_000L;

  private MidConventions() {}

  /** True if {@code mid} is the reserved treasury omnibus id. */
  public static boolean isTreasury(long mid) {
    return mid == TREASURY_MID;
  }

  /**
   * Classify {@code mid} into {@link PartyKind} using default bands.
   *
   * @return {@link PartyKind#UNKNOWN} for {@code mid == 0} or values between bands
   */
  public static PartyKind kindOf(long mid) {
    if (mid == 0L) {
      return PartyKind.UNKNOWN;
    }
    if (mid == TREASURY_MID) {
      return PartyKind.TREASURY;
    }
    if (mid >= USER_BAND_MIN && mid <= USER_BAND_MAX) {
      return PartyKind.USER;
    }
    if (mid >= MERCHANT_BAND_MIN && mid < TREASURY_MID) {
      return PartyKind.MERCHANT;
    }
    return PartyKind.UNKNOWN;
  }
}
