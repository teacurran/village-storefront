# Row Level Security (RLS) Setup Guide

## Overview

Village Storefront implements PostgreSQL Row Level Security (RLS) policies to enforce tenant isolation at the database layer. This ensures that tenant data is protected even if application code fails to filter queries correctly.

## Architecture

- **Migration**: `V20260113__enable_rls_policies.sql`
- **Helper Functions**:
  - `set_current_tenant_id(UUID)`: Sets the tenant ID for the current session
  - `get_current_tenant_id()`: Retrieves the current tenant ID from session variable
- **Session Variable**: `app.tenant_id` - stores the current tenant UUID

## How It Works

1. **Request Filter**: `TenantResolutionFilter` resolves the tenant from the Host header
2. **Context Population**: Sets `TenantContext.setCurrentTenant(tenantInfo)`
3. **Database Session**: When `tenant.rls.enabled=true` (default), `TenantResolutionFilter` automatically runs
   `SELECT set_current_tenant_id(:tenantId)` so PostgreSQL RLS policies can evaluate `current_setting('app.tenant_id')`.
4. **RLS Enforcement**: PostgreSQL automatically filters all queries to the current tenant's data

## Protected Tables

All tenant-scoped tables have RLS policies enabled:

### Tenancy Module
- `tenants`
- `custom_domains`

### Identity Module
- `users`
- `roles`
- `user_roles`
- `api_keys`

### Catalog Module
- `categories`
- `products`
- `product_variants`
- `product_categories`
- `product_images`
- `inventory_levels`

### Cart/Order/Payment Module
- `carts`
- `cart_items`
- `orders`
- `order_line_items`
- `shipments`
- `payment_methods`
- `payments`
- `refunds`

### Consignment Module
- `consignors`
- `consignment_items`
- `payout_batches`
- `payout_line_items`

### Loyalty Module
- `loyalty_programs`
- `loyalty_members`
- `loyalty_transactions`

## Testing RLS

### Unit Tests

`TenantFilterTest` now uses Quarkus Dev Services to automatically start PostgreSQL 17 via Testcontainers during the test
phase. No additional flags are required.

```bash
# Run focused RLS test suite
./mvnw test -Dtest=TenantFilterTest
```

### Manual Testing

```sql
-- Connect to database
psql -U storefront -d storefront

-- Create test tenants
INSERT INTO tenants (id, subdomain, name, status, created_at, updated_at)
VALUES
  ('11111111-1111-1111-1111-111111111111'::uuid, 'tenant1', 'Tenant 1', 'active', NOW(), NOW()),
  ('22222222-2222-2222-2222-222222222222'::uuid, 'tenant2', 'Tenant 2', 'active', NOW(), NOW());

-- Create test users for each tenant
INSERT INTO users (id, tenant_id, email, status, created_at, updated_at)
VALUES
  (gen_random_uuid(), '11111111-1111-1111-1111-111111111111'::uuid, 'user@tenant1.com', 'active', NOW(), NOW()),
  (gen_random_uuid(), '22222222-2222-2222-2222-222222222222'::uuid, 'user@tenant2.com', 'active', NOW(), NOW());

-- Set tenant context to tenant1
SELECT set_current_tenant_id('11111111-1111-1111-1111-111111111111'::uuid);

-- Query users - should only see tenant1 users
SELECT email FROM users;
-- Expected: user@tenant1.com

-- Set tenant context to tenant2
SELECT set_current_tenant_id('22222222-2222-2222-2222-222222222222'::uuid);

-- Query users - should only see tenant2 users
SELECT email FROM users;
-- Expected: user@tenant2.com

-- Clear tenant context
SELECT set_config('app.tenant_id', '', FALSE);

-- Query users - should see NO users (RLS blocks all)
SELECT email FROM users;
-- Expected: (empty result)
```

## Migration Rollback

To rollback the RLS migration:

```bash
cd migrations
mvn migration:down -Dmigration.env=development
```

This will:
1. Drop all RLS policies
2. Disable RLS on all tables
3. Drop helper functions

## Security Considerations

1. **FORCE ROW LEVEL SECURITY**: All tenant tables use `FORCE ROW LEVEL SECURITY` to ensure RLS applies even to table owners (not just normal users)
2. **No Bypass**: Superuser accounts can bypass RLS. Ensure application database user is NOT a superuser
3. **Session Variable**: The `app.tenant_id` session variable is set by `TenantResolutionFilter` for every request (when
   `tenant.rls.enabled=true`) and cleared by `TenantContextClearFilter` when the request completes. Missing variable
   causes RLS to block all rows.
4. **Cache Invalidation**: Tenant resolution results are cached. See `TenantCacheInvalidator` for cache coherence strategy

## Performance Impact

- **Negligible overhead**: RLS policies use simple equality checks on indexed `tenant_id` columns
- **Query planner integration**: PostgreSQL's query planner optimizes RLS policies into index scans
- **Recommended indexes**: All tenant-scoped tables have indexes on `tenant_id` column

## References

- [ADR-001: Tenancy Strategy (Section 3: Row Level Security)](../docs/adr/ADR-001-tenancy.md)
- [PostgreSQL Row Level Security Documentation](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- Task I1.T5: Tenant Access Gateway Prototype
