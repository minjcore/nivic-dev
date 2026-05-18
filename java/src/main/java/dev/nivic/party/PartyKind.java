package dev.nivic.party;

/**
 * Logical party type for a wire {@code mid}. Neo-bank convention: users, merchants, and internal
 * treasury share one numeric {@code mid} namespace; classification uses {@link MidConventions}.
 */
public enum PartyKind {
  /** End-user / wallet holder (typical P2P leg). */
  USER,
  /** Merchant or business actor on the platform. */
  MERCHANT,
  /**
   * Internal omnibus / settlement leg — same {@code mid} mechanics as other parties on the ledger,
   * reserved numeric id ({@link MidConventions#TREASURY_MID}).
   */
  TREASURY,
  /** Mid outside configured bands or reserved ids (e.g. zero). */
  UNKNOWN
}
