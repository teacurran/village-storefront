<!-- anchor: 3-0-proposed-architecture -->
## 3. Proposed Architecture (Operational View)

Village Storefront operates as a layered modular monolith per foundation §2.0, and the operational view describes how Quarkus services, storage, queues, and supporting systems are orchestrated to meet the SaaS, consignment, and media-heavy requirements.
Operational ownership spans platform engineering, SRE, and domain squads, with this document serving as the run rail for build, deploy, observe, and remediate workflows.
The sections below translate blueprint seams into routable services, Kubernetes primitives, and day-two patterns so on-call teams can reason about failure domains before code ships.

<!-- anchor: 3-1-operational-context -->
### 3.1 Operational Context

Village Storefront targets thousands of independent merchants, so shared infrastructure must isolate tenants while preserving economies of scale.
All public traffic routes through wildcard DNS entries that resolve to the Kubernetes ingress tier, ensuring subdomain routing policies enforce Tenant Access Gateway guarantees from foundation §4.0.
Each execution pod is stateless and compiles to GraalVM native executables to keep cold-start budgets under 100ms for scaling events and blue/green rollouts.
The operational context assumes three environments (dev, staging, prod) plus ad-hoc sandboxes, each with identical Kubernetes constructs to avoid drift.
SaaS administrators use `/admin/platform/*` endpoints to monitor stores, making platform control planes part of the same deployment while being restricted by role scopes.
External dependencies include managed PostgreSQL 17, Cloudflare R2-compatible object storage, Stripe, carrier APIs, and SMTP relays; each dependency has explicit adapters to encapsulate retries, observability, and secrets.

<!-- anchor: 3-2-service-topology -->
### 3.2 Service Topology and Workload Partitioning

Quarkus hosts multiple bounded contexts inside a single binary, but Kubernetes separates runtime concerns via dedicated Deployments and Jobs to enforce scaling envelopes per workload class.
The topology aligns to the modules enumerated in foundation §4.0 so operational alerts can be mapped back to domain owners.

<!-- anchor: 3-2-1-tenant-access-gateway -->
#### 3.2.1 Tenant Access Gateway

- Terminates TLS at the ingress controller and inspects Host headers to resolve tenant context, invoking ACME controllers for custom domains as mandated in §1.0 and §14.0.
- Publishes CDI events (`TenantResolved`, `TenantMissing`) so downstream modules subscribe without duplicating DNS parsing logic, enabling consistent logging of tenant_id and store_id.
- Enforces per-tenant rate limiting using hybrid Caffeine + PostgreSQL token buckets, with thresholds stored in the `feature_flags` table for per-plan overrides.
- Emits metrics such as resolution latency, cache hit ratio, and unknown-domain counts to the Prometheus registry for proactive alerting.
- Integrates with the platform-run DNS automation via `platform-ops` jobs that ensure wildcard certificates remain valid and revocations propagate when stores suspend.

<!-- anchor: 3-2-2-identity-session-service -->
#### 3.2.2 Identity and Session Service

- Issues JWT and refresh tokens using Quarkus OIDC extensions configured for stateless pods, storing refresh tokens and session logs in PostgreSQL partitions per §3.0 Rulebook.
- Applies row-level RLS policies to prevent cross-tenant session leakage and enforces impersonation audit capture with immutable append-only writes.
- Provides OAuth client credential issuance for headless API partners, storing hashed client secrets and scope definitions for catalog, cart, and order access.
- Runs scheduled cleanup jobs to expire old refresh tokens and archive session data older than 90 days to R2 JSONL blobs per §5.0 Data Governance.
- Integrates with Quarkus Mailer for login notifications, honoring non-production domain filtering to avoid misdirected emails during staging tests.

<!-- anchor: 3-2-3-storefront-rendering-engine -->
#### 3.2.3 Storefront Rendering Engine

- Serves all non-`/admin` routes via Qute templates backed by Tailwind-derived design tokens compiled during build as per §2 Implementation Notes.
- Injects per-tenant branding, feature flags, and inventory snapshots into render contexts using cached DTOs refreshed via domain events such as `ProductPublished`.
- Integrates PrimeUI components for dynamic cart panels and checkout interactions while keeping hydration bundles under the 120KB gzipped KPI from §4 Component KPIs.
- Handles CDN-safe asset URLs by referencing Cloudflare R2 paths with signed URLs generated via the Media Pipeline Controller.
- Provides server-side caching hints and ETag headers so Cloudflare CDN can cache storefront pages without compromising personalized content.

<!-- anchor: 3-2-4-admin-spa-delivery -->
#### 3.2.4 Admin SPA Delivery Service

- Serves Vue 3 assets produced by Quinoa builds, storing hashed bundles inside the Quarkus classpath for immutable serving.
- Supplies bootstrapped tenant metadata, feature flag states, RBAC scopes, and API base URLs via a `/admin/bootstrap` endpoint so the SPA can initialize offline-friendly caches.
- Enforces Tailwind token parity with storefront by fetching the same design token JSON during build steps, ensuring consistent typography and spacing.
- Emits structured logs per SPA request tagging the acting staff roles to facilitate forensics, especially when impersonation contexts apply.
- Implements caching headers that differentiate between static assets (long TTL) and bootstrap payloads (short TTL) to prevent stale configuration.

