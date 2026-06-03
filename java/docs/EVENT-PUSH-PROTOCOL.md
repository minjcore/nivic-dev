# Event Push Protocol: C Server → Saving-Gateway → Java Ledger

**Version:** 1.0  
**Status:** ACTIVE  
**Date:** 2026-06-03  
**Architecture:** C Server (:7474) → Saving-Gateway (Orchestration) → Java Ledger (:8090)  
**Reliability:** At-least-once delivery (idempotency via Snowflake ID)

---

## Overview

The **Event Push Protocol** enables asynchronous event flow from the C server (wire protocol) through the **Saving-Gateway** orchestration layer to the Java ledger.

**Architecture:**

```
┌─────────────┐
│  C Server   │
│  (:7474)    │  Publishes events
└──────┬──────┘
       │
       │ Event (JSON + Snowflake ID)
       │ HTTP/gRPC
       ↓
┌──────────────────────────────────────┐
│    Saving-Gateway (Orchestration)    │
│                                      │
│  ├─ Event aggregation                │
│  ├─ Deduplication (Snowflake ID)     │
│  ├─ Event enrichment                 │
│  ├─ Routing (by event type)          │
│  ├─ Circuit breaker (Java down)      │
│  ├─ Rate limiting                    │
│  └─ Batch optimization               │
└──────┬───────────────────────────────┘
       │
       │ RabbitMQ Topic Exchange
       │ gtel-events
       ↓
┌──────────────────────────────────────┐
│   Message Queue (RabbitMQ)           │
│                                      │
│  java-ledger-events                  │
│  ledger-events-retry                 │
│  ledger-events-dlq                   │
└──────┬───────────────────────────────┘
       │
       │ Event consumer
       ↓
┌──────────────────────────────────────┐
│      Java Ledger (:8090)             │
│                                      │
│  ├─ Idempotency check (event_id)     │
│  ├─ Double-entry validation          │
│  ├─ Disruptor pipeline               │
│  └─ Audit logging                    │
└──────────────────────────────────────┘
```

**Design Goals:**
- **Decoupled:** C server publishes, doesn't wait for confirmation
- **Centralized Routing:** Saving-Gateway handles event routing logic
- **Reliable:** Events queued if Java temporarily down
- **Idempotent:** Dedup via Snowflake ID (monotonic, distributed)
- **Observable:** Full audit trail + metrics at gateway level
- **Scalable:** 10k+ events/sec, batch processing, circuit breakers

---

## Event Types

### C Server → Saving-Gateway Events

```
Event Type              │ Priority │ Handler     │ Purpose
────────────────────────┼──────────┼─────────────┼─────────────────────────
TRANSACTION_POSTED      │ CRITICAL │ Ledger      │ Double-entry posted
TRANSACTION_FAILED      │ HIGH     │ Ledger      │ Validation/DB error
SESSION_CREATED         │ MEDIUM   │ Ledger      │ User authenticated
SESSION_EXPIRED         │ MEDIUM   │ Gateway     │ Token cleanup
PAYMENT_SETTLED         │ CRITICAL │ Ledger      │ Payment committed
SETTLEMENT_ERROR        │ HIGH     │ Ledger      │ Settlement failed
BALANCE_UPDATED         │ MEDIUM   │ Cache       │ Real-time sync
FRAUD_DETECTED          │ CRITICAL │ Ledger      │ Suspicious pattern
RATE_LIMIT_EXCEEDED     │ MEDIUM   │ Gateway     │ Throttling applied
MERCHANT_PAYOUT_READY   │ HIGH     │ Ledger      │ Batch payout pending
DEVICE_REGISTERED       │ LOW      │ Gateway     │ Mobile client registered
```

**Event Routing (Saving-Gateway):**

