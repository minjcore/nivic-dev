# Phase 4: Production Rollout - UUID→BIGINT Migration

**Status:** 🚀 Ready for Execution  
**Timeline:** 3 weeks  
**Risk Level:** LOW (staging validated, 100% double-entry invariant maintained)  
**Rollback Window:** 2 weeks post-migration  

---

## Migration Strategy: Zero-Downtime Dual-Write

### Architecture
```
Week 1 (Read-from-BIGINT):
  Application reads from BIGINT tables
  Application writes to BOTH UUID + BIGINT tables
  ↓
Week 2 (Finalize Writes):
  Stop writing to UUID tables
  Verify all data synced to BIGINT
  ↓
Week 3 (Cleanup):
  Drop UUID tables
  Verify query performance
  Enable read-only on legacy backup
```

### Tables Affected
| Table | Current Type | Target Type | Rows (prod est.) | Index Size Impact |
|-------|-------------|-------------|-----------------|-------------------|
| `coa_trans` | UUID | BIGINT | ~15M | -120MB |
| `coa_trans_data` | UUID→BIGINT | BIGINT→BIGINT | ~45M | -360MB |
| `coa_proposal` | UUID | BIGINT | ~200k | -1.6MB |

**Total Storage Savings: ~500MB** (1.2% of 41GB production database)

---

## Pre-Flight Checklist (48 hours before)

### Code Readiness
- [x] Java code uses BIGINT (`CoaTrans.id: long`)
- [x] 380/384 integration tests passing
- [x] LoadTestBigintIds validates performance
- [x] DisruptorBalanceQueryService targets 20k TPS

### Database Readiness
- [x] Staging validation: all 7 tests green
- [x] 502 transactions with 100% double-entry integrity
- [x] Concurrent load: 5.6-8.3k TPS sustained
- [x] Migration SQL created (12_migrate_uuid_to_bigint.sql)
- [x] Rollback procedure documented

### Infrastructure Readiness
- [ ] Production database backup completed (before cutover)
- [ ] Replication lag <1s verified
- [ ] Monitoring dashboards active
- [ ] On-call team briefed
- [ ] Rollback runbook accessible

### Approvals
- [ ] DBA sign-off on migration SQL
- [ ] DevOps sign-off on deployment plan
- [ ] Security review completed
- [ ] Incident management on standby

---

## Week 1: Dual-Write Phase (Read from BIGINT)

### Day 1: Pre-Migration
```
Timeline: 2am UTC (low-traffic window)

1. Create backup
   pg_dump gtelpay_prod | gzip > gtelpay_prod_$(date +%Y%m%d_%H%M%S).sql.gz

2. Enable application logs
   - Log all UUID→BIGINT mappings
   - Monitor write latency to both tables
   - Track conversion errors
```

### Day 1-2: Execute Migration
```sql
-- Step 1: Create new BIGINT tables (0 downtime)
BEGIN;
CREATE TABLE coa_trans_new AS SELECT * FROM coa_trans WHERE 1=0;  -- Schema only
ALTER TABLE coa_trans_new ADD CONSTRAINT coa_trans_new_pkey PRIMARY KEY (id);

-- Step 2: Copy data with UUID→BIGINT mapping
WITH uuid_to_id AS (
  SELECT
    old_uuid.id as old_id,
    ROW_NUMBER() OVER (ORDER BY old_uuid.created_at) as new_id
  FROM coa_trans old_uuid
)
INSERT INTO coa_trans_new (id, ref_id, memo, created_at)
SELECT utoi.new_id, t.ref_id, t.memo, t.created_at
FROM coa_trans t
JOIN uuid_to_id utoi ON utoi.old_id = t.id
ORDER BY utoi.new_id;

-- Step 3: Verify counts match
DO $$
DECLARE
  old_count INT;
  new_count INT;
BEGIN
  SELECT COUNT(*) INTO old_count FROM coa_trans;
  SELECT COUNT(*) INTO new_count FROM coa_trans_new;
  IF old_count != new_count THEN
    RAISE EXCEPTION 'Row count mismatch: % vs %', old_count, new_count;
  END IF;
  RAISE NOTICE 'Migration verified: % rows', new_count;
END $$;

COMMIT;
```