<!-- anchor: 3-2-5-catalog-inventory-module -->
#### 3.2.5 Catalog and Inventory Module

- Owns CRUD APIs for products, variants, categories, collections, and inventory locations, exposing spec-first OpenAPI definitions per §5 Contract Patterns.
- Applies Panache filters for tenant isolation and MapStruct DTO mappers to prevent leakage of internal fields such as cost basis and audit metadata.
- Handles bulk import/export pipelines via database-backed jobs, respecting throughput targets (5k products/minute) from §4 Component KPIs by batching SQL operations.
- Emits domain events (`InventoryAdjusted`, `ProductScheduled`) recorded in the `domain_events` table, enabling downstream projections without direct table joins.
- Collaborates with Consignment module strictly via service interfaces, ensuring consignor-specific attributes do not pollute catalog schemas.

<!-- anchor: 3-2-6-consignment-module -->
#### 3.2.6 Consignment Module

- Tracks consignor onboarding, commission rules, batch intake flows, and automated payouts using Stripe Connect Express accounts as defined in §5 and §8.
- Enforces PII encryption for tax identifiers by leveraging pgcrypto functions with key version logging to satisfy §5 Data Governance.
- Provides consignor portal APIs gated by scoped tokens so vendors can review balances, statements, and notifications without entering the admin SPA.
- Generates payout statements via scheduled jobs that coordinate Payment Provider calls, ledger entries, and email notifications with audit logs referencing ticket numbers.
- Interfaces with Inventory module through variant IDs only, preventing direct writes that could cause double counting or bypass RLS.

<!-- anchor: 3-2-7-checkout-order-orchestrator -->
#### 3.2.7 Checkout and Order Orchestrator

- Handles persistent cart storage, shipping address validation, rate shopping, payment intent creation, and order lifecycle transitions per §7 requirements.
- Implements saga-style orchestration with compensating actions recorded in audit tables to recover from partial failure (e.g., shipping label creation fails after payment capture).
- Utilizes feature flags to gate advanced flows (gift cards, loyalty) ensuring new capabilities can release dark per §3 Rulebook.
- Coordinates with Payment Provider, Loyalty, and Inventory modules via CDI interfaces, ensuring future provider additions do not require cross-cutting rewrites.
- Provides webhook handlers for Stripe events, ensuring idempotency via stored event IDs and verifying signatures before processing.

<!-- anchor: 3-2-8-payment-integration-layer -->
#### 3.2.8 Payment Integration Layer

- Implements the `PaymentProvider`, `PaymentMethodProvider`, `MarketplaceProvider`, and `WebhookHandler` interfaces described in §8, with Stripe as the primary implementation.
- Stores platform fee configuration, onboarding state, and payout schedules per tenant, enabling platform revenue tracking in platform admin dashboards.
- Utilizes dedicated worker queues for payout reconciliation, dispute handling, and refund processing, ensuring each job emits metrics for status, latency, and failure counts.
- Encrypts provider secrets using Kubernetes Secrets mounted as files and rotated quarterly per §3 Operational Guardrails.
- Provides dummy provider implementations used exclusively in automated tests to validate interface contracts before onboarding new processors.

<!-- anchor: 3-2-9-loyalty-pos-media -->
#### 3.2.9 Loyalty, POS, and Media Workloads

- Loyalty module maintains ledger entries, nightly tier recalculation jobs, and customer account APIs for viewing point balances, referencing §9 KPIs.
- POS module ships as part of the admin SPA but relies on checkout APIs for transaction persistence, including offline batch uploads signed and encrypted per §5 Contract Patterns.
- Media Pipeline Controller handles upload requests, variant generation, FFmpeg video transcodes, poster extraction, and metadata persistence per §3.0 Technical Architecture decisions.
- Background Scheduler module coordinates certificate renewals, archival jobs, queue compaction, and reporting ETL, tagging each job with owning module metadata for runbook clarity.
- Reporting Projection Service consumes domain events and builds read-optimized aggregates powering storefront snippets, admin dashboards, and platform analytics.

<!-- anchor: 3-3-environment-segmentation -->
### 3.3 Environment Segmentation and Access Controls

