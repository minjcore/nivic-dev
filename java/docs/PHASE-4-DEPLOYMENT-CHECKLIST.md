# Phase 4 - Deployment Checklist

**Migration Date:** [TBD]  
**Migration Window:** 2:00 AM UTC (Low traffic)  
**Estimated Duration:** 2-4 hours for execution + 3 weeks for gradual rollout  
**Rollback Window:** 2 weeks post-migration  

---

## Pre-Deployment (T-48 hours)

### Infrastructure Checks
- [ ] Database backups completed and tested
  - Command: `pg_dump gtelpay_prod | gzip > backup_$(date +%Y%m%d).sql.gz`
  - Verify: `gunzip -t backup_*.sql.gz` returns 0
  - Location: Documented in incident tracking system

- [ ] Replication lag verified < 1 second
  - Command: `SELECT NOW() - pg_last_xact_replay_timestamp();`
  - Expected: < 1s
  - Standby prepared for cutover

- [ ] Disk space available for migration
  - Requirement: ~500MB for BIGINT tables
  - Current free space: `df -h /var/lib/postgresql`
  - Result: ✅ / ❌

### Application Readiness
- [ ] Code version v2.0.2 deployed to production (UUID IDs)
  - Verify: `SELECT version_key FROM app_version;`
  - Expected: `2.0.2-uuid`

- [ ] Feature flag ready (if using)
  - Flag name: `ENABLE_BIGINT_MIGRATION`
  - Current status: `OFF` (UUID only)
  - Verified in: Feature flag dashboard

- [ ] Monitoring dashboards active
  - [ ] Transaction volume graph
  - [ ] Query latency (p50, p95, p99)
  - [ ] Replication lag
  - [ ] Error rate dashboard
  - [ ] Disk usage trending

### Team Readiness
- [ ] On-call engineer briefed
  - Name: _________________
  - Phone verified: _______________
  - Slack: @_________________

- [ ] DBA on standby
  - Name: _________________
  - Phone verified: _______________
  - Slack: @_________________

- [ ] DevOps on standby
  - Name: _________________
  - Phone verified: _______________
  - Slack: @_________________

- [ ] Rollback runbook reviewed
  - File: `PHASE-4-PRODUCTION-ROLLOUT.md`
  - Reviewed by: _________________
  - Date: _________________

- [ ] Communication plan activated
  - Slack channel: #uuid-to-bigint-migration
  - Status updates scheduled: Hourly during execution
  - Escalation path documented

---

## Week 1: Dual-Write Phase

### Day 1: Pre-Migration Setup (T-24 hours before cutover)

#### Backup
- [ ] Full database backup
  ```bash
  pg_dump gtelpay_prod | gzip > gtelpay_prod_$(date +%Y%m%d_%H%M%S).sql.gz
  ```
  - Size: _________________ MB
  - Checksum: _________________
  - Verified restore: ✅ / ❌

#### Schema Preparation
- [ ] Enable logging for UUID→BIGINT conversions
  ```sql
  ALTER SYSTEM SET log_statement = 'all';
  SELECT pg_reload_conf();
  ```

- [ ] Create new BIGINT tables (schema only, no data yet)
  ```sql
  CREATE TABLE coa_trans_new (...);  -- See migration SQL
  CREATE TABLE coa_trans_data_new (...);
  CREATE TABLE coa_proposal_new (...);
  ```
  - Executed by: _________________
  - Time: _________________
  - Status: ✅ / ❌

#### Monitoring Setup
- [ ] Prometheus scrape config updated
  - Include coa_* tables in metrics
  - Interval: 15 seconds

- [ ] Alert rules deployed
  - unbalanced_transactions > 0 → CRITICAL
  - write_latency_p99 > 200ms → WARNING
  - replication_lag > 5s → CRITICAL
  - Verified: ✅ / ❌

### Day 1: Execute Migration (2:00 AM UTC)

#### Pre-Execution
- [ ] Ensure low traffic window
  - Check transaction rate: _________________ TPS
  - Expected: < 100 TPS in maintenance window
  
