package dev.nivic.coa.query;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Example usage of DisruptorBalanceQueryService.
 * Demonstrates: batching, non-blocking API, metrics.
 */
class BalanceQueryServiceTest {

  /**
   * Example: Query single account balance.
   * Note: Requires running database; this is a usage example.
   */
  @Test
  void testSingleAccountQuery() throws Exception {
    // Initialize service
    DisruptorBalanceQueryService service = new DisruptorBalanceQueryService(
        "jdbc:postgresql://localhost:5432/testdb",
        "user",
        "password",
        8192,    // Ring buffer size
        64       // Batch size
    );
    service.start();

    try {
      // Query account 1111 (Bank Account)
      CompletableFuture<BalanceQueryResult> future = service.queryAccount("1111");

      // Non-blocking: do other work while query executes
      assertFalse(future.isDone());

      // Wait for result (with timeout)
      BalanceQueryResult result = future.get(1, TimeUnit.SECONDS);
      assertNotNull(result);
      assertTrue(result.balances().containsKey("1111"));

      // Print metrics
      System.out.println("Single query: " + service.getMetrics());

    } finally {
      service.shutdown();
    }
  }

  /**
   * Example: Batch multiple account queries.
   * Demonstrates automatic batching for efficiency.
   */
  @Test
  void testBatchedQueries() throws Exception {
    DisruptorBalanceQueryService service = new DisruptorBalanceQueryService(
        "jdbc:postgresql://localhost:5432/testdb",
        "user",
        "password"
    );
    service.start();

    try {
      // Submit 100 queries rapidly (will be batched)
      CompletableFuture<?>[] futures = new CompletableFuture[100];
      for (int i = 0; i < 100; i++) {
        String accountCode = String.format("1%03d", i);
        futures[i] = service.queryAccount(accountCode);
      }

      // Wait for all to complete
      CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

      // Check metrics: should show efficient batching
      BalanceQueryMetrics metrics = service.getMetrics();
      System.out.println("Batch metrics: " + metrics);
      assertTrue(metrics.totalQueries() >= 100);
      assertTrue(metrics.averageLatencyMicros() > 0);

    } finally {
      service.shutdown();
    }
  }

  /**
   * Example: High throughput simulation (wallet & subsidiary ledger queries).
   */
  @Test
  void testHighThroughput() throws Exception {
    DisruptorBalanceQueryService service = new DisruptorBalanceQueryService(
        "jdbc:postgresql://localhost:5432/testdb",
        "user",
        "password",
        16384,   // Larger ring buffer for high throughput
        128      // Larger batch for efficiency
    );
    service.start();

    try {
      // Simulate 20k TPS for 10 seconds
      int tps = 20000;
      int durationSeconds = 10;
      CountDownLatch latch = new CountDownLatch(tps * durationSeconds);

      long startTime = System.currentTimeMillis();

      // Submit queries continuously
      Thread submitter = new Thread(() -> {
        long userId = 1;
        for (int i = 0; i < tps * durationSeconds; i++) {
          CompletableFuture<BalanceQueryResult> future =
              service.queryUserWallet(userId++ % 100_000);  // Rotate through 100k users

          // Decrement latch when done
          future.thenAccept(result -> latch.countDown())
                .exceptionally(ex -> {
                  latch.countDown();
                  return null;
                });

          // Small delay to spread queries
          if (i % 100 == 0) {
            try {
              Thread.sleep(5);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          }
        }
      });
      submitter.start();

      // Wait for completion
      boolean completed = latch.await(durationSeconds + 10, TimeUnit.SECONDS);
      long elapsedMs = System.currentTimeMillis() - startTime;

      // Print results
      BalanceQueryMetrics metrics = service.getMetrics();
      System.out.println("\n=== High Throughput Test Results ===");
      System.out.println("Target: " + tps + " TPS for " + durationSeconds + " seconds");
      System.out.println("Completed: " + completed);
      System.out.println("Actual elapsed: " + elapsedMs + "ms");
      System.out.println("Metrics: " + metrics);
      System.out.println("Actual throughput: " + (metrics.totalQueries() * 1000 / elapsedMs) + " QPS");
      System.out.println("P95 latency: " + metrics.estimateP95LatencyMicros() + "μs");

      assertTrue(completed, "All queries should complete");
      assertTrue(metrics.totalQueries() >= tps * durationSeconds / 2, "Should handle significant throughput");

      submitter.join();

    } finally {
      service.shutdown();
    }
  }

  /**
   * Example: Query different wallet types.
   */
  @Test
  void testWalletQueries() throws Exception {
    DisruptorBalanceQueryService service = new DisruptorBalanceQueryService(
        "jdbc:postgresql://localhost:5432/testdb",
        "user",
        "password"
    );
    service.start();

    try {
      long userId = 42;

      // Query user wallet (2110)
      CompletableFuture<BalanceQueryResult> userWallet = service.queryUserWallet(userId);

      // Query merchant wallet (2120)
      CompletableFuture<BalanceQueryResult> merchWallet = service.queryMerchantWallet(userId);

      // Query savings (2140)
      CompletableFuture<BalanceQueryResult> savings = service.querySavingsWallet(userId);

      // Wait for all
      CompletableFuture.allOf(userWallet, merchWallet, savings).get(2, TimeUnit.SECONDS);

      System.out.println("User wallet balance: " + userWallet.get().balances());
      System.out.println("Merchant wallet balance: " + merchWallet.get().balances());
      System.out.println("Savings balance: " + savings.get().balances());

      // Verify results are maps
      assertTrue(!userWallet.get().balances().isEmpty());
      assertTrue(!merchWallet.get().balances().isEmpty());
      assertTrue(!savings.get().balances().isEmpty());

    } finally {
      service.shutdown();
    }
  }
}
