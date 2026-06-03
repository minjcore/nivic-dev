package dev.nivic.gateway.service;

import dev.nivic.gateway.model.LedgerEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
public class EventPublisher {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RedisTemplate<String, String> redis;

    private static final String EXCHANGE_NAME = "gtel-events";

    /**
     * Publish event to Java Ledger (via RabbitMQ)
     */
    public void publishToLedger(LedgerEvent event) {
        String routingKey = determineRoutingKey(event.getEventType());
        event.setRoutingKey(routingKey);

        try {
            rabbitTemplate.convertAndSend(EXCHANGE_NAME, routingKey, event, message -> {
                // Set persistent delivery
                message.getMessageProperties().setDeliveryMode(
                    MessageProperties.DEFAULT_CONTENT_TYPE != null ?
                        org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT :
                        org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT
                );
                message.getMessageProperties().setHeader("X-Event-ID", event.getEventId());
                message.getMessageProperties().setHeader("X-Event-Type", event.getEventType());
                message.getMessageProperties().setHeader("X-Timestamp", event.getTimestamp());
                return message;
            });

            log.info("event_published",
                () -> Map.of(
                    "event_id", event.getEventId(),
                    "event_type", event.getEventType(),
                    "routing_key", routingKey
                ));

        } catch (Exception e) {
            log.error("Error publishing event to ledger: event_id={}", event.getEventId(), e);
            throw new RuntimeException("Failed to publish event to RabbitMQ", e);
        }
    }

    /**
     * Publish event to cache (Redis)
     * Used for BALANCE_UPDATED, SESSION_CREATED, etc.
     */
    public void publishToCache(LedgerEvent event) {
        try {
            switch (event.getEventType()) {
                case "BALANCE_UPDATED":
                    updateBalanceCache(event);
                    break;

                case "SESSION_CREATED":
                    createSessionCache(event);
                    break;

                case "SESSION_EXPIRED":
                    removeSessionCache(event);
                    break;

                default:
                    log.debug("No cache action for event type: {}", event.getEventType());
            }
        } catch (Exception e) {
            log.error("Error publishing to cache: event_id={}", event.getEventId(), e);
            // Non-fatal: don't fail ledger processing if cache fails
        }
    }

    /**
     * Update balance cache
     */
    private void updateBalanceCache(LedgerEvent event) {
        String accountCode = (String) event.getData().get("account_code");
        Long balanceMinor = (Long) event.getData().get("balance_minor");

        if (accountCode == null || balanceMinor == null) {
            log.warn("Missing balance data: account_code={}", accountCode);
            return;
        }

        String key = "balance:" + accountCode;
        try {
            redis.opsForValue().set(
                key,
                balanceMinor.toString(),
                Duration.ofHours(1)  // 1 hour TTL
            );
            log.debug("Balance cache updated: account_code={}, balance={}", accountCode, balanceMinor);
        } catch (Exception e) {
            log.error("Error updating balance cache: account_code={}", accountCode, e);
        }
    }

    /**
     * Create session cache
     */
    private void createSessionCache(LedgerEvent event) {
        String userId = event.getUserId();
        if (userId == null) {
            log.warn("Missing user_id for session creation");
            return;
        }

        String key = "session:" + userId;
        try {
            redis.opsForValue().set(
                key,
                "ACTIVE",
                Duration.ofHours(24)  // 24 hour TTL
            );
            log.debug("Session cache created: user_id={}", userId);
        } catch (Exception e) {
            log.error("Error creating session cache: user_id={}", userId, e);
        }
    }

    /**
     * Remove session cache
     */
    private void removeSessionCache(LedgerEvent event) {
        String userId = event.getUserId();
        if (userId == null) {
            log.warn("Missing user_id for session cleanup");
            return;
        }

        String key = "session:" + userId;
        try {
            redis.delete(key);
            log.debug("Session cache removed: user_id={}", userId);
        } catch (Exception e) {
            log.error("Error removing session cache: user_id={}", userId, e);
        }
    }

    /**
     * Determine routing key based on event type
     */
    private String determineRoutingKey(String eventType) {
        return switch (eventType) {
            case "TRANSACTION_POSTED" -> "ledger.transaction_posted";
            case "TRANSACTION_FAILED" -> "ledger.transaction_failed";
            case "PAYMENT_SETTLED" -> "ledger.payment_settled";
            case "SETTLEMENT_ERROR" -> "ledger.settlement_error";
            case "FRAUD_DETECTED" -> "ledger.fraud_detected";
            default -> "ledger.unknown";
        };
    }
}
