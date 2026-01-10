# Background Job Catalog

This document catalogs all background job types in the Village Storefront platform, including their payload schemas, queue configurations, retry policies, and operational guidance.

## Overview

The platform uses a database-backed DelayedJob pattern for asynchronous workloads. Jobs are organized by priority (CRITICAL, HIGH, DEFAULT, LOW, BULK) and processed by dedicated worker pods.

## Job Types

### Catalog Import Job

**Queue:** `catalog.import`
**Priority:** DEFAULT
**Handler:** `CatalogImportJobHandler`

#### Purpose

Imports product catalog data from CSV files uploaded by store administrators. Processes rows idempotently using SKU-based upsert logic, validates each row independently, and logs errors without failing the entire import.

#### Payload Schema

```json
{
  "jobId": "uuid",
  "tenantId": "uuid",
  "fileLocation": "string",
  "requestedBy": "string",
  "options": {
    "skipValidation": "boolean",
    "upsertMode": "string"
  },
  "idempotencyToken": "string",
  "createdAt": "timestamp",
  "version": 1
}
```

**Field Descriptions:**
- `jobId`: Unique identifier for this job execution
- `tenantId`: Tenant owning the catalog data
- `fileLocation`: Absolute path to CSV file or object storage key
- `requestedBy`: User ID or system identifier that initiated the import
- `options`: Optional processing flags
  - `skipValidation`: If true, bypass schema validation (use with caution)
  - `upsertMode`: "create-only" or "update-only" to restrict operation type
- `idempotencyToken`: Token for duplicate detection across retries
- `createdAt`: Job creation timestamp
- `version`: Payload schema version (current: 1)

#### CSV Format

The import CSV must have the following columns (header row required):

| Column | Required | Type | Description |
|--------|----------|------|-------------|
| `sku` | Yes | String | Unique product SKU (used for upsert matching) |
| `name` | Yes | String | Product name |
| `description` | No | String | Product description |
| `type` | No | Enum | Product type: `physical` or `digital` (default: `physical`) |
| `status` | No | Enum | Status: `draft`, `active`, or `archived` (default: `draft`) |

**Note:** This MVP version imports product records only. Variant pricing and inventory data must be managed separately through the product/variant admin APIs. Future enhancements will support variant import.

**Example:**
```csv
sku,name,description,type,status
WIDGET-001,Premium Widget,High quality widget,physical,active
WIDGET-002,Basic Widget,Entry level widget,physical,draft
```

See sample template: `docs/templates/catalog-import-sample.csv`

#### Retry Policy

- **Max Attempts:** 3
- **Initial Delay:** 1 second
- **Max Delay:** 5 minutes
- **Backoff Multiplier:** 2.0 (exponential)

#### Idempotency

Jobs are idempotent via SKU-based upsert logic. Re-running the same import file will update existing products and create new ones without duplication. The `idempotencyToken` prevents duplicate job enqueuing.

#### Error Handling

- **Row-level validation errors:** Logged with row number, SKU, and error message; processing continues for remaining rows
- **File read errors:** Job fails and enters retry cycle
- **Persistence errors:** Individual row failures logged; batch continues processing

#### Metrics

- `catalog.import.job.started` - Counter, tags: `tenant_id`
- `catalog.import.job.duration` - Timer, tags: `tenant_id`
- `catalog.import.job.failed` - Counter, tags: `tenant_id`
- `catalog.import.row.created` - Counter
- `catalog.import.row.updated` - Counter
- `catalog.import.row.error` - Counter, tags: `tenant_id`
- `catalog.import.enqueued` - Counter, tags: `tenant_id`
- `catalog.import.enqueue_rejected` - Counter, tags: `tenant_id`

#### Operator Workflow

1. **Enqueue Import:**
   ```bash
   curl -X POST https://{tenant}.storefront.com/api/v1/admin/catalog/import \
     -H "Authorization: Bearer {token}" \
     -H "Content-Type: application/json" \
     -d '{
       "fileLocation": "/tmp/catalog-import.csv",
       "requestedBy": "admin@example.com",
       "options": {}
     }'
   ```

2. **Monitor Queue Depth:**
   ```bash
   # Query Prometheus metrics
   catalog_import_queue_depth{priority="DEFAULT"}
   ```

3. **Check Job Status:**
   - Review application logs for job ID
   - Check success/error counters in metrics dashboard
   - Inspect row-level errors in logs

4. **Troubleshooting:**
    - If queue is full: Check worker pod health and scaling policies
    - If validation fails: Download error log, fix CSV, re-enqueue
    - If job enters DLQ: Review dead letter queue table for payload and error message

**Processing Cadence:** `CatalogJobScheduler` drains the `catalog.import` queue every 15 seconds (batch size 25) via the DelayedJob processor so HTTP threads are never blocked. Queue depth metrics surface at `catalog_import_queue_depth`.

