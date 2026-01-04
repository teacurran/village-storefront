# Compliance Archival Runbook

## Overview

This runbook describes operational procedures for managing compliance archives, verifying archival pipeline integrity, and responding to retrieval requests for archived privacy exports and audit logs.

**References:**
- Task: I5.T6 - Compliance automation (archival job verification)
- Architecture: 04_Operational_Architecture.md Section 3.6 (Background Jobs)
- Related: docs/compliance/privacy.md

---

## Archival Pipeline Components

### 1. Privacy Export Archives

**Location:** Cloudflare R2 bucket: `village-storefront-compliance/{environment}/`

**Structure:**
```
{tenant_id}/
  privacy-exports/
    {sanitized_email}_{hash}.zip   # Customer data exports
  audit-logs/
    {year}/{month}/{batch_id}.jsonl  # Platform command exports
```

**Retention:**
- **Privacy Exports:** 90 days (then auto-purged via R2 lifecycle policy)
- **Audit Logs:** 7 years (regulatory requirement for financial/tax records)

### 2. Database Archival

**Tables:**
- `privacy_requests`: Retained indefinitely (audit trail)
- `platform_commands`: Retained indefinitely (audit trail)
- `marketing_consents`: Retained per customer lifecycle (purged with customer deletion)
- `deleted_customers` (shadow table): Anonymized records retained 7 years for tax compliance

**Partitioning Strategy:**
- `platform_commands`: Partitioned by month (RANGE on `occurred_at`)
- Older partitions archived to cold storage (R2) quarterly

---

## Verification Procedures

### Daily Health Checks

**Objective:** Ensure archival jobs running and exports accessible

**Steps:**

1. **Check Queue Metrics**
   ```bash
   # Via Prometheus
   curl -s 'http://prometheus:9090/api/v1/query?query=compliance_export_queue_depth'
   curl -s 'http://prometheus:9090/api/v1/query?query=compliance_delete_queue_depth'
   ```
   - **Expected:** Queue depth < 50
   - **Alert:** If depth > 100, investigate job processor health

2. **Verify Export Generation**
   ```bash
   # Check last 24 hours of completed exports
   psql -h postgres -U storefront -d storefront_production -c \
     "SELECT COUNT(*), AVG(EXTRACT(EPOCH FROM (completed_at - approved_at))) AS avg_duration_seconds
      FROM privacy_requests
      WHERE status = 'COMPLETED' AND completed_at > NOW() - INTERVAL '24 hours';"
   ```
   - **Expected:** Count > 0 (if requests approved), avg_duration < 300 seconds
   - **Alert:** If avg_duration > 600 seconds or count = 0 despite approvals, check job logs

3. **Validate Signed URLs**
   ```bash
   # Test signed URL generation (use recent export)
   curl -I "$(psql -tA -c "SELECT result_url FROM privacy_requests WHERE status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1;")"
   ```
   - **Expected:** HTTP 200 OK, Content-Type: application/zip
   - **Alert:** If 403 Forbidden, check R2 credentials/permissions

### Weekly Verification Drills

**Objective:** End-to-end verification of export retrieval and integrity

**Steps:**

1. **Submit Test Export Request**
   ```bash
   curl -X POST https://platform.villagecompute.com/api/v1/platform/compliance/privacy-requests/export \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{
       "requesterEmail": "ops-drill@villagecompute.com",
       "subjectEmail": "test-customer@example.com",
       "reason": "Weekly verification drill",
       "ticketNumber": "DRILL-'$(date +%Y%m%d)'"
     }'
   ```

2. **Approve and Monitor**
   - Record `requestId` from response
   - Approve via Platform console or API
   - Monitor job processing:
     ```bash
     watch -n 5 "psql -tA -c \"SELECT status, updated_at FROM privacy_requests WHERE id = '{requestId}';\""
     ```

3. **Download and Validate Archive**
   ```bash
   # Retrieve signed URL
   SIGNED_URL=$(psql -tA -c "SELECT result_url FROM privacy_requests WHERE id = '{requestId}';")

   # Download
   curl -o drill_export.zip "$SIGNED_URL"

   # Verify ZIP integrity
   unzip -t drill_export.zip

   # Verify contents
   unzip -l drill_export.zip | grep -E 'customer_data.jsonl|marketing_consents.csv|export_summary.csv'
   ```
   - **Expected:** All three files present, ZIP passes integrity check
   - **Alert:** If missing files or corrupt ZIP, escalate to engineering

