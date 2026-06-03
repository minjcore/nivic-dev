package dev.nivic.gateway.service;

import dev.nivic.gateway.model.LedgerEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Service
public class EventService {

    @Autowired
    private EventDeduplicator deduplicator;

    @Autowired
    private EventEnricher enricher;

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private CircuitBreakerService circuitBreaker;

    private final Queue<LedgerEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private static final int BATCH_SIZE = 1000;
    private static final long BATCH_WINDOW_MS = 100;  // 100ms

    private long lastBatchTime = System.currentTimeMillis();

    /**
     * Enqueue event for async processing
     * Called from REST controller (fire-and-forget)
     */
    public void enqueueEvent(LedgerEvent event) {
        eventQueue.offer(event);
    }

    /**
     * Batch processor (runs every 100ms or when batch size reached)
     */
    @Scheduled(fixedDelay = 50, initialDelay = 100)
    public void processBatch() {
        if (eventQueue.isEmpty()) {
            return;
        }

        List<LedgerEvent> batch = new ArrayList<>();

        // Collect up to BATCH_SIZE events
        while (!eventQueue.isEmpty() && batch.size() < BATCH_SIZE) {
            LedgerEvent event = eventQueue.poll();
            if (event != null) {
                batch.add(event);
            }
        }

        if (batch.isEmpty()) {
            return;
        }

        long startTime = System.currentTimeMillis();

        // Process batch
        for (LedgerEvent event : batch) {
            try {
                // 1. Deduplication check
                if (deduplicator.isDuplicate(event.getEventId())) {
                    log.debug("Duplicate event (skipped): event_id={}", event.getEventId());
                    continue;
                }

                // 2. Enrichment
                enricher.enrich(event);

                // 3. Routing & Publishing
                routeEvent(event);

                // 4. Record in dedup store
                deduplicator.recordProcessed(event.getEventId(), event.getEventType());

            } catch (Exception e) {
                log.error("Error processing event: event_id={}", event.getEventId(), e);
                // Failed events are NOT retried at gateway level
                // They're queued in ledger-events-retry by RabbitMQ
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("batch_processed",
            () -> Map.of(
                "batch_size", batch.size(),
                "elapsed_ms", elapsed,
                "avg_ms_per_event", batch.isEmpty() ? 0 : elapsed / batch.size()
            ));

        lastBatchTime = System.currentTimeMillis();
    }

    /**
     * Route event based on type
     */
    private void routeEvent(LedgerEvent event) {
        switch (event.getEventType()) {
            case "TRANSACTION_POSTED":
            case "TRANSACTION_FAILED":
            case "PAYMENT_SETTLED":
            case "SETTLEMENT_ERROR":
            case "FRAUD_DETECTED":
                // Route to Java Ledger via RabbitMQ
                publishToLedger(event);
                break;

            case "BALANCE_UPDATED":
                // Update Redis cache (no ledger queue)
                publisher.publishToCache(event);
                break;

            case "SESSION_CREATED":
                // Both: publish to ledger AND update session cache
                publishToLedger(event);
                publisher.publishToCache(event);
                break;

            case "SESSION_EXPIRED":
                // Cleanup: remove from session cache only
                publisher.publishToCache(event);
                break;

            default:
                log.warn("Unknown event type: {} (event_id={})",
                    event.getEventType(), event.getEventId());
                publishToLedger(event);
        }
    }

    /**
     * Publish event to ledger (via RabbitMQ or local buffer)
     */
    private void publishToLedger(LedgerEvent event) {
        try {
            if (circuitBreaker.isOpen()) {
                // Java Ledger is down: buffer locally
                circuitBreaker.bufferEvent(event);
                log.debug("Event buffered (circuit OPEN): event_id={}", event.getEventId());
            } else {
                // Normal path: publish to RabbitMQ
                publisher.publishToLedger(event);
                log.debug("Event published to ledger: event_id={}", event.getEventId());
            }
        } catch (Exception e) {
            log.error("Error publishing event: event_id={}", event.getEventId(), e);
            throw new RuntimeException("Failed to publish event", e);
        }
    }

    /**
     * Get current queue size (for monitoring)
     */
    public int getQueueSize() {
        return eventQueue.size();
    }

    /**
     * Get circuit breaker state (for monitoring)
     */
    public String getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    /**
     * Metrics reporting
     */
    @Scheduled(fixedDelay = 10000)  // Every 10 seconds
    public void reportMetrics() {
        log.info("gateway_metrics",
            () -> Map.of(
                "queue_size", getQueueSize(),
                "circuit_breaker", getCircuitBreakerState(),
                "dedup_cache_size", deduplicator.getCacheSize()
            ));
    }
}
