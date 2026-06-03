# Event Push Protocol: C Server → Java Ledger

**Version:** 1.0  
**Status:** ACTIVE  
**Date:** 2026-06-03  
**Direction:** C Server (:7474) → Java Ledger (:8090)  
**Reliability:** At-least-once delivery (idempotency via event ID)

---

## Overview

The **Event Push Protocol** allows the C server to asynchronously notify the Java ledger of important events that occur in the wire protocol flow.

**Design Goals:**
- **Decoupled:** C server doesn't block waiting for Java response
- **Reliable:** Events delivered even if Java temporarily down
- **Idempotent:** Duplicate events safely handled via Snowflake ID
- **Traceable:** Full audit trail for compliance (PCI-DSS, fintech regulations)
- **Scalable:** 10k+ events/sec via RabbitMQ or webhooks

---

## Event Types

### C Server → Java Ledger Events

```
Event Type              │ Priority │ Delivery    │ Purpose
────────────────────────┼──────────┼─────────────┼─────────────────────────
TRANSACTION_POSTED      │ CRITICAL │ RabbitMQ    │ Double-entry posted
TRANSACTION_FAILED      │ HIGH     │ RabbitMQ    │ Validation/DB error
SESSION_CREATED         │ MEDIUM   │ RabbitMQ    │ User authenticated
SESSION_EXPIRED         │ MEDIUM   │ HTTP        │ Token expired
PAYMENT_SETTLED         │ CRITICAL │ RabbitMQ    │ Payment committed
SETTLEMENT_ERROR        │ HIGH     │ RabbitMQ    │ Settlement failed
BALANCE_UPDATED         │ MEDIUM   │ HTTP        │ Real-time balance sync
FRAUD_DETECTED          │ CRITICAL │ RabbitMQ    │ Suspicious pattern
RATE_LIMIT_EXCEEDED     │ MEDIUM   │ HTTP        │ Throttling applied
MERCHANT_PAYOUT_READY   │ HIGH     │ RabbitMQ    │ Batch payout pending
```

---

## Event Structure (JSON)

### Base Event Schema

```json
{
  "event_id": "1234567890123456789",        // Snowflake ID (unique, monotonic)
  "event_type": "TRANSACTION_POSTED",       // Event type
  "timestamp": 1717435200000,               // Unix milliseconds
  "source": "wire-c-server",                // Origin identifier
  "request_id": "abc-123-def",              // Correlate with wire transaction
  "user_id": "user123",                     // User context
  "correlation_id": 9876543210,             // Links to wire frame
  "version": "1.0",                         // Schema version
  "data": { ... },                          // Event-specific payload
  "retry_count": 0                          // Incremented on retry
}
```

### Event Examples

#### 1. TRANSACTION_POSTED

```json
{
  "event_id": "1234567890123456789",
  "event_type": "TRANSACTION_POSTED",
  "timestamp": 1717435200000,
  "source": "wire-c-server",
  "request_id": "abc1234567890",
  "user_id": "user123",
  "data": {
    "trans_id": 42,
    "ref_id": "abc1234567890",
    "account_codes": ["1111000001", "2222000001"],
    "total_debit_minor": 10000,
    "total_credit_minor": 10000,
    "currency": "VND",
    "status": "POSTED",
    "posted_at": 1717435200000
  }
}
```

#### 2. PAYMENT_SETTLED

```json
{
  "event_id": "1234567890123456790",
  "event_type": "PAYMENT_SETTLED",
  "timestamp": 1717435205000,
  "source": "wire-c-server",
  "request_id": "payment-456",
  "user_id": "user123",
  "data": {
    "trans_id": 42,
    "from_account": "1111000001",
    "to_account": "2222000001",
    "amount_minor": 10000,
    "currency": "VND",
    "merchant_id": 999,
    "settlement_batch_id": "batch-2026-06-03-001",
    "settlement_time": 1717435205000,
    "reference": "MCC-5411"
  }
}
```

#### 3. TRANSACTION_FAILED

```json
{
  "event_id": "1234567890123456791",
  "event_type": "TRANSACTION_FAILED",
  "timestamp": 1717435210000,
  "source": "wire-c-server",
  "request_id": "abc1234567891",
  "user_id": "user123",
  "data": {
    "ref_id": "abc1234567891",
    "reason": "INSUFFICIENT_BALANCE",
    "account_code": "1111000001",
    "requested_amount": 50000,
    "available_balance": 25000,
    "error_code": "0x0005",
    "failed_at": 1717435210000
  }
}
```

#### 4. FRAUD_DETECTED

