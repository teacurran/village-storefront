# Reporting Projections & Export System

## Overview

The reporting projection system provides read-optimized aggregate tables and async export capabilities for analytics, dashboards, and compliance reporting. Built on event-driven architecture principles, the system maintains eventual consistency with configurable freshness SLAs.

**References:**
- Task: I3.T3 - Reporting Projection Service
- Architecture: `docs/architecture/02_System_Structure_and_Data.md`
- Architecture: `docs/architecture/04_Operational_Architecture.md` (Section 3.6)

---

## Architecture

### Components

1. **Projection Service** (`ReportingProjectionService`)
   - Consumes domain events from catalog, orders (carts), and consignment modules
   - Computes and persists read-optimized aggregates
   - Emits metrics for freshness tracking and SLA monitoring

2. **Job Service** (`ReportingJobService`)
   - Manages async export job queue
   - Generates CSV reports from aggregates
   - Uploads to Cloudflare R2 and generates signed download URLs

3. **Scheduled Jobs** (`ReportingScheduledJobs`)
   - Automatic aggregate refresh on configurable intervals
   - Queue processing for exports
   - Multi-tenant iteration with isolated context

4. **REST API** (`ReportsResource`)
   - Query aggregate data for dashboards
   - Request async exports
   - Poll job status and retrieve download URLs

5. **Storage Client** (`ReportStorageClient`)
   - Abstraction for cloud object storage (Cloudflare R2)
   - Stub implementation for testing
   - Signed URL generation with configurable expiry

---

## Aggregate Tables

### Sales by Period Aggregate

**Table:** `sales_by_period_aggregates`

**Purpose:** Pre-computed sales totals by time period for dashboard widgets and trend analysis.

**Schema:**
```sql
id                      UUID PRIMARY KEY
tenant_id               UUID NOT NULL (FK to tenants)
period_start            DATE NOT NULL
period_end              DATE NOT NULL
total_amount            DECIMAL(19,4) DEFAULT 0
item_count              INTEGER DEFAULT 0
order_count             INTEGER DEFAULT 0
data_freshness_timestamp TIMESTAMPTZ NOT NULL
job_name                VARCHAR(255) NOT NULL
created_at              TIMESTAMPTZ NOT NULL
updated_at              TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, period_start, period_end)
```

**Refresh Schedule:** Every 15 minutes
**Data Source:** Cart/order data (MVP uses carts as proxy)
**SLA:** < 15 minutes lag

---

### Consignment Payout Aggregate

**Table:** `consignment_payout_aggregates`

**Purpose:** Pre-computed amounts owed to consignors per period for payout batch generation.

**Schema:**
```sql
id                      UUID PRIMARY KEY
tenant_id               UUID NOT NULL (FK to tenants)
consignor_id            UUID NOT NULL (FK to consignors)
period_start            DATE NOT NULL
period_end              DATE NOT NULL
total_owed              DECIMAL(19,4) DEFAULT 0
item_count              INTEGER DEFAULT 0
items_sold              INTEGER DEFAULT 0
data_freshness_timestamp TIMESTAMPTZ NOT NULL
job_name                VARCHAR(255) NOT NULL
created_at              TIMESTAMPTZ NOT NULL
updated_at              TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, consignor_id, period_start, period_end)
```

**Refresh Schedule:** Every 30 minutes
**Data Source:** Consignment item sales
**SLA:** < 30 minutes lag
**Payout Calculation:** `salePrice * (1 - commissionRate/100)`

---

### Inventory Aging Aggregate

**Table:** `inventory_aging_aggregates`

**Purpose:** Track days in stock for slow-mover analysis and clearance decisions.

**Schema:**
```sql
id                      UUID PRIMARY KEY
tenant_id               UUID NOT NULL (FK to tenants)
variant_id              UUID NOT NULL (FK to product_variants)
location_id             UUID NOT NULL (FK to inventory_locations)
days_in_stock           INTEGER DEFAULT 0
quantity                INTEGER DEFAULT 0
first_received_at       TIMESTAMPTZ
data_freshness_timestamp TIMESTAMPTZ NOT NULL
job_name                VARCHAR(255) NOT NULL
created_at              TIMESTAMPTZ NOT NULL
updated_at              TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, variant_id, location_id)
```

