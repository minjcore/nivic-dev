package dev.nivic.wal;

import java.nio.ByteBuffer;

/** Non-client WAL payloads (e.g. intent TTL expiry markers). */
public final class InternalWalEvent {

  public static final byte[] PREFIX = {'N', 'I', 'V', 'X'};
  public static final byte OP_EXPIRED = 1;

  private InternalWalEvent() {}

  public static byte[] encodeExpired(long mid, long requestId) {
    return ByteBuffer.allocate(PREFIX.length + 1 + 8 + 8)
        .put(PREFIX)
        .put(OP_EXPIRED)
        .putLong(mid)
        .putLong(requestId)
        .array();
  }
}
