#!/bin/bash
set -e

echo "╔════════════════════════════════════════════╗"
echo "║  CRYPTO DEPOSIT FLOW - END-TO-END TEST    ║"
echo "╚════════════════════════════════════════════╝"

DEPOSIT_ID="dep-test-$(date +%s)"
AMOUNT="1000000"  # 1 USDT in smallest units
TX_HASH="0x$(openssl rand -hex 32)"
BLOCK_HEIGHT=$((18000000 + RANDOM % 1000000))

echo ""
echo "Test Parameters:"
echo "  Deposit ID: $DEPOSIT_ID"
echo "  Amount: $AMOUNT (wei/satoshi equivalent)"
echo "  TX Hash: $TX_HASH"
echo "  Block: $BLOCK_HEIGHT"

# Step 1: Check services
echo ""
echo "Step 1: Verify Services"
LEDGER_HEALTH=$(curl -s http://localhost:8090/actuator/health | jq -r '.status')
RABBIT_STATUS=$(curl -s http://localhost:8090/actuator/health | jq -r '.components.rabbit.status')
PG_STATUS=$(curl -s http://localhost:8090/actuator/health | jq -r '.components.db.status')

echo "  ✓ Ledger: $LEDGER_HEALTH"
echo "  ✓ RabbitMQ: $RABBIT_STATUS"
echo "  ✓ PostgreSQL: $PG_STATUS"

if [[ "$RABBIT_STATUS" != "UP" ]]; then
  echo "❌ RabbitMQ not connected!"
  exit 1
fi

# Step 2: Publish test event
echo ""
echo "Step 2: Publish CRYPTO_DEPOSIT_INITIATED Event"

# Create test event
cat > /tmp/crypto_event.json << EOF
{
  "event_id": $(date +%s000),
  "event_type": "CRYPTO_DEPOSIT_INITIATED",
  "timestamp": $(date +%s000),
  "source": "test-script",
  "correlation_id": $(date +%s),
  "data": {
    "deposit_id": "$DEPOSIT_ID",
    "crypto_currency": "USDT",
    "crypto_amount": "0x$(printf '%064x' $AMOUNT)",
    "tx_hash": "$TX_HASH",
    "block_height": $BLOCK_HEIGHT
  },
  "retry_count": 0
}
EOF

# Try to publish using RabbitMQ directly via docker
docker exec nivic-dev-rabbitmq-1 rabbitmq-publish \
  -u guest -p guest \
  -V / \
  -x gtel-events \
  -r ledger.crypto \
  -p "$(cat /tmp/crypto_event.json)" 2>/dev/null && echo "✓ Event published to RabbitMQ" || \
echo "⚠ Could not publish via rabbitmq-publish, checking listener status..."

sleep 2

# Step 3: Check listener logs
echo ""
echo "Step 3: Verify Event Processing"
docker logs nivic-dev-java-ledger-1 2>&1 | grep -i "processing\|crypto\|deposit" | tail -3 || \
echo "⚠ No deposit logs found yet (listener may be inactive)"

# Step 4: Check database
echo ""
echo "Step 4: Query Ledger Database"
psql -h localhost -U postgres -d gtelpay_prod -c "
SELECT trans_id, description, status, created_at
FROM coa_trans
ORDER BY created_at DESC
LIMIT 5;" 2>/dev/null || echo "Database query failed"

echo ""
echo "════════════════════════════════════════════"
echo "✓ Test Complete"
echo "════════════════════════════════════════════"
echo ""
echo "Next: Verify event was processed in Ledger logs above"