---

### Catalog Export Job

**Queue:** `catalog.export`
**Priority:** DEFAULT
**Handler:** `CatalogExportJobHandler`

#### Purpose

Exports product catalog data to CSV format and uploads to R2/MinIO object storage. Returns signed download URL valid for 24 hours.

#### Payload Schema

```json
{
  "jobId": "uuid",
  "tenantId": "uuid",
  "requestedBy": "string",
  "filters": {
    "status": "string",
    "category": "string",
    "collection": "string"
  },
  "format": "csv",
  "createdAt": "timestamp",
  "version": 1
}
```

**Field Descriptions:**
- `jobId`: Unique identifier for this job execution
- `tenantId`: Tenant owning the catalog data
- `requestedBy`: User ID or system identifier that initiated the export
- `filters`: Optional filters to narrow export scope
  - `status`: Filter by product status (`draft`, `active`, `archived`)
  - `category`: Filter by category slug (future)
  - `collection`: Filter by collection slug (future)
- `format`: Export format (current: `csv`)
- `createdAt`: Job creation timestamp
- `version`: Payload schema version (current: 1)

#### Output Format

Exported CSV matches import schema for round-trip compatibility. See CSV format table above.

#### Retry Policy

- **Max Attempts:** 3
- **Initial Delay:** 1 second
- **Max Delay:** 5 minutes
- **Backoff Multiplier:** 2.0 (exponential)

#### Storage

- **Bucket:** Tenant-scoped object storage
- **Key Pattern:** `{tenantId}/catalog-exports/{jobId}.csv`
- **Signed URL Expiry:** 24 hours
- **Retention:** Exports are not automatically deleted; implement lifecycle policy for cleanup

#### Metrics

- `catalog.export.job.started` - Counter, tags: `tenant_id`
- `catalog.export.job.duration` - Timer, tags: `tenant_id`
- `catalog.export.job.completed` - Counter, tags: `tenant_id`
- `catalog.export.job.failed` - Counter, tags: `tenant_id`
- `catalog.export.rows` - Counter, tags: `tenant_id`
- `catalog.export.enqueued` - Counter, tags: `tenant_id`
- `catalog.export.enqueue_rejected` - Counter, tags: `tenant_id`

#### Operator Workflow

1. **Enqueue Export:**
   ```bash
   curl -X POST https://{tenant}.storefront.com/api/v1/admin/catalog/export \
     -H "Authorization: Bearer {token}" \
     -H "Content-Type: application/json" \
     -d '{
       "requestedBy": "admin@example.com",
       "filters": {"status": "active"},
       "format": "csv"
     }'
   ```

2. **Monitor Job Progress:**
   ```bash
   # Query Prometheus metrics
   catalog_export_queue_depth{priority="DEFAULT"}
   catalog_export_rows_total
   ```

3. **Retrieve Export:**
   - Export URL is returned in job response or via webhook
   - URL is valid for 24 hours
   - Re-run export if URL expires

4. **Troubleshooting:**
    - If export fails with OOM: Reduce filter scope or contact engineering for pagination tuning
    - If storage upload fails: Check R2/MinIO credentials and bucket permissions
    - If job enters DLQ: Review error message in dead letter queue table

**Processing Cadence:** `CatalogJobScheduler` drains the `catalog.export` queue every 30 seconds (batch size 25) and streams CSV output directly to R2/MinIO via `ReportStorageClient`. Adjust cadence/limits via scheduler configuration if backlog builds.

---

### Payout Reconciliation Job

**Queue:** `payments.payout.reconciliation`
**Priority:** HIGH
**Handler:** `PayoutReconciliationJobHandler`

#### Purpose

Reconciles consignment payouts with payment provider (Stripe Connect) payout batches. Verifies payout amounts match expected consignment totals, updates payout status, and flags discrepancies for manual review.

#### Payload Schema

```json
{
  "jobId": "uuid",
  "tenantId": "uuid",
  "payoutBatchId": "uuid",
  "providerPayoutId": "string",
  "expectedAmount": "decimal",
  "currency": "string",
  "periodStart": "date",
  "periodEnd": "date",
  "consignorId": "uuid",
  "idempotencyToken": "string",
  "createdAt": "timestamp",
  "version": 1
}
```

**Field Descriptions:**
- `jobId`: Unique identifier for this job execution
- `tenantId`: Tenant owning the payout
- `payoutBatchId`: Internal payout batch UUID
- `providerPayoutId`: Stripe payout ID (e.g., `po_xxx`)
- `expectedAmount`: Expected payout amount calculated from consignment sales
- `currency`: ISO 4217 currency code (e.g., `USD`)
- `periodStart`: Start of payout period (inclusive)
- `periodEnd`: End of payout period (inclusive)
- `consignorId`: Consignor UUID receiving the payout
- `idempotencyToken`: Token for duplicate detection across retries
- `createdAt`: Job creation timestamp
- `version`: Payload schema version (current: 1)