| Handler   | Events                                          | Action |
|-----------|------------------------------------------------|--------|
| **Ledger** | TRANSACTION_POSTED, PAYMENT_SETTLED, etc.     | → RabbitMQ gtel-events |
| **Cache**  | BALANCE_UPDATED, SESSION_CREATED              | → Redis, update user session |
| **Gateway** | SESSION_EXPIRED, RATE_LIMIT_EXCEEDED          | → Local dedup, circuit breaker |

---

## Saving-Gateway Responsibilities

### 1. Event Reception (HTTP/gRPC)

```
Endpoint: POST /api/events (Saving-Gateway)
↑
├─ TLS 1.3 (encrypted)
├─ API Key authentication
├─ Rate limiting (10k events/sec max per C server)
├─ Request validation + schema
└─ Immediate ACK (fire-and-forget)

Response: { "status": "accepted", "event_id": "..." }
Timeline: < 10ms
```

### 2. Event Aggregation

```
Single C Server     All C Servers
   ↓                    ↓
Event 1              Event 1 (C-1)
Event 2              Event 2 (C-1)
Event 3              Event 3 (C-2)
  ...                Event 4 (C-1)
                     Event 5 (C-3)
   
   ↓ (Buffer window: 100ms or 1000 events max)
   
   Batch[
     Event 1, Event 2, Event 3, ...
   ]
   
   ↓ (Route by event_type)
   
   [TRANSACTION_POSTED] → ledger.*
   [BALANCE_UPDATED]    → cache.*
   [FRAUD_DETECTED]     → ledger.fraud.*
```

### 3. Deduplication

```
Incoming Event:
  {
    "event_id": 1234567890123456789,  // Snowflake ID
    "event_type": "TRANSACTION_POSTED",
    ...
  }
  
Check: Is event_id in dedup cache?
  ├─ YES  → Skip (return cached result)
  └─ NO   → Process + store in cache (TTL: 5 min)

Dedup Store: Redis (fast, distributed)
  Key:   snowflake:{event_id}
  Value: { "status": "POSTED", "timestamp": 1717435200000 }
  TTL:   300 seconds
```

### 4. Event Enrichment

```
Raw Event (C Server):
{
  "event_id": 1234567890123456789,
  "event_type": "TRANSACTION_POSTED",
  "user_id": "user123",
  "data": { "trans_id": 42, "amount": 10000 }
}

Enriched (Saving-Gateway):
{
  "event_id": 1234567890123456789,
  "event_type": "TRANSACTION_POSTED",
  "user_id": "user123",
  "source_c_server": "c-server-1",      // Which C instance
  "gateway_received_at": 1717435201000,
  "gateway_routing_key": "ledger.transaction_posted",
  "data": {
    "trans_id": 42,
    "amount": 10000,
    "user_tier": "PREMIUM",              // From user cache
    "merchant_category": "5411"          // From merchant cache
  }
}
```

### 5. Routing & Publishing

```
Route decision (Saving-Gateway):
  
  if (event_type == "TRANSACTION_POSTED") {
    publish to RabbitMQ(routing_key="ledger.transaction_posted")
  } else if (event_type == "BALANCE_UPDATED") {
    update Redis(key="balance:{user_id}")
    update WebSocket clients
  } else if (event_type == "FRAUD_DETECTED") {
    increment alert counter
    if (threshold breached) {
      lock account (local Redis cache)
      publish to RabbitMQ(routing_key="ledger.fraud_detected")
    }
  }
```

### 6. Circuit Breaker

```
If Java Ledger is DOWN:
  
  C Server          Saving-Gateway              Java Ledger
      │                   │                           │
      ├─ Event ──────────>│                           │
      │                   ├─ Try publish to RabbitMQ  │
      │                   ├─ TIMEOUT (Java unreachable)
      │                   │
      │                   ├─ Circuit breaker OPEN
      │                   ├─ Store events in local queue
      │                   │
      │                   ├─ Health check: every 10s
      │                   ├─ Once Java recovers
      │                   │
      │                   ├─ Circuit breaker HALF_OPEN
      │                   ├─ Drain local queue to RabbitMQ
      │                   ├─ Circuit breaker CLOSED
      │                   │
      └─ Continue accepting events (buffered)

Metrics:
  - CLOSED (normal):      publish to RabbitMQ directly
  - OPEN (Java down):     buffer in local queue (max 100k)
  - HALF_OPEN (recovery): drain queue slowly
```

