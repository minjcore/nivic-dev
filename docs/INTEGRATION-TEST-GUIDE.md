# Integration Test Guide: C → Saving-Gateway → Java Ledger

**Status:** ✅ READY FOR TESTING  
**Date:** 2026-06-03  
**Pipeline:** C Server → Saving-Gateway (8091) → RabbitMQ → Java Ledger (8090)

---

## Quick Start (5 minutes)

```bash
# 1. Make script executable
chmod +x scripts/run-integration-test.sh

# 2. Run full integration test (sets up infra + runs tests)
./scripts/run-integration-test.sh

# Expected output:
#  ✅ All integration tests passed!
#  ✅ Gateway health check
#  ✅ Event acceptance
#  ✅ Duplicate detection
#  ✅ Authentication
```

---

## Option 1: Docker Compose (Complete Stack)

Fastest way to test the entire pipeline locally.

```bash
# Start all services (Postgres, RabbitMQ, Redis, Gateway, Ledger)
docker-compose -f docker-compose.integration-test.yml up -d

# Wait for services to be healthy
sleep 10

# Check logs
docker-compose -f docker-compose.integration-test.yml logs -f saving-gateway

# Stop all services
docker-compose -f docker-compose.integration-test.yml down
```

### Services started:
```
✅ PostgreSQL (:5432)       - Database for Java Ledger
✅ RabbitMQ (:5672)         - Message broker (ledger.*)
✅ Redis (:6379)            - Cache + dedup store
✅ Saving-Gateway (:8091)   - Event orchestration
✅ Java Ledger (:8090)      - COA accounting ledger
```

---

## Option 2: Manual Start (Full Control)

For development or debugging specific components.

### 1. Start Infrastructure

```bash
# Terminal 1: Start RabbitMQ
docker run -d \
  --name gtel-rabbitmq \
  -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=gtel-c-server \
  -e RABBITMQ_DEFAULT_PASS=password \
  rabbitmq:3.12-management-alpine

# Terminal 2: Start Redis
docker run -d \
  --name gtel-redis \
  -p 6379:6379 \
  redis:7-alpine

# Terminal 3: Start PostgreSQL
docker run -d \
  --name gtel-postgres \
  -p 5432:5432 \
  -e POSTGRES_DB=gtelpay_prod \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  postgres:15-alpine
```

### 2. Start Saving-Gateway

```bash
cd java

# Build and run
mvn clean spring-boot:run \
  -Dspring-boot.run.arguments="--spring.profiles.active=gateway" \
  -Dspring.rabbitmq.host=localhost \
  -Dspring.rabbitmq.port=5672 \
  -Dspring.redis.host=localhost \
  -Dgateway.api-keys="c-server-1=secret1,test-server=test-key"
```

Expected output:
```
2026-06-03 10:30:00 INFO SavingGatewayApplication - Started in 2.5s
2026-06-03 10:30:01 INFO EventService - Gateway metrics: queue_size=0, circuit_breaker=CLOSED
```

### 3. Start Java Ledger (in another terminal)

```bash
cd java

mvn clean spring-boot:run \
  -Dspring-boot.run.arguments="--spring.profiles.active=ledger" \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/gtelpay_prod \
  -Dspring.rabbitmq.host=localhost \
  -Dspring.redis.host=localhost
```

Expected output:
```
2026-06-03 10:30:30 INFO ControlPlaneServer - Control Plane listening on :8095
2026-06-03 10:30:31 INFO EventListener - Ready to consume from ledger events
```

---

## Option 3: JUnit Integration Tests

Run E2E tests with Testcontainers (auto-manages Docker containers).

```bash
cd java

# Run all integration tests
mvn test -Dtest=E2EIntegrationTest

# Run specific test
mvn test -Dtest=E2EIntegrationTest#testTransactionPostedFlow

# Run with verbose output
mvn test -Dtest=E2EIntegrationTest -X
```

### Test Cases

```
✅ testTransactionPostedFlow
   - C Server sends TRANSACTION_POSTED
   - Saving-Gateway accepts + publishes to RabbitMQ
   - Verify gateway processed

✅ testDuplicateDetection
   - Send same event_id twice
   - Second request dedup'd (Redis cache)

✅ testRateLimiting
   - Send 100 events rapidly
   - Some may be 429 (rate-limited)

✅ testAuthenticationFailure
   - Invalid API key → 401 Unauthorized

✅ testMultipleEventTypes
   - Test: TRANSACTION_POSTED, PAYMENT_SETTLED, FRAUD_DETECTED

✅ testBatchProcessing
   - Send 100 events
   - Verify gateway batches them (100ms window)

✅ testCircuitBreaker
   - Verify initial state: CLOSED
   - Test transitions: CLOSED → OPEN → HALF_OPEN → CLOSED

✅ testLoadTest
   - Send 1000 events
   - Measure throughput (target: > 100 TPS)
```

