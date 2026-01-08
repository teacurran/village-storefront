# Multi-Tenant Data Model: Tenancy Enforcement & Data Isolation

**Document Version:** 1.0
**Date:** 2026-01-08
**Status:** Active
**Related Artifacts:**
- ERD (Mermaid): `docs/diagrams/datamodel_erd.mmd`
- ERD (PlantUML): `docs/diagrams/datamodel_erd.puml`
- Architecture Decision: `docs/adr/ADR-001-tenancy.md`
- Quality Suite: `docs/quality/tenant_isolation.md`

---

## Overview

The Village Storefront platform uses a **shared-database, tenant-scoped architecture** where all tenants share a single PostgreSQL database with application-enforced and database-enforced isolation. This document describes the data model's multi-tenancy conventions, Row-Level Security (RLS) policy templates, indexing strategies, and partitioning/archival approaches.

---

## 1. Tenancy Columns & Metadata

### 1.1 Tenant-Scoped Tables

All tables storing tenant-specific data include the following mandatory columns:

```sql
-- Core tenancy column (foreign key to tenants table)
tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE

-- Audit metadata (standard across all tables)
created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
created_by UUID REFERENCES store_users(id)  -- Optional: track creating user
```

**Tenant-Scoped Table Categories:**

| Category | Tables | Notes |
|----------|--------|-------|
| **Identity** | `store_users`, `customers`, `session_log`, `api_keys` | All auth/user data scoped per tenant |
| **Catalog** | `categories`, `products`, `variants`, `inventory_locations`, `inventory_levels`, `product_categories` | Product catalog fully isolated |
| **Cart/Order** | `carts`, `orders`, `order_line_items`, `payment_intents`, `refunds`, `shipments`, `return_authorizations` | Transactional data per tenant |
| **Consignment** | `consignors`, `consignment_items`, `consignor_payouts` | Vendor management isolated |
| **Loyalty** | `loyalty_ledger_entries` | Points/rewards per tenant |
| **Financials** | `gift_cards`, `store_credits` | Financial instruments scoped |
| **Media** | `media_assets` | Uploaded media per tenant |
| **Events** | `webhook_events`, `domain_events`, `audit_events` | Event logs per tenant |

### 1.2 Platform-Wide Tables (Nullable tenant_id)

Some tables support both platform-wide and tenant-specific records:

```sql
-- Examples: feature_flags, background_jobs, rate_limit_buckets, platform_commands
tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE  -- NULLABLE
```

**Use Cases:**
- **Feature Flags:** Global defaults (`tenant_id IS NULL`) with per-tenant overrides (`tenant_id = <uuid>`)
- **Background Jobs:** Platform maintenance jobs (`tenant_id IS NULL`) vs. tenant-specific jobs (`tenant_id = <uuid>`)
- **Rate Limits:** Global API limits vs. tenant-specific throttles
- **Audit Events:** Platform admin actions vs. tenant user actions

### 1.3 Composite Unique Constraints

All unique constraints in tenant-scoped tables include `tenant_id` to ensure uniqueness per tenant, not globally:

```sql
-- Example: Product SKU uniqueness
ALTER TABLE products ADD CONSTRAINT uq_products_tenant_sku UNIQUE (tenant_id, sku);

-- Example: Custom domains (globally unique across all tenants)
ALTER TABLE custom_domains ADD CONSTRAINT uq_custom_domains_domain UNIQUE (domain);

-- Example: Store user emails (unique per tenant, not globally)
ALTER TABLE store_users ADD CONSTRAINT uq_store_users_tenant_email UNIQUE (tenant_id, email);
```

---

## 2. Row-Level Security (RLS) Policies

### 2.1 RLS Policy Template

PostgreSQL Row-Level Security provides defense-in-depth by enforcing tenant isolation at the database layer. All tenant-scoped tables (marked with `[RLS]` in the ERD) require the following policy:

```sql
-- Enable RLS on table
ALTER TABLE <table_name> ENABLE ROW LEVEL SECURITY;

-- Create tenant isolation policy
CREATE POLICY tenant_isolation_policy ON <table_name>
  USING (
    -- Read filter: Only return rows matching current tenant
    current_setting('app.current_tenant_id', true) IS NOT NULL
    AND tenant_id = current_setting('app.current_tenant_id', true)::uuid
  )
  WITH CHECK (
    -- Write filter: Only allow inserts/updates for current tenant
    current_setting('app.current_tenant_id', true) IS NOT NULL
    AND tenant_id = current_setting('app.current_tenant_id', true)::uuid
  );
```

