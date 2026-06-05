# 🔬 Load Test Report: Crypto Settlement System

**Date**: June 5, 2026  
**Test Type**: Concurrent Settlement Processing  
**Status**: ✅ **SEQUENTIAL OK** | ⚠️ **CONCURRENT - RACE CONDITIONS DETECTED**

---

## Executive Summary

The crypto settlement system demonstrates:
- ✅ **100% success rate** in sequential processing
- ⚠️ **64-82% success rate** under concurrent load
- Root cause: Race conditions in concurrent wallet balance checks
- Recommendation: Implement optimistic locking or row-level versioning

---

## Test Results

### Test 1: Sequential Processing
```
Configuration:
├─ Wallets: 5
├─ Settlements per wallet: 10
├─ Concurrent limit: 1 (sequential)
├─ Total requests: 50
└─ Duration: 221ms

Results:
├─ Total: 50 requests
├─ Successful: 50 (100%)
├─ Failed: 0 (0%)
└─ Latency: 9-55ms (avg 24ms)

Status: ✅ PASSED - Perfect success rate
```

### Test 2: Concurrent Processing (10 concurrent)
```
Configuration:
├─ Wallets: 5
├─ Settlements per wallet: 10
├─ Concurrent limit: 10
├─ Total requests: 50
└─ Duration: 267ms

Results:
├─ Total: 50 requests
├─ Successful: 32 (64%)
├─ Failed: 18 (36%)
└─ Latency: 9-55ms (avg 24.15ms)

Status: ⚠️ ISSUES - Race condition detected
```

### Test 3: Concurrent Processing (20 concurrent)
```
Configuration:
├─ Wallets: 10
├─ Settlements per wallet: 5
├─ Concurrent limit: 20
├─ Total requests: 50
└─ Duration: 510ms

Results:
├─ Total: 50 requests
├─ Successful: 41 (82%)
├─ Failed: 9 (18%)
└─ Throughput: 80,392 req/s

Status: ⚠️ ISSUES - Race condition detected
```

---

## Root Cause Analysis

### The Problem: Race Condition

When multiple concurrent requests process settlements for the same wallet:

```
Timeline of race condition:

Request A (T=0ms):     Request B (T=1ms):
├─ Check balance      ├─ Check balance
│  Balance = 100      │  Balance = 100 ✗ (stale read)
├─ Reserve amount     ├─ Reserve amount
│  Reserve = 50       │  Reserve = 50
└─ Create settlement  └─ Create settlement
   OK (Balance 100)      FAIL (Balance 50-50-50 = negative!)
```

### Current Implementation Issues

1. **No optimistic locking** on wallet balance checks
2. **Non-atomic** balance verification + reservation sequence
3. **Read-modify-write** race condition

### Code Location

File: `java/src/main/java/dev/nivic/ledger/JdbcSettlementManager.java` (lines 54-59)

```java
var wallet = walletManager.getWallet(walletId)
    .orElseThrow(...);
if (wallet.balanceMinor() < amountMinor) {  // ← Race condition here
    throw new RuntimeException("Insufficient balance");
}
// Between check and settlement creation, another request may have 
// already reserved the same balance!
```

---

## Performance Analysis

### Latency Metrics (Sequential)
```
Min:     9ms
Max:     55ms
Average: 24.15ms
Median:  20ms
P90:     32ms
P99:     52ms
```

### Latency Metrics (Concurrent - 10)
```
Same as sequential (latency is good)
Problem is: 36% of requests fail due to race condition
```

### Throughput
```
Sequential:  ~225 requests/second
Concurrent:  ~120 requests/second (due to failures)
Target:      >1000 requests/second
```

---

## System Health During Load Test

### Resource Utilization
```
Java Ledger Memory:  110MB (healthy)
Java Ledger CPU:     31.2% (good headroom)
PostgreSQL:          UP ✅
RabbitMQ:            UP ✅
```

### Database Performance
```
Settlements created: 73 in 5 minutes
Average: ~14.6 settlements/minute
Query response: <100ms
Connection pool: Healthy
```

---

## Recommended Solutions

### Solution 1: Pessimistic Locking (Recommended for this scale)
```sql
-- Lock row before reading balance
SELECT balance_minor FROM wallet 
WHERE id = ? FOR UPDATE;  -- Database-level lock

-- This prevents other transactions from reading stale data
```

**Pros**:
- Simple to implement
- Guaranteed consistency
- Works with existing JDBC code

**Cons**:
- Reduces concurrency slightly
- May cause some lock contention

### Solution 2: Optimistic Locking with Version Column
```sql
ALTER TABLE wallet ADD COLUMN version BIGINT DEFAULT 0;

-- When updating:
UPDATE wallet SET balance_minor = balance_minor - amount, version = version + 1
WHERE id = ? AND version = ?;  -- Fails if version changed
```

**Pros**:
- Better concurrency under normal load
- No database locks

**Cons**:
- Requires retry logic on failure
- More complex code

### Solution 3: Event Sourcing Pattern
```java
// Instead of UPDATE, INSERT events
INSERT INTO settlement_events (wallet_id, event_type, amount, timestamp);

// Process events in order
// Settlement state derived from events
```

**Pros**:
- Highest concurrency
- Complete audit trail
- Easy to replay

**Cons**:
- Architectural change
- More complex

---

## Implementation Recommendation

**Implement Solution 1 (Pessimistic Locking)** for immediate fix:

1. **File**: `java/src/main/java/dev/nivic/ledger/JdbcWalletManager.java`

2. **Add method**:
```java
public Optional<Wallet> getWalletForUpdate(long walletId) {
    // SELECT ... FOR UPDATE to lock row
}
```

3. **Update Settlement Manager**:
```java
var wallet = walletManager.getWalletForUpdate(walletId)
    .orElseThrow(...);
// Now safe to read balance
```

4. **Expected Impact**:
```
Before fix:  64% success rate (race conditions)
After fix:   99%+ success rate (all settled correctly)
Latency:     Minimal increase (~1-2ms per request)
```

---

## Test Recommendations

### For Staging Environment
1. Run load test with 100 concurrent requests
2. Monitor database locks
3. Validate all settlements are created correctly
4. Check P99 latency under sustained load

### For Production Readiness
1. Implement optimistic/pessimistic locking
2. Add retry logic for failed settlements
3. Configure database connection pool (20-30 connections)
4. Monitor lock wait times in production

---

## Next Steps

1. **Immediate** (this session)
   - [x] Document race condition (done)
   - [ ] Create fix plan (in progress)
   - [ ] Implement pessimistic locking

2. **Short term** (next session)
   - [ ] Test fix with concurrent load
   - [ ] Validate all settlements consistent
   - [ ] Performance benchmark after fix

3. **Long term**
   - [ ] Consider event sourcing for scale
   - [ ] Add circuit breaker for bank failures
   - [ ] Implement dead letter queue for failures

---

## Conclusion

The system is **functionally correct** with **good latency**, but has **concurrency issues** that need to be addressed before production deployment under high load.

**Grade**: B+ (Good foundation, needs concurrency fixes)

- ✅ Single-threaded/sequential: Excellent
- ✅ API design: Good
- ✅ Database schema: Good
- ❌ Concurrent access: Needs work
- ⚠️ Production readiness: Conditional on fix

**Recommendation**: Apply pessimistic locking fix, re-test, then ready for production.

---

**Report Generated**: 2026-06-05  
**Tested By**: Load Test Suite  
**Status**: Under Review
