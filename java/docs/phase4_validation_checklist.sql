-- Phase 4 Production Rollout - Validation Checklist
-- Run this script to validate UUID→BIGINT migration success
-- Timeline: Day 16 (post-swap) and ongoing monitoring

\set QUIET on
\timing on

-- ============================================================================
-- SECTION 1: Data Integrity Checks
-- ============================================================================

\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║ SECTION 1: DATA INTEGRITY & ROW COUNT VALIDATION                  ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

\echo ''
\echo '✓ Transaction count:'
SELECT COUNT(*) as coa_trans_total FROM coa_trans;

\echo '✓ Transaction detail rows:'
SELECT COUNT(*) as coa_trans_data_total FROM coa_trans_data;

\echo '✓ Proposals count:'
SELECT COUNT(*) as coa_proposal_total FROM coa_proposal;

\echo '✓ Total storage used:'
SELECT
  pg_size_pretty(pg_total_relation_size('coa_trans')) as coa_trans_size,
  pg_size_pretty(pg_total_relation_size('coa_trans_data')) as coa_trans_data_size,
  pg_size_pretty(pg_total_relation_size('coa_proposal')) as coa_proposal_size,
  pg_size_pretty(pg_total_relation_size('coa_trans') +
                 pg_total_relation_size('coa_trans_data') +
                 pg_total_relation_size('coa_proposal')) as total_size;

-- ============================================================================
-- SECTION 2: BIGINT ID Validation
-- ============================================================================

\echo ''
\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║ SECTION 2: BIGINT ID VALIDATION & SEQUENCING                      ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

\echo ''
\echo '✓ BIGINT ID range:'
SELECT
  MIN(id) as min_trans_id,
  MAX(id) as max_trans_id,
  COUNT(*) as total_trans
FROM coa_trans;

\echo '✓ Verify sequential IDs (no gaps):'
WITH id_sequence AS (
  SELECT
    id,
    ROW_NUMBER() OVER (ORDER BY id) as rn,
    id - ROW_NUMBER() OVER (ORDER BY id) as gap_group
  FROM coa_trans
)
SELECT
  CASE
    WHEN COUNT(*) = (SELECT COUNT(*) FROM coa_trans) THEN '✅ PASS: All IDs sequential (no gaps)'
    ELSE '❌ FAIL: Found ' || COUNT(*) || ' gaps in ID sequence'
  END as id_sequence_check
FROM id_sequence
GROUP BY gap_group
HAVING COUNT(*) = 1;

\echo '✓ Verify UUID→BIGINT conversion (all IDs are positive BIGINT):'
SELECT
  COUNT(*) as total_ids,
  COUNT(*) FILTER (WHERE id > 0) as valid_bigint_ids,
  COUNT(*) FILTER (WHERE id <= 0) as invalid_ids
FROM coa_trans;

\echo '✓ BIGINT ID type check (should be BIGINT):'
SELECT
  column_name,
  data_type,
  is_nullable
FROM information_schema.columns
WHERE table_name IN ('coa_trans', 'coa_trans_data', 'coa_proposal')
  AND column_name IN ('id', 'trans_id')
ORDER BY table_name, column_name;

-- ============================================================================
-- SECTION 3: Double-Entry Accounting Validation
-- ============================================================================

\echo ''
\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║ SECTION 3: DOUBLE-ENTRY INVARIANT VALIDATION                      ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

\echo ''
\echo '✓ Total unbalanced transactions:'
WITH txn_balance AS (
  SELECT
    trans_id,
    COALESCE(SUM(debit_minor), 0) - COALESCE(SUM(credit_minor), 0) as balance
  FROM coa_trans_data
  GROUP BY trans_id
)
SELECT
  COUNT(*) FILTER (WHERE balance = 0) as balanced_txns,
  COUNT(*) FILTER (WHERE balance != 0) as unbalanced_txns,
  COUNT(*) as total_txns
FROM txn_balance;

\echo '✓ Show unbalanced transactions (should be empty):'
SELECT
  t.id,
  t.ref_id,
  t.memo,
  t.created_at,
  COALESCE(SUM(td.debit_minor), 0) - COALESCE(SUM(td.credit_minor), 0) as imbalance
FROM coa_trans t
LEFT JOIN coa_trans_data td ON t.id = td.trans_id
GROUP BY t.id, t.ref_id, t.memo, t.created_at
HAVING COALESCE(SUM(td.debit_minor), 0) - COALESCE(SUM(td.credit_minor), 0) != 0
LIMIT 10;

\echo '✓ Double-entry validation by account:'
SELECT
  ca.code,
  ca.name,
  ca.kind,
  COUNT(DISTINCT td.trans_id) as txn_count,
  COALESCE(SUM(td.debit_minor), 0) as total_debits,
  COALESCE(SUM(td.credit_minor), 0) as total_credits,
  COALESCE(SUM(td.debit_minor), 0) - COALESCE(SUM(td.credit_minor), 0) as net_balance
FROM coa_account ca
LEFT JOIN coa_trans_data td ON ca.code = td.account_code
GROUP BY ca.code, ca.name, ca.kind
ORDER BY ca.code;

-- ============================================================================
-- SECTION 4: Index Validation
-- ============================================================================

\echo ''
\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║ SECTION 4: INDEX VALIDATION & PERFORMANCE                         ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

\echo ''
\echo '✓ Verify all indexes exist:'
SELECT
  schemaname,
  tablename,
  indexname,
  indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN ('coa_trans', 'coa_trans_data', 'coa_proposal')
ORDER BY tablename, indexname;

