<!-- anchor: verification-and-integration-strategy -->
## 6. Verification and Integration Strategy

- **Testing Levels:**
  - *Unit Tests:* Every module (tenant, identity, catalog, consignment, loyalty, POS, payments) maintains ≥80% branch coverage enforced via JaCoCo; unit suites must stub TenantContext + feature flags to avoid cross-tenant leakage; calculators (commission, loyalty, FX) require deterministic fixture-based tests.
  - *Integration Tests:* Quarkus devservices PostgreSQL with RLS enabled validates REST endpoints, migrations, and repository filters; headless + admin flows simulate JWT + OAuth scopes; FFmpeg wrappers tested with lightweight fixtures verifying command invocation; Stripe/carrier adapters use mocked servers recorded in `tests/integration/mocks/`.
  - *End-to-End & Acceptance:* Iterations I3–I5 add e2e suites (REST + SPA + POS) orchestrated via Playwright or Cypress hitting deployed dev cluster; flows include storefront checkout, admin consignment payout, media upload, loyalty redemption, POS offline queue replay, platform impersonation.
  - *Performance/Baseline:* Iteration I5 introduces JMeter/Gatling scripts for checkout, storefront SSR, admin dashboards, POS offline flush, ensuring KPIs (checkout <800ms P95, storefront <2s TTFB, POS flush <60s) prior to GA; Perf results stored under `docs/operational/perf-readout.md`.
  - *Security Testing:* Static analysis (SpotBugs, ESLint security plugin) plus OWASP ZAP smoke tests against staging; impersonation flow receives targeted threat modeling (I4) and manual pen-test scenarios (invalid reason codes, token replay); FFmpeg command input sanitized via tests verifying argument escaping.
- **CI/CD Workflow:**
  - GitHub Actions pipeline includes matrix for JVM + Native builds, Quinoa lint/tests, OpenAPI validation, PlantUML + Mermaid rendering checks (`make diagrams:test`), and MyBatis migration dry run; gating steps run Spotless/JaCoCo per iteration tasks; caching ensures Quinoa + Maven reproducibility.
  - Post-build steps publish OpenAPI specs, diagrams, and Kubernetes manifests as artifacts; subsequent deploy job applies Kustomize overlays to k3s dev/staging clusters, runs smoke tests (`ops/scripts/smoke.sh` hitting `/q/health` + checkout sample), and updates release notes.
  - Canary + blue/green strategy: staging receives release candidate, synthetic monitors verify tenant flows, then production overlay flips Service selector; feature flags guard high-risk modules allowing rollback by toggling key.
  - CI enforces ADR linkage by running custom script checking new modules reference Section 4 directives; failure blocks merge until references provided.
- **Quality Gates:**
  - Spotless formatting, ESLint (JS/TS), Stylelint (Tailwind), and Prettier for UI code; `mvn verify` aborts if formatting drift occurs.
  - JaCoCo coverage ≥80% globally and ≥75% per module; CLI enforces `jacoco:check` rules referencing plan KPIs.
  - OpenAPI + Async artifact validation ensures specs compile; PlantUML + Mermaid lint prevents broken diagrams; ADR lints confirm template compliance.
  - Dependency scanning (OWASP DependencyCheck + npm audit) integrated into CI; failing vulnerabilities require documented suppression referencing security review.
- **Artifact Validation:**
  - Diagrams validated via `make diagrams:check` running PlantUML + mermaid-cli; outputs stored alongside commit hash for traceability.
  - OpenAPI specs validated, then published to `api/` folder; headless + catalog specs versioned with changelog entries referencing iteration tasks.
  - Migration scripts executed against ephemeral PostgreSQL containers; RLS tests confirm policies on new tables; failure blocks release.
  - Background job payload schemas validated against JSON Schema file `jobs/workers/payload-schema.json` before worker merges.
