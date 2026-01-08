# C4 Architecture Diagrams

## Overview

This document provides commentary and context for the C4 model architecture diagrams of Village Storefront. The diagrams follow the C4 (Context, Containers, Components, Code) model developed by Simon Brown, using PlantUML with the C4-PlantUML library for visualization.

**Document Purpose:** Link architectural diagrams to implementation concerns, deployment topology, and integration patterns so development teams can trace system structure from high-level context through detailed component boundaries.

**Related Documentation:**
- [`docs/architecture_overview.md`](../architecture_overview.md) - Architectural blueprint and design rationale
- [`docs/adr/ADR-001-tenancy.md`](../adr/ADR-001-tenancy.md) - Multi-tenancy design decisions
- [`CLAUDE.md`](../../CLAUDE.md) - Project standards and build instructions

---

## Diagram Hierarchy

The three diagrams form a hierarchical view of the system:

```
System Context (L1)
  └─> Container (L2)
       └─> Component (L3)
```

Each level zooms into progressively more detail:

1. **System Context (L1)**: Shows Village Storefront as a single system interacting with external actors and services
2. **Container (L2)**: Reveals the major runtime containers (Quarkus app, PostgreSQL, worker pods) and their interactions
3. **Component (L3)**: Details the internal modules within the Quarkus application container

---

## System Context Diagram (C4 Level 1)

**File:** [`system-context.puml`](./system-context.puml)

### Intent

Provides a 30,000-foot view of Village Storefront's external dependencies and user personas. This diagram answers:
- **Who uses the system?** (personas)
- **What external services does it integrate with?** (third-party APIs, infrastructure)
- **What are the primary interaction patterns?** (HTTPS/JWT, webhooks, async processing)

### Actors & Personas

The diagram includes all required personas per acceptance criteria:

| Persona | Role | Primary Interface | Authentication |
|---------|------|-------------------|----------------|
| **Merchant** | Store owner | Admin Dashboard (Vue.js SPA) | JWT (access + refresh tokens) |
| **Store Staff** | Employee | Admin Dashboard | JWT with limited permissions |
| **Customer** | Shopper | Storefront (Qute templates) | Optional JWT for account features |
| **Consignment Vendor** | Third-party seller | Vendor Portal (subset of Admin) | JWT with vendor role scope |
| **Platform Admin** | SaaS operator | Platform Console | JWT with elevated permissions, impersonation capability |
| **Headless Partner** | External system | REST API (`/api/v1/*`) | Long-lived API keys with OAuth scopes |
| **Auditor** | Compliance reviewer | Read-only admin access | JWT with read-only audit scope |
| **POS Clerk** | In-store staff | POS interface (offline-capable) | JWT + hardware bridge |

### External Systems

All integrations specified in acceptance criteria are included:

#### Payment Processing
- **Stripe API**: Payment intents, refunds, disputes, Stripe Connect for marketplace payouts
  - **Integration Pattern**: Stripe Java SDK (v29.5.0), webhook validation with signature verification
  - **Data Flow**: Bidirectional (API calls + inbound webhooks)

#### Object Storage
- **Cloudflare R2**: Product images, digital downloads, processed media variants
  - **Integration Pattern**: AWS S3 SDK (v2.20.162) with S3-compatible endpoints
  - **Data Flow**: Quarkus app uploads originals/variants, generates signed URLs for customer downloads

#### Shipping & Address Validation
- **Shipping Carriers**: USPS, UPS, FedEx APIs for rate calculation and label generation
  - **Integration Pattern**: Unified adapter layer with retry/fallback policies
  - **Data Flow**: REST API calls during checkout + tracking webhook ingestion

- **Address Validation**: USPS/SmartyStreets for shipping address normalization
  - **Integration Pattern**: REST API calls with caching to reduce costs
  - **Data Flow**: Synchronous validation during checkout address entry

#### Infrastructure & Automation
- **DNS & SSL Automation**: cert-manager for ACME certificate issuance, Cloudflare DNS API for custom domain validation
  - **Integration Pattern**: Kubernetes-native cert-manager CRDs, DNS01 challenge automation
  - **Data Flow**: Automated certificate renewal, DNS record creation for tenant custom domains