### 2.2 Setting Tenant Context

The application sets the `app.current_tenant_id` session variable for each database connection:

```sql
-- Java/Quarkus: Set tenant context at connection start
SELECT set_config('app.current_tenant_id', ?::text, false);
```

**Implementation Notes:**
- `TenantResolutionFilter` extracts tenant from subdomain/domain and stores in `TenantContext` (ThreadLocal)
- Database connection interceptor sets `app.current_tenant_id` before query execution
- `TenantContextClearFilter` clears context after request completion

### 2.3 RLS for Nullable Tenant Tables

Platform-wide tables with nullable `tenant_id` use conditional policies:

```sql
-- Example: Feature flags (global + tenant-specific)
CREATE POLICY tenant_or_global_policy ON feature_flags
  USING (
    tenant_id IS NULL  -- Platform-wide records visible to all
    OR (
      current_setting('app.current_tenant_id', true) IS NOT NULL
      AND tenant_id = current_setting('app.current_tenant_id', true)::uuid
    )
  )
  WITH CHECK (
    -- Writes require tenant context unless platform admin
    (tenant_id IS NULL AND current_setting('app.is_platform_admin', true)::boolean = true)
    OR (
      current_setting('app.current_tenant_id', true) IS NOT NULL
      AND tenant_id = current_setting('app.current_tenant_id', true)::uuid
    )
  );
```

### 2.4 RLS Testing & Validation

The `TenantIsolationIT` test suite validates RLS enforcement:

```java
// Test: RLS blocks raw queries from accessing other tenant's data
@Test
void rlsPolicy_shouldBlockRawQueryAccessToDifferentTenantData() {
    // Set tenant context for Tenant A
    setDatabaseTenant(tenantAId);

    // Raw JPQL query (bypasses Panache filters)
    List<Product> products = entityManager
        .createQuery("SELECT p FROM Product p", Product.class)
        .getResultList();

    // Should only return Tenant A products
    assertThat(products).allMatch(p -> p.tenant.id.equals(tenantAId));
}
```

**See:** `docs/quality/tenant_isolation.md` for complete test coverage.

---

## 3. Indexing Strategy

### 3.1 Mandatory Tenant Indexes

Every tenant-scoped table requires a B-tree index on `tenant_id`:

```sql
-- Standard tenant index
CREATE INDEX idx_<table_name>_tenant_id ON <table_name>(tenant_id);

-- Composite indexes include tenant_id as first column
CREATE INDEX idx_products_tenant_status ON products(tenant_id, status);
CREATE INDEX idx_orders_tenant_created ON orders(tenant_id, created_at DESC);
```

**Rationale:**
- Enables efficient tenant-scoped queries (99% of application queries filter by tenant)
- Supports foreign key cascades (`ON DELETE CASCADE`)
- Facilitates tenant data export/deletion operations

### 3.2 Composite Index Design

Common query patterns dictate composite index structure:

```sql
-- Catalog queries: tenant + status + created_at
CREATE INDEX idx_products_active_recent ON products(tenant_id, status, created_at DESC)
  WHERE status = 'active';

-- Order queries: tenant + customer + created_at
CREATE INDEX idx_orders_customer ON orders(tenant_id, customer_id, created_at DESC);

-- Inventory lookups: tenant + variant + location
CREATE INDEX idx_inventory_levels_variant_location
  ON inventory_levels(tenant_id, variant_id, location_id);

-- Session queries: tenant + user + login_at
CREATE INDEX idx_session_log_user ON session_log(tenant_id, user_id, login_at DESC);
```

### 3.3 Full-Text Search Indexes

Product search uses PostgreSQL `tsvector` with tenant scoping:

```sql
-- Add tsvector column
ALTER TABLE products ADD COLUMN search_vector tsvector;

-- Populate search vector (title + description)
UPDATE products SET search_vector =
  to_tsvector('english', coalesce(title, '') || ' ' || coalesce(description, ''));

-- Create GIN index with tenant filter
CREATE INDEX idx_products_search ON products USING GIN(search_vector)
  WHERE status = 'active';

-- Query example (tenant_id filter in WHERE clause)
SELECT * FROM products
WHERE tenant_id = ?
  AND search_vector @@ to_tsquery('english', 'widget')
  AND status = 'active'
ORDER BY created_at DESC;
```

---

## 4. Partitioning Strategy

High-volume tables use PostgreSQL declarative partitioning to improve query performance and enable efficient archival.

