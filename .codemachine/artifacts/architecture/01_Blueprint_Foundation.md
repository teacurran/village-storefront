<!-- anchor: blueprint-foundation -->
# 01_Blueprint_Foundation.md

<!-- anchor: section-1-project-scale -->
### **1.0 Project Scale & Directives for Architects**

*   **Classification:** Large
*   **Rationale:** Scope spans SaaS multi-tenant ecommerce, consignment parity, POS, media processing, payment extensibility, and Kubernetes-native deployment; this exceeds startup MVP complexity and demands sustained cross-team coordination.
*   **Core Directive for Architects:** This is a **Large-scale** platform; every architectural decision MUST preserve high scalability, tenant isolation, and replaceable subsystems so that future vertical teams can evolve domains without destabilizing others.
*   Architects MUST treat every requirement through a platform lens: think in terms of platform capabilities, not one-off merchant hacks, and enforce configuration-driven extensibility for tenants.
*   Delivery must respect phased rollout, but the foundational seams (APIs, contracts, data models, background processing hooks) must be designed up front so Phase 2/3 modules can plug in without rework.

The leadership stance is "go slow to go fast": define the seams rigorously now, document tradeoffs, and prefer deliberate composition over improvisation. Each downstream architect inherits this mandate and will be evaluated against these constraints.

All artifacts referencing this blueprint must quote section numbers when proposing deviations; no silent drift is permitted. Use this section as the escalation protocol: if a design conflicts here, raise a change request before any implementation.

<!-- anchor: section-2-standard-kit -->
### **2.0 The "Standard Kit" (Mandatory Technology Stack)**

*   **Architectural Style:** Layered modular monolith in Quarkus with bounded-context modules, supporting future extraction into services if warranted; all modules communicate via CDI interfaces and domain events persisted in PostgreSQL, avoiding distributed transactions for v1.
*   **Frontend:** Qute + Tailwind + PrimeUI for storefront routes, Vue 3 + Vite + TypeScript + PrimeVue for `/admin/*`, both sharing a design token catalog stored in PostgreSQL and projected to Tailwind configs during build.
*   **Backend Language/Framework:** Java 21 with Quarkus extensions (RESTEasy Reactive, Qute, Panache, Scheduler, Kubernetes, Mailer, AWS S3) compiled to GraalVM native executables; Maven orchestrates modules with Spotless and JaCoCo gates enforced in CI.
*   **Database(s):** PostgreSQL 17 as single shared cluster with tenant-discriminator columns plus Postgres Row Level Security; MyBatis Migrations own schema evolution; time-series logs stay in partitioned PostgreSQL tables before archival to R2-compatible storage; no Redis per constraint, so Caffeine caches hot lookups per pod.
*   **Cloud Platform:** Neutral stance but optimized for Cloudflare R2 object storage, Stripe integrations, SMTP/SES mail relay, and Kubernetes (k3s) clusters; feature flags, secrets, and environment config ride on Kubernetes ConfigMaps and Secrets.
*   **Containerization:** GraalVM native executable baked into minimal Docker image (distroless/ubi-minimal) with Quarkus-generated Kubernetes manifests; GitHub Actions pipeline builds, signs, and pushes images, then applies k3s manifests with blue/green deployment strategy.
*   **Messaging/Queues:** Database-backed DelayedJob pattern (per standards doc) for async workloads, using dedicated tables with CRITICAL/HIGH/DEFAULT/LOW/BULK priorities; no external broker, but jobs expose CDI event hooks for retries, notifications, and auditing.

The Standard Kit is frozen; substitutions require explicit approval recorded in architecture decision log referencing this section. Any new module must integrate with this toolchain out of the gate.

<!-- anchor: section-3-rulebook -->
### **3.0 The "Rulebook" (Cross-Cutting Concerns)**

