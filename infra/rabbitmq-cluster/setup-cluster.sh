#!/bin/bash
# RabbitMQ Cluster Setup Script
# Initializes a 3-node RabbitMQ cluster with HAProxy load balancer

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "=== RabbitMQ Cluster Setup ==="
echo "Project root: $PROJECT_ROOT"
echo ""

# Start docker-compose
echo "1. Starting RabbitMQ cluster (3 nodes + HAProxy)..."
docker-compose -f "$PROJECT_ROOT/docker-compose.rabbitmq-cluster.yml" up -d

echo ""
echo "2. Waiting for nodes to be healthy..."
sleep 15

# Wait for all nodes to be ready
for i in {1..30}; do
    if docker exec rabbitmq-1 rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
        echo "   ✓ Node 1 is healthy"
        break
    fi
    echo "   Waiting for node 1... ($i/30)"
    sleep 2
done

for i in {1..30}; do
    if docker exec rabbitmq-2 rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
        echo "   ✓ Node 2 is healthy"
        break
    fi
    echo "   Waiting for node 2... ($i/30)"
    sleep 2
done

for i in {1..30}; do
    if docker exec rabbitmq-3 rabbitmq-diagnostics -q ping >/dev/null 2>&1; then
        echo "   ✓ Node 3 is healthy"
        break
    fi
    echo "   Waiting for node 3... ($i/30)"
    sleep 2
done

# Join nodes to cluster
echo ""
echo "3. Forming cluster..."

# Stop RabbitMQ app on node 2 and join cluster
docker exec rabbitmq-2 rabbitmqctl stop_app
docker exec rabbitmq-2 rabbitmqctl reset
docker exec rabbitmq-2 rabbitmqctl join_cluster rabbit@rabbitmq-1
docker exec rabbitmq-2 rabbitmqctl start_app
echo "   ✓ Node 2 joined cluster"

# Stop RabbitMQ app on node 3 and join cluster
docker exec rabbitmq-3 rabbitmqctl stop_app
docker exec rabbitmq-3 rabbitmqctl reset
docker exec rabbitmq-3 rabbitmqctl join_cluster rabbit@rabbitmq-1
docker exec rabbitmq-3 rabbitmqctl start_app
echo "   ✓ Node 3 joined cluster"

sleep 5

# Verify cluster status
echo ""
echo "4. Verifying cluster status..."
CLUSTER_STATUS=$(docker exec rabbitmq-1 rabbitmqctl cluster_status)

if echo "$CLUSTER_STATUS" | grep -q "rabbit@rabbitmq-2"; then
    echo "   ✓ Node 2 is in cluster"
fi

if echo "$CLUSTER_STATUS" | grep -q "rabbit@rabbitmq-3"; then
    echo "   ✓ Node 3 is in cluster"
fi

echo ""
echo "5. Setting up queue policies for HA..."

# Create HA policy for all queues (replicate to all nodes)
docker exec rabbitmq-1 rabbitmqctl set_policy \
    -p /gtel-prod \
    HA-all "^" \
    '{"ha-mode":"all","ha-sync-mode":"automatic","ha-sync-batch-size":5}' \
    --apply-to queues

echo "   ✓ HA policy applied to all queues"

# Create HA policy for exchanges
docker exec rabbitmq-1 rabbitmqctl set_policy \
    -p /gtel-prod \
    HA-exchanges "^" \
    '{"ha-mode":"all"}' \
    --apply-to exchanges

echo "   ✓ HA policy applied to exchanges"

echo ""
echo "=== Cluster Setup Complete! ==="
echo ""
echo "Access points:"
echo "  - AMQP (balanced):        amqp://gtel-c-server:password@localhost:5672/gtel-prod"
echo "  - Management UI (direct): http://localhost:15672 (admin/admin)"
echo "  - HAProxy Stats:          http://localhost:8404/stats"
echo ""
echo "Individual nodes:"
echo "  - Node 1: http://localhost:15672"
echo "  - Node 2: http://localhost:15673"
echo "  - Node 3: http://localhost:15674"
echo ""
echo "Verify cluster status:"
echo "  docker exec rabbitmq-1 rabbitmqctl cluster_status"
echo ""
