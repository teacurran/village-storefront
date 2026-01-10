# Quick Start: Running Catalog/Inventory Migrations

This guide walks you through applying the new catalog and inventory migrations to your local development database.

## Prerequisites

- PostgreSQL 14+ running locally (or accessible via connection string)
- Java 21 installed
- Maven 3.9+ installed

## Step 1: Start PostgreSQL

### Option A: Docker Compose (Recommended)

If you have Docker installed:

```bash
# From project root
docker compose up -d postgres

# Verify PostgreSQL is running
docker compose ps
```

### Option B: Local PostgreSQL

If you have PostgreSQL installed locally:

```bash
# Start PostgreSQL service (macOS with Homebrew)
brew services start postgresql@14

# Or start manually
pg_ctl -D /usr/local/var/postgresql@14 start
```

### Option C: Use Environment Variable

If PostgreSQL is running elsewhere:

```bash
# Set connection string
export DB_URL=jdbc:postgresql://your-host:5432/storefront_dev
export DB_USER=your_username
export DB_PASSWORD=your_password
```

## Step 2: Create Database (First Time Only)

```bash
# Create database if it doesn't exist
createdb storefront_dev

# Or using psql
psql -U postgres -c "CREATE DATABASE storefront_dev;"
```

## Step 3: Run Migrations

### Option A: Quarkus Dev Mode (Recommended)

This automatically applies migrations when you start the application:

```bash
# From project root
./mvnw quarkus:dev -pl modules/core-platform

# Watch for migration output in logs:
# [io.quarkus.flyway] Flyway: Successfully applied X migrations
```

Once started, the application will be available at http://localhost:8080

### Option B: Flyway Maven Plugin

Apply migrations without starting the application:

```bash
# From project root
./mvnw flyway:migrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/storefront_dev \
  -Dflyway.user=storefront \
  -Dflyway.password=storefront \
  -Dflyway.locations=filesystem:modules/core-platform/src/main/resources/db/migrations
```

## Step 4: Verify Migrations

Run the verification script to ensure RLS policies are properly configured:

```bash
psql -U storefront -d storefront_dev -f modules/core-platform/src/main/resources/db/migrations/verify_rls_policies.sql
```

**Expected Output:**

```
============================================================
Village Storefront - RLS Policy Verification
============================================================

Check 1: Verifying RLS helper functions...
 function_name           | arguments
-------------------------+------------
 get_current_tenant_id   |
 set_current_tenant_id   | p_tenant_id uuid

Check 2: Verifying RLS is enabled on tenant-scoped tables...
 tablename               | rls_enabled | status
-------------------------+-------------+-----------
 collections             | t           | ENABLED ✓
 inventory_locations     | t           | ENABLED ✓
 inventory_adjustments   | t           | ENABLED ✓
 ...

Check 3: Verifying policies reference current_setting('app.tenant_id')...
 tablename               | policy_check | policy_definition
-------------------------+--------------+--------------------
 collections             | VALID ✓      | (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
 ...
```

## Step 5: Test with Sample Data

```sql
-- Connect to database
psql -U storefront -d storefront_dev

-- Create a test tenant
INSERT INTO tenants (id, subdomain, name, status)
VALUES ('11111111-1111-1111-1111-111111111111', 'teststore', 'Test Store', 'active');

-- Set tenant context (REQUIRED for RLS)
SELECT set_current_tenant_id('11111111-1111-1111-1111-111111111111'::UUID);

-- Create a collection
INSERT INTO collections (tenant_id, code, name, slug, status)
VALUES (
  get_current_tenant_id(),
  'summer-2026',
  'Summer 2026 Collection',
  'summer-2026',
  'active'
);

-- Create an inventory location
INSERT INTO inventory_locations (tenant_id, code, name, type, active)
VALUES (
  get_current_tenant_id(),
  'warehouse-1',
  'Main Warehouse',
  'warehouse',
  TRUE
);

-- Verify data
SELECT * FROM collections;
SELECT * FROM inventory_locations;
```

## Step 6: Test Tenant Isolation

```sql
-- Create a second tenant
INSERT INTO tenants (id, subdomain, name, status)
VALUES ('22222222-2222-2222-2222-222222222222', 'otherstore', 'Other Store', 'active');

-- Set context to second tenant
SELECT set_current_tenant_id('22222222-2222-2222-2222-222222222222'::UUID);

-- Try to query collections (should see nothing)
SELECT * FROM collections;
-- Returns 0 rows (tenant isolation working!)

-- Create collection for second tenant
INSERT INTO collections (tenant_id, code, name, slug, status)
VALUES (
  get_current_tenant_id(),
  'winter-2026',
  'Winter 2026 Collection',
  'winter-2026',
  'active'
);

-- Now see only second tenant's collections
SELECT * FROM collections;
-- Returns 1 row (winter-2026)

-- Switch back to first tenant
SELECT set_current_tenant_id('11111111-1111-1111-1111-111111111111'::UUID);
SELECT * FROM collections;
-- Returns 1 row (summer-2026) - isolation confirmed!
```