- **Dev Environment:** Runs on a smaller k3s cluster with ephemeral PostgreSQL to validate migrations and integration tests; access restricted to engineers with MFA-enabled VPN. Feature flags default to disabled state so new work is opt-in.
- **Staging Environment:** Mirrors production topology with anonymized tenant data subsets restored nightly; used for performance tests, blue/green rehearsals, and security scans. Stripe, carrier, and email credentials point to sandbox accounts.
- **Production Environment:** Spans multi-node k3s cluster across at least three worker nodes, each pinned to separate availability zones when the hosting provider supports it. Managed PostgreSQL 17 cluster provides HA via synchronous replica.
- **Sandboxes:** Created per spike or partner integration; automatically expire after a set TTL enforced by `platform-ops` jobs to prevent drift. Sandboxes cannot connect to production object storage to avoid leakage.
- **Access Management:** Kubernetes RBAC ties into SSO, ensuring on-call engineers can impersonate service accounts for debugging only during approved maintenance windows. Database credentials rotate quarterly and are distributed through sealed secrets.
- **Configuration Management:** Quarkus `%dev`, `%test`, `%prod` profiles align with ConfigMaps and Secrets per §2 Implementation Notes. All sensitive material (Stripe keys, SMTP passwords) lives in Kubernetes Secrets encrypted at rest.
- **Network Segmentation:** Only ingress controllers expose public endpoints; worker nodes restrict egress to approved destinations (Stripe, USPS/UPS/FedEx APIs, SES/SMTP, R2) using network policies documented in `platform-ops`.
- **Audit Logging:** Access to environments is logged centrally; `PlatformCommand` entities capture manual interventions (e.g., force-suspending a tenant) with reason codes referencing tickets.

<!-- anchor: 3-4-tenant-routing-domain-ops -->
### 3.4 Tenant Routing, Domain Management, and SSL Automation

- Wildcard DNS `*.{platform-domain}.com` points to the ingress controller, enabling automatic tenant bootstrap when a merchant claims a subdomain via the admin UI.
- Custom domains require merchants to publish DNS validation records; an ACME HTTP-01 controller running in the cluster completes certificate issuance and stores results in Kubernetes Secrets with annotations for renewal windows.
- Tenant Access Gateway caches subdomain-to-tenant mappings in Caffeine with eviction policies tuned for millions of tenants; fallback queries use PostgreSQL indexes on `subdomain` and `custom_domains`.
- Store suspension flips a feature flag that denies all storefront requests and stops background jobs for that tenant. DNS remains intact but requests respond with branded suspension page referencing support instructions.
- Domain verification and SSL renewal failures route to the `platform-ops` queue, generating alerts before certificates expire. Runbooks provide `kubectl` commands to re-trigger ACME challenges safely.
- Public endpoints enforce HTTPS via ingress annotations; HTTP requests redirect to HTTPS to align with security posture from §3 Rulebook.
- Tenant-specific CORS policies are derived from the set of allowed domains/subdomains and exported to both Quarkus and Cloudflare worker configurations, ensuring headless integrations operate without manual whitelisting.
- CDN caching leverages hashed asset filenames; when merchants update brand assets, the Media Pipeline generates new keys, invalidating cached versions without manual cache purges.

<!-- anchor: 3-5-data-operations -->
### 3.5 Data Operations, Schema Management, and Lifecycle

- MyBatis migrations define schema changes, RLS policies, and seed data, and run as part of GitHub Actions plus per-environment jobs before application rollouts to honor §3 Release Management.
- Migration scripts are timestamped and reversible where possible; destructive operations require change approval referencing foundation §1 directives.
- Tenant-id columns exist on all tables; `created_by` and `updated_by` track actor metadata, satisfying §5 Data Governance.
- Partitioned tables (`session_logs`, `audit_events`, `webhook_events`) rotate monthly; scheduled jobs create future partitions, archive data older than 90 days to R2, and prune old partitions only after verifying archives.
- Archival pipeline writes JSONL + manifest files to R2 with server-side encryption, storing metadata about schema version and checksum for reproducibility.
- Data exports for merchants bundle JSONL plus CSV summaries zipped with hashed filenames; download URLs expire after 72 hours and require signed URLs tied to the requesting tenant_id.
- PII encryption uses pgcrypto columns with key rotation tracked in metadata tables; application code logs key version per encrypt/decrypt event to expedite incident investigations.
- Rate limit counters, feature flag overrides, and token buckets reside in PostgreSQL tables with indexes tuned for heavy writes, ensuring operations remain consistent without Redis.
- Backup strategy includes daily base backups, hourly WAL shipping, and quarterly DR drills restoring into isolated clusters to test point-in-time recovery.

<!-- anchor: 3-6-background-processing -->
### 3.6 Background Processing, Media Workloads, and Job Governance

- DelayedJob tables handle asynchronous work for emails, media processing, payouts, reporting, and certificate renewal per §2 Standard Kit; workers claim jobs using row-level locking with exponential backoff.
- Queues are differentiated by priority (CRITICAL, HIGH, DEFAULT, LOW, BULK) and each worker Deployment can scale independently via HPAs watching queue depth metrics exported to Prometheus.
- Image processing jobs execute in-process when under 30s; heavier workloads (video transcoding, HLS packaging) spawn FFmpeg sub-processes with CPU/memory limits enforced via Kubernetes container settings.
- Job payloads are JSON structures with version numbers to prevent schema drift; the worker validates version compatibility before processing and logs mismatches as severity-one incidents.
- Retry logic tracks attempts and last_error columns; after exceeding thresholds, jobs move to a `dead_jobs` table requiring manual inspection with runbooks referencing relevant feature owners.
- Media uploads rely on presigned URLs; upon completion, a job verifies checksums, stores metadata, and triggers variant generation. Failures leave the original asset intact for manual reprocessing.
- Offline POS batches upload encrypted payloads to `/pos/offline/batches`, which enqueues reconciliation jobs to replay transactions sequentially; duplicates are prevented via stored UUIDs.
- Reporting exports enqueued via `/reports/{reportType}` use dedicated worker pools tuned for I/O heavy workloads to avoid starving transactional queues.
- Stripe webhook handling uses separate worker pods to ensure high availability even when checkout traffic surges; webhook payloads persist before processing to support replay.