---

## Manual Testing (curl)

Test the pipeline without running full Docker stack.

### 1. Health Check

```bash
curl -s http://localhost:8091/api/events/health | jq .

# Expected response:
{
  "status": "UP",
  "timestamp": 1717435200000,
  "queue_size": 0,
  "circuit_breaker_state": "CLOSED"
}
```

### 2. Send Event (C Server simulation)

```bash
curl -X POST http://localhost:8091/api/events \
  -H "Authorization: Bearer secret1" \
  -H "Content-Type: application/json" \
  -d '{
    "event_id": 1234567890123456789,
    "event_type": "TRANSACTION_POSTED",
    "timestamp": '$(date +%s000)',
    "source": "test-c-server",
    "request_id": "test-req-1",
    "user_id": "user-123",
    "data": {
      "trans_id": 42,
      "ref_id": "test-ref-1",
      "amount": 10000,
      "currency": "VND"
    }
  }' | jq .

# Expected response:
{
  "status": "accepted",
  "event_id": 1234567890123456789
}
```

### 3. Test Invalid Authentication

```bash
curl -X POST http://localhost:8091/api/events \
  -H "Authorization: Bearer invalid-key" \
  -H "Content-Type: application/json" \
  -d '{"event_id": 999, "event_type": "TRANSACTION_POSTED"}' | jq .

# Expected response:
{
  "error": "Invalid API key"
}

# HTTP Status: 401
```

### 4. Monitor RabbitMQ

```bash
# View published events in queue
rabbitmqctl list_queues name messages consumers

# Expected:
java-ledger-events         5 1
ledger-events-retry        0 0
ledger-events-dlq          0 0
```

### 5. Monitor Redis

```bash
# View dedup cache entries
redis-cli keys "dedup:*"

# Get dedup entry for event
redis-cli get "dedup:1234567890123456789"

# Expected output:
TRANSACTION_POSTED
```

---

## Test Scenarios

### Scenario 1: Happy Path (Normal Operation)

```
Step 1: C Server sends event
Step 2: Saving-Gateway receives + validates
Step 3: Gateway checks dedup cache (Redis)
Step 4: Gateway enriches event (user_tier, merchant_cat)
Step 5: Gateway publishes to RabbitMQ (ledger.transaction_posted)
Step 6: Java Ledger consumes from RabbitMQ
Step 7: Ledger validates double-entry invariant
Step 8: Ledger persists to database
Step 9: Ledger records in dedup store (idempotency)

Verification:
  ✅ Event appears in java-ledger-events queue
  ✅ Event appears in coa_trans table
  ✅ Dedup cache has entry
  ✅ Gateway health: CLOSED
```

### Scenario 2: Duplicate Event (Idempotency)

```
Step 1: C Server sends event A (event_id=123)
Step 2: Gateway receives + publishes to RabbitMQ
Step 3: C Server sends event A again (same event_id=123)
Step 4: Gateway checks dedup cache → FOUND
Step 5: Gateway skips processing (no duplicate publish)

Verification:
  ✅ Event only published once
  ✅ Queue has 1 message (not 2)
  ✅ Database has 1 trans (not 2)
```

### Scenario 3: Circuit Breaker (Java Ledger Down)

```
Step 1: C Server sends events
Step 2: Gateway publishes to RabbitMQ
Step 3: Java Ledger is DOWN (no consumer)
Step 4: Health check timeout → Circuit OPEN
Step 5: Subsequent events buffer locally (not published)
Step 6: Gateway shows: circuit_breaker_state=OPEN
Step 7: Java Ledger comes back UP
Step 8: Health check succeeds → Circuit CLOSED
Step 9: Gateway drains buffer to RabbitMQ

Verification:
  ✅ Events buffered locally while circuit open
  ✅ Buffer drains when ledger recovers
  ✅ All events eventually processed
```

### Scenario 4: Rate Limiting

```
Step 1: C Server sends > 10k events/sec from one client
Step 2: First 10k events accepted (200 OK)
Step 3: Subsequent events rejected (429 Too Many Requests)
Step 4: Client backed off
Step 5: Subsequent requests accepted

Verification:
  ✅ Rate limiter working correctly
  ✅ No event loss (retry works)
```

---

## Monitoring During Tests

### Gateway Logs

