# 🧪 Sepolia Testnet Integration Testing Guide

**Date**: June 5, 2026  
**Status**: Ready for End-to-End Blockchain Testing  
**Environment**: Ethereum Sepolia Testnet (Chain ID: 11155111)

---

## 📋 Overview

This guide walks through testing the complete crypto settlement system with real Sepolia blockchain transfers. The system automatically detects ERC20 deposits, processes them through the settlement pipeline, and makes them available for CRYPTO_TO_FIAT settlements.

### What Gets Tested
- ✅ Blockchain deposit detection (C-Server)
- ✅ RabbitMQ event publishing
- ✅ Java Ledger wallet balance updates
- ✅ Settlement flow with real blockchain funds
- ✅ End-to-end blockchain → ledger → settlement

---

## 🏗️ System Architecture

```
SEPOLIA TESTNET
    ↓ (ERC20 Transfer)
    ↓
C-SERVER (Blockchain Listener)
    ├─ Polls eth_getLogs() every 15 seconds
    ├─ Detects Transfer events
    └─ Publishes CRYPTO_DEPOSIT_INITIATED
    ↓ (RabbitMQ Message)
    ↓
RABBITMQ (Topic Exchange: gtel-events)
    └─ Routing Key: ledger.crypto
    ↓ (Event Message)
    ↓
JAVA LEDGER (Event Listener)
    ├─ Receives CRYPTO_DEPOSIT_INITIATED
    ├─ Creates deposit record
    ├─ Updates wallet balance
    └─ Publishes CRYPTO_DEPOSIT_CONFIRMED
    ↓ (Database Update)
    ↓
POSTGRESQL (gtelpay_prod)
    ├─ wallet: balance_minor increased
    └─ settlement: ready for creation
```

---

## 🚀 Quick Start (5 minutes)

### Prerequisites
- All services running (C-Server, Java Ledger, RabbitMQ, PostgreSQL)
- Sepolia testnet ETH (from faucet)
- Sepolia testnet USDT (from Uniswap or faucet)

### Steps

**1. Create Custody Wallet**
```bash
# Use MetaMask or any web3 wallet
# Create a new account on Sepolia testnet
# Example: 0xYourSepoliaAddress
```

**2. Get Sepolia RPC Endpoint**

Option A (Recommended - Free):
```bash
BLOCKCHAIN_RPC_URL="https://sepolia.drpc.org"
```

Option B (Infura):
```bash
# Sign up at https://infura.io
# Create Sepolia project
# Use: https://sepolia.infura.io/v3/{PROJECT_ID}
```

Option C (Alchemy):
```bash
# Sign up at https://alchemy.com
# Create Sepolia app
# Use: https://eth-sepolia.g.alchemy.com/v2/{API_KEY}
```

**3. Configure C-Server**

Edit `.env.sepolia`:
```bash
C_SERVER_PORT=8081
BLOCKCHAIN_RPC_URL=https://sepolia.drpc.org
BLOCKCHAIN_NETWORK=sepolia
BLOCKCHAIN_CHAIN_ID=11155111

# Sepolia USDT contract address
USDT_CONTRACT=0x7169D38eAf756838186F3f42C2b16210EbfD2026

# Your custody wallet (from step 1)
CUSTODY_WALLET=0xYourSepoliaWalletAddress

RABBITMQ_URL=amqp://guest:guest@localhost:5672//
RABBITMQ_EXCHANGE=gtel-events
RABBITMQ_ROUTING_KEY=ledger.crypto
```

**4. Restart C-Server**
```bash
cd c-server
source ../.env.sepolia
cargo run --release
```

Watch for logs:
```
✓ Connected to blockchain: https://sepolia.drpc.org
✓ Starting blockchain listener
Watching USDT: 0x7169D38eAf756838186F3f42C2b16210EbfD2026
Custody wallet: 0xYourSepoliaWalletAddress
```

**5. Send Test USDT**

