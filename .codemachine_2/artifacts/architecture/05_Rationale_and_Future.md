<!-- anchor: 4-0-design-rationale -->
## 4. Design Rationale & Trade-offs

Village Storefront inherits the foundation blueprint and codifies why certain technology, deployment, and governance choices anchor the SaaS platform.
This section summarizes decisions, alternatives, and risk posture to ensure downstream architects understand the "why" before evolving modules.

<!-- anchor: 4-1-key-decisions -->
### 4.1 Key Decisions Summary

<!-- anchor: 4-1-1-architectural-style -->
#### 4.1.1 Layered Modular Monolith in Quarkus

- Adopted per foundation §2.0 to maintain cohesion while exposing seams for future service extraction.
- CDI interfaces and domain events keep modules loosely coupled, allowing independent team velocity without microservice overhead.
- GraalVM native compilation provides consistent performance for storefront rendering and background workers sharing the same runtime.
- MapStruct DTO boundaries and Panache repositories enforce separation between API contracts and persistence.

<!-- anchor: 4-1-2-tenancy-and-database -->
#### 4.1.2 Shared PostgreSQL with RLS Tenancy

- Shared cluster with `tenant_id` column on every table, enforced by PostgreSQL RLS policies, yields predictable cost per tenant.
- MyBatis migrations own schema + RLS definitions, preventing drift between structure and security.
- Partitioning for session/audit tables ensures time-based retention and efficient archival to Cloudflare R2 per §5 Data Governance.
- TenantContext class plus Panache filters create defense-in-depth, ensuring requests cannot bypass RLS.

<!-- anchor: 4-1-3-frontend-split -->
#### 4.1.3 Dual Frontend Strategy (Qute + Vue)

- Qute + Tailwind + PrimeUI for storefront routes satisfy SEO, performance, and multi-tenant branding requirements without shipping a client-heavy SPA.
- Vue 3 + Vite + PrimeVue for `/admin/*` enables rich admin interactions, POS workflows, and virtualization-heavy components.
- Quinoa plugin standardizes asset builds, embedding hashed bundles within Quarkus for simplified deployments.
- Design token catalog stored in PostgreSQL ensures storefront and admin share typography, spacing, and color systems.

<!-- anchor: 4-1-4-background-async -->
#### 4.1.4 Database-backed DelayedJob Pattern

- Aligns with "No Redis" constraint while providing prioritized background processing for media, payouts, reporting, and certificate renewal.
- JSON payloads with versioning allow schema evolution and safe reprocessing after code changes.
- Workers leverage Quarkus `@Scheduled` and Panache for predictable behavior even under auto-scaling.
- Metrics emitted for queue depth, job latency, retries, and failure codes, feeding runbook automation.

<!-- anchor: 4-1-5-payment-architecture -->
#### 4.1.5 Payment Provider Abstraction with Stripe-first Delivery

- PaymentProvider/Method/Marketplace interfaces described in §8 ensure physical separation between Stripe orchestration and business logic.
- Stripe Connect adoption aligns with platform fee requirements and consignment payouts, while leaving hooks for PayPal, CashApp, Square.
- Webhook storage + idempotency keys protect against double-processing and support forensic replay.
- Dummy provider implementation used in CI proves extensibility before new providers join.

<!-- anchor: 4-1-6-media-pipeline -->
#### 4.1.6 Media Pipeline Strategy

- Cloudflare R2 for object storage satisfies multi-tenant isolation via prefix structure and server-side encryption.
- Thumbnailator and FFmpeg invoked per foundation decisions for image recompression and video transcode, providing deterministic outputs.
- Variant generation stored once and reused; lazy generation ensures compute consumption scales with demand.
- Signed URLs with expiry enforce access control for digital downloads and private assets.

<!-- anchor: 4-1-7-observability -->
#### 4.1.7 Observability and Governance

- Structured JSON logging, Prometheus metrics, and OpenTelemetry tracing standardized to ensure consistent triage experience.
- Health endpoints integrate with Kubernetes to gate traffic and mark dependencies as degraded before failure cascades.
- Feature flags recorded with owners and expiry dates to satisfy governance in §3 Rulebook and §3 Enforcement Playbooks.
- Monthly governance reviews mandated by §Section 6 Alignment capture deviations and seed ADR updates.