4. **Verify Hash Integrity**
   ```bash
   # Extract hash from filename
   FILENAME=$(basename "$SIGNED_URL" | cut -d'?' -f1)
   HASH_SUFFIX=$(echo "$FILENAME" | grep -oE '[a-f0-9]{8}\.zip$' | sed 's/\.zip$//')

   # Compute actual hash
   ACTUAL_HASH=$(sha256sum drill_export.zip | cut -d' ' -f1 | cut -c1-8)

   # Compare
   if [ "$HASH_SUFFIX" = "$ACTUAL_HASH" ]; then
     echo "Hash verified: $HASH_SUFFIX"
   else
     echo "HASH MISMATCH: Expected $HASH_SUFFIX, got $ACTUAL_HASH"
   fi
   ```
   - **Expected:** Hashes match
   - **Alert:** Mismatch indicates data corruption or tampering

### Monthly Audit Log Exports

**Objective:** Archive platform command logs for compliance reporting

**Steps:**

1. **Export Previous Month's Audit Logs**
   ```bash
   # Generate JSONL export
   psql -tA -F',' -c \
     "COPY (
       SELECT row_to_json(pc)
       FROM platform_commands pc
       WHERE occurred_at >= DATE_TRUNC('month', NOW() - INTERVAL '1 month')
         AND occurred_at < DATE_TRUNC('month', NOW())
     ) TO STDOUT;" > audit_logs_$(date -d 'last month' +%Y%m).jsonl
   ```

2. **Upload to R2**
   ```bash
   YEAR=$(date -d 'last month' +%Y)
   MONTH=$(date -d 'last month' +%m)
   BATCH_ID=$(uuidgen)

   aws s3 cp audit_logs_${YEAR}${MONTH}.jsonl \
     s3://village-storefront-compliance/production/audit-logs/${YEAR}/${MONTH}/${BATCH_ID}.jsonl \
     --endpoint-url https://r2.cloudflarestorage.com \
     --profile villagecompute-r2
   ```

3. **Verify Upload**
   ```bash
   aws s3 ls s3://village-storefront-compliance/production/audit-logs/${YEAR}/${MONTH}/ \
     --endpoint-url https://r2.cloudflarestorage.com \
     --profile villagecompute-r2
   ```

4. **Document Archive**
   - Record batch ID, row count, file size in runbook log spreadsheet
   - Tag in JIRA: `COMPLIANCE-ARCHIVE-${YEAR}${MONTH}`

---

## Retrieval Procedures

### Privacy Export Retrieval (Within 90 Days)

**Use Case:** Support team needs to re-send export link to customer

**Steps:**

1. **Lookup Request by Ticket Number**
   ```sql
   SELECT id, subject_email, status, result_url, completed_at
   FROM privacy_requests
   WHERE ticket_number = 'TICKET-12345'
     AND request_type = 'EXPORT';
   ```

2. **Check URL Expiry**
   - Signed URLs expire after 72 hours
   - If `completed_at` > 72 hours ago, regenerate signed URL:
     ```bash
     # Via admin API (future enhancement) or manual R2 SDK call
     aws s3 presign s3://village-storefront-compliance/production/{object_key} \
       --expires-in 259200 \
       --endpoint-url https://r2.cloudflarestorage.com \
       --profile villagecompute-r2
     ```

3. **Provide to Customer**
   - Send via secure email (encrypted) or support portal download link
   - Log retrieval in support ticket notes

### Privacy Export Retrieval (After 90 Days)

**Use Case:** Customer requests export that was auto-purged

**Process:**

1. **Notify Customer**
   - Exports retained only 90 days per policy
   - Offer to generate new export if customer still has valid request basis

2. **Generate New Export**
   - Submit new privacy request following standard approval workflow
   - Reference original ticket number in reason field

### Audit Log Retrieval (Historical)