- **FFmpeg Worker**: Media processing for video transcoding, thumbnail generation
  - **Integration Pattern**: Separate worker pod triggered via database-backed job queue
  - **Data Flow**: Job payloads include R2 object keys, worker processes and uploads variants

#### Identity & Tax
- **OAuth Providers**: Google, Facebook, Apple for social login
  - **Integration Pattern**: OAuth 2.0 authorization code flow with PKCE
  - **Data Flow**: Redirects to provider, callback with authorization code, token exchange

- **Tax Calculation Service**: Avalara/TaxJar for sales tax rates and nexus determination
  - **Integration Pattern**: REST API calls with address + line items during checkout
  - **Data Flow**: Synchronous tax calculation, cached by ZIP/postal code

#### Notifications & Analytics
- **Transactional Email**: SMTP relay (Mailpit for dev, SendGrid for prod)
  - **Integration Pattern**: Quarkus Mailer with domain filtering, background job queue for async dispatch
  - **Data Flow**: Email templates rendered by Qute, sent via SMTP, bounce/complaint callbacks logged

- **Analytics Destination**: Google Analytics, Mixpanel for storefront telemetry
  - **Integration Pattern**: Client-side JavaScript SDK on storefront pages
  - **Data Flow**: Browser-initiated events, no server-side PII transmission

### Architectural Concerns

**Multi-Tenancy:**
- All requests flow through Tenant Resolution Filter (see Container diagram)
- Tenant identified by `Host` header (subdomain or custom domain)
- External systems accessed with tenant-scoped credentials (Stripe Connected Accounts, per-tenant API keys)

**Security:**
- JWT tokens for authenticated requests (access: 15min TTL, refresh: 30 days)
- API keys for server-to-server integrations with OAuth scopes
- Webhook signature validation for all inbound webhooks (Stripe, carriers)
- Impersonation audit trail for platform admin actions

**Scalability:**
- Kubernetes horizontal pod autoscaling for Quarkus application pods
- Asynchronous processing via background job workers for emails, media, payouts
- CDN-backed object storage for media delivery
- Read replicas for reporting queries (future)

---

## Container Diagram (C4 Level 2)

**File:** [`container.puml`](./container.puml)

### Intent

Shows the major runtime containers (deployable units) and their interactions. This diagram answers:
- **What are the deployment units?** (Quarkus app, PostgreSQL, worker pods, SPAs)
- **How do they communicate?** (HTTPS, JDBC, job queues, CDI)
- **What technology stacks power each container?** (Java 21, Vue.js 3, PostgreSQL 17)

### Container Catalog

#### Presentation Containers

**Storefront Web App**
- **Technology**: Qute templates + Tailwind CSS + PrimeUI widgets
- **Responsibility**: Server-side rendering of customer-facing pages (product listings, cart, checkout)
- **Deployment**: Served by Quarkus application container under all paths except `/admin/*`
- **Integration**: Internal CDI calls to Quarkus application services

**Admin Dashboard**
- **Technology**: Vue.js 3 + Vite + TypeScript + PrimeVue + Pinia stores
- **Responsibility**: Merchant/staff SPA for product management, order processing, settings
- **Deployment**: Compiled via Quinoa plugin, served under `/admin/*` paths
- **Integration**: REST API calls to `/api/v1/*` endpoints with JWT authentication

**POS Interface**
- **Technology**: Vue.js 3 + TypeScript + Stripe Terminal SDK
- **Responsibility**: Browser-based point-of-sale for in-store transactions, offline queue management
- **Deployment**: Served under `/pos/*` paths with service worker for offline capability
- **Integration**: REST API calls to `/api/v1/pos/*` endpoints, hardware bridges for receipt printers

**Platform Console**
- **Technology**: Vue.js 3 + TypeScript + PrimeVue
- **Responsibility**: SaaS operator dashboard for tenant lifecycle, impersonation, system health monitoring
- **Deployment**: Served under `/platform/*` paths (authenticated with elevated JWT claims)
- **Integration**: REST API calls to `/api/v1/platform/*` endpoints