- **Integration Strategy:**
  - Domain events feed reporting; integration tests simulate event emission + projection; mismatched schema versions flagged by worker logs and gating script.
  - External adapters (Stripe, USPS/UPS/FedEx, address validation, FFmpeg) stubbed via local mock servers; contract tests confirm request/response shape and error handling; real sandbox runs executed nightly in staging with alerts to operations.
  - Feature flags coordinate multi-tenant rollouts; toggles tracked in platform admin UI; release notes identify which tenants receive features when.
  - Observability instrumentation validated via integration tests asserting presence of correlation IDs and spans; Grafana dashboards exported into repo and versioned to catch drift.

<!-- anchor: glossary -->
## 7. Glossary

- **Tenant Access Gateway:** Request filter + CDI event system that resolves tenant context from Host header or custom domain before handing off to controllers; enforces suspension + CORS rules.
- **TenantContext:** `@RequestScoped` bean containing tenant_id, store metadata, feature flags, and theme tokens; injected into services for tenant-scoped logic.
- **Panache Repository:** Quarkus data access layer providing entity operations; extended here with automatic tenant filters and soft-deletes to comply with RLS.
- **DelayedJob Pattern:** Database-backed queue storing prioritized background jobs (CRITICAL/HIGH/DEFAULT/LOW/BULK) with leasing + retry semantics; replaces external brokers per constraint.
- **ADR (Architecture Decision Record):** Markdown file documenting context/decision/consequences for governance topics (job management, feature flags); referenced before any deviation.
- **OpenAPI Spec-First:** Workflow where REST endpoints defined in YAML prior to implementation, ensuring shared DTOs and error contracts; validated via `swagger-cli`.
- **Qute:** Quarkus templating engine powering storefront server-rendered HTML with Tailwind + PrimeUI components.
- **Quinoa:** Quarkus extension bundling Vue 3 admin SPA assets, enabling unified Maven build and dev server integration.
- **Feature Flag Governance:** Policies dictating metadata, expiry, owner, and audit requirements for toggles; ensures features roll out gradually with kill switches.
- **Consignment Payout Batch:** Aggregation of sold consignment items awaiting Stripe Connect transfer; includes commission logic, vendor statements, and audit logs.
- **Media Pipeline Controller:** Service handling media uploads (presigned URLs) and background processing via FFmpeg + Thumbnailator producing tenant-scoped variants.
- **Reporting Projection Service:** Domain-event-driven subsystem generating read-optimized tables + SSE feeds for dashboards, enforcing `data_freshness_timestamp` fields.
- **Platform Admin Console:** `/admin/platform` backend + UI enabling SaaS super-users to monitor stores, impersonate staff/customers, suspend tenants, and view cross-tenant analytics.
- **POS Offline Queue:** Encrypted local storage on POS devices capturing transactions during connectivity loss, later replayed via `/pos/offline/batches` with idempotency guarantees.
- **Headless API:** OAuth-protected REST endpoints for catalog/cart/order data consumed by static sites or partners; share schemas with storefront but optimized for API use.
- **Plan Manifest:** `plan_manifest.json` index mapping anchors to file paths so autonomous agents can retrieve specific plan fragments quickly.
- **Release Criteria & Gates:**
  - Feature completeness verified via checklists tied to iteration exit criteria; backlog items referencing plan sections must be closed or linked to follow-up tasks.
  - Smoke, regression, and acceptance suites must be green in staging for 72 hours before GA; synthetic monitors prove zero downtime across sample tenants.
  - Security signoff requires threat model updates, dependency scans, secret rotation verification, and impersonation governance review.
  - Documentation audit ensures README, ADR indexes, runbooks, and plan manifest entries match latest artifacts; failure blocks release until updates committed.
- **Data Validation & Migration Strategy:**
  - MyBatis migrations run forward + rollback in CI to verify safe deployment; data snapshots compare row counts + tenant distribution before/after.
  - Partitioned tables tested for automatic creation; script ensures next-month partitions exist; integration tests confirm archive jobs push JSONL to R2.
  - Data exports validated via checksum + schema version manifest; e2e tests confirm downloads expire correctly and respect tenant scoping.
- **User Acceptance & Beta Programs:**
  - Pilot tenants identified per feature flag; runbook includes enabling/disabling instructions plus telemetry collection forms.
  - Feedback loops feed backlog issues tagged with `beta-feedback`; gating release until severity-one items resolved.
