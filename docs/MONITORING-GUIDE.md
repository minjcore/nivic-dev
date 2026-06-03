# Grafana Monitoring Guide

Complete monitoring stack for COA Ledger, Saving-Gateway, and Infrastructure with Prometheus, Grafana, and AlertManager.

## Quick Start

### 1. Start Monitoring Stack

```bash
cd /path/to/nivic-dev
docker-compose -f docker-compose.monitoring.yml up -d
```

Verify all services are running:
```bash
docker-compose -f docker-compose.monitoring.yml ps
```

### 2. Access Services

- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **AlertManager**: http://localhost:9093

### 3. Configure AlertManager (Optional)

Edit `monitoring/alertmanager.yml` to set Slack webhook:

```bash
export SLACK_WEBHOOK_URL="https://hooks.slack.com/services/YOUR/WEBHOOK/URL"
```

Then restart AlertManager:
```bash
docker-compose -f docker-compose.monitoring.yml restart alertmanager
```

## Dashboards

Four pre-built dashboards auto-load into Grafana:

### 1. COA Ledger - Core Performance Monitoring

**Key Metrics:**
- **TPS**: Received, Processed, Failed transactions per second
- **Latency**: P50, P95, P99, P99.9 transaction processing time
- **Disruptor**: Ring buffer utilization, available slots
- **Double-Entry**: Validation success/failure count
- **Error Rate**: By event type and error code
- **Database**: Connection pool, query performance
- **JVM**: Heap usage, GC pause time, GC frequency

**When to React:**
- TPS > 25k: Check for bottlenecks in Disruptor pipeline
- P99 Latency > 1s: High processing time, possible DB lock contention
- Ring Buffer < 100 slots: Events backing up, slow consumer
- Double-Entry Failures > 0: Data integrity issue, investigate immediately
- Error Rate > 5%: High failure rate, check error codes

**Interpretation:**
- Received vs Processed TPS should track closely; gap indicates queue buildup
- Ring buffer should stay < 70% utilization under normal load
- Double-entry validation must never fail (invariant violation)

### 2. Saving-Gateway - Event Orchestration

**Key Metrics:**
- **Events**: Received, Processed, Failed TPS
- **Deduplication**: Cache hit rate, duplicates detected
- **Batch Processing**: Batch size, batch count/sec
- **Circuit Breaker**: Current state (CLOSED/OPEN/HALF_OPEN)
- **Local Buffer**: Buffered events, max capacity
- **Latency**: Event processing time percentiles
- **RabbitMQ Publishing**: Publish latency
- **Rate Limiter**: Rejected request count

**When to React:**
- Circuit Breaker → OPEN: Java Ledger down, gateway buffering locally
- Local Buffer > 50k: High backlog, Java Ledger slow or offline
- Dedup Hit Rate < 95%: Consider longer Redis TTL
- Processing Latency P99 > 100ms: Enrichment or publishing slow
- Rate Limit Exceeded > 1 req/sec: C Server sending too fast

**Interpretation:**
- Circuit breaker oscillating (CLOSED ↔ OPEN frequently): Transient connectivity issue
- Local buffer draining over time: Java Ledger recovering
- Dedup cache hits prevent duplicate transaction posting to ledger

### 3. Disruptor Performance - Ring Buffer Analysis

**Key Metrics:**
- **Ring Buffer**: Utilization %, available slots
- **Throughput**: Published, Processed, Failed TPS
- **Handler Latency**: P50, P95, P99, P99.9 (microseconds)
- **Wait Strategy**: Busy spins, yields, parks per second
- **GC Impact**: P99 pause time, GC events/sec, free memory post-GC
- **Handler Distribution**: Event type breakdown
- **Batch Size**: Average and max batch size
- **E2E Latency**: End-to-end pipeline latency

**When to React:**
- Buffer Utilization > 80%: Ring is filling, add more consumers or reduce producer throughput
- Handler Latency P99 > 1ms: Slow event processing, check business logic
- GC Pause > 500ms: GC pressure, increase heap size or tune GC
- Busy Spins > 10k/sec: High CPU usage, consider different wait strategy
- Ring Full Errors > 0: Buffer overflow, events dropped

**Interpretation:**
- High busy spins = low-latency tuning (consumes CPU)
- Yielding = balanced wait strategy (respects CPU sharing)
- Parking = power-efficient wait (high latency)
- Wrap-arounds show how many times the ring cycled; frequent = high throughput