```json
{
  "event_id": "1234567890123456792",
  "event_type": "FRAUD_DETECTED",
  "timestamp": 1717435215000,
  "source": "wire-c-server",
  "request_id": "fraud-check-1",
  "user_id": "user123",
  "data": {
    "user_id": "user123",
    "alert_type": "HIGH_FREQUENCY",
    "description": "10 transactions in 30 seconds",
    "transaction_count": 10,
    "time_window_seconds": 30,
    "recommended_action": "BLOCK_USER",
    "confidence": 0.95,
    "fraud_score": 0.85
  }
}
```

#### 5. BALANCE_UPDATED

```json
{
  "event_id": "1234567890123456793",
  "event_type": "BALANCE_UPDATED",
  "timestamp": 1717435220000,
  "source": "wire-c-server",
  "request_id": "balance-sync-1",
  "user_id": "user123",
  "data": {
    "account_code": "1111000001",
    "balance_minor": 990000,
    "held_minor": 0,
    "available_minor": 990000,
    "currency": "VND",
    "previous_balance": 1000000,
    "delta": -10000,
    "as_of": 1717435220000
  }
}
```

---

## Transport: RabbitMQ (Preferred)

### Setup

**RabbitMQ Exchange Configuration:**

```
Exchange:    gtel-events
Type:        Topic
Durable:     True
Auto-delete: False
```

**Queue Configuration:**

```
Queue:       java-ledger-events
Exchange:    gtel-events
Routing Key: ledger.*
Durable:     True
TTL:         24 hours (86400000 ms)
Max Length:  1,000,000 messages
```

### Publishing (C Server)

```c
// Pseudocode: C server publishes event to RabbitMQ
void publish_event(Event event) {
    const char *routing_key = "ledger.transaction_posted";  // Based on event_type
    const char *json_payload = event_to_json(&event);
    
    // Set headers
    amqp_table_t headers;
    headers["x-event-id"] = event.event_id;
    headers["x-event-type"] = event.event_type;
    headers["x-timestamp"] = event.timestamp;
    
    // Publish with persistence
    amqp_basic_publish(
        connection,
        1,                      // channel
        exchange,               // gtel-events
        routing_key,            // ledger.TRANSACTION_POSTED
        1,                      // mandatory
        1,                      // immediate
        &properties,            // persistent delivery
        amqp_cstring_bytes(json_payload)
    );
    
    // Confirm delivery
    if (!wait_for_confirm(connection)) {
        enqueue_for_retry(event, 5);  // Retry in 5 seconds
    }
}
```

### Consuming (Java Ledger)

```java
// Java: Listen on RabbitMQ queue
@RabbitListener(queues = "java-ledger-events")
public void handleLedgerEvent(Message message, Channel channel) throws IOException {
    try {
        String json = new String(message.getBody());
        LedgerEvent event = objectMapper.readValue(json, LedgerEvent.class);
        
        // Idempotency check: skip if event_id already processed
        if (eventStore.exists(event.getEventId())) {
            log.debug("Duplicate event (already processed): {}", event.getEventId());
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            return;
        }
        
        // Process event
        processEvent(event);
        
        // Mark as processed (for idempotency)
        eventStore.saveProcessed(event.getEventId(), event.getTimestamp());
        
        // Acknowledge message (removes from queue)
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        
    } catch (Exception e) {
        log.error("Error processing event: {}", e.getMessage());
        // NACK: requeue message for retry (exponential backoff in queue)
        channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
    }
}
```

**Routing Key Mapping:**

```
Event Type              │ Routing Key
────────────────────────┼──────────────────────
TRANSACTION_POSTED      │ ledger.transaction_posted
TRANSACTION_FAILED      │ ledger.transaction_failed
PAYMENT_SETTLED         │ ledger.payment_settled
SETTLEMENT_ERROR        │ ledger.settlement_error
FRAUD_DETECTED          │ ledger.fraud_detected
SESSION_CREATED         │ ledger.session_created
... (etc)               │ ledger.*
```

---

## Transport: HTTP Webhooks (Fallback)

### Endpoint

**Java Ledger Webhook Endpoint:**

```
POST /api/events/push
Content-Type: application/json
X-Event-Signature: HMAC-SHA256(body, webhook_secret)
```

### Webhook Handler (Java)