*   **Feature Flag Strategy:** Implement configuration-driven flags backed by PostgreSQL `feature_flags` table with tenant overrides and platform defaults; expose CDI `FeatureToggle` service cached via Caffeine, and ensure every net-new merchant-facing capability (storefront or admin) deploys dark with flag control and logging. Emergency kill switches must exist for payment flows, checkout, media processing, and impersonation features.
*   **Observability (Logging, Metrics, Tracing):** Structured JSON logging via Quarkus log format to stdout with tenant_id, store_id, user_id, session_id, correlation_id; OpenTelemetry instrumentation exports traces to Jaeger, and Prometheus scrapes `/q/metrics`. Error budgets must be codified per module, and alert rules map to Kubernetes liveness/readiness probes ensuring degraded modules scale independently.
*   **Security:** JWT auth with refresh tokens is canonical; TenantFilter + RLS enforce data isolation, and all admin impersonation actions log to immutable audit tables. Secrets live in Kubernetes Secrets, mail in non-prod uses domain filtering, and every inbound/outbound webhook is signed and validated. All SQL uses Panache parameter binding; bcrypt with work factor 12 handles passwords, and CSRF tokens are mandatory on mutating storefront forms.
*   **Data Lifecycle & Compliance:** Session/audit tables partition monthly, retaining 90 days hot; archival jobs gzip JSONL data to R2 with metadata for rehydration. Media uploads tagged by tenant path prefix; deletion uses soft-delete markers to keep auditability. PII encryption at rest uses database-native pgcrypto or application-level encryption for sensitive fields (SSN/EIN for consignors) with key rotation policy documented.
*   **Performance & Scaling:** Target <200ms API responses and <2s storefront page loads; Caffeine caches product catalog fragments, shipping rate lookups cached 15 minutes. All background processing must support horizontal scaling via job-claiming leases, and Stripe webhooks must be idempotent. Multi-pod deployments rely on stateless Quarkus instances; session state never stored in-memory beyond caching.
*   **Release Management:** GitHub Actions pipeline enforces Spotless, JaCoCo ≥80%, unit/integration tests, and native-image build. Feature flags gate partial functionality; blue/green rollout plus database migrations executed in safe mode (no destructive change without backwards-compatible toggle). Rollbacks rely on previous image plus migration down scripts prepared beforehand.
*   **Tenant Safety:** TenantContext is request-scoped truth; services must fail fast when tenant missing, and scheduled jobs iterating tenants must stream IDs to avoid memory blowup. Subdomain and custom-domain resolution share canonical data model to prevent duplication. CORS rules automatically whitelist each tenant's domains, and CDN signing respects tenant-specific TTL policies.

These rules bind all teams; deviations require documented architectural decision records referencing why a core concern was adjusted and when it will be reconciled.

<!-- anchor: section-4-blueprint -->
### **4.0 The "Blueprint" (Core Components & Boundaries)**

*   **System Overview:** Village Storefront is a layered modular monolith where Quarkus hosts tenant-aware REST APIs, storefront Qute templates, admin Vue SPA assets, and background job executors; PostgreSQL anchors multi-tenant data with RLS, while object storage, Stripe, carrier APIs, and FFmpeg-driven media processors run as integration edges controlled via explicit adapters.
*   **Core Architectural Principle:** Separation of Concerns is enforced through CDI interfaces, Panache repositories per domain, and DTO mappers isolating API schemas from persistence; modules communicate via domain events recorded in the database so that replacing Stripe, adding loyalty engines, or extracting POS services later does not ripple through storefront rendering or checkout orchestration.
*   **Key Components/Services:**
    *   **Tenant Access Gateway:** Handles subdomain/custom-domain resolution, populates TenantContext, enforces CORS, and rejects unknown tenants before routing deeper into the stack.
    *   **Identity & Session Service:** Issues JWT/refresh tokens, maintains session/activity logs, and exposes impersonation workflows with audit logging.
    *   **Storefront Rendering Engine:** Delivers Qute-templated storefront pages, injects branding/theme tokens, and coordinates PrimeUI interactions plus Tailwind builds.
    *   **Admin SPA Delivery Service:** Serves Vue 3 assets under `/admin/*`, provides bootstrapped config (tenant, feature flags, RBAC scopes), and proxies API calls through authenticated endpoints.
    *   **Catalog & Inventory Domain Module:** Owns products, variants, categories, collections, multi-location inventory, adjustments, transfers, and reporting projections.
    *   **Consignment Domain Module:** Manages consignor profiles, commission schedules, intake batches, payouts, vendor portals, and related notifications.
    *   **Checkout & Order Orchestrator:** Coordinates cart persistence, address validation, shipping rate fetch, Stripe intents, order lifecycle transitions, refunds, and returns.
    *   **Payment Integration Layer:** Implements PaymentProvider interfaces (Stripe first) plus generic payment, refund, dispute, and ledger models; handles Stripe Connect onboarding and payouts.
    *   **Loyalty & Rewards Module:** Tracks points balances, tier upgrades, expirations, and redemption hooks invoked during checkout and customer account flows.
    *   **POS & Offline Processor:** Provides browser-based POS UI assets, hardware integrations (Stripe Terminal, printers), offline queueing, and reconciliation jobs syncing queued sales.
    *   **Media Pipeline Controller:** Accepts uploads, enforces limits, queues FFmpeg jobs, generates thumbnails/WebP variants, and persists metadata plus signed URLs in object storage.
    *   **Background Job Scheduler & Worker:** Implements DelayedJob semantics, executes email dispatch, media processing, payouts, report generation, and certificate renewals via Quarkus `@Scheduled` triggers.
    *   **Reporting & Analytics Projection Service:** Builds read-optimized aggregates for sales, inventory, consignment, loyalty, and platform metrics; powers admin dashboard widgets and platform-level reports.
    *   **Platform Admin Console Backend:** Provides SaaS-level store management APIs, impersonation controls, plan/billing observability, and health monitoring endpoints for operations.
    *   **Integration Adapter Layer:** Abstracts external services (Stripe, USPS/UPS/FedEx APIs, address validation, SMTP, Cloudflare R2) behind versioned interfaces with retry/fallback policies.