- [ ] Verify all connections to replica are closed
  ```sql
  SELECT COUNT(*) FROM pg_stat_activity WHERE datname = 'gtelpay_prod';
  ```
  - Result: 0 (or only admin connections)
  - Verified by: _________________

#### Execute Migration SQL
- [ ] Run migration in transaction
  ```bash
  # Step 1: Copy data with UUID→BIGINT mapping
  psql gtelpay_prod < /path/to/12_migrate_uuid_to_bigint.sql
  ```
  - Start time: _________________
  - End time: _________________
  - Duration: _________________ min
  - Status: ✅ / ❌

- [ ] Verify row counts match
  ```sql
  SELECT COUNT(*) FROM coa_trans;      -- Should match backup count
  SELECT COUNT(*) FROM coa_trans_new;
  SELECT COUNT(*) FROM coa_trans_data;
  SELECT COUNT(*) FROM coa_trans_data_new;
  ```
  - coa_trans: _____________ → _____________ rows
  - coa_trans_data: _____________ → _____________ rows
  - Match: ✅ / ❌

#### Post-Migration Validation
- [ ] Run validation checklist
  ```bash
  psql gtelpay_prod < phase4_validation_checklist.sql
  ```
  - All checks ✅ PASS: ✅ / ❌
  - Issues found: _________________ 
  - Resolution: _________________

- [ ] Verify indexes created
  ```sql
  SELECT COUNT(*) FROM pg_indexes WHERE tablename LIKE 'coa_%';
  ```
  - Expected: 5+ indexes
  - Actual: _________________
  - Status: ✅ / ❌

### Days 2-7: Dual-Write Phase Monitoring

#### Daily Monitoring (8:00 AM UTC)
- **Day 2:**
  - [ ] Write latency to both tables: p99 < 50ms
  - [ ] Error rate: 0
  - [ ] Unbalanced transactions: 0
  - [ ] Replication lag: < 1s
  - Verified by: _________________
  - Time: _________________

- **Day 3:**
  - [ ] Write latency: p99 < 50ms
  - [ ] Error rate: 0
  - [ ] Unbalanced transactions: 0
  - [ ] Replication lag: < 1s
  - Verified by: _________________
  - Time: _________________

- **Day 4:**
  - [ ] Write latency: p99 < 50ms
  - [ ] Error rate: 0
  - [ ] Unbalanced transactions: 0
  - [ ] Replication lag: < 1s
  - Verified by: _________________
  - Time: _________________

- **Day 5:**
  - [ ] Write latency: p99 < 50ms
  - [ ] Error rate: 0
  - [ ] Unbalanced transactions: 0
  - [ ] Replication lag: < 1s
  - Verified by: _________________
  - Time: _________________

- **Day 6:**
  - [ ] Write latency: p99 < 50ms
  - [ ] Error rate: 0
  - [ ] Unbalanced transactions: 0
  - [ ] Replication lag: < 1s
  - Verified by: _________________
  - Time: _________________

- **Day 7:**
  - [ ] Write latency: p99 < 50ms
  - [ ] Error rate: 0
  - [ ] Unbalanced transactions: 0
  - [ ] Replication lag: < 1s
  - Verified by: _________________
  - Time: _________________

#### Query Performance Test (Day 3-4)
- [ ] Run `LoadTestBigintIds` on production replica
  ```bash
  mvn test -Dtest=LoadTestBigintIds#testHighThroughputQueries
  ```
  - Throughput: _________________ TPS (Target: > 20k)
  - P99 latency: _________________ μs (Target: < 100μs)
  - Errors: 0
  - Status: ✅ / ❌

#### Go/No-Go Decision (End of Day 7)
- [ ] All daily checks passed: ✅ / ❌
- [ ] No unbalanced transactions: ✅ / ❌
- [ ] Query performance validated: ✅ / ❌
- [ ] Team consensus: ✅ / ❌

