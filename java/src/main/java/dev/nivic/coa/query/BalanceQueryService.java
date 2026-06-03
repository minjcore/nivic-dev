package dev.nivic.coa.query;

import java.util.concurrent.CompletableFuture;

/**
 * Balance query service: non-blocking API for querying account balances at high throughput.
 * Uses LMAX-Disruptor for batching & low latency (target: 20k TPS, P95 <50ms).
 */
public interface BalanceQueryService {

  /**
   * Query balance of a single account.
   * Non-blocking: returns immediately with a future.
   */
  CompletableFuture<BalanceQueryResult> queryAccount(String accountCode);

  /**
   * Query balance of multiple accounts.
   * Batches with other queries for efficiency.
   */
  CompletableFuture<BalanceQueryResult> queryAccounts(String... accountCodes);

  /**
   * Query user wallet balance (subsidiary ledger of 2110).
   * Calculates: SUM(credit − debit) on 2110 for given user.
   */
  CompletableFuture<BalanceQueryResult> queryUserWallet(long userId);

  /**
   * Query merchant wallet balance (2120).
   */
  CompletableFuture<BalanceQueryResult> queryMerchantWallet(long merchantId);

  /**
   * Query savings balance (2140).
   */
  CompletableFuture<BalanceQueryResult> querySavingsWallet(long userId);

  /**
   * Get service metrics: throughput, latency, queue depth.
   */
  BalanceQueryMetrics getMetrics();

  /**
   * Start the service (initialize Disruptor).
   */
  void start();

  /**
   * Shutdown gracefully.
   */
  void shutdown();
}
