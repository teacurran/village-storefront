# Catalog & Inventory Runbook

**Component:** Catalog & Inventory Module
**Owner:** Platform Engineering
**Last Updated:** 2026-01-09
**Related Docs:** [Architecture §4](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md), [Observability Framework](./observability.md)

---

## Overview

The Catalog & Inventory module manages the product catalog (products, variants, categories, collections) and multi-location inventory tracking for the Village Storefront platform. This runbook provides operational guidance for responding to alerts, troubleshooting common issues, and tuning performance.

### Component KPIs

| KPI | Target | Alert Threshold | Criticality |
|-----|--------|----------------|-------------|
| Bulk Import Throughput | ≥5000 products/min | <5000/min for 10min | Warning |
| Variant Upsert Latency (p95) | <200ms | >200ms for 5min | Warning |
| Product Search Latency (p95) | <500ms | >500ms for 5min | Warning |
| Inventory Adjustment Failure Rate | <1% | >1% for 10min | Critical |
| Import Job Success Rate | >95% | <95% for 10min | Warning |

### Service Dependencies

- **PostgreSQL**: Primary data store with RLS policies
- **Caffeine Cache**: In-memory caching for catalog reads
- **Object Storage (R2/MinIO)**: CSV import file storage
- **Background Job Scheduler**: Async import/export processing

---

## Alerts

### Alert: CatalogBulkImportThroughputLow

**Symptom:** Bulk import processing fewer than 5000 products/min

