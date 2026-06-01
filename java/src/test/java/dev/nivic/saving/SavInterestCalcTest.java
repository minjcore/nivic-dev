package dev.nivic.saving;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for the daily simple-interest formula — no database required. */
class SavInterestCalcTest {

  // ── Formula correctness ───────────────────────────────────────────────────

  @Test
  void fullYear_exactRate() {
    // 10,000,000 VND × 6.5% × 365/365 = 650,000 VND
    assertEquals(650_000L, SavInterestCalc.compute(10_000_000L, 650, 365));
  }

  @Test
  void oneDay_knownRate() {
    // 10,000,000 × 0.065 / 365 = 1,780.821... → floor = 1,780
    assertEquals(1_780L, SavInterestCalc.compute(10_000_000L, 650, 1));
  }

  @Test
  void thirtyDays_knownRate() {
    // 10,000,000 × 0.065 × 30/365 = 53,424.657... → floor = 53,424
    assertEquals(53_424L, SavInterestCalc.compute(10_000_000L, 650, 30));
  }

  @Test
  void ninetyDays_knownRate() {
    // 10,000,000 × 0.065 × 90/365 = 160,273.972... → floor = 160,273
    assertEquals(160_273L, SavInterestCalc.compute(10_000_000L, 650, 90));
  }

  @Test
  void highRate_shortPeriod() {
    // 100,000,000 VND × 8.5% × 1/365 = 23,287.671... → floor = 23,287
    assertEquals(23_287L, SavInterestCalc.compute(100_000_000L, 850, 1));
  }

  @Test
  void smallBalance_noFractionalUnit() {
    // 10,000 VND × 6.5% × 1/365 = 1.78... → floor = 1
    assertEquals(1L, SavInterestCalc.compute(10_000L, 650, 1));
  }

  @Test
  void verySmallBalance_yieldsZero() {
    // 100 VND × 6.5% × 1/365 = 0.017... → floor = 0
    assertEquals(0L, SavInterestCalc.compute(100L, 650, 1));
  }

  // ── Floor semantics ───────────────────────────────────────────────────────

  @Test
  void result_isAlwaysFloored_notRounded() {
    // Verify floor, not HALF_UP: any fractional đồng is dropped
    long oneDayInterest = SavInterestCalc.compute(10_000_000L, 650, 1); // 1,780.82...
    assertEquals(1_780L, oneDayInterest);                                // not 1,781

    // Accumulating 365 individual days should be ≤ one-year lump calculation
    long dailySum = oneDayInterest * 365L;
    long annualLump = SavInterestCalc.compute(10_000_000L, 650, 365);
    assertTrue(dailySum <= annualLump,
        "sum of daily floor amounts must not exceed annual lump: " + dailySum + " vs " + annualLump);
  }

  // ── Edge / guard cases ────────────────────────────────────────────────────

  @Test
  void zeroPrincipal_returnsZero() {
    assertEquals(0L, SavInterestCalc.compute(0L, 650, 30));
  }

  @Test
  void negativeBalance_returnsZero() {
    assertEquals(0L, SavInterestCalc.compute(-1_000_000L, 650, 30));
  }

  @Test
  void zeroDays_returnsZero() {
    assertEquals(0L, SavInterestCalc.compute(10_000_000L, 650, 0));
  }

  @Test
  void zeroRate_returnsZero() {
    assertEquals(0L, SavInterestCalc.compute(10_000_000L, 0, 365));
  }

  // ── Proportionality ───────────────────────────────────────────────────────

  @Test
  void doubleBalance_doublesInterest_orFloorOff() {
    long base   = SavInterestCalc.compute( 5_000_000L, 650, 1);
    long double_ = SavInterestCalc.compute(10_000_000L, 650, 1);
    // Due to flooring, double balance gives at least double interest
    assertTrue(double_ >= base * 2,
        "double balance should yield >= double interest (floor may add 1 unit)");
  }

  @Test
  void doubleDays_doublesInterest_orFloorOff() {
    // Computing N days as one call vs summing N×1-day may differ by up to 1 unit (floor effect).
    // compute(2d) floors 10M×0.065×2/365 = 3561.64… → 3561
    // compute(1d) floors 10M×0.065×1/365 = 1780.82… → 1780; ×2 = 3560  (1 đồng short)
    long oneDay = SavInterestCalc.compute(10_000_000L, 650, 1);
    long twoDay = SavInterestCalc.compute(10_000_000L, 650, 2);
    assertTrue(twoDay >= oneDay * 2,
        "full-period computation should be >= sum of individual days (no compounding, floor once)");
    assertTrue(twoDay <= oneDay * 2 + 1,
        "full-period floor may exceed sum of individual floors by at most 1 unit");
  }
}