<!-- anchor: 3-7-observability -->
### 3.7 Observability Fabric, Telemetry, and Runbook Integration

- Structured JSON logging includes tenant_id, store_id, user_id, session_id, correlation_id, and impersonation context per §3 Rulebook; logs stream to centralized collectors with retention aligned to compliance.
- OpenTelemetry instrumentation wraps REST endpoints, background jobs, FFmpeg calls, and external HTTP clients, exporting traces to Jaeger with service names representing bounded contexts.
- Prometheus scrapes `/q/metrics`, collecting JVM metrics (even though native), queue depth, job latency, HTTP latency, and custom counters for tenant resolution outcomes.
- Alerting rules map to component KPIs (§4 Component KPIs); e.g., Checkout 95th percentile >300ms triggers warnings, Media queue depth >100 for 5 minutes triggers scaling events.
- Grafana dashboards correlate tenant-specific errors with release versions and feature flag states, enabling quick identification of regressions tied to specific cohorts.
- Correlation IDs propagate through HTTP headers (`X-Request-ID`) and background job metadata, ensuring cross-service tracing even when requests pass through CDN caches.
- On-call runbooks link to dashboards, log queries, and feature flag toggles; each runbook references anchor IDs from the foundation for traceability.
- Synthetic monitors run against staging and production using canary tenants to test login, storefront rendering, checkout, and admin flows every 5 minutes, capturing screenshot evidence for SLA reporting.

<!-- anchor: 3-8-cross-cutting -->
### 3.8 Cross-Cutting Concerns

<!-- anchor: 3-8-1-authentication-authorization -->
#### 3.8.1 Authentication & Authorization

- JWT access tokens issued by Identity Service expire quickly (15 minutes) and embed tenant_id plus role scopes; refresh tokens stored server-side with revocation timestamps.
- Admin SPA obtains tokens via OAuth flows, while storefront uses password, guest checkout, or social login depending on feature flags; all flows rely on bcrypt hashed passwords (work factor 12).
- RBAC policies stored centrally define roles (Owner, Admin, Manager, Staff, Customer, Platform Super-User) mapped to permission scopes; controllers use declarative annotations referencing these scopes.
- Impersonation events require reason codes and create visual indicators; all impersonation actions log to immutable audit tables with correlation to platform admin user_id.
- Headless API access uses OAuth client credentials with scopes like `catalog:read` and `orders:write`; rate limiting applied per client_id and tenant combination.

<!-- anchor: 3-8-2-logging-monitoring -->
#### 3.8.2 Logging & Monitoring

- Logs follow JSON format with standardized field names, enabling Kibana/Loki queries; sensitive payloads (PII, payment data) redacted before logging.
- Metrics collected via Prometheus include HTTP latency histograms, database connection pool usage, feature flag overrides, queue depths, and tenant resolution stats.
- Alert routing integrates with PagerDuty; severity thresholds align with component KPIs, ensuring teams respond proportionally to impact.
- Jaeger traces capture spans for template rendering, database queries, external API calls, and background job execution, highlighting hotspots for optimization.
- Health endpoints `/q/health/live` and `/q/health/ready` integrate with Kubernetes probes, surfacing dependency readiness (database, object storage, FFmpeg availability) prior to accepting traffic.

<!-- anchor: 3-8-3-security-considerations -->
#### 3.8.3 Security Considerations

- HTTPS enforced at ingress with automatic redirect from HTTP; TLS certificates managed via Let's Encrypt automation and rotated before expiry.
- Secrets stored in Kubernetes Secrets encrypted by the cluster; GitHub Actions uses OIDC to fetch short-lived tokens for deployments, reducing static secret sprawl.
- Input validation performed via Bean Validation and custom sanitizers; Qute auto-escaping prevents XSS, and CSRF tokens protect storefront forms.
- Webhooks validated via HMAC signatures; event payloads stored before processing to support forensic replay.
- Database connections restricted to least-privilege roles; background workers run under service accounts limited to necessary tables.

<!-- anchor: 3-8-4-scalability-performance -->
#### 3.8.4 Scalability & Performance

- Stateless Quarkus pods scale horizontally via HPAs triggered by CPU, memory, and custom metrics (request rate, queue depth), ensuring multi-tenant load can spike without saturating pods.
- GraalVM native executables reduce memory footprint (~150MB per pod) and cold start time (<100ms) enabling aggressive auto-scaling and blue/green swaps.
- Caffeine caches hot data (tenant resolution, product fragments, shipping rate responses) per §3 Rulebook, while PostgreSQL indexes optimized for `tenant_id` ensure query performance.
- Shipping rates cached for 15 minutes; rate service uses asynchronous refresh to preemptively update hotspots during peak seasons.
- Asset delivery optimized by storing multiple image variants and HLS playlists; storefront requests pick the smallest variant required, reducing bandwidth and improving TTFB.

