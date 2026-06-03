package dev.nivic.coa.query;

/**
 * Performance metrics for balance query service.
 * Updated continuously during operation.
 */
public record BalanceQueryMetrics(
    long totalQueries,              // Total queries processed
    long totalLatencyMicros,        // Sum of all query latencies
    long minLatencyMicros,          // Minimum latency observed
    long maxLatencyMicros,          // Maximum latency observed
    long currentQueueDepth,         // Current queries waiting in ring buffer
    double averageLatencyMicros,    // Average latency
    double throughputQPS            // Queries per second
) {

  /**
   * Get P95 latency from histogram (requires tracking).
   * For now, use maxLatencyMicros as upper bound.
   */
  public long estimateP95LatencyMicros() {
    return Math.min(maxLatencyMicros, (long) (averageLatencyMicros * 2));
  }

  /**
   * Human-readable summary.
   */
  @Override
  public String toString() {
    return String.format(
        "BalanceQueryMetrics{total=%d, avg=%.2fμs, p95≈%dμs, throughput=%.0f QPS, queue=%d}",
        totalQueries,
        averageLatencyMicros,
        estimateP95LatencyMicros(),
        throughputQPS,
        currentQueueDepth
    );
  }
}