**Decision: ✅ GO TO WEEK 2** / ❌ ROLLBACK

---

## Week 2: Write Cutover Phase

### Day 8-9: Cutover Deployment

#### Pre-Deployment
- [ ] Code review for v2.1.0 (stop UUID writes)
  - Reviewed by: _________________
  - Approved by: _________________
  - Date: _________________

- [ ] Staged deployment to canary (5% traffic)
  - Canary version: v2.1.0-rc1
  - Duration: 1 hour
  - Error rate threshold: < 0.1%
  - Status: ✅ / ❌

#### Day 8: Deploy v2.1.0 (Stop UUID writes)
- [ ] Deploy v2.1.0 to 50% of production
  - Time: _________________
  - Instances updated: _____ / _____
  - Errors: _________________
  - Status: ✅ / ❌

- [ ] Monitor writes to UUID table
  ```sql
  SELECT COUNT(*) FROM coa_trans WHERE created_at > NOW() - INTERVAL '5 min';
  ```
  - Should be 0 after cutover
  - Verified at: _________________
  - Result: ✅ / ❌

- [ ] Monitor writes to BIGINT table
  ```sql
  SELECT COUNT(*) FROM coa_trans_new WHERE created_at > NOW() - INTERVAL '5 min';
  ```
  - Should be > 0 (normal transaction load)
  - Verified at: _________________
  - Result: ✅ / ❌

#### Day 9: Full Rollout v2.1.0
- [ ] Deploy v2.1.0 to 100% of production
  - Time: _________________
  - All instances updated: ✅ / ❌
  - Status page updated: ✅ / ❌

- [ ] Verify all writes go to BIGINT only
  - Checked at: _________________
  - UUID writes: 0
  - BIGINT writes: > 0
  - Status: ✅ / ❌

### Days 10-14: Cutover Stabilization

#### Daily Sanity Checks
- **Day 10:**
  - [ ] Zero inserts to coa_trans (UUID): `SELECT COUNT(*) FROM coa_trans WHERE created_at > CUTOVER_TIME;` → 0
  - [ ] Inserts to coa_trans_new (BIGINT): > 0
  - [ ] No missing transactions
  - Verified by: _________________
  - Time: _________________

- **Day 11:**
  - [ ] Zero inserts to coa_trans (UUID)
  - [ ] Inserts to coa_trans_new (BIGINT): > 0
  - [ ] Read latency p99 < 100μs
  - Verified by: _________________
  - Time: _________________

- **Day 12:**
  - [ ] Zero inserts to coa_trans (UUID)
  - [ ] Inserts to coa_trans_new (BIGINT): > 0
  - [ ] No unbalanced transactions
  - Verified by: _________________
  - Time: _________________

- **Day 13:**
  - [ ] Zero inserts to coa_trans (UUID)
  - [ ] Inserts to coa_trans_new (BIGINT): > 0
  - [ ] Replication lag < 1s
  - Verified by: _________________
  - Time: _________________

- **Day 14:**
  - [ ] Zero inserts to coa_trans (UUID)
  - [ ] Inserts to coa_trans_new (BIGINT): > 0
  - [ ] All checks passing
  - Verified by: _________________
  - Time: _________________

#### Go/No-Go Decision (End of Day 14)
- [ ] All daily checks passed: ✅ / ❌
- [ ] Zero writes to UUID tables: ✅ / ❌
- [ ] BIGINT table growth normal: ✅ / ❌

**Decision: ✅ GO TO WEEK 3** / ❌ ROLLBACK

---

## Week 3: Cleanup & Optimization

### Day 15: Atomic Table Swap

#### Pre-Swap
- [ ] Final backup before swap
  ```bash
  pg_dump gtelpay_prod | gzip > backup_pre_swap_$(date +%Y%m%d).sql.gz
  ```
  - Size: _________________ MB
  - Verified: ✅ / ❌

- [ ] Notify team of swap window
  - Message sent to: #uuid-to-bigint-migration
  - Time: _________________