## Rollback (If Needed)

### Test Rollback (Dry Run)

Test rollback without actually removing tables:

```bash
psql -U storefront -d storefront_dev -f modules/core-platform/src/main/resources/db/migrations/test_rollback.sql
```

This runs Down migrations in a transaction and then rolls back.

### Actual Rollback (DESTRUCTIVE)

**⚠️ WARNING:** This will delete all data in the affected tables!

```bash
# Rollback to specific version
./mvnw flyway:migrate \
  -Dflyway.target=20260113 \
  -Dflyway.url=jdbc:postgresql://localhost:5432/storefront_dev

# Or manually run Down migrations
psql -U storefront -d storefront_dev <<SQL
-- Drop RLS policies first
DROP POLICY IF EXISTS inventory_transfer_lines_isolation_policy ON inventory_transfer_lines;
ALTER TABLE inventory_transfer_lines DISABLE ROW LEVEL SECURITY;
-- ... (continue with all Down statements)

-- Drop tables
DROP TABLE IF EXISTS inventory_transfer_lines CASCADE;
DROP TABLE IF EXISTS inventory_transfers CASCADE;
DROP TABLE IF EXISTS inventory_adjustments CASCADE;
DROP TABLE IF EXISTS inventory_locations CASCADE;
DROP TABLE IF EXISTS product_collections CASCADE;
DROP TABLE IF EXISTS collections CASCADE;
SQL
```

## Troubleshooting

### Issue: "relation does not exist"

**Cause:** Migrations haven't been applied yet

**Solution:**
```bash
./mvnw quarkus:dev -pl modules/core-platform
# Check logs for migration output
```

### Issue: "ERROR: permission denied"

**Cause:** Database user doesn't have CREATE TABLE permission

**Solution:**
```bash
psql -U postgres -d storefront_dev
GRANT ALL PRIVILEGES ON DATABASE storefront_dev TO storefront;
GRANT ALL ON SCHEMA public TO storefront;
```

### Issue: "RLS blocks all queries"

**Cause:** Forgot to set tenant context

**Solution:**
```sql
-- Always set tenant context before querying RLS-protected tables
SELECT set_current_tenant_id('your-tenant-uuid'::UUID);
```

### Issue: "Flyway checksum mismatch"

**Cause:** Migration file was modified after being applied

**Solution (Development Only):**
```bash
# Clean database and reapply migrations
./mvnw flyway:clean flyway:migrate \
  -Dflyway.cleanDisabled=false \
  -Dflyway.url=jdbc:postgresql://localhost:5432/storefront_dev
```

**⚠️ Never use `flyway:clean` in production!**

## Next Steps

1. ✅ Migrations applied successfully
2. ✅ RLS policies verified
3. ✅ Tenant isolation tested
4. 🚀 Start building catalog repositories (Task I2.T3)
5. 🚀 Implement inventory service layer (Task I2.T4)

## Additional Resources

- **Detailed Documentation:** `modules/core-platform/src/main/resources/db/migrations/README_CATALOG_INVENTORY.md`
- **Implementation Summary:** `I2T2_MIGRATION_SUMMARY.md`
- **ERD Diagram:** `docs/diagrams/datamodel_erd.mmd`
- **Project Standards:** `docs/java-project-standards.adoc`

## Getting Help

If you encounter issues:
1. Check migration logs in Quarkus console
2. Run `verify_rls_policies.sql` to diagnose RLS issues
3. Review `README_CATALOG_INVENTORY.md` for detailed troubleshooting
4. Check project documentation in `docs/architecture/`

---

**Quick Reference Commands:**

```bash
# Start dev environment
./mvnw quarkus:dev -pl modules/core-platform

# Verify migrations
psql -U storefront -d storefront_dev -f modules/core-platform/src/main/resources/db/migrations/verify_rls_policies.sql

# Connect to database
psql -U storefront -d storefront_dev

# Set tenant context (in psql)
SELECT set_current_tenant_id('your-tenant-uuid'::UUID);

# Test rollback (dry run)
psql -U storefront -d storefront_dev -f modules/core-platform/src/main/resources/db/migrations/test_rollback.sql
```
