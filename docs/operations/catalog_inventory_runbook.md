# Catalog & Inventory Operational Runbook

**Task:** I2.T7
**Last Updated:** 2026-01-08
**Owner:** Platform Operations / Catalog & Inventory Domain Squad
**Related Docs:** [System Structure](../../.codemachine/artifacts/architecture/02_System_Structure_and_Data.md) | [Operational Architecture §3.7](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#3-7-observability-fabric) | [Architecture §4](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#4-component-kpis)

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture Components](#architecture-components)
3. [Normal Operations](#normal-operations)
4. [Scaling Procedures](#scaling-procedures)
5. [Failure Scenarios](#failure-scenarios)
6. [Verification Metrics](#verification-metrics)
7. [Kill Switches](#kill-switches)
8. [Capacity Planning](#capacity-planning)
9. [Appendix: Configuration Reference](#appendix-configuration-reference)

---

## Overview

The Catalog & Inventory domain manages multi-tenant product catalogs, variants, categories, collections, and multi-location inventory tracking for the Village Storefront platform.

### Key Capabilities

1. **Catalog Management** → Products, variants, categories, collections with tenant isolation
2. **Bulk Operations** → Import/export jobs for catalog data (CSV/JSON formats)
3. **Inventory Tracking** → Multi-location levels, reservations, transfers, adjustments
4. **Search & Browse** → Product search, category/collection filtering with caching

### Key Principles

- **Tenant Isolation**: All data scoped by `tenant_id` with RLS enforcement
- **Caching Strategy**: Caffeine caches root categories, featured products (15-minute TTL)
- **Import/Export**: Background jobs for bulk catalog operations (DEFAULT priority queue)
- **Inventory Safety**: Reservation-based stock management prevents overselling
- **Observability**: Prometheus metrics, OpenTelemetry spans with `tenant_id` attributes

**Visual Reference:** See [System Structure](../../.codemachine/artifacts/architecture/02_System_Structure_and_Data.md) for domain model diagrams.

---

## Architecture Components

### Code Modules

| Component | Location | Responsibility |
|-----------|----------|----------------|
| **CatalogAdminResource** | `villagecompute.storefront.api.rest.CatalogAdminResource` | REST API for category, collection, import/export |
| **InventoryAdminResource** | `villagecompute.storefront.api.rest.InventoryAdminResource` | REST API for locations, transfers, adjustments |
| **CatalogService** | `villagecompute.storefront.services.CatalogService` | Business logic for products, categories, search |
| **InventoryService** | `villagecompute.storefront.services.InventoryService` | Business logic for levels, reservations, commits |
| **InventoryTransferService** | `villagecompute.storefront.services.InventoryTransferService` | Multi-location transfer orchestration |
| **CatalogMetrics** | `villagecompute.storefront.services.metrics.CatalogMetrics` | Centralized catalog metrics helper |
| **InventoryMetrics** | `villagecompute.storefront.services.metrics.InventoryMetrics` | Centralized inventory metrics helper |
| **CatalogJobService** | `villagecompute.storefront.services.CatalogJobService` | Import/export job orchestration |

### Database Tables

#### Catalog Domain
- `products` – Product master data (SKU, name, slug, type, status)
- `product_variants` – Variant matrix (size, color, price, inventory tracking)
- `categories` – Hierarchical category tree (code, slug, parent_id)
- `collections` – Marketing collections (manual/automatic selection rules)
- `product_category_mappings` – M:N product ↔ category associations
- `product_collection_mappings` – M:N product ↔ collection associations

#### Inventory Domain
- `inventory_levels` – Variant stock per location (quantity, reserved)
- `inventory_locations` – Location master (code, name, type, address)
- `inventory_transfers` – Transfer headers (source, destination, status)
- `inventory_transfer_lines` – Transfer line items (variant, quantity)
- `inventory_adjustments` – Audit trail for manual adjustments (reason, adjusted_by)

---

## Normal Operations

### Health Indicators

Monitor these signals to confirm normal operation:

#### Key Metrics (Prometheus)

```promql
# Catalog operations (create/update/delete rates)
rate(catalog_product_created_total{tenant="*"}[5m])
rate(catalog_category_created_total{tenant="*"}[5m])
rate(catalog_collection_created_total{tenant="*"}[5m])

# Product search latency (p50, p95, p99)
histogram_quantile(0.95, catalog_product_search_duration_seconds_bucket{tenant="*"})

# Import/export job throughput
rate(catalog_import_success_total{tenant="*"}[5m])
rate(catalog_export_success_total{tenant="*"}[5m])

# Import/export job failures
rate(catalog_import_failed_total{tenant="*",reason="*"}[5m])
rate(catalog_export_failed_total{tenant="*",reason="*"}[5m])

# Inventory operations (reservations, commits, adjustments)
rate(inventory_reserved_total{tenant="*"}[5m])
rate(inventory_committed_total{tenant="*"}[5m])
rate(inventory_adjustment_total{tenant="*",location="*",reason="*"}[5m])

# Inventory transfer operations
rate(inventory_transfer_created_total{tenant="*",source="*",destination="*"}[5m])
rate(inventory_transfer_received_total{tenant="*",destination="*"}[5m])

# API endpoint latency (JAX-RS @Timed metrics)
histogram_quantile(0.95, catalog_admin_category_create_seconds_bucket)
histogram_quantile(0.95, inventory_admin_transfer_create_seconds_bucket)
```

**Dashboard:** Grafana > Village Storefront > Catalog & Inventory Health

#### Expected Baselines (Foundation §6.0 KPIs)

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| Product search latency (p95) | <300ms | >500ms for 5 min |
| Category list latency (p95) | <100ms | >200ms for 5 min |
| Import job success rate | >99% | <98% for 10 min |
| Inventory reservation latency (p95) | <50ms | >100ms for 5 min |
| Transfer creation latency (p95) | <200ms | >500ms for 5 min |
| Cache hit rate (root categories) | >90% | <80% for 10 min |

#### Health Endpoints

```bash
# Application liveness (JVM health, DB connectivity)
curl http://village-storefront:8080/q/health/live

# Application readiness (tenant resolution, cache warmup)
curl http://village-storefront:8080/q/health/ready
```

**Expected responses:** Both should return HTTP 200 with `{"status":"UP"}`.

### Routine Monitoring Tasks

**Daily:**
- Review import/export job failure rates: `SELECT COUNT(*) FROM delayed_jobs WHERE owning_module='catalog.import' AND status='failed'`
- Check for stuck transfers (>7 days in transit): `SELECT * FROM inventory_transfers WHERE status='transit' AND created_at < NOW() - INTERVAL '7 days'`
- Verify cache invalidation working: Check `catalogCacheService` logs for invalidation events

**Weekly:**
- Audit top 10 tenants by product count for abuse patterns
- Review inventory adjustments by reason: `SELECT reason, COUNT(*) FROM inventory_adjustments WHERE created_at > NOW() - INTERVAL '7 days' GROUP BY reason`
- Validate search performance trends (p95 latency should be stable)

**Monthly:**
- Test kill switch procedures in staging (see Kill Switches section)
- Review and optimize slow queries (catalog search, inventory availability checks)
- Archive old inventory adjustments (>90 days) to cold storage

---

## Scaling Procedures

### Detecting Scale Needs

**Trigger conditions:**

1. **High API Latency**: p95 response times exceed targets (>500ms search, >200ms category list)
2. **Import Queue Backlog**: Import jobs queued >30 minutes (check `delayed_jobs` where `owning_module='catalog.import'`)
3. **Cache Miss Spike**: Cache hit rate <80% sustained for >10 minutes
4. **Database CPU**: PostgreSQL CPU >70% sustained with slow catalog queries
5. **Anticipated Load**: Known event (product launch, sale) expected to spike catalog traffic

### Horizontal Scaling (Application Pods)

#### Increase API Replica Count

```bash
# Scale to 5 replicas immediately
kubectl scale deployment village-storefront --replicas=5

# Verify new pods are ready
kubectl get pods -l app=village-storefront -w

# Check logs for successful tenant resolution
kubectl logs -l app=village-storefront --tail=50 | grep "TenantResolutionFilter"
```

**Rollback:**
```bash
kubectl scale deployment village-storefront --replicas=3
```

### Vertical Scaling (Database)

#### Increase PostgreSQL Resources

For catalog/inventory queries hitting CPU limits:

1. **Identify slow queries**:
```sql
SELECT query, mean_exec_time, calls
FROM pg_stat_statements
WHERE query ILIKE '%products%' OR query ILIKE '%inventory_levels%'
ORDER BY mean_exec_time * calls DESC
LIMIT 10;
```

2. **Add missing indexes**:
```sql
-- Product search performance
CREATE INDEX CONCURRENTLY idx_products_name_search ON products USING gin(to_tsvector('english', name));

-- Inventory availability lookups
CREATE INDEX CONCURRENTLY idx_inventory_levels_variant_location ON inventory_levels(variant_id, location) WHERE quantity > 0;
```

3. **Upgrade PostgreSQL instance** (via cloud provider console or Kubernetes operator)

### Cache Tuning

#### Increase Caffeine Cache Size

Edit `application.properties`:

```properties
# Increase cache sizes for high-traffic tenants
catalog.cache.root-categories.max-size=500  # Default: 100
catalog.cache.featured-products.max-size=1000  # Default: 200
catalog.cache.ttl-minutes=15  # Keep at 15 for consistency
```

Redeploy application:
```bash
kubectl rollout restart deployment village-storefront
kubectl rollout status deployment village-storefront
```

**Effect:** Reduces database load for frequently accessed catalog data.

### Import/Export Job Scaling

If import/export jobs are backing up:

1. **Increase queue capacity**:
```properties
# Edit application.properties
jobs.queue.capacity.default=15000  # Default: 10000 (includes catalog jobs)
```

2. **Increase worker threads** (if supported):
```properties
# Tune Quarkus worker pool (affects all background jobs)
quarkus.thread-pool.max-threads=50  # Default: 30
```

3. **Redeploy and monitor**:
```bash
kubectl rollout restart deployment village-storefront-workers
```

---

## Failure Scenarios

### Scenario 1: Product Search Latency Spike

**Symptoms:**
- `catalog_product_search_duration_seconds{quantile="0.95"}` >500ms for >5 minutes
- Prometheus alert: `CatalogSearchLatency` firing
- Customer complaints about slow storefront search

**Detection:**
```bash
# Check current search latency
kubectl exec -it $(kubectl get pod -l app=village-storefront -o name | head -1) -- \
  curl -s localhost:8080/q/metrics | grep catalog_product_search_duration

# Review recent search queries in logs
kubectl logs -l app=village-storefront --tail=100 | grep "Searching products"
```

**Response Steps:**

1. **Identify problematic tenants** (check if specific tenant causing spike):
```sql
-- Find tenants with longest search durations (requires application logs analysis)
SELECT tenant_id, COUNT(*), AVG(duration_ms)
FROM application_logs  -- Hypothetical structured log table
WHERE operation='product_search' AND timestamp > NOW() - INTERVAL '10 minutes'
GROUP BY tenant_id
ORDER BY AVG(duration_ms) DESC
LIMIT 10;
```

2. **Check database query performance**:
```sql
-- Identify slow search queries
SELECT query, mean_exec_time, calls
FROM pg_stat_statements
WHERE query ILIKE '%searchProducts%'
ORDER BY mean_exec_time * calls DESC
LIMIT 5;
```

3. **Add missing indexes** (if identified):
```sql
CREATE INDEX CONCURRENTLY idx_products_search_optimized
ON products USING gin((name || ' ' || description) gin_trgm_ops)
WHERE tenant_id IS NOT NULL AND status = 'active';
```

4. **Restart application** to clear any stuck requests:
```bash
kubectl rollout restart deployment village-storefront
```

5. **Monitor recovery**:
```bash
watch -n 10 'kubectl exec -it $(kubectl get pod -l app=village-storefront -o name | head -1) -- curl -s localhost:8080/q/metrics | grep catalog_product_search_duration'
```

**Prevention:**
- Set up proactive alerting at p95 >300ms (earlier warning)
- Implement search query complexity limits (max term length, wildcard restrictions)
- Pre-warm search cache for top 10 search terms per tenant

---

### Scenario 2: Import Job Failures

**Symptoms:**
- `catalog_import_failed_total{reason="validation_error"}` spiking
- Admin reports CSV import rejections with HTTP 400
- `delayed_jobs` table shows failed jobs with `owning_module='catalog.import'`

**Detection:**
```bash
# Check import failure rate
kubectl exec -it $(kubectl get pod -l app=village-storefront -o name | head -1) -- \
  curl -s localhost:8080/q/metrics | grep catalog_import_failed_total

# Review failed jobs in database
kubectl exec -it postgres-0 -- psql -U storefront -c \
  "SELECT id, tenant_id, error_message, attempt_count FROM delayed_jobs WHERE owning_module='catalog.import' AND status='failed' ORDER BY created_at DESC LIMIT 10;"
```

**Response Steps:**

1. **Identify failure reasons**:
```sql
-- Aggregate failures by reason
SELECT jsonb_extract_path_text(payload, 'options', 'fileLocation') AS file_location,
       error_message,
       COUNT(*)
FROM delayed_jobs
WHERE owning_module = 'catalog.import'
  AND status = 'failed'
  AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY file_location, error_message
ORDER BY COUNT(*) DESC;
```

2. **Download and inspect failing CSV** (if storage error):
```bash
# Use R2 CLI or AWS S3 CLI (R2 is S3-compatible)
aws s3 cp s3://village-media/{tenantId}/imports/{filename}.csv /tmp/debug.csv --endpoint-url=https://...

# Check file format
head -n 5 /tmp/debug.csv
```

3. **Validate CSV schema** against expected format:
- Required columns: `sku`, `name`, `price`, `status`
- Common errors: Missing headers, invalid UTF-8 encoding, malformed JSON in metadata columns

4. **Fix validation logic** (if bug identified):
   - Update `CatalogJobService.enqueueImport()` or import handler
   - Deploy fix to staging, validate with failing CSV
   - Promote to production

5. **Retry failed jobs** manually (after fix):
```bash
# Trigger retry via admin API
curl -X POST https://platform.domain.com/admin/api/catalog/import/{jobId}/retry \
  -H "Authorization: Bearer {admin-token}"
```

**Prevention:**
- Implement CSV schema validation endpoint (pre-validate before job enqueue)
- Add comprehensive import format documentation with examples
- Set up sample CSV download endpoint with correct format

---

### Scenario 3: Inventory Reservation Failures

**Symptoms:**
- `inventory_reservation_failed_total{reason="insufficient_stock"}` increasing
- Checkout errors: "Product out of stock" despite showing available on storefront
- `inventory_levels.reserved` values stuck high (not releasing)

**Detection:**
```bash
# Check reservation failure rate
kubectl exec -it $(kubectl get pod -l app=village-storefront -o name | head -1) -- \
  curl -s localhost:8080/q/metrics | grep inventory_reservation_failed

# Identify variants with high reserved vs. quantity
kubectl exec -it postgres-0 -- psql -U storefront -c \
  "SELECT v.sku, il.location, il.quantity, il.reserved, (il.quantity - il.reserved) AS available FROM inventory_levels il JOIN product_variants v ON il.variant_id = v.id WHERE il.reserved > il.quantity ORDER BY il.reserved DESC LIMIT 20;"
```

**Response Steps:**

1. **Identify stuck reservations** (expired orders holding stock):
```sql
-- Find reservations older than 2 hours (typical cart expiry)
SELECT o.id, o.tenant_id, o.created_at, oli.variant_id, oli.quantity
FROM orders o
JOIN order_line_items oli ON o.id = oli.order_id
WHERE o.status = 'pending'
  AND o.created_at < NOW() - INTERVAL '2 hours';
```

2. **Release expired reservations** (via background job or manual):
```java
// Trigger via admin endpoint or scheduled job
inventoryService.releaseReservation(variantId, location, quantity);
```

3. **Audit inventory levels** for inconsistencies:
```sql
-- Check for negative available quantities (data corruption)
SELECT variant_id, location, quantity, reserved, (quantity - reserved) AS available
FROM inventory_levels
WHERE (quantity - reserved) < 0;
```

4. **Reconcile inventory** (if corruption found):
```sql
-- Reset reserved count for affected variants (use with extreme caution!)
UPDATE inventory_levels
SET reserved = 0
WHERE id IN (SELECT id FROM inventory_levels WHERE (quantity - reserved) < 0);
```

5. **Re-sync reservations** from active orders:
```sql
-- Recalculate reserved counts from pending orders
UPDATE inventory_levels il
SET reserved = COALESCE((
  SELECT SUM(oli.quantity)
  FROM order_line_items oli
  JOIN orders o ON oli.order_id = o.id
  WHERE oli.variant_id = il.variant_id
    AND o.status IN ('pending', 'confirmed')
), 0);
```

**Prevention:**
- Implement scheduled job to release reservations for abandoned carts (>2 hours)
- Add database constraints: `CHECK (reserved <= quantity)`
- Set up alerting for negative available quantities
- Use database transactions with row-level locking for reservation operations

---

### Scenario 4: Transfer Stuck in Transit

**Symptoms:**
- `inventory_transfers` table shows transfers with `status='transit'` for >7 days
- Staff reports inability to receive shipments at destination location
- Metrics show low `inventory_transfer_received_total` rate

**Detection:**
```sql
-- Find old in-transit transfers
SELECT id, tenant_id, source_location_id, destination_location_id, created_at, tracking_number
FROM inventory_transfers
WHERE status = 'transit'
  AND created_at < NOW() - INTERVAL '7 days'
ORDER BY created_at;
```

**Response Steps:**

1. **Investigate transfer status**:
   - Check carrier tracking via `tracking_number` field
   - Contact destination location staff for physical receipt confirmation

2. **Manually receive transfer** (if physically received but not marked in system):
```bash
# Via admin API
curl -X POST https://platform.domain.com/admin/api/inventory/transfers/{transferId}/receive \
  -H "Authorization: Bearer {admin-token}"
```

3. **Cancel transfer** (if lost or returned):
```sql
-- Update transfer status to cancelled
UPDATE inventory_transfers
SET status = 'cancelled',
    updated_at = NOW()
WHERE id = '{transfer-uuid}';

-- Release reserved inventory at source location
UPDATE inventory_levels
SET reserved = reserved - (
  SELECT SUM(quantity)
  FROM inventory_transfer_lines
  WHERE transfer_id = '{transfer-uuid}'
    AND inventory_levels.variant_id = inventory_transfer_lines.variant_id
);
```

4. **Audit barcode generation job** (if transfers consistently stuck):
   - Check if barcode generation jobs are failing: `SELECT * FROM delayed_jobs WHERE owning_module='inventory.barcode' AND status='failed'`
   - Verify barcode rendering service connectivity

**Prevention:**
- Set up automated alerts for transfers >7 days in transit
- Implement auto-cancellation policy (e.g., 30 days without movement)
- Add carrier webhook integration for automatic status updates

---

## Verification Metrics

Section 6 of the [Blueprint Foundation](../../.codemachine/artifacts/architecture/01_Blueprint_Foundation.md#section-6-risk-mitigations) mandates verification metrics for every domain workload. Use the signals below to demonstrate recovery during incidents.

| Section 6 verification metric | Catalog/Inventory signal | Dashboard / alert |
|------------------------------|--------------------------|-------------------|
| API latency budgets | `histogram_quantile(0.95, catalog_admin_category_create_seconds_bucket)`, `histogram_quantile(0.95, inventory_admin_transfer_create_seconds_bucket)` | Grafana: *Catalog & Inventory Latency* panel, alert `CatalogAPILatency` |
| Operation throughput | `rate(catalog_product_created_total{tenant="*"}[5m])`, `rate(inventory_reserved_total{tenant="*"}[5m])` | Grafana: *Throughput* panel, alert `CatalogThroughputDrop` |
| Failure + error rate | `rate(catalog_import_failed_total{tenant="*",reason="*"}[5m])`, `rate(inventory_reservation_failed_total{tenant="*",reason="*"}[5m])` | Grafana: *Failure Funnel*, alert `CatalogFailureSpike` |
| Search performance | `histogram_quantile(0.95, catalog_product_search_duration_seconds_bucket{tenant="*"})` | Grafana: *Search Latency* panel, alert `CatalogSearchLatency` |
| Cache efficiency | `catalog_cache_hit_rate{cache="root-categories"}` (if instrumented) | Grafana: *Cache Performance*, alert `CatalogCacheMiss` |
| Job completion rate | `rate(catalog_import_success_total{tenant="*"}[5m]) / (rate(catalog_import_success_total{tenant="*"}[5m]) + rate(catalog_import_failed_total{tenant="*"}[5m]))` | Grafana: *Job Success Rate*, alert `CatalogJobFailure` |

**Verification workflow:**
1. **Capture baseline:** Snapshot metrics before remediation (e.g., prior to scaling pods or adding indexes)
2. **Apply response:** Follow scenario-specific detection/response steps, recording timestamps
3. **Confirm recovery:** Re-run PromQL queries. Metrics must return to target bands within 15 minutes
4. **Record evidence:** Attach Grafana screenshots to incident ticket
5. **Automate checks:** Update alerting rules to catch similar issues earlier

---

## Kill Switches

Emergency feature flags to disable catalog/inventory operations without code deployment.

### Available Kill Switches

| Feature Flag | Scope | Effect | Use Case |
|--------------|-------|--------|----------|
| `catalog.import.enabled` | Platform or Tenant | Disables import job enqueuing; returns HTTP 503 | Pause intake during job backlog or validation bugs |
| `catalog.export.enabled` | Platform or Tenant | Disables export job enqueuing; returns HTTP 503 | Halt exports if storage unavailable or data corruption |
| `inventory.reservation.enabled` | Platform or Tenant | Blocks new reservations; returns HTTP 503 | Prevent overselling during inventory reconciliation |
| `inventory.transfer.enabled` | Platform or Tenant | Disables transfer creation; returns HTTP 503 | Halt transfers if location data corrupt or auditing needed |

### Activating Kill Switches

**Platform Admin UI:**

1. Navigate to: **Platform Admin > Configuration > Feature Flags**
2. Search for flag key (e.g., `catalog.import.enabled`)
3. Toggle **Platform Default** to `false` (affects all tenants)
   - OR set **Tenant Override** to `false` for specific tenant
4. Click **Save** (changes take effect within ~10s due to Caffeine cache TTL)

**Via Database (emergency fallback):**

```sql
-- Disable catalog imports globally
INSERT INTO feature_flags (tenant_id, flag_key, enabled, updated_at)
VALUES (NULL, 'catalog.import.enabled', false, NOW())
ON CONFLICT (tenant_id, flag_key)
DO UPDATE SET enabled = false, updated_at = NOW();

-- Disable for specific tenant
INSERT INTO feature_flags (tenant_id, flag_key, enabled, updated_at)
VALUES ('{tenant-uuid}', 'inventory.reservation.enabled', false, NOW())
ON CONFLICT (tenant_id, flag_key)
DO UPDATE SET enabled = false, updated_at = NOW();
```

### Verifying Kill Switch Activation

```bash
# Test import endpoint (should return 503)
curl -X POST https://{tenant}.platform.com/api/v1/admin/catalog/import \
  -H "Content-Type: application/json" \
  -d '{"fileLocation":"s3://test.csv","requestedBy":"admin"}' \
  -w "\nHTTP Status: %{http_code}\n"

# Check feature flag resolution in logs
kubectl logs -l app=village-storefront --tail=50 | grep "FeatureToggle.*catalog.import"
```

### Re-enabling After Incident

1. **Confirm root cause resolved** (job queue cleared, validation bug fixed, inventory reconciled)
2. **Re-enable flag** via Admin UI or database (reverse steps above)
3. **Monitor metrics** for 15 minutes to ensure normal operation resumes
4. **Communicate status** to affected tenants (if downtime was significant)

**Post-Incident:**
- Document trigger, response, and resolution in incident log
- Update runbook if new failure mode discovered
- Schedule blameless postmortem within 48 hours

---

## Capacity Planning

### Growth Projections

**Assumptions (baseline per tenant):**
- Products: 500 products with 2,000 variants (avg 4 variants/product)
- Categories: 50 categories, 20 collections
- Inventory locations: 3 locations (warehouse, retail store, pop-up)
- Monthly operations: 100 imports, 20 transfers, 500 adjustments

**Database storage growth per tenant per month:**
```
Products: 500 rows × 2 KB = 1 MB
Variants: 2,000 rows × 1 KB = 2 MB
Inventory levels: 2,000 variants × 3 locations × 0.5 KB = 3 MB
Adjustments (audit): 500 rows × 1 KB = 0.5 MB
Transfers: 20 rows × 2 KB + 200 lines × 0.5 KB = 0.14 MB
Total: ~6.6 MB/tenant/month
```

**Scaling thresholds:**

| Tenant Count | Monthly DB Growth | Recommended PostgreSQL Size | API Replica Baseline |
|--------------|-------------------|------------------------------|----------------------|
| 100 | 660 MB | 50 GB | 3 |
| 500 | 3.3 GB | 100 GB | 5 |
| 1,000 | 6.6 GB | 250 GB | 8 |
| 5,000 | 33 GB | 1 TB | 12 |

### API Capacity Formula

**Processing capacity per pod (assuming 2 CPU, 4GB RAM):**
- Product searches: ~500 req/s (with cache hit rate >90%)
- Catalog creates/updates: ~100 req/s
- Inventory reservations: ~200 req/s

**Required pods for target latency:**

```
Pods_needed = (Peak_requests_per_second / Capacity_per_pod) × Safety_factor

Example (1,000 tenants, peak 2,000 searches/s):
  Pods = (2,000 / 500) × 1.5 = 6 pods minimum
```

**Safety factor (1.5)** accounts for:
- Traffic bursts (flash sales, coordinated launches)
- Pod rescheduling during deployments
- Cache warm-up periods after restarts

### Database Tuning Recommendations

Set connection pool based on tenant count:

| Tenant Tier | Max Connections | Connection Pool Size (per pod) |
|-------------|-----------------|--------------------------------|
| 1-100 | 100 | 10 |
| 101-500 | 200 | 15 |
| 501-1000 | 300 | 20 |
| 1000+ | 500 | 25 |

Edit `application.properties`:
```properties
# Adjust based on tenant tier
quarkus.datasource.jdbc.max-size=20  # Default: 10
```

### Monitoring Trends for Proactive Scaling

**Weekly review:**
```sql
-- Catalog growth trend (past 4 weeks)
SELECT DATE_TRUNC('week', created_at) AS week,
       COUNT(*) AS new_products,
       (SELECT COUNT(*) FROM product_variants WHERE product_variants.product_id = products.id) AS variant_count
FROM products
WHERE created_at > NOW() - INTERVAL '4 weeks'
GROUP BY week
ORDER BY week;

-- Import job volume trend
SELECT DATE_TRUNC('day', created_at) AS day,
       COUNT(*) AS import_jobs,
       AVG(EXTRACT(EPOCH FROM (updated_at - created_at))) AS avg_duration_seconds
FROM delayed_jobs
WHERE owning_module = 'catalog.import'
  AND created_at > NOW() - INTERVAL '4 weeks'
GROUP BY day
ORDER BY day;

-- Inventory operation volume
SELECT DATE_TRUNC('day', created_at) AS day,
       COUNT(*) AS adjustments,
       SUM(ABS(quantity_change)) AS total_quantity_adjusted
FROM inventory_adjustments
WHERE created_at > NOW() - INTERVAL '4 weeks'
GROUP BY day
ORDER BY day;
```

**Trigger proactive scaling when:**
- Product count grows >30% week-over-week for 2 consecutive weeks
- Import job volume increases >50% month-over-month
- Search latency p95 baseline increases (e.g., from 200ms to 300ms sustained)
- Database CPU >60% sustained during off-peak hours

---

## Appendix: Configuration Reference

### Application Properties (application.properties)

```properties
# Catalog Cache Settings
catalog.cache.root-categories.max-size=100
catalog.cache.featured-products.max-size=200
catalog.cache.ttl-minutes=15

# Import/Export Job Settings
catalog.import.max-file-size-mb=50
catalog.export.default-format=csv
catalog.export.presigned-url-expiry-hours=24

# Inventory Reservation Settings
inventory.reservation.expiry-hours=2
inventory.transfer.auto-cancel-days=30

# Observability
quarkus.log.console.json=true
quarkus.otel.enabled=true
quarkus.otel.exporter.otlp.endpoint=http://jaeger:4317
```

### Prometheus Metric Catalog

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `catalog_product_created_total` | Counter | `tenant_id` | Products created |
| `catalog_product_updated_total` | Counter | `tenant_id` | Products updated |
| `catalog_product_deleted_total` | Counter | `tenant_id` | Products deleted |
| `catalog_product_search_duration_seconds` | Histogram | `tenant_id` | Product search latency |
| `catalog_product_search_results` | Summary | `tenant_id` | Search result sizes |
| `catalog_category_created_total` | Counter | `tenant_id` | Categories created |
| `catalog_collection_created_total` | Counter | `tenant_id` | Collections created |
| `catalog_import_enqueued_total` | Counter | `tenant_id` | Import jobs enqueued |
| `catalog_import_success_total` | Counter | `tenant_id` | Import jobs succeeded |
| `catalog_import_failed_total` | Counter | `tenant_id`, `reason` | Import jobs failed |
| `catalog_import_duration_seconds` | Histogram | `tenant_id` | Import processing duration |
| `catalog_import_items` | Summary | `tenant_id` | Items imported per job |
| `catalog_export_enqueued_total` | Counter | `tenant_id` | Export jobs enqueued |
| `catalog_export_success_total` | Counter | `tenant_id` | Export jobs succeeded |
| `catalog_export_failed_total` | Counter | `tenant_id`, `reason` | Export jobs failed |
| `inventory_level_updated_total` | Counter | `tenant_id`, `location` | Inventory levels updated |
| `inventory_adjustment_total` | Counter | `tenant_id`, `location`, `reason` | Adjustments recorded |
| `inventory_adjustment_duration_seconds` | Histogram | `tenant_id` | Adjustment processing latency |
| `inventory_reserved_total` | Counter | `tenant_id` | Reservations created |
| `inventory_committed_total` | Counter | `tenant_id` | Reservations committed |
| `inventory_released_total` | Counter | `tenant_id` | Reservations released |
| `inventory_reservation_failed_total` | Counter | `tenant_id`, `reason` | Reservation failures |
| `inventory_transfer_created_total` | Counter | `tenant_id`, `source`, `destination` | Transfers created |
| `inventory_transfer_received_total` | Counter | `tenant_id`, `destination` | Transfers received |
| `inventory_transfer_duration_seconds` | Histogram | `tenant_id` | Transfer processing latency |
| `catalog_admin_category_create_seconds` | Timer | (none) | JAX-RS endpoint latency |
| `inventory_admin_transfer_create_seconds` | Timer | (none) | JAX-RS endpoint latency |

### OpenTelemetry Span Attributes

All catalog and inventory operations include these span attributes:

- `tenant.id`: Tenant UUID
- `catalog.operation`: Operation type (e.g., `create_product`, `import_enqueue`, `list_categories`)
- `inventory.operation`: Operation type (e.g., `create_transfer`, `create_adjustment`, `receive_transfer`)
- `category.id`, `category.code`: Category identifiers
- `collection.id`, `collection.code`: Collection identifiers
- `import.file_location`, `import.job_id`: Import job metadata
- `export.format`, `export.job_id`: Export job metadata
- `transfer.id`, `transfer.source_location_id`, `transfer.destination_location_id`: Transfer metadata
- `adjustment.variant_id`, `adjustment.location_id`, `adjustment.quantity_change`, `adjustment.reason`: Adjustment details

**Trace Context Propagation:** Correlation IDs propagate through HTTP headers (`X-Request-ID`) and background job metadata.

### Kubernetes Resources Quick Reference

```bash
# View application pod status
kubectl get pods -l app=village-storefront

# Check worker pod status
kubectl get pods -l component=workers

# View application logs (last 100 lines)
kubectl logs -l app=village-storefront --tail=100

# View worker logs (catalog import jobs)
kubectl logs -l component=workers --tail=100 | grep "catalog.import"

# Describe deployment for resource limits
kubectl describe deployment village-storefront

# Port-forward to metrics endpoint
kubectl port-forward svc/village-storefront 8080:8080
# Then: curl localhost:8080/q/metrics | grep catalog
```

### Database Queries for Troubleshooting

```sql
-- Check catalog import queue depth
SELECT priority, COUNT(*) AS queued_jobs
FROM delayed_jobs
WHERE status = 'pending'
  AND owning_module = 'catalog.import'
GROUP BY priority;

-- Find stuck import jobs (queued >1 hour)
SELECT id, tenant_id, priority, created_at, attempts
FROM delayed_jobs
WHERE status = 'pending'
  AND owning_module = 'catalog.import'
  AND created_at < NOW() - INTERVAL '1 hour'
ORDER BY created_at
LIMIT 20;

-- Product count per tenant (top 20)
SELECT t.subdomain, COUNT(p.id) AS product_count
FROM tenants t
JOIN products p ON t.id = p.tenant_id
WHERE p.status != 'deleted'
GROUP BY t.id
ORDER BY product_count DESC
LIMIT 20;

-- Inventory levels by location (summary)
SELECT il.location, COUNT(*) AS variant_count, SUM(il.quantity) AS total_quantity, SUM(il.reserved) AS total_reserved
FROM inventory_levels il
JOIN product_variants v ON il.variant_id = v.id
WHERE v.tenant_id = '{tenant-uuid}'
GROUP BY il.location;

-- Recent inventory adjustments (last 24 hours)
SELECT ia.created_at, v.sku, il_loc.code AS location, ia.reason, ia.quantity_change, ia.adjusted_by
FROM inventory_adjustments ia
JOIN product_variants v ON ia.variant_id = v.id
JOIN inventory_locations il_loc ON ia.location_id = il_loc.id
WHERE ia.tenant_id = '{tenant-uuid}'
  AND ia.created_at > NOW() - INTERVAL '24 hours'
ORDER BY ia.created_at DESC
LIMIT 50;

-- Transfers pending receipt
SELECT it.id, src.code AS source, dst.code AS destination, it.created_at, it.tracking_number
FROM inventory_transfers it
JOIN inventory_locations src ON it.source_location_id = src.id
JOIN inventory_locations dst ON it.destination_location_id = dst.id
WHERE it.tenant_id = '{tenant-uuid}'
  AND it.status = 'transit'
ORDER BY it.created_at;
```

---

## Related Documentation

- **Architecture:** [02_System_Structure_and_Data.md](../../.codemachine/artifacts/architecture/02_System_Structure_and_Data.md)
- **Operational Architecture:** [04_Operational_Architecture.md §3.7](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#3-7-observability-fabric)
- **Foundation:** [01_Blueprint_Foundation.md §3.0 Rulebook](../../.codemachine/artifacts/architecture/01_Blueprint_Foundation.md#section-3-rulebook)
- **KPI Reference:** [04_Operational_Architecture.md §4](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#4-component-kpis)
- **Metrics Helper:** `CatalogMetrics.java`, `InventoryMetrics.java`

---

**Document Version:** 1.0
**Last Verified:** 2026-01-08
**Next Review:** 2026-02-08 (monthly cadence)
**Feedback:** Report issues or suggest improvements via platform ops Slack channel or GitHub issues