**REST API**
- **Technology**: Quarkus RESTEasy Reactive with OpenAPI 3.0 spec
- **Responsibility**: OpenAPI-documented endpoints for storefront, admin, headless partners, webhooks
- **Deployment**: Hosted within Quarkus application container, routes to service layer modules
- **Integration**: CDI-wired services, Panache repositories

#### Core Application Container

**Quarkus Application**
- **Technology**: Java 21 + Quarkus 3.17+ (RESTEasy Reactive, Panache, Scheduler, Kubernetes, Mailer)
- **Responsibility**: Modular monolith hosting tenant gateway, bounded-context modules, background schedulers
- **Deployment**: GraalVM native executable in Kubernetes Deployment (2+ replicas), horizontal pod autoscaling
- **Module Boundaries**: See Component diagram for detailed breakdown
- **Observability**: OpenTelemetry traces to Jaeger, Prometheus metrics scraping, structured JSON logs

#### Data Containers

**PostgreSQL Database**
- **Technology**: PostgreSQL 17 with row-level security (RLS) policies
- **Responsibility**: Persistent storage for all tenant-scoped data
- **Schema Design**: Shared database with `tenant_id UUID` foreign key on all tables, partitioned `sessions` and `audit_events` tables
- **Deployment**: Kubernetes StatefulSet with persistent volume claims, automated backups (Velero), read replicas for reporting
- **Security**: pgcrypto for sensitive field encryption, RLS policies for defense-in-depth

**In-Memory Cache**
- **Technology**: Caffeine (in-process cache library)
- **Responsibility**: Pod-local caching for tenant metadata, product fragments, rate-limit counters, feature flags
- **Cache Strategy**: Tenant-aware keys (`tenant:123:product:456`), TTL-based expiration, no distributed cache dependency
- **Invalidation**: CDI events for cross-module cache invalidation

#### Background Processing Containers

**Background Job Worker**
- **Technology**: Quarkus Scheduler + DelayedJob pattern (database-backed queue)
- **Responsibility**: Email dispatch, payout processing, report generation, certificate renewals
- **Priority Queues**: CRITICAL, HIGH, DEFAULT, LOW, BULK with separate thread pools
- **Deployment**: Separate Kubernetes Deployment for workload isolation, exponential backoff retries, dead letter queue

**Media Processing Worker**
- **Technology**: FFmpeg + Thumbnailator (Java image library)
- **Responsibility**: CPU-intensive video transcoding, image resizing, WebP generation
- **Deployment**: Separate Kubernetes Deployment with higher CPU limits, triggered via job queue
- **Integration**: Fetches originals from Cloudflare R2, uploads processed variants, updates `media_assets` table

### Communication Patterns

**Synchronous (HTTPS/REST):**
- Customer → Storefront → Quarkus App (server-side rendering)
- Merchant → Admin SPA → REST API → Quarkus App (JSON payloads)
- Quarkus App → External APIs (Stripe, carriers, tax, address validation)

**Asynchronous (Job Queue):**
- Quarkus App → Background Job Worker (email, payouts, reports)
- Background Job Worker → Media Worker (video transcoding)
- Database-backed DelayedJob table with polling schedulers

**Webhooks (Inbound):**
- Stripe → REST API `/webhooks/stripe` (payment events, disputes, payouts)
- Carriers → REST API `/webhooks/tracking` (shipment status updates)

**Internal (CDI):**
- REST API → Service Layer Modules (in-process CDI injection)
- Service Layer → Repositories → PostgreSQL (JDBC via Panache)

### Deployment Topology

```
Kubernetes Cluster (k3s)
├── Quarkus Application (Deployment, 2+ replicas)
│   ├── Autoscaling: CPU/memory thresholds
│   ├── Probes: /health/live, /health/ready
│   └── Resources: 512Mi-2Gi memory, 250m-1000m CPU
├── Background Job Worker (Deployment, 1-3 replicas)
│   ├── Resources: 256Mi-1Gi memory, 250m-500m CPU
│   └── Separate deployment for workload isolation
├── Media Worker (Deployment, 1-5 replicas)
│   ├── Resources: 1Gi-4Gi memory, 1000m-4000m CPU
│   └── High CPU for FFmpeg processing
├── PostgreSQL (StatefulSet, 1 primary + N replicas)
│   ├── Persistent Volume Claims (100Gi+ storage)
│   ├── Automated backups via Velero/pgBackRest
│   └── Read replicas for reporting queries
└── Ingress Controller
    ├── cert-manager for ACME certificates
    └── Cloudflare DNS for custom domain routing
```