Component boundaries must remain thin yet explicit: each module exposes interfaces plus OpenAPI-documented endpoints; no module may reach around another via shared tables without going through defined repositories or service contracts. Shared utilities live in a `platform-kit` module (validation, money, feature flags) to avoid duplication while preserving clarity.

<!-- anchor: section-5-contract -->
### **5.0 The "Contract" (API & Data Definitions)**

*   **Primary API Style:** RESTful APIs defined spec-first via OpenAPI 3.0; admin SPA and storefront both consume these endpoints, and headless integrations re-use the same definitions with tenant-aware auth.
*   **Data Model - Core Entities:**
    *   **Tenant:** `id (UUID)`, `subdomain`, `custom_domains[]`, `plan`, `status`, `base_currency`, `branding`, `config_flags`, `created_at`, `suspended_at`.
    *   **StoreUser:** `id`, `tenant_id`, `email`, `hashed_password`, `roles[]`, `permissions`, `status`, `last_login_at`, `mfa_secret`, `staff_profile`.
    *   **Customer:** `id`, `tenant_id`, `email`, `password_hash`, `name`, `addresses[]`, `phone`, `loyalty_balance`, `preferences`, `created_at`.
    *   **SessionLog:** `id`, `user_type`, `user_id`, `tenant_id`, `ip`, `user_agent`, `login_at`, `last_activity_at`, `logout_reason`, `device_fingerprint`.
    *   **Product:** `id`, `tenant_id`, `title`, `slug`, `description`, `status`, `visibility_window`, `seo`, `category_ids[]`, `collection_ids[]`, `custom_attributes`, `created_at`, `updated_at`.
    *   **Variant:** `id`, `product_id`, `tenant_id`, `sku`, `barcode`, `option_values`, `price`, `compare_at_price`, `inventory_policy`, `weight`, `dimensions`, `media_ids[]`.
    *   **InventoryLocation:** `id`, `tenant_id`, `name`, `address`, `type`, `capacity`, `timezone`.
    *   **InventoryLevel:** `id`, `variant_id`, `location_id`, `on_hand`, `reserved`, `incoming`, `safety_stock`, `low_stock_threshold`.
    *   **Consignor:** `id`, `tenant_id`, `contact`, `tax_info`, `commission_rules`, `payout_preferences`, `portal_access_token`, `status`.
    *   **ConsignmentItem:** `id`, `tenant_id`, `variant_id`, `consignor_id`, `cost_basis`, `received_at`, `expires_at`, `status`, `aging_days`, `payout_state`.
    *   **Cart:** `id`, `tenant_id`, `customer_id/null`, `items[]`, `discounts[]`, `shipping_address`, `billing_address`, `currency`, `subtotal`, `tax_total`, `shipping_total`, `grand_total`, `last_activity_at`.
    *   **Order:** `id`, `tenant_id`, `order_number`, `customer_id`, `cart_snapshot`, `status`, `payment_state`, `fulfillment_state`, `shipping_address`, `billing_address`, `totals`, `timeline[]`, `notes`, `tags`.
    *   **PaymentIntent:** `id`, `order_id`, `tenant_id`, `provider`, `provider_reference`, `amount`, `currency`, `application_fee`, `status`, `captured_at`, `failure_reason`, `metadata`.
    *   **Refund:** `id`, `payment_intent_id`, `amount`, `currency`, `reason`, `processed_at`, `provider_reference`, `status`.
    *   **Shipment:** `id`, `order_id`, `tenant_id`, `carrier`, `service_level`, `tracking_number`, `labels`, `packages[]`, `status`, `shipped_at`, `delivered_at`.
    *   **ReturnAuthorization:** `id`, `order_id`, `items[]`, `reason_codes[]`, `status`, `approved_by`, `received_at`, `restock_decision`, `refund_amount`.
    *   **LoyaltyLedgerEntry:** `id`, `tenant_id`, `customer_id`, `points_delta`, `reason`, `order_id`, `balance_after`, `expires_at`, `source`.
    *   **FeatureFlag:** `key`, `description`, `default_state`, `allowed_audiences`, `tenant_overrides`, `created_at`, `updated_at`.
    *   **AuditEvent:** `id`, `tenant_id`, `actor_type`, `actor_id`, `action`, `target_type`, `target_id`, `metadata`, `impersonation_context`, `occurred_at`.
    *   **BackgroundJob:** `id`, `queue`, `priority`, `payload`, `attempts`, `last_error`, `run_at`, `locked_by`, `locked_at`, `finished_at`.

Contracts require OpenAPI schemas for each entity and versioned endpoints such as `/api/v1/tenants/{tenantId}/products` or `/api/v1/admin/consignors`. Query parameters must include pagination, filtering, and sorting conventions standardized platform-wide. Webhooks (Stripe, carriers) must map to internal events and produce idempotency keys stored alongside payloads.

<!-- anchor: section-6-safety-net -->
### **6.0 The "Safety Net" (Ambiguities & Assumptions)**

