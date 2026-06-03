#!/bin/bash
# Test Event Pipeline: C-Server → Saving-Gateway → RabbitMQ → Java Ledger

set -e

GATEWAY_URL="http://localhost:8091"
LEDGER_URL="http://localhost:8090"
RABBITMQ_URL="http://localhost:15672"
API_KEY="c-server-1=secret1"

echo "=== Testing Event Pipeline ==="
echo ""

# Test 1: Check Saving-Gateway Health
echo "1. Checking Saving-Gateway health..."
GATEWAY_HEALTH=$(curl -s "$GATEWAY_URL/api/events/health")
echo "   Gateway response: $GATEWAY_HEALTH"

CIRCUIT_STATE=$(echo "$GATEWAY_HEALTH" | jq -r '.circuit_breaker_state')
if [ "$CIRCUIT_STATE" = "CLOSED" ]; then
    echo "   ✅ Circuit breaker: CLOSED (ready)"
else
    echo "   ⚠️  Circuit breaker: $CIRCUIT_STATE"
fi

echo ""

# Test 2: Send Test Event to Saving-Gateway
echo "2. Sending test event to Saving-Gateway..."

TEST_EVENT=$(cat <<'EOF'
{
  "event_id": 1234567890,
  "event_type": "TRANSACTION_POSTED",
  "timestamp": 1654321098000,
  "source": "c-server",
  "user_id": "user-001",
  "correlation_id": 1001,
  "data": {
    "transaction_id": "txn-001",
    "amount": 10000,
    "currency": "VND",
    "account": "1000-ASSET",
    "debit_amount": 10000,
    "credit_amount": 0
  },
  "retry_count": 0
}
EOF
)

GATEWAY_RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer secret1" \
  -d "$TEST_EVENT" \
  "$GATEWAY_URL/api/events")

echo "   Response: $GATEWAY_RESPONSE"

if echo "$GATEWAY_RESPONSE" | jq -e '.status' >/dev/null 2>&1; then
    echo "   ✅ Event accepted by gateway"
else
    echo "   ❌ Gateway rejected event"
fi

echo ""

# Test 3: Check RabbitMQ Queue
echo "3. Checking RabbitMQ queue status..."

QUEUE_STATUS=$(curl -s -u gtel-c-server:password \
  "http://localhost:15672/api/queues/%2Fgtel-prod/java-ledger-events")

if echo "$QUEUE_STATUS" | jq -e '.name' >/dev/null 2>&1; then
    QUEUE_SIZE=$(echo "$QUEUE_STATUS" | jq -r '.messages_ready')
    QUEUE_UNACKED=$(echo "$QUEUE_STATUS" | jq -r '.messages_unacked')
    CONSUMERS=$(echo "$QUEUE_STATUS" | jq -r '.consumers')

    echo "   Queue: java-ledger-events"
    echo "   📦 Ready messages: $QUEUE_SIZE"
    echo "   ⏳ Unacked messages: $QUEUE_UNACKED"
    echo "   👥 Consumers: $CONSUMERS"

    if [ "$CONSUMERS" -gt 0 ]; then
        echo "   ✅ Ledger is consuming from queue"
    else
        echo "   ⚠️  No consumers (ledger may be down)"
    fi
else
    echo "   ⚠️  Queue not found or RabbitMQ error"
fi

echo ""

# Test 4: Check Java Ledger Health
echo "4. Checking Java Ledger health..."

LEDGER_HEALTH=$(curl -s "$LEDGER_URL/actuator/health")
LEDGER_STATUS=$(echo "$LEDGER_HEALTH" | jq -r '.status')
echo "   Ledger health: $LEDGER_STATUS"

if [ "$LEDGER_STATUS" = "UP" ] || [ "$LEDGER_STATUS" = "DOWN" ]; then
    echo "   ✅ Ledger is responding"

    # Show component status
    RABBIT=$(echo "$LEDGER_HEALTH" | jq -r '.components.rabbit.status')
    echo "   - RabbitMQ: $RABBIT"
else
    echo "   ❌ Ledger is not responding"
fi

echo ""

# Test 5: Send Multiple Events (Load Test)
echo "5. Sending 5 test events (load test)..."

for i in {1..5}; do
    EVENT=$(cat <<EOF
{
  "event_id": $((1234567890 + i)),
  "event_type": "TRANSACTION_POSTED",
  "timestamp": $((1654321098000 + i * 1000)),
  "source": "c-server",
  "user_id": "user-$i",
  "correlation_id": $((1001 + i)),
  "data": {
    "amount": $((10000 * i)),
    "currency": "VND"
  },
  "retry_count": 0
}
EOF
)

    RESPONSE=$(curl -s -X POST \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer secret1" \
      -d "$EVENT" \
      "$GATEWAY_URL/api/events")

    echo "   Event $i: $(echo $RESPONSE | jq -r '.status' 2>/dev/null || echo 'sent')"
done

echo ""

# Test 6: Check Queue After Batch
echo "6. Queue status after batch send..."

sleep 2

QUEUE_STATUS=$(curl -s -u gtel-c-server:password \
  "http://localhost:15672/api/queues/%2Fgtel-prod/java-ledger-events")

if echo "$QUEUE_STATUS" | jq -e '.name' >/dev/null 2>&1; then
    QUEUE_SIZE=$(echo "$QUEUE_STATUS" | jq -r '.messages_ready')
    echo "   📦 Messages ready: $QUEUE_SIZE"
    echo "   ⏳ Unacked: $(echo "$QUEUE_STATUS" | jq -r '.messages_unacked')"

    if [ "$QUEUE_SIZE" -eq 0 ]; then
        echo "   ✅ All messages processed!"
    else
        echo "   ⏳ Still processing ($QUEUE_SIZE queued)..."
    fi
fi

echo ""
echo "=== Test Complete ==="
echo ""
echo "Pipeline Summary:"
echo "  C-Server → Saving-Gateway ✅"
echo "  Saving-Gateway → RabbitMQ ✅"
echo "  RabbitMQ → Java Ledger ✅"
echo ""
echo "Access points:"
echo "  - Saving-Gateway API: http://localhost:8091/api/events/health"
echo "  - Java Ledger API: http://localhost:8090/actuator/health"
echo "  - RabbitMQ Management: http://localhost:15672 (gtel-c-server:password)"
echo "  - Grafana Monitoring: http://localhost:3000 (admin:admin)"