**Severity:** Warning
**Component:** Catalog
**Dashboard:** [Catalog KPIs Panel 6](https://grafana.villagecompute.com/d/component-kpis?panelId=6)

#### Causes

1. **Database connection pool exhaustion**
   - Too many concurrent imports
   - Leaked connections from failed transactions

2. **Small batch size configuration**
   - Default batch size (100) insufficient for large imports
   - Network round-trip overhead dominates

3. **RLS policy overhead**
   - Complex tenant filtering on large product tables
   - Missing indexes on tenant_id columns

4. **Slow network to object storage**
   - R2/MinIO storage latency spikes
   - Large CSV files with slow reads

#### Investigation Steps

1. **Check Current Throughput:**
   ```promql
   rate(catalog_bulk_import_products_total{tenant_id="YOUR_TENANT"}[5m]) * 60
   ```

2. **Check Database Pool:**
   ```sql
   SELECT * FROM pg_stat_activity WHERE datname = 'storefront' AND state = 'active';
   ```

3. **Check Job Logs:**
   ```bash
   kubectl logs -l app=storefront --tail=100 | grep "catalog.import"
   ```

4. **Review Import Job Metrics:**
   ```promql
   histogram_quantile(0.95, sum(rate(catalog_import_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le))
   ```

#### Resolution

1. **Increase Batch Size:**
   - Edit `application.properties`: `catalog.import.batch-size=500`
   - Default is 100, increase to 500-1000 for large imports
   - Restart service: `kubectl rollout restart deployment/storefront`

2. **Increase Database Pool:**
   - Edit `application.properties`: `quarkus.datasource.jdbc.max-size=20`
   - Monitor pool usage after change
   - Watch for connection exhaustion: `sum(hikaricp_connections_active) by (pool)`

3. **Optimize RLS Policies:**
   - Verify indexes: `\d products` in psql
   - Ensure `tenant_id` index exists: `CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_tenant_id ON products(tenant_id);`
   - Check RLS policy performance: `EXPLAIN ANALYZE SELECT * FROM products WHERE tenant_id = 'uuid';`

4. **Disable Feature Flags Causing Overhead:**
   - Temporarily disable `catalog.import.validation.strict` if validation is bottleneck
   - Feature flag: `catalog.import.skip-validation=true` (use with caution)

---

### Alert: VariantUpsertLatencyHigh

**Symptom:** Variant upsert p95 latency exceeds 200ms target

**Severity:** Warning
**Component:** Catalog
**Dashboard:** [Catalog KPIs Panel 6](https://grafana.villagecompute.com/d/component-kpis?panelId=6)

#### Causes

1. **Missing database indexes**
   - Slow lookups on variant SKU or product_id
   - RLS policy overhead on variants table

2. **Large batch size**
   - Single transaction too large (>1000 variants)
   - Lock contention on product table

3. **Cache invalidation overhead**
   - Excessive cache invalidations on variant updates
   - Cache stampede during bulk operations

#### Investigation Steps

1. **Check Variant Upsert Latency:**
   ```promql
   histogram_quantile(0.95, sum(rate(catalog_variant_upsert_batch_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le))
   ```

2. **Check Database Indexes:**
   ```sql
   SELECT schemaname, tablename, indexname, indexdef
   FROM pg_indexes
   WHERE tablename = 'product_variants';
   ```

3. **Review Slow Query Log:**
   ```sql
   SELECT query, mean_exec_time, calls
   FROM pg_stat_statements
   WHERE query LIKE '%product_variants%'
   ORDER BY mean_exec_time DESC
   LIMIT 10;
   ```

#### Resolution

1. **Add Missing Indexes:**
   ```sql
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_variants_sku ON product_variants(sku);
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_variants_product_id ON product_variants(product_id);
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_variants_tenant_id ON product_variants(tenant_id);
   ```

2. **Reduce Batch Size:**
   - Edit `application.properties`: `catalog.variant.upsert.batch-size=250`
   - Default is 500, reduce to 100-250 if lock contention observed

3. **Optimize Cache Invalidation:**
   - Review cache invalidation strategy in `CatalogCacheService`
   - Consider tenant-scoped cache keys to avoid global invalidation

---

### Alert: CatalogSearchLatencyHigh

**Symptom:** Product search p95 latency exceeds 500ms target

**Severity:** Warning
**Component:** Catalog
**Dashboard:** [Catalog KPIs Panel 6](https://grafana.villagecompute.com/d/component-kpis?panelId=6)

#### Causes

1. **Missing full-text search indexes**
   - PostgreSQL text search without GIN index
   - Search on name/description fields without optimization

2. **Large result sets**
   - Fetching too many products without pagination
   - No LIMIT clause on search queries

3. **Complex search queries**
   - Multiple LIKE clauses on different columns
   - Slow wildcard searches (`%term%`)

#### Investigation Steps

1. **Check Search Latency:**
   ```promql
   histogram_quantile(0.95, sum(rate(catalog_product_search_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le))
   ```

2. **Review Search Query:**
   ```sql
   EXPLAIN ANALYZE
   SELECT * FROM products
   WHERE tenant_id = 'uuid'
     AND (name ILIKE '%search_term%' OR description ILIKE '%search_term%');
   ```

3. **Check Index Usage:**
   ```sql
   SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read
   FROM pg_stat_user_indexes
   WHERE tablename = 'products';
   ```

#### Resolution

1. **Add Full-Text Search Index:**
   ```sql
   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_search
   ON products USING GIN (to_tsvector('english', name || ' ' || description));
   ```

2. **Optimize Search Query:**
   - Use full-text search instead of LIKE:
   ```sql
   SELECT * FROM products
   WHERE tenant_id = 'uuid'
     AND to_tsvector('english', name || ' ' || description) @@ to_tsquery('english', 'search_term');
   ```

3. **Add Pagination:**
   - Ensure all search endpoints use LIMIT and OFFSET
   - Default page size: 20, max: 100

---

### Alert: InventoryAdjustmentFailureRateHigh

**Symptom:** Inventory adjustment failure rate exceeds 1% threshold

**Severity:** Critical
**Component:** Inventory
**Dashboard:** [Catalog KPIs Panel 6](https://grafana.villagecompute.com/d/component-kpis?panelId=6)

#### Causes

1. **Insufficient stock errors**
   - Reservation attempts exceeding available inventory
   - Race conditions on concurrent reservations

2. **Missing inventory levels**
   - Variant not initialized at location
   - Inventory sync issues from external systems

3. **Negative inventory warnings**
   - Overselling due to reservation bugs
   - Inventory adjustments without proper validation

#### Investigation Steps

1. **Check Failure Rate:**
   ```promql
   sum(rate(inventory_error_insufficient_stock_total{tenant_id="YOUR_TENANT"}[5m])) / sum(rate(inventory_adjustment_total{tenant_id="YOUR_TENANT"}[5m]))
   ```

2. **Review Error Logs:**
   ```bash
   kubectl logs -l app=storefront --tail=200 | grep "insufficient_stock"
   ```

3. **Check Inventory Levels:**
   ```sql
   SELECT variant_id, location, quantity, reserved, (quantity - reserved) as available
   FROM inventory_levels
   WHERE tenant_id = 'uuid'
   ORDER BY available ASC
   LIMIT 20;
   ```

#### Resolution

1. **Initialize Missing Inventory Levels:**
   ```sql
   INSERT INTO inventory_levels (id, tenant_id, variant_id, location, quantity, reserved)
   SELECT gen_random_uuid(), 'tenant_uuid', v.id, 'default', 0, 0
   FROM product_variants v
   WHERE v.tenant_id = 'tenant_uuid'
     AND NOT EXISTS (
       SELECT 1 FROM inventory_levels il
       WHERE il.variant_id = v.id AND il.location = 'default'
     );
   ```

2. **Add Pessimistic Locking:**
   - Review `InventoryService.reserveInventory()` method
   - Ensure `SELECT FOR UPDATE` is used for reservation queries
   - Example:
   ```sql
   SELECT * FROM inventory_levels
   WHERE variant_id = 'uuid' AND location = 'default'
   FOR UPDATE NOWAIT;
   ```

3. **Enable Inventory Safety Checks:**
   - Feature flag: `inventory.allow-overselling=false`
   - Validate available quantity before reservations
   - Reject reservations when `available < requested`

---

### Alert: CatalogImportJobFailureRateHigh

**Symptom:** Catalog import job failure rate exceeds 5%

**Severity:** Warning
**Component:** Catalog
**Dashboard:** [Catalog KPIs Panel 6](https://grafana.villagecompute.com/d/component-kpis?panelId=6)

#### Causes

1. **CSV validation errors**
   - Malformed CSV files (missing headers, wrong encoding)
   - Invalid data types (non-numeric price, invalid UUID)

2. **File not found**
   - Object storage access issues
   - Expired presigned URLs

3. **Duplicate SKU conflicts**
   - Import files contain duplicate SKUs
   - Unique constraint violations

#### Investigation Steps

1. **Check Import Failure Rate:**
   ```promql
   sum(rate(catalog_import_failed_total{tenant_id="YOUR_TENANT"}[5m])) / sum(rate(catalog_import_enqueued_total{tenant_id="YOUR_TENANT"}[5m]))
   ```

2. **Review Import Job Logs:**
   ```bash
   kubectl logs -l app=storefront --tail=200 | grep "catalog.import.failed"
   ```

3. **Check Failed Jobs:**
   ```sql
   SELECT job_id, status, error_message, created_at
   FROM catalog_import_jobs
   WHERE status = 'failed'
   ORDER BY created_at DESC
   LIMIT 10;
   ```

#### Resolution

1. **Fix CSV Validation:**
   - Validate CSV format before upload
   - Ensure UTF-8 encoding
   - Required headers: `sku,name,description,price,status`

2. **Handle Duplicate SKUs:**
   - Enable `catalog.import.skip-duplicates=true` feature flag
   - Or use upsert mode: `catalog.import.mode=upsert`

3. **Retry Failed Jobs:**
   ```bash
   curl -X POST https://admin.store.com/api/v1/catalog/import/retry/{job_id}
   ```

---

## Feature Flags

### Catalog Feature Flags

| Flag | Default | Description |
|------|---------|-------------|
| `catalog.import.validation.strict` | `true` | Enable strict validation (type checks, required fields) |
| `catalog.import.skip-duplicates` | `false` | Skip duplicate SKUs instead of failing |
| `catalog.import.batch-size` | `100` | Products per batch for bulk imports |
| `catalog.cache.ttl` | `3600` | Cache TTL in seconds |
| `catalog.search.full-text-enabled` | `true` | Use PostgreSQL full-text search |

### Inventory Feature Flags

| Flag | Default | Description |
|------|---------|-------------|
| `inventory.allow-overselling` | `false` | Allow reservations when available < requested |
| `inventory.negative-stock-warning` | `true` | Log warnings for negative stock levels |
| `inventory.reservation.timeout` | `900` | Reservation timeout in seconds (15min) |

---

## Common Issues

### Issue: Slow Product Search

**Symptoms:**
- Search queries taking >1 second
- Timeout errors in storefront

**Troubleshooting:**
1. Check if full-text search is enabled
2. Verify GIN index exists on products table
3. Review query plan with EXPLAIN ANALYZE
4. Check database CPU usage

**Resolution:**
- Enable full-text search: `catalog.search.full-text-enabled=true`
- Add GIN index (see Alert: CatalogSearchLatencyHigh)

---

### Issue: Import Job Failures

**Symptoms:**
- High failure rate on CSV imports
- Validation errors in logs

**Troubleshooting:**
1. Download failed CSV file for inspection
2. Check error_message in catalog_import_jobs table
3. Validate CSV format manually
4. Test with small sample file (10 rows)

**Resolution:**
- Fix CSV format issues
- Use strict validation: `catalog.import.validation.strict=true`
- Retry failed jobs after fixing source data

---

### Issue: Cache Misses

**Symptoms:**
- High cache miss rate
- Increased database load

**Troubleshooting:**
1. Check cache hit/miss ratio:
   ```promql
   sum(rate(catalog_cache_hit_total[5m])) / (sum(rate(catalog_cache_hit_total[5m])) + sum(rate(catalog_cache_miss_total[5m])))
   ```
2. Review cache eviction policy
3. Check cache size limits

**Resolution:**
- Increase cache TTL: `catalog.cache.ttl=7200`
- Increase cache size: `catalog.cache.max-size=10000`
- Use tenant-scoped cache keys

---

## Performance Tuning

### Database Connection Pool Sizing

**Recommended Settings:**

```properties
# For import-heavy workloads
quarkus.datasource.jdbc.min-size=5
quarkus.datasource.jdbc.max-size=20

# For read-heavy workloads
quarkus.datasource.jdbc.min-size=10
quarkus.datasource.jdbc.max-size=30
```

**Monitoring:**
```promql
# Pool utilization
hikaricp_connections_active / hikaricp_connections_max

# Connection wait time
rate(hikaricp_connections_timeout_total[5m])
```

---

### Batch Size Configuration

**Import Jobs:**

```properties
# Small imports (<1000 products)
catalog.import.batch-size=100

# Medium imports (1000-10000 products)
catalog.import.batch-size=500

# Large imports (>10000 products)
catalog.import.batch-size=1000
```

**Variant Upserts:**

```properties
# Default (balanced)
catalog.variant.upsert.batch-size=250

# Low contention (single tenant imports)
catalog.variant.upsert.batch-size=500
```

---

### Cache TTL Tuning

**Product Catalog:**

```properties
# High traffic stores (frequent reads)
catalog.cache.ttl=7200  # 2 hours

# Low traffic stores (infrequent updates)
catalog.cache.ttl=3600  # 1 hour

# Admin operations (frequent writes)
catalog.cache.ttl=300   # 5 minutes
```

---

## Troubleshooting Commands

### Prometheus Queries

**Catalog Metrics:**

```promql
# Bulk import throughput (products/min)
rate(catalog_bulk_import_products_total{tenant_id="YOUR_TENANT"}[5m]) * 60

# Variant upsert latency (p95)
histogram_quantile(0.95, sum(rate(catalog_variant_upsert_batch_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le))

# Product search latency (p95)
histogram_quantile(0.95, sum(rate(catalog_product_search_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le))

# Import job success rate
sum(rate(catalog_import_success_total{tenant_id="YOUR_TENANT"}[5m])) / sum(rate(catalog_import_enqueued_total{tenant_id="YOUR_TENANT"}[5m]))
```

**Inventory Metrics:**

```promql
# Insufficient stock error rate
sum(rate(inventory_error_insufficient_stock_total{tenant_id="YOUR_TENANT"}[5m]))

# Inventory adjustment latency
histogram_quantile(0.95, sum(rate(inventory_adjustment_operation_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le))

# Reservation failure rate
sum(rate(inventory_reservation_failed_total{tenant_id="YOUR_TENANT"}[5m])) / sum(rate(inventory_reserved_total{tenant_id="YOUR_TENANT"}[5m]))
```

---

### Kubernetes Commands

```bash
# View catalog service logs
kubectl logs -l app=storefront --tail=100 | grep "catalog"

# View inventory service logs
kubectl logs -l app=storefront --tail=100 | grep "inventory"

# Check pod resource usage
kubectl top pod -l app=storefront

# Restart service
kubectl rollout restart deployment/storefront

# Scale replicas
kubectl scale deployment/storefront --replicas=3
```

---

### SQL Queries

**Check Product Counts:**

```sql
SELECT tenant_id, COUNT(*) as product_count
FROM products
WHERE status = 'active'
GROUP BY tenant_id
ORDER BY product_count DESC;
```

**Check Inventory Levels:**

```sql
SELECT v.sku, il.location, il.quantity, il.reserved, (il.quantity - il.reserved) as available
FROM inventory_levels il
JOIN product_variants v ON il.variant_id = v.id
WHERE il.tenant_id = 'YOUR_TENANT_UUID'
ORDER BY available ASC
LIMIT 20;
```

**Check Import Job Status:**

```sql
SELECT status, COUNT(*) as count
FROM catalog_import_jobs
WHERE tenant_id = 'YOUR_TENANT_UUID'
  AND created_at > NOW() - INTERVAL '24 hours'
GROUP BY status;
```

---

## Escalation

**For Critical Issues:**
1. **Inventory Overselling**: Contact @platform-on-call immediately
2. **Data Loss**: Notify @platform-lead and @cto
3. **Multi-Tenant Impact**: Page @sre-team

**For Performance Issues:**
1. **Persistent High Latency**: Create incident in PagerDuty
2. **Database Deadlocks**: Escalate to @database-team
3. **Cache Stampede**: Contact @platform-engineering

---

## Related Documentation

- [Architecture §4: Operational Architecture](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md)
- [Observability Framework](./observability.md)
- [Catalog Domain Model](../../modules/core-platform/src/main/java/villagecompute/storefront/data/models/Product.java)
- [Inventory Domain Model](../../modules/core-platform/src/main/java/villagecompute/storefront/data/models/InventoryLevel.java)
- [Task I2.T7: Observability Instrumentation](../../.codemachine/artifacts/tasks/tasks_I2.json)