**Use Case:** Compliance audit, legal discovery, incident investigation

**Steps:**

1. **Identify Date Range**
   - Determine month(s) needed for query

2. **Download Archived JSONL**
   ```bash
   aws s3 cp s3://village-storefront-compliance/production/audit-logs/2025/01/ . \
     --recursive \
     --endpoint-url https://r2.cloudflarestorage.com \
     --profile villagecompute-r2
   ```

3. **Query Using jq**
   ```bash
   # Example: Find all privacy approvals by specific admin
   cat *.jsonl | jq -c 'select(.action == "approve_privacy_export" and .actor_email == "admin@example.com")'

   # Example: Count actions by type
   cat *.jsonl | jq -r '.action' | sort | uniq -c | sort -nr
   ```

4. **Import to Analysis Database (if needed)**
   ```bash
   # Create temp table
   psql -c "CREATE TABLE audit_import (data JSONB);"

   # Import
   cat *.jsonl | psql -c "COPY audit_import (data) FROM STDIN;"

   # Query
   psql -c "SELECT data->>'action', COUNT(*) FROM audit_import GROUP BY 1 ORDER BY 2 DESC;"
   ```

---

## Incident Response

### Scenario: Export Job Failures Spike

**Symptoms:**
- `compliance_export_failed` metric increasing
- Multiple requests stuck in `IN_PROGRESS` status
- Queue depth growing

**Response:**

1. **Check Job Logs**
   ```bash
   kubectl logs -n storefront deployment/storefront-app --tail=500 | grep "compliance.export"
   ```
   - Look for exceptions, storage upload errors, database connection issues

2. **Verify R2 Connectivity**
   ```bash
   aws s3 ls s3://village-storefront-compliance/production/ \
     --endpoint-url https://r2.cloudflarestorage.com \
     --profile villagecompute-r2
   ```
   - If fails, check R2 API credentials in Kubernetes secrets

3. **Retry Failed Jobs**
   - Option 1: Reset status to `APPROVED`, reprocess automatically
     ```sql
     UPDATE privacy_requests
     SET status = 'APPROVED', error_message = NULL
     WHERE status = 'FAILED' AND completed_at > NOW() - INTERVAL '1 hour';
     ```
   - Option 2: Manual re-approval via API

4. **Scale Job Processors (if capacity issue)**
   ```bash
   kubectl scale deployment/storefront-app --replicas=5 -n storefront
   ```

5. **Notify Stakeholders**
   - Post incident in #compliance-ops Slack channel
   - Update status page if customer-facing SLAs at risk

### Scenario: Purge Job Deletes Wrong Customer

**Symptoms:**
- Customer reports data missing unexpectedly
- No corresponding privacy request found

**CRITICAL RESPONSE:**

1. **STOP ALL DELETE JOBS IMMEDIATELY**
   ```bash
   # Scale down job processors
   kubectl scale deployment/storefront-job-processor --replicas=0 -n storefront

   # Disable job scheduling
   kubectl set env deployment/storefront-app JOBS_ENABLED=false -n storefront
   ```

2. **Investigate**
   ```sql
   -- Find recent delete operations
   SELECT pc.*, pr.subject_email, pr.status
   FROM platform_commands pc
   LEFT JOIN privacy_requests pr ON pc.target_id = pr.id
   WHERE pc.action IN ('privacy_soft_delete', 'privacy_purge')
     AND pc.occurred_at > NOW() - INTERVAL '24 hours'
   ORDER BY pc.occurred_at DESC;
   ```

3. **Attempt Recovery**
   - If soft-deleted (within 90 days): Restore by clearing `deleted_at` timestamp
     ```sql
     UPDATE customers SET deleted_at = NULL WHERE id = '{customer_id}';
     ```
   - If purged: **No recovery possible** from database
   - Check R2 backups (daily snapshots retained 30 days):
     ```bash
     aws s3 ls s3://village-storefront-backups/postgres/daily/ \
       --endpoint-url https://r2.cloudflarestorage.com
     ```

4. **Escalate**
   - Page on-call engineer
   - Notify legal/compliance team
   - Document incident in postmortem template

### Scenario: R2 Storage Quota Exceeded

