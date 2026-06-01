package dev.nivic.saving;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Daily simple-interest calculation for savings accounts.
 *
 * <p>Formula: {@code interest = principal × (rateBps / 10_000) × (days / 365)}.</p>
 *
 * <p>Result is always floored to the nearest minor unit (e.g. đồng for VND) — no rounding up,
 * consistent with Vietnamese retail banking convention.</p>
 */
public final class SavInterestCalc {

  private static final BigDecimal BASIS_POINTS = BigDecimal.valueOf(10_000);
  private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);
  private static final int CALC_SCALE = 12;

  private SavInterestCalc() {}

  /**
   * Calculates interest for {@code days} elapsed at an annual rate of {@code rateBps} basis points.
   *
   * @param principalMinor balance in minor units (e.g. đồng)
   * @param rateBps        annual rate in basis points (e.g. 650 = 6.5 %/year)
   * @param days           number of days in the accrual period (must be ≥ 1)
   * @return interest amount in minor units, floored; 0 if any input is non-positive
   */
  public static long compute(long principalMinor, int rateBps, int days) {
    if (principalMinor <= 0 || rateBps <= 0 || days <= 0) return 0L;
    BigDecimal principal = BigDecimal.valueOf(principalMinor);
    BigDecimal rate      = BigDecimal.valueOf(rateBps).divide(BASIS_POINTS, CALC_SCALE, RoundingMode.HALF_UP);
    BigDecimal period    = BigDecimal.valueOf(days).divide(DAYS_PER_YEAR, CALC_SCALE, RoundingMode.HALF_UP);
    return principal.multiply(rate).multiply(period)
        .setScale(0, RoundingMode.FLOOR)
        .longValueExact();
  }
}