```java
@PostMapping("/api/events/push")
public ResponseEntity<?> receiveEvent(
        @RequestBody LedgerEvent event,
        @RequestHeader("X-Event-Signature") String signature,
        @RequestHeader("X-Timestamp") long timestamp) {
    
    // Verify signature
    String expectedSig = hmacSha256(objectMapper.writeValueAsString(event), webhookSecret);
    if (!signature.equals(expectedSig)) {
        return ResponseEntity.status(401).body("Invalid signature");
    }
    
    // Verify freshness (within 5 minutes)
    long now = System.currentTimeMillis();
    if (Math.abs(now - timestamp) > 300_000) {
        return ResponseEntity.status(400).body("Request expired");
    }
    
    // Idempotency check
    if (eventStore.exists(event.getEventId())) {
        return ResponseEntity.ok().body("{\"status\": \"idempotent\"}");
    }
    
    try {
        processEvent(event);
        eventStore.saveProcessed(event.getEventId(), event.getTimestamp());
        return ResponseEntity.ok().body("{\"status\": \"accepted\", \"event_id\": \"" + event.getEventId() + "\"}");
    } catch (Exception e) {
        return ResponseEntity.status(500).body("{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
    }
}
```

### C Server Webhook Publishing

```c
// C server pushes event via HTTP
void publish_event_http(Event event) {
    const char *url = "http://localhost:8090/api/events/push";
    char *json = event_to_json(&event);
    long timestamp = current_time_ms();
    char *signature = hmac_sha256(json, webhook_secret);
    
    // Build headers
    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    headers = curl_slist_append(headers, "X-Event-Signature: " + signature);
    headers = curl_slist_append(headers, "X-Timestamp: " + timestamp);
    
    // POST
    CURL *curl = curl_easy_init();
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, json);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    
    CURLcode res = curl_easy_perform(curl);
    
    // Retry on failure
    if (res != CURLE_OK) {
        schedule_retry(event, 5000);  // 5 second backoff
    }
    
    curl_easy_cleanup(curl);
}
```

---

## Reliability & Deduplication

### Snowflake ID Generation (C Server)

```c
// Twitter Snowflake: 64-bit distributed ID
// [1 bit unused][41 bits timestamp][5 bits datacenter][5 bits worker][12 bits sequence]

typedef struct {
    long datacenter_id;    // 0-31 (e.g., US-WEST = 1)
    long worker_id;        // 0-31 (e.g., C-Server-1 = 5)
    long sequence;         // 0-4095 (per millisecond)
    long last_timestamp;   // Last generated ID timestamp
} SnowflakeGenerator;

long generate_snowflake_id(SnowflakeGenerator *gen) {
    long timestamp = current_time_ms();
    
    // Handle clock skew
    if (timestamp < gen->last_timestamp) {
        error("Clock moved backwards!");
        return -1;
    }
    
    // Reset sequence on new millisecond
    if (timestamp > gen->last_timestamp) {
        gen->sequence = 0;
    } else {
        gen->sequence = (gen->sequence + 1) & 0xFFF;  // 12-bit mask
        if (gen->sequence == 0) {
            timestamp = wait_next_ms(gen->last_timestamp);
        }
    }
    
    gen->last_timestamp = timestamp;
    
    // Bit composition
    long id = 0;
    id |= (timestamp - EPOCH_2017) << 22;  // 41 bits
    id |= (gen->datacenter_id << 17);       // 5 bits
    id |= (gen->worker_id << 12);           // 5 bits
    id |= gen->sequence;                    // 12 bits
    
    return id;
}
```

### Idempotency Store (Java)

```java
// Table: ledger_event_dedup
// Stores: event_id (primary key), event_type, timestamp, processed_at

@Entity
@Table(name = "ledger_event_dedup")
public class EventDedup {
    @Id
    private long event_id;                  // Snowflake ID
    
    @Column(nullable = false)
    private String event_type;
    
    @Column(nullable = false)
    private long event_timestamp;
    
    @Column(nullable = false)
    private LocalDateTime processed_at;     // When received
    
    @Column
    private String result_code;             // "OK", "ERROR", etc.
}

// Check for duplicates
boolean isDuplicate(long eventId) {
    return eventDedupRepo.existsById(eventId);
}

// Record processed event
void recordProcessed(long eventId, String eventType, long timestamp) {
    EventDedup dedup = new EventDedup();
    dedup.setEventId(eventId);
    dedup.setEventType(eventType);
    dedup.setEventTimestamp(timestamp);
    dedup.setProcessedAt(LocalDateTime.now());
    dedup.setResultCode("OK");
    eventDedupRepo.save(dedup);
}
```

### Retention & Cleanup

```java
// Clean up old dedup records (keep 7 days)
@Scheduled(cron = "0 2 * * *")  // Daily at 2 AM
public void cleanupOldEvents() {
    LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
    eventDedupRepo.deleteByProcessedAtBefore(sevenDaysAgo);
}
```

---

## Error Handling & Retries

### Retry Strategy (Exponential Backoff)