*   **Identified Ambiguities:**
    *   The depth of reporting drill-downs per persona is unspecified beyond summaries.
    *   Requirements for digital product license enforcement or download limits are vague.
    *   Subscription billing cadence customization and proration behavior are not detailed.
    *   POS offline reconciliation rules (conflict resolution, duplicate prevention) are not described.
    *   Headless API authentication scopes for external partners are not fully defined.
    *   GDPR/CCPA-style data export/delete flows are not called out even though customer data persists.
    *   Multi-currency display rules for rounding, rate caching, and fallback currencies are underspecified.
    *   Media CDN invalidation and cache-busting strategy lacks detail.
    *   Feature priority phases imply sequencing but do not specify dependencies or rollout toggles.
    *   Platform admin analytics depth (real-time vs batch) is ambiguous.

*   **Governing Assumptions:**
    *   Reporting MVP delivers scheduled CSV/JSON exports and aggregated dashboards using precomputed tables; ad-hoc drill-downs beyond defined filters fall to later releases unless explicitly funded.
    *   Digital products enforce max download attempts per purchase and signed URLs with 24-hour expiry; licensing integrations (DRM) are out-of-scope for v1, but contract seams must allow plugin modules later.
    *   Subscription billing supports fixed cadences (weekly/monthly/quarterly/yearly) with Stripe Billing handling proration; platform stores configuration but delegates proration math to Stripe.
    *   POS offline mode stores encrypted transaction blobs locally and replays sequentially; conflicts resolved by "first accepted" rule, and duplicates avoided through offline-generated UUIDs validated server-side.
    *   Headless API tokens issued via OAuth Client Credentials per tenant with scopes (`catalog:read`, `cart:write`, `orders:read`); no anonymous API access allowed.
    *   Data privacy requests leverage existing archival mechanism: export generates JSONL package pulled from PostgreSQL + R2, delete requests soft-delete then purge after retention timer; full GDPR automation deferred but hooks must exist.
    *   Multi-currency display uses daily FX rates cached per tenant; rounding follows standard bankers' rounding to two decimals, and fallback currency is tenant base currency if conversion fails.
    *   Media CDN invalidation relies on versioned object keys (hash in filename) to avoid explicit purge calls; signed URLs include version to ensure clients fetch latest asset.
    *   Phase sequencing uses feature flags plus queue-based throttling; dependent modules must degrade gracefully when upstream feature disabled.
    *   Platform analytics run hourly batch ETL jobs populating aggregates; real-time streaming dashboards are future scope but schema must keep timestamp precision for later expansion.
    *   Stripe-only payment constraint holds for MVP; PaymentProvider interface must still be production-ready to onboard PayPal without rewriting checkout orchestration.
    *   No Redis mandate means rate limiting and throttling rely on token bucket implemented via PostgreSQL + Caffeine hybrid caches, and architects shall not design flows that assume cross-pod shared cache.

<!-- anchor: section-1-extended-directives -->
#### Section 1 Extended Directives

*   Treat the Large classification as justification for allocating separate CI lanes (storefront, admin, backend, infra); architects must design interfaces that let each lane progress independently without blocking others.
*   All architectural proposals must cite affected domains, expected tenant impact, and rollback plans; submissions lacking this triad are automatically rejected by the Foundation Architect.
*   Roadmaps must maintain at least one sprint buffer dedicated to technical debt remediation; this prevents deferred cleanups from eroding the separation-of-concerns mandate.
*   Architects must define explicit KPIs (latency, uptime, throughput) for their components and communicate them in their specialized blueprints to ensure consistent expectations across teams.
*   Threat modeling sessions are mandatory before releasing any module touching payments, impersonation, or media uploads; outputs feed security backlog items with owners.
*   Architectural artifacts must include dependency diagrams annotated with feature flags controlling each integration point to simplify phased rollouts.
*   Platform governance reviews occur monthly to reconcile new requirements with this blueprint; any accepted amendments must be versioned and redistributed to all architects.
*   Cross-team spike work (e.g., experimenting with alternative shipping providers) must run in isolated sandboxes and cannot touch production schemas without signed approval.

<!-- anchor: section-2-implementation-notes -->
#### Section 2 Implementation Notes