### 7. Rate Limiting

```
Per C-Server limits:
  ├─ 10,000 events/sec (burst limit)
  ├─ Leaky bucket algorithm (smooth traffic)
  └─ If exceeded: 429 Too Many Requests

Per Event Type:
  ├─ TRANSACTION_POSTED: unlimited
  ├─ BALANCE_UPDATED: 1000/sec
  ├─ FRAUD_DETECTED: 100/sec
  └─ Helps with cascading issues
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

## Transport Layer 1: C Server → Saving-Gateway

### HTTP Endpoint (Fire-and-Forget)

```
POST /api/events
Host: saving-gateway.internal
Content-Type: application/json
Authorization: Bearer {api_key}

Request (Saving-Gateway receives):
{
  "event_id": 1234567890123456789,
  "event_type": "TRANSACTION_POSTED",
  "timestamp": 1717435200000,
  "source": "wire-c-server-1",
  "request_id": "abc1234567890",
  "user_id": "user123",
  "data": { ... }
}

Response (immediate, < 10ms):
{
  "status": "accepted",
  "event_id": 1234567890123456789
}

Behavior:
  ├─ C Server does NOT wait for Java response
  ├─ Gateway acknowledges immediately
  ├─ Gateway handles routing/retry asynchronously
  └─ Decouples C from Java ledger
```

### C Server Implementation

```c
// Send event to Saving-Gateway (non-blocking)
void send_event_to_gateway(LedgerEvent *event) {
    CURL *curl = curl_easy_init();
    if (!curl) return;
    
    char json_body[4096];
    event_to_json(event, json_body, sizeof(json_body));
    
    // TLS + API key
    curl_easy_setopt(curl, CURLOPT_URL, 
                     "https://saving-gateway.internal/api/events");
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 5L);  // 5 second timeout
    
    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    char auth[128];
    snprintf(auth, sizeof(auth), "Authorization: Bearer %s", api_key);
    headers = curl_slist_append(headers, auth);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, json_body);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);  // Thread-safe
    
    // Send async (don't block)
    CURLcode res = curl_easy_perform(curl);
    
    if (res != CURLE_OK) {
        // Failed to reach gateway: queue for retry
        queue_event_for_retry(event, 1000);  // Retry in 1 second
    }
    
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
}
```

---

## Transport Layer 2: Saving-Gateway → Java Ledger (RabbitMQ)

### Setup

**RabbitMQ Configuration (Saving-Gateway publishes):**

```
Exchange:    gtel-events
Type:        Topic
Durable:     True
Auto-delete: False

Queues:
  ├─ java-ledger-events    (routing: ledger.*)
  ├─ ledger-events-retry   (routing: ledger.*, TTL: 5min)
  └─ ledger-events-dlq     (dead-letter queue)

Durability:  True (survive broker restart)
TTL:         24 hours (86400000 ms)
Max Length:  1,000,000 messages
```

### Publishing (Saving-Gateway → RabbitMQ)

```java
// Saving-Gateway: Publish enriched event to RabbitMQ
@Service
public class EventPublisher {
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    public void publishToLedger(LedgerEvent event) {
        // 1. Determine routing key
        String routingKey = determineRoutingKey(event.getEventType());
        
        // 2. Publish to RabbitMQ
        rabbitTemplate.convertAndSend("gtel-events", routingKey, event, message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setHeader("X-Event-ID", event.getEventId());
            message.getMessageProperties().setHeader("X-Event-Type", event.getEventType());
            message.getMessageProperties().setHeader("X-Timestamp", event.getTimestamp());
            return message;
        });
        