### 4.1 Partitioned Tables

Tables marked with `[PARTITIONED]` in the ERD use range partitioning by `created_at`:

| Table | Partition Interval | Retention Policy | Archival Target |
|-------|-------------------|------------------|-----------------|
| `session_log` | Monthly | 90 days (3 partitions) | Dropped after retention |
| `audit_events` | Monthly | 1 year (12 partitions) | S3 cold storage (Parquet) |
| `background_jobs` | Weekly | 30 days (~4 partitions) | Dropped after retention |
| `domain_events` | Monthly | 6 months (6 partitions) | S3 event archive (JSONL) |

### 4.2 Partition Schema Example: `session_log`

```sql
-- Create parent table
CREATE TABLE session_log (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_type VARCHAR(20) NOT NULL,
    user_id UUID,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    ip INET,
    user_agent TEXT,
    device_fingerprint VARCHAR(255),
    login_at TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ,
    logout_reason VARCHAR(50),
    impersonation_context JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (created_at);

-- Create monthly partitions
CREATE TABLE session_log_2026_01 PARTITION OF session_log
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE session_log_2026_02 PARTITION OF session_log
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE session_log_2026_03 PARTITION OF session_log
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

-- Create indexes on child partitions
CREATE INDEX idx_session_log_2026_01_tenant ON session_log_2026_01(tenant_id);
CREATE INDEX idx_session_log_2026_02_tenant ON session_log_2026_02(tenant_id);
CREATE INDEX idx_session_log_2026_03_tenant ON session_log_2026_03(tenant_id);

-- Automated partition management (via scheduled job)
-- 1. Create next month's partition 7 days before month end
-- 2. Drop partitions older than 90 days
DROP TABLE IF EXISTS session_log_2025_10;  -- Example: Drop October 2025 in January 2026
```

### 4.3 Partition Maintenance Automation

**Background Job:** `PartitionMaintenanceJob` (runs daily)

1. **Create Future Partitions:** Ensures next month's partition exists 7 days before rollover
2. **Drop Old Partitions:** Removes partitions exceeding retention policy
3. **Archive Before Drop:** Exports `audit_events` and `domain_events` to S3 before partition deletion

```java
// Pseudocode: PartitionMaintenanceJob
@Scheduled(cron = "0 2 * * *")  // 2 AM daily
public void maintainPartitions() {
    // Create next month's partition for session_log
    String nextMonth = LocalDate.now().plusMonths(1).format("yyyy_MM");
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS session_log_" + nextMonth + " " +
        "PARTITION OF session_log FOR VALUES FROM ('" + nextMonth + "-01') " +
        "TO ('" + LocalDate.now().plusMonths(2).withDayOfMonth(1) + "')"
    );

    // Drop session_log partitions older than 90 days
    String cutoffMonth = LocalDate.now().minusDays(90).format("yyyy_MM");
    jdbcTemplate.execute("DROP TABLE IF EXISTS session_log_" + cutoffMonth);

    // Archive audit_events before dropping (12 month retention)
    String archiveMonth = LocalDate.now().minusMonths(12).format("yyyy_MM");
    exportPartitionToS3("audit_events_" + archiveMonth, "s3://archives/audit_events/");
    jdbcTemplate.execute("DROP TABLE IF EXISTS audit_events_" + archiveMonth);
}
```

### 4.4 Querying Partitioned Tables

Application code queries partitioned tables transparently:

```sql
-- PostgreSQL automatically prunes partitions based on WHERE clause
SELECT * FROM session_log
WHERE tenant_id = ?
  AND created_at >= '2026-01-01'
  AND created_at < '2026-02-01'
ORDER BY created_at DESC;

-- Query plan shows only session_log_2026_01 partition scanned
EXPLAIN SELECT * FROM session_log WHERE created_at >= '2026-01-01' AND created_at < '2026-02-01';
-- Seq Scan on session_log_2026_01 (partition pruning: other partitions excluded)
```

---

## 5. Data Archival & Cold Storage

### 5.1 Archival Pipeline

**Trigger:** `PartitionMaintenanceJob` before partition deletion

**Process:**
1. Export partition to Parquet/JSONL format
2. Compress with gzip
3. Upload to S3 bucket: `s3://village-storefront-archives/{table_name}/{year}/{month}/{partition_name}.parquet.gz`
4. Verify upload checksum
5. Drop partition table

**Example:** `audit_events` 12-month retention