---

## Component Diagram (C4 Level 3)

**File:** [`component.puml`](./component.puml)

### Intent

Details the internal modules within the Quarkus application container. This diagram answers:
- **What are the bounded contexts?** (catalog, orders, consignment, loyalty, identity, etc.)
- **How do modules interact?** (CDI interfaces, domain events, service orchestration)
- **What are the integration points?** (adapters for external services)

### Component Catalog by Domain

#### Tenant Access Gateway (Cross-Cutting)

**Tenant Resolution Filter**
- **Responsibility**: Extracts `Host` header, resolves subdomain/custom domain to Tenant entity
- **Implementation**: JAX-RS `ContainerRequestFilter` with priority 1000 (runs before security filters)
- **Output**: Populates `TenantContext` ThreadLocal for request scope
- **Error Handling**: Returns 404 for unknown domains, 503 during tenant resolution failures

**TenantContext**
- **Responsibility**: Request-scoped holder for `tenant_id` propagated to all layers
- **Implementation**: ThreadLocal with `TenantContextFilter` cleanup on request completion
- **Usage**: All repositories inject `TenantContext.getCurrentTenantId()` into queries

**Feature Flag Service**
- **Responsibility**: Loads tenant-specific feature flags from database + cache, evaluates toggles
- **Implementation**: CDI bean with `@CacheResult` for per-tenant flag maps
- **Invalidation**: CDI events from admin flag updates trigger cache invalidation

#### Identity & Session Module

**Authentication Service**
- **Responsibility**: JWT issuance/validation, refresh token rotation, OAuth client coordination
- **Key Methods**: `login(username, password)`, `refresh(refreshToken)`, `validateJWT(token)`
- **Security**: Argon2 password hashing, short-lived access tokens (15min), refresh token rotation on use

**Session Logger**
- **Responsibility**: Writes login/logout/impersonation events to `session_log` table for compliance
- **Data Captured**: User ID, tenant ID, IP address, user agent, action type, timestamp
- **Retention**: 2 years per GDPR/CCPA requirements, partitioned by month

**Impersonation Controller**
- **Responsibility**: Platform admin impersonation workflows with audit trail
- **Constraints**: Requires `platform:admin` role, logs original + impersonated user IDs
- **Session Marking**: Impersonated sessions include `impersonated_by` claim in JWT

#### Catalog & Inventory Module

**Catalog Service**
- **Responsibility**: Product/variant CRUD, category hierarchies, collections, search event publishing
- **Entity Ownership**: `Product`, `Variant`, `Category`, `Collection`, `ProductImage`
- **Business Rules**: SKU uniqueness per tenant, variant validation (option combos), slug generation

**Inventory Service**
- **Responsibility**: Multi-location stock tracking, adjustments, transfers, low-stock alerts
- **Entity Ownership**: `InventoryLocation`, `InventoryLevel`, `StockAdjustment`, `StockTransfer`
- **Concurrency**: Optimistic locking via `@Version` on `InventoryLevel`, reserved vs available quantities

**Search Indexer**
- **Responsibility**: Consumes product change events, updates search projections (future Elasticsearch)
- **Implementation**: CDI `@Observes` listener for `ProductCreated`, `ProductUpdated`, `ProductDeleted` events
- **Future**: Async push to Elasticsearch/Algolia for full-text search

#### Consignment Module

**Vendor Service**
- **Responsibility**: Consignor onboarding, portal access, commission schedule management
- **Entity Ownership**: `Consignor`, `ConsignmentItem`, `CommissionSchedule`
- **Workflows**: Vendor application approval, item intake batching, payout coordination