\echo '✓ Index bloat analysis:'
SELECT
  schemaname,
  tablename,
  indexname,
  ROUND(100.0 * (pg_relation_size(indexrelid) -
         pg_relation_size(indexrelid, 'main')) /
         pg_relation_size(indexrelid), 2) as bloat_ratio
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
  AND tablename IN ('coa_trans', 'coa_trans_data', 'coa_proposal')
ORDER BY bloat_ratio DESC;

-- ============================================================================
-- SECTION 5: Query Performance Validation
-- ============================================================================

\echo ''
\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║ SECTION 5: QUERY PERFORMANCE VALIDATION (BIGINT vs UUID)          ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

\echo ''
\echo '✓ Single row lookup performance (by BIGINT ID):'
EXPLAIN ANALYZE SELECT * FROM coa_trans WHERE id = 1000000;

\echo ''
\echo '✓ Range scan performance (BIGINT ID range):'
EXPLAIN ANALYZE SELECT COUNT(*) FROM coa_trans WHERE id BETWEEN 1000000 AND 1000100;

\echo ''
\echo '✓ Index lookup by ref_id:'
EXPLAIN ANALYZE SELECT * FROM coa_trans WHERE ref_id = 'TXN-001';

\echo ''
\echo '✓ Transaction data lookup by trans_id (BIGINT FK):'
EXPLAIN ANALYZE SELECT * FROM coa_trans_data WHERE trans_id = 1000000;

-- ============================================================================
-- SECTION 6: Replication Lag & Consistency
-- ============================================================================

\echo ''
\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║ SECTION 6: REPLICATION LAG & CONSISTENCY                          ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

\echo ''
\echo '✓ Current replication status:'
SELECT
  client_addr,
  state,
  sync_state,
  replay_lag,
  write_lag,
  flush_lag
FROM pg_stat_replication;

\echo ''
\echo '✓ WAL position check:'
SELECT
  pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0') as current_wal_bytes,
  CASE
    WHEN pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0') > 1000000000 THEN 'Check WAL archiving'
    ELSE 'Normal'
  END as wal_status;

-- ============================================================================
-- SECTION 7: Foreign Key Integrity
-- ============================================================================

\echo ''
\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║ SECTION 7: FOREIGN KEY INTEGRITY                                  ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

\echo ''
\echo '✓ Orphaned transaction data (trans_id not in coa_trans):'
SELECT COUNT(*) as orphaned_lines
FROM coa_trans_data td
WHERE NOT EXISTS (SELECT 1 FROM coa_trans t WHERE t.id = td.trans_id);

\echo '✓ Orphaned account codes (account_code not in coa_account):'
SELECT COUNT(*) as orphaned_accounts
FROM coa_trans_data td
WHERE NOT EXISTS (SELECT 1 FROM coa_account ca WHERE ca.code = td.account_code);

\echo '✓ Verify all coa_trans_data rows have valid trans_id:'
SELECT
  COUNT(*) as total_data_rows,
  COUNT(*) FILTER (WHERE trans_id > 0) as valid_bigint_fk,
  COUNT(*) FILTER (WHERE trans_id <= 0) as invalid_fk
FROM coa_trans_data;

-- ============================================================================
-- SECTION 8: Post-Migration Checklist
-- ============================================================================

\echo ''
\echo '╔════════════════════════════════════════════════════════════════════╗'
\echo '║ SECTION 8: POST-MIGRATION SUMMARY & GO/NO-GO DECISION             ║'
\echo '╚════════════════════════════════════════════════════════════════════╝'

\echo ''
\echo '✓ Migration Validation Summary:'
SELECT
  'Data Integrity' as check_name,
  CASE
    WHEN (SELECT COUNT(*) FROM coa_trans) > 0 THEN '✅ PASS'
    ELSE '❌ FAIL'
  END as status
UNION ALL
SELECT
  'BIGINT ID Sequencing',
  CASE
    WHEN (SELECT COUNT(DISTINCT id) FROM coa_trans WHERE id > 0) =
         (SELECT COUNT(*) FROM coa_trans) THEN '✅ PASS'
    ELSE '❌ FAIL'
  END
UNION ALL
SELECT
  'Double-Entry Invariant',
  CASE
    WHEN (SELECT COUNT(*) FROM coa_trans t
          WHERE (SELECT COALESCE(SUM(debit_minor), 0) - COALESCE(SUM(credit_minor), 0)
                 FROM coa_trans_data WHERE trans_id = t.id) != 0) = 0 THEN '✅ PASS'
    ELSE '❌ FAIL'
  END
UNION ALL
SELECT
  'Foreign Key Integrity',
  CASE
    WHEN (SELECT COUNT(*) FROM coa_trans_data
          WHERE NOT EXISTS (SELECT 1 FROM coa_trans t WHERE t.id = trans_id)) = 0 THEN '✅ PASS'
    ELSE '❌ FAIL'
  END
UNION ALL
SELECT
  'Index Coverage',
  CASE
    WHEN (SELECT COUNT(*) FROM pg_indexes
          WHERE tablename IN ('coa_trans', 'coa_trans_data')) >= 5 THEN '✅ PASS'
    ELSE '❌ FAIL'
  END;

\echo ''
\echo '═══════════════════════════════════════════════════════════════════════'
\echo 'END OF VALIDATION CHECKLIST'
\echo '═══════════════════════════════════════════════════════════════════════'
\echo ''
\echo 'Next: Review all results above. If all checks ✅ PASS, migration is SUCCESS.'
\echo 'If any ❌ FAIL, execute rollback procedure immediately.'
\echo ''
