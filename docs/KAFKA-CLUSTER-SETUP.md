# Apache Kafka Cluster Setup for Event Streaming

Production-grade Kafka cluster with 3 brokers, Zookeeper coordination, and Confluent Control Center for managing high-volume event streaming in the GtelPay ledger system.

## Architecture

```
┌─────────────────────────────────────────┐
│      Producers                Consumers │
│  (Saving-Gateway, C-Server)  (Ledger)   │
└────────────┬──────────────────┬─────────┘
             │                  │
    ┌────────┴──────────────────┴────────┐
    │                                    │
┌──────────┐  ┌──────────┐  ┌──────────┐
│ Broker 1 │  │ Broker 2 │  │ Broker 3 │
│ (port 9) │  │(port 10) │  │(port 11) │
└────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │
     └─────────────┴─────────────┘
            (Replication)
     
┌──────────────────────────────────────┐
│         Zookeeper Cluster            │
│  (Coordination & Leader Election)    │
└──────────────────────────────────────┘

┌──────────────────────────────────────┐
│      Confluent Control Center        │
│     (Management & Monitoring)        │
│         http://localhost:9021        │
└──────────────────────────────────────┘
```

## Quick Start

### 1. Start the Cluster

```bash
chmod +x infra/kafka-cluster/setup-cluster.sh
./infra/kafka-cluster/setup-cluster.sh
```

This will:
- Start 3 Kafka brokers
- Initialize Zookeeper
- Launch Control Center
- Create default topics for GtelPay

### 2. Verify Cluster Status

```bash
# List brokers
docker exec kafka-broker-1 kafka-broker-api-versions.sh --bootstrap-server localhost:29092

# List all topics
docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 --list

# Describe cluster
docker exec kafka-broker-1 kafka-metadata.sh --bootstrap-server localhost:29092 --describe
```

### 3. Access Control Center

Open browser: **http://localhost:9021**

Features:
- Cluster overview
- Topic management
- Consumer group monitoring
- Message inspection
- Performance metrics

## Topics

### 1. `gtel-ledger-events`

**Purpose:** Core ledger transaction events

```
Partitions: 9
Replication Factor: 3
Retention: 7 days
Compression: Snappy
```

Message schema:
```json
{
  "event_id": "snowflake_id",
  "event_type": "TRANSACTION_POSTED|BALANCE_QUERY",
  "timestamp": 1654321098000,
  "user_id": "uid",
  "amount": 1000,
  "currency": "VND",
  "ledger_line": {
    "account": "1000-ASSET",
    "debit_amount": 1000,
    "credit_amount": 0
  }
}
```

### 2. `gtel-payment-events`

**Purpose:** Payment lifecycle events

```
Partitions: 6
Replication Factor: 3
Retention: 7 days
Compression: Snappy
```

Message schema:
```json
{
  "payment_id": "uuid",
  "status": "INITIATED|PROCESSING|SETTLED|FAILED",
  "merchant_id": "mid",
  "amount": 50000,
  "timestamp": 1654321098000,
  "metadata": {}
}
```

### 3. `gtel-settlement-events`

**Purpose:** Daily settlement and reconciliation

```
Partitions: 3
Replication Factor: 3
Retention: 30 days
Compression: Snappy
```

Message schema:
```json
{
  "settlement_date": "2026-06-03",
  "settlement_id": "uuid",
  "total_transactions": 1234,
  "total_amount": 12345678,
  "status": "PENDING|COMPLETED|FAILED"
}
```

## Configuration

### Broker Configuration (docker-compose.kafka-cluster.yml)

**Replication & Durability:**
```
DEFAULT_REPLICATION_FACTOR=3      # All topics replicated to 3 brokers
MIN_INSYNC_REPLICAS=2             # Require 2 replicas for ACK
```

**Performance:**
```
NUM_NETWORK_THREADS=8             # Network request threads
NUM_IO_THREADS=8                  # I/O operation threads
COMPRESSION_TYPE=snappy           # Snappy compression for topics
```

