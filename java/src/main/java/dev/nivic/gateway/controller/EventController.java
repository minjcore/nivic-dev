package dev.nivic.gateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.nivic.gateway.model.LedgerEvent;
import dev.nivic.gateway.service.EventService;
import dev.nivic.gateway.service.RateLimitService;
import dev.nivic.gateway.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EventController.class);


    @Autowired
    private EventService eventService;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private AuthService authService;

    /**
     * Receive event from C Server
     * Fire-and-forget: immediate ACK, process async
     */
    @PostMapping
    public ResponseEntity<?> receiveEvent(
            @RequestBody LedgerEvent event,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        try {
            // 1. Validate request
            if (event == null || event.getEventId() == 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid event: missing event_id"));
            }

            // 2. Authenticate API key
            String clientId = authService.validateApiKey(authHeader);
            if (clientId == null) {
                log.warn("Unauthorized event request: invalid auth header");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid API key"));
            }

            // 3. Rate limit check
            if (!rateLimitService.allowRequest(clientId, 10000)) {
                log.warn("Rate limit exceeded for client: {}", clientId);
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Rate limit exceeded"));
            }

            // 4. Validate event schema
            String validationError = validateEventSchema(event);
            if (validationError != null) {
                log.warn("Invalid event schema: {} - error: {}",
                    event.getEventId(), validationError);
                return ResponseEntity.badRequest()
                    .body(Map.of("error", validationError));
            }

            // 5. Set gateway metadata
            event.setSourceCServer(clientId);
            event.setGatewayReceivedAt(System.currentTimeMillis());

            // 6. Enqueue for async processing (fire-and-forget)
            eventService.enqueueEvent(event);

            log.debug("Event received: event_id={}, type={}, client={}", event.getEventId(), event.getEventType(), clientId);

            // 7. Immediate response (< 10ms)
            return ResponseEntity.ok(Map.of(
                "status", "accepted",
                "event_id", event.getEventId()
            ));

        } catch (Exception e) {
            log.error("Error receiving event", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * Health check endpoint (for C server retries)
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        health.put("queue_size", eventService.getQueueSize());
        health.put("circuit_breaker_state", eventService.getCircuitBreakerState());
        return ResponseEntity.ok(health);
    }

    /**
     * Validate event schema
     */
    private String validateEventSchema(LedgerEvent event) {
        if (event.getEventType() == null || event.getEventType().isEmpty()) {
            return "Missing event_type";
        }

        if (event.getTimestamp() == 0) {
            return "Missing timestamp";
        }

        if (event.getTimestamp() > System.currentTimeMillis() + 60000) {
            return "Event timestamp too far in future";
        }

        // Event-specific validation
        return null;
    }
}
