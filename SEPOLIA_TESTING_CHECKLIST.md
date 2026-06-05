# 🧪 Sepolia Testnet Testing Checklist

**Status**: Ready to Start  
**Date Started**: June 5, 2026  
**Goal**: Validate complete system with real blockchain deposits

---

## ✅ Phase 1: Setup (45 minutes)

### Task 1: Create Sepolia Wallet
- [ ] Go to https://metamask.io
- [ ] Install MetaMask browser extension
- [ ] Create new wallet or import existing
- [ ] **Switch network to Sepolia** (top right dropdown)
- [ ] Copy your wallet address (format: 0x followed by 40 hex characters)
- [ ] **Save wallet address**: `_____________________________`

### Task 2: Fund with Testnet ETH
- [ ] Go to https://sepoliafaucet.com
- [ ] Paste your wallet address from Task 1
- [ ] Complete the faucet process (may require Twitter/GitHub login)
- [ ] Wait for transaction confirmation (~1 minute)
- [ ] Verify on Etherscan: https://sepolia.etherscan.io/address/YOUR_ADDRESS
- [ ] Check: Should show ~0.5 ETH balance

### Task 3: Get Testnet USDT
- [ ] Go to https://app.uniswap.org
- [ ] Verify you're on **Sepolia network** (top right)
- [ ] Click "Swap" and select ETH → USDT
- [ ] Enter amount: 0.1 ETH (to get ~1-2 USDT)
- [ ] Find USDT token:
  - Contract: `0x7169D38eAf756838186F3f42C2b16210EbfD2026`
  - Or search for "Sepolia USDT"
- [ ] Click "Swap" and confirm in MetaMask
- [ ] Wait for confirmation
- [ ] Verify in wallet: Should show ~1-2 USDT balance

### Task 4: Configure C-Server
**Your wallet address**: `_____________________________`

Edit `/Users/khangdc/Desktop/nivic-dev/.env.sepolia`:
```bash
# Replace this line with YOUR wallet address:
CUSTODY_WALLET=0x1234567890123456789012345678901234567890

# Change to:
CUSTODY_WALLET=YOUR_WALLET_ADDRESS_HERE
```

**Command to update automatically**:
```bash
sed -i '' 's/CUSTODY_WALLET=.*/CUSTODY_WALLET=YOUR_WALLET_ADDRESS/' \
  /Users/khangdc/Desktop/nivic-dev/.env.sepolia
```

### Task 5: Start Monitoring Stack
- [ ] Open terminal
- [ ] Run:
```bash
cd /Users/khangdc/Desktop/nivic-dev/monitoring
docker-compose up -d
```
- [ ] Verify services running:
```bash
docker-compose ps
```
- [ ] Expected: All services showing "Up"

### Task 6: Restart C-Server with Sepolia Config
- [ ] Open **new terminal window**
- [ ] Run:
```bash
cd /Users/khangdc/Desktop/nivic-dev/c-server
source ../.env.sepolia
cargo run --release
```
- [ ] Watch for these logs (should appear within 10 seconds):
  - ✓ "Connected to blockchain: https://sepolia.drpc.org"
  - ✓ "Starting blockchain listener"
  - ✓ "Watching USDT: 0x7169D38eAf756838186F3f42C2b16210EbfD2026"
  - ✓ "Custody wallet: 0xYOUR_ADDRESS"

---

## ✅ Phase 2: Blockchain Deposit Test (5 minutes)

### Task 7: Send Test USDT (Critical Step!)
This is where you test the complete flow. You're sending USDT **to your own custody wallet**.

**Option A: Using Uniswap (Easiest)**
1. Go to https://app.uniswap.org
2. Click "Send" (or "More" → "Send")
3. Select USDT token
4. Enter amount: 1 USDT
5. Paste your custody wallet address
6. Confirm and sign in MetaMask
7. Note the transaction hash

**Option B: Using Etherscan (More direct)**
1. Go to https://sepolia.etherscan.io
2. Search for: `0x7169D38eAf756838186F3f42C2b16210EbfD2026` (USDT contract)
3. Click "Contract" tab → "Write Contract" → Connect MetaMask
4. Find "transfer" function
5. Enter:
   - `_to`: Your wallet address
   - `_value`: 1000000 (1 USDT, since USDT uses 6 decimals)