```
Attempt │ Delay       │ Total Wait
────────┼─────────────┼────────────
1       │ 5 seconds   │ 5s
2       │ 10 seconds  │ 15s
3       │ 20 seconds  │ 35s
4       │ 40 seconds  │ 75s
5       │ 80 seconds  │ 155s
6       │ 160 seconds │ 315s
7       │ Dead Letter Queue (manual intervention)
```

### Retry Queue (RabbitMQ)

```
Queue:       ledger-events-retry
Exchange:    gtel-events-retry
TTL:         5 minutes per attempt
Max retries: 6 (then → Dead Letter Queue)
```

### Dead Letter Queue

```json
{
  "event_id": "1234567890123456789",
  "event_type": "TRANSACTION_POSTED",
  "error_reason": "Max retries exceeded",
  "last_error": "Connection timeout",
  "retry_count": 6,
  "enqueued_at": 1717435200000,
  "dlq_at": 1717435500000
}
```

**Monitoring:** Alert on events in DLQ (indicates system issues)

---

## Event Processing Flow (Java)

```
[RabbitMQ/HTTP]
      ↓
[Receive Event]
      ↓
[Check Idempotency] ←─ Skip if duplicate
      │
      ├─→ TRANSACTION_POSTED
      │   ├─ Update coa_trans status
      │   ├─ Update balance cache
      │   └─ Publish to analytics
      │
      ├─→ PAYMENT_SETTLED
      │   ├─ Record settlement batch
      │   ├─ Generate merchant payout
      │   └─ Update reconciliation
      │
      ├─→ FRAUD_DETECTED
      │   ├─ Lock user account
      │   ├─ Alert compliance team
      │   └─ Notify via email/SMS
      │
      └─→ [Record in dedup store]
           ↓
          [ACK/Response]
```

---

## Monitoring & Observability

### Metrics (Prometheus)

```
# Event publishing (C server)
gtel_events_published_total{event_type="TRANSACTION_POSTED"} 1024
gtel_events_published_total{event_type="PAYMENT_SETTLED"} 512
gtel_events_publish_errors_total{reason="network_timeout"} 3

# Event processing (Java)
gtel_events_received_total{event_type="TRANSACTION_POSTED"} 1022
gtel_events_processed_seconds{event_type="TRANSACTION_POSTED"} 0.045
gtel_events_retries_total{event_type="PAYMENT_SETTLED"} 2
gtel_events_dlq_total{event_type="FRAUD_DETECTED"} 0
```

### Alerting

```yaml
- alert: EventProcessingBacklog
  expr: rabbitmq_queue_messages_ready{queue="java-ledger-events"} > 10000
  annotations:
    message: "Event processing backlog: {{ $value }} messages"

- alert: DeadLetterQueueAlarm
  expr: rabbitmq_queue_messages_ready{queue="ledger-events-dlq"} > 0
  annotations:
    message: "Events in DLQ - investigate: {{ $value }}"

- alert: EventPublishFailure
  expr: rate(gtel_events_publish_errors_total[5m]) > 0.1
  annotations:
    message: "Event publishing errors detected"
```

### Logging

```java
// Structured logging for event processing
log.info("event_processed",
    kv("event_id", event.getEventId()),
    kv("event_type", event.getEventType()),
    kv("processing_time_ms", processingTime),
    kv("status", "success")
);

// Error logging
log.error("event_failed",
    kv("event_id", event.getEventId()),
    kv("error_code", errorCode),
    kv("retry_count", retryCount),
    ex);  // Include stacktrace
```

---

## Security Considerations

### Message Signing (Webhook)

```
Signature = HMAC-SHA256(JSON_Body, WebhookSecret)
Header:    X-Event-Signature: sha256=abc123def456...
Verify:    Compare signature with HMAC of received body
```

### RabbitMQ Authentication

```
User:     gtel-c-server
Password: (strong, rotated quarterly)
Vhost:    /gtel-prod
Perms:    publish on gtel-events, listen on ledger-events-retry
```

### Network Security

- **TLS 1.3** for webhook HTTPS
- **AMQPS** (TLS) for RabbitMQ connections
- **IP Whitelisting:** Only C server can publish to RabbitMQ
- **Rate Limiting:** 10k events/sec per C server instance

---

## Production Deployment

### RabbitMQ Setup

```bash
# Create exchange
rabbitmqctl declare_exchange gtel-events topic durable=true

# Create queues
rabbitmqctl declare_queue java-ledger-events durable=true
rabbitmqctl declare_queue ledger-events-retry durable=true
rabbitmqctl declare_queue ledger-events-dlq durable=true

# Bind queues to exchange
rabbitmqctl bind_queue gtel-events java-ledger-events "ledger.*"
rabbitmqctl bind_queue gtel-events-retry ledger-events-retry "ledger.*"

# Set DLQ policy
rabbitmqctl set_policy dlx "ledger-events-retry" '{"dead-letter-exchange":"gtel-events-dlq"}' \
  --apply-to queues --priority 10
```