<!-- anchor: 4-1-8-security-posture -->
#### 4.1.8 Security Posture

- bcrypt password hashing (work factor 12), JWT + refresh tokens, and RBAC scopes align with requirements listed in §2 Security.
- Impersonation logging with reason codes ensures compliance and auditability for platform admins.
- PII encryption via pgcrypto with key version metadata addresses tax and payout compliance.
- HTTPS enforced cluster-wide, secrets isolated via Kubernetes Secrets, and webhooks validated with HMAC signatures.

<!-- anchor: 4-1-9-ci-cd -->
#### 4.1.9 CI/CD and Deployment Decision

- GitHub Actions center pipeline, running Spotless, JaCoCo (≥80%), frontend lint/tests, and GraalVM builds before pushing images.
- Quarkus Kubernetes extension + Kustomize overlays per environment reduce manual manifest drift.
- Blue/green strategy chosen to preserve SLA while enabling fast rollbacks; feature flags fill gaps for incremental rollout.
- Artifacts include OpenAPI specs, Tailwind token bundles, Kubernetes manifests, and PlantUML diagrams for traceability.

<!-- anchor: 4-1-10-data-lifecycle -->
#### 4.1.10 Data Lifecycle and Compliance

- 90-day hot retention for session/audit data with archival to R2 JSONL ensures compliance without ballooning primary storage.
- Privacy workflows reuse archival mechanism, producing manifest-driven exports zipped with hashed filenames.
- Background jobs manage partition creation, archival, and deletion automatically, keeping manual labor minimal.
- Consent tracking, loyalty ledger, and PlatformCommand records share consistent metadata (actor, reason, timestamp) to pass audits.

<!-- anchor: 4-1-11-observability-kpi -->
#### 4.1.11 KPI-driven Operations

- KPIs per module (Tenant Access Gateway, Checkout, Media, Loyalty, etc.) defined in foundation §4 and repeated operational doc, guiding SLO definitions.
- Alert budgets tie into release gating: if budgets burn, release freeze enforces "go slow to go fast" directive.
- Synthetic monitoring ensures real customer flow coverage; failure to maintain tests blocks releases.

<!-- anchor: 4-1-12-feature-flag-discipline -->
#### 4.1.12 Feature Flag Discipline

- All merchant-facing features ship behind flags stored in PostgreSQL `feature_flags` table with tenant overrides.
- Flags double as emergency kill switches for checkout, media, impersonation, and payments per §3 Rulebook.
- Governance requires owner, review cadence, and expiry, preventing "flag debt".
- Platform admin UI surfaces per-tenant overrides to control Phase 2/3 features gracefully.

<!-- anchor: 4-2-alternatives -->
### 4.2 Alternatives Considered

<!-- anchor: 4-2-1-microservices-vs-monolith -->
#### 4.2.1 Microservices versus Modular Monolith

- A microservice architecture was evaluated but rejected because operational stack would expand to multiple repositories, service meshes, and cross-service migrations prematurely.
- Modular monolith keeps team focus on domain boundaries without paying distributed transaction cost, aligning with foundation §2.0.
- Decision defers service decomposition until scaling pressures justify added complexity, with CDI interfaces and domain events preparing for extraction.

<!-- anchor: 4-2-2-per-tenant-database -->
#### 4.2.2 Per-tenant Database versus Shared Cluster

- Dedicated databases per tenant offer hard isolation but increase operational cost, migration complexity, and reporting headwinds.
- Shared PostgreSQL with RLS handles hundreds of tenants while leveraging indexes, partitions, and caching to control performance.
- Platform-level analytics and cross-tenant administration rely on shared schema, further justifying single-cluster strategy.

<!-- anchor: 4-2-3-redis-orchestration -->
#### 4.2.3 External Message Broker versus Database-backed Jobs