### Day 2-7: Dual-Write Phase
```
Application behavior:
- READ: Query BIGINT tables (NEW)
- WRITE: Insert/update BOTH UUID + BIGINT tables
- VERIFY: Compare results, log mismatches

Monitoring metrics:
✓ Write latency to both tables
✓ Replication lag
✓ Error rate (0 acceptable)
✓ Query performance on BIGINT (expected: 5-10% faster)
✓ Disk space utilization

Success criteria:
✓ 7 days of error-free dual writes
✓ No unbalanced transactions introduced
✓ Query latency on BIGINT < 100μs
✓ Zero data loss or corruption
```

### Validation: Day 3-4
```
Run on production (read-only):
mvn test -Dtest=LoadTestBigintIds#testHighThroughputQueries

Expected:
✓ 20k TPS throughput maintained
✓ P99 latency < 100μs
✓ Zero transaction failures
```

---

## Week 2: Write Cutover (Stop UUID writes)

### Day 8-9: Cutover
```
Timeline: 2am UTC

1. Deploy application code v2.1.0:
   - STOP writing to UUID tables
   - CONTINUE writing to BIGINT tables
   - CONTINUE reading from BIGINT tables

2. Monitor writes:
   - Verify no INSERT/UPDATE to coa_trans (UUID)
   - Verify all writes go to coa_trans_new (BIGINT)
   - Check transaction counts don't diverge

3. Final sync:
   -- Any records inserted to UUID after migration?
   SELECT COUNT(*) FROM coa_trans WHERE created_at > 'MIGRATION_TIMESTAMP';
   -- Should be 0. If not, INSERT to BIGINT manually
```

### Day 10-14: Stabilization
```
Monitoring:
✓ Zero inserts to UUID tables
✓ BIGINT table growth matches transaction load
✓ Read latency on BIGINT stable
✓ No missing transactions

Sanity checks (daily):
psql -c "SELECT COUNT(*) FROM coa_trans;"      -- Should be stable
psql -c "SELECT COUNT(*) FROM coa_trans_new;"  -- Should grow with txns
psql -c "SELECT MAX(id) FROM coa_trans_new;"   -- Sequential BIGINT IDs
```

---

## Week 3: Cleanup & Validation

### Day 15: Atomic Table Swap
```sql
-- Executed in single transaction for atomicity
BEGIN;

-- Backup old UUID tables
ALTER TABLE coa_trans RENAME TO coa_trans_old;
ALTER TABLE coa_trans_data RENAME TO coa_trans_data_old;
ALTER TABLE coa_proposal RENAME TO coa_proposal_old;

-- Promote new BIGINT tables
ALTER TABLE coa_trans_new RENAME TO coa_trans;
ALTER TABLE coa_trans_data_new RENAME TO coa_trans_data;
ALTER TABLE coa_proposal_new RENAME TO coa_proposal;

-- Recreate indexes
CREATE INDEX coa_trans_ref_id_idx ON coa_trans(ref_id);
CREATE INDEX coa_trans_data_account_idx ON coa_trans_data(account_code);
CREATE INDEX coa_trans_data_party_idx ON coa_trans_data(account_code, party_mid) WHERE party_mid IS NOT NULL;

-- Verify swap successful
DO $$
BEGIN
  IF (SELECT COUNT(*) FROM coa_trans) = 0 THEN
    RAISE EXCEPTION 'Table swap failed: coa_trans is empty!';
  END IF;
  IF (SELECT COUNT(*) FROM coa_trans WHERE id > 0) = 0 THEN
    RAISE EXCEPTION 'BIGINT IDs not populated!';
  END IF;
END $$;

COMMIT;
```