<!-- anchor: 3-8-5-reliability-availability -->
#### 3.8.5 Reliability & Availability

- Multi-pod deployments across at least three nodes prevent single-node failure from causing downtime; PodDisruptionBudgets maintain minimum pod counts during maintenance.
- PostgreSQL HA achieved via managed service with synchronous replica; read replicas considered for reporting workloads to offload OLTP traffic.
- Background job workers distributed across nodes; queue leasing ensures if one worker dies, jobs unlock after timeout and other pods resume work.
- Blue/green deployments allow safe rollbacks; the previous version remains deployed until health checks pass and smoke tests succeed.
- Disaster recovery relies on daily backups plus hourly WAL shipping; DR drills include failover to new cluster, rehydration of R2 archives, and DNS updates.

<!-- anchor: 3-9-deployment-view -->
### 3.9 Deployment View

<!-- anchor: 3-9-1-target-environment -->
#### 3.9.1 Target Environment

Village Storefront targets Kubernetes (k3s) clusters running in a cloud-neutral footprint optimized for Cloudflare R2 storage, Stripe integrations, and managed PostgreSQL as mandated by foundation §2.0 and §Deployment Architecture.
Clusters run containerd, Calico networking, and cert-manager for ACME automation, with GitHub Actions delivering manifests via kubectl apply mediated by blue/green overlays.

<!-- anchor: 3-9-2-deployment-strategy -->
#### 3.9.2 Deployment Strategy

- Maven builds Quarkus native executables within GitHub Actions using GraalVM; resulting binaries are packaged into minimal distroless images (<100MB).
- Quarkus Kubernetes extension generates base manifests committed to the repo; Kustomize overlays per environment add secrets, resource limits, HPAs, and ingress specifics.
- CI pipeline enforces Spotless, JaCoCo ≥80%, unit/integration tests, frontend lint/tests, and native image builds before publishing images to the registry.
- Deployments use blue/green strategy: new ReplicaSets spin up alongside existing pods, readiness probes ensure health, smoke tests run, and traffic switches via ingress annotations or service label flips.
- Database migrations run as Kubernetes Jobs before switching traffic; down scripts prepared for rollback scenarios with feature flag toggles ready to disable new behavior.
- Feature flags gate new modules, enabling canary tenants; platform admin UI includes toggles for tenant enrollment, ensuring release managers can manage progressive rollouts.

<!-- anchor: 3-9-3-deployment-diagram -->
#### 3.9.3 Deployment Diagram (PlantUML)

```plantuml
@startuml
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Deployment.puml

LAYOUT_WITH_LEGEND()

Deployment_Node(k3s,"k3s Cluster","Quarkus Pods, cert-manager, Prometheus") {
  Deployment_Node(ingress,"Ingress Tier","NGINX Ingress + cert-manager") {
    Container_Boundary(gateway,"Tenant Access Gateway Pods") {
      Container(tenantPods,"Quarkus Gateway Pods","Java 21 + GraalVM","Host TenantFilter, Qute storefront routes")
    }
  }
  Deployment_Node(app,"Application Pods","Quarkus Native Deployments") {
    Container(catalog,"Catalog Pods","Quarkus","Catalog, Inventory, Consignment APIs")
    Container(checkout,"Checkout Pods","Quarkus","Checkout, Orders, Payments")
    Container(admin,"Admin SPA Delivery","Quarkus","Serves Vue assets, admin bootstrap")
    Container(workers,"Background Workers","Quarkus Jobs","DelayedJob executors, FFmpeg workers")
  }
  Deployment_Node(observability,"Observability Stack","Prometheus, Grafana, Jaeger") {
    Container(prom,"Prometheus","Metrics","Scrapes /q/metrics")
    Container(jaeger,"Jaeger","Tracing","Collects OpenTelemetry spans")
  }
}

Deployment_Node(db,"Managed PostgreSQL 17","HA Cluster") {
  ContainerDb(pg,"PostgreSQL","Row level security, partitions")
}

Deployment_Node(storage,"Cloudflare R2","S3-compatible object storage") {
  Container(media,"Media Buckets","Images, Video Variants")
}

Deployment_Node(stripe,"Stripe Connect","External Service") {
  Container(stripeApi,"Stripe APIs","Payments, Payouts")
}

Rel(tenantPods,catalog,"Tenant context events, API calls","mTLS")
Rel(catalog,pg,"Panache queries with tenant_id filters","JDBC")
Rel(checkout,stripeApi,"Payment intents, payouts","HTTPS")
Rel(workers,media,"Upload processed variants","S3 API")
Rel(app,prom,"Expose metrics","HTTP")
Rel(app,jaeger,"Export traces","gRPC/HTTP")
Rel(tenantPods,storage,"Signed URL generation","HTTPS")
@enduml
```