- Redis, RabbitMQ, or Kafka-based queues were considered for delayed jobs but conflict with "No Redis" mandate and add operational burden.
- Database-backed DelayedJob pattern ensures persistence, durability, and transactional control without extra infrastructure.
- Quarkus scheduling combined with row locks delivers predictable behavior even in auto-scaling clusters.

<!-- anchor: 4-2-4-frontend-approach -->
#### 4.2.4 SPA Storefront versus Server-rendered Qute

- Full SPA storefronts increase complexity, degrade SEO, and complicate multi-tenant theming.
- Qute templates keep HTML server-side with optional PrimeUI hydration for interactive elements, aligning with performance targets (<2s load).
- Decision also simplifies headless API interplay by sharing REST resources across store and external consumers.

<!-- anchor: 4-2-5-third-party-media -->
#### 4.2.5 Outsourced Media Processing versus In-cluster FFmpeg

- Third-party media pipelines reduce custom work but introduce cost and limited control over variant formats.
- In-cluster FFmpeg via ProcessBuilder ensures deterministic outputs, easier tenant-specific quotas, and alignment with no external dependency directive.
- Decision retains ability to scale worker pods based on queue depth and reuse Cloudflare R2 storage for both originals and derivatives.

<!-- anchor: 4-2-6-managed-identity -->
#### 4.2.6 Managed Identity Provider versus In-app Identity

- External identity platforms (Auth0, Okta) were evaluated but rejected to keep tenant-level customization, impersonation logging, and POS quick-login in sync with foundation mandates.
- In-app identity leverages Quarkus OIDC and Panache while still integrating with social logins as optional features.
- Decision ensures SaaS-specific requirements (PlatformAdmin impersonation, portal tokens) live alongside business logic.

<!-- anchor: 4-3-known-risks -->
### 4.3 Known Risks & Mitigation

<!-- anchor: 4-3-1-tenant-hotspots -->
#### 4.3.1 Tenant Hotspots and Noisy Neighbors

- Risk: High-traffic tenants could saturate shared tables, caches, and job queues, impacting smaller merchants.
- Mitigation: HPAs per module, per-tenant rate limits, dedicated queues for heavy tenants, and partitioned reporting data reduce blast radius.

<!-- anchor: 4-3-2-background-job-backlog -->
#### 4.3.2 Background Job Backlog

- Risk: Database-backed queues may grow faster than worker pods, leading to delayed media processing or payouts.
- Mitigation: Queue depth metrics trigger auto-scaling; spare worker pools dedicated to regulatory tasks prevent starvation; dead-letter monitoring catches poison pills.

<!-- anchor: 4-3-3-migration-complexity -->
#### 4.3.3 Schema Migration Complexity

- Risk: Large schema touching many tenant tables can extend deployment windows or require downtime.
- Mitigation: MyBatis reversible migrations, feature-flag-driven read/write paths, and black-box tests against staging ensure safe rollouts.

<!-- anchor: 4-3-4-ffmpeg-resource -->
#### 4.3.4 FFmpeg Resource Contention

- Risk: Video transcode jobs could exhaust CPU/memory, impacting transactional pods.
- Mitigation: Separate worker Deployment with resource quotas, queue prioritization, and ability to throttle new uploads via feature flags.

<!-- anchor: 4-3-5-stripe-dependence -->
#### 4.3.5 Stripe Dependency

- Risk: Stripe outages or API changes pause checkout or payouts since Stripe is only provider in MVP.
- Mitigation: PaymentProvider abstraction, sandbox monitoring, and readiness for second provider reduce future migration time; platform runs fallback communication plans.

<!-- anchor: 4-3-6-reporting-latency -->
#### 4.3.6 Reporting Latency and Consistency

- Risk: Aggregations built from domain events may lag or miss events if queue processing fails.
- Mitigation: Idempotent event consumers, reconciliation jobs comparing source-of-truth tables, and `data_freshness_timestamp` metadata make staleness transparent.

<!-- anchor: 4-3-7-impersonation-control -->
#### 4.3.7 Impersonation Abuse

- Risk: Platform admins gain powerful access; misuse could violate privacy expectations.
- Mitigation: Mandatory reason codes, immutable logs, visual indicators, and regular audits keep impersonation accountable.