**Commission Calculator**
- **Responsibility**: Calculates splits per order line item based on vendor rules
- **Algorithms**: Percentage-based, tiered (volume discounts), flat fee per item
- **Invocation**: Called during order completion, payout batch generation

**Payout Orchestrator**
- **Responsibility**: Batches vendor payouts via Stripe Connect, records ledger entries
- **Scheduling**: Weekly batch jobs (configurable per tenant), minimum payout threshold ($25 default)
- **Failure Handling**: Retry failed transfers, notify vendors via email, manual reconciliation UI

#### Checkout & Order Module

**Cart Service**
- **Responsibility**: Persistent cart management, line item validation, promo code application
- **Entity Ownership**: `Cart`, `CartLineItem`, `PromoCode`
- **Persistence**: Database-backed carts (not session-only) for abandoned cart recovery

**Checkout Orchestrator**
- **Responsibility**: Coordinates address validation, shipping, tax, payment, order creation
- **Transaction Boundary**: Single database transaction for atomicity (rollback on any step failure)
- **Idempotency**: Cart token prevents duplicate order submission
- **Steps** (see diagram note):
  1. Validate cart + inventory availability
  2. Validate shipping address (address validation service)
  3. Fetch shipping rates (carrier adapters)
  4. Calculate sales tax (tax adapter)
  5. Apply loyalty discounts (loyalty service)
  6. Create payment intent (payment service)
  7. Reserve inventory (inventory service)
  8. Create order + shipment records (order service)
  9. Send confirmation email (async job)

**Order Service**
- **Responsibility**: Order lifecycle management, fulfillment status, tracking updates
- **Entity Ownership**: `Order`, `OrderLineItem`, `FulfillmentStatus`
- **State Machine**: Pending → Processing → Shipped → Delivered / Cancelled / Returned

**Shipment Service**
- **Responsibility**: Carrier rate fetching, label generation, tracking webhook ingestion
- **Entity Ownership**: `Shipment`, `ShippingLabel`, `TrackingEvent`
- **Carrier Integration**: Unified adapter layer for USPS/UPS/FedEx with fallback logic

**Return Service**
- **Responsibility**: RMA creation, restocking, refund coordination
- **Entity Ownership**: `ReturnAuthorization`, `ReturnLineItem`, `RestockInstruction`
- **Workflows**: Customer-initiated returns (via storefront), merchant-initiated returns (via admin)

#### Payment Integration Module

**Payment Service**
- **Responsibility**: Abstracts `PaymentProvider` interface, orchestrates intents, captures, refunds
- **Interface**: `PaymentProvider` with methods `createIntent`, `capturePayment`, `refundPayment`
- **Providers**: Stripe (primary), extensible for PayPal/Square future support

**Stripe Adapter**
- **Responsibility**: Implements `PaymentProvider` + `MarketplaceProvider` via Stripe Java SDK
- **Features**: Payment intents, Stripe Connect onboarding, marketplace splits, webhook handling
- **Idempotency**: Stripe idempotency keys for safe retries, webhook deduplication via `event_id`

**Webhook Processor**
- **Responsibility**: Validates Stripe webhook signatures, dispatches to domain event handlers
- **Security**: HMAC signature verification with rolling secret support
- **Event Types**: `payment_intent.succeeded`, `charge.dispute.created`, `transfer.paid`, etc.

**Ledger Service**
- **Responsibility**: Double-entry accounting for payments, refunds, payouts, store credit
- **Entity Ownership**: `LedgerEntry`, `LedgerAccount` (per tenant + per consignor)
- **Immutability**: Append-only ledger, corrections via reversing entries

#### Loyalty & Rewards Module

**Loyalty Service**
- **Responsibility**: Points accumulation, tier upgrades, expiration rules
- **Entity Ownership**: `LoyaltyAccount`, `LoyaltyTransaction`, `LoyaltyTier`
- **Rules Engine**: Configurable points-per-dollar, bonus events, expiration policies

**Reward Redemption Engine**
- **Responsibility**: Applies loyalty discounts during checkout, validates balances
- **Integration**: Called by Checkout Orchestrator before payment step
- **Constraints**: Minimum order value for redemption, maximum discount percentage

#### POS & Offline Module