*   Modularization: Create Maven submodules for `core-platform`, `catalog`, `orders`, `consignment`, `loyalty`, `pos`, `media`, `admin-ui`, `storefront-ui`, and `platform-ops`. Each module enforces visibility boundaries using Java 9 module-info where feasible.
*   Storefront build pipeline: Tailwind configs per tenant compile during deployment using design tokens stored in the database; Hot reloading exists only for development pods to avoid runtime CSS compilation in production.
*   Admin SPA hosting: Quinoa plugin builds Vue assets, which are embedded as static resources served via Quarkus; caching headers differentiate between SPA shell (long-lived) and API responses (short-lived) to honor data freshness.
*   PostgreSQL tenancy: All tables include `tenant_id` plus `created_by` metadata; migration scripts must include RLS policy definitions to prevent manual drift between schema and security rules.
*   Object storage layering: Media uploads use presigned URLs generated by backend; processing jobs read from storage, write derived assets under `tenant_id/media_type/hash/size.ext`, and store metadata referencing both original and processed variants.
*   CI/CD specifics: GitHub Actions matrix builds run JVM tests, native image builds, and frontend lint/test steps in parallel. Artifacts include OpenAPI specs, Tailwind token bundles, and Kubernetes manifests published as pipeline outputs.
*   Infrastructure as Code: Use the Quarkus Kubernetes extension outputs as canonical manifests, but wrap them in Kustomize overlays per environment (`dev`, `staging`, `prod`). Secrets are provisioned via sealed-secrets or equivalent mechanism approved by security.
*   Queue visibility: DelayedJob tables expose Grafana dashboards via Prometheus metrics for job latency, failure rates, and worker concurrency; architects must ensure new job types emit these metrics.
*   Payment sandboxing: Stripe Connect onboarding flows require separate webhook endpoints for sandbox and production; the Standard Kit mandates environment variables controlling API keys with zero hardcoding.
*   Media processing dependencies: FFmpeg binaries packaged via container multi-stage build; health checks verify binary presence and version before pods become ready.
*   Browser compatibility: PrimeUI/PrimeVue components must follow accessibility guidelines WCAG 2.1 AA; linting includes axe-core tests in CI to maintain compliance.
*   CDN strategy: Static assets served through Cloudflare (or equivalent) with cache-busting hashed filenames; storefront-critical HTML remains server-rendered to preserve SEO and speed.

<!-- anchor: section-3-enforcement-playbooks -->
#### Section 3 Enforcement Playbooks

*   Feature flags governance: Maintain `feature_flags` table with columns for owner, review cadence, and expiry date; architects cannot introduce long-lived "permanent" flags without migrating them into configuration defaults.
*   Observability contracts: Every REST endpoint must include correlation IDs and measure key timings (DB query, external calls, template rendering) recorded as structured fields; OpenTelemetry spans reflect these breakdowns.
*   Security layering: RBAC rules defined centrally; admin and storefront controllers must use declarative annotations referencing scopes rather than imperative checks scattered through code.
*   Data retention automation: Scheduled jobs create upcoming partitions monthly and archive/deletion tasks run weekly; architects owning new log or audit tables must hook into this framework to prevent orphaned data.
*   Performance budgets: Each module publishes SLOs; e.g., Checkout ensures 95th percentile <300ms excluding external carrier/Stripe latencies, while Media Pipeline targets completion of standard image processing under 5 seconds.
*   Release guardrails: Any migration introducing new constraints must include feature-flagged code path toggleable back to previous behavior; this includes product schema changes, checkout enhancements, and loyalty logic updates.
*   Tenant safety nets: Background jobs iterating across tenants must page results and respect configurable rate limits so that large tenants do not starve smaller ones; failure to do so constitutes a severity-one incident.
*   Incident response: Logging and tracing fields allow Security/Support teams to extract tenant-specific incident timelines without cross-tenant data exposure; architects must ensure their modules populate these fields in every log.
*   Testing mandates: Unit tests cover business rules, integration tests run against PostgreSQL containers with RLS policies enabled, and end-to-end smoke tests verify tenant routing, checkout, and admin logins before promoting builds.
*   Compliance posture: PCI scope restricted by Stripe Elements/Connect; no module may handle raw card data. For SSN/EIN fields, encryption/decryption routines must log key version used for each operation.

<!-- anchor: section-4-interaction-notes -->
#### Section 4 Component Interaction Notes

*   Tenant Access Gateway publishes CDI events when tenants resolved or missing; downstream modules subscribe to enrich context (theme tokens, feature flags) without re-parsing host headers.
*   Identity Service exposes OAuth clients for headless integrations and uses Panache repositories dedicated to authentication tables; no other module touches these tables directly.
*   Storefront Rendering Engine consumes Catalog APIs via internal service calls even though running in same process, reinforcing contract-first development and enabling future service extraction.
*   Admin SPA operations route through `/api/v1/admin/*` endpoints that enforce role scopes; Platform Admin Console extends same API namespace with `platform` scope requiring super-user tokens.
*   Catalog module emits domain events (`ProductPublished`, `InventoryAdjusted`, `ConsignmentExpired`) persisted to `domain_events` table; Reporting Projection Service consumes these events via polling worker to build aggregates.
*   Consignment module coordinates with Inventory module by referencing variant IDs only; it cannot mutate inventory tables directly but instead calls Inventory service methods that enforce transactional integrity.
*   Checkout orchestrator sequences address validation, shipping rates, gift card redemption, loyalty points, and Stripe payments via asynchronous saga steps; failure handling relies on compensating actions recorded in audit logs.
*   Payment Integration Layer isolates provider-specific payloads; new providers implement `PaymentProvider`, `PaymentMethodProvider`, `WebhookHandler`, and optional `MarketplaceProvider` interfaces to guarantee parity.
*   POS module synchronizes carts and payments through the same Checkout APIs; offline transactions stored locally reuse Cart and Order schemas when replayed to avoid duplication.
*   Media Pipeline registers job types (`IMAGE_VARIANT`, `VIDEO_TRANSCODE`, `POSTER_FRAME`); each job yields metadata entries consumed by Storefront and Admin UI to display processing status.
*   Background Scheduler coordinates certificate renewal for custom domains, Stripe payout auditing, and archival tasks; each scheduled job identifies owning module for accountability.
*   Platform Admin Backend aggregates cross-tenant metrics by querying read models rather than live tables to avoid heavy scans on production workloads.
*   Integration Adapter Layer enforces timeout/retry policies in one place; modules declare adapters via dependency injection, never instantiating HTTP clients manually.
*   Shared utilities (money, measurement units, address normalization) live in `platform-kit`; consumption is read-only to avoid hidden coupling.

