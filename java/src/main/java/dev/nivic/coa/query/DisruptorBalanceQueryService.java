package dev.nivic.coa.query;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.YieldingWaitStrategy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance balance query service using LMAX-Disruptor.
 * Targets: 20k TPS, P95 <50ms latency, zero-copy batching.
 */
public class DisruptorBalanceQueryService implements BalanceQueryService {

  private final String dataSourceUrl;
  private final String dbUser;
  private final String dbPassword;
  private final int ringBufferSize;
  private final int batchSize;

  // Disruptor components
  private Disruptor<BalanceQueryEvent> disruptor;
  private RingBuffer<BalanceQueryEvent> ringBuffer;
  private ExecutorService executor;

  // Metrics
  private final AtomicLong totalQueries = new AtomicLong(0);
  private final AtomicLong totalLatencyMicros = new AtomicLong(0);
  private final AtomicLong minLatencyMicros = new AtomicLong(Long.MAX_VALUE);
  private final AtomicLong maxLatencyMicros = new AtomicLong(0);
  private volatile long lastMetricsTime = System.nanoTime();
  private volatile long queriesLastSecond = 0;

  public DisruptorBalanceQueryService(
      String dataSourceUrl,
      String dbUser,
      String dbPassword) {
    this(dataSourceUrl, dbUser, dbPassword, 8192, 64);
  }

  public DisruptorBalanceQueryService(
      String dataSourceUrl,
      String dbUser,
      String dbPassword,
      int ringBufferSize,
      int batchSize) {
    this.dataSourceUrl = dataSourceUrl;
    this.dbUser = dbUser;
    this.dbPassword = dbPassword;
    this.ringBufferSize = ringBufferSize;  // Power of 2
    this.batchSize = batchSize;
  }

  @Override
  public void start() {
    executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "BalanceQueryHandler");
      t.setDaemon(false);
      t.setPriority(Thread.MAX_PRIORITY);  // High priority for handler
      return t;
    });

    // Create Disruptor
    disruptor = new Disruptor<>(
        new BalanceQueryEventFactory(),
        ringBufferSize,
        executor,
        ProducerType.MULTI,                     // Multiple producer threads
        new YieldingWaitStrategy()              // Low latency
    );

    // Add event handler
    BalanceQueryHandler handler = new BalanceQueryHandler(
        dataSourceUrl, dbUser, dbPassword, batchSize);
    disruptor.handleEventsWith(handler);

    ringBuffer = disruptor.start();
  }

  @Override
  public void shutdown() {
    if (disruptor != null) {
      disruptor.shutdown();
    }
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public CompletableFuture<BalanceQueryResult> queryAccount(String accountCode) {
    return queryAccounts(accountCode);
  }

  @Override
  public CompletableFuture<BalanceQueryResult> queryAccounts(String... accountCodes) {
    CompletableFuture<BalanceQueryResult> future = new CompletableFuture<>();

    // Publish event
    long sequence = ringBuffer.next();
    try {
      BalanceQueryEvent event = ringBuffer.get(sequence);
      event.type = BalanceQueryEvent.QueryType.SINGLE_ACCOUNT;
      event.accountCodes = accountCodes;
      event.future = future;
    } finally {
      ringBuffer.publish(sequence);
    }

    // Update metrics
    recordQuery();

    return future;
  }

  @Override
  public CompletableFuture<BalanceQueryResult> queryUserWallet(long userId) {
    CompletableFuture<BalanceQueryResult> future = new CompletableFuture<>();

    long sequence = ringBuffer.next();
    try {
      BalanceQueryEvent event = ringBuffer.get(sequence);
      event.type = BalanceQueryEvent.QueryType.USER_WALLET;
      event.mid = userId;
      event.future = future;
    } finally {
      ringBuffer.publish(sequence);
    }

    recordQuery();
    return future;
  }

  @Override
  public CompletableFuture<BalanceQueryResult> queryMerchantWallet(long merchantId) {
    CompletableFuture<BalanceQueryResult> future = new CompletableFuture<>();

    long sequence = ringBuffer.next();
    try {
      BalanceQueryEvent event = ringBuffer.get(sequence);
      event.type = BalanceQueryEvent.QueryType.MERCHANT_WALLET;
      event.mid = merchantId;
      event.future = future;
    } finally {
      ringBuffer.publish(sequence);
    }

    recordQuery();
    return future;
  }

  @Override
  public CompletableFuture<BalanceQueryResult> querySavingsWallet(long userId) {
    CompletableFuture<BalanceQueryResult> future = new CompletableFuture<>();

    long sequence = ringBuffer.next();
    try {
      BalanceQueryEvent event = ringBuffer.get(sequence);
      event.type = BalanceQueryEvent.QueryType.SAVINGS_WALLET;
      event.mid = userId;
      event.future = future;
    } finally {
      ringBuffer.publish(sequence);
    }

    recordQuery();
    return future;
  }

  @Override
  public BalanceQueryMetrics getMetrics() {
    long total = totalQueries.get();
    long totalLatency = totalLatencyMicros.get();
    long minLatency = minLatencyMicros.get();
    long maxLatency = maxLatencyMicros.get();

    double avgLatency = total > 0 ? (double) totalLatency / total : 0;
    long currentQueue = ringBuffer.remainingCapacity() < ringBufferSize
        ? (ringBufferSize - ringBuffer.remainingCapacity())
        : 0;

    // Calculate QPS
    long now = System.nanoTime();
    long elapsedNanos = now - lastMetricsTime;
    double qps = (elapsedNanos > 0) ? queriesLastSecond * 1_000_000_000.0 / elapsedNanos : 0;

    return new BalanceQueryMetrics(
        total,
        totalLatency,
        minLatency == Long.MAX_VALUE ? 0 : minLatency,
        maxLatency,
        currentQueue,
        avgLatency,
        qps
    );
  }

  /**
   * Record a query for metrics.
   */
  private void recordQuery() {
    long count = totalQueries.incrementAndGet();
    queriesLastSecond++;

    // Reset metrics every second
    long now = System.nanoTime();
    if (now - lastMetricsTime > 1_000_000_000L) {
      lastMetricsTime = now;
      queriesLastSecond = 0;
    }
  }

  /**
   * Record latency after query completes.
   * Called by handler after processing.
   */
  void recordLatency(long micros) {
    totalLatencyMicros.addAndGet(micros);
    minLatencyMicros.accumulateAndGet(micros, Math::min);
    maxLatencyMicros.accumulateAndGet(micros, Math::max);
  }
}
