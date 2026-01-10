# Data Retention & Archival

**Task Reference:** I5.T2 - Reporting & Retention pipeline
**Architectural Reference:** docs/architecture/datamodel_tenancy_narrative.md (Partition Maintenance)

This document describes the data retention policies, partition maintenance automation, and archival processes for the Village Storefront platform.

---

## Table of Contents

1. [Retention Windows](#retention-windows)
2. [Partition Strategy](#partition-strategy)
3. [Archival Process](#archival-process)
4. [Automation Schedule](#automation-schedule)
5. [Recovery Procedures](#recovery-procedures)
6. [Metrics & Monitoring](#metrics--monitoring)

---

## Retention Windows

The platform implements tiered retention policies based on data type, compliance requirements, and business intelligence needs.

### Summary Table

| Data Type | Retention | Partition Interval | Archival Destination | Lifecycle |
|-----------|-----------|-------------------|---------------------|-----------|
| `domain_events` | **6 months** | Monthly | S3 JSONL (Glacier Deep Archive) | 7 years |
| `processed_domain_events` | **7 days** | None | None (dropped) | N/A |
| `audit_events` | **1 year** | Monthly | S3 Parquet (Glacier) | 7 years |
| `session_log` | **90 days** | Monthly | None (dropped) | N/A |
| `background_jobs` | **30 days** | Weekly | None (dropped) | N/A |

### Domain Events

**Table:** `domain_events`
**Retention:** 6 months (hot storage), 7 years (cold archive)
**Partition Interval:** Monthly

Domain events contain business intelligence data for event replay, reporting aggregation, and audit trails.

- **Hot Storage (PostgreSQL):** Last 6 months of events for real-time aggregate processing
- **Cold Archive (S3 Glacier Deep Archive):** 180 days after archival, transitioned to Glacier for 7-year compliance retention
- **Event Types:** ORDER_PLACED, INVENTORY_ADJUSTED, LOYALTY_EARNED, CONSIGNMENT_SOLD, etc.

### Processed Domain Events (Idempotency Tracking)

**Table:** `processed_domain_events`
**Retention:** 7 days
**Purpose:** Idempotency deduplication for aggregate processing

The `processed_domain_events` table tracks which domain events have been successfully processed by the reporting aggregator. This prevents double-counting when events are reprocessed during the lookback window (used for handling late-arriving events due to clock skew).

- **Lookback Window:** 5 minutes (configurable via `reporting.events.lookback_minutes`)
- **Cleanup:** Daily at 2 AM, deletes records older than 7 days
- **Rationale:** 7-day retention provides safety margin well beyond typical lookback window
- **Implementation:** `DomainEventProcessor` checks this table before processing each event

### Compliance Notes

- **SOX Compliance:** Domain events support financial audit trails (7-year retention)
- **GDPR:** Customer data in events subject to right-to-erasure (use event metadata for customer correlation)
- **Business Intelligence:** Events power reporting aggregates (sales, inventory, loyalty, consignment)

---

## Partition Strategy

The platform uses PostgreSQL native table partitioning (RANGE partitioning by `created_at` timestamp) for high-volume event tables.

### Domain Events Partitioning

**Parent Table:** `domain_events`
**Partition Key:** `created_at` (RANGE)
**Child Tables:** `domain_events_YYYY_MM` (e.g., `domain_events_2026_01`)

Example:

```sql
-- Parent table (partitioned)
CREATE TABLE domain_events (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id),
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    metadata JSONB,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (created_at);

-- Monthly partition for January 2026
CREATE TABLE domain_events_2026_01 PARTITION OF domain_events
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

-- Indexes on child partition
CREATE INDEX idx_domain_events_2026_01_tenant ON domain_events_2026_01(tenant_id);
CREATE INDEX idx_domain_events_2026_01_type ON domain_events_2026_01(event_type);
CREATE INDEX idx_domain_events_2026_01_aggregate ON domain_events_2026_01(aggregate_id);
CREATE INDEX idx_domain_events_2026_01_occurred ON domain_events_2026_01(occurred_at);
```

### Benefits

- **Query Performance:** Partition pruning eliminates scanning old partitions
- **Efficient Archival:** Export entire partition to S3 without full table scan
- **Fast Deletes:** Drop partition table instead of DELETE (no VACUUM overhead)
- **Index Locality:** Smaller indexes per partition improve cache hit rates

---

## Archival Process

The archival pipeline exports partition data to S3-compatible object storage (Cloudflare R2) before dropping partitions.

### Workflow

1. **Export:** Query partition data and serialize to JSONL (one JSON object per line)
2. **Compress:** Gzip compression for storage efficiency
3. **Upload:** Upload to R2 bucket with hierarchical key structure
4. **Verify:** Confirm upload success (checksum validation)
5. **Drop:** Drop partition table to reclaim disk space

### Archive Key Structure

```
s3://village-storefront-archives/domain_events/{YEAR}/{MONTH}/{PARTITION_NAME}.jsonl.gz

Examples:
- s3://village-storefront-archives/domain_events/2025/07/domain_events_2025_07.jsonl.gz
- s3://village-storefront-archives/domain_events/2025/08/domain_events_2025_08.jsonl.gz
```

### S3 Lifecycle Rules

| Archive Type | S3 Storage Class | Transition | Expiration |
|--------------|------------------|------------|------------|
| `domain_events` | Standard → Glacier Deep Archive | 180 days | 7 years |
| `audit_events` | Standard → Glacier | 90 days | 7 years |

**Rationale:**
- Domain events transitioned to Glacier Deep Archive (lowest cost, 12-hour retrieval) after 180 days
- Audit events transitioned to Glacier (faster retrieval for compliance queries) after 90 days

### Example Archive File

```jsonl
{"id":"550e8400-e29b-41d4-a716-446655440000","tenantId":"123e4567-e89b-12d3-a456-426614174000","eventType":"ORDER_PLACED","aggregateId":"789e4567-e89b-12d3-a456-426614174000","aggregateType":"ORDER","payload":"{\"orderId\":\"789\",\"totalAmount\":99.99}","occurredAt":"2025-07-15T14:30:00Z","createdAt":"2025-07-15T14:30:01Z"}
{"id":"650e8400-e29b-41d4-a716-446655440000","tenantId":"123e4567-e89b-12d3-a456-426614174000","eventType":"INVENTORY_ADJUSTED","aggregateId":"889e4567-e89b-12d3-a456-426614174000","aggregateType":"INVENTORY_LEVEL","payload":"{\"variantId\":\"456\",\"delta\":-2}","occurredAt":"2025-07-15T14:31:00Z","createdAt":"2025-07-15T14:31:01Z"}
```

---

## Automation Schedule

All partition maintenance tasks are automated via the `PartitionMaintenanceJob` scheduled job.

### Job Schedule

**Cron:** `0 2 * * * ?` (Daily at 2 AM UTC)
**Job Class:** `villagecompute.storefront.jobs.PartitionMaintenanceJob`

### Tasks

1. **Create Future Partitions**
   - **Trigger:** 7 days before month end
   - **Action:** Create next month's partition (e.g., `domain_events_2026_02` on 2026-01-24)
   - **Purpose:** Ensure writes don't fail at month boundary

2. **Archive Old Partitions**
   - **Trigger:** Daily check for partitions older than 6 months
   - **Action:** Export partition to S3 JSONL, verify upload, drop partition
   - **Example:** On 2026-01-10, archive `domain_events_2025_07` (July 2025)

### Configuration

```properties
# application.properties

# Retention period (months)
reporting.retention.domain_events=6

# Future partition threshold (days before month end)
reporting.partition.future_threshold_days=7

# R2 archive bucket
reporting.storage.r2.bucket=village-storefront-archives
reporting.storage.r2.endpoint=https://YOUR_ACCOUNT.r2.cloudflarestorage.com
```

### Manual Triggers (Emergency)

If partition maintenance needs to run outside the schedule:

```bash
# Trigger via Quarkus Scheduler REST API (dev mode)
curl -X POST http://localhost:8080/q/scheduler/trigger/partition-maintenance

# OR via background job queue (production)
# Insert job record into background_jobs table
INSERT INTO background_jobs (job_type, status, payload)
VALUES ('PartitionMaintenanceJob', 'pending', '{}');
```

---

## Recovery Procedures

### Scenario 1: Archive Upload Failed Before Drop

**Symptoms:** Partition maintenance job logs show S3 upload error, partition still exists in PostgreSQL

**Recovery Steps:**

1. **Check S3 bucket:** Verify archive file does NOT exist in R2
2. **Re-run archive export:** Manually trigger partition maintenance job
3. **Verify upload:** Confirm archive file exists in S3 with correct size
4. **Drop partition:** Manually execute `DROP TABLE domain_events_YYYY_MM`

**Mitigation:** Partition maintenance job ALWAYS verifies upload success before dropping. If upload fails, partition is retained.

### Scenario 2: Restore Events from Archive

**Symptoms:** Need to restore archived events for compliance investigation or event replay

**Recovery Steps:**

1. **Download archive from S3:**
   ```bash
   aws s3 cp s3://village-storefront-archives/domain_events/2025/07/domain_events_2025_07.jsonl.gz /tmp/
   ```

2. **Decompress:**
   ```bash
   gunzip /tmp/domain_events_2025_07.jsonl.gz
   ```

3. **Restore to PostgreSQL:**
   ```bash
   # Create temporary partition for restoration
   CREATE TABLE domain_events_2025_07_restored (LIKE domain_events INCLUDING ALL);

   # Import JSONL (using COPY or custom import script)
   COPY domain_events_2025_07_restored FROM '/tmp/domain_events_2025_07.jsonl' WITH (FORMAT 'csv');
   ```

4. **Query restored data:**
   ```sql
   SELECT * FROM domain_events_2025_07_restored WHERE tenant_id = 'XXX';
   ```

### Scenario 3: Partition Not Dropped After Archive

**Symptoms:** Partition older than 6 months still exists in PostgreSQL

**Diagnosis:**

1. **Check partition maintenance job logs:** Look for errors in `/q/logging`
2. **Check S3 archive:** Verify archive file exists for this partition
3. **Check partition age:**
   ```sql
   SELECT tablename FROM pg_tables WHERE tablename LIKE 'domain_events_%';
   ```

**Resolution:**

- If archive exists, manually drop partition: `DROP TABLE domain_events_YYYY_MM;`
- If archive does NOT exist, re-run partition maintenance job to archive first

---

## Metrics & Monitoring

The partition maintenance job emits Prometheus metrics for observability.

### Key Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `partition.maintenance.duration` | Timer | Duration of partition maintenance job execution |
| `partition.maintenance.completed` | Counter | Count of successful maintenance runs |
| `partition.maintenance.failed` | Counter | Count of failed maintenance runs |
| `partition.created{table}` | Counter | Count of partitions created (by table) |
| `partition.archived{table}` | Counter | Count of partitions archived (by table) |
| `partition.dropped{partition}` | Counter | Count of partitions dropped |
| `partition.archive.events{partition}` | Counter | Count of events exported to archive |
| `partition.archive.duration{partition}` | Timer | Duration of archive export (by partition) |
| `partition.archive.failed{partition}` | Counter | Count of failed archive exports |

### Alerts

**Alert:** `PartitionMaintenanceJobFailed`
**Condition:** `partition.maintenance.failed > 0` in last 24 hours
**Severity:** Warning
**Action:** Check job logs for errors, verify S3 connectivity

**Alert:** `PartitionArchiveLagHigh`
**Condition:** Partition older than 7 months still exists (1 month past retention)
**Severity:** Critical
**Action:** Investigate stuck partition, manually archive and drop if needed

### Dashboards

**Grafana Panel:** Partition Maintenance Overview

```promql
# Partition count by age
count(pg_tables{tablename=~"domain_events_.*"}) by (tablename)

# Archive lag (days between cutoff and oldest partition)
(now() - partition.oldest.created_at) / 86400

# Archive throughput (events/sec)
rate(partition.archive.events[5m])
```

---

## FAQ

### Why 6 months retention for domain_events?

- **Balance:** Hot storage (PostgreSQL) optimized for query performance, cold storage (S3) for compliance
- **Cost:** PostgreSQL storage more expensive than S3 Glacier Deep Archive
- **Performance:** Partition pruning keeps aggregate queries fast (6 months < 1 year prevents full table scan)

### Can I extend retention to 12 months?

Yes. Update `reporting.retention.domain_events=12` in `application.properties` and redeploy. Partition maintenance job will automatically adjust archival cutoff.

### What happens if R2 is unavailable during maintenance?

Partition maintenance job will FAIL but will NOT drop partition. Partition is retained in PostgreSQL until next maintenance run (retry after 24 hours). This prevents data loss.

### How do I query archived events?

Use AWS CLI or S3-compatible tools to download archive, decompress, and import to PostgreSQL temporary table. See [Recovery Procedures](#scenario-2-restore-events-from-archive).

### Can I disable partition maintenance?

Not recommended. Disabling partition maintenance will cause `domain_events` table to grow indefinitely, degrading query performance and increasing storage costs. If needed, disable via:

```properties
quarkus.scheduler.job-type.partition-maintenance.enabled=false
```

---

## References

- **Architecture:** [datamodel_tenancy_narrative.md](../architecture/datamodel_tenancy_narrative.md)
- **Job Catalog:** [job-catalog.md](../architecture/async/job-catalog.md)
- **Compliance:** [privacy.md](../compliance/privacy.md)
- **Source Code:**
  - `PartitionMaintenanceJob.java` - Scheduled partition maintenance
  - `DomainEventProcessor.java` - Domain event aggregation processor
  - `R2ReportStorageClient.java` - S3-compatible archive upload

---

**Last Updated:** 2026-01-10
**Maintainer:** Platform Engineering Team
**Contact:** platform-eng@villagecompute.com