<!-- anchor: section-5-contract-patterns -->
#### Section 5 Contract Patterns

*   OpenAPI governance requires each endpoint to declare tenant scoping, authentication scheme, rate limits, and feature flag dependencies inside the spec description; linting fails PRs missing this metadata.
*   DTO layering: Define separate request/response DTOs, domain models, and persistence entities; mapping happens via MapStruct or manually curated assemblers to guard against exposing internal columns (e.g., commission cost basis).
*   Pagination contract: All list endpoints require `page`, `page_size`, `sort`, and `filter` parameters with documented defaults and maximums; response envelopes include `total_count`, `page_count`, and `links` for HATEOAS compatibility.
*   Webhook contracts: Incoming webhooks validated via HMAC signature; the handler writes payload + signature to `webhook_events` table before processing to allow replay. Outgoing webhooks (if introduced later) must follow the same guarantee.
*   Error handling: APIs return RFC 7807 Problem Details JSON with `traceId`, `tenantId`, and user-friendly `message`. Client libraries rely on these structures, so architects must not return ad-hoc payloads.
*   Data versioning: Entities with frequent schema evolution (Product, Order, PaymentIntent) include `version` numbers incremented per update; concurrency control ensures conflicting writes produce 409 responses.
*   Headless endpoints: Provide read-only scopes for catalog/cart, and write scopes for orders/cart modifications; tokens embed tenant_id, and rate limits apply per tenant per scope.
*   File upload contracts: Media uploads negotiated via `POST /media/upload-request` returning presigned URL, size/type limits, checksum; clients confirm completion via `POST /media/uploads/{id}/complete` enabling background processing.
*   Background job payload schema: Use JSON structures referencing job type, version, and associated entity IDs; workers must validate schema version before processing to prevent drift.
*   Reporting exports: `POST /reports/{reportType}` enqueues job returning job_id; `GET /reports/jobs/{id}` exposes status, download link, and expiration. Architects must reuse this template for all long-running report workflows.
*   POS offline queue API: `/pos/offline/batches` accepts encrypted bundle with transaction metadata; backend validates signature, stores payload, and triggers reconciliation jobs.

<!-- anchor: section-6-risk-mitigations -->
#### Section 6 Risk Mitigations

*   Ambiguity around reporting depth means architects must log instrumentation from day one so later drill-down work has data to leverage; retrofitting analytics without data capture is infeasible.
*   Digital subscription uncertainties require interface-driven checkout logic; keep subscription orchestration behind `SubscriptionCoordinator` so future policy tweaks stay localized.
*   POS offline assumptions should be validated through pilot programs with instrumentation capturing offline duration, queue sizes, and conflict rates.
*   Headless API scope clarity hinges on security reviews; until final scopes approved, architects must design tokens as easily extensible claim sets.
*   Privacy workflow assumptions drive backlog for deletion/export automation; the platform should expose admin UI toggles for manual review before executing destructive operations.
*   Multi-currency display must include automated tests verifying rounding for USD, EUR, GBP to catch localization bugs early.
*   Media CDN strategy should be load-tested to confirm hashed filenames propagate within caching TTLs; instrumentation of cache hit ratio is mandatory.
*   Feature flag sequencing for phased releases must include dependency graphs; release managers need dashboards showing which tenants have opted into each feature.
*   Analytics batch cadence assumptions imply eventual move toward streaming; architects should tag critical aggregates with metadata describing source freshness so UI can display "last updated" indicators.
*   Payment provider extensibility remains a risk if not validated; even with Stripe-only launch, create a dummy provider implementation used in automated tests to verify interface expectations.
*   Queue-only background processing could bottleneck; monitor job table size and plan partitioning or sharding strategies once thresholds reached. Architects must design jobs to be idempotent to support reprocessing after failures.

<!-- anchor: section-4-component-kpis -->
#### Section 4 Component KPIs