**POS API**
- **Responsibility**: Browser-based POS endpoints, hardware integration bridges (Stripe Terminal)
- **Endpoints**: `/api/v1/pos/sales`, `/api/v1/pos/hardware/printers`, `/api/v1/pos/sync`
- **Hardware**: Stripe Terminal SDK for card-present transactions, ESC/POS printer commands

**Offline Queue Reconciler**
- **Responsibility**: Syncs queued sales from disconnected POS sessions
- **Implementation**: Scheduled job polls `pos_offline_queue` table, validates + processes queued transactions
- **Conflict Resolution**: Inventory conflicts resolved via merchant review queue

#### Media Pipeline Module

**Media Upload Controller**
- **Responsibility**: Pre-signed upload URL generation, enforces size/type limits
- **Limits**: 10MB for images, 500MB for videos, whitelisted MIME types
- **Flow**: Client requests upload URL → controller generates signed R2 URL → client uploads directly → webhook notifies completion

**Media Pipeline Coordinator**
- **Responsibility**: Enqueues FFmpeg jobs, tracks processing status, updates `media_assets` table
- **Job Types**: Video transcoding (1080p/720p/480p), thumbnail extraction, WebP conversion
- **Status Tracking**: `media_assets.processing_status` (pending/processing/completed/failed)

#### Reporting & Analytics Module

**Reporting Projections**
- **Responsibility**: Builds read-optimized aggregates for sales, inventory, commissions, loyalty
- **Implementation**: CDI event listeners for `OrderCompleted`, `PaymentCaptured`, `PayoutProcessed` events
- **Storage**: Separate aggregate tables (`sales_summary_daily`, `inventory_snapshot_hourly`)
- **Consistency**: Eventually consistent (acceptable for reporting use cases)

**Dashboard Metrics API**
- **Responsibility**: Exposes time-series data for admin widgets, exports CSV/PDF
- **Endpoints**: `/api/v1/reports/sales`, `/api/v1/reports/inventory`, `/api/v1/reports/commissions`
- **Filtering**: Date ranges, product filters, location filters, export formats

#### Platform Admin Module

**Tenant Lifecycle Service**
- **Responsibility**: Store provisioning, plan changes, suspension, deletion
- **Operations**: `provisionTenant`, `upgradePlan`, `suspendTenant`, `deleteTenant`
- **Side Effects**: Provisions database tenant row, creates default admin user, sends welcome email

**Platform Metrics Aggregator**
- **Responsibility**: Cross-tenant health metrics, rate limit tracking, usage billing
- **Implementation**: Scheduled job aggregates metrics across all tenants
- **Metrics**: Active tenants, request rates, storage usage, payment volume

#### Integration Adapter Layer

**Carrier Adapters**
- **Responsibility**: Unified interface for USPS/UPS/FedEx APIs with retry/fallback
- **Interface**: `ShippingCarrier` with methods `getRates`, `createLabel`, `getTracking`
- **Resilience**: Circuit breaker pattern, fallback to alternative carriers on failures

**Address Validator**
- **Responsibility**: Normalizes addresses via USPS/SmartyStreets
- **Caching**: Validated addresses cached by hash to reduce API costs
- **Confidence Scoring**: Returns confidence score + suggestions for ambiguous addresses

**Tax Service Adapter**
- **Responsibility**: Calculates sales tax via Avalara/TaxJar
- **Input**: Line items + shipping address + nexus settings
- **Caching**: Tax rates cached by ZIP/postal code + product category

**Email Adapter**
- **Responsibility**: Quarkus Mailer wrapper with domain filtering, template rendering
- **Domain Filtering**: Development mode restricts emails to allowlisted domains
- **Templates**: Qute-rendered HTML emails with tenant branding

#### Infrastructure Primitives

**Background Job Scheduler**
- **Responsibility**: Polls `delayed_jobs` table, invokes handlers with priority queues
- **Implementation**: `@Scheduled` methods with separate thread pools per priority
- **Retries**: Exponential backoff (1min, 5min, 15min, 1hr), max 5 attempts