<!-- anchor: 4-3-8-privacy-workflows -->
#### 4.3.8 Privacy and Retention Workflows

- Risk: Failure to process export/delete requests degrades compliance posture.
- Mitigation: Queue isolation, manifest-tracked exports, and scheduled audits verifying pipeline health ensure regulatory readiness.

<!-- anchor: 4-3-9-carrier-risk -->
#### 4.3.9 Carrier Integration Fragility

- Risk: Direct USPS/UPS/FedEx integrations can degrade or change terms without notice, breaking checkout shipping quotes.
- Mitigation: Maintain fallback table rates, monitor APIs via synthetic calls, and provide feature flags to temporarily disable problematic carriers without halting entire checkout.

<!-- anchor: 4-3-10-api-abuse -->
#### 4.3.10 Headless API Abuse

- Risk: OAuth client credentials could be abused for scraping or abusive automation if tenants leak credentials.
- Mitigation: Rate limits keyed by tenant + client_id, scoped tokens, and anomaly detection on API usage patterns; revoke + rotate credentials via admin tooling.

<!-- anchor: 5-0-future-considerations -->
## 5. Future Considerations

<!-- anchor: 5-1-potential-evolution -->
### 5.1 Potential Evolution

- Expand PaymentProvider roster (PayPal, CashApp, Square) leveraging existing interfaces and dummy provider tests.
- Introduce streaming analytics or data lake feeding from domain events when reporting latency requirements exceed batch approach.
- Implement multi-region active/active deployments once tenant distribution justifies latency reductions across continents.
- Add AI-assisted merchandising or recommendation engines on top of headless APIs once "No AI for v1" constraint relaxes.
- Extend loyalty program with partner exchanges and wallet integrations, requiring new scopes and ledger event types.
- Build white-label mobile apps using same REST APIs and OAuth flows, reusing feature flag governance for staged rollout.
- Integrate marketing automation (Klaviyo, Mailchimp) through event webhooks, ensuring rate limits and privacy controls extend accordingly.
- Consider eventually splitting POS into dedicated service if hardware scaling demands separate release cadence.
- Explore usage-based billing and subscription metering once roadmap clarifies requirements beyond fixed intervals.
- Prepare data residency options by abstracting storage providers and enabling per-tenant data placement policies.
- Expand reporting APIs to support push-based webhooks when reports ready, reducing polling load.
- Evaluate event streaming (Kafka, Pulsar) once cross-team consumption outgrows database-based domain events.
- Add configurable tax inclusion/exclusion per item for VAT/GST markets when internationalization prioritized.
- Investigate marketplace or cross-store discovery features cautiously to avoid violating assumption #5 while keeping data models extensible.
- Plan for data residency controls (EU) by decoupling encryption keys and storage buckets per region.

<!-- anchor: 5-2-deeper-dives -->
### 5.2 Areas for Deeper Dive

- CI/CD Hardening: detail artifact signing, supply-chain scanning, and progressive delivery automation beyond current blue/green.
- Rate Limiting Implementation: document precise token bucket algorithms, datastore schema, and multi-pod synchronization approach.
- Address Validation Service Contracts: specify provider selection, caching, and fallback behaviors per geography.
- POS Offline Conflict Resolution: define deterministic merge rules and UI patterns for duplicated transactions.
- Digital Product Fulfillment: explore DRM integration seams, download attempt tracking, and streaming rights management.
- Multi-currency Display Rules: finalize rounding policies per currency, rate source governance, and caching expirations.
- Carrier API Governance: document credential management, onboarding UIs, and fallback hierarchies for new shipping providers.
- Feature Flag Lifecycle Automation: design tooling to auto-close stale flags, notify owners, and embed flag metadata into release notes.
- Security Threat Modeling Playbooks: provide checklists and sample outputs for modules touching payments, impersonation, media.
- Platform Analytics Expansion: specify ETL transformations, aggregation granularity, and dashboard requirements for Phase 2 metrics.
- Tenant Billing Flexibility: analyze requirements for tiered pricing, overage computation, and invoicing integrations.
- Media CDN Policy: document cache-busting, signed URL TTL, and per-tenant CDN configuration in depth.
- Disaster Recovery Automation: script full failover steps, including DNS, database promotion, and background job resumption.