### 4. Infrastructure - Database & Messaging

**Key Metrics:**
- **PostgreSQL**: Connections, query performance, cache hit ratio, I/O operations
- **RabbitMQ**: Queue depth, consumer count, publisher throughput, memory usage
- **Redis**: Memory, keys, commands/sec, hit/miss ratio
- **Disk**: Available space, usage percentage
- **System**: Load average, CPU usage

**When to React:**
- PG Connections > 90% of max: Pool exhaustion imminent
- PG Query Time P95 > 500ms: Slow queries, check slow query log
- RabbitMQ Queue Messages Ready > 10k: Consumer lag, increase processing capacity
- Redis Memory > 90% of limit: Eviction starting, increase memory
- Disk Available < 10%: Disk space running low, archival needed

**Interpretation:**
- PG cache hit ratio > 99% = good indexing
- RabbitMQ consumers = 0 means no listeners (ledger down?)
- Redis hit ratio > 95% = effective caching (dedup working)

## Alert Rules

Alerts are defined in `monitoring/alert-rules.yml` and automatically loaded by Prometheus.

### Critical Alerts (Page Oncall)

1. **HighErrorRate**: Error rate > 5% for 5 minutes
2. **DisruptorRingBufferFull**: < 100 available slots for 2 minutes
3. **CircuitBreakerOpen**: Saving-Gateway circuit breaker OPEN for 1 minute
4. **DoubleEntryValidationFailure**: Any double-entry validation failure
5. **RabbitMQConsumerDown**: No consumers for 2 minutes

### Warning Alerts (Log + Team Notification)

1. **HighTransactionLatency**: P99 > 1 second for 5 minutes
2. **HighQueueDepth**: Local buffer > 50k for 5 minutes
3. **RabbitMQQueueBacklog**: Queue ready > 10k for 5 minutes
4. **DatabasePoolExhaustion**: Active > 90% of max for 5 minutes
5. **HighDedupCacheMissRate**: > 10% misses for 5 minutes
6. **HighGCPauseTime**: P99 GC pause > 500ms for 5 minutes
7. **LowAvailableMemory**: JVM memory < 10% for 5 minutes
8. **GatewayQueueBacklog**: Event queue > 1000 for 5 minutes
9. **HighRateLimitExceeded**: Rate limit rejects > 1 req/sec for 5 minutes

### Alert Routing

Default route sends to Slack channel `#gtel-alerts` (requires webhook configuration in alertmanager.yml).

To customize routing (add email, PagerDuty, etc.), edit `monitoring/alertmanager.yml` and restart:
```bash
docker-compose -f docker-compose.monitoring.yml restart alertmanager
```

## Monitoring Checklist

### Daily
- [ ] Check Grafana dashboard for any warnings
- [ ] Verify no critical alerts firing
- [ ] Monitor TPS trend (should be stable)
- [ ] Check circuit breaker state (should be CLOSED)

### Weekly
- [ ] Review error rate trends (should be < 0.1%)
- [ ] Check database query performance (cache hit ratio > 99%)
- [ ] Monitor GC frequency (< 5 GC events/sec)
- [ ] Verify dedup cache effectiveness (hit rate > 95%)

### Monthly
- [ ] Capacity planning: analyze TPS growth trend
- [ ] Review slow query logs in PostgreSQL
- [ ] Check for any disk space issues
- [ ] Analyze long-tail latency (P99.9 trends)

## Troubleshooting

### Grafana Won't Load Dashboards
```bash
# Check Grafana logs
docker-compose -f docker-compose.monitoring.yml logs grafana
# Verify provisioning directory exists
ls -la monitoring/grafana/provisioning/
```

### Prometheus Not Scraping
```bash
# Check Prometheus targets
curl http://localhost:9090/api/v1/targets
# Look for errors in target state
```

### AlertManager Not Sending Alerts
```bash
# Verify AlertManager is running
docker-compose -f docker-compose.monitoring.yml logs alertmanager
# Check alertmanager.yml syntax
docker run --rm -v $(pwd)/monitoring/alertmanager.yml:/alertmanager.yml prom/alertmanager:latest amtool config routes
```

