# Blockchain Listener Design — Build vs Buy Decision

## The Problem We're Solving

GtelPay needs to detect **crypto deposits in real-time** and post them to the accounting ledger atomically. Options:

### Option A: Build Custom Listener (What we built)

**Pros:**
- ✅ Full control: Can replay, filter, reorg-handle ourselves
- ✅ No external dependencies: Works offline if blockchain is accessible
- ✅ Cost: $0/month (just compute)
- ✅ Customizable: Can add VRF, multi-sig validation, etc

**Cons:**
- ❌ Complex: Handle reorgs, missed blocks, race conditions
- ❌ Operational burden: Monitoring, restarts, state management
- ❌ Slower: Poll interval = 12s, not real-time
- ❌ Not high-availability by default

**Good for:** Early stage, testing, full control requirements

---

### Option B: Use Managed Service (Recommended for Production)

**Services:**
- **Alchemy Notify** — Webhooks when deposits arrive ($1-10/month)
- **The Graph** — Decentralized indexing (free or $20/month)
- **Thirdweb Webhooks** — High availability, retry handling
- **Infura webhooks** — Ethereum-native, battle-tested

**Pros:**
- ✅ Real-time: Instant notification (< 1 second)
- ✅ Reliable: HA, automatic reorg handling
- ✅ Compliant: Audit logs, SLA guarantees
- ✅ Minimal ops: Just configure and listen

**Cons:**
- ❌ Cost: $10-100+/month per network
- ❌ Dependency: If service down, you don't know about deposits
- ❌ Less control: Can't customize filtering/validation

**Good for:** Production, compliance requirements, scale

---

### Option C: Hybrid (Recommended for MVP)

1. **Build internal listener** (what we did) for development & testing
2. **Use Alchemy Notify** for production deposits
3. **Internal listener as backup** — validates received deposits

```
Deposit → Alchemy webhook → Validate with internal listener → Post to ledger
                ↓ (if webhook fails)
          Internal listener catches it within 12 seconds
```

---

## Implementation Strategy

### Phase 1: MVP (Today)
```
Custom Go listener (in docker-compose) 
→ Polls every 12 seconds
→ Emits to RabbitMQ
→ Integration tests
```

**Deployment:** `docker-compose up` — blockchain-listener runs alongside ledger

### Phase 2: Production (Month 2)
```
Alchemy Notify webhook (production)
+ Custom listener (backup/validation)
+ Fallback to listener if webhook unavailable
```

### Phase 3: Scale (Month 6)
```
The Graph subgraph
+ Multi-chain listener (Polygon, Optimism, Arbitrum)
+ Real-time dashboard
```

---

## Current Go Listener — When to Use

**Use the custom listener if:**
- Testing locally (no Alchemy API key needed)
- Developing new crypto flows (control test data)
- Running in environments without external APIs
- Building internal audit/reconciliation
- Need offline capability

**Don't use for production yet because:**
- No redundancy (single point of failure)
- 12-second latency (not real-time)
- Manual state recovery if pod crashes
- No metric/alerting integration

---

## Quick Start: Choose Your Path

### Path A: Quick Dev/Test
```bash
# Use the Go listener we just built
docker-compose -f docker-compose.integration-test.yml up blockchain-listener

# Watch RabbitMQ for crypto events
curl -u gtel-c-server:password http://localhost:15672/api/queues/%2Fgtel-prod/java-ledger-events
```

### Path B: Alchemy Production
```bash
# 1. Get API key from https://dashboard.alchemy.com
# 2. Create webhook for USDT transfers to custody wallet
# 3. Set webhook URL to: https://your-domain/api/crypto/deposit

# 4. Update docker-compose to remove blockchain-listener service
# 5. Deploy REST endpoint to receive webhook
```

### Path C: The Graph (Decentralized)
```bash
# Create subgraph at https://thegraph.com
# Query: all USDT transfers to custody wallet
# Subscribe to changes via GraphQL subscription

# Lighter than listener, but requires GraphQL client
```

---

## Recommendation

**For this MVP:** Use custom listener (already built) + Alchemy Notify fallback

**Justification:**
1. **No external costs** during development
2. **Full control** for testing deposit flows
3. **Easy to replace** with Alchemy later (same event schema)
4. **Self-contained**: Doesn't require third-party API keys

---

## Listener Architecture (Current)

```
┌─────────────────────────────────────────┐
│  Ethereum RPC Endpoint (Alchemy/Infura) │
└────────────────┬────────────────────────┘
                 │ (JSON-RPC)
        ┌────────▼────────┐
        │  Go Listener    │
        │ - Poll blocks   │
        │ - Decode xfers  │
        │ - Track reorgs  │
        └────────┬────────┘
                 │ (JSON)
        ┌────────▼─────────────┐
        │  RabbitMQ Exchange   │
        │  gtel-events         │
        │  routing: ledger.crypto
        └────────┬─────────────┘
                 │
        ┌────────▼──────────────┐
        │ Saving-Gateway        │
        │ EventDeduplicator     │
        │ CryptoDepositHandler  │
        └────────┬──────────────┘
                 │
        ┌────────▼──────────────┐
        │ Java Ledger           │
        │ CryptoDepositProcessor│
        │ Post journal entries  │
        └───────────────────────┘
```

---

## Testing the Listener

### Manual Test
```bash
# 1. Start listener + RabbitMQ
docker-compose up rabbitmq blockchain-listener

# 2. Check RabbitMQ management UI
curl -u guest:guest http://localhost:15672/api/queues

# 3. Send test event (simulating blockchain)
curl -X POST http://localhost:5672 \
  -H "Content-Type: application/json" \
  -d '{"event_id": 1, "event_type": "CRYPTO_DEPOSIT_INITIATED", ...}'

# 4. Verify in ledger
curl http://localhost:8090/actuator/health
```

### Integration Test
```bash
docker-compose -f docker-compose.integration-test.yml up
./test-event-pipeline.sh
# Should show: Blockchain Listener → RabbitMQ → Ledger ✓
```

---

## Migration Path: Custom → Alchemy

When you want to switch to Alchemy:

1. **Keep event schema** (our JSON is identical to Alchemy's)
2. **Implement Alchemy adapter** (converts webhook → our event format)
3. **Run both in parallel** (listener as backup, Alchemy primary)
4. **Monitor lag** (if Alchemy lag > 2min, trigger alert)
5. **Fallback:** If Alchemy unavailable, listener picks up within 12s

```java
@RestController
class AlchemyWebhookController {
  @PostMapping("/api/crypto/deposit")
  void handleAlchemyWebhook(@RequestBody AlchemyEvent evt) {
    // Convert Alchemy event → our event schema
    DepositEvent ourEvent = convertAlchemy(evt);
    // Publish same way as listener
    eventPublisher.publish(ourEvent);
  }
}
```

This gives you **best of both worlds**: Real-time + backup.

---

## Monitoring & Alerts

Once deployed, watch these metrics:

```
1. Listener health:
   - Last block scanned (should be within 2 blocks of current)
   - RabbitMQ publish success rate (should be 99%+)
   - Processed TXs count (cumulative)

2. Ledger health:
   - Crypto deposits posted (count per hour)
   - Failed posts (should be 0)
   - FX rate staleness (< 5 min old)

3. RabbitMQ:
   - Queue depth (should be 0 if ledger processing)
   - Consumer count (should be > 0)
   - Message TTL expiry (set to 24h for crypto events)
```

Alert if:
- Listener stops publishing (> 60s gap)
- Queue depth > 100 (ledger not consuming)
- Consumer count = 0 (ledger crashed)