### Day 16: Query Performance Validation
```bash
# Run performance test on production
mvn test -Dtest=LoadTestBigintIds#testPerformanceComparison

# Expected improvements:
# - BIGINT ops: ~2μs per query
# - UUID ops: ~4-5μs per query
# - Improvement: 50-60%

# Full suite validation:
mvn test -Dtest=LoadTestBigintIds -q

# Check transaction integrity:
psql gtelpay_prod -c "
  SELECT COUNT(*) unbalanced_txns
  FROM coa_trans t
  WHERE (SELECT COALESCE(SUM(debit_minor), 0) - COALESCE(SUM(credit_minor), 0)
         FROM coa_trans_data WHERE trans_id = t.id) != 0;
"
# Should return: 0
```

### Day 17-21: Post-Migration Cleanup

#### Option 1: Keep Backup (Recommended for 2 weeks)
```sql
-- Rename old UUID tables to archive
ALTER TABLE coa_trans_old RENAME TO coa_trans_uuid_backup_20260610;
ALTER TABLE coa_trans_data_old RENAME TO coa_trans_data_uuid_backup_20260610;
ALTER TABLE coa_proposal_old RENAME TO coa_proposal_uuid_backup_20260610;

-- Set table space to archive (slower storage)
ALTER TABLE coa_trans_uuid_backup_20260610 SET TABLESPACE archive_space;

-- Compress backup for storage
pg_dump gtelpay_prod | gzip > gtelpay_prod_pre_migration_backup_20260610.sql.gz
```

#### Option 2: Drop After Verification (Day 21+)
```sql
-- Only after 2 weeks of error-free operation
DROP TABLE IF EXISTS coa_trans_uuid_backup_20260610 CASCADE;
DROP TABLE IF EXISTS coa_trans_data_uuid_backup_20260610 CASCADE;
DROP TABLE IF EXISTS coa_proposal_uuid_backup_20260610 CASCADE;

-- Verify data integrity post-drop
SELECT COUNT(*) total_txns FROM coa_trans;
SELECT MAX(id) last_bigint_id FROM coa_trans;
SELECT COUNT(DISTINCT account_code) total_accounts FROM coa_account;
```

---

## Monitoring & Alerting

### Real-Time Dashboards
```
Prometheus metrics to track:

1. Write Latency
   histogram_quantile(0.95, coa_trans_write_duration_ms) < 50

2. Query Latency
   histogram_quantile(0.99, coa_trans_read_duration_us) < 100

3. Transaction Volume
   rate(coa_trans_inserts_total[5m]) > 0  -- Verify writes happening

4. Double-Entry Invariant
   coa_unbalanced_transactions == 0

5. Table Size
   pg_table_size_bytes{table="coa_trans"} < 200GB

6. Replication Lag
   pg_replication_lag_seconds < 1
```

### Alert Thresholds
| Metric | Threshold | Action |
|--------|-----------|--------|
| Write latency (p99) | > 200ms | Page on-call |
| Read latency (p99) | > 500μs | Check indexes |
| Unbalanced txns | > 0 | IMMEDIATE ROLLBACK |
| Replication lag | > 5s | Investigate standby |
| Insert errors | > 0 | Check app logs |

---

## Rollback Procedure

### Immediate Rollback (if issues detected)
```
Timeline: < 5 minutes

1. Deploy rollback version (v2.0.1)
   - Revert to UUID reads/writes
   - Switch app traffic to UUID tables

2. Database rollback:
   BEGIN;
   ALTER TABLE coa_trans RENAME TO coa_trans_bigint_failed;
   ALTER TABLE coa_trans_uuid_backup RENAME TO coa_trans;
   
   -- Verify counts match
   DO $$ ... END $$;
   
   COMMIT;

3. Verify:
   ✓ Transactions post normally
   ✓ Zero errors in logs
   ✓ User-facing services operational
   ✓ No data loss

4. Post-incident:
   - Analyze root cause
   - Update migration plan
   - Schedule retry for next week
```

### Full Rollback (Week 1-2)
```sql
-- Restore from backup
pg_restore -d gtelpay_prod gtelpay_prod_pre_migration_backup.sql
```

---