### Java Ledger Configuration

```yaml
spring:
  rabbitmq:
    host: rabbitmq.internal
    port: 5672
    username: gtel-c-server
    password: ${RABBITMQ_PASSWORD}
    virtual-host: /gtel-prod
    listener:
      simple:
        concurrency: 8          # 8 consumer threads
        max-concurrency: 16
        prefetch: 10            # Pre-fetch 10 messages
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms
          max-interval: 10000ms

  datasource:
    hikari:
      auto-commit: false       # Manual transaction control for idempotency
      maximum-pool-size: 20
```

### C Server Configuration

```c
// RabbitMQ connection pool
struct {
    char *host;              // "rabbitmq.internal"
    int port;                // 5672
    char *vhost;             // "/gtel-prod"
    char *username;          // "gtel-c-server"
    char *password;          // from env
    int pool_size;           // 4 connections
    int heartbeat;           // 60 seconds
} rabbitmq_config;

// Webhook fallback
struct {
    char *url;               // "http://java-ledger:8090/api/events/push"
    char *secret;            // webhook secret
    int timeout_ms;          // 30000
    int max_retries;         // 6
} webhook_config;
```

---

## Testing Strategy

### Unit Tests (Java)

```java
@Test
void testEventIdempotency() {
    LedgerEvent event = new LedgerEvent(...);
    
    // First process
    eventService.processEvent(event);
    assertTrue(eventStore.exists(event.getEventId()));
    
    // Duplicate should be skipped
    eventService.processEvent(event);
    assertEquals(1, transactionRepo.count());  // No duplicates
}

@Test
void testDLQOnMaxRetries() {
    RabbitTemplate template = new RabbitTemplate(...);
    
    // Simulate 6 failed retries
    for (int i = 0; i < 6; i++) {
        simulateConsumerError(event);
    }
    
    // Should move to DLQ
    Message dlqMessage = rabbitTemplate.receiveAndConvert("ledger-events-dlq");
    assertNotNull(dlqMessage);
}
```

### Integration Tests

```java
@Test
void testEventPublishingToRabbitMQ() {
    LedgerEvent event = new LedgerEvent(
        "TRANSACTION_POSTED",
        Map.of("trans_id", 42L)
    );
    
    eventService.publishEvent(event);
    
    // Verify received on Java side
    Thread.sleep(1000);
    assertTrue(eventStore.exists(event.getEventId()));
}
```

### Load Tests

```bash
# Generate 1000 events/sec for 60 seconds
jmeter -Jrabbitmq.host=localhost \
       -Jrabbitmq.rate=1000 \
       -Jduration=60 \
       -n -t event-load-test.jmx
```

---

## References

- **Snowflake ID:** Twitter's distributed ID algorithm (69-year lifespan, 4M IDs/sec per node)
- **RabbitMQ:** [RabbitMQ Tutorials](https://www.rabbitmq.com/getstarted.html)
- **Idempotent Consumers:** [Enterprise Integration Patterns](https://www.enterpriseintegrationpatterns.com/IdempotentReceiver.html)
- **Event Sourcing:** [Martin Fowler - Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)

---

## Deployment Timeline

| Phase | Action | Timeline | Owner |
|-------|--------|----------|-------|
| Design | Approval of this spec | Today | Architecture |
| Dev | C server event publishing | Week 1 | C Team |
| Dev | Java event consumer | Week 1 | Java Team |
| Test | Integration tests + load test | Week 2 | QA |
| Staging | 48-hour staging validation | Week 2 | DevOps |
| Prod | Production deployment | June 10 | DevOps |

---

## Appendix: Example RabbitMQ Setup (Docker)

```yaml
version: '3.8'
services:
  rabbitmq:
    image: rabbitmq:3.12-management-alpine
    ports:
      - "5672:5672"    # AMQP
      - "15672:15672"  # Management UI
    environment:
      RABBITMQ_DEFAULT_USER: admin
      RABBITMQ_DEFAULT_PASS: admin
    volumes:
      - ./rabbitmq-init.conf:/etc/rabbitmq/rabbitmq.conf
```

**rabbitmq-init.conf:**
```
# Enable management plugin
management.load_definitions = /etc/rabbitmq/definitions.json

# Clustering (if HA needed)
cluster_partition_handling = autoheal
```

