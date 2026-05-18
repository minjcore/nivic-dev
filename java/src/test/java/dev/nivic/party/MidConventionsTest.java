package dev.nivic.party;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MidConventionsTest {

  @Test
  void treasuryReserved() {
    assertTrue(MidConventions.isTreasury(Long.MAX_VALUE));
    assertEquals(PartyKind.TREASURY, MidConventions.kindOf(Long.MAX_VALUE));
  }

  @Test
  void userBandMatchesReadmeAndHotPathExamples() {
    assertEquals(PartyKind.USER, MidConventions.kindOf(1L));
    assertEquals(PartyKind.USER, MidConventions.kindOf(42L));
    assertEquals(PartyKind.USER, MidConventions.kindOf(MidConventions.USER_BAND_MAX));
  }

  @Test
  void merchantBand() {
    assertEquals(PartyKind.MERCHANT, MidConventions.kindOf(MidConventions.MERCHANT_BAND_MIN));
    assertEquals(PartyKind.MERCHANT, MidConventions.kindOf(MidConventions.MERCHANT_BAND_MIN + 1));
    assertEquals(
        PartyKind.MERCHANT, MidConventions.kindOf(Long.MAX_VALUE - 1)); // just below treasury
  }

  @Test
  void unknownMid() {
    assertEquals(PartyKind.UNKNOWN, MidConventions.kindOf(0L));
    assertEquals(PartyKind.UNKNOWN, MidConventions.kindOf(-1L));
  }
}