Option A: Using Uniswap:
- Go to https://app.uniswap.org (select Sepolia)
- Swap ETH for USDT
- Send 1-10 USDT to your CUSTODY_WALLET

Option B: Using another wallet:
- If you have USDT on another testnet account, transfer to your custody wallet

**6. Monitor Deposit Detection**

Watch C-Server logs for (should appear within 15-30 seconds):
```
Checking blocks 10992272 → 10993287
✓ Deposits detected: 1
✓ Events published: 1
```

**7. Verify Deposit in Java Ledger**

Query wallet balance:
```bash
curl http://localhost:8090/api/wallets/{walletId}/balance | jq .
```

Expected response:
```json
{
  "total": 1000000,    // 1 USDT in minor units
  "held": 0,
  "available": 1000000
}
```

**8. Complete Settlement Flow**

With the new balance, initiate settlement:
```bash
curl -X POST http://localhost:8090/api/settlement/wallet/initiate \
  -H "Content-Type: application/json" \
  -d '{
    "walletId": <walletId>,
    "amountMinor": 500000,
    "type": "CRYPTO_TO_FIAT",
    "currency": "USDT",
    "destination": "bank-001"
  }'
```

Then complete the settlement flow:
- Hold → Post → Execute → Confirm

---

## 📊 Testing Scenarios

### Scenario 1: Single Deposit Detection
**Objective**: Verify C-Server detects one USDT transfer

**Steps**:
1. Configure C-Server with Sepolia RPC
2. Send 1 USDT to custody wallet
3. Wait 15-30 seconds for C-Server to poll
4. Check C-Server health: `curl http://localhost:8081/health`
5. Verify `deposits_detected` counter increased

**Success Criteria**:
- C-Server logs show "Deposits detected: 1"
- Java Ledger wallet balance increased by 1,000,000
- RabbitMQ received CRYPTO_DEPOSIT_INITIATED event

### Scenario 2: Multiple Deposits
**Objective**: Verify system handles sequential deposits

**Steps**:
1. Send USDT transfer #1 (1 token)
2. Wait for detection (15-30 seconds)
3. Send USDT transfer #2 (2 tokens)
4. Wait for detection (15-30 seconds)
5. Send USDT transfer #3 (3 tokens)
6. Verify all detected

**Success Criteria**:
- All three transfers detected
- Wallet balance = 6,000,000 (1+2+3 USDT)
- No duplicate detections
- Proper ordering maintained

### Scenario 3: Deposit → Settlement → Confirmation
**Objective**: End-to-end crypto deposit + settlement

**Steps**:
1. Send 5 USDT to custody wallet
2. Wait for C-Server detection
3. Verify wallet balance updated to 5,000,000
4. Create settlement for 2,000,000 (2 USDT)
5. Hold → Post → Execute → Confirm settlement
6. Verify final settlement status = CONFIRMED

**Success Criteria**:
- Wallet balance available: 3,000,000 (5-2)
- Settlement moved through all states
- Database records created for each state
- RabbitMQ events published throughout

---

## 🔍 Debugging & Monitoring

### Check C-Server Health
```bash
curl http://localhost:8081/health | jq .
```

Expected output:
```json
{
  "status": "UP",
  "blockchain": "UP",
  "rabbitmq": "UP",
  "deposits_detected": 42,
  "events_published": 42
}
```

### View C-Server Metrics
```bash
curl http://localhost:8081/metrics
```

### Monitor RabbitMQ Messages
```bash
# Login to RabbitMQ management console
# http://localhost:15672
# Username: guest, Password: guest
# Check gtel-events exchange → bindings
```

### Query Deposits in Database
```bash
psql -h localhost -U postgres -d gtelpay_prod

-- View recent deposits
SELECT id, wallet_id, amount_minor, blockchain_tx_hash, created_at 
FROM crypto_deposit 
ORDER BY created_at DESC 
LIMIT 10;

-- Check wallet balances
SELECT id, uid, balance_minor, currency_code 
FROM wallet;
```

