# Catalog & Inventory Migrations

This document describes the database migrations for the catalog and inventory domain models.

## Migration Overview

The catalog and inventory schema is split across multiple migration files:

| Migration | Description | Tables Created |
|-----------|-------------|----------------|
| `V20260102__baseline_schema.sql` | Baseline schema with core catalog tables | `categories`, `products`, `product_variants`, `product_categories`, `product_images`, `inventory_levels` |
| `V20260112__domain_events_table.sql` | Domain events for event sourcing and reporting | `domain_events` |
| `V20260113__enable_rls_policies.sql` | Enable RLS on baseline tables | N/A (policies only) |
| `V20260114__catalog_inventory_extensions.sql` | Extended catalog/inventory models | `collections`, `product_collections`, `inventory_locations`, `inventory_adjustments`, `inventory_transfers`, `inventory_transfer_lines` |
| `V20260115__catalog_inventory_rls_policies.sql` | Enable RLS on extended tables | N/A (policies only) |

## Migration Order

Migrations must be applied in sequential order by version number. Flyway automatically handles this when running:

```bash
# From project root
./mvnw flyway:migrate -Dflyway.url=jdbc:postgresql://localhost:5432/storefront_dev
```

Or using Quarkus dev mode (migrations run automatically):

```bash
./mvnw quarkus:dev -Dquarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/storefront_dev
```

## Schema Details

### Collections

**Table:** `collections`

Curator-defined product groupings for merchandising and promotions. Unlike hierarchical categories, collections are flat and support both manual product assignment and automatic rules-based selection.

**Fields:**
- `id` (UUID): Primary key
- `tenant_id` (UUID): Tenant scope (FK → tenants)
- `code` (VARCHAR): Unique code per tenant
- `slug` (VARCHAR): URL-safe slug per tenant
- `collection_type` (VARCHAR): `manual` or `automatic`
- `selection_rules` (JSONB): Rules for automatic collections
- `status` (VARCHAR): `draft`, `active`, `archived`, `deleted`

**Indexes:**
- `idx_collections_tenant_id`: Query by tenant
- `idx_collections_tenant_id_status`: Filter active collections
- `idx_collections_slug`: URL routing
- `idx_collections_published`: Filter published collections

**RLS Policy:** `collections_isolation_policy` restricts access to `tenant_id = get_current_tenant_id()`

### Domain Events

**Table:** `domain_events`

Immutable event log for inventory operations, transfers, and adjustments. Events support event-driven reporting projections and audit trails.

**Fields:**
- `id` (UUID): Primary key
- `tenant_id` (UUID): Tenant scope (FK → tenants)
- `aggregate_type` (VARCHAR): Type of aggregate (e.g., `INVENTORY_LEVEL`, `INVENTORY_TRANSFER`)
- `aggregate_id` (UUID): UUID of the aggregate instance
- `event_type` (VARCHAR): Type of event (e.g., `INVENTORY_ADJUSTED`, `TRANSFER_INITIATED`, `TRANSFER_RECEIVED`)
- `payload` (JSONB): Event-specific data structure
- `metadata` (JSONB): Correlation IDs, user context, trace information
- `occurred_at` (TIMESTAMPTZ): Business event timestamp

**Indexes:**
- `idx_domain_events_tenant_aggregate`: Query events by tenant and aggregate
- `idx_domain_events_tenant_type_occurred`: Query events by type and time range
- `idx_domain_events_occurred_at`: Temporal queries for event replay

**RLS Policy:** `domain_events_tenant_isolation` restricts access to `tenant_id = current_setting('app.current_tenant_id', true)::uuid`

**Event Types:**
- `INVENTORY_ADJUSTED`: Manual inventory adjustments with reason codes (payload: variant, location, quantities, reason, adjustedBy)
- `TRANSFER_INITIATED`: Transfer creation between locations (payload: transferId, source/destination locations, line items, initiatedBy)
- `TRANSFER_RECEIVED`: Transfer completion (payload: transferId, locations, received quantities, timestamp)