**Retention:**
```
LOG_RETENTION_HOURS=168           # 7 days default
LOG_SEGMENT_BYTES=1GB             # 1GB per log segment
```

**Reliability:**
```
AUTO_CREATE_TOPICS_ENABLE=true    # Auto-create topics on demand
DELETE_TOPIC_ENABLE=true          # Allow topic deletion
COMPRESSION_TYPE=snappy           # Reduce storage/network overhead
```

## High Availability

### 1. Broker Failover

If a broker fails:
1. Zookeeper detects the failure (heartbeat timeout)
2. Leader election triggered for affected partitions
3. New leader elected from replicas
4. Producers/consumers retry and reconnect
5. No message loss (with acks=all)

**Test failover:**
```bash
docker stop kafka-broker-1
# Wait for metadata refresh (~10 seconds)
docker exec kafka-broker-2 kafka-topics.sh --describe --topic gtel-ledger-events
docker start kafka-broker-1
```

### 2. Replication Factor 3

Benefits:
- Tolerate 2 simultaneous broker failures
- Load distribution across brokers
- Automatic failover without leader election timeout
- In-sync replica (ISR) tracking

**Check ISR:**
```bash
docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 \
  --describe --topic gtel-ledger-events
```

### 3. Min ISR Setting

```
MIN_INSYNC_REPLICAS=2
```

Ensures:
- At least 2 replicas acknowledge writes
- Data consistency even if 1 broker fails
- No loss of committed messages

## Monitoring

### Metrics Available

```bash
# Broker metrics
curl http://localhost:9021/api/v1/clusters/*/brokers

# Topic metrics
curl http://localhost:9021/api/v1/clusters/*/topics/gtel-ledger-events

# Consumer lag
curl http://localhost:9021/api/v1/clusters/*/consumer-groups/\
  saving-gateway/lag?exclude_system_topics=true
```

### Key Metrics to Monitor

1. **Under-Replicated Partitions:** Should be 0
2. **Consumer Lag:** Should be < 10k messages
3. **Broker Disk I/O:** Monitor for saturation
4. **Network Throughput:** Peak during peak hours
5. **Producer Error Rate:** Should be near 0

### Prometheus Integration

Kafka exposes metrics on JMX. To enable Prometheus scraping:

```bash
# Add JMX exporter to brokers (optional)
docker exec kafka-broker-1 env | grep JMX
```

## Producer Configuration

For Saving-Gateway (Spring Boot):

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092,localhost:9093,localhost:9094
    producer:
      acks: all                    # Wait for all ISRs
      retries: 3
      compression-type: snappy     # Compress messages
      batch-size: 16384            # 16KB batch
      linger-ms: 10                # 10ms wait for batching
```

## Consumer Configuration

For Java Ledger (Spring Boot):

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092,localhost:9093,localhost:9094
    consumer:
      group-id: ledger-service
      enable-auto-commit: false    # Manual commit
      max-poll-records: 500        # Batch size
      session-timeout-ms: 30000    # 30 second timeout
```

## Scaling

### Add Broker to Cluster

1. **Update docker-compose.yml** with new broker config
2. **Start the broker:**
   ```bash
   docker-compose -f docker-compose.kafka-cluster.yml up -d kafka-broker-4
   ```
3. **Wait for broker to join cluster** (automatic via Zookeeper)
4. **Rebalance topics:**
   ```bash
   # Create reassignment JSON with new broker
   docker exec kafka-broker-1 kafka-reassign-partitions.sh \
     --bootstrap-server localhost:29092 \
     --reassignment-json-file reassignment.json \
     --execute
   ```

### Increase Partition Count

```bash
docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 \
  --alter --topic gtel-ledger-events --partitions 15
```

**Warning:** Only increase partitions. Decreasing causes data loss.

## Disaster Recovery

### Backup Strategy

1. **Data persistence:** All broker data persists in Docker volumes
2. **Volume snapshots:** Take daily snapshots of Kafka volumes
3. **Export critical topics:**
   ```bash
   docker exec kafka-broker-1 kafka-console-consumer.sh \
     --bootstrap-server localhost:29092 \
     --topic gtel-settlement-events \
     --from-beginning > settlement-backup.json
   ```

