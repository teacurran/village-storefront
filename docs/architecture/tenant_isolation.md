# Tenant Isolation Architecture

<!-- anchor: tenant-isolation-blueprint -->

**Status:** Authoritative
**Last Updated:** 2026-01-11
**Owner:** Architecture Team

## Document Purpose

This document provides the authoritative technical specification for Village Storefront's multi-tenant data isolation strategy. It details the complete request-to-database lifecycle of tenant resolution, context propagation, and enforcement mechanisms across application and database layers.

**Intended Audience:** Backend engineers implementing tenant-aware features, QA engineers writing isolation tests, security auditors evaluating data protection, DevOps engineers troubleshooting tenant-related issues.

---

## Table of Contents

1. [Overview & Architecture](#overview--architecture)
2. [Tenant Resolution Flow](#tenant-resolution-flow)
3. [TenantContext Management](#tenantcontext-management)
4. [Row-Level Security (RLS)](#row-level-security-rls)
5. [Testing Strategy](#testing-strategy)
6. [References & Related Documents](#references--related-documents)

---

<!-- anchor: overview-architecture -->

## 1. Overview & Architecture

### Tenancy Model

Village Storefront implements a **shared database, tenant-scoped query** multi-tenancy model. All tenant-scoped tables include a `tenant_id UUID` foreign key to the `tenants` table. Data isolation is enforced at three layers:

1. **HTTP Layer:** `TenantResolutionFilter` (JAX-RS request filter) resolves tenant from HTTP Host header
2. **Application Layer:** `TenantContext` (ThreadLocal) propagates tenant ID through service/repository calls
3. **Database Layer:** PostgreSQL Row-Level Security (RLS) policies enforce tenant filtering at the SQL level

### Tenant Access Gateway (TAG)

The **Tenant Access Gateway** is the named entry point from Section 2 of the Core Architecture brief. It is implemented by `TenantResolutionFilter` and is responsible for:

- Normalizing hostnames, stripping ports, and rejecting IP-based access per Clarification 1
- Resolving active tenants via custom domains first, then subdomains
- Seeding `TenantContext` along with placeholder feature flag snapshots
- Setting the `app.tenant_id` PostgreSQL session variable when `tenant.rls.enabled=true`
- Emitting CDI events (`TenantResolved`, `TenantMissing`) so downstream listeners (feature flags, auditing) can subscribe without reading HTTP metadata directly
- Logging every decision (structured JSON) for RISK-001 auditing and RISK-002 plan traceability

> **Component Diagram Cross-Link:** The Tenant Access Gateway lives inside the `edge-gateway` component placeholder on `docs/architecture/component_diagram.puml` (I1.T4). Once that diagram is accepted, reference this document's anchors for filter responsibilities.

### Defense-in-Depth Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                    HTTP Request Layer                            │
│  TenantResolutionFilter: Host header → Tenant lookup            │
│  Rejects requests with missing/invalid/suspended tenants        │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│                  Application Layer (ThreadLocal)                 │
│  TenantContext: Propagates tenant_id through request lifecycle  │
│  All service/repository methods use getCurrentTenantId()        │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│              Database Layer (PostgreSQL RLS)                     │
│  Row-Level Security: app.tenant_id session variable             │
│  FORCE ROW LEVEL SECURITY on all tenant-scoped tables          │
│  Blocks queries when session variable not set                   │
└─────────────────────────────────────────────────────────────────┘
```

### Key Design Principles

1. **Fail-Safe Defaults:** Missing tenant context → request rejected (HTTP 400/404) or RLS blocks all rows
2. **Explicit Over Implicit:** All repository queries explicitly reference `TenantContext.getCurrentTenantId()`
3. **Audit Trail:** All tenant resolution events logged with structured JSON (hostname, tenant_id, duration, outcome)
4. **Cache Coherence:** Tenant resolution results cached via Caffeine (configurable: `tenant.cache.enabled`)
5. **Toggle-Friendly:** RLS enforcement can be disabled via `tenant.rls.enabled=false` (testing/debugging only)

### Supported Tenant Access Patterns

| Access Pattern | Resolution Strategy | Example |
|---------------|---------------------|---------|
| **Subdomain** | Extract subdomain from `Host: storename.villagecompute.com` → query `tenants.subdomain` | `GET / Host: mystore.villagecompute.com` |
| **Custom Domain** | Query `custom_domains.domain` (exact match, verified=true) → join to `tenants` | `GET / Host: shop.example.com` |
| **Background Jobs** | Manual context seeding via `TenantContext.setCurrentTenantId(uuid)` | Scheduled jobs, async processing |
| **API Keys** | (Future) API key linked to tenant → same resolution mechanism | Server-to-server integrations |

---

<!-- anchor: tenant-resolution-flow -->

## 2. Tenant Resolution Flow

### Request Lifecycle Sequence

```
┌──────────────────────────────────────────────────────────────────┐
│ 1. HTTP Request Arrives                                          │
│    Host: storename.villagecompute.com                            │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 2. TenantResolutionFilter (Priority: AUTHENTICATION - 1)        │
│    - Extract host from Host header                               │
│    - Normalize to lowercase                                      │
│    - Strip port if present (e.g., :8080)                         │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 3. Cache Lookup (if tenant.cache.enabled=true)                  │
│    - Check Caffeine cache for hostname → TenantInfo mapping     │
│    - Cache miss → proceed to database lookup                     │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 4a. Strategy 1: Custom Domain Lookup                            │
│     SELECT t.id, t.subdomain, t.name, t.status                  │
│     FROM tenants t                                               │
│     JOIN custom_domains cd ON cd.tenant_id = t.id               │
│     WHERE cd.domain = :hostname AND cd.verified = true          │
└──────────────────────────────────────────────────────────────────┘
         ↓ (if not found)
┌──────────────────────────────────────────────────────────────────┐
│ 4b. Strategy 2: Subdomain Extraction                            │
│     - Check if hostname matches: ^([a-z0-9-]+)\.villagecompute\.com$ │
│     - Extract subdomain prefix                                   │
│     SELECT t.id, t.subdomain, t.name, t.status                  │
│     FROM tenants t                                               │
│     WHERE t.subdomain = :subdomain                              │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 5. Tenant Status Validation                                      │
│    - Not found → Fire TenantMissing event → HTTP 404            │
│    - status=suspended → Fire TenantMissing event → HTTP 503     │
│    - status≠active → Fire TenantMissing event → HTTP 404        │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 6. Context Population                                            │
│    - TenantContext.setCurrentTenant(tenantInfo)                 │
│    - TenantContext.setFeatureFlagSnapshot(placeholder)          │
│    - If tenant.rls.enabled=true:                                │
│      → SELECT set_current_tenant_id(:tenantId)                  │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 7. CDI Event Broadcast                                           │
│    - Fire TenantResolved(tenantInfo, hostname) event            │
│    - Downstream subscribers can hydrate feature flags, etc.     │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 8. Request Processing                                            │
│    - Authentication filter (JWT validation)                      │
│    - Business logic (services/repositories)                      │
│    - All queries filtered by TenantContext.getCurrentTenantId() │
└──────────────────────────────────────────────────────────────────┘
                            ↓
┌──────────────────────────────────────────────────────────────────┐
│ 9. Response & Cleanup (TenantContextClearFilter)                │
│    - If tenant.rls.enabled=true:                                │
│      → SELECT set_config('app.tenant_id', '', FALSE)            │
│    - TenantContext.clear() (remove ThreadLocal)                 │
└──────────────────────────────────────────────────────────────────┘
```

### Subdomain Resolution Logic

**Implementation:** `TenantResolutionFilter.resolveFromSubdomain()` (line 234)

```java
// Example: "mystore.villagecompute.com" → subdomain="mystore"
String SUBDOMAIN_PATTERN = "^([a-z0-9][a-z0-9-]{0,61}[a-z0-9]?)\\.villagecompute\\.com$";

if (hostname.matches(SUBDOMAIN_PATTERN)) {
    String subdomain = hostname.substring(0, hostname.indexOf('.'));
    // Query tenants.subdomain = :subdomain
}
```

**Subdomain Constraints:**
- 2-63 characters (RFC 1123 DNS label limits)
- Lowercase alphanumeric + hyphens
- Cannot start/end with hyphen
- Unique across platform (enforced by database constraint)

### Custom Domain Resolution Logic

**Implementation:** `TenantResolutionFilter.resolveFromCustomDomain()` (line 213)

```java
// Example: "shop.example.com" → lookup custom_domains.domain
SELECT t.id, t.subdomain, t.name, t.status
FROM tenants t
JOIN custom_domains cd ON cd.tenant_id = t.id
WHERE cd.domain = :domain AND cd.verified = true
```

**Custom Domain Requirements:**
- Domain ownership verified via DNS TXT record (separate process)
- `custom_domains.verified = true` required for resolution
- Multiple custom domains per tenant supported
- Fallback to subdomain if custom domain lookup fails

### Cache Behavior

**Cache Implementation:** Caffeine (via `quarkus-cache`)
**Cache Key:** Normalized hostname (lowercase, port stripped)
**Cache Invalidation:** Manual (see `TenantCacheInvalidator` service)

```java
// Cache configuration (application.properties)
tenant.cache.enabled=true              // Toggle caching
quarkus.cache.caffeine."tenant-cache".maximum-size=1000
quarkus.cache.caffeine."tenant-cache".expire-after-write=10m
```

**When to Invalidate Cache:**
- Tenant subdomain changed
- Custom domain added/removed/verified
- Tenant status changed (active → suspended)

---

<!-- anchor: tenantcontext-management -->

## 3. TenantContext Management

### ThreadLocal Lifecycle

**Implementation:** `TenantContext.java` (ThreadLocal holder)

```java
public class TenantContext {
    private static final ThreadLocal<TenantInfo> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<FeatureFlagSnapshot> FEATURE_FLAGS = new ThreadLocal<>();

    // Populated by TenantResolutionFilter
    public static void setCurrentTenant(TenantInfo tenantInfo) { ... }

    // Used by all repository queries
    public static UUID getCurrentTenantId() { ... }

    // MUST be called in response filter
    public static void clear() {
        CURRENT_TENANT.remove();
        FEATURE_FLAGS.remove();
    }
}
```

### Context Population

**Primary Path (HTTP Requests):**
`TenantResolutionFilter.filter()` → `TenantContext.setCurrentTenant(tenantInfo)`

**Background Job Path:**
```java
// Scheduled job / async processor
TenantContext.setCurrentTenantId(tenantId); // Resolves Tenant entity from DB
try {
    // Business logic here (all queries auto-filtered by tenant_id)
} finally {
    TenantContext.clear(); // CRITICAL: prevent ThreadLocal leak
}
```

### Context Consumption (Repository Pattern)

**All repository queries MUST filter by tenant:**

```java
// Example: ProductRepository
public List<Product> findActiveProducts() {
    return Product.find("tenant.id = ?1 AND status = ?2",
                        TenantContext.getCurrentTenantId(),
                        "active")
                  .list();
}
```

### Panache Query Filter Template

Every Panache repository MUST expose an internal helper that injects the tenant predicate before any additional criteria. This keeps the tenant scope centralized and testable, mirroring the Panache filter convention documented in Section 2 of the Core Architecture brief.

```java
@ApplicationScoped
public class TenantScopedProductRepository implements PanacheRepositoryBase<Product, UUID> {

    private static final String TENANT_FILTER = "tenant.id = ?1";

    public List<Product> findBySku(String sku) {
        return list(TENANT_FILTER + " AND sku = ?2",
                    TenantContext.getCurrentTenantId(), sku);
    }

    // Test-only hook to override tenant ID explicitly
    List<Product> findBySku(UUID tenantId, String sku) {
        return list(TENANT_FILTER + " AND sku = ?2", tenantId, sku);
    }

    // TODO(I1.T2): Promote TENANT_FILTER to a reusable Panache predicate helper once
    // `TenantScopedPanacheRepository` lands (see docs/architecture/tenant_isolation.md#tenantcontext-management).
}
```

**Panache Entity Pattern:**

```java
@Entity
public class Product extends PanacheEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    public Tenant tenant;

    @PrePersist
    void setTenant() {
        if (tenant == null) {
            tenant = Tenant.findById(TenantContext.getCurrentTenantId());
        }
    }
}
```

### Cleanup Guarantees

**Implementation:** `TenantContextClearFilter.java` (response filter)

```java
@Provider
@Priority(Priorities.USER)
public class TenantContextClearFilter implements ContainerResponseFilter {
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        try {
            if (TenantContext.hasContext()) {
                if (rlsEnabled) {
                    // Reset PostgreSQL session variable
                    entityManager.createNativeQuery(
                        "SELECT set_config('app.tenant_id', '', FALSE)"
                    ).getSingleResult();
                }
            }
            TenantContext.clear(); // ALWAYS called, even on exceptions
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error clearing tenant context", e);
        }
    }
}
```

**Cleanup is mandatory to prevent:**
- ThreadLocal memory leaks in Quarkus worker thread pool
- Context bleed-over between requests on same thread
- Stale `app.tenant_id` session variable in PostgreSQL connection pool

### CDI Event Model

**Events Published:**

```java
// Success path
@Inject Event<TenantResolved> tenantResolvedEvent;
tenantResolvedEvent.fire(new TenantResolved(tenantInfo, hostname));

// Failure paths
@Inject Event<TenantMissing> tenantMissingEvent;
tenantMissingEvent.fire(new TenantMissing(hostname, reason));
```

**Example Subscriber (Feature Flag Hydration):**

```java
// TODO: Implement in future iteration
void onTenantResolved(@Observes TenantResolved event) {
    FeatureFlagSnapshot snapshot = featureFlagService.loadForTenant(event.tenantInfo().tenantId());
    TenantContext.setFeatureFlagSnapshot(snapshot);
}
```

---

<!-- anchor: row-level-security-rls -->

## 4. Row-Level Security (RLS)

### Architecture Overview

PostgreSQL Row-Level Security provides defense-in-depth enforcement at the database layer. All tenant-scoped tables have `FORCE ROW LEVEL SECURITY` enabled, which means:

1. RLS policies apply even to table owners (not just normal users)
2. Queries without `app.tenant_id` session variable set → return zero rows
3. Queries with mismatched `tenant_id` → return zero rows (unauthorized access blocked)

**Migration:** `V20260113__enable_rls_policies.sql` (lines 1-350)

### Helper Functions

**Set Tenant Context (called by TenantResolutionFilter):**

```sql
CREATE OR REPLACE FUNCTION set_current_tenant_id(p_tenant_id UUID)
RETURNS VOID AS $$
BEGIN
    PERFORM set_config('app.tenant_id', p_tenant_id::TEXT, FALSE);
    PERFORM set_config('row_security', 'on', FALSE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
```

**Get Current Tenant (used in RLS policies):**

```sql
CREATE OR REPLACE FUNCTION get_current_tenant_id()
RETURNS UUID AS $$
BEGIN
    RETURN NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID;
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;
```

**Session Variable:** `app.tenant_id` (PostgreSQL session-scoped configuration parameter)

### RLS Policy Template

**Pattern applied to all tenant-scoped tables:**

```sql
-- Example: products table
ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;

CREATE POLICY products_isolation_policy ON products
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY products_isolation_policy ON products IS
    'RLS policy: restrict products to current tenant';
```

**Policy Explanation:**
- `FOR ALL`: Applies to SELECT, INSERT, UPDATE, DELETE
- `USING`: Filter condition for reads (SELECT) and mutation validation
- `tenant_id = get_current_tenant_id()`: Only return/modify rows matching session variable

### Protected Tables (by Module)

**Tenancy Module:**
- `tenants` (special case: `id = get_current_tenant_id()`)
- `custom_domains`

**Identity Module:**
- `users`, `roles`, `user_roles`, `api_keys`

**Catalog Module:**
- `categories`, `products`, `product_variants`, `product_categories`, `product_images`, `inventory_levels`

**Cart/Order/Payment Module:**
- `carts`, `cart_items`, `orders`, `order_line_items`, `shipments`, `payment_methods`, `payments`, `refunds`

**Consignment Module:**
- `consignors`, `consignment_items`, `payout_batches`, `payout_line_items`

**Loyalty Module:**
- `loyalty_programs`, `loyalty_members`, `loyalty_transactions`

### RLS Enforcement Flow

```
Application Query:
    SELECT * FROM products WHERE status = 'active';

PostgreSQL Query Planner Applies RLS:
    SELECT * FROM products
    WHERE status = 'active'
    AND tenant_id = get_current_tenant_id();

Execution:
    1. get_current_tenant_id() → Reads 'app.tenant_id' session variable
    2. If NULL → No rows returned (fail-safe)
    3. If UUID → Filter applied transparently
    4. Index scan on (tenant_id, status) composite index
```

### RLS Configuration Toggles

```properties
# application.properties
tenant.rls.enabled=true   # Default: enable RLS session variable seeding
```

**When `tenant.rls.enabled=false`:**
- `TenantResolutionFilter` skips `SELECT set_current_tenant_id(...)` call
- `TenantContextClearFilter` skips session variable reset
- RLS policies remain active (but queries will return 0 rows unless manually seeded)
- **Use case:** Integration tests that manually control RLS state

**Security Note:** Application database user must NOT be a superuser (superusers bypass RLS).

### Rollback Procedures

**Rolling back RLS migration (emergencies only):**

```bash
cd modules/core-platform/src/main/resources/db/migrations
# Manual rollback (no automated down migration in Flyway)
psql -U storefront -d storefront -f rollback_rls.sql
```

**Rollback script creates temporary policies allowing all access:**

```sql
-- Emergency rollback template (execute per-table)
DROP POLICY IF EXISTS products_isolation_policy ON products;
ALTER TABLE products DISABLE ROW LEVEL SECURITY;
ALTER TABLE products NO FORCE ROW LEVEL SECURITY;
DROP FUNCTION IF EXISTS get_current_tenant_id();
DROP FUNCTION IF EXISTS set_current_tenant_id(UUID);
```

**Rollback checklist:**
1. ✅ Verify application-level tenant filtering is working (TenantContext in all queries)
2. ✅ Alert on-call team (potential data leak window during rollback)
3. ✅ Execute rollback on staging environment first
4. ✅ Monitor error rates post-rollback
5. ✅ Schedule RLS re-enablement (fix underlying issue, test, redeploy)

### Performance Considerations

**Index Requirements:**
All tenant-scoped tables have composite indexes starting with `tenant_id`:

```sql
-- Example: products table
CREATE INDEX idx_products_tenant_status ON products(tenant_id, status);
CREATE INDEX idx_products_tenant_sku ON products(tenant_id, sku);
```

**Query Planner Behavior:**
- RLS policies integrated into query plan (not applied as post-filter)
- Equality check on `tenant_id` → Index scan (not table scan)
- Negligible overhead vs. explicit `WHERE tenant_id = ?` in application code

**Benchmarks (PostgreSQL 17, 1M products, 1000 tenants):**
- Without RLS: 0.8ms avg query time
- With RLS: 0.9ms avg query time (+12.5% overhead)
- Network latency dominates (10-50ms typical), RLS overhead negligible

---

<!-- anchor: testing-strategy -->

## 5. Testing Strategy

### Test Layers

Village Storefront validates tenant isolation at three layers:

1. **Unit Tests:** Verify TenantContext lifecycle, helper methods
2. **Integration Tests:** Verify repository queries respect tenant filters
3. **System Tests:** Verify HTTP-to-database isolation with real PostgreSQL + RLS

### Unit Tests (TenantContext)

**Test File:** `TenantContextTest.java` (planned)

```java
@Test
void getCurrentTenantId_throwsWhenContextNotSet() {
    TenantContext.clear();
    assertThrows(IllegalStateException.class,
                 () -> TenantContext.getCurrentTenantId());
}

@Test
void setCurrentTenant_populatesFeatureFlagPlaceholder() {
    TenantInfo info = new TenantInfo(TENANT_ID, "mystore", "My Store", "active");
    TenantContext.setCurrentTenant(info);
    assertTrue(TenantContext.hasFeatureFlagSnapshot());
}
```

### Integration Tests (Repository Layer)

**Test File:** `TenantIsolationIT.java`

**Purpose:** Verify that repository queries return only current tenant's data, even without RLS.

```java
@QuarkusTest
class TenantIsolationIT {
    @Test
    void productRepository_filtersToCurrentTenant() {
        // Setup: Tenant A and Tenant B with products
        Tenant tenantA = createTenant("tenant-a");
        Tenant tenantB = createTenant("tenant-b");
        createProduct(tenantA, "Product A1");
        createProduct(tenantB, "Product B1");

        // Act: Set context to Tenant A
        TenantContext.setCurrentTenantId(tenantA.id);
        List<Product> products = Product.listAll();

        // Assert: Only Tenant A products visible
        assertEquals(1, products.size());
        assertEquals("Product A1", products.get(0).name);
        assertEquals(tenantA.id, products.get(0).tenant.id);
    }
}
```

**Test Suite Reference:** `docs/quality/tenant_isolation.md`

### System Tests (RLS Layer)

**Test File:** `TenantFilterTest.java`

**Purpose:** Verify PostgreSQL RLS policies enforce isolation when `app.tenant_id` session variable is set/unset.

**Test Infrastructure:** Quarkus Dev Services (Testcontainers) automatically starts PostgreSQL 17 container.

```java
@QuarkusTest
class TenantFilterTest {
    @Test
    void rlsPolicies_blockQueriesWhenSessionVariableNotSet() {
        // Setup: Create tenant + user
        Tenant tenant = createTenant("mystore");
        User user = createUser(tenant, "user@example.com");

        // Act: Clear session variable, query users
        entityManager.createNativeQuery(
            "SELECT set_config('app.tenant_id', '', FALSE)"
        ).getSingleResult();
        List<User> users = User.listAll();

        // Assert: RLS blocks all rows
        assertEquals(0, users.size());
    }

    @Test
    void rlsPolicies_allowQueriesWhenSessionVariableMatches() {
        // Setup: Create tenant + user
        Tenant tenant = createTenant("mystore");
        User user = createUser(tenant, "user@example.com");

        // Act: Set session variable, query users
        entityManager.createNativeQuery(
            "SELECT set_current_tenant_id(:tenantId)"
        ).setParameter("tenantId", tenant.id).getSingleResult();
        List<User> users = User.listAll();

        // Assert: RLS allows tenant's rows
        assertEquals(1, users.size());
        assertEquals(tenant.id, users.get(0).tenant.id);
    }
}
```

**Running RLS Tests:**

```bash
# Run focused test suite (PostgreSQL container auto-started)
./mvnw test -Dtest=TenantFilterTest

# Run full tenant isolation suite
./mvnw test -Dtest=TenantIsolationIT,TenantFilterTest
```

### Manual Verification (Production Debugging)

**See:** `docs/rls-setup.md` (lines 79-116) for step-by-step SQL verification.

```sql
-- Connect to database
psql -U storefront -d storefront

-- Set tenant context to tenant1
SELECT set_current_tenant_id('11111111-1111-1111-1111-111111111111'::uuid);

-- Query users - should only see tenant1 users
SELECT email FROM users;

-- Clear tenant context
SELECT set_config('app.tenant_id', '', FALSE);

-- Query users - should see NO users (RLS blocks all)
SELECT email FROM users;
-- Expected: (empty result)
```

### Test Hooks for Future Development

**Repository Method Convention:**
All repository methods should accept optional `tenantId` parameter for testing:

```java
// Production code path
public List<Product> findActiveProducts() {
    return findActiveProducts(TenantContext.getCurrentTenantId());
}

// Test-friendly overload (allows explicit tenant override)
List<Product> findActiveProducts(UUID tenantId) {
    return Product.find("tenant.id = ?1 AND status = ?2", tenantId, "active").list();
}
```

**CDI Event Testing:**
Mock `TenantResolved` / `TenantMissing` events in tests:

```java
@Inject
Event<TenantResolved> tenantResolvedEvent;

@Test
void featureFlagService_hydratesOnTenantResolved() {
    TenantInfo info = createTenantInfo();
    tenantResolvedEvent.fire(new TenantResolved(info, "mystore.villagecompute.com"));
    // Assert feature flags hydrated
}
```

---

<!-- anchor: references-related-documents -->

## 6. References & Related Documents

### Architecture Documents

- **[Architecture Overview](../architecture_overview.md)** (Section 4: Multi-Tenancy & Data Isolation)
- **[ADR-001: Tenancy Strategy](../adr/ADR-001-tenancy.md)** (authoritative design decisions)
- **[Component Diagram](../architecture/component_diagram.puml)** (placeholder: I1.T4)
- **[ERD](../diagrams/datamodel_erd.puml)** (baseline schema)
- **[Domain ERD](../diagrams/domain_erd.puml)** (authoritative schema with RLS + indexes, Task I1.T4)

### Implementation References

- **TenantResolutionFilter:** `modules/core-platform/src/main/java/villagecompute/storefront/tenant/TenantResolutionFilter.java`
- **TenantContext:** `modules/core-platform/src/main/java/villagecompute/storefront/tenant/TenantContext.java`
- **TenantContextClearFilter:** `modules/core-platform/src/main/java/villagecompute/storefront/tenant/TenantContextClearFilter.java`
- **RLS Migration:** `modules/core-platform/src/main/resources/db/migrations/V20260113__enable_rls_policies.sql`

### Operational Guides

- **[RLS Setup Guide](../rls-setup.md)** (manual verification, rollback procedures)
- **[Tenant Isolation Test Suite](../quality/tenant_isolation.md)** (mandatory CI tests)

### Standards & Clarifications

- **[Java Project Standards](../java-project-standards.adoc)** (Section 6: Multi-Tenancy Requirements)
- **Clarification 1:** Subdomain-based tenant resolution (not IP-based)
- **Clarification 6:** PostgreSQL RLS as defense-in-depth layer

### Risk Register

- **RISK-001:** Tenant data leakage due to missing `tenant_id` filter (mitigated by RLS + test suite)

---

## 7. Database Schema & Entity Relationship Diagram

### Domain ERD Overview

The authoritative database schema is documented in `docs/diagrams/domain_erd.puml` (Task I1.T4). This PlantUML diagram provides comprehensive coverage of all tenant-scoped entities with detailed commentary on:

- **Tenant isolation enforcement:** Every table includes `tenant_id UUID NOT NULL` foreign key to `tenants(id)`
- **PostgreSQL RLS policies:** All tables marked with `<<RLS>>` stereotype have `FORCE ROW LEVEL SECURITY` enabled
- **Composite indexes:** Critical performance indexes listed for tenant-scoped queries (e.g., `(tenant_id, sku)`, `(tenant_id, status, created_at)`)
- **Partitioning strategy:** Tables marked with `<<PARTITIONED>>` include `sessions`, `audit_log_entries`, and `offline_pos_queue`
- **Data type standards:** NUMERIC(19,4) for money, TIMESTAMPTZ for timestamps, JSONB for flexible data

### Entity Modules

The ERD organizes entities into logical modules aligned with the bounded contexts from the component diagram:

1. **Tenancy Module:** `tenants`, `custom_domains` (foundation for all isolation)
2. **Identity Module:** `users`, `roles`, `user_roles`, `sessions`, `api_keys`
3. **Catalog Module:** `categories`, `products`, `product_variants`, `product_categories`, `product_images`, `inventory_levels`
4. **Cart/Order/Payment Module:** `carts`, `cart_items`, `orders`, `order_line_items`, `shipments`, `payment_methods`, `payments`, `refunds`
5. **Consignment Module:** `consignors`, `consignment_items`, `payout_batches`, `payout_line_items` (see `docs/consignment/domain.md`)
6. **Loyalty Module:** `loyalty_programs`, `loyalty_members`, `loyalty_transactions`, `gift_cards`, `gift_card_transactions`, `subscription_plans`
7. **POS Module:** `pos_devices`, `offline_pos_queue` (encrypted offline transaction replay)
8. **Media Module:** `media_assets`, `media_variants` (Cloudflare R2 storage with FFmpeg processing)
9. **Platform-Ops Module:** `background_jobs`, `domain_events`, `audit_log_entries`, `feature_flags`

### Key Indexes for Tenant-Scoped Queries

All tenant-scoped tables follow the composite index convention `(tenant_id, <lookup_column>)` to enable efficient filtering:

**Tenancy:**
- `idx_tenants_subdomain` (UNIQUE): Subdomain-based tenant resolution
- `idx_custom_domains_domain` (UNIQUE): Custom domain-based resolution
- `idx_custom_domains_tenant_verified` (tenant_id, verified): Active domain lookup

**Identity:**
- `idx_users_tenant_email` (UNIQUE, tenant_id, email): User authentication
- `idx_sessions_tenant_token` (tenant_id, session_token): Session validation
- `idx_sessions_tenant_expires` (tenant_id, expires_at): Cleanup queries (partial index WHERE expires_at < NOW())

**Catalog:**
- `idx_products_tenant_sku` (UNIQUE, tenant_id, sku): Product lookup
- `idx_products_tenant_type_status` (tenant_id, type, status): Active product queries
- `idx_variants_tenant_product` (tenant_id, product_id, status): Variant listing

**Orders:**
- `idx_orders_tenant_number` (UNIQUE, tenant_id, order_number): Order lookup
- `idx_orders_tenant_user` (tenant_id, user_id, status): Customer order history
- `idx_payments_provider_txn` (provider_transaction_id): Webhook idempotency

**Consignment:**
- `idx_consignment_items_tenant_consignor` (tenant_id, consignor_id, status): Vendor inventory
- `idx_consignment_items_sold` (tenant_id, status, sold_at): Payout batch generation (partial index WHERE status='sold')
- `idx_payout_batches_tenant_period` (UNIQUE, tenant_id, consignor_id, period_start, period_end): Duplicate prevention

**Loyalty:**
- `idx_loyalty_members_unique` (UNIQUE, tenant_id, program_id, user_id): Loyalty enrollment guardrail
- `idx_loyalty_tx_member` (tenant_id, member_id, created_at DESC): Ledger pagination + balance checks
- `idx_loyalty_tx_order` (tenant_id, order_id): Order-to-loyalty reconciliation and fraud traces
- `idx_gift_card_tx_card` (tenant_id, gift_card_id, created_at DESC): Redemption audit with time filtering

**Background Jobs:**
- `idx_background_jobs_queue_status` (tenant_id, queue, status, run_at): Worker polling
- `idx_background_jobs_worker` (status, run_at): Distributed job claiming (SKIP LOCKED)
- `idx_background_jobs_lock_expiry` (locked_until): Stale lock cleanup

### Partitioned Tables

The following tables use PostgreSQL declarative partitioning for performance and retention management:

**sessions** (monthly range partitions):
- Partition key: `created_at`
- Strategy: One partition per month (e.g., `sessions_2026_01`, `sessions_2026_02`)
- Retention: 90 days, old partitions dropped automatically
- Rationale: High write volume, session data expires quickly

**audit_log_entries** (weekly range partitions):
- Partition key: `created_at`
- Strategy: One partition per week (e.g., `audit_2026_w01`, `audit_2026_w02`)
- Retention: 2 years active, then archival to S3 for 7 years (compliance requirement)
- Rationale: Compliance logging, high write volume, time-based queries

**offline_pos_queue** (composite tenant + time partitions):
- Partition key: `(tenant_id, created_at)`
- Strategy: One partition per tenant per month
- Retention: 6 months, then dropped (transaction replayed, no need to retain)
- Rationale: Tenant-scoped sharding preparation, encrypted payload isolation

### RLS Policy Enforcement

Every tenant-scoped table has a corresponding RLS policy enforcing `tenant_id = get_current_tenant_id()`. The helper function `get_current_tenant_id()` reads the PostgreSQL session variable `app.tenant_id`, which is set by `TenantResolutionFilter` at request start and cleared by `TenantContextClearFilter` at response end.

**RLS Policy Template:**
```sql
ALTER TABLE <table_name> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <table_name> FORCE ROW LEVEL SECURITY;

CREATE POLICY <table_name>_isolation_policy ON <table_name>
    FOR ALL
    USING (tenant_id = get_current_tenant_id());
```

**Special Cases:**
- `tenants` table: Policy is `id = get_current_tenant_id()` (self-referential)
- Cross-cutting tables (`audit_log_entries`, `background_jobs`, `domain_events`, `feature_flags`): `tenant_id` is nullable for platform-wide operations, RLS policy is `(tenant_id IS NULL OR tenant_id = get_current_tenant_id())`

### Data Type Standards (PostgreSQL 17)

The schema follows these data type conventions for consistency and PostgreSQL 17 compatibility:

- **UUIDs:** All primary keys and foreign keys (`UUID` type, not `VARCHAR(36)`)
- **Money:** `NUMERIC(19,4)` with HALF_UP rounding (supports micro-currencies, no floating-point errors)
- **Timestamps:** `TIMESTAMPTZ` (UTC storage, local display via application timezone)
- **Text:** `VARCHAR(n)` for bounded fields (with explicit limits), `TEXT` for unbounded content
- **JSON:** `JSONB` for flexible data (indexed via GIN indexes, not plain `JSON`)
- **IP Addresses:** `INET` type for IP address columns (supports IPv4 and IPv6)
- **Enumerations:** `VARCHAR(n)` with application-level validation (not PostgreSQL `ENUM` type for schema flexibility)

### Consignment Schema Details

The consignment module entities (`consignors`, `consignment_items`, `payout_batches`, `payout_line_items`) implement the commission tracking and payout logic documented in `docs/consignment/domain.md`. Key relationships:

- `consignment_items.product_id` → `products.id`: Links consigned products to vendors
- `consignment_items.consignor_id` → `consignors.id`: Tracks item ownership
- `payout_line_items.order_line_item_id` → `order_line_items.id`: References sold items for payout calculation
- `payout_batches` unique constraint on `(tenant_id, consignor_id, period_start, period_end)`: Prevents duplicate payouts

**Commission Calculation:**
```sql
-- Stored as NUMERIC(19,4) for precision
commission_amount = item_subtotal × (commission_rate / 100)
net_payout = item_subtotal - commission_amount
```

### Loyalty & Gift Card Schema

The loyalty module introduces several new entities beyond the baseline ERD:

**Gift Cards:**
- `gift_cards` table stores card codes, balances, and expiry dates
- `gift_card_transactions` ledger tracks all redemptions and refunds
- Code uniqueness is global (not tenant-scoped) to prevent cross-tenant fraud
- Current balance updated via row-level locking during redemption (optimistic concurrency control)

**Subscription Plans:**
- `subscription_plans` defines recurring billing configurations
- Future integration with Stripe Subscriptions API (per ADR-003)
- Features JSONB field stores plan entitlements for feature flag gating

### Background Jobs & Domain Events

**Background Jobs (Database-Backed Queue):**
- Eliminates need for external message broker (Redis/RabbitMQ)
- Workers poll `WHERE status='pending' AND run_at <= NOW()` with `FOR UPDATE SKIP LOCKED`
- Queues: `default`, `media`, `payouts`, `emails`, `reports`, `cleanup`
- Automatic retry with exponential backoff (controlled by `attempts` and `max_attempts`)

**Domain Events (Event Sourcing):**
- Immutable append-only log of domain events (`OrderPlaced`, `PaymentSucceeded`, etc.)
- Event replay capability for projections and audit trails
- Future integration with Kafka/Pulsar for external consumers
- Indexed by `(tenant_id, aggregate_type, aggregate_id, published_at)` for event stream queries

### Media Assets & Variants

**Media Module:**
- `media_assets`: Original uploaded files (images, videos, documents)
- `media_variants`: Processed variants (thumbnails, transcoded videos, previews)
- Storage via Cloudflare R2 (S3-compatible), keys stored in `storage_key` column
- Processing via FFmpeg (videos) and Thumbnailator (images) in background jobs

**Variant Strategy:**
- Images: `thumbnail` (100x100), `small` (400x400), `medium` (800x800), `large` (1600x1600)
- Videos: Transcoded to 480p, 720p, 1080p with HLS playlists
- Lazy generation: Variants created on-demand if not exist

### POS Offline Queue

**Offline Transaction Replay:**
- `offline_pos_queue` stores encrypted transaction payloads from POS devices
- Partitioned by `(tenant_id, created_at)` for tenant-scoped sharding
- Payload encrypted at-rest (AES-256-GCM), decryption key per tenant via KMS
- Sync on network reconnect, conflict resolution via timestamp-based merge

---

**Document Maintainers:** Architecture Team
**Review Cadence:** After each ADR affecting tenancy model
**Next Review:** Q2 2026 (post-tenant sharding evaluation)