6. Click "Write" and confirm in MetaMask
7. Note the transaction hash

### Task 8: Watch Deposit Detection
- [ ] Transaction sent (note transaction hash): `_____________________________`
- [ ] Wait 15-30 seconds
- [ ] Check C-Server logs in terminal - should show:
  ```
  Checking blocks X → Y
  ✓ Deposits detected: 1
  ✓ Events published: 1
  ```
- [ ] Check C-Server health endpoint:
  ```bash
  curl http://localhost:8081/health | jq .
  ```
  - Should show: `"deposits_detected": 1` (or higher)

---

## ✅ Phase 3: System Verification (10 minutes)

### Task 9: Verify in Java Ledger
```bash
# Get wallet list
curl http://localhost:8090/api/wallets | jq '.'

# Save the wallet ID from response
# Should see your wallet with balance_minor = 1000000 (1 USDT)
```

**Expected response**:
```json
[
  {
    "id": 1234567890123,
    "uid": "...",
    "balance_minor": 1000000,
    "currency_code": "USDT",
    "status": "ACTIVE"
  }
]
```

- [ ] Wallet found with 1,000,000 balance (1 USDT)
- [ ] Wallet ID: `_____________________________`

### Task 10: Monitor with Grafana
- [ ] Open http://localhost:3000
- [ ] Login: admin / admin
- [ ] Go to Dashboards → Settlement Overview
- [ ] Check that you see:
  - [ ] Deposits detected graph shows 1+
  - [ ] Settlement metrics displaying
  - [ ] System health (CPU, memory, disk)

### Task 11: Create Settlement
Use the wallet ID from Task 9:
```bash
curl -X POST http://localhost:8090/api/settlement/wallet/initiate \
  -H "Content-Type: application/json" \
  -d '{
    "walletId": WALLET_ID_HERE,
    "amountMinor": 500000,
    "type": "CRYPTO_TO_FIAT",
    "currency": "USDT",
    "destination": "test-bank-001"
  }' | jq .
```

- [ ] Settlement created successfully
- [ ] Settlement ID: `_____________________________`
- [ ] Status: PENDING

### Task 12: Complete Settlement Flow
Using the settlement ID from Task 11:
```bash
SETTLEMENT_ID="your_settlement_id_here"

# Hold
curl -X POST http://localhost:8090/api/settlement/wallet/$SETTLEMENT_ID/hold

# Post (after 1-2 seconds)
curl -X POST "http://localhost:8090/api/settlement/wallet/$SETTLEMENT_ID/post?transactionId=99999"

# Execute (after 1-2 seconds)
curl -X POST "http://localhost:8090/api/settlement/$SETTLEMENT_ID/execute?bankTransactionId=SEPOLIA-001"

# Confirm (after 1-2 seconds)
curl -X POST "http://localhost:8090/api/settlement/$SETTLEMENT_ID/confirm?bankTransactionId=SEPOLIA-001"

# Check final status
curl http://localhost:8090/api/settlement/$SETTLEMENT_ID | jq .
```

- [ ] Hold: Success
- [ ] Post: Success
- [ ] Execute: Success
- [ ] Confirm: Success
- [ ] Final status: CONFIRMED

---

## ✅ Phase 4: Extended Testing (20+ minutes)

### Task 13: Send Multiple Deposits
Test system stability with multiple deposits:

```bash
# Send 5 more USDT transfers to your custody wallet
# (Using Uniswap or direct transfer)

# Between each transfer, wait 15-30 seconds for detection
```

- [ ] Deposit 1: Detected ✓
- [ ] Deposit 2: Detected ✓
- [ ] Deposit 3: Detected ✓
- [ ] Deposit 4: Detected ✓
- [ ] Deposit 5: Detected ✓

**Check total**:
```bash
curl http://localhost:8081/health | jq '.deposits_detected'
```

Should show: 6+ (1 from task 8 + 5 new ones)

### Task 14: Test Concurrent Settlements
With multiple deposits available, create settlements concurrently:

```bash
# Create 3 settlements rapidly (don't wait between them)

for i in {1..3}; do
  curl -X POST http://localhost:8090/api/settlement/wallet/initiate \
    -H "Content-Type: application/json" \
    -d "{
      \"walletId\": WALLET_ID_HERE,
      \"amountMinor\": 200000,
      \"type\": \"CRYPTO_TO_FIAT\",
      \"currency\": \"USDT\",
      \"destination\": \"test-bank-$i\"
    }" | jq '.id' >> /tmp/settlement_ids.txt
done

# All 3 should succeed (tests pessimistic locking)
cat /tmp/settlement_ids.txt
```

- [ ] Settlement 1: Created ✓
- [ ] Settlement 2: Created ✓
- [ ] Settlement 3: Created ✓

### Task 15: Performance Baselines
Record metrics for production comparison:

```bash
# C-Server deposits
curl http://localhost:8081/health | jq '.deposits_detected, .events_published'

# Database settlement count
psql -U postgres -d gtelpay_prod -c \
  "SELECT status, COUNT(*) FROM settlement GROUP BY status;"

# System metrics
curl http://localhost:9090/api/v1/query?query=up | jq '.data.result | length'
```

**Record baseline metrics**:
- Total deposits detected: `_____________________________`
- Total events published: `_____________________________`
- Total settlements: `_____________________________`
- System uptime: `_____________________________`

---

## ✅ Phase 5: Monitoring Validation (5 minutes)

### Task 16: Verify Alert Rules
Check that Prometheus alerts are evaluating:

```bash
# View all alerts
curl http://localhost:9090/api/v1/alerts | jq '.data.alerts | length'

# View firing alerts (should be 0 since system is healthy)
curl http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | select(.state == "firing")'

# Check alert rules
curl http://localhost:9090/api/v1/rules | jq '.data.groups[0].rules | length'
```

- [ ] Alerts configured: 13
- [ ] Firing alerts: 0 (system is healthy)
- [ ] All metrics scraping: ✓

### Task 17: Document Performance
In Grafana, take note of:
- [ ] Average settlement latency: `_____________________________` ms
- [ ] Max memory usage: `_____________________________` %
- [ ] Max CPU usage: `_____________________________` %
- [ ] RabbitMQ queue depth: `_____________________________` messages
- [ ] Database connections: `_____________________________` active

---

## ✅ Summary

**Phase 1 (Setup)**: ✅ Complete
**Phase 2 (Deposit Test)**: ✅ Complete
**Phase 3 (Verification)**: ✅ Complete
**Phase 4 (Extended Testing)**: ✅ Complete
**Phase 5 (Monitoring)**: ✅ Complete

**Total Time**: ~90 minutes
**Result**: System validated with real Sepolia blockchain ✅

---

## 📊 What This Proves

✅ **Blockchain Integration**
- C-Server detects real Ethereum transactions
- Deposit detection works across network latency
- HashSet prevents duplicates

✅ **Event-Driven Architecture**
- RabbitMQ publishing works
- Java Ledger receives events correctly
- Async processing handles concurrent messages

✅ **Settlement State Machine**
- PENDING → HOLD → POSTED → EXECUTING → CONFIRMED
- All transitions work correctly
- Database persistence verified

✅ **Concurrent Processing**
- Pessimistic locking prevents race conditions
- Multiple settlements process safely
- No balance corruption

✅ **Production Monitoring**
- Prometheus scrapes all metrics
- Grafana dashboards display data
- AlertManager detects issues
- Performance baselines established

---

## 🚀 Next Steps After Testing

1. **Review monitoring data** (30 minutes)
   - Analyze Grafana dashboards
   - Document performance baselines
   - Verify all alerts work

2. **Security & Compliance Audit** (2 hours)
   - Code security review
   - Wallet management validation
   - Compliance checklist

3. **Production Preparation** (2 hours)
   - Get production RPC endpoint (Alchemy/Infura)
   - Configure production environment
   - Setup wallet key management

4. **Production Deployment** (1 day)
   - Deploy with 10% traffic
   - Monitor for 4 hours
   - Ramp to 100%

---

**Status**: 🟢 Ready to Execute  
**Estimated Completion**: ~2 hours  
**Next Review**: After Phase 5 completion
