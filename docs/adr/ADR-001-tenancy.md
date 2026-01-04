# ADR-001: Multi-Tenancy & Tenant Isolation Strategy

**Status:** Accepted
**Date:** 2026-01-02
**Deciders:** Architecture Team, Tech Lead
**Consulted:** DevOps Lead, Security Team
**Informed:** Engineering Team, Product Management

---

## Context

Village Storefront is a multi-tenant SaaS ecommerce platform where each merchant operates an independent online store. The platform must support:

1. **Tenant Identification:** Each store accessible via subdomain (`storename.villagecompute.com`) or custom domain (`shop.merchant.com`)
2. **Data Isolation:** Complete logical separation of tenant data (products, orders, customers) to prevent cross-tenant data leakage
3. **Scalability:** Support 1000+ concurrent tenants with minimal performance overhead
4. **Operational Simplicity:** Single deployable application, avoiding microservices operational complexity at early stage
5. **Future Flexibility:** Design must allow tenant sharding or microservices extraction if needed

### Technical Context

From `docs/java-project-standards.adoc`:
- **Framework:** Quarkus 3.17+ (Jakarta EE, Panache ORM)
- **Database:** PostgreSQL 17 (JSONB support, proven multi-tenancy patterns)
- **Deployment:** Kubernetes (k3s), native executable (GraalVM)
- **Session Management:** JWT tokens (stateless), no Redis dependency

### Competitive Landscape (from competitor research)

| Platform | Tenancy Model | Trade-offs |
|----------|---------------|------------|
| **Shopify** | Instance-per-tenant | Isolated but expensive at scale, complex provisioning |
| **Spree Commerce** | Shared DB, tenant-scoped queries | Cost-efficient, requires rigorous query filtering |
| **Medusa.js** | Configurable (shared DB or isolated) | Flexible but increases architectural complexity |
| **Squarespace** | Website builder (not true multi-tenancy) | Different problem domain |

**Decision Driver:** Spree's proven shared-database model balances cost efficiency, operational simplicity, and data isolation when implemented correctly.

---

## Decision

We will implement a **shared database, tenant-scoped architecture** with the following design:

### 1. Tenant Data Model

```sql
-- Core tenant table
CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subdomain VARCHAR(63) UNIQUE NOT NULL,  -- RFC 1035 label max length
    name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, suspended, deleted
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    settings JSONB DEFAULT '{}'  -- Tenant-specific config (theme, locale, features)
);

-- Custom domain mapping (many-to-one with tenants)
CREATE TABLE custom_domains (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    domain VARCHAR(253) UNIQUE NOT NULL,  -- RFC 1035 FQDN max length
    verified BOOLEAN DEFAULT FALSE,
    verification_token VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_custom_domains_tenant_id ON custom_domains(tenant_id);
CREATE INDEX idx_custom_domains_domain ON custom_domains(domain);

-- Example tenant-scoped table (products)
CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    -- ... other product fields
    CONSTRAINT uq_products_tenant_sku UNIQUE (tenant_id, sku)
);
CREATE INDEX idx_products_tenant_id ON products(tenant_id);
```

**Key Design Elements:**
- Every tenant-scoped table includes `tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE`
- Composite unique constraints include `tenant_id` (e.g., SKU uniqueness per tenant, not globally)
- Indexes on `tenant_id` for query performance

### 2. Tenant Resolution Flow

```
┌─────────────────────────────────────────────────────────────┐
│  HTTP Request                                               │
│  Host: storename.villagecompute.com                         │
│  or Host: shop.merchant.com                                 │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│  TenantResolutionFilter (Quarkus ContainerRequestFilter)   │
│  Priority: AUTHENTICATION - 1 (runs before auth)           │
│                                                              │
│  1. Extract host from HTTP Host header                      │
│  2. Check custom_domains table by domain                    │
│     → If found: tenant_id = custom_domain.tenant_id         │
│  3. Else: Extract subdomain, check tenants table            │
│     → If found: tenant_id = tenant.id                       │
│  4. If no tenant found: HTTP 404 (store not found)          │
│  5. TenantContext.setCurrentTenant(tenant)                  │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│  Request Processing (Resources, Services, Repositories)     │
│  All data access auto-filtered by TenantContext.tenantId()  │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│  TenantContextClearFilter (ContainerResponseFilter)         │
│  ThreadLocal cleanup to prevent context leakage             │
└─────────────────────────────────────────────────────────────┘
```

### 3. TenantContext Contract

