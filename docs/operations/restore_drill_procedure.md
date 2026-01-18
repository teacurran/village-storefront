# Disaster Recovery Restore Drill Procedure

## Overview

This document provides step-by-step instructions for executing disaster recovery (DR) restore drills to validate backup integrity and recovery procedures. DR drills are performed **weekly in staging** and **monthly in production** to ensure RTO/RPO targets are achievable.

**References:**
- Task: I6.T5 - Data Ops + DR Automation
- DR Playbook: `docs/operations/dr_playbook.md`
- Restore Scripts: `scripts/ops/restore-full-backup.sh`, `scripts/ops/restore-pitr.sh`

## Schedule

| Environment | Frequency | Day/Time | Duration | Success Criteria |
|-------------|-----------|----------|----------|------------------|
| Staging | Weekly | Friday 2 PM UTC | 2-4 hours | RTO < 4 hours |
| Production | Monthly | First Sunday 4 AM UTC | 2-4 hours | RTO < 4 hours |

## Prerequisites

- [ ] kubectl access to target cluster (staging or production)
- [ ] WireGuard VPN connection established
- [ ] R2 credentials for backup bucket
- [ ] PostgreSQL superuser credentials
- [ ] Isolated restore namespace created
- [ ] Monitoring access (Grafana, Prometheus, PagerDuty)

## Preparation (Before Drill)

### 1. Schedule Drill

Create calendar event with stakeholders:
- Platform ops engineer (drill executor)
- Database administrator (backup verification)
- Application engineer (smoke test execution)
- On-call engineer (incident response if issues found)

### 2. Create Isolated Restore Namespace

```bash
# For staging drills
kubectl create namespace storefront-restore-drill-staging --dry-run=client -o yaml | kubectl apply -f -

# For production drills
kubectl create namespace storefront-restore-drill-prod --dry-run=client -o yaml | kubectl apply -f -
```

### 3. Verify Latest Backup

```bash
# Set environment
ENVIRONMENT="staging"  # or "production"
R2_BUCKET="village-storefront-backups-${ENVIRONMENT}"

# List recent backups
aws s3 ls "s3://${R2_BUCKET}/postgres/daily/" \
  --endpoint-url https://account.r2.cloudflarestorage.com \
  --profile village-storefront \
  | sort -r | head -5

# Expected output:
# 2026-01-18 03:00:12  1234567890 postgres/daily/backup-2026-01-18.tar.gz
# 2026-01-17 03:00:08  1234567890 postgres/daily/backup-2026-01-17.tar.gz
# ...
```

### 4. Verify WAL Archiving

```bash
# Check latest WAL file
aws s3 ls "s3://${R2_BUCKET}/postgres/wal/" \
  --endpoint-url https://account.r2.cloudflarestorage.com \
  --profile village-storefront \
  | sort -r | head -5

# Verify WAL is recent (within last 2 hours)
LATEST_WAL=$(aws s3api list-objects-v2 \
  --bucket "${R2_BUCKET}" \
  --prefix "postgres/wal/" \
  --endpoint-url https://account.r2.cloudflarestorage.com \
  --query 'sort_by(Contents, &LastModified)[-1]' \
  --output json)

echo "Latest WAL file: $(echo $LATEST_WAL | jq -r '.Key')"
echo "Last modified: $(echo $LATEST_WAL | jq -r '.LastModified')"
```

## Drill Execution

### Phase 1: Full Restore (Target: < 3 hours)

**Start Time:** Record in `drill_results.md`