*   **Tenant Access Gateway:** SLA 99.99% availability, median tenant resolution <10ms, <0.1% misclassification rate. Metrics emitted per request with parsed subdomain, rule path, and fallback result.
*   **Identity & Session Service:** Token issuance median <50ms, refresh success ratio >99%, session log writes asynchronous but guaranteed within 1s; publishes audit spans for impersonation start/stop.
*   **Storefront Rendering Engine:** Cold render <150ms server-side, hydration bundle <120KB gzipped, SEO-critical pages pre-rendered nightly with monitoring to detect template regressions.
*   **Admin SPA Delivery:** Initial load under 2MB zipped, subsequent navigations rely on API caching; Service Worker handles offline shell but caches only static assets to avoid stale data.
*   **Catalog & Inventory Module:** Bulk import throughput of 5k products/minute target; ensuring variant upsert pipeline uses batched SQL to avoid row-by-row penalties.
*   **Consignment Module:** Intake batch creation <500ms for 100 items; payout batch closing within 5 minutes even for large consignors; ensures compliance logs retained for 7 years.
*   **Checkout & Order Orchestrator:** Successful checkout path 95th percentile <800ms despite external API calls; failure funnels instrumented to identify shipping vs payment vs inventory errors.
*   **Payment Layer:** Stripe webhook handling idempotency ratio 100%; payout reconciliation job ensures zero unresolved discrepancies after daily cycle.
*   **Loyalty & Rewards Module:** Points accrual synchronous writes <20ms overhead; tier recalculation job completes within 10 minutes nightly even for top-tier tenants.
*   **POS & Offline Processor:** Offline queue flush under 60 seconds upon reconnect; device enrollment logs include geolocation/timezone for auditing unusual access patterns.
*   **Media Pipeline:** Image variant generation success >99.5%; video transcode queue monitors queue depth <100 jobs default, autoscaling worker pods when threshold exceeded.
*   **Background Job Scheduler:** Job failure rate maintained below 0.5%; instrumentation distinguishing retriable vs permanent failures surfaces to on-call runbooks.
*   **Reporting Service:** Report generation throughput 50k rows/min; caching layer stores latest results for 15 minutes for repeated requests.
*   **Platform Admin Backend:** Global store list queries respond <300ms with pagination; impersonation commands require confirmation prompts and audit logs persisted before acting.
*   **Integration Adapter Layer:** External call retries capped at 3 with exponential backoff; fallback metrics differentiate between dependency outage vs local misconfiguration.

<!-- anchor: section-5-data-governance-notes -->
#### Section 5 Data Governance Notes

*   Entity schemas must include `created_by` and `updated_by` columns referencing StoreUser or system accounts to maintain auditability; API contracts expose these fields only to appropriately scoped users.
*   Multi-tenant data exports zip JSONL files plus CSV summary; filenames include tenant slug, date range, and hash for verification; download URLs expire after 72 hours.
*   Currency handling uses `Money` value objects storing integer minor units plus ISO currency code; contracts avoid floating-point arithmetic by enforcing integer arithmetic at all layers.
*   Tax calculations rely on external services or configuration tables; API requests for checkout include `tax_items[]` arrays with jurisdiction codes and amounts to keep auditable trails.
*   Discount/Gift card models treat codes as first-class entities with lifecycle statuses; API endpoints for applying discounts must return standardized responses indicating acceptance, rejection reason, or requires approval.
*   Address data normalized via `AddressNormalizer` service; API accepts freeform input but persistence uses structured components (street, city, state, postal_code, country, lat/long when available).
*   Media metadata stores MIME type, checksum, dimensions, duration (videos), and processing status; API responses must redact storage bucket/internal paths from untrusted clients.
*   Background jobs referencing customer data must store hashed identifiers in payloads; plaintext personal data only retrieved at execution time to limit exposure in logs.
*   RBAC policies stored in structured table linking role -> permission -> scope; API responses deliver effective permissions so clients can adjust UI controls dynamically.
*   Consent tracking for marketing communications stored per customer with timestamp/source; exports include consent timeline to satisfy compliance audits.
*   Archival records include manifest JSON describing schema version and encryption method; retrieval requests validate manifest before streaming data back to requestor.
*   Reporting APIs returning aggregated data must include `data_freshness_timestamp` field so downstream systems know staleness level.
*   Subscription entities include `trial_period_days`, `auto_renew`, `cancel_at_period_end` flags; API must provide clear semantics for pause/resume operations once implemented.
*   POS devices registered as `PosDevice` entities with `device_id`, `tenant_id`, `location_id`, `status`, `last_seen_at`, `firmware_version`; checkout APIs validate device registration before accepting transactions.
*   Platform admin overrides recorded via `PlatformCommand` entity capturing actor, tenant, command, payload snapshot, and rationale; APIs retrieving audit logs rely on this schema.

<!-- anchor: section-6-outstanding-questions -->
#### Section 6 Outstanding Questions

