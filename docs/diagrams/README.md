# Component Diagrams

## component_overview.puml

The main architectural component diagram showing the Village Storefront's layered modular monolith architecture.

### Rendering Instructions

**Using Docker:**
```bash
docker run --rm -v "$PWD":/work ghcr.io/plantuml/plantuml docs/diagrams/component_overview.puml -tpng
```

**Using local PlantUML:**
```bash
plantuml docs/diagrams/component_overview.puml -tpng
```

**Output:** `docs/diagrams/component_overview.png`

### Diagram Contents

The diagram illustrates:

1. **Presentation Layer** - Three client-facing interfaces:
   - Qute Storefront (server-side rendered)
   - Admin SPA (Vue.js 3 + Quinoa)
   - REST API (/api/v1/*)

2. **Tenant Access Gateway** - Cross-cutting tenant isolation:
   - TenantResolutionFilter
   - TenantContext (ThreadLocal propagation)
   - Tenant Cache (Caffeine)

3. **Service Layer - Logical Modules** (7 bounded contexts):
   - **Inner modules** (no outward dependencies):
     - Tenancy: Tenant, Store, CustomDomain
     - Identity: Auth, Sessions, Impersonation
   - **Middle modules**:
     - Catalog: Products, Variants, Inventory
     - Payments: Stripe integration, Refunds
   - **Outer modules** (depend on inner):
     - Orders: Cart, Checkout, Fulfillment
     - Consignment: Vendors, Commissions, Payouts
     - Notifications: Email, Webhooks, Jobs

4. **Infrastructure Layer**:
   - PostgreSQL (shared schema with tenant_id)
   - Background Job Queue

5. **External Systems**:
   - Stripe API
   - Email Provider
   - Object Storage (S3/R2)
   - Shipping APIs

### Architectural Anchors

The diagram references these documentation sections:

- `docs/architecture_overview.md#section-3-layered-modular-monolith`
- `docs/architecture_overview.md#section-4-tenant-isolation`
- `docs/adr/ADR-001-tenancy.md`

### Request Flows

Two annotated flows demonstrate system behavior:

1. **Customer Purchase Flow** - Storefront → Catalog → Checkout → Payments → Order
2. **Admin Vendor Management** - Admin SPA → Auth → Vendor Service → Webhooks