        log.info("event_published", 
            kv("event_id", event.getEventId()),
            kv("event_type", event.getEventType()),
            kv("routing_key", routingKey));
    }
    
    private String determineRoutingKey(String eventType) {
        return switch(eventType) {
            case "TRANSACTION_POSTED" -> "ledger.transaction_posted";
            case "PAYMENT_SETTLED" -> "ledger.payment_settled";
            case "FRAUD_DETECTED" -> "ledger.fraud_detected";
            case "BALANCE_UPDATED" -> "cache.balance_updated";
            default -> "ledger.unknown";
        };
    }
}
```

### Consuming (Java Ledger - from Saving-Gateway via RabbitMQ)

```java
// Java Ledger: Consume events published by Saving-Gateway
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

## Saving-Gateway Implementation (Java/Spring)

### Event Receiver Controller

```java
@RestController
@RequestMapping("/api/events")
public class EventController {
    
    @Autowired
    private EventService eventService;
    
    @Autowired
    private RateLimitService rateLimitService;
    
    @PostMapping
    public ResponseEntity<?> receiveEvent(
            @RequestBody LedgerEvent event,
            @RequestHeader("Authorization") String token) {
        
        // 1. Authenticate API key
        String clientId = validateApiKey(token);
        if (clientId == null) {
            return ResponseEntity.status(401).body("Invalid API key");
        }
        
        // 2. Rate limit check
        if (!rateLimitService.allowRequest(clientId, 10000)) {
            return ResponseEntity.status(429).body("Rate limit exceeded");
        }
        
        // 3. Validate event structure
        if (!validateEvent(event)) {
            return ResponseEntity.status(400).body("Invalid event format");
        }
        
        // 4. Set source
        event.setSourceCServer(clientId);
        event.setGatewayReceivedAt(System.currentTimeMillis());
        
        // 5. Enqueue for async processing
        eventService.enqueueEvent(event);
        
        // 6. Immediate response (fire-and-forget)
        return ResponseEntity.ok(Map.of(
            "status", "accepted",
            "event_id", event.getEventId()
        ));
    }
}
```

### Event Service (Aggregation + Routing)

```java
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
    private final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(4);
    
    @PostConstruct
    public void init() {
        // Start batch processor (every 100ms or 1000 events)
        scheduler.scheduleAtFixedRate(
            this::processBatch, 
            100,  // initial delay
            100,  // period (100ms)
            TimeUnit.MILLISECONDS
        );
    }
    
    public void enqueueEvent(LedgerEvent event) {
        eventQueue.offer(event);
    }
    
    private void processBatch() {
        List<LedgerEvent> batch = new ArrayList<>();
        
        // Collect up to 1000 events or wait 100ms
        while (!eventQueue.isEmpty() && batch.size() < 1000) {
            LedgerEvent event = eventQueue.poll();
            if (event != null) batch.add(event);
        }
        
        if (batch.isEmpty()) return;
        
        // Process batch
        for (LedgerEvent event : batch) {
            try {
                // 1. Deduplication check
                if (deduplicator.isDuplicate(event.getEventId())) {
                    log.debug("Duplicate event (skipped): {}", 
                        event.getEventId());
                    continue;
                }
                
                // 2. Enrichment (add user_tier, merchant_category, etc.)
                enricher.enrich(event);
                
                // 3. Routing & Publishing
                routeEvent(event);
                
                // 4. Record in dedup cache
                deduplicator.recordProcessed(event.getEventId(), 
                    event.getEventType());
                
            } catch (Exception e) {
                log.error("Error processing event: {}", 
                    event.getEventId(), e);
                // Failed events are NOT retried at gateway level
                // They're queued in ledger-events-retry by RabbitMQ
            }
        }
    }
    
    private void routeEvent(LedgerEvent event) {
        try {
            switch (event.getEventType()) {
                case "TRANSACTION_POSTED", "PAYMENT_SETTLED", "FRAUD_DETECTED":
                    // Route to Java Ledger via RabbitMQ
                    if (circuitBreaker.isOpen()) {
                        // Java Ledger is down: buffer locally
                        enqueueForLocalBuffer(event);
                    } else {
                        // Normal path: publish to RabbitMQ
                        publisher.publishToLedger(event);
                    }
                    break;
                    
                case "BALANCE_UPDATED":
                    // Update Redis cache, don't queue to ledger
                    cacheService.updateBalance(
                        event.getData().get("account_code"),
                        event.getData().get("balance_minor")
                    );
                    break;
                    
                case "SESSION_EXPIRED":
                    // Cleanup: remove from session cache
                    sessionCache.remove(event.getUserId());
                    break;
                    
                case "FRAUD_DETECTED":
                    // Increment alert counter
                    fraudService.recordAlert(event.getUserId());
                    // Also publish to ledger
                    publisher.publishToLedger(event);
                    break;
            }
        } catch (Exception e) {
            log.error("Error routing event: {}", event.getEventId(), e);
            throw e;
        }
    }
}
```

