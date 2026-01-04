# Village Storefront Architecture Overview

<!-- anchor: blueprint-foundation -->

## Document Purpose

This document provides the foundational architectural blueprint for Village Storefront, a multi-tenant SaaS ecommerce platform built on Quarkus. It synthesizes constraints from the VillageCompute Java Project Standards, competitive research insights, and strategic design decisions into a cohesive system architecture.

Sections 1–3 intentionally link constraints → standard technology kit → layered modular monolith breakdown so reviewers can trace business requirements (Section&nbsp;1) to platform capabilities (Section&nbsp;2) and structural implications (Section&nbsp;3) without leaving this document.

**Intended Audience:** Development team, technical leadership, DevOps engineers, and stakeholders requiring insight into architectural choices and their rationale.

**Document Status:** Living document, updated as architectural decisions evolve through ADRs.

---

<!-- anchor: section-1-vision-constraints -->

## 1. Vision & Constraints

### Business Vision

Village Storefront enables merchants to launch and manage professional online stores through a multi-tenant SaaS platform. Each merchant operates an isolated store accessible via subdomain (e.g., `storename.villagecompute.com`) or custom domain, with full support for:

- Physical products with variants (size, color, material)
- Digital products (downloads, licenses)
- Consignment vendor management (commission-based selling)
- Integrated payment processing via Stripe
- International commerce (multi-currency, multi-language)

### Key Differentiators (from Competitor Research)

Based on analysis of Spree Commerce, ConsignCloud, Gumroad, Squarespace, Shopify, and Medusa.js, our competitive advantages are:

1. **True Multi-Tenant SaaS Architecture:** Unlike Shopify (merchant-per-instance) or Squarespace (website builder + commerce), we provide isolated tenant contexts within a unified platform.
2. **Consignment Workflows:** First-class support for vendor management, commission tracking, and automated payouts (ConsignCloud's niche applied to general commerce).
3. **Modular Monolith Design:** Faster time-to-market than microservices, easier operational overhead than Medusa.js headless approach, while maintaining clear bounded contexts for future extraction.
4. **Spec-First API Design:** OpenAPI-driven contracts ensure API stability and enable generated client SDKs (similar to Shopify REST Admin API).

### Technical Constraints

Per `docs/java-project-standards.adoc`:

| Constraint | Requirement | Rationale |
|------------|-------------|-----------|
| **Java Version** | Java 21 (LTS) | Modern language features (records, pattern matching), long-term support |
| **Framework** | Quarkus 3.17+ | Fast startup, low memory, Kubernetes-native, GraalVM native compilation |
| **Build System** | Maven | Standard across VillageCompute projects |
| **Database** | PostgreSQL 17 | JSONB support for flexible schemas, proven at scale, PostGIS option for future geospatial features |
| **Schema Management** | MyBatis Migrations | Versioned, repeatable, environment-specific migrations |
| **Code Quality** | Spotless + JaCoCo (80% coverage) + SonarCloud | Enforced formatting, test coverage gates, security/quality checks |
| **Frontend** | Qute templates (storefront) + Vue.js 3 with Quinoa (admin) | Server-rendered storefronts for SEO/performance, SPA admin dashboard for rich interactions |
| **Deployment** | Native executable (GraalVM) on Kubernetes (k3s) | <100ms cold start, ~50-100MB containers, cost-efficient scaling |
| **Observability** | OpenTelemetry + Prometheus + Jaeger | Distributed tracing, metrics, health checks for Kubernetes probes |

### Non-Functional Requirements

- **Availability:** 99.9% uptime (measured monthly)
- **Performance:** <200ms p95 page load for storefronts, <500ms p95 API response
- **Scalability:** Support 1000+ concurrent tenants, 10K+ requests/sec at peak
- **Security:** OWASP Top 10 compliance, PCI DSS compliance for payment handling (Stripe offloads card storage)
- **Data Residency:** Single-region deployment initially (US-West), EU expansion planned for Q3 2026

---

<!-- anchor: section-2-standard-kit -->

## 2. Standard Technology Kit

The following dependencies from `pom.xml` form our baseline stack:

### Core Framework (Quarkus)
- **quarkus-arc:** CDI dependency injection
- **quarkus-rest + quarkus-rest-jackson:** REST endpoints with JSON serialization
- **quarkus-hibernate-orm-panache:** ORM with active record pattern
- **quarkus-jdbc-postgresql:** PostgreSQL connectivity
- **quarkus-hibernate-validator:** Bean validation (JSR 380)

### Presentation Layer
- **quarkus-qute:** Server-side templating for customer-facing storefront
- **quarkus-quinoa:** Vue.js 3 integration for `/admin/*` paths (admin dashboard)

### Persistence & Caching
- **PostgreSQL 17:** Primary data store
- **quarkus-cache (Caffeine):** In-memory caching for tenant metadata, product catalogs
- **MyBatis Migrations:** Schema versioning (`migrations/` module)

### Security & Authentication
- **quarkus-smallrye-jwt + quarkus-smallrye-jwt-build:** Stateless JWT tokens (access + refresh)
- Session logging to database for compliance/auditing
- **No Redis dependency:** Caffeine cache for transient data, JWT eliminates server-side session storage

### Integration & External Services
- **Stripe Java SDK (v29.5.0):** Payment processing, refunds, webhooks
- **AWS SDK for S3 (v2.20.162):** Product image storage (object storage)
- **quarkus-mailer:** Transactional email (order confirmations, password resets)

### Observability
- **quarkus-opentelemetry:** Distributed tracing (exports to Jaeger)
- **quarkus-micrometer-registry-prometheus:** Metrics (CPU, memory, request rates)
- **quarkus-smallrye-health:** Kubernetes liveness/readiness probes

### Background Processing
- **quarkus-scheduler:** Cron-like job scheduling (delayed job processor, email queue)
- Custom Delayed Job pattern (see ADR-002, future) for reliable async task execution

### API Documentation
- **quarkus-smallrye-openapi:** Auto-generated OpenAPI spec from `src/main/resources/openapi/api.yaml`
- Spec-first design: OpenAPI schema defines contracts, code implements interfaces

### Deployment & Build
- **quarkus-kubernetes:** Automatic K8s manifest generation
- **quarkus-container-image-jib:** Native container builds without Docker daemon
- **GraalVM native compilation:** Target deployment artifact for production

---

<!-- anchor: section-3-layered-architecture -->

## 3. Layered Modular Monolith Architecture

### Architectural Style: Layered Modular Monolith

Village Storefront adopts a **layered modular monolith** architecture, balancing:
- **Speed to market:** Single deployable unit, simpler CI/CD than microservices
- **Clear boundaries:** Modules organized by domain (catalog, orders, tenancy, payments)
- **Future flexibility:** Well-defined module contracts enable extraction to services if needed

This approach mirrors Shopify's original architecture (Rails monolith with clear domain boundaries) while avoiding premature microservices complexity seen in early-stage startups.

### Layer Breakdown

```
┌─────────────────────────────────────────────────────────────┐
│                     Presentation Layer                       │
│  • Qute Templates (storefront: /, /products, /cart, ...)   │
│  • Vue.js Admin SPA (/admin/*)                              │
│  • REST API Resources (/api/v1/*)                           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      Service Layer                          │
│  • Business logic encapsulation                             │
│  • Transaction boundaries (@Transactional)                  │
│  • Cross-module orchestration                               │
│  • Domain event publishing (future)                         │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                    Data Access Layer                        │
│  • JPA Entities (Panache active record)                     │
│  • Repositories (query encapsulation)                       │
│  • Named queries with constants (QUERY_* pattern)           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                      Integration Layer                      │
│  • Stripe payment gateway                                   │
│  • Email service (Mailer + delayed jobs)                    │
│  • Object storage (S3-compatible)                           │
│  • Shipping rate APIs (future: Shippo, EasyPost)            │
└─────────────────────────────────────────────────────────────┘
```

### Package Structure (Per Standards)

```
src/main/java/villagecompute/storefront/
├── api/
│   ├── rest/         # REST resources (@Path endpoints)
│   └── types/        # API DTOs (OpenAPI-generated + custom)
├── config/           # Quarkus configuration classes
├── data/
│   ├── models/       # JPA entities (Tenant, Store, Product, Order, etc.)
│   └── repositories/ # Data access layer (custom queries beyond Panache)
├── exceptions/       # Custom exceptions (all extend RuntimeException)
├── integration/      # External service integrations
│   ├── stripe/       # Stripe payment processing
│   ├── email/        # Email service with domain filtering
│   └── storage/      # S3 object storage
├── jobs/             # Background job handlers (delayed job pattern)
├── services/         # Business logic (TenantService, CatalogService, OrderService, etc.)
└── util/             # Shared utilities (TenantContext, JsonHelper, etc.)
```

### Module Boundaries (Logical)

While physically a monolith, modules enforce logical boundaries:

| Module | Responsibilities | Key Entities | External Dependencies |
|--------|------------------|--------------|----------------------|
| **Tenancy** | Tenant resolution, isolation, context propagation | Tenant, Store, CustomDomain | None |
| **Catalog** | Products, variants, categories, inventory | Product, Variant, Category, InventoryItem | Tenancy |
| **Orders** | Cart, checkout, order fulfillment, returns | Order, LineItem, Shipment, Return | Catalog, Payments, Tenancy |
| **Payments** | Payment processing, refunds, disputes | Payment, Refund, PaymentMethod | Stripe SDK, Orders |
| **Consignment** | Vendor management, commissions, payouts | Vendor, Commission, Payout | Catalog, Orders, Payments |
| **Identity** | User auth, sessions, impersonation | User, Session, Role, Permission | Tenancy |
| **Notifications** | Email, webhooks, background jobs | DelayedJob, EmailTemplate | Mailer, Jobs framework |

**Dependency Rule:** Inner modules (Tenancy, Identity) have no dependencies on outer modules (Orders, Consignment). Outer modules may depend on inner modules via service interfaces.

---

<!-- anchor: section-4-tenant-isolation -->

## 4. Multi-Tenancy & Data Isolation

**See ADR-001 for detailed tenancy design decisions.**

### Tenancy Model: Shared Database, Tenant-Scoped Queries

Every table includes a `tenant_id UUID` foreign key to the `tenants` table. All queries automatically filter by `TenantContext.getCurrentTenantId()` via:

1. **Request-level tenant resolution:** Custom filter extracts subdomain/domain from HTTP `Host` header → resolves Tenant → sets `TenantContext` (ThreadLocal)
2. **Repository-level enforcement:** All Panache queries use `find("tenant.id = ?1 AND ...", TenantContext.getCurrentTenantId())`
3. **Entity-level constraints:** JPA `@PrePersist` hooks inject `tenant_id` before insert

### Tenant Resolution Flow

```
HTTP Request (Host: storename.villagecompute.com)
    ↓
TenantResolutionFilter
    ↓ (extract subdomain "storename")
CustomDomainRepository.findByDomain() OR TenantRepository.findBySubdomain()
    ↓ (if found)
TenantContext.setCurrentTenant(tenant)
    ↓
Request proceeds to resource/service/repository
    ↓ (all queries auto-filtered by tenant_id)
Response
```

### Data Isolation Guarantees

- **Database-level:** Row-level security policies (PostgreSQL RLS) planned for defense-in-depth (ADR-001)
- **Application-level:** All queries explicitly filter by `tenant_id`
- **Cache-level:** Cache keys include `tenant_id` prefix (e.g., `tenant:123:product:456`)
- **Audit trail:** All tenant context switches logged (admin impersonation, API key usage)

### Scalability Considerations

- **Tenant sharding (future):** `tenant_id` supports hash-based sharding if single-DB becomes bottleneck (Q4 2026+)
- **Read replicas:** Tenant-scoped queries can route to read replicas via Quarkus datasource configuration
- **Cache warming:** Caffeine cache pre-loads tenant metadata on startup for top 100 tenants

---

<!-- anchor: section-5-api-contracts -->

## 5. API Design & Contracts

### Spec-First REST API (OpenAPI 3.0.3)

All REST endpoints defined in `src/main/resources/openapi/api.yaml` before implementation:

**Current Baseline:**
```yaml
openapi: 3.0.3
info:
  title: Village Storefront API
  version: 1.0.0
paths:
  /health:
    get:
      operationId: healthCheck
      responses:
        '200':
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/HealthResponse'
```

**Expansion Strategy:**
- Add tenant-aware endpoints under `/api/v1/*` (e.g., `/api/v1/products`, `/api/v1/orders`)
- Security schemes: `bearerAuth` (JWT) + `apiKeyAuth` (for server-to-server integrations)
- Shared schemas in `components/schemas` (ErrorResponse, PaginationMetadata, etc.)
- Webhook definitions in separate `webhooks.yaml` (Stripe inbound, merchant outbound)

### API Versioning

- **URL-based versioning:** `/api/v1`, `/api/v2` (breaking changes)
- **Header-based versioning (future):** `Accept: application/vnd.villagecompute.v2+json` for non-breaking additions
- **Deprecation policy:** 12-month notice for endpoint removal, `deprecated: true` in OpenAPI spec

### API Authentication & Authorization

1. **JWT Tokens (Customer + Admin Users):**
   - Access token (short-lived, 15 min) + Refresh token (long-lived, 30 days)
   - Claims: `sub` (user ID), `tenant_id`, `roles` (admin, customer, vendor)
   - Issued by `/api/v1/auth/login`, refreshed via `/api/v1/auth/refresh`

2. **API Keys (Server-to-Server):**
   - Long-lived tokens for headless integrations, webhooks
   - Scoped to tenant + permissions (read:products, write:orders)
   - Managed via admin dashboard (`/admin/settings/api-keys`)

3. **Tenant Context Injection:**
   - `tenant_id` claim in JWT → TenantContext populated by security filter
   - API key linked to Tenant record → same TenantContext mechanism

### Error Handling Contract

All errors follow RFC 7807 Problem Details:
```json
{
  "type": "https://docs.villagecompute.com/errors/validation-error",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Product SKU must be unique within tenant",
  "instance": "/api/v1/products",
  "errors": {
    "sku": ["SKU 'WIDGET-001' already exists"]
  }
}
```

---

<!-- anchor: section-6-safety-net -->

## 6. Safety Net: Risks, Decisions, & Governance

### Risk Register

| Risk ID | Description | Likelihood | Impact | Mitigation Strategy | Owner | Status |
|---------|-------------|------------|--------|---------------------|-------|--------|
| RISK-001 | Tenant data leakage due to missing tenant_id filter | Medium | Critical | Automated test suite verifying all queries include tenant filter; code review checklist | Dev Lead | Open |
| RISK-002 | Missing upstream plan alignment (no `01_Plan_Overview_and_Setup.md`) | High | Medium | Document assumptions from standards doc + competitor research; revisit when plan doc created | Architect | Open |
| RISK-003 | Native compilation compatibility issues with Stripe SDK | Low | Medium | Regular native build tests in CI; fallback to JVM mode if GraalVM reflection config fails | DevOps | Open |
| RISK-004 | PostgreSQL single point of failure | Medium | High | Multi-AZ PostgreSQL (RDS/CloudSQL), automated backups, read replicas for scaling | DevOps | Planned Q2 2026 |
| RISK-005 | Insufficient test coverage for payment flows | Medium | High | Dedicated Stripe test mode webhooks, 80% JaCoCo threshold enforced, manual QA for checkout flows | QA Lead | Open |
| RISK-006 | OpenAPI spec drift from implementation | Medium | Medium | CI job validates OpenAPI spec against runtime endpoints (Quarkus OpenAPI extension) | Dev Lead | Planned Q1 2026 |

### Decision Log Template

All significant architectural decisions documented in ADRs under `docs/adr/`. Use this template for new ADRs:

```markdown
# ADR-XXX: [Title]

**Status:** [Proposed | Accepted | Deprecated | Superseded by ADR-YYY]
**Date:** YYYY-MM-DD
**Deciders:** [Names/Roles]
**Consulted:** [Names/Roles]
**Informed:** [Names/Roles]

## Context

[What is the issue we're addressing? Include technical and business context.]

## Decision

[What are we doing? State the decision clearly.]

## Rationale

[Why this decision? Include:
- Alternatives considered
- Trade-offs analyzed
- Constraints from standards/requirements
- Evidence from competitor research or benchmarks]

## Consequences

[What becomes easier or harder?
- Positive consequences
- Negative consequences
- Risks introduced
- Technical debt accepted]

## References

- [Link to standards doc sections]
- [Link to competitor research]
- [Link to prototype/POC]
- [Link to related ADRs]
```

### Architectural Governance

- **ADR Submission Process:**
  1. Draft ADR in `docs/adr/ADR-XXX-title.md` (status: Proposed)
  2. Present in architecture review meeting (bi-weekly Fridays)
  3. Iterate based on feedback → status: Accepted
  4. Communicate decision in team standup + Slack #engineering channel

- **Review Cadence:**
  - **Weekly:** Tech lead reviews PRs for architectural alignment
  - **Bi-weekly:** Architecture review board (tech lead + 2 senior engineers) reviews ADRs
  - **Quarterly:** Retrospective on ADR effectiveness, architecture health metrics

- **Traceability:**
  - Each ADR links back to requirements (standards doc sections, user stories)
  - Each major module includes `ARCHITECTURE.md` linking to relevant ADRs
  - Code comments reference ADR IDs for non-obvious design choices (e.g., `// See ADR-001: tenant_id required`)

---

## 7. Next Steps & Roadmap Alignment

### Immediate Tasks (Iteration I1)

- ✅ Architecture overview documented (this file)
- ✅ ADR-001: Multi-tenancy & tenant isolation strategy
- ⏳ ERD diagram (planned: I1.T3)
- ⏳ Component diagram (planned: I1.T4)
- ⏳ CI/CD scaffolding with SonarCloud quality gate (planned: I1.T6)

### Iteration I2+ Dependencies

Future iterations depend on this foundational architecture:
- **I2:** Core domain models (Tenant, Store, Product schema based on ERD)
- **I3:** Tenant resolution filter + TenantContext implementation (based on ADR-001)
- **I4:** OpenAPI schema expansion (auth endpoints, catalog CRUD)
- **I5:** Payment integration (Stripe Connect for multi-vendor payouts)

### Monitoring Architecture Health

- **Metrics:**
  - SonarCloud quality gate pass rate (target: 100%)
  - Test coverage (target: ≥80% line + branch)
  - ADR coverage: % of major code modules with linked ADRs (target: ≥90%)
- **Dashboards:**
  - Architecture decision register (Notion/Confluence)
  - Risk register with aging/status (updated monthly)

---

## Appendix A: Cross-References

- **Standards:** `docs/java-project-standards.adoc` (Sections 2-8, 10-13)
- **Competitor Research:** `.codemachine/inputs/competitor-research.md` (Multi-tenancy, catalog features)
- **ADRs:**
  - ADR-001: Multi-Tenancy & Tenant Isolation Strategy
  - ADR-002: (Planned) Delayed Job Architecture
  - ADR-003: (Planned) Payment Gateway Abstraction Layer
- **OpenAPI Spec:** `src/main/resources/openapi/api.yaml`
- **POM Dependencies:** `pom.xml` (lines 35-211)

---

## Appendix B: Glossary

- **ADR:** Architecture Decision Record
- **Caffeine:** High-performance in-memory cache library (used by quarkus-cache)
- **GraalVM:** Ahead-of-time compiler for Java → native executables
- **JaCoCo:** Java Code Coverage library
- **MyBatis Migrations:** Database schema versioning tool
- **Panache:** Quarkus ORM layer (active record pattern over Hibernate)
- **Qute:** Quarkus templating engine (server-side HTML rendering)
- **Quinoa:** Quarkus extension for integrating Node.js frontend builds (Vite/Vue.js)
- **SonarCloud:** Cloud-based code quality and security analysis platform
- **Spotless:** Code formatting Maven plugin (Eclipse formatter rules)
- **Tenant:** A merchant's store instance within the multi-tenant platform
- **TenantContext:** ThreadLocal holder for current tenant ID during request processing

---

**Document Version:** 1.0
**Last Updated:** 2026-01-02
**Maintained By:** Architecture Team
**Review Frequency:** Quarterly or after major ADR changes