```java
package villagecompute.storefront.util;

/**
 * Thread-local holder for current tenant context.
 * Automatically populated by TenantResolutionFilter.
 *
 * CRITICAL: All repository queries MUST filter by TenantContext.getCurrentTenantId()
 * to prevent cross-tenant data leakage.
 */
public class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT_ID = new ThreadLocal<>();

    /**
     * Set current tenant for this request thread.
     * Called by TenantResolutionFilter.
     */
    public static void setCurrentTenantId(UUID tenantId) {
        Objects.requireNonNull(tenantId, "Tenant ID cannot be null");
        CURRENT_TENANT_ID.set(tenantId);
    }

    /**
     * Get current tenant ID for this request thread.
     * @throws IllegalStateException if no tenant context set (filter not executed)
     */
    public static UUID getCurrentTenantId() {
        UUID tenantId = CURRENT_TENANT_ID.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                "No tenant context available - TenantResolutionFilter not executed");
        }
        return tenantId;
    }

    /**
     * Check if tenant context is set (useful for background jobs).
     */
    public static boolean hasContext() {
        return CURRENT_TENANT_ID.get() != null;
    }

    /**
     * Clear tenant context. Called by TenantContextClearFilter.
     */
    public static void clear() {
        CURRENT_TENANT_ID.remove();
    }
}
```

### 4. Repository-Level Enforcement

All Panache repository queries include tenant filter:

```java
@ApplicationScoped
public class ProductRepository {

    /**
     * Find product by SKU within current tenant.
     * Auto-scoped to TenantContext.getCurrentTenantId().
     */
    public Optional<Product> findBySku(String sku) {
        return Product.find(
            "tenant.id = ?1 AND sku = ?2",
            TenantContext.getCurrentTenantId(),
            sku
        ).firstResultOptional();
    }

    /**
     * List all active products for current tenant.
     */
    public List<Product> listActive() {
        return Product.find(
            "tenant.id = ?1 AND status = 'active'",
            TenantContext.getCurrentTenantId()
        ).list();
    }
}
```

### 5. Entity-Level Safeguards

JPA lifecycle hooks inject tenant ID on persist:

```java
@Entity
@Table(name = "products")
public class Product extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    public Tenant tenant;

    public String sku;
    public String name;
    // ... other fields

    /**
     * Automatically inject current tenant on new entity creation.
     * Prevents accidental cross-tenant data creation.
     */
    @PrePersist
    void injectTenant() {
        if (this.tenant == null) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            this.tenant = Tenant.findById(tenantId);
            if (this.tenant == null) {
                throw new IllegalStateException(
                    "Cannot persist entity: tenant not found for ID " + tenantId);
            }
        }
    }
}
```

### 6. Security: OpenAPI Schema Changes

OpenAPI spec will include tenant-aware security schemes:

```yaml
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: |
        JWT token with claims: sub (user ID), tenant_id, roles.
        Tenant context auto-injected from tenant_id claim.

    apiKeyAuth:
      type: apiKey
      in: header
      name: X-API-Key
      description: |
        Long-lived API key for server-to-server integrations.
        Scoped to specific tenant + permissions.

security:
  - bearerAuth: []
  - apiKeyAuth: []
```

JWT token claims:
```json
{
  "sub": "user-uuid",
  "tenant_id": "tenant-uuid",
  "roles": ["admin"],
  "exp": 1704110400
}
```

---

## Rationale

### Why Shared Database over Database-per-Tenant?

| Criterion | Shared DB | DB-per-Tenant | Analysis |
|-----------|-----------|---------------|----------|
| **Cost** | ✅ Single PostgreSQL instance | ❌ N instances (expensive at scale) | Shared DB wins for 1000+ tenants |
| **Provisioning** | ✅ INSERT into tenants table | ❌ Schema migration per tenant | Shared DB: instant tenant creation |
| **Backup/Recovery** | ✅ Single backup pipeline | ⚠️ N backup jobs | Operational simplicity favors shared |
| **Data Isolation** | ⚠️ Requires app-level filtering | ✅ Physical separation | Mitigated by Row-Level Security (see below) |
| **Schema Evolution** | ✅ Single migration run | ❌ N migrations (slow, risky) | Shared DB: faster iteration |
| **Tenant Sharding** | ✅ Possible via tenant_id hash | ❌ Already sharded, harder to rebalance | Shared DB more flexible |

**Decision:** Shared database offers superior cost, operational simplicity, and provisioning speed. Data isolation risk mitigated through multi-layer enforcement.

### Why ThreadLocal (TenantContext) over Request-Scoped CDI Bean?

- **Performance:** ThreadLocal faster than CDI bean lookup (no proxy overhead)
- **Universality:** Works in non-CDI contexts (background jobs, database callbacks)
- **Clarity:** Explicit `TenantContext.getCurrentTenantId()` calls in queries make tenant scoping visible to reviewers

**Trade-off Accepted:** Must explicitly clear ThreadLocal in response filter (already standard pattern in Quarkus request filters).

### Why Not PostgreSQL Row-Level Security (RLS) Alone?

PostgreSQL RLS can enforce `tenant_id` filtering at database level:
```sql
CREATE POLICY tenant_isolation ON products
    USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
```

**Why Not Primary Defense:**
- Requires setting `app.current_tenant_id` session variable on every connection (Quarkus connection pool overhead)
- Harder to test (requires database-level setup in tests)
- Less explicit in code (filtering "magic" happens in DB, harder to audit)

**Planned Use:** RLS as **defense-in-depth**, not primary isolation mechanism (see Consequences).

### Alternatives Considered

1. **Schema-per-Tenant (PostgreSQL schemas):**
   - Pro: Logical isolation within single DB
   - Con: PostgreSQL search_path overhead, schema migration complexity (N migrations)
   - Rejected: Operational burden outweighs benefits