**Usage:** Reporting services poll `domain_events` to build read-optimized aggregates for dashboards and analytics.

### Inventory Locations

**Table:** `inventory_locations`

Physical or virtual locations where inventory is tracked (warehouses, retail stores, supplier locations, consignment depots).

**Fields:**
- `id` (UUID): Primary key
- `tenant_id` (UUID): Tenant scope (FK → tenants)
- `code` (VARCHAR): Unique code per tenant
- `type` (VARCHAR): Location type (warehouse, retail, dropship, consignor)
- `address` (JSONB): Structured address object
- `active` (BOOLEAN): Whether location accepts new transactions

**Indexes:**
- `idx_inventory_locations_tenant_id`: Query by tenant
- `idx_inventory_locations_tenant_id_active`: Filter active locations
- `idx_inventory_locations_code`: Query by code

**Extension:** `inventory_levels` gains a `version BIGINT` column (optimistic locking) so concurrent updates align with the JPA model.

**RLS Policy:** `inventory_locations_isolation_policy` restricts access to the tenant derived from `current_setting('app.tenant_id')`

### Inventory Levels

**Table:** `inventory_levels`

Tracks `(tenant, variant, location)` stock positions. Baseline schema provides `quantity`, `reserved`, timestamps, and the optimistic locking `version` column. Task `I2.T2` extends the table with:

- `safety_stock` (INTEGER, default `0`): Buffer that triggers replenishment workflows before the location reaches zero available stock.
- `low_stock_threshold` (INTEGER, default `0`): Threshold evaluated by the low-stock alert scheduler (`quantity - reserved <= threshold`). When configured (> 0), falling below the threshold raises alerts (currently logs + queue entries, future work will deliver email/webhook notifications).

The columns are introduced via `V20260128__inventory_low_stock_thresholds.sql`, ensuring the scheduler stub and SLA instrumentation can reason about configured safety buffers without bespoke joins.

### Inventory Adjustments

**Table:** `inventory_adjustments`

Append-only audit log of all inventory quantity changes across locations.

**Fields:**
- `quantity_change` (INTEGER): Positive/negative delta
- `quantity_before` / `quantity_after`: Snapshot for audit trail
- `reason` (VARCHAR): Enum values (`CYCLE_COUNT`, `DAMAGE`, `RETURN`, `SHRINKAGE`, `FOUND`, `OTHER`)
- `adjusted_by` (VARCHAR): User or system who performed adjustment
- `notes` (TEXT): Optional detail
- `created_at` (TIMESTAMPTZ): Partition key (monthly RANGE partitions)

**Partition Seeds:**
- `inventory_adjustments_2026_01` (2026-01-01 → 2026-02-01)
- `inventory_adjustments_2026_02` (2026-02-01 → 2026-03-01)
- `inventory_adjustments_2026_03` (2026-03-01 → 2026-04-01)
- `inventory_adjustments_default` (catch-all)

**Indexes:**
- `idx_inventory_adjustments_tenant_created_at`: Tenant scoped time queries
- `idx_inventory_adjustments_variant_created_at`: Variant history drill-down
- `idx_inventory_adjustments_location_created_at`: Location history queries

**RLS Policy:** `inventory_adjustments_isolation_policy` restricts access to the tenant resolved via `current_setting('app.tenant_id')`

### Inventory Transfers

**Table:** `inventory_transfers`

Transfer batches for moving inventory between locations.

**Fields:**
- `source_location_id` / `destination_location_id` (UUID): FK → inventory_locations
- `status` (VARCHAR): Enum (`PENDING`, `IN_TRANSIT`, `RECEIVED`, `CANCELLED`)
- `initiated_by` (VARCHAR): User/system initiating transfer
- `expected_arrival_date` (TIMESTAMPTZ): ETA for receiving team
- `carrier`, `tracking_number`, `shipping_cost`, `barcode_job_id`, `notes`
- `version` (BIGINT): Optimistic locking token

