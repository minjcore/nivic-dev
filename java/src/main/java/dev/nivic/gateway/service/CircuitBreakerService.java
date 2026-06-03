package dev.nivic.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.nivic.gateway.model.LedgerEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class CircuitBreakerService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CircuitBreakerService.class);


    enum State {
        CLOSED,      // Normal: publish to RabbitMQ
        OPEN,        // Java Ledger down: buffer locally
        HALF_OPEN    // Attempting recovery: slowly drain buffer
    }

    @Autowired
    private EventPublisher publisher;

    private volatile State state = State.CLOSED;
    private volatile long openedAt = 0;
    private final long TIMEOUT_MS = 30_000;  // 30 seconds before half-open

    private final Queue<LedgerEvent> localBuffer = new ConcurrentLinkedQueue<>();
    private static final int MAX_BUFFER_SIZE = 100_000;

    /**
     * Check if circuit is open (don't publish to RabbitMQ)
     */
    public boolean isOpen() {
        return state == State.OPEN || state == State.HALF_OPEN;
    }

    /**
     * Buffer event locally (when circuit is open)
     */
    public void bufferEvent(LedgerEvent event) {
        if (localBuffer.size() >= MAX_BUFFER_SIZE) {
            log.error("Local buffer full: max_size={}, dropping event: event_id={}",
                MAX_BUFFER_SIZE, event.getEventId());
            return;
        }

        if (localBuffer.offer(event)) {
            log.debug("Event buffered locally: event_id={}, buffer_size={}",
                event.getEventId(), localBuffer.size());
        } else {
            log.error("Failed to buffer event: event_id={}", event.getEventId());
        }
    }

    /**
     * Get circuit breaker state (for monitoring)
     */
    public String getState() {
        return state.toString();
    }

    /**
     * Get local buffer size (for monitoring)
     */
    public int getBufferSize() {
        return localBuffer.size();
    }

    /**
     * Health check: attempt to publish to RabbitMQ
     * Runs every 5 seconds
     */
    @Scheduled(fixedDelay = 5000)
    public void healthCheck() {
        try {
            // Create a test event to check RabbitMQ connectivity
            LedgerEvent testEvent = new LedgerEvent(
                -1,  // Special marker
                "HEALTHCHECK",
                "system"
            );

            // Attempt to publish
            try {
                publisher.publishToLedger(testEvent);

                // Success: RabbitMQ is up
                if (state != State.CLOSED) {
                    log.info("Circuit breaker transitioning to CLOSED");
                    state = State.CLOSED;
                    drainLocalBuffer();
                }

            } catch (Exception e) {
                // RabbitMQ publish failed
                if (state == State.CLOSED) {
                    log.error("Circuit breaker OPEN: RabbitMQ unreachable - {}", e.getMessage());
                    state = State.OPEN;
                    openedAt = System.currentTimeMillis();

                } else if (state == State.OPEN) {
                    long elapsed = System.currentTimeMillis() - openedAt;
                    if (elapsed > TIMEOUT_MS) {
                        log.info("Circuit breaker transitioning to HALF_OPEN (timeout: {}ms)", elapsed);
                        state = State.HALF_OPEN;
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error during health check", e);
        }
    }

    /**
     * Drain buffered events back to RabbitMQ
     * Called when circuit transitions from OPEN → CLOSED
     */
    private void drainLocalBuffer() {
        if (localBuffer.isEmpty()) {
            return;
        }

        int bufferSize = localBuffer.size();
        log.info("Starting to drain local buffer: size={}", bufferSize);

        int drained = 0;
        int failed = 0;

        try {
            while (!localBuffer.isEmpty() && drained < bufferSize) {
                LedgerEvent event = localBuffer.poll();
                if (event == null) break;

                try {
                    publisher.publishToLedger(event);
                    drained++;

                    if (drained % 1000 == 0) {
                        log.info("Draining buffer progress: {} of {}", drained, bufferSize);
                    }

                } catch (Exception e) {
                    failed++;
                    log.error("Failed to drain event: event_id={}", event.getEventId(), e);
                    // Put it back in buffer
                    localBuffer.offer(event);
                }
            }

            log.info("Buffer drain complete: drained={}, failed={}, remaining={}",
                drained, failed, localBuffer.size());

        } catch (Exception e) {
            log.error("Error during buffer drain", e);
        }
    }

    /**
     * Metrics reporting
     */
    @Scheduled(fixedDelay = 10000)  // Every 10 seconds
    public void reportMetrics() {
        if (state != State.CLOSED) {
            log.warn("circuit_breaker_metrics: state={}, buffer_size={}, uptime_open_ms={}",
                state.toString(),
                localBuffer.size(),
                state == State.CLOSED ? 0 : System.currentTimeMillis() - openedAt);
        }
    }
}