#### Execute Atomic Swap (2:00 AM UTC)
- [ ] Begin transaction
  ```sql
  BEGIN;
  ```

- [ ] Rename old UUID tables
  ```sql
  ALTER TABLE coa_trans RENAME TO coa_trans_uuid_backup;
  ALTER TABLE coa_trans_data RENAME TO coa_trans_data_uuid_backup;
  ALTER TABLE coa_proposal RENAME TO coa_proposal_uuid_backup;
  ```
  - Executed at: _________________
  - Status: ✅ / ❌

- [ ] Rename new BIGINT tables
  ```sql
  ALTER TABLE coa_trans_new RENAME TO coa_trans;
  ALTER TABLE coa_trans_data_new RENAME TO coa_trans_data;
  ALTER TABLE coa_proposal_new RENAME TO coa_proposal;
  ```
  - Executed at: _________________
  - Status: ✅ / ❌

- [ ] Recreate indexes
  ```sql
  CREATE INDEX coa_trans_ref_id_idx ON coa_trans(ref_id);
  CREATE INDEX coa_trans_data_account_idx ON coa_trans_data(account_code);
  CREATE INDEX coa_trans_data_party_idx ON coa_trans_data(account_code, party_mid) WHERE party_mid IS NOT NULL;
  ```
  - Executed at: _________________
  - Status: ✅ / ❌

- [ ] Run final validation
  ```sql
  -- Verify tables not empty
  IF (SELECT COUNT(*) FROM coa_trans) = 0 THEN
    RAISE EXCEPTION 'Table swap failed: coa_trans is empty!';
  END IF;
  ```
  - Validation passed: ✅ / ❌

- [ ] Commit transaction
  ```sql
  COMMIT;
  ```
  - Committed at: _________________
  - Duration: _________________ min
  - Status: ✅ / ❌

### Day 16: Performance Validation

#### Query Performance Test
- [ ] Run performance validation
  ```bash
  mvn test -Dtest=LoadTestBigintIds#testPerformanceComparison
  ```
  - BIGINT latency: _________________ μs
  - UUID latency (simulated): _________________ μs
  - Improvement: _________________ %
  - Status: ✅ / ❌

- [ ] Run full test suite
  ```bash
  mvn test -Dtest=LoadTestBigintIds -q
  ```
  - Total tests: _________
  - Passed: _________ (Target: 100%)
  - Status: ✅ / ❌

#### Query Verification
- [ ] Run validation checklist
  ```bash
  psql gtelpay_prod < phase4_validation_checklist.sql
  ```
  - All checks ✅ PASS: ✅ / ❌
  - Issues: _________________

- [ ] Verify query plans unchanged
  - Sample query: SELECT from coa_trans WHERE id = 1
  - Plan type: Index scan (should be BIGINT index)
  - Status: ✅ / ❌

### Days 17-21: Archive Old Tables

#### Day 17: Archive Planning
- [ ] Decide archival strategy
  - [ ] Option 1: Keep for 2 weeks (recommended)
  - [ ] Option 2: Drop immediately
  - Decision: _________________

#### Day 17-21: Keep Backup Tables (Recommended)
- [ ] Rename backup tables for archive
  ```sql
  ALTER TABLE coa_trans_uuid_backup RENAME TO coa_trans_uuid_archive_20260610;
  ALTER TABLE coa_trans_data_uuid_backup RENAME TO coa_trans_data_uuid_archive_20260610;
  ALTER TABLE coa_proposal_uuid_backup RENAME TO coa_proposal_uuid_archive_20260610;
  ```
  - Executed at: _________________
  - Status: ✅ / ❌

- [ ] Move to slower storage (if available)
  ```sql
  ALTER TABLE coa_trans_uuid_archive_20260610 SET TABLESPACE archive_space;
  ```
  - Storage class: _________________
  - Status: ✅ / ❌

- [ ] Document archival
  - Archive location: _________________
  - Retention date: 2026-06-24 (2 weeks)
  - Approved by: _________________