```bash
# 1. Set environment variables
export ENVIRONMENT="staging"
export RESTORE_NAMESPACE="storefront-restore-drill-${ENVIRONMENT}"
export PGHOST="postgres-restore.${RESTORE_NAMESPACE}.svc.cluster.local"
export PGPORT="5432"
export PGDATABASE="storefront_${ENVIRONMENT}_restored"
export PGUSER="postgres"
export PGPASSWORD="$(kubectl get secret postgres-admin -n ${RESTORE_NAMESPACE} -o jsonpath='{.data.password}' | base64 -d)"

# 2. Execute restore script
cd scripts/ops
./restore-full-backup.sh

# Expected output:
# [2026-01-18 14:05:00 UTC] [RESTORE] Starting database restore from R2 backups
# [2026-01-18 14:05:05 UTC] [RESTORE] Listing available backups...
# [2026-01-18 14:05:10 UTC] [RESTORE] Latest backup: backup-2026-01-18.tar.gz (1.2 GB, 2026-01-18 03:00:12 UTC)
# [2026-01-18 14:05:15 UTC] [RESTORE] Downloading backup from R2...
# [2026-01-18 14:35:42 UTC] [RESTORE] Download completed (1.2 GB in 30 minutes)
# [2026-01-18 14:35:45 UTC] [RESTORE] Stopping PostgreSQL...
# [2026-01-18 14:35:50 UTC] [RESTORE] Clearing data directory...
# [2026-01-18 14:35:55 UTC] [RESTORE] Extracting backup...
# [2026-01-18 15:05:23 UTC] [RESTORE] Extraction completed
# [2026-01-18 15:05:25 UTC] [RESTORE] Creating recovery.conf...
# [2026-01-18 15:05:30 UTC] [RESTORE] Starting PostgreSQL...
# [2026-01-18 15:06:00 UTC] [RESTORE] PostgreSQL started, replaying WAL...
# [2026-01-18 15:45:12 UTC] [RESTORE] WAL replay completed
# [2026-01-18 15:45:15 UTC] [RESTORE] Verifying database integrity...
# [2026-01-18 15:45:20 UTC] [RESTORE] ✓ Database restored successfully
# [2026-01-18 15:45:20 UTC] [RESTORE] Total time: 1 hour 40 minutes
```

**End Time:** Record in `drill_results.md`

### Phase 2: Point-in-Time Recovery Test (Target: < 1 hour)

```bash
# 1. Choose target timestamp (2 hours ago)
TARGET_TIMESTAMP=$(date -u -d '2 hours ago' '+%Y-%m-%d %H:%M:%S')
echo "PITR target: ${TARGET_TIMESTAMP}"

# 2. Execute PITR restore
./restore-pitr.sh "${TARGET_TIMESTAMP}"

# Expected output:
# [2026-01-18 15:50:00 UTC] [PITR] Starting point-in-time recovery to: 2026-01-18 13:50:00
# [2026-01-18 15:50:05 UTC] [PITR] Downloading base backup...
# [2026-01-18 16:20:30 UTC] [PITR] Downloading WAL segments...
# [2026-01-18 16:25:45 UTC] [PITR] Replaying WAL to target time...
# [2026-01-18 16:35:12 UTC] [PITR] ✓ Recovery completed at: 2026-01-18 13:50:00
# [2026-01-18 16:35:12 UTC] [PITR] Total time: 45 minutes
```

### Phase 3: Application Smoke Tests (Target: < 30 minutes)

```bash
# 1. Deploy application to restore namespace
kubectl apply -k k8s/overlays/${ENVIRONMENT} \
  --namespace ${RESTORE_NAMESPACE} \
  --dry-run=client -o yaml > /tmp/restore-app.yaml

# Edit to point to restored database
sed -i "s/postgres.storefront.svc/${PGHOST}/" /tmp/restore-app.yaml
sed -i "s/storefront_${ENVIRONMENT}/${PGDATABASE}/" /tmp/restore-app.yaml

kubectl apply -f /tmp/restore-app.yaml

# 2. Wait for pods to be ready
kubectl wait --for=condition=ready pod \
  -l app=village-storefront \
  -n ${RESTORE_NAMESPACE} \
  --timeout=5m

# 3. Run smoke tests
kubectl run smoke-test \
  --image=ghcr.io/villagecompute/village-storefront:${ENVIRONMENT} \
  --namespace=${RESTORE_NAMESPACE} \
  --restart=Never \
  --command -- /app/run-smoke-tests.sh

# Watch logs
kubectl logs -f smoke-test -n ${RESTORE_NAMESPACE}

# Expected output:
# ✓ Database connection successful
# ✓ Tenants table accessible (12,345 rows)
# ✓ Products table accessible (98,765 rows)
# ✓ Orders table accessible (54,321 rows)
# ✓ Session logs accessible (1,234,567 rows)
# ✓ Feature flags accessible (234 rows)
# ✓ Platform commands accessible (456 rows)
# ✓ All tables have expected row counts (variance < 5%)
#
# Summary: 7/7 tests passed
```