**Refresh Schedule:** Every hour
**Data Source:** Inventory levels and transfer history
**SLA:** < 1 hour lag

---

## Report Jobs

**Table:** `report_jobs`

**Purpose:** Track async export job status and provide download URLs.

**Schema:**
```sql
id              UUID PRIMARY KEY
tenant_id       UUID NOT NULL (FK to tenants)
report_type     VARCHAR(50) NOT NULL (sales_by_period, consignment_payout, inventory_aging)
status          VARCHAR(20) DEFAULT 'pending' (pending, running, completed, failed)
requested_by    VARCHAR(255)
parameters      TEXT (JSON blob)
result_url      VARCHAR(2048) (signed download URL)
error_message   TEXT
started_at      TIMESTAMPTZ
completed_at    TIMESTAMPTZ
created_at      TIMESTAMPTZ NOT NULL
updated_at      TIMESTAMPTZ NOT NULL
```

**Lifecycle:**
1. `pending` - Job enqueued, awaiting processing
2. `running` - Export generation in progress
3. `completed` - Report uploaded to R2, signed URL available
4. `failed` - Error occurred (see `error_message`)

---

## Scheduled Refresh Jobs

### Sales Aggregates
- **Schedule:** `0 */15 * * * ?` (every 15 minutes)
- **Scope:** Yesterday + Today (captures late-arriving data)
- **Job ID:** `refresh-sales-aggregates`

### Consignment Payouts
- **Schedule:** `0 */30 * * * ?` (every 30 minutes)
- **Scope:** Current month-to-date
- **Job ID:** `refresh-consignment-payouts`

### Inventory Aging
- **Schedule:** `0 0 * * * ?` (every hour)
- **Scope:** All active inventory levels
- **Job ID:** `refresh-inventory-aging`

### Export Queue Processing
- **Schedule:** `0 */5 * * * ?` (every 5 minutes)
- **Batch Limit:** 50 jobs per cycle
- **Job ID:** `process-export-queue`

---

## REST API Endpoints

### Query Aggregates

**GET** `/api/v1/admin/reports/aggregates/sales`

Query sales aggregates for dashboard widgets.

**Query Parameters:**
- `startDate` (optional): ISO 8601 date (YYYY-MM-DD)
- `endDate` (optional): ISO 8601 date (YYYY-MM-DD)

**Response:** Array of `SalesByPeriodAggregate`

---

**GET** `/api/v1/admin/reports/aggregates/consignment-payouts`

Query consignment payout aggregates.

**Query Parameters:**
- `consignorId` (optional): UUID
- `startDate` (optional): ISO 8601 date
- `endDate` (optional): ISO 8601 date

**Response:** Array of `ConsignmentPayoutAggregate`

---

**GET** `/api/v1/admin/reports/aggregates/inventory-aging`

Query inventory aging aggregates.

**Query Parameters:**
- `locationId` (optional): UUID
- `minDays` (optional): Integer threshold for slow movers

**Response:** Array of `InventoryAgingAggregate`

---

### Request Export

**POST** `/api/v1/admin/reports/{reportType}/export`

Request async export job.

**Path Parameters:**
- `reportType`: `sales`, `consignment-payouts`, or `inventory-aging`

**Request Body:**
```json
{
  "format": "csv",
  "startDate": "2026-01-01",
  "endDate": "2026-01-31",
  "requestedBy": "user@example.com"
}
```