#### Day 21: Final Verification Before Cleanup
- [ ] Verify no issues in Week 3
  - Unbalanced transactions: 0
  - Write errors: 0
  - Query performance stable: ✅ / ❌
  - Replication lag: < 1s

**Go/No-Go: ✅ CLEANUP OK** / ❌ EXTEND RETENTION

#### Day 21+: Drop Old Tables (Optional)
- [ ] Final backup before drop
  ```bash
  pg_dump gtelpay_prod | gzip > backup_before_cleanup_$(date +%Y%m%d).sql.gz
  ```

- [ ] Drop archive tables (if decision = immediate drop)
  ```sql
  DROP TABLE IF EXISTS coa_trans_uuid_archive_20260610 CASCADE;
  DROP TABLE IF EXISTS coa_trans_data_uuid_archive_20260610 CASCADE;
  DROP TABLE IF EXISTS coa_proposal_uuid_archive_20260610 CASCADE;
  ```
  - Executed at: _________________
  - Status: ✅ / ❌

- [ ] Verify storage space recovered
  ```bash
  df -h /var/lib/postgresql
  ```
  - Space freed: _________________ MB
  - Status: ✅ / ❌

---

## Post-Migration

### Documentation Updates
- [ ] Update schema.sql to use BIGINT
  - File: `java/src/main/resources/db/schema.sql`
  - Updated by: _________________
  - Verified: ✅ / ❌

- [ ] Update migration guide
  - File: `MIGRATION-UUID-TO-BIGINT.md`
  - Mark Phase 1-4 as ✅ COMPLETE
  - Date: _________________

- [ ] Update API documentation
  - ID format: BIGINT (was UUID)
  - Example: `{ "id": 12345678, ... }`
  - Updated by: _________________

- [ ] Update runbooks
  - DBA procedures: Updated for BIGINT schema
  - Verified by: _________________

### Team Retrospective
- [ ] Schedule retrospective meeting
  - Date: _________________
  - Time: _________________
  - Attendees: _________________

- [ ] Document lessons learned
  - What went well: _________________________
  - What to improve: _________________________
  - Action items: _________________________

### Monitoring Handoff
- [ ] Update monitoring dashboards for ongoing tracking
  - Unbalanced transactions (daily check)
  - Query latency trends
  - Storage utilization

- [ ] Set up quarterly health checks
  - Frequency: Every 90 days
  - Owner: _________________
  - Last check: _________________

---

## Rollback Procedure (If Needed at Any Point)

### Immediate Rollback (< 5 minutes)
- [ ] Deploy v2.0.2 (revert to UUID code)
  - Deployed at: _________________
  - Status: ✅ / ❌

- [ ] Database rollback:
  ```sql
  BEGIN;
  ALTER TABLE coa_trans RENAME TO coa_trans_bigint_failed;
  ALTER TABLE coa_trans_uuid_backup RENAME TO coa_trans;
  COMMIT;
  ```
  - Executed at: _________________
  - Status: ✅ / ❌

- [ ] Verify services operational
  - Transactions posting: ✅ / ❌
  - No errors in logs: ✅ / ❌
  - User reports normal: ✅ / ❌

### Full Restore from Backup
- [ ] If rollback insufficient, restore from backup
  ```bash
  pg_restore -d gtelpay_prod backup_*.sql
  ```
  - Restore started: _________________
  - Restore completed: _________________
  - Duration: _________________ min
  - Status: ✅ / ❌

- [ ] Verify data consistency
  - Transaction count: _________________
  - Unbalanced txns: 0
  - Application errors: 0

---

## Final Sign-Off

**Migration Lead:** _________________  
**Date Completed:** _________________  
**Overall Status:** ✅ SUCCESS / ⚠️ PARTIAL / ❌ FAILED  

**Comments:**
_________________________________________________________________
_________________________________________________________________
_________________________________________________________________

---

**Approved by:**  
DBA: _________________________ Date: _________________  
DevOps: _________________________ Date: _________________  
Engineering: _________________________ Date: _________________  