### Phase 4: Data Integrity Verification

```bash
# 1. Connect to restored database
psql -h ${PGHOST} -p ${PGPORT} -U ${PGUSER} -d ${PGDATABASE}

# 2. Verify row counts match production
SELECT
  schemaname,
  tablename,
  n_live_tup AS row_count
FROM pg_stat_user_tables
ORDER BY n_live_tup DESC
LIMIT 20;

# 3. Verify latest transaction timestamp
SELECT MAX(created_at) AS latest_transaction
FROM (
  SELECT created_at FROM orders
  UNION ALL
  SELECT created_at FROM products
  UNION ALL
  SELECT occurred_at AS created_at FROM platform_commands
) AS all_timestamps;

-- Expected: Within 1 hour of drill start time (RPO < 1 hour)

# 4. Check for data corruption
SELECT
  tablename,
  last_vacuum,
  last_analyze,
  n_dead_tup
FROM pg_stat_user_tables
WHERE n_dead_tup > 10000
ORDER BY n_dead_tup DESC;

-- Expected: No tables with excessive dead tuples
```

## Metrics Collection

Record the following metrics in `drill_results.md`:

```markdown
## Drill Metrics - [YYYY-MM-DD]

### Environment: [staging/production]

| Metric | Target | Actual | Status |
|--------|--------|--------|--------|
| Full restore duration | < 4 hours | X hours Y minutes | PASS/FAIL |
| PITR duration | < 1 hour | X minutes | PASS/FAIL |
| Backup download speed | > 10 MB/s | X MB/s | PASS/FAIL |
| WAL replay speed | > 100 MB/s | X MB/s | PASS/FAIL |
| Smoke test pass rate | 100% | X/Y tests | PASS/FAIL |
| Data loss window (RPO) | < 1 hour | X minutes | PASS/FAIL |
| Latest transaction age | < 1 hour | X minutes | PASS/FAIL |

### Issues Encountered

- [List any issues, errors, or anomalies]

### Action Items

- [ ] Issue #1: [Description] - Assigned to: [Name] - Due: [Date]
- [ ] Issue #2: [Description] - Assigned to: [Name] - Due: [Date]
```

## Cleanup

```bash
# 1. Stop application in restore namespace
kubectl delete deployment,service,configmap,secret \
  -l app=village-storefront \
  -n ${RESTORE_NAMESPACE}

# 2. Delete smoke test pod
kubectl delete pod smoke-test -n ${RESTORE_NAMESPACE}

# 3. Optionally preserve namespace for investigation
# OR delete namespace completely
kubectl delete namespace ${RESTORE_NAMESPACE}

# 4. Document results
git add docs/operations/drill_results.md
git commit -m "docs: add DR drill results for $(date +%Y-%m-%d)"
git push
```

## Troubleshooting

### Issue: Backup download timeout

**Symptom:** Download stalls or times out after 30 minutes

**Solution:**
```bash
# Increase timeout in restore script
export DOWNLOAD_TIMEOUT_MINUTES=120

# Use R2 presigned URL for faster transfer
aws s3 presign "s3://${R2_BUCKET}/postgres/daily/backup-YYYY-MM-DD.tar.gz" \
  --endpoint-url https://account.r2.cloudflarestorage.com \
  --expires-in 7200

# Download via curl with resume support
curl -C - -o backup.tar.gz "[presigned-url]"
```

### Issue: WAL replay stalls

**Symptom:** WAL replay stops progressing

**Solution:**
```bash
# Check PostgreSQL logs
kubectl logs postgres-restore-0 -n ${RESTORE_NAMESPACE} --tail=100

# Common causes:
# 1. Missing WAL segments - verify WAL files in R2
# 2. Corrupted WAL file - try skipping corrupt segment
# 3. Insufficient disk space - check PVC size

# Verify WAL continuity
aws s3 ls "s3://${R2_BUCKET}/postgres/wal/" \
  --endpoint-url https://account.r2.cloudflarestorage.com \
  | awk '{print $4}' | sort | uniq -c

# Expected: Continuous sequence with no gaps
```