```bash
# Export partition to Parquet (using DuckDB or similar)
duckdb -c "
  COPY (SELECT * FROM audit_events_2025_01)
  TO 's3://village-storefront-archives/audit_events/2025/01/audit_events_2025_01.parquet'
  (FORMAT PARQUET, COMPRESSION GZIP);
"

# Verify upload
aws s3 ls s3://village-storefront-archives/audit_events/2025/01/

# Drop partition (safe after verification)
DROP TABLE audit_events_2025_01;
```

### 5.2 Archive Query Access

Archived data accessible via:

1. **Athena/Presto:** Query Parquet files directly from S3
2. **DuckDB:** Local analysis tool for archived partitions
3. **Snowflake External Tables:** Query cold storage via SQL

**Example:** Query archived audit events

```sql
-- Athena DDL for archived audit_events
CREATE EXTERNAL TABLE audit_events_archive (
    id VARCHAR(36),
    tenant_id VARCHAR(36),
    actor_type VARCHAR(20),
    actor_id VARCHAR(36),
    action VARCHAR(100),
    target_type VARCHAR(100),
    target_id VARCHAR(36),
    metadata VARCHAR,  -- JSONB as VARCHAR in Parquet
    occurred_at TIMESTAMP,
    created_at TIMESTAMP
)
STORED AS PARQUET
LOCATION 's3://village-storefront-archives/audit_events/';

-- Query specific tenant's archived events
SELECT * FROM audit_events_archive
WHERE tenant_id = 'aaaaaaaa-1111-2222-3333-444444444444'
  AND occurred_at >= DATE '2025-01-01'
  AND occurred_at < DATE '2025-02-01'
ORDER BY occurred_at DESC;
```

### 5.3 Archival Lifecycle Policies

**S3 Lifecycle Rules:**

| Archive Type | S3 Storage Class | Transition | Expiration |
|--------------|------------------|------------|------------|
| `audit_events` | Standard | → Glacier after 90 days | Never (compliance) |
| `domain_events` | Standard | → Glacier Deep Archive after 180 days | 7 years |
| `session_log` | N/A | Not archived | Dropped after 90 days |
| `background_jobs` | N/A | Not archived | Dropped after 30 days |

**Rationale:**
- **Audit Events:** Long-term compliance retention (SOX, GDPR audit trails)
- **Domain Events:** Business intelligence, event replay (7-year retention)
- **Session Logs:** Security forensics only (short-term, dropped after 90 days)
- **Background Jobs:** Operational logs only (no archival needed)

---

## 6. Tenant Data Deletion & GDPR Compliance

### 6.1 Tenant Deletion Flow

When a tenant is deleted or suspended:

1. **Soft Delete:** Set `tenants.status = 'deleted'` and `suspended_at = NOW()`
2. **Grace Period:** 30-day grace period before hard deletion
3. **Data Export:** Generate full tenant data export (JSONL) to S3
4. **Hard Delete:** `DELETE FROM tenants WHERE id = ? AND status = 'deleted' AND suspended_at < NOW() - INTERVAL '30 days'`
5. **Cascade:** All tenant-scoped records deleted via `ON DELETE CASCADE` foreign keys

**Background Job:** `TenantDeletionJob` (runs weekly)

```sql
-- Find tenants eligible for hard deletion
SELECT id, subdomain, suspended_at
FROM tenants
WHERE status = 'deleted'
  AND suspended_at < NOW() - INTERVAL '30 days';

-- Export tenant data to S3 (before deletion)
-- (Java code: iterate tenant records, serialize to JSONL, upload)

-- Hard delete (cascades to all tenant-scoped tables)
DELETE FROM tenants WHERE id = ? AND status = 'deleted' AND suspended_at < NOW() - INTERVAL '30 days';
```

### 6.2 GDPR Right to Erasure (Customer Data)

**Customer Deletion:** Anonymize customer records instead of hard delete (preserve order history)

```sql
-- Anonymize customer PII (preserves referential integrity)
UPDATE customers SET
    email = 'deleted-' || id || '@anonymized.local',
    name = '[DELETED]',
    phone = NULL,
    addresses = '[]'::jsonb,
    preferences = '{}'::jsonb,
    status = 'deleted'
WHERE id = ? AND tenant_id = ?;

-- Anonymize related session logs
UPDATE session_log SET
    ip = NULL,
    user_agent = '[DELETED]',
    device_fingerprint = NULL
WHERE user_type = 'customer' AND user_id = ?;

-- Anonymize audit events
UPDATE audit_events SET
    metadata = jsonb_set(metadata, '{pii_removed}', 'true')
WHERE actor_type = 'customer' AND actor_id = ?;
```