**Cache Manager**
- **Responsibility**: Tenant-aware cache key management, TTL policies, invalidation events
- **Keys**: `tenant:{tenant_id}:{entity}:{id}` format for automatic tenant scoping
- **Invalidation**: CDI events trigger cache invalidation across pods (eventually consistent)

**Domain Event Bus**
- **Responsibility**: In-process publish/subscribe for cross-module notifications
- **Implementation**: CDI `@Observes` / `Event.fire()` pattern
- **Event Types**: `ProductCreated`, `OrderCompleted`, `PaymentCaptured`, `PayoutProcessed`

### Module Dependency Rules

**Dependency Hierarchy** (inner modules have no outward dependencies):

```
┌─────────────────────────────────────────┐
│        Presentation Layer               │ (REST Resources, Qute, Vue Assets)
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│     Tenant Access Gateway (Cross-Cut)   │ (TenantFilter, TenantContext, Feature Flags)
└─────────────────┬───────────────────────┘
                  │
        ┌─────────┴──────────┐
        │                    │
┌───────▼──────┐    ┌────────▼──────────┐
│   Identity   │    │    Catalog        │ (Inner Modules - no dependencies on outer)
│   Tenancy    │    │    Payments       │
└──────────────┘    └───────────────────┘
        │                    │
        └─────────┬──────────┘
                  │
        ┌─────────▼──────────┐
        │     Orders         │ (Outer Modules - depend on inner)
        │   Consignment      │
        │     Loyalty        │
        └─────────┬──────────┘
                  │
        ┌─────────▼──────────┐
        │  Notifications     │ (Observes all domain events)
        │   Reporting        │
        └────────────────────┘
```

**Rules:**
- Inner modules (Tenancy, Identity, Catalog, Payments) have no dependencies on outer modules
- Outer modules (Orders, Consignment) may depend on inner modules via CDI service interfaces
- Cross-cutting modules (Tenant Gateway, Reporting, Notifications) observe domain events but don't introduce tight coupling
- Integration adapters abstract external dependencies behind interfaces

---

## Rendering Instructions

All diagrams use PlantUML with the C4-PlantUML library. To generate PNG images:

### Using Docker (Recommended)

```bash
# From repository root
docker run --rm -v "$PWD":/work ghcr.io/plantuml/plantuml \
  docs/diagrams/system-context.puml \
  docs/diagrams/container.puml \
  docs/diagrams/component.puml \
  -tpng
```

**Output:**
- `docs/diagrams/system-context.png`
- `docs/diagrams/container.png`
- `docs/diagrams/component.png`

### Using Local PlantUML

```bash
# Requires PlantUML JAR and Graphviz installed
plantuml docs/diagrams/system-context.puml -tpng
plantuml docs/diagrams/container.puml -tpng
plantuml docs/diagrams/component.puml -tpng
```

### CI Integration

GitHub Actions workflow (`.github/workflows/docs.yml`) automatically regenerates diagrams on commits to `docs/diagrams/*.puml` files and commits updated PNGs.

---

## Architectural Anchors

These diagrams support the following architectural documentation sections:

- **`docs/architecture_overview.md#section-3-layered-architecture`**: Layered modular monolith breakdown
- **`docs/architecture_overview.md#section-4-tenant-isolation`**: Multi-tenancy enforcement via TenantContext
- **`docs/adr/ADR-001-tenancy.md`**: Shared database tenancy model, RLS policies
- **`CLAUDE.md#multi-tenancy`**: Tenant resolution flow, subdomain/custom domain mapping
- **`CLAUDE.md#authentication--sessions`**: JWT stateless auth, session logging, impersonation

---

## Module Responsibilities Summary

Quick reference for module ownership:

| Module | Primary Entities | Key Responsibilities | External Dependencies |
|--------|------------------|----------------------|----------------------|
| **Tenancy** | Tenant, Store, CustomDomain | Tenant resolution, domain mapping | None (core module) |
| **Identity** | User, Session, Role, Permission | Auth, JWT, impersonation, session logs | Tenancy |
| **Catalog** | Product, Variant, Category, InventoryItem | Product CRUD, stock tracking, search | Tenancy, Object Storage |
| **Payments** | Payment, Refund, PaymentMethod, LedgerEntry | Payment processing, ledger, webhooks | Stripe SDK |
| **Orders** | Order, LineItem, Shipment, Return | Checkout orchestration, fulfillment | Catalog, Payments, Carriers, Tax |
| **Consignment** | Consignor, CommissionSchedule, Payout | Vendor management, commissions, payouts | Catalog, Orders, Payments (Stripe Connect) |
| **Loyalty** | LoyaltyAccount, LoyaltyTransaction, Tier | Points accumulation, redemption | Tenancy |
| **POS** | POSSale, OfflineQueue, HardwareIntegration | In-store sales, offline sync, hardware | Orders, Payments (Stripe Terminal) |
| **Media** | MediaAsset, ProcessingJob | Upload coordination, FFmpeg processing | Object Storage (R2), FFmpeg Worker |
| **Reporting** | Aggregates, DashboardMetrics | Event-driven projections, exports | All modules (observes events) |
| **Platform** | TenantLifecycle, PlatformMetrics | Store provisioning, cross-tenant monitoring | Identity (impersonation) |

---

## Deployment Mapping

Relationship between C4 containers and Kubernetes resources:

| C4 Container | Kubernetes Resource | Replicas | Scaling Strategy |
|--------------|---------------------|----------|------------------|
| Quarkus Application | Deployment (`village-storefront-app`) | 2-10 | HPA on CPU/memory |
| PostgreSQL | StatefulSet (`village-storefront-db`) | 1 primary + replicas | Vertical scaling, read replicas |
| Background Job Worker | Deployment (`village-storefront-worker`) | 1-3 | Manual scaling based on queue depth |
| Media Worker | Deployment (`village-storefront-media`) | 1-5 | HPA on CPU (high CPU intensive) |
| Admin SPA / Storefront | (Served by Quarkus App) | N/A | Bundled in Quarkus container |

**Service Mesh:** Not currently used (planned for Q3 2026 if microservices extraction occurs)

---

## Integration Patterns Summary

| Pattern | Usage | Implementation |
|---------|-------|----------------|
| **API Gateway** | External API access | Kubernetes Ingress with cert-manager, Cloudflare DNS |
| **Service Orchestration** | Checkout workflow | Checkout Orchestrator CDI service coordinates catalog, payments, shipping, tax |
| **Event-Driven** | Reporting, notifications | CDI events (`@Observes`), future domain events table for inter-service events |
| **Webhook Ingestion** | Stripe, carriers | REST endpoints with signature validation, idempotency via event IDs |
| **Job Queue** | Async processing | Database-backed DelayedJob table, priority queues, scheduled polling |
| **Adapter Pattern** | External services | Unified interfaces for carriers, tax, address validation with retry/fallback |
| **Circuit Breaker** | External API resilience | Quarkus Fault Tolerance annotations on adapter methods |
| **Cache-Aside** | Performance | Caffeine cache with tenant-aware keys, TTL-based expiration |

---

## Next Steps

### Diagram Evolution

As the system evolves, these diagrams should be updated when:

1. **New external integrations** are added (update System Context)
2. **New deployment containers** are introduced (update Container diagram)
3. **New bounded-context modules** are created (update Component diagram)
4. **Major architectural decisions** change module boundaries (update all levels)

### Future Diagrams

Planned for subsequent iterations:

- **C4 Code Level (L4)**: UML class diagrams for key modules (Checkout Orchestrator, Payment Service)
- **Sequence Diagrams**: Detailed flows for checkout, consignment payout, impersonation
- **Entity-Relationship Diagram (ERD)**: Database schema (planned I1.T3)
- **Deployment Diagram**: Kubernetes topology with networking, storage, ingress (planned I2.T8)

### Validation

To ensure diagrams remain accurate:

- **Code Reviews**: Check diagram updates accompany architectural changes
- **Architecture Review Board**: Bi-weekly diagram review during ADR sessions
- **CI Validation**: Automated PlantUML syntax checks, broken link detection for anchors

---

**Document Version:** 1.0
**Last Updated:** 2026-01-08
**Maintained By:** Architecture Team
**Review Frequency:** Quarterly or after major architectural changes