### Circuit Breaker (Java Ledger Health)

```java
@Service
public class CircuitBreakerService {
    
    private enum State { CLOSED, OPEN, HALF_OPEN }
    
    private volatile State state = State.CLOSED;
    private volatile long openedAt = 0;
    private final long TIMEOUT = 30_000;  // 30 seconds
    
    @Autowired
    private RabbitTemplate rabbitTemplate;
    
    @Scheduled(fixedDelay = 5000)  // Check every 5 seconds
    public void healthCheck() {
        try {
            // Test RabbitMQ connectivity (broker is up)
            rabbitTemplate.convertAndSend(
                "gtel-events", 
                "healthcheck.ping", 
                "{\"ping\": true}"
            );
            
            if (state == State.OPEN || state == State.HALF_OPEN) {
                log.info("Circuit breaker transitioning to CLOSED");
                state = State.CLOSED;
                drainLocalBuffer();
            }
        } catch (Exception e) {
            if (state == State.CLOSED) {
                log.error("Circuit breaker OPEN: {}", e.getMessage());
                state = State.OPEN;
                openedAt = System.currentTimeMillis();
            } else if (state == State.OPEN) {
                long elapsed = System.currentTimeMillis() - openedAt;
                if (elapsed > TIMEOUT) {
                    state = State.HALF_OPEN;
                    log.info("Circuit breaker transitioning to HALF_OPEN");
                }
            }
        }
    }
    
    public boolean isOpen() {
        return state != State.CLOSED;
    }
    
    private void drainLocalBuffer() {
        // Replay buffered events to RabbitMQ
        List<LedgerEvent> buffered = localBuffer.drainToList();
        log.info("Draining {} buffered events", buffered.size());
        for (LedgerEvent event : buffered) {
            publisher.publishToLedger(event);
        }
    }
}
```

### Event Deduplicator (Redis)

```java
@Service
public class EventDeduplicator {
    
    @Autowired
    private RedisTemplate<String, String> redis;
    
    private static final String DEDUP_KEY_PREFIX = "dedup:";
    private static final long DEDUP_TTL = 300;  // 5 minutes
    
    public boolean isDuplicate(long eventId) {
        String key = DEDUP_KEY_PREFIX + eventId;
        return redis.hasKey(key);
    }
    
    public void recordProcessed(long eventId, String eventType) {
        String key = DEDUP_KEY_PREFIX + eventId;
        redis.opsForValue().set(
            key,
            eventType,
            Duration.ofSeconds(DEDUP_TTL)
        );
    }
}
```

### Event Enricher