- **Incident Response & Rollback:**
  - On-call runbooks versioned in `docs/runbooks/`; plan mandates tabletop exercise before GA verifying tenant isolation, media pipeline failure, payment outage, and POS offline backpressure scenarios.
  - Release includes automated rollback scripts for Kubernetes (switch Service to previous ReplicaSet) and database migrations (MyBatis down scripts) plus data backup verification.
- **Compliance & Privacy Reviews:**
  - Stripe + PCI scoping documented; proof that no card data stored server-side.
  - PII encryption routines validated with sample consignor data; logs confirm key version IDs recorded per access.
  - Data retention flows (90-day hot storage, R2 archives) tested, with reports exported for auditors.

- **Tooling & Automation Coverage:**
  - `make verify` orchestrates lint/test/diagram validation locally; CI ensures same command executed to reduce drift.
  - Git hooks encourage developers to update plan manifest when adding diagrams/specs; failing to do so triggers CI reminder.
  - Observability smoke tests verify logs include tenant metadata and metrics scraped by Prometheus; absence triggers failing check.

- **Integration Testing Cadence:**
  - Nightly job runs cross-tenant scenario (create store, import catalog, run checkout, payout consignment, process media) and posts report to Slack; failures create GitHub issues automatically.
  - Weekly shipping sandbox tests confirm carrier adapters stay in sync; results logged in `docs/operational/shipping-readout.md` with plan references.

- **Training & Handoff:**
  - Before GA, platform support receives training deck referencing plan sections, diagrams, runbooks; acceptance recorded in `docs/release/ga-checklist.md`.
  - Knowledge transfer sessions scheduled for feature owners (loyalty, POS, media) with recorded demos stored in knowledge base (linked from README).

- **Post-Release Monitoring:**
  - Observability dashboards configured with canary tenants; metrics watch for SLA breaches; feature flags ready for rollback.
  - Daily review of impersonation logs + payout reconciliation ensures compliance; anomalies escalate via pager.

<!-- anchor: glossary-extended -->
### 7.1 Extended Glossary Entries

- **GraalVM Native Build:** Quarkus native image compilation producing fast-start binaries used in containers, enabling aggressive auto-scaling.
- **Quarkus Scheduler:** Built-in scheduling used for loyalty tier jobs, FX refresh, archival tasks; respects tenant-safe iteration.
- **Stripe Connect:** Payment platform for handling per-tenant merchant accounts, application fees, and vendor payouts; integration includes onboarding flows, transfers, and webhook handling.
- **Feature Toggle Service:** Application component caching feature flags with Caffeine, exposing evaluation API for backend + SPA; includes CLI/admin UI for overrides.
- **RLS (Row-Level Security):** PostgreSQL feature enforcing tenant isolation inside the database; migrations create policies for every tenant table.
- **OpenTelemetry:** Observability standard emitting traces/spans; Quarkus instrumentation ensures parent/child relationships across REST, jobs, FFmpeg.
- **Caffeine Cache:** In-memory caching library for Java; used for tenant resolution, feature flags, shipping rates, FX data, and headless API throttling.
- **Domain Events:** JSON payloads persisted in `domain_events` table, enabling reporting projections and cross-module decoupling.
- **POS Device Registration:** Secure enrollment process binding hardware to tenant/location + firmware version; Authorization enforced before offline batches accepted.
- **Gift Card Ledger:** Internal record of gift card issuance/redemption, integrated with checkout + payments to track balances and apply store credit.
- **Headless Client Credential:** OAuth credential representing partner integration consuming headless APIs; scoped + rate limited per tenant.
- **Smoke Script:** `ops/scripts/smoke.sh` hitting `/q/health` and representative API endpoints after deployments.
- **Plan Manifest:** JSON index describing anchors, file paths, and descriptions for plan content—enables autonomous agents to reference sections precisely.
- **HPAs (Horizontal Pod Autoscalers):** Kubernetes resources configured in `ops/k8s/overlays/*/hpa.yaml` scaling pods based on CPU/memory/custom metrics.
- **Synthetic Monitors:** Automated flows hitting storefront/admin endpoints to detect regressions before customers notice; part of post-release verification.