**Indexes:**
- `idx_inventory_transfers_status`: Tenant + status filtering
- `idx_inventory_transfers_source_location` / `_destination_location`: Movement history
- `idx_inventory_transfers_tracking_number`: Quick lookup by tracking number
- `idx_inventory_transfers_expected_arrival`: SLA monitoring

**RLS Policy:** `inventory_transfers_isolation_policy` restricts access via `current_setting('app.tenant_id')`

### Inventory Transfer Lines

**Table:** `inventory_transfer_lines`

Line items for each transfer with quantity + optional notes. Includes `tenant_id` for RLS enforcement and inherits policies referencing `current_setting('app.tenant_id')`.

## Verification

### Check RLS Policies

Run the verification script to ensure RLS policies are properly configured:

```bash
psql -U storefront -d storefront_dev -f modules/core-platform/src/main/resources/db/migrations/verify_rls_policies.sql
```

Expected output:
- Check 1: 2 helper functions (`get_current_tenant_id`, `set_current_tenant_id`)
- Check 2: All tables show "ENABLED ✓"
- Check 3: All policies show "VALID ✓" and expressions include `current_setting('app.tenant_id')`
- Check 4: Each table has exactly 1 policy
- Check 5: All tables show "VALID ✓" for `tenant_id` UUID column
- Check 6: All `tenant_id` FKs point to `tenants(id)` with CASCADE delete

### Verify Current Settings

Check if `app.tenant_id` is set in your session:

```sql
-- Check current tenant context
SELECT current_setting('app.tenant_id', TRUE);

-- Set tenant context (required before querying RLS-protected tables)
SELECT set_current_tenant_id('xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx'::UUID);

-- Verify RLS is enforced
SHOW row_security;
```

## Rollback Testing

Test rollback behavior in a transaction (dry run):

```bash
psql -U storefront -d storefront_test -f modules/core-platform/src/main/resources/db/migrations/test_rollback.sql
```

This script:
1. Starts a transaction
2. Executes Down migrations
3. Verifies tables/policies are dropped
4. Rolls back the transaction (restores state)

## Production Rollback

**⚠️ WARNING:** Only rollback in production as a last resort. Coordinate with team and take database backups first.

```bash
# Rollback last migration
./mvnw flyway:undo -Dflyway.url=jdbc:postgresql://prod-host:5432/storefront_prod

# Rollback to specific version
./mvnw flyway:migrate -Dflyway.target=20260113 -Dflyway.url=jdbc:postgresql://prod-host:5432/storefront_prod
```

## Migration Format

Migrations use MyBatis convention with `-- +migrate Up/Down` markers for Flyway compatibility:

```sql
-- +migrate Up
-- Forward migration DDL

-- +migrate Down
-- Rollback DDL (reverse dependency order)
```

## Common Issues

### Issue: RLS blocks all queries

**Symptom:** Queries return 0 rows even though data exists

**Solution:** Set tenant context before querying:
```sql
SELECT set_current_tenant_id('your-tenant-uuid'::UUID);
```

### Issue: Migration fails with foreign key constraint error

**Symptom:** `ERROR: insert or update on table "X" violates foreign key constraint`

**Solution:** Ensure parent tables are created first. Check migration order and dependencies.

### Issue: Flyway reports checksum mismatch

**Symptom:** `ERROR: Migration checksum mismatch`

**Solution:** Do not modify applied migrations. Create a new migration to fix issues. In development, you can reset:
```bash
./mvnw flyway:clean flyway:migrate -Dflyway.cleanDisabled=false
```

## References

- **ERD:** `docs/diagrams/datamodel_erd.mmd`
- **Architecture:** `docs/architecture/02_System_Structure_and_Data.md` Section 3.6
- **ADR-001:** `docs/adr/ADR-001-tenancy.md` (RLS policies)
- **Task:** I2.T2 (Catalog/Inventory Migrations)
- **Java Project Standards:** `docs/java-project-standards.adoc`

## Contact

For questions about migrations:
- Review the `#database` channel in team chat
- Consult the Database Agent documentation
- Review existing migrations for patterns