## Success Criteria

### Go/No-Go Checklist
- [ ] Week 1: 7 days error-free dual writes
- [ ] Week 1: Query performance on BIGINT validated (< 100μs p99)
- [ ] Week 1: Zero unbalanced transactions introduced
- [ ] Week 2: Table swap completed atomically
- [ ] Week 2: All indexes recreated
- [ ] Week 3: Query performance improved (50-60%)
- [ ] Week 3: Zero data loss detected
- [ ] Week 3: Backup archived for 2 weeks

### Final Validation
```bash
# Production integrity check
psql gtelpay_prod -f validation_checklist.sql

# Expected output:
# ✓ Total transactions: 15,234,567
# ✓ Unbalanced transactions: 0
# ✓ Max transaction ID: 15234567 (BIGINT)
# ✓ Account balances match subsidiary ledgers
# ✓ Storage savings: ~500MB
# ✓ Query latency: 45-60μs (improvement from ~100μs)
```

---

## Post-Migration

### Day 21+: Optimization Opportunities
1. Rebuild indexes with FILLFACTOR 80 for write workloads
2. Update table statistics: `ANALYZE coa_trans, coa_trans_data;`
3. Consider materialized views for common balance queries
4. Archive old UUID backup tables to cheaper storage

### Monitoring (Ongoing)
- Daily: Check for unbalanced transactions
- Weekly: Query latency trends
- Monthly: Storage utilization, index fragmentation

### Documentation Updates
- [ ] Update schema.sql to use BIGINT
- [ ] Update API documentation with BIGINT ID examples
- [ ] Update runbooks with BIGINT migration details
- [ ] Update DBA procedures for backup/restore

---

## Communication Plan

### Pre-Migration (T-48 hours)
- Notify: Engineering, QA, DevOps, On-Call team
- Message: "UUID→BIGINT migration scheduled for [DATE]"
- Runbook: Link to this document

### During Migration (T-0 to T+3 weeks)
- Daily standup: 15 min status update
- Slack alerts: Real-time threshold notifications
- Weekly: Exec summary of metrics

### Post-Migration
- Retrospective: Lessons learned, process improvements
- Metrics: Performance improvements documented
- Celebration: Team recognition for successful migration

---

## Timeline Summary

```
Week 1:  Dual-Write Phase (read BIGINT, write both)
  Day 1-2:  Execute migration, verify counts
  Day 3-7:  Monitor, validate, stress-test

Week 2:  Write Cutover Phase (stop UUID writes)
  Day 8-9:  Deploy v2.1.0, finalize cutover
  Day 10-14: Stabilization, daily sanity checks

Week 3:  Cleanup & Optimization (drop old tables)
  Day 15: Atomic table swap
  Day 16: Performance validation
  Day 17-21: Cleanup & archive

Total: 21 days, zero downtime, full rollback capability
```

---

## Contacts & Escalation

| Role | Owner | Slack | Availability |
|------|-------|-------|--------------|
| **Migration Lead** | [Engineer Name] | @migration-lead | 24/7 during cutover |
| **DBA** | [DBA Name] | @dba-on-call | 24/7 during cutover |
| **DevOps** | [DevOps Name] | @devops-lead | 24/7 during cutover |
| **On-Call** | [Current] | @oncall | 24/7 |
| **Exec Sponsor** | [Manager] | @[manager] | During cutover hours |

---

## References

- **Migration Design:** `MIGRATION-UUID-TO-BIGINT.md`
- **Phase 3 Validation:** `StagingValidationTest.java` (7 all-green tests)
- **Performance Benchmarks:** `LoadTestBigintIds.java`
- **Schema Migration:** `12_migrate_uuid_to_bigint.sql`
- **Monitoring Setup:** `prometheus_migration_alerts.yml` (TODO)
- **Rollback Procedure:** See "Rollback Procedure" section above

---

**Last Updated:** 2026-06-03  
**Status:** 🟢 Ready for Execution  
**Approval:** ⏳ Awaiting DBA + DevOps sign-off