### Complete Recovery

```bash
# Backup current cluster
docker-compose -f docker-compose.kafka-cluster.yml down -v

# Restore from snapshot or volumes
# Restart cluster
docker-compose -f docker-compose.kafka-cluster.yml up -d
```

## Troubleshooting

### Broker Won't Start

```bash
docker logs kafka-broker-1
# Check Zookeeper connectivity
docker exec kafka-broker-1 zookeeper-shell.sh zookeeper:2181 ls /
```

### Consumer Lag Increasing

```bash
# Check consumer group status
docker exec kafka-broker-1 kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 \
  --group ledger-service \
  --describe

# Reset offset if needed (CAUTION)
docker exec kafka-broker-1 kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 \
  --group ledger-service \
  --reset-offsets --to-latest \
  --topic gtel-ledger-events \
  --execute
```

### Under-Replicated Partitions

```bash
docker exec kafka-broker-1 kafka-topics.sh \
  --bootstrap-server localhost:29092 \
  --describe --under-replicated-partitions

# Fix by triggering leader election
docker exec kafka-broker-1 kafka-leader-election.sh \
  --bootstrap-server localhost:29092 \
  --election-type preferred \
  --all-topic-partitions
```

## Migration from RabbitMQ to Kafka

### Dual-Write Strategy

1. **Phase 1:** Produce to both RabbitMQ and Kafka
   - Ledger listens to RabbitMQ
   - Gateway produces to both systems

2. **Phase 2:** Consume from Kafka
   - Ledger listens to Kafka topics
   - Monitor lag until caught up

3. **Phase 3:** Remove RabbitMQ
   - Stop RabbitMQ producers
   - Verify all messages processed
   - Decommission RabbitMQ

### Java Implementation

```java
@Configuration
public class KafkaProducerConfig {
    
    @Bean
    public ProducerFactory<String, LedgerEvent> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, 
            "kafka-broker-1:29092,kafka-broker-2:29092,kafka-broker-3:29092");
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        
        return new DefaultProducerFactory<>(configProps);
    }
    
    @Bean
    public KafkaTemplate<String, LedgerEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

## Performance Tuning

### Producer Optimization

```yaml
batch-size: 32768           # 32KB batch (from 16KB)
linger-ms: 100              # Wait 100ms for batch (from 10ms)
compression-type: snappy    # Enabled (reduce network)
buffer-memory: 67108864     # 64MB buffer (from 32MB)
```

### Consumer Optimization

```yaml
max-poll-records: 1000      # Larger batch (from 500)
fetch-min-bytes: 10240      # 10KB minimum (from 1KB)
fetch-max-wait-ms: 500      # 500ms wait (from 500ms)
```

### Broker Optimization

```yaml
num-network-threads: 16     # Increase from 8
num-io-threads: 16          # Increase from 8
num-replica-fetchers: 4     # Cross-broker fetch threads
replica-socket-receive-buffer-bytes: 524288  # 512KB from 102KB
```

## Production Checklist

- [ ] All 3 brokers healthy and in cluster
- [ ] Zookeeper quorum stable
- [ ] All partitions have ISR ≥ 2
- [ ] No under-replicated partitions
- [ ] Control Center accessible and healthy
- [ ] Topics created with RF=3, MIN_ISR=2
- [ ] Retention policies set appropriately
- [ ] Compression enabled (snappy)
- [ ] Monitoring/alerts configured
- [ ] Backup strategy implemented
- [ ] Producer acks=all configured
- [ ] Consumer group created
- [ ] Performance benchmarked
- [ ] Disaster recovery tested

## See Also

- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)
- [Confluent Control Center Guide](https://docs.confluent.io/platform/current/control-center/)
- [Kafka Performance Tuning](https://kafka.apache.org/documentation/#bestpractices)
- [Saving-Gateway Kafka Integration](docs/INTEGRATION-TEST-GUIDE.md)