<!-- anchor: 3-10-operational-runbooks -->
### 3.10 Operational Runbooks and Incident Response

- **Tenant Isolation Breach Suspected:** Immediately toggle emergency feature flag `checkout.kill-switch` and `impersonation.disable` via platform admin UI, capture relevant audit IDs, and run SQL scripts to verify RLS policies remain intact.
- **Media Queue Backlog:** Scale worker Deployment `media-workers` by +3 replicas, verify FFmpeg readiness via `/q/health`, inspect `dead_jobs` for repeated failures, and consider temporarily disabling new uploads via feature flag if backlog exceeds SLA.
- **Carrier API Outage:** Enable fallback rate tables per tenant using configuration overrides, notify merchants via status page, and queue re-rating jobs to reconcile shipments once carriers recover.
- **Stripe Webhook Failures:** Inspect `webhook_events` table for repeated error codes, requeue stuck events via runbook script, and confirm Stripe dashboard sees 200 responses; enable test provider in staging to replicate payload.
- **Database Failover:** Initiate managed PostgreSQL failover, monitor application reconnection attempts, and validate read-side caches flush after failover to avoid stale data; run smoke tests across storefront and admin flows.
- **Background Job Poison Pill:** If a malformed payload causes repeated worker crashes, use `paused_jobs` feature in the queue management admin screen to isolate job IDs, patch payload or code, and redeploy before resuming.
- **Custom Domain SSL Expiry:** Use cert-manager CLI to force renew certificate, inspect ingress annotations, and confirm Cloudflare DNS entries still point to ingress IP; inform tenant and log platform command entry.
- **POS Offline Discrepancy:** Retrieve offline batch records, replay transactions in dry run mode, compare totals, and escalate to finance if cash drawer discrepancies appear; audit logs for staff actions provide accountability.

<!-- anchor: 3-11-security-compliance -->
### 3.11 Security, Compliance, and Governance Controls

- Feature flags recorded with owners, expiry dates, and review cadences; long-lived flags require conversion into configuration defaults per §3 Enforcement Playbooks.
- Threat modeling required for payments, impersonation, media uploads, and POS modules before GA; outputs create backlog items with assigned owners and due dates.
- PCI scope minimized by never handling raw card data and relying on Stripe Elements/Connect; compliance documentation stored in `platform-ops` repo with evidence of quarterly reviews.
- Secrets rotation tracked via calendar tasks; automation uses GitHub Actions to trigger rotation jobs and ensures new secrets deployed before revoking old ones.
- Audit logs (including PlatformCommand, impersonation events, payout approvals) treated as write-once; any issue writing to audit tables triggers deployment rollback.
- Data retention policies implemented via scheduled jobs; PII soft-deleted first, then hard-deleted after retention timer expires, with manifest stored in R2 for compliance queries.
- Privacy requests (export/delete) executed via admin UI that queues background jobs; exports zipped and made available via signed URL with 72-hour expiry.
- Incident response processes require correlation IDs in status updates; runbooks instruct teams to anonymize tenant-specific details unless NDAs permit disclosure.

<!-- anchor: 3-12-kpi-telemetry -->
### 3.12 KPI Tracking and Telemetry-Driven Feedback Loops

- Tenant Access Gateway monitors SLA 99.99% with metrics for resolution latency, cache hit rate, and misclassification count; dashboards highlight top tenants by traffic for load forecasting.
- Checkout orchestrator tracks success rate, 95th percentile latency, and failure categories (payment, inventory, shipping); alerts trigger if any category exceeds 2% failure for 5 minutes.
- Media pipeline metrics include job throughput, variant generation time, FFmpeg failure counts, and storage usage per tenant; data feeds capacity planning for object storage.
- Reporting service logs job queue time, execution time, and row counts, enabling operations to tune worker capacity before sale events.
- Loyalty module tracks points accrual latency, nightly tier recalculation duration, and queue backlog, ensuring promotional campaigns do not lag behind purchases.
- POS offline processor measures queue size per device, replay latency after reconnect, and duplicate prevention hits; anomalies escalate to store support teams.
- Platform admin backend records impersonation events per agent, time spent in impersonation, and commands executed; compliance teams review weekly.
- Observability stack includes SLO error budgets; if budgets burn beyond thresholds, teams must pause feature rollouts to invest in reliability per §1 Extended Directives.

<!-- anchor: 3-13-operational-backlog -->
### 3.13 Operational Backlog and Continuous Improvement Themes

Operational excellence requires steady investment; the following themed backlog items keep the platform aligned with foundation guardrails while preparing for future phases.

<!-- anchor: 3-13-1-hardening-checklist -->
#### 3.13.1 Hardening Checklist