**Symptoms:**
- Export upload failures with "quota exceeded" errors
- New archives cannot be stored

**Response:**

1. **Verify Current Usage**
   ```bash
   aws s3 ls s3://village-storefront-compliance/production/ --recursive --summarize \
     --endpoint-url https://r2.cloudflarestorage.com
   ```

2. **Trigger Early Purge (if safe)**
   - Review exports older than 60 days (still within 90-day policy)
   - Delete if no pending legal holds:
     ```bash
     aws s3 rm s3://village-storefront-compliance/production/ \
       --recursive \
       --exclude "*" \
       --include "*/privacy-exports/*" \
       --dryrun \
       --endpoint-url https://r2.cloudflarestorage.com
     # Remove --dryrun after verification
     ```

3. **Request Quota Increase**
   - Contact Cloudflare support
   - Provide growth projections based on recent export volume

4. **Implement Lifecycle Policy** (if not already set)
   ```json
   {
     "Rules": [
       {
         "Id": "PurgePrivacyExports",
         "Status": "Enabled",
         "Prefix": "privacy-exports/",
         "Expiration": {
           "Days": 90
         }
       }
     ]
   }
   ```

---

## Monitoring & Alerting

### Critical Alerts (PagerDuty)

**Export Job Failure Rate:**
```promql
rate(compliance_export_failed[5m]) > 0.1
```
- **Severity:** P2 (High)
- **Escalation:** Page on-call engineer after 15 minutes

**Delete Job Failure Rate:**
```promql
rate(compliance_delete_failed[5m]) > 0
```
- **Severity:** P1 (Critical)
- **Escalation:** Immediate page, notify compliance lead

**R2 Upload Errors:**
```promql
rate(storage_upload_errors{service="compliance"}[5m]) > 0.05
```
- **Severity:** P2 (High)
- **Escalation:** Page on-call engineer after 10 minutes

### Warning Alerts (Slack)

**Queue Depth High:**
```promql
compliance_export_queue_depth > 100 OR compliance_delete_queue_depth > 50
```
- **Channel:** #compliance-ops
- **Action:** Scale job processors, investigate slow exports

**Pending Approval Backlog:**
```sql
-- Run every 6 hours via cron
SELECT COUNT(*) AS backlog
FROM privacy_requests
WHERE status = 'PENDING_REVIEW'
  AND created_at < NOW() - INTERVAL '3 days';
```
- **Threshold:** > 10 requests
- **Channel:** #compliance-ops
- **Action:** Notify compliance team to review queue

**Signed URL Expiry:**
```sql
-- Run daily via cron
SELECT COUNT(*) AS expiring_soon
FROM privacy_requests
WHERE status = 'COMPLETED'
  AND completed_at BETWEEN NOW() - INTERVAL '69 hours' AND NOW() - INTERVAL '48 hours';
```
- **Threshold:** > 0 (customers may need re-notification)
- **Channel:** #support
- **Action:** Proactively regenerate URLs, notify customers

---

## Quarterly Compliance Review

**Objectives:**
- Verify archival pipeline integrity
- Audit deletion workflow adherence
- Review consent management accuracy

**Checklist:**

- [ ] Run full verification drill (submit, approve, download, verify hash)
- [ ] Sample 10 random exports from past quarter, verify ZIP contents complete
- [ ] Review all failed jobs (export + delete), document root causes
- [ ] Verify purge jobs executed on schedule (90 days post soft-delete)
- [ ] Audit platform commands for privacy actions, ensure all have reasons/tickets
- [ ] Check R2 lifecycle policies active and configured correctly
- [ ] Review SLA adherence (export <24hr, delete <5 days)
- [ ] Update runbook based on lessons learned

**Report Deliverable:**
- Summary document for Legal/Compliance
- Metrics dashboard screenshots
- List of any policy violations or SLA misses
- Recommendations for process improvements

---

## Runbook Maintenance

**Owner:** Platform Operations Team

**Review Cadence:** Quarterly or after major compliance incidents

**Change Log:**
- 2026-01-03: Initial version (Task I5.T6)

**Feedback:** Submit runbook improvements via JIRA tag `runbook-compliance`