**Response (202 Accepted):**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "pending",
  "message": "Export job enqueued successfully"
}
```

---

### Get Job Status

**GET** `/api/v1/admin/reports/jobs/{jobId}`

Poll export job status and retrieve download URL.

**Response (completed):**
```json
{
  "jobId": "550e8400-e29b-41d4-a716-446655440000",
  "reportType": "sales_by_period",
  "status": "completed",
  "createdAt": "2026-01-03T10:00:00Z",
  "startedAt": "2026-01-03T10:05:00Z",
  "completedAt": "2026-01-03T10:05:30Z",
  "downloadUrl": "https://r2.example.com/reports/550e8400.../file.csv?expires=..."
}
```

**Response (failed):**
```json
{
  "jobId": "...",
  "status": "failed",
  "error": "Unknown report type: invalid_type",
  "completedAt": "2026-01-03T10:05:30Z"
}
```

---

### List Jobs

**GET** `/api/v1/admin/reports/jobs`

List recent export jobs for current tenant.

**Query Parameters:**
- `page` (default: 0)
- `size` (default: 20)

**Response:**
```json
{
  "jobs": [...],
  "page": 0,
  "size": 20,
  "totalCount": 42
}
```

---

## Storage & Signed URLs

### Cloudflare R2 Integration

**Configuration (`application.properties`):**
```properties
reporting.storage.r2.endpoint=https://<account-id>.r2.cloudflarestorage.com
reporting.storage.r2.bucket=village-reports
reporting.storage.r2.access-key-id=<key>
reporting.storage.r2.secret-access-key=<secret>
reporting.storage.r2.region=auto
```

**Enable R2 Client:**
```properties
quarkus.arc.selected-alternatives=villagecompute.storefront.reporting.R2ReportStorageClient
```

### Signed URL Behavior

- **Expiry:** 24 hours (configurable via `DEFAULT_SIGNED_URL_EXPIRY`)
- **Format:** `https://<bucket>.r2.cloudflarestorage.com/reports/<tenant>/<reportType>/<jobId>.<format>?expires=...`
- **Security:** URLs expire automatically; no persistent public access

---

## SLA Monitoring & Freshness Tracking

### Freshness Metadata

Each aggregate record includes:
- `data_freshness_timestamp`: Timestamp of last refresh
- `job_name`: Scheduled job that produced the data

### Metrics

**Gauges:**
- `reporting.aggregate.freshness.lag.seconds` - Time since last refresh (tagged by `aggregate_type`, `tenant_id`)
- `reporting.refresh.queue.depth` - Pending refresh jobs
- `reporting.export.queue.depth` - Pending export jobs

**Counters:**
- `reporting.aggregate.refresh.started` - Refresh job starts (tagged by `aggregate_type`)
- `reporting.aggregate.refresh.completed` - Successful refreshes
- `reporting.aggregate.refresh.failed` - Failed refreshes
- `reporting.job.enqueued` - Jobs added to queue (tagged by `type`, `aggregate_type` or `report_type`)
- `reporting.job.started` - Jobs started processing
- `reporting.job.failed` - Job failures

**Timers:**
- `reporting.aggregate.refresh.duration` - Refresh job execution time
- `reporting.job.duration` - Export job execution time

### SLA Targets

| Aggregate Type       | Target Lag | Refresh Interval | Status       |
|----------------------|------------|------------------|--------------|
| Sales by Period      | < 15 min   | 15 minutes       | ✅ Compliant  |
| Consignment Payout   | < 30 min   | 30 minutes       | ✅ Compliant  |
| Inventory Aging      | < 1 hour   | 1 hour           | ✅ Compliant  |

---

## Retention & Archival

### Current Policy (MVP)

- **Aggregates:** Retained indefinitely (partitioning TBD)
- **Report Jobs:** 90-day retention (automated cleanup TBD)
- **Exported Files (R2):** 30-day TTL (lifecycle policy TBD)

### Future Enhancements

1. **Partition aggregates by month** for efficient querying and archival
2. **Background job** to archive completed report jobs > 90 days old
3. **R2 lifecycle policy** to auto-delete exports after 30 days

---

## Troubleshooting

### Lagging Aggregates