- Conduct quarterly chaos tests that disable one PostgreSQL replica to validate application retry logic and connection pooling behavior.
- Expand ACME automation to support DNS-01 challenges for tenants using providers that block HTTP-01, ensuring global reach.
- Implement per-tenant anomaly detection on login activity leveraging existing session logs to highlight suspicious IP or device fingerprints.
- Add automated scanning of FFmpeg binaries to ensure no CVEs remain unpatched between releases.
- Encrypt offline POS cache storage on devices using platform-managed keys and rotate them annually.
- Build automated tests verifying Row Level Security policies exist for every tenant-scoped table defined in MyBatis migrations.
- Introduce rate limiting analytics dashboards to confirm token bucket parameters produce expected rejection patterns during brute force attempts.
- Harden webhook endpoints by adding payload size limits and circuit breakers to stop cascading failures during provider outages.
- Add Kubernetes Pod Security Standards enforcement to restrict privileged containers and hostPath mounts.
- Validate Stripe Connect onboarding flows monthly to ensure webhook endpoints remain registered after provider-side changes.
- Automate TLS cipher suite verification for ingress controllers to detect regressions after upgrades.
- Introduce spare worker pools dedicated to handling regulatory export/delete jobs so user-facing queues remain responsive.
- Create synthetic media uploads that run nightly to confirm FFmpeg path health and storage permissions.
- Add guardrails for background job code to require idempotency tests in CI before merging.
- Document fallback playbooks for shipping carriers beyond USPS/UPS/FedEx to accelerate future onboarding.

<!-- anchor: 3-13-2-observability-roadmap -->
#### 3.13.2 Observability Enhancements

- Implement trace-level sampling configuration that increases sample rate automatically when error rates spike.
- Correlate Prometheus metrics with feature flag states by labeling metrics with `feature=on/off` to highlight performance impact of new features.
- Extend logging pipeline to redact structured JSON keys defined in a central registry, preventing drift across modules.
- Add business-level dashboards showing platform revenue, consignment payouts, and loyalty redemption rates with freshness timestamps.
- Integrate Grafana alerts with status page automation so merchant-facing comms update when major components degrade.
- Capture client-side metrics from storefront and admin SPAs (TTFB, CLS, JS errors) and pipe into observability stack for holistic insight.
- Build automated validation ensuring `/q/health` reports include dependency-specific messages, aiding triage.
- Instrument MyBatis migrations with telemetry to record duration and locking behavior for future optimizations.
- Add trace attributes for tenant plan tier to correlate performance issues with plan entitlements.
- Provide runbook search integration within Grafana so alerts link directly to remediation steps.

<!-- anchor: 3-13-3-automation-goals -->
#### 3.13.3 Automation and Tooling Goals

- Deliver CLI tooling for platform admins to script tenant onboarding, plan changes, and suspension workflows via audited commands.
- Automate generation of Tailwind design token bundles per environment, ensuring build agents fetch tokens securely and purge after use.
- Build GitHub Actions that pre-warm GraalVM native image caches to reduce CI duration and encourage frequent deployments.
- Create configuration drift detection comparing desired Kustomize overlays with live cluster state, raising alerts when discrepancies arise.
- Automate background job scaling decisions by feeding queue metrics into custom controllers that adjust worker replicas proactively.
- Provide `kubectl` plugins that annotate running pods with tenant load distribution, aiding capacity planning.
- Implement secret scanning on build artifacts to ensure no credentials accidentally embedded before publishing images.
- Build pipeline step that validates PlantUML diagrams render successfully and remain in sync with textual descriptions.
- Provide SSO-integrated access to reporting exports so teams avoid manual credential sharing.
- Auto-generate monthly compliance packets summarizing audit log counts, secrets rotations, and DR drill outcomes.

<!-- anchor: 3-13-4-tenant-safety-validations -->
#### 3.13.4 Tenant Safety Validations

- Run weekly queries verifying no cross-tenant data exists by checking random samples for mismatched tenant_id and store_id relationships.
- Simulate tenant suspension/resumption flows in staging every sprint to validate automation scripts and ensure no manual drift occurs.
- Validate feature flag overrides expire on schedule by running nightly jobs that flag stale overrides for review.
- Ensure consignment payout statements always reconcile with Stripe transfers by reconciling ledger entries daily.
- Test custom domain onboarding with newly registered DNS providers to catch UI assumptions early.
- Run synthetic headless API calls using OAuth clients to confirm rate limiting and scope enforcement behave as expected.
- Validate archival pipeline by restoring random JSONL blobs into scratch databases and comparing row counts.
- Monitor upload size throttles by enforcing per-tenant quotas and alerting when stores approach limits, enabling proactive upsell or data hygiene conversations.
- Review CORS whitelist entries monthly to remove unused domains and reduce attack surface.
- Audit background job payload metadata to ensure no plaintext sensitive data persists beyond job execution.

<!-- anchor: 3-14-multi-region-readiness -->
### 3.14 Multi-Region Expansion Readiness

