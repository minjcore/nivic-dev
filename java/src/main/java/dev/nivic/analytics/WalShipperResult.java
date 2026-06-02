package dev.nivic.analytics;

/** Result of one {@link WalShipper#runOnce()} pass. */
public record WalShipperResult(int shipped, int skipped, long walByteOffset) {}