```java
@Service
public class EventEnricher {
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private MerchantService merchantService;
    
    public void enrich(LedgerEvent event) {
        Map<String, Object> data = event.getData();
        
        // Add user tier
        if (event.getUserId() != null) {
            User user = userService.getUser(event.getUserId());
            data.put("user_tier", user.getTier());
        }
        
        // Add merchant category
        if (data.containsKey("merchant_id")) {
            Merchant merchant = merchantService
                .getMerchant((String) data.get("merchant_id"));
            data.put("merchant_category", merchant.getMcc());
        }
    }
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
# C Server → Saving-Gateway
gtel_gateway_events_received_total{client="c-server-1"} 10240
gtel_gateway_request_duration_seconds{percentile="p95"} 0.008

# Saving-Gateway Processing
gtel_gateway_dedup_hits_total{event_type="TRANSACTION_POSTED"} 5
gtel_gateway_circuit_breaker_state{state="CLOSED"} 1
gtel_gateway_local_buffer_size 0

# Saving-Gateway → RabbitMQ Publishing
gtel_gateway_published_total{event_type="TRANSACTION_POSTED"} 10240
gtel_gateway_publish_errors_total{reason="circuit_open"} 0

# RabbitMQ Queue Depth
rabbitmq_queue_messages_ready{queue="java-ledger-events"} 1200
rabbitmq_queue_messages_ready{queue="ledger-events-dlq"} 0

# Java Ledger Consumption
gtel_ledger_events_received_total{event_type="TRANSACTION_POSTED"} 10235
gtel_ledger_events_processed_seconds{event_type="TRANSACTION_POSTED"} 0.045
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

## Component Responsibilities Summary

```
┌──────────────────────┐
│   C Server (:7474)   │
│   ─────────────────  │
│ • Wire protocol      │
│ • Generate events    │
│ • Call Java REST     │
│ • Snowflake ID gen   │
│ • Retry locally      │
└──────────┬───────────┘
           │ HTTP POST
           │ /api/events
           ↓
┌──────────────────────────────────────┐
│ Saving-Gateway (Orchestration)       │
│ ──────────────────────────────────   │
│ • Receive events (HTTP)              │
│ • Authenticate + rate limit          │
│ • Dedup check (Redis)                │
│ • Enrich (user tier, merchant cat)   │
│ • Route by event type                │
│ • Circuit breaker (Java health)      │
│ • Batch + aggregate                  │
│ • Publish to RabbitMQ                │
│ • Buffer locally if Java down        │
└──────────┬───────────────────────────┘
           │ RabbitMQ
           │ gtel-events
           │ Topic Exchange
           ↓
┌──────────────────────────────────────┐
│ Message Queue (RabbitMQ)             │
│ ──────────────────────────────────   │
│ • java-ledger-events (persistent)    │
│ • ledger-events-retry (5min TTL)     │
│ • ledger-events-dlq (manual review)  │
└──────────┬───────────────────────────┘
           │ @RabbitListener
           ↓
┌──────────────────────┐
│ Java Ledger (:8090)  │
│ ─────────────────── │
│ • Dedup check       │
│ • Double-entry val  │
│ • Process event     │
│ • Disruptor queue   │
│ • Update DB         │
│ • Audit logging     │
└──────────────────────┘
```

---

## Deployment Timeline

| Phase | Action | Timeline | Owner |
|-------|--------|----------|-------|
| Design | Approval of spec | Today (6/3) | Architecture |
| Dev | Saving-Gateway (events, dedup, routing) | Week 1 | Backend |
| Dev | C server HTTP client (event publishing) | Week 1 | C Team |
| Dev | Java event consumer (@RabbitListener) | Week 1 | Java Team |
| Test | Integration tests (C→Gateway→Java) | Week 2 | QA |
| Test | Load test (1000 events/sec) | Week 2 | Perf |
| Staging | 48-hour validation (circuit breaker, DLQ) | Week 2 | DevOps |
| Prod | Blue-green deployment (gate on metrics) | June 10 | DevOps |

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