- Current release deploys to a single region but keeps stateful dependencies abstracted behind adapters so additional regions can be added without code rewrites.
- DNS hosted zones already support latency-based routing; once additional clusters spin up, traffic can be steered geographically while keeping tenant affinity.
- PostgreSQL read replicas in secondary regions operate in async mode initially; plan includes eventual logical replication with conflict detection for read-heavy workloads like reporting.
- Object storage leverages Cloudflare R2's regional replication; bucket naming conventions reserve suffixes for prospective regions (`storefront-media-us`, `storefront-media-eu`).
- Background jobs referencing tenants include region metadata so operations can selectively pause jobs in specific regions during incidents.
- CI/CD pipeline parameterized to deploy to multiple clusters sequentially, running smoke tests per region and failing fast if any target fails.
- Observability stack aggregates metrics from all regions but labels each data point with region identifiers to facilitate blast radius analysis.
- Disaster recovery documentation extends to cross-region failover once traffic splits, requiring database promotion scripts and DNS TTL tuning.

<!-- anchor: 3-15-operational-personas -->
### 3.15 Operational Personas and Responsibilities

- **Platform SRE:** Owns Kubernetes health, ingress, TLS, and CI/CD automation; maintains runbooks and ensures HPAs tuned for demand surges.
- **Domain Engineers:** Manage module-specific alerts (catalog, checkout, consignment, loyalty) and coordinate feature flag rollouts with product leads.
- **Security Engineering:** Oversees secrets management, threat modeling schedules, and incident response for auth, impersonation, and payment flows.
- **Data Operations:** Handles MyBatis migrations, partition management, archival workflows, and privacy request tooling.
- **Support & Success:** Uses platform admin dashboards to monitor store status, impersonate for troubleshooting, and communicate via status page.
- **Media Operations:** Ensures FFmpeg dependencies patched, monitors storage usage, and manages video transcode SLA commitments.
- **Finance & Compliance:** Reviews payout reconciliation, audit logs, and quarterly compliance packets, raising change requests when regulations shift.
- **Release Management:** Coordinates blue/green deployments, tracks canary tenant behavior, and enforces freeze windows before major retail events.

<!-- anchor: 3-16-module-slo-scorecards -->
### 3.16 Module SLO Scorecards

- **Tenant Access Gateway:** Target 99.99% uptime, median resolution <10ms, misclassification <0.1%; error budget burn triggers synthetic test expansion.
- **Identity Service:** Token issuance median <50ms, refresh success >99%, audit log write latency <1s; SLO breach halts auth feature work.
- **Storefront Rendering:** Cold render <150ms, hydration bundle <120KB, template errors <0.1%; outliers require caching review and template diffing.
- **Admin SPA Delivery:** Initial load <2MB, bootstrap API latency <150ms, error-free route transitions >99.5%; tracked during release cycles.
- **Catalog & Inventory:** Bulk import throughput ≥5k/min, variant update API <200ms, job backlog <10 minutes; ensures merchants manage catalogs efficiently.
- **Consignment:** Intake transactions <500ms for 100 items, payout closure <5 minutes, notification latency <2 minutes; ensures consignor trust.
- **Checkout & Orders:** 95th percentile <800ms, payment success >98%, refund turnaround <5 minutes; critical for revenue flow.
- **Payment Layer:** Webhook acknowledgment <1s, payout reconciliation zero unresolved for >24h, dispute handling initiated within 1h.
- **Loyalty:** Points accrual <20ms overhead, nightly tier recalculation <10 minutes, ledger discrepancies zero per audit cycle.
- **POS:** Offline queue flush <60s, device heartbeat <5 minutes, cash drawer variance <0.2%; ensures physical store reliability.
- **Media Pipeline:** Image variant success >99.5%, video transcode queue depth <100, manifest generation <2 minutes per asset.
- **Reporting:** Export queue time <5 minutes, generation throughput 50k rows/min, cache freshness indicator <15 minutes old.
- **Platform Admin:** Global store list API <300ms, impersonation log replication <30s, system health widget updates every 60s.
- **Integration Adapters:** Retry success ratio >95%, timeout budget <1%, fallback invocation logged 100% of time.

<!-- anchor: 3-17-operational-data-flow -->
### 3.17 Operational Data Flow Narrative

Customer requests enter through CDN and ingress, hit the Tenant Access Gateway, and once tenant context loads, route to either storefront renderers, admin SPA bootstrappers, or API controllers.
Each controller uses Panache repositories filtered by tenant_id, ensuring RLS enforcement before data leaves PostgreSQL.
Background jobs triggered by user actions (orders, media uploads, payouts) persist payloads, emit metrics, and run asynchronously, keeping foreground latency targets intact.
Observability signals (logs, metrics, traces) flow to centralized stacks, enabling rapid detection of anomalies and correlation with feature flags or deployments.
Disaster recovery artifacts (database backups, R2 archives) replicate off-cluster, giving the operations team confidence in restore plans aligned with §3 Operational Guardrails.

<!-- anchor: 3-18-conclusion -->
### 3.18 Conclusion

This operational architecture enforces the foundation mandates around modular seams, tenant isolation, and deliberate rollout discipline.
By anchoring every concern—authentication, logging, security, scalability, reliability—into concrete Kubernetes and PostgreSQL patterns, Village Storefront stays ready for Phase 2/3 expansions without rework.
Runbooks, observability, and automation themes ensure platform teams can evolve safely while keeping merchants online and confident in the SaaS platform.