### View C-Server Logs (with timestamps)
```bash
# If running in background, check logs:
journalctl -u c-server -f

# Or if running in terminal, output will show directly:
# 2026-06-05T12:34:56.789Z INFO: Checking blocks 10992000 → 10993000
# 2026-06-05T12:34:57.100Z INFO: ✓ Deposits detected: 2
```

---

## ⚙️ Configuration Reference

### C-Server Environment Variables

```bash
# HTTP Server
C_SERVER_PORT=8081

# Blockchain Configuration
BLOCKCHAIN_RPC_URL=https://sepolia.drpc.org
BLOCKCHAIN_NETWORK=sepolia
BLOCKCHAIN_CHAIN_ID=11155111

# Contract Addresses (Sepolia)
USDT_CONTRACT=0x7169D38eAf756838186F3f42C2b16210EbfD2026
USDC_CONTRACT=0x1234567890123456789012345678901234567890

# Your Custody Wallet (update this!)
CUSTODY_WALLET=0xYourSepoliaWalletAddress

# RabbitMQ
RABBITMQ_URL=amqp://guest:guest@localhost:5672//
RABBITMQ_EXCHANGE=gtel-events
RABBITMQ_ROUTING_KEY=ledger.crypto

# Polling Configuration
BLOCK_POLL_INTERVAL=15        # seconds
BLOCKS_TO_CHECK=1000          # lookback window
```

### Sepolia Contract Addresses

| Token | Address |
|-------|---------|
| USDT | `0x7169D38eAf756838186F3f42C2b16210EbfD2026` |
| USDC | `0x1234567890123456789012345678901234567890` |
| WETH | `0xfff9976782d46cc05630d07ee6e6a95b5b8dce91` |

### RPC Endpoints (Public)

| Provider | URL | Rate Limit |
|----------|-----|-----------|
| DRPC | https://sepolia.drpc.org | 3 req/sec |
| Infura | https://sepolia.infura.io/v3/{key} | Depends on plan |
| Alchemy | https://eth-sepolia.g.alchemy.com/v2/{key} | Depends on plan |
| Ankr | https://rpc.ankr.com/eth_sepolia | 1000 req/day |

---

## 🧪 Test Cases

### TC-001: Deposit Detection
```
When: USDT transfer to custody wallet
Then: C-Server detects within 15-30 seconds
  And: Java Ledger balance increased
  And: Deposit record created in database
```

### TC-002: Event Publishing
```
When: Deposit detected
Then: CRYPTO_DEPOSIT_INITIATED published to RabbitMQ
  And: Message contains deposit_id, amount, tx_hash
  And: Message routed to ledger.crypto
```

### TC-003: Balance Update
```
When: Deposit event received by Java Ledger
Then: wallet.balance_minor increased by amount
  And: Wallet status remains ACTIVE
  And: Transaction recorded in audit log
```

### TC-004: Settlement Creation
```
When: Wallet has available balance from deposit
Then: Can create settlement with new balance
  And: Settlement moves through states correctly
  And: Settlement confirms successfully
```

### TC-005: Duplicate Prevention
```
When: Same transaction polled twice
Then: Deposit only recorded once
  And: No duplicate balance increases
  And: System tracks processed tx hashes
```

---

## 🐛 Common Issues & Solutions

### Issue: "Failed to connect to blockchain"
**Solution**:
```bash
# Check RPC endpoint is accessible
curl https://sepolia.drpc.org -X POST \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"eth_blockNumber","params":[],"id":1}'

# Should return a block number in result field
```

### Issue: "Deposits detected: 0" (no deposits found)
**Possible Causes**:
1. No transfers to custody wallet yet
2. Custody wallet address incorrect (check encoding)
3. Wrong contract address configured
4. Transfers outside the 1000-block lookback window

**Solution**:
```bash
# Verify custody wallet address is valid Sepolia address
# Re-send USDT transfer with correct custody wallet
# Check wallet balance on Etherscan
```