```bash
# Watch gateway logs
tail -f ~/logs/gateway/saving-gateway.log | grep -E "event_received|batch_processed|circuit_breaker"

# Expected patterns:
event_received: event_id=123, event_type=TRANSACTION_POSTED, client=c-server-1
batch_processed: batch_size=1000, elapsed_ms=45
gateway_metrics: queue_size=0, circuit_breaker=CLOSED
```

### RabbitMQ Management UI

```
URL: http://localhost:15672
User: gtel-c-server
Pass: password

Navigate to:
  - Exchanges → gtel-events (check publish rate)
  - Queues → java-ledger-events (check depth + messages)
  - Connections (check C server + gateway connections)
```

### Redis CLI

```bash
# Monitor dedup cache growth
redis-cli MONITOR | grep "dedup:"

# Check cache size
redis-cli DBSIZE

# Get cache hits
redis-cli get dedup:1234567890
```

### Java Ledger Console

```bash
# Watch event consumption
tail -f ~/logs/gateway/java-ledger.log | grep -E "event_consumed|trans_posted|double_entry"

# Expected patterns:
event_consumed: event_id=123, status=SUCCESS
trans_posted: trans_id=42, amount=10000
double_entry_validated: debits=10000, credits=10000
```

---

## Troubleshooting

### Issue: Gateway returns 401 (Invalid API key)

**Solution:**
```bash
# Check API keys in application-gateway.yml
cat application-gateway.yml | grep gateway.api-keys

# Or set environment variable
export GATEWAY_API_KEYS="c-server-1=secret1"

# Then retry with correct key
curl -H "Authorization: Bearer secret1" ...
```

### Issue: Events stuck in queue (not consumed)

**Check:**
```bash
# 1. Is Java Ledger running?
curl http://localhost:8090/api/health

# 2. Is RabbitMQ up?
curl http://localhost:15672 -u guest:guest

# 3. Check if consumer connected
rabbitmqctl list_consumers

# 4. Check Java Ledger logs
tail -f ~/logs/gateway/java-ledger.log
```

### Issue: Dedup not working (duplicates being processed)

**Check:**
```bash
# 1. Is Redis running?
redis-cli ping

# 2. Check dedup cache
redis-cli get dedup:1234567890

# 3. Check if dedup service is enabled
curl http://localhost:8091/api/events/health | jq .circuit_breaker_state
```

### Issue: Circuit breaker stuck in OPEN

**Solution:**
```bash
# 1. Verify Java Ledger is running
curl http://localhost:8090/api/health

# 2. Check RabbitMQ connectivity
docker logs gtel-rabbitmq | tail -20

# 3. Force circuit reset (restart gateway)
docker-compose restart saving-gateway
```

---

## Performance Expectations

### Latency

```
Event Latency Breakdown:
  C Server → Gateway:        5-10ms (HTTP)
  Gateway processing:       10-20ms (dedup + enrich + publish)
  → RabbitMQ:                2-5ms
  Ledger consuming:         20-50ms (validate + persist)
  ────────────────────────────────
  Total E2E:               50-100ms (p95)
```

### Throughput

```
Per-Client Limit:          10,000 events/sec
Batch Size:                1,000 events
Batch Window:              100 milliseconds
Expected Gateway TPS:      22,000+ (with batching)
```

### Resource Usage

```
Saving-Gateway:
  Memory:     ~200 MB
  CPU:        ~10% (idle), 30-50% (10k TPS)
  Disk I/O:   minimal (mostly memory operations)

Java Ledger:
  Memory:     ~400 MB
  CPU:        ~20% (idle), 60-80% (10k TPS)
  Disk I/O:   ~100 IOPS (PostgreSQL)
```

---

## Cleanup

```bash
# Stop all containers
docker-compose -f docker-compose.integration-test.yml down

# Remove volumes (full reset)
docker-compose -f docker-compose.integration-test.yml down -v

# Or manually
docker stop gtel-rabbitmq gtel-redis gtel-postgres
docker rm gtel-rabbitmq gtel-redis gtel-postgres
```

---

## Next Steps

After successful integration test:

1. **Deploy to Staging** (June 5)
   - Run 48-hour validation
   - Monitor metrics
   - Test failover scenarios

2. **Production Rollout** (June 10)
   - Blue-green deployment
   - Gradual traffic shift (10% → 50% → 100%)
   - Monitor circuit breaker + buffer

3. **Post-Deployment** (June 11+)
   - Verify 22k TPS achieved
   - Check P99 latency < 100ms
   - Validate event dedup rate
   - Archive gateway metrics

