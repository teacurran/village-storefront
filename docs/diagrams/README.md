# Architecture Diagrams

This directory contains architectural diagrams for the Village Storefront platform, including C4 model diagrams and detailed component views.

## Overview

The diagrams follow the [C4 model](https://c4model.com/) for visualizing software architecture:
- **Level 1 (System Context)**: Shows the system and its external dependencies
- **Level 2 (Container)**: Shows major runtime containers and their interactions
- **Level 3 (Component)**: Shows internal modules within containers
- **Level 4 (Code)**: Shows class-level details (future)

All diagrams are authored in PlantUML and can be rendered to PNG format.

---

## Data Model Diagram

### datamodel_erd.mmd (Mermaid ERD)

Comprehensive entity-relationship diagram capturing the multi-tenant data model with RLS annotations.

**Rendering:**
```bash
# Using Mermaid CLI (mmdc)
npm install -g @mermaid-js/mermaid-cli
mmdc -i docs/diagrams/datamodel_erd.mmd -o docs/diagrams/datamodel_erd.png

# Or using Docker
docker run --rm -v "$PWD":/data minlag/mermaid-cli -i /data/docs/diagrams/datamodel_erd.mmd -o /data/docs/diagrams/datamodel_erd.png
```

**Output:** `docs/diagrams/datamodel_erd.png`

**Contents:**
- **Tenancy Module**: `tenants`, `custom_domains`
- **Identity Module**: `store_users`, `customers`, `session_log`, `api_keys`
- **Catalog Module**: `categories`, `products`, `variants`, `inventory_locations`, `inventory_levels`
- **Cart/Order/Payment Module**: `carts`, `orders`, `order_line_items`, `payment_intents`, `refunds`, `shipments`, `return_authorizations`
- **Consignment Module**: `consignors`, `consignment_items`, `consignor_payouts`
- **Loyalty Module**: `loyalty_ledger_entries`
- **Gift Cards & Store Credit Module**: `gift_cards`, `store_credits`
- **Media Module**: `media_assets`
- **Cross-Cutting Tables**: `feature_flags`, `audit_events`, `background_jobs`, `webhook_events`, `domain_events`, `platform_commands`, `rate_limit_buckets`

**RLS Annotations:**
- All tenant-scoped tables marked with `[RLS]` indicate Row-Level Security policy requirement
- Tables marked with `[PARTITIONED]` use declarative partitioning (e.g., `session_log`, `audit_events`, `background_jobs`, `domain_events`)

**Documentation:**
- **Narrative Guide**: [`datamodel_tenancy_narrative.md`](./datamodel_tenancy_narrative.md) - Comprehensive explanation of tenancy columns, RLS policy templates, indexing strategy, partitioning, and archival
- **Architecture Reference**: `docs/architecture_overview.md` Section 5 (Data Model)
- **Tenancy ADR**: `docs/adr/ADR-001-tenancy.md`
- **Quality Suite**: `docs/quality/tenant_isolation.md`

**PlantUML Version:**
A parallel PlantUML ERD is maintained at `datamodel_erd.puml` for teams preferring PlantUML tooling. Both diagrams represent the same schema with equivalent entity coverage.

---

## C4 Architecture Diagrams

### system-context.puml (C4 Level 1)

Shows Village Storefront as a single system interacting with external actors and services.

**Rendering:**
```bash
docker run --rm -v "$PWD":/work ghcr.io/plantuml/plantuml docs/diagrams/system-context.puml -tpng
# Or: plantuml docs/diagrams/system-context.puml -tpng
```

**Output:** `docs/diagrams/system-context.png`

**Contents:**
- **Personas**: Merchants, customers, staff, consignors, platform admins, headless partners, auditors, POS clerks
- **External Systems**: Stripe, Cloudflare R2, shipping carriers (USPS/UPS/FedEx), DNS/SSL automation (cert-manager), FFmpeg workers, OAuth providers, tax engines, address validation, SMTP relay, analytics
- **Integration Patterns**: HTTPS/REST, webhooks, job queues, OAuth 2.0

**Documentation:** See [`c4-architecture-diagrams.md`](./c4-architecture-diagrams.md#system-context-diagram-c4-level-1) for detailed commentary.

---

### container.puml (C4 Level 2)

Shows major runtime containers (deployable units) and their communication patterns.

**Rendering:**
```bash
docker run --rm -v "$PWD":/work ghcr.io/plantuml/plantuml docs/diagrams/container.puml -tpng
# Or: plantuml docs/diagrams/container.puml -tpng
```

**Output:** `docs/diagrams/container.png`

**Containers:**
- **Storefront Web App** (Qute templates + Tailwind CSS + PrimeUI)
- **Admin Dashboard** (Vue.js 3 + Vite + TypeScript + PrimeVue)
- **POS Interface** (Vue.js 3 with offline capability)
- **Platform Console** (Vue.js 3 for SaaS operators)
- **REST API** (Quarkus RESTEasy Reactive, OpenAPI spec-first)
- **Quarkus Application** (Modular monolith, Java 21 + Quarkus 3.17+)
- **PostgreSQL Database** (PostgreSQL 17 with RLS policies)
- **In-Memory Cache** (Caffeine, pod-local)
- **Background Job Worker** (Quarkus Scheduler + DelayedJob pattern)
- **Media Processing Worker** (FFmpeg + Thumbnailator)

**Communication:**
- Synchronous: HTTPS/REST, JDBC, CDI (in-process)
- Asynchronous: Database-backed job queue
- Webhooks: Inbound from Stripe, carriers

**Documentation:** See [`c4-architecture-diagrams.md`](./c4-architecture-diagrams.md#container-diagram-c4-level-2) for deployment topology.

---

### component.puml (C4 Level 3)

Details internal modules within the Quarkus application container.

**Rendering:**
```bash
docker run --rm -v "$PWD":/work ghcr.io/plantuml/plantuml docs/diagrams/component.puml -tpng
# Or: plantuml docs/diagrams/component.puml -tpng
```

**Output:** `docs/diagrams/component.png`

**Component Domains:**
1. **Tenant Access Gateway** (Cross-cutting): TenantFilter, TenantContext, Feature Flags
2. **Identity & Session**: AuthService, SessionLogger, ImpersonationController
3. **Catalog & Inventory**: CatalogService, InventoryService, SearchIndexer
4. **Consignment**: VendorService, CommissionCalculator, PayoutOrchestrator
5. **Checkout & Orders**: CartService, CheckoutOrchestrator, OrderService, ShipmentService, ReturnService
6. **Payment Integration**: PaymentService, StripeAdapter, WebhookProcessor, LedgerService
7. **Loyalty & Rewards**: LoyaltyService, RewardRedemption
8. **POS & Offline**: POS API, OfflineQueueReconciler
9. **Media Pipeline**: MediaUploadController, MediaPipelineCoordinator
10. **Reporting & Analytics**: ReportingProjections, DashboardMetricsAPI
11. **Platform Admin**: TenantLifecycleService, PlatformMetrics
12. **Integration Adapters**: CarrierAdapters, AddressValidator, TaxAdapter, EmailAdapter
13. **Infrastructure Primitives**: JobScheduler, CacheManager, DomainEventBus

**Module Dependency Rules:**
- Inner modules (Tenancy, Identity, Catalog, Payments) have no outward dependencies
- Outer modules (Orders, Consignment) depend on inner modules via CDI interfaces
- Cross-cutting concerns observe domain events without tight coupling

**Documentation:** See [`c4-architecture-diagrams.md`](./c4-architecture-diagrams.md#component-diagram-c4-level-3) for detailed component responsibilities.

---

## Legacy Component Diagram

### component_overview.puml

**Note:** This diagram predates the C4 diagrams above. The new `component.puml` (C4 Level 3) supersedes this file but is retained for historical reference. Future updates should focus on the C4 diagrams.

**Rendering:**
```bash
docker run --rm -v "$PWD":/work ghcr.io/plantuml/plantuml docs/diagrams/component_overview.puml -tpng
```

**Output:** `docs/diagrams/component_overview.png`

**Contents:**
- Presentation Layer (Qute, Admin SPA, REST API)
- Tenant Access Gateway
- Service Layer Modules (7 bounded contexts)
- Infrastructure Layer (PostgreSQL, Job Queue)
- External Systems (Stripe, Email, Object Storage, Shipping)
- Annotated request flows for customer purchase and admin vendor management

**Architectural Anchors:**
- `docs/architecture_overview.md#section-3-layered-modular-monolith`
- `docs/architecture_overview.md#section-4-tenant-isolation`
- `docs/adr/ADR-001-tenancy.md`

---

## Rendering All Diagrams

**PlantUML Diagrams (Using Docker - Recommended):**
```bash
# From repository root
docker run --rm -v "$PWD":/work ghcr.io/plantuml/plantuml \
  docs/diagrams/system-context.puml \
  docs/diagrams/container.puml \
  docs/diagrams/component.puml \
  docs/diagrams/component_overview.puml \
  docs/diagrams/datamodel_erd.puml \
  -tpng
```

**PlantUML Diagrams (Using Local PlantUML):**
```bash
# Requires PlantUML JAR and Graphviz installed
plantuml docs/diagrams/*.puml -tpng
```

**Mermaid ERD (Using Mermaid CLI):**
```bash
# Render Mermaid ERD
mmdc -i docs/diagrams/datamodel_erd.mmd -o docs/diagrams/datamodel_erd.png

# Or using Docker
docker run --rm -v "$PWD":/data minlag/mermaid-cli \
  -i /data/docs/diagrams/datamodel_erd.mmd \
  -o /data/docs/diagrams/datamodel_erd.png
```

**CI Integration:**
GitHub Actions workflow (`.github/workflows/docs.yml`) automatically regenerates diagrams on commits to `docs/diagrams/*.puml` files.

---

## Documentation

For detailed commentary on diagram intent, module responsibilities, and architectural anchors, see:

- **[`c4-architecture-diagrams.md`](./c4-architecture-diagrams.md)**: Comprehensive guide to C4 diagrams with module responsibilities, integration patterns, and deployment mapping
- **[`../architecture_overview.md`](../architecture_overview.md)**: High-level architectural blueprint
- **[`../adr/ADR-001-tenancy.md`](../adr/ADR-001-tenancy.md)**: Multi-tenancy design decisions

---

## Diagram Update Guidelines

When to update diagrams:

1. **Data Model ERD** (`datamodel_erd.mmd` & `datamodel_erd.puml`): Adding/removing tables, changing relationships, updating RLS annotations, modifying partition strategies
2. **System Context**: Adding new external integrations, personas, or removing deprecated services
3. **Container**: Introducing new deployment containers (e.g., separate search service), changing container communication patterns
4. **Component**: Adding new bounded-context modules, refactoring module boundaries, introducing new adapters
5. **component_overview.puml**: (Legacy) Should not be updated; migrate changes to `component.puml` instead

**Review Process:**
- Diagram updates require Architecture Review Board approval (bi-weekly sessions)
- C4 diagram changes must be accompanied by updates to `c4-architecture-diagrams.md` commentary
- ERD changes must be accompanied by updates to `datamodel_tenancy_narrative.md` commentary
- Rendered PNG outputs should be committed alongside `.puml`/`.mmd` source changes for documentation portability
- ERD changes require review with Identity Team (session_log, RLS policies) and Reporting Team (partitioning, archival)