---

## 7. Performance Considerations

### 7.1 Query Optimization Best Practices

**Always filter by `tenant_id` first:**
```sql
-- Good: Tenant filter + indexed status
SELECT * FROM products
WHERE tenant_id = ? AND status = 'active'
ORDER BY created_at DESC LIMIT 20;

-- Bad: Missing tenant_id (full table scan)
SELECT * FROM products WHERE status = 'active' LIMIT 20;
```

**Use prepared statements to leverage index caching:**
```java
// Panache repository (auto-includes tenant filter via TenantContext)
public List<Product> findActiveProducts() {
    return Product.find(
        "tenant.id = ?1 AND status = 'active'",
        TenantContext.getCurrentTenantId()
    ).list();
}
```

### 7.2 Connection Pooling

**Quarkus Agroal Configuration:**
```properties
# Connection pool per tenant? No - shared pool with tenant_id session variable
quarkus.datasource.jdbc.min-size=10
quarkus.datasource.jdbc.max-size=50
quarkus.datasource.jdbc.idle-removal-interval=5M
quarkus.datasource.jdbc.validation-query-sql=SELECT 1

# Set tenant context on connection acquisition
quarkus.datasource.jdbc.new-connection-sql=SELECT set_config('app.current_tenant_id', NULL, false)
```

**Tenant Context Injection:**
```java
// Hibernate interceptor sets tenant_id session variable before query
@ApplicationScoped
public class TenantConnectionInterceptor extends EmptyInterceptor {
    @Override
    public String onPrepareStatement(String sql) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        // Execute: SELECT set_config('app.current_tenant_id', ?, false)
        // Then execute original SQL
        return sql;
    }
}
```

### 7.3 Monitoring & Alerting

**Key Metrics (Prometheus/Grafana):**

- **Tenant Isolation Violations:** Count of queries returning empty result when `TenantContext` missing
- **RLS Policy Hits:** PostgreSQL log `SET app.current_tenant_id` invocations (should match request count)
- **Partition Size:** Monitor partition row counts, trigger alerts if growth exceeds 10M rows/partition
- **Archive Lag:** Track time delta between partition drop date and actual archival completion

**Example Alert:**
```yaml
# Prometheus alert: Tenant context missing
- alert: TenantContextMissing
  expr: rate(tenant_context_missing_total[5m]) > 0
  for: 1m
  labels:
    severity: critical
  annotations:
    summary: "Tenant context not set for database query"
    description: "Query executed without TenantContext - potential data leakage"
```

---

## 8. Migration Checklist

When adding a new tenant-scoped table:

- [ ] Add `tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` column
- [ ] Add `created_at`, `updated_at`, `created_by` audit columns
- [ ] Create `idx_<table_name>_tenant_id` index
- [ ] Add composite unique constraints including `tenant_id` (if applicable)
- [ ] Create RLS policy: `CREATE POLICY tenant_isolation_policy ON <table_name> ...`
- [ ] Update `TenantIsolationIT` test suite with new table isolation tests
- [ ] Update ERD diagrams (Mermaid + PlantUML)
- [ ] Document table purpose in this narrative

**See:** `docs/adr/ADR-001-tenancy.md` Section "Implementation Checklist"

---

## 9. References

- **ERD (Mermaid):** `docs/diagrams/datamodel_erd.mmd`
- **ERD (PlantUML):** `docs/diagrams/datamodel_erd.puml`
- **Tenancy ADR:** `docs/adr/ADR-001-tenancy.md`
- **Quality Suite:** `docs/quality/tenant_isolation.md`
- **Architecture Overview:** `docs/architecture_overview.md` Section 5
- **PostgreSQL RLS Docs:** https://www.postgresql.org/docs/17/ddl-rowsecurity.html
- **PostgreSQL Partitioning Docs:** https://www.postgresql.org/docs/17/ddl-partitioning.html
- **Project Standards:** `docs/java-project-standards.adoc` Section 12

---

## 10. Change Log

| Date | Change | Author |
|------|--------|--------|
| 2026-01-08 | Initial narrative document covering tenancy columns, RLS policies, indexing, partitioning, and archival | CodeImplementer Agent (Task I1.T3) |

---

**Document Owner:** Architecture Team
**Reviewers:** Identity Team (session_log, RLS policies), Reporting Team (partitioning, archival)
**Next Review:** Post-MVP (Q2 2026) after RLS production deployment