#### Reconciliation Logic

1. **Fetch Provider Payout:** Retrieve payout from Stripe using `providerPayoutId`
2. **Verify Amount:** Compare provider amount with `expectedAmount`
3. **Check Status:** Ensure payout status is `paid` or `in_transit`
4. **Update Local State:**
   - Set `PayoutBatch.status` to `completed` if payout succeeded
   - Set to `failed` if payout failed
   - Set to `pending_review` if amounts don't match
5. **Flag Discrepancies:** If amount mismatch exceeds tolerance (0.01), create admin alert
6. **Record Metrics:** Emit reconciliation success/failure counters

#### Retry Policy

- **Max Attempts:** 5
- **Initial Delay:** 5 seconds
- **Max Delay:** 10 minutes
- **Backoff Multiplier:** 2.0 (exponential)

**Note:** Higher retry count than default due to occasional Stripe API delays in payout finalization.

#### Idempotency

Jobs are idempotent via `idempotencyToken`. Re-running reconciliation for the same payout batch updates status without duplication. Provider payout status is retrieved fresh on each attempt.

#### Error Handling

- **Provider API errors:** Job enters retry cycle
- **Payout not found:** Job fails after max retries, enters DLQ
- **Amount mismatch:** Job succeeds but marks payout as `pending_review` and sends alert
- **Status mismatch:** If payout failed at provider, mark local status as `failed`

#### Metrics

- `payments.payout.reconciliation.started` - Counter, tags: `tenant_id`
- `payments.payout.reconciliation.duration` - Timer, tags: `tenant_id`
- `payments.payout.reconciliation.completed` - Counter, tags: `tenant_id`
- `payments.payout.reconciliation.failed` - Counter, tags: `tenant_id`, `reason`
- `payments.payout.reconciliation.discrepancy` - Counter, tags: `tenant_id`, `discrepancy_type`
- `payments.payout.enqueued` - Counter, tags: `tenant_id`
- `payments.payout.enqueue_rejected` - Counter, tags: `tenant_id`

#### Operator Workflow

1. **Automatic Enqueue:**
   - System automatically enqueues reconciliation job when webhook `payout.paid` received
   - Job runs in background to verify payout completion

2. **Monitor Queue Depth:**
   ```bash
   # Query Prometheus metrics
   payments_payout_reconciliation_queue_depth{priority="HIGH"}
   ```

3. **Check Reconciliation Status:**
   - Review `PayoutBatch` table for status updates
   - Check metrics dashboard for reconciliation success/failure rates
   - Inspect logs for discrepancy alerts

4. **Handle Discrepancies:**
   - Query `payout_batches` where `status = 'pending_review'`
   - Compare expected vs actual amounts
   - Manually adjust or contact provider support
   - Update status to `completed` after resolution

5. **Troubleshooting:**
   - If job fails repeatedly: Check Stripe API credentials and account status
   - If amount mismatches persist: Verify consignment calculation logic
   - If webhook delivery delayed: Payouts may enter reconciliation before webhook arrives; job will retry

**Processing Cadence:** `PaymentJobScheduler` drains the `payments.payout.reconciliation` queue every 10 seconds (batch size 10) to ensure timely payout confirmation. Queue capacity is 5,000 jobs (HIGH priority).

**Feature Flag:** `payments.payout.reconciliation.enabled` - Defaults to `true`, can be disabled globally or per-tenant to pause reconciliation during provider outages.

---

## Queue Configuration

All catalog jobs use the DEFAULT priority queue with the following settings:

- **Capacity:** 10,000 jobs (configurable via `jobs.queue.capacity.default`)
- **Retry Attempts:** 3 (configurable via `jobs.retry.max_attempts.default`)
- **Worker Concurrency:** Scaled via Kubernetes HPA based on queue depth

## Feature Flags

### `catalog.import.enabled`

Controls whether catalog import functionality is available. Can be toggled at tenant level or globally.

**Default:** `false` (disabled)

**Scope:** Tenant-specific override supported

**Emergency Kill Switch:** Yes - disable immediately if import jobs cause data corruption or performance degradation

**Rollout Strategy:**
1. Enable for internal testing tenant
2. Enable for beta cohort (via tenant overrides)
3. Enable globally after 2-week beta period

## References

- Task: I2.T5 - Catalog Import/Export Foundation
- Architecture: `docs/architecture/04_Operational_Architecture.md` (Section 3.6)
- KPI Target: 5k products/minute import throughput
- Standards: `docs/java-project-standards.adoc`