### High Memory Usage in Redis
```bash
# Check Redis key eviction policy
redis-cli INFO stats
# Check memory usage by keyspace
redis-cli --scan --pattern "gtel*" | wc -l
```

## Performance Tuning

### Optimize Prometheus Retention
Default: 15 days of data. To increase:

```bash
# Edit docker-compose.monitoring.yml, Prometheus command:
- '--storage.tsdb.retention.time=30d'
```

### Reduce Grafana Refresh Rate
Default: 10 seconds. To reduce load, increase to:
- Production: 30 seconds
- Staging: 60 seconds

### Scale Disruptor Ring Buffer
If buffer utilization > 80% frequently:

```java
// In JdbcFundFlowLedger.java, increase ring size:
// From: RingBuffer.createSingleProducer(..., 16384);
// To:   RingBuffer.createSingleProducer(..., 32768);
```

## Integration with Other Tools

### PagerDuty Integration
Edit `monitoring/alertmanager.yml`:
```yaml
receivers:
  - name: 'default'
    pagerduty_configs:
      - service_key: '${PAGERDUTY_SERVICE_KEY}'
        description: 'Alert: {{ .GroupLabels.alertname }}'
```

### Email Alerts
```yaml
receivers:
  - name: 'default'
    email_configs:
      - to: 'oncall@company.com'
        from: 'alertmanager@company.com'
        smarthost: 'smtp.gmail.com:587'
        auth_username: 'alertmanager@company.com'
        auth_password: '${ALERT_PASSWORD}'
```

## Dashboard Customization

To add new panels to existing dashboards:

1. Open Grafana UI
2. Navigate to dashboard
3. Click "Edit" button
4. Add panel, configure metric, save dashboard
5. Export dashboard JSON (Dashboard settings → JSON model)
6. Update file in `monitoring/grafana/dashboards/`
7. Restart Grafana

For programmatic updates, use Grafana API:
```bash
# Get dashboard
curl -H "Authorization: Bearer ${GRAFANA_API_TOKEN}" \
  http://localhost:3000/api/dashboards/db/coa-ledger-dashboard

# Update dashboard (POST with updated JSON)
curl -X POST -H "Authorization: Bearer ${GRAFANA_API_TOKEN}" \
  -H "Content-Type: application/json" \
  -d @updated-dashboard.json \
  http://localhost:3000/api/dashboards/db
```

## Metrics Reference

### COA Ledger Metrics (exposed on :8090/actuator/prometheus)

```
gtel_ledger_events_received_total        # Total events received
gtel_ledger_events_processed_total       # Total events processed
gtel_ledger_events_failed_total          # Total events failed
gtel_ledger_events_processed_seconds     # Processing latency histogram
gtel_ledger_double_entry_validated_total # Double-entry validations
gtel_ledger_double_entry_failed_total    # Double-entry failures
gtel_disruptor_ring_buffer_available     # Available ring buffer slots
gtel_disruptor_ring_buffer_size          # Total ring buffer size
gtel_disruptor_handler_latency_seconds   # Handler processing latency
```

### Saving-Gateway Metrics (exposed on :8091/actuator/prometheus)

```
gtel_gateway_events_received_total       # Total events received
gtel_gateway_events_processed_total      # Total events processed
gtel_gateway_dedup_hits_total            # Dedup cache hits
gtel_gateway_local_buffer_size           # Buffered events count
gtel_gateway_circuit_breaker_state       # Circuit breaker state (0-3)
gtel_gateway_batch_size_avg              # Average batch size
gtel_gateway_rate_limit_exceeded_total   # Rate limit rejects
```

### JVM Metrics (all Spring Boot apps)

```
jvm_memory_used_bytes                    # Heap memory used
jvm_memory_max_bytes                     # Heap memory max
jvm_gc_pause_seconds                     # GC pause time
jvm_threads_live                         # Active threads
```

### RabbitMQ Metrics (on :15672/metrics)

```
rabbitmq_queue_messages_ready            # Ready messages
rabbitmq_queue_consumers                 # Consumer count
rabbitmq_connections                     # Connection count
```

## See Also

- [COA Ledger Architecture](architecture/wire-payment-multitenant.md)
- [Saving-Gateway Design](docs/INTEGRATION-TEST-GUIDE.md)
- [Prometheus Query Syntax](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana Dashboard Guide](https://grafana.com/docs/grafana/latest/dashboards/)