**Symptom:** `data_freshness_timestamp` shows lag > SLA
**Diagnosis:**
1. Check `reporting.aggregate.freshness.lag.seconds` metric
2. Review scheduled job logs for errors
3. Inspect `reporting.aggregate.refresh.failed` counter

**Resolution:**
- Verify Quarkus Scheduler is running (`/q/health`)
- Check database connectivity and query performance
- Review tenant count and consider scaling

---

### Failed Export Jobs

**Symptom:** Job status = `failed`, `error_message` populated
**Common Errors:**
- `Unknown report type` - Invalid `reportType` parameter
- `Failed to generate report data` - CSV generation exception
- `R2 upload failed` - Storage client error (check R2 credentials)

**Resolution:**
1. Inspect `report_jobs.error_message` for details
2. Review `reporting.job.failed` counter and logs
3. Retry export request with corrected parameters

---

### Stub Storage in Tests

**Default Behavior:** `StubReportStorageClient` is used unless `R2ReportStorageClient` is explicitly selected.

**Test Assertions:**
```java
@Inject
StubReportStorageClient stubStorageClient;

// Check if report was uploaded
assertTrue(stubStorageClient.reportExists(objectKey));

// Retrieve stored report data
StubReportStorageClient.StoredReport report = stubStorageClient.getStoredReport(objectKey);
assertEquals("text/csv", report.getContentType());
String csvContent = new String(report.getData());
```

**Cleanup:**
```java
@AfterEach
public void cleanup() {
    stubStorageClient.clear();
}
```

---

## Development Workflow

### Local Testing

1. Start Quarkus dev mode: `./mvnw quarkus:dev`
2. Create test data (carts, consignments, inventory)
3. Manually trigger refresh: Call projection service methods
4. Query aggregates: `GET /api/v1/admin/reports/aggregates/{type}`
5. Request export: `POST /api/v1/admin/reports/{type}/export`
6. Poll job status: `GET /api/v1/admin/reports/jobs/{jobId}`
7. Inspect stub storage: Use `stubStorageClient.getStoredReport()`

### Integration Tests

Run tests:
```bash
./mvnw test -Dtest=ReportingProjectionTest
./mvnw test -Dtest=ReportExportIT
```

Coverage targets:
- Line coverage: ≥ 80%
- Branch coverage: ≥ 80%

---

## Migration Guide

### Enable R2 Production Storage

1. Add AWS SDK dependency to `pom.xml`:
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
```

2. Configure `application.properties`:
```properties
quarkus.arc.selected-alternatives=villagecompute.storefront.reporting.R2ReportStorageClient
reporting.storage.r2.endpoint=https://<account-id>.r2.cloudflarestorage.com
reporting.storage.r2.bucket=village-reports
reporting.storage.r2.access-key-id=${R2_ACCESS_KEY}
reporting.storage.r2.secret-access-key=${R2_SECRET_KEY}
```

3. Uncomment S3 client initialization in `R2ReportStorageClient.java`

---

## Future Enhancements

### Planned Features (Post-MVP)

1. **Real-time Event Streaming**
   - Replace direct repository access with domain event consumers
   - Kafka or equivalent for event sourcing

2. **Advanced Export Formats**
   - PDF generation with charts
   - Excel (XLSX) with formulas and pivot tables

3. **Custom Report Builder**
   - User-defined filters, grouping, and calculations
   - Saved report templates

4. **Scheduled Exports**
   - Recurring export jobs (daily, weekly, monthly)
   - Email delivery integration

5. **Dashboard Embedding**
   - Pre-built chart widgets consuming aggregate APIs
   - Real-time freshness indicators

---

## References

- [Architecture: System Structure & Data](../architecture/02_System_Structure_and_Data.md)
- [Architecture: Operational Architecture](../architecture/04_Operational_Architecture.md)
- [ADR-001: Tenancy Strategy](../adr/ADR-001-tenancy.md)
- [Cloudflare R2 Documentation](https://developers.cloudflare.com/r2/)
- [Quarkus Scheduler Guide](https://quarkus.io/guides/scheduler)
