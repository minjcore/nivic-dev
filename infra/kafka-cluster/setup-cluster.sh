#!/bin/bash
# Kafka Cluster Setup Script
# Initializes a 3-broker Kafka cluster with Zookeeper and Control Center

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=== Kafka Cluster Setup ==="
echo "Project root: $PROJECT_ROOT"
echo ""

# Start docker-compose
echo "1. Starting Kafka cluster (3 brokers + Zookeeper + Control Center)..."
docker-compose -f "$PROJECT_ROOT/docker-compose.kafka-cluster.yml" up -d

echo ""
echo "2. Waiting for brokers to be healthy..."
sleep 20

# Wait for all brokers to be ready
for i in {1..30}; do
    if docker exec kafka-broker-1 kafka-broker-api-versions.sh --bootstrap-server localhost:29092 >/dev/null 2>&1; then
        echo "   ✓ Broker 1 is healthy"
        break
    fi
    echo "   Waiting for broker 1... ($i/30)"
    sleep 2
done

for i in {1..30}; do
    if docker exec kafka-broker-2 kafka-broker-api-versions.sh --bootstrap-server localhost:29092 >/dev/null 2>&1; then
        echo "   ✓ Broker 2 is healthy"
        break
    fi
    echo "   Waiting for broker 2... ($i/30)"
    sleep 2
done

for i in {1..30}; do
    if docker exec kafka-broker-3 kafka-broker-api-versions.sh --bootstrap-server localhost:29092 >/dev/null 2>&1; then
        echo "   ✓ Broker 3 is healthy"
        break
    fi
    echo "   Waiting for broker 3... ($i/30)"
    sleep 2
done

echo ""
echo "3. Creating default topics..."

# Create topics for GtelPay event streaming
docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 \
  --create --if-not-exists \
  --topic gtel-ledger-events \
  --partitions 9 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --config compression.type=snappy \
  --config min.insync.replicas=2

echo "   ✓ Topic: gtel-ledger-events (9 partitions, RF=3)"

docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 \
  --create --if-not-exists \
  --topic gtel-payment-events \
  --partitions 6 \
  --replication-factor 3 \
  --config retention.ms=604800000 \
  --config compression.type=snappy \
  --config min.insync.replicas=2

echo "   ✓ Topic: gtel-payment-events (6 partitions, RF=3)"

docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 \
  --create --if-not-exists \
  --topic gtel-settlement-events \
  --partitions 3 \
  --replication-factor 3 \
  --config retention.ms=2592000000 \
  --config compression.type=snappy \
  --config min.insync.replicas=2

echo "   ✓ Topic: gtel-settlement-events (3 partitions, RF=3)"

echo ""
echo "4. Verifying cluster metadata..."
BROKERS=$(docker exec kafka-broker-1 kafka-broker-api-versions.sh --bootstrap-server localhost:29092 2>/dev/null | grep "id:" || echo "")
if [[ ! -z "$BROKERS" ]]; then
    echo "   ✓ Cluster is healthy"
fi

TOPIC_COUNT=$(docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 --list | wc -l)
echo "   ✓ Topics created: $(docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 --list | grep gtel)"

echo ""
echo "=== Kafka Cluster Setup Complete! ==="
echo ""
echo "Access points:"
echo "  - Bootstrap servers: kafka-broker-1:29092,kafka-broker-2:29092,kafka-broker-3:29092"
echo "  - External (Docker host): localhost:9092,localhost:9093,localhost:9094"
echo "  - Control Center: http://localhost:9021"
echo "  - Zookeeper: localhost:2181"
echo ""
echo "Management commands:"
echo "  - List topics:"
echo "    docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 --list"
echo ""
echo "  - Describe topic:"
echo "    docker exec kafka-broker-1 kafka-topics.sh --bootstrap-server localhost:29092 --describe --topic gtel-ledger-events"
echo ""
echo "  - Produce test message:"
echo "    docker exec -it kafka-broker-1 kafka-console-producer.sh --broker-list localhost:29092 --topic gtel-ledger-events"
echo ""
echo "  - Consume messages:"
echo "    docker exec -it kafka-broker-1 kafka-console-consumer.sh --bootstrap-server localhost:29092 --topic gtel-ledger-events --from-beginning"
echo ""