### Issue: Smoke tests fail

**Symptom:** Application returns errors during smoke tests

**Solution:**
```bash
# Check application logs
kubectl logs -l app=village-storefront -n ${RESTORE_NAMESPACE} --tail=100

# Common causes:
# 1. Database migrations not applied - check flyway_schema_history
# 2. RLS policies failing - verify tenant context
# 3. Missing indexes - check pg_stat_user_indexes

# Verify migrations
psql -h ${PGHOST} -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;"
```

## Success Criteria

A DR drill is considered **PASSED** if:

1. ✅ Full restore completes within RTO (< 4 hours)
2. ✅ PITR completes successfully (< 1 hour)
3. ✅ All smoke tests pass (100%)
4. ✅ Data loss is within RPO (< 1 hour)
5. ✅ No data corruption detected
6. ✅ Application functions correctly on restored database

A DR drill is considered **FAILED** if any of the above criteria are not met. Failure requires:
1. Incident created in PagerDuty
2. Root cause analysis documented
3. Action items assigned with deadlines
4. Re-test scheduled within 7 days

## Reporting

After completing the drill, send summary to stakeholders:

**To:** platform-ops@villagecompute.com
**CC:** engineering@villagecompute.com
**Subject:** DR Drill Results - [Environment] - [Date] - [PASS/FAIL]

```
DR Drill Summary
================

Environment: [staging/production]
Date: [YYYY-MM-DD HH:MM UTC]
Executed by: [Your Name]

Results: [PASS/FAIL]

Metrics:
- Full restore: [X hours Y minutes] (target: < 4 hours)
- PITR: [X minutes] (target: < 1 hour)
- RPO achieved: [X minutes] (target: < 1 hour)
- Smoke tests: [X/Y passed] (target: 100%)

Issues:
[List any issues or N/A]

Action Items:
[List action items or "None - drill passed all criteria"]

Next drill scheduled: [Date]

Full results: docs/operations/drill_results.md
```

## Appendix: Automated Drill Script

For fully automated drill execution (staging only):

```bash
#!/bin/bash
# scripts/ops/automated-restore-drill.sh

set -euo pipefail

ENVIRONMENT="staging"
RESULTS_FILE="docs/operations/drill_results.md"

echo "## DR Drill - $(date -u '+%Y-%m-%d %H:%M UTC')" >> "${RESULTS_FILE}"
echo "" >> "${RESULTS_FILE}"

# Run full restore and capture metrics
START_TIME=$(date +%s)
./scripts/ops/restore-full-backup.sh 2>&1 | tee /tmp/restore.log
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo "| Full restore duration | < 4 hours | ${DURATION}s | $([ $DURATION -lt 14400 ] && echo PASS || echo FAIL) |" >> "${RESULTS_FILE}"

# Run smoke tests
kubectl run smoke-test --image=ghcr.io/villagecompute/village-storefront:staging \
  --namespace=storefront-restore-drill-staging \
  --restart=Never \
  --command -- /app/run-smoke-tests.sh 2>&1 | tee /tmp/smoke-tests.log

TESTS_PASSED=$(grep -c "✓" /tmp/smoke-tests.log || echo 0)
TESTS_TOTAL=$(grep -c "test" /tmp/smoke-tests.log || echo 0)

echo "| Smoke test pass rate | 100% | ${TESTS_PASSED}/${TESTS_TOTAL} | $([ $TESTS_PASSED -eq $TESTS_TOTAL ] && echo PASS || echo FAIL) |" >> "${RESULTS_FILE}"

# Cleanup
kubectl delete namespace storefront-restore-drill-staging

echo "" >> "${RESULTS_FILE}"
```

---

**Document version:** 1.0
**Last updated:** 2026-01-18
**Owner:** Platform Operations Team
**Review frequency:** Quarterly