2. **Microservices with Tenant Routing:**
   - Pro: Physical isolation, independent scaling
   - Con: Operational complexity, distributed transactions, higher latency
   - Rejected: Premature for MVP stage; can extract later if needed

3. **Hibernate Filters (automatic tenant_id injection):**
   - Pro: Transparent filtering (no explicit `tenant_id` in queries)
   - Con: "Magic" behavior increases debugging difficulty, missed filter = data leakage
   - Rejected: Explicit filtering more auditable and safer

---

## Consequences

### Positive Consequences

1. **Fast Tenant Provisioning:** New merchant onboarding = single INSERT, instant activation
2. **Cost Efficiency:** Single PostgreSQL instance supports 1000+ tenants vs. 1000 databases
3. **Operational Simplicity:** One backup, one migration, one connection pool
4. **Query Performance:** Indexed `tenant_id` columns enable efficient tenant-scoped queries
5. **Testability:** Test isolation via tenant ID switching (no database schema switching)

### Negative Consequences

1. **Data Leakage Risk:** Missing `tenant_id` filter = potential cross-tenant data exposure
   - **Mitigation:** Mandatory code review checklist item, automated test coverage
2. **Noisy Neighbor Problem:** One tenant's heavy queries can impact others
   - **Mitigation:** PostgreSQL connection pooling, query timeouts, future read replicas
3. **Tenant Deletion Complexity:** Deleting tenant data requires cascading deletes across all tables
   - **Mitigation:** `ON DELETE CASCADE` foreign keys, soft deletes (status = 'deleted')
4. **Limited Physical Isolation:** Shared database = shared failure domain
   - **Mitigation:** PostgreSQL high availability (multi-AZ), automated backups, point-in-time recovery

### Technical Debt Accepted

- **No RLS initially:** PostgreSQL Row-Level Security deferred to post-MVP for defense-in-depth (planned Q2 2026)
- **No tenant data encryption at rest:** Application-level encryption for sensitive fields (e.g., PII) deferred to compliance phase (planned Q3 2026)

### Risks Introduced

See Architecture Overview Section 6 (Risk Register):
- **RISK-001:** Tenant data leakage due to missing tenant_id filter (Medium likelihood, Critical impact)

**Mitigation Strategy:**
1. Automated tests: Every repository method tested with multi-tenant data
2. Code review checklist: "Does this query filter by tenant_id?"
3. Static analysis: SonarCloud custom rule flagging unfiltered queries (future)
4. Audit logging: All tenant context switches logged for forensics

### Impact on Future Decisions

- **ADR-002 (Delayed Jobs):** Background jobs must restore TenantContext from job payload
- **ADR-003 (Payment Gateway):** Stripe Connect accounts scoped to tenant_id
- **ADR-004 (Caching Strategy):** Cache keys must include tenant_id prefix to prevent cross-tenant cache pollution

---

## Implementation Checklist

- [ ] Create `tenants` and `custom_domains` tables (migration `001_create_tenants.sql`)
- [ ] Implement `TenantContext` utility class (as specified above)
- [ ] Implement `TenantResolutionFilter` (request filter, priority before authentication)
- [ ] Implement `TenantContextClearFilter` (response filter, cleanup ThreadLocal)
- [ ] Add `tenant_id` column to all domain tables (products, orders, customers, etc.)
- [ ] Update all repository queries to filter by `TenantContext.getCurrentTenantId()`
- [ ] Add `@PrePersist` hooks to entities for automatic tenant injection
- [ ] Write integration tests verifying tenant isolation (query tenant A data from tenant B context = empty result)
- [ ] Update OpenAPI spec with `tenant_id` claim in JWT security scheme
- [ ] Document TenantContext usage in `CONTRIBUTING.md` + onboarding guide

---

## References

- **Standards:** `docs/java-project-standards.adoc` (Sections 5, 7, 12)
- **Competitor Research:** `.codemachine/inputs/competitor-research.md` (Multi-tenancy comparison table)
- **Architecture Overview:** `docs/architecture_overview.md` (Section 4: Multi-Tenancy & Data Isolation)
- **Quarkus Filters:** [Jakarta REST ContainerRequestFilter](https://jakarta.ee/specifications/restful-ws/3.1/apidocs/jakarta.ws.rs/jakarta/ws/rs/container/containerrequestfilter)
- **PostgreSQL RLS:** [PostgreSQL Row-Level Security](https://www.postgresql.org/docs/17/ddl-rowsecurity.html)
- **Prior Art:**
  - Spree Commerce multi-store implementation: [GitHub - Spree Multi-Store](https://github.com/spree/spree/blob/master/guides/content/developer/core/multi_store.md)
  - Discourse multi-tenancy: [Discourse Developer Docs](https://meta.discourse.org/t/multisite-configuration-with-docker/14084)

---

**Document Version:** 1.0
**Last Updated:** 2026-01-02
**Maintained By:** Architecture Team
**Next Review:** Q2 2026 (after RLS implementation)