*   What SLA is promised to merchants for media processing turnaround, and does it vary by plan tier? Need business input to finalize autoscaling policies.
*   Does the platform intend to support white-label mobile apps in future phases, requiring additional API scopes or push notification infrastructure?
*   For subscriptions, will stores need usage-based billing (meters) or purely interval-based? Architects should clarify before locking database schema for subscriptions.
*   Should gift cards be multi-store (platform-level) or tenant-specific only? Current assumption is tenant-only, but platform promotions might need cross-store support later.
*   Will vendor payouts ever need alternative rails (ACH direct, PayPal MassPay), or is Stripe Connect sufficient long-term? Contract boundaries allow new providers but business roadmap is unclear.
*   How much control do merchants receive over search relevance tuning? Without clarity, the catalog search implementation should remain modular for future customization.
*   Are there regulatory requirements for consignment contracts (state-specific) that require storing signed agreements or document attachments in secure storage?
*   What is the expectation for customers exporting loyalty histories? Determine whether self-service portal needed or admin-managed exports suffice.
*   How frequently should address validation re-check stored addresses (e.g., annually, per order)?
*   Are there compliance constraints that would require data residency options (EU data center) in the near term?
*   Does the platform plan to integrate third-party marketing automation (Klaviyo, Mailchimp), affecting API surface and webhook volume?
*   Should future marketplaces or cross-store discovery remain out of scope permanently, or should data models avoid assumptions that prevent such expansion?
*   Will store suspension include automatic disabling of DNS/SSL automation, or is manual intervention expected by platform ops?
*   Are there requirements for auditing staff actions within POS separate from general audit events, especially for cash drawer openings and voided transactions?
*   Does the SaaS need configurable tax inclusion/exclusion per catalog item for jurisdictions like VAT/GST, or will U.S.-style tax exclusive pricing remain default?
*   Are there service level objectives for carrier rate integrations (retry windows, caching durations) beyond the base 15-minute cache directive?

<!-- anchor: section-2-compliance-checklist -->
#### Section 2 Compliance Checklist

*   Verify every Maven module applies Spotless and JaCoCo plugins; CI must fail if coverage falls below 80% or formatting drifts.
*   GraalVM native compilation flags (`-H:+ReportExceptionStackTraces`, `--initialize-at-build-time`) documented per module; deviations require sign-off because native builds amplify reflection issues.
*   Quarkus configuration profiles (`%dev`, `%test`, `%prod`) must isolate secrets; no profile may inherit production credentials, and developers must rely on `.env` files ignored by git.
*   PostgreSQL schema migrations require reversible scripts unless data loss prevention plan documented; MyBatis migration IDs follow timestamp naming for ordering.
*   S3/R2 buckets configured with server-side encryption and object versioning; lifecycle policies remove old media variants only when flagged as superseded.
*   Stripe webhook endpoints stored in configuration and rotated per environment; TLS certificates automatically renewed using Let's Encrypt automation jobs defined in `platform-ops` module.
*   Vite/Vue build enforces TypeScript strict mode and ESLint with security plugin (eslint-plugin-security); storefront JS linted via ESLint as well to avoid divergence.
*   Tailwind configuration uses design token JSON generated from database; build process fetches tokens securely (service account) and writes ephemeral file removed after build.
*   Kubernetes manifests include resource requests/limits, liveness/readiness probes, and PodDisruptionBudgets; absence of these fields fails CI manifest validation.
*   GitHub Actions secrets stored in GitHub Encrypted Secrets; workflows referencing secrets must provide environment-scoped access controls.
*   Background job workers run with dedicated service accounts/roles limiting database permissions to relevant tables, preventing job code from escalating privileges inadvertently.
*   Logging retention policies mirrored between application logs and Kubernetes cluster logs; ensure log shipping pipelines redact PII before leaving cluster boundaries.
*   PrimeVue components imported tree-shaken to minimize bundle size; unused components banned from admin build to meet performance targets.
*   FFmpeg invocation sanitized to prevent shell injection; parameters whitelisted, and temporary files stored in pod-local volumes cleared after completion.

<!-- anchor: section-3-operational-guardrails -->
#### Section 3 Operational Guardrails

*   On-call rotation receives runbooks for each module describing feature flags to flip, commands to pause/resume queues, and database access procedures; architects own runbook accuracy.
*   Deployments must support canarying by routing a subset of tenant traffic through header-based routing; architects need to design APIs tolerant of duplicate requests during canary aborts.
*   Rate limiting uses hybrid approach (Caffeine + PostgreSQL counters); modules needing stricter limits (auth, checkout) must specify thresholds and rejection messaging consistent with UX guidelines.
*   Audit logs considered write-once; no service can edit/delete entries outside archival pipeline. Architects must treat audit logging failures as blocking errors, not best-effort.
*   Secrets rotation schedule documented; payment, carrier, and email credentials must rotate quarterly, with automation tasks tracked in platform backlog.
*   Disaster recovery: Daily database backups plus hourly WAL shipping; object storage replicates across regions when available. Architects must ensure their modules support point-in-time recovery by avoiding cross-tenant shared state outside the database.

<!-- anchor: section-6-alignment-tasks -->
#### Section 6 Alignment Tasks

*   Capture answers to outstanding questions in centralized decision log within two sprints; unresolved doubts block downstream blueprint approvals.
*   Schedule quarterly blueprint refresh workshops with specialized architects to validate assumptions, retire obsolete ones, and add new guardrails as the product matures.