<!-- anchor: 6-0-glossary -->
## 6. Glossary

- **ACME:** Automated Certificate Management Environment used for Let's Encrypt certificate issuance.
- **ADR:** Architecture Decision Record capturing deviations and rationale tied to foundation anchors.
- **C4 Diagram:** Context/Container/Component/Code diagramming style used for deployment visuals.
- **Caffeine Cache:** In-memory cache inside Quarkus pods providing per-tenant hot data without external stores.
- **CDN:** Content Delivery Network (Cloudflare) caching storefront assets and shielding ingress.
- **CDI:** Contexts and Dependency Injection, Quarkus mechanism for wiring services.
- **DelayedJob:** Database-backed job queue pattern with priority queues defined in foundation standards.
- **Domain Event:** Persisted record representing business change (e.g., ProductPublished) for projections.
- **FFmpeg:** Media transcoding tool invoked via ProcessBuilder for video/audio conversion.
- **GraalVM:** High-performance runtime generating native executables for Quarkus apps.
- **HLS:** HTTP Live Streaming format for adaptive video playback in storefronts.
- **HPA:** HorizontalPodAutoscaler, Kubernetes object scaling pods based on metrics.
- **JaCoCo:** Java code coverage tool enforcing ≥80% coverage in CI.
- **Jaeger:** Distributed tracing backend receiving OpenTelemetry spans.
- **Kustomize:** Kubernetes manifest overlay tool used per environment.
- **MyBatis Migrations:** SQL migration toolkit mandated in foundation for schema evolution.
- **Panache:** Quarkus ORM layer providing active record-style repositories with filters.
- **PlatformCommand:** Audit entity recording platform admin actions, reasons, and timestamps.
- **POS:** Point of Sale web application for in-store transactions and hardware integration.
- **PrimeUI/PrimeVue:** Component libraries for storefront and admin interactive widgets.
- **Quinoa:** Quarkus plugin managing Vite/Vue builds embedded within the backend.
- **Qute:** Quarkus templating engine powering server-rendered storefront pages.
- **R2:** Cloudflare object storage service compatible with AWS S3 APIs.
- **RLS:** Row Level Security, PostgreSQL feature enforcing per-tenant data isolation.
- **SLO:** Service Level Objective derived from module KPIs (latency, availability).
- **Spotless:** Formatting plugin ensuring consistent code style before builds.
- **Stripe Connect:** Payment platform used for merchant onboarding, charges, and payouts.
- **TenantContext:** Request-scoped bean storing tenant_id, store info, and feature flags per request.
- **Token Bucket:** Rate limiting algorithm implemented via PostgreSQL + Caffeine hybrid storage.
- **Vite:** Build tool powering Vue admin SPA bundling with HMR in dev.
- **Webhook Event Store:** Table capturing incoming/outgoing webhook payloads for idempotency.
- **Blue/Green Deployment:** Strategy running two production environments side-by-side to enable safe cutover and immediate rollback.
- **Canary Tenant:** Selected merchant receiving early feature access for validation before broad rollout.
- **Domain Events Table:** Persistent log of business state changes consumed by reporting and projections.
- **Feature Flag:** Configuration toggle stored in PostgreSQL controlling runtime behavior per tenant or plan.
- **GitHub Actions:** CI/CD system executing Maven, Vite, and deployment workflows with enforced gates.
- **K3s:** Lightweight Kubernetes distribution targeted by foundation for cluster orchestration.
- **OpenTelemetry:** Telemetry standard used to instrument Quarkus services for traces and metrics.
- **Quarkus:** Java framework powering backend runtime with native compilation support.
- **Stripe Elements:** Hosted payment UI ensuring PCI compliance without server-side card handling.
- **Tailwind CSS:** Utility-first CSS framework generating tenant-branded storefront/admin styles.
- **Tenant Access Gateway:** Request filter resolving subdomains/custom domains to tenant context before processing.
- **Vite Dev Server:** Development server powering admin SPA hot module reload and optimized builds.