### Issue: RabbitMQ message not reaching Java Ledger
**Solution**:
```bash
# Check RabbitMQ broker is running
curl -u guest:guest http://localhost:15672/api/aliveness-test

# Check exchange exists
curl -u guest:guest http://localhost:15672/api/exchanges | jq '.[] | select(.name == "gtel-events")'

# Check Java Ledger is listening
curl http://localhost:8090/actuator/health | jq '.components.rabbit'
```

### Issue: Wallet balance not updated in Java Ledger
**Solution**:
```bash
# Check CryptoDepositListener logs in Java Ledger
# Verify deposit listener is active
curl http://localhost:8090/api/wallets/{id} | jq '.balance_minor'

# Query database directly
psql -U postgres -d gtelpay_prod -c "SELECT * FROM crypto_deposit ORDER BY created_at DESC LIMIT 1;"
```

---

## 📈 Performance Expectations

| Metric | Expected | Typical |
|--------|----------|---------|
| Deposit Detection | < 30 sec | 15-20 sec |
| RabbitMQ Publishing | < 100 ms | 5-10 ms |
| Balance Update | < 1 sec | 200-500 ms |
| Settlement Creation | < 50 ms | 20-30 ms |
| Full Flow (Deposit → Settlement) | < 2 min | 30-60 sec |

---

## 🔐 Security Considerations

### For Testing
- Use test wallets with small amounts
- Don't expose RPC keys in logs
- Use public endpoints or test API keys

### For Production
- Use private RPC endpoint (Alchemy/Infura Pro)
- Implement rate limiting on RPC calls
- Add monitoring for suspicious transfer patterns
- Verify custody wallet ownership
- Implement withdrawal limits
- Add transaction signing verification

---

## 📞 Support

### Helpful Resources
- Sepolia Faucet: https://sepoliafaucet.com
- Block Explorer: https://sepolia.etherscan.io
- Uniswap (Sepolia): https://app.uniswap.org
- MetaMask Docs: https://docs.metamask.io

### Debugging Commands

```bash
# Check if C-Server is running
curl http://localhost:8081/health

# Check Java Ledger status
curl http://localhost:8090/actuator/health | jq .

# List recent deposits
psql -U postgres -d gtelpay_prod -c "
  SELECT id, wallet_id, amount_minor, blockchain_tx_hash, status 
  FROM crypto_deposit 
  ORDER BY created_at DESC 
  LIMIT 5;"

# Check wallet balance
psql -U postgres -d gtelpay_prod -c "
  SELECT id, uid, balance_minor, currency_code 
  FROM wallet 
  WHERE status = 'ACTIVE';"
```

---

## ✅ Testing Checklist

- [ ] C-Server configured with Sepolia RPC
- [ ] Custody wallet created and funded with testnet ETH
- [ ] USDT obtained on Sepolia testnet
- [ ] .env.sepolia configured with wallet address
- [ ] C-Server restarted with Sepolia config
- [ ] USDT transferred to custody wallet
- [ ] Deposit detected in C-Server logs (within 30 sec)
- [ ] Wallet balance updated in Java Ledger
- [ ] Settlement created with new balance
- [ ] Settlement flow completed (PENDING → CONFIRMED)
- [ ] Database verified for all records
- [ ] RabbitMQ events verified in management console

---

## 🎯 Next Steps

1. **Complete Sepolia Testing**
   - Send test deposits
   - Verify detection and processing
   - Complete settlement flows

2. **Load Testing on Sepolia**
   - Send multiple deposits
   - Concurrent settlement processing
   - Verify system stability

3. **Production Preparation**
   - Deploy monitoring dashboards
   - Configure production RPC endpoints
   - Setup wallet key management
   - Implement backup systems

4. **Production Deployment**
   - Deploy to production with real mainnet
   - Monitor active deposits
   - Enable withdrawal functionality
   - Setup compliance checks

---

**Last Updated**: 2026-06-05  
**Status**: Ready for Sepolia Testing  
**Next Review**: After successful Sepolia test completion
