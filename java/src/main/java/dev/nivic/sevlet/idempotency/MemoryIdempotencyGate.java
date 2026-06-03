package dev.nivic.sevlet.idempotency;

import java.util.concurrent.ConcurrentHashMap;

/** In-process idempotency; use {@link JdbcIdempotencyGate} when multiple app instances share Postgres. */
public final class MemoryIdempotencyGate implements IdempotencyGate {

  private final ConcurrentHashMap<Key, Long> seen = new ConcurrentHashMap<>();

  private record Key(long mid, long requestId) {}

  @Override
  public boolean claimFirst(long mid, long requestId, long orderId, boolean orderPaymentMode) {
    Key k = new Key(mid, requestId);
    Long prev = seen.putIfAbsent(k, orderId);
    if (prev == null) {
      return true;
    }
    if (orderPaymentMode && prev.longValue() != orderId) {
      throw new OrderIdMismatchException(mid, requestId, prev, orderId);
    }
    return false;
  }
}
