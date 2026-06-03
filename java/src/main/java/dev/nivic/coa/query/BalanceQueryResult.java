package dev.nivic.coa.query;

import java.util.*;

/**
 * Result of a balance query: account code → balance mapping.
 * Immutable, safe for concurrent access.
 */
public record BalanceQueryResult(
    Map<String, Long> balances,      // account code → balance in minor units
    long queryTimeNanos,             // Time taken for query (for metrics)
    long timestamp                   // Timestamp of query
) {

  public BalanceQueryResult {
    Objects.requireNonNull(balances, "balances");
    balances = Collections.unmodifiableMap(balances);
  }

  /**
   * Get balance for a single account.
   * @return balance, or 0 if account not found
   */
  public long getBalance(String accountCode) {
    return balances.getOrDefault(accountCode, 0L);
  }

  /**
   * Get all balances.
   */
  public Map<String, Long> getBalances() {
    return balances;
  }

  /**
   * Total of all balances (sum of all accounts).
   */
  public long totalBalance() {
    return balances.values().stream().mapToLong(Long::longValue).sum();
  }

  /**
   * Query latency in microseconds.
   */
  public long getLatencyMicros() {
    return queryTimeNanos / 1000;
  }

  /**
   * Helper to create a single-account result.
   */
  public static BalanceQueryResult single(String code, long balance, long nanos) {
    return new BalanceQueryResult(
        Map.of(code, balance),
        nanos,
        System.currentTimeMillis()
    );
  }
}
