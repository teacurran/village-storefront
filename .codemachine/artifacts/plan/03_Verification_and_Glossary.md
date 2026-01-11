<!-- anchor: verification-and-integration-strategy -->
## 6. Verification and Integration Strategy

*   **Testing Levels:**
    - **Unit:** Each domain/service (tenant, catalog, checkout, payment, consignment, loyalty, POS, media) must keep ≥85% coverage, include negative cases (tenant mismatch, validation errors), and run via `mvn test` + Jest/Vitest for Vue stores. Feature toggles require dedicated unit tests verifying enable/disable behavior.
    - **Integration:** Quarkus integration tests run against PostgreSQL + MinIO containers verifying RLS policies, Stripe webhook flows (mocked), shipping adapters, media uploads; REST-assured contract tests automatically validate OpenAPI schemas per iteration deliverables.
    - **End-to-End:** Playwright suites cover storefront browsing/checkout, admin catalog/order workflows, POS offline transactions, platform impersonation; nightly + release candidate runs capture screenshots, tracing metadata, and Lighthouse metrics for storefront/admin.
    - **Performance & Chaos:** Gatling/Locust load tests simulate peak checkout/cart traffic, POS offline bursts, and consignment payout spikes; chaos scripts trigger DB failover, worker restarts, and Stripe/carrier outages verifying kill-switch + fallback behavior documented in runbook.
*   **CI/CD Flow:** GitHub Actions pipeline runs Spotless, unit/integration tests, Playwright API smoke, OpenAPI lint, coverage upload (JaCoCo ≥80%), Quinoa build, GraalVM native compile, docker image publish, and deploy workflow (blue/green with smoke tests + manual approval). Feature branch gating enforces spec alignment and prevents merges with unchecked tasks.
*   **Quality Gates:**
    - Test coverage: backend modules ≥80% (JaCoCo), frontend stores/components ≥80% (Vitest), e2e suites must pass each run.
    - Lint/style: Spotless, ESLint, Stylelint, Spectral (OpenAPI) zero errors.
    - Security: Dependency scanning (OWASP/Trivy) integrated into CI; secrets scanning ensures no credentials in repo; Stripe keys only referenced via GitHub secrets.
    - Performance budgets: Checkout API <300 ms p95, storefront LCP <2 s, admin initial JS bundle <2 MB gz; budgets enforced by integration tests + Lighthouse CI.
    - Observability checks: `/q/health` endpoints validated in pipeline; metrics/tracing exporters verified via unit tests.
*   **Integration Strategy:**
    - Feature flags gate incremental rollout; iteration deliverables specify required toggles/clamps for new modules (catalog, checkout, consignment, POS, headless, domain management).
    - OpenAPI spec-first approach ensures backend/frontend/headless clients stay in sync; spec merged before code, and generated SDKs updated automatically.
    - Background job payload schemas versioned; new job handlers introduced with compatibility tests + migration notes in runbook.
    - Infrastructure integration uses Kustomize overlays; dev/staging/prod parity maintained with sealed secrets + environment variable matrices documented in runbook.
*   **Artifact Validation:** PlantUML/Mermaid diagrams validated via CI (PlantUML CLI, `mmdc`), OpenAPI via Spectral, Markdown docs linted (markdownlint). Media worker container includes health check to ensure FFmpeg version pinned; Kubernetes manifests tested via `kubeconform`.
*   **Release Readiness:** Final iteration executes verification plan (Task `I5.T7`) producing release readiness report, capturing coverage metrics, unresolved risks, rollback plans, tenant onboarding checklist, and platform governance approvals.

<!-- anchor: glossary -->
## 7. Glossary

*   **TenantContext:** Request-scoped CDI bean storing tenant_id, store metadata, feature flags, used across services to enforce RLS and theming.
*   **DelayedJob:** Database-backed queue table implementing CRITICAL/HIGH/DEFAULT/LOW/BULK priorities and `SELECT ... SKIP LOCKED` workers.
*   **Feature Flag:** Configuration entry in `feature_flags` table with default + tenant overrides, owner, expiry, and kill-switch semantics.
*   **Platform Admin Console:** `/admin/platform/*` SPA modules used by VillageCompute ops to manage stores, impersonation, feature flags, and system health.
*   **Headless API:** OAuth-scoped REST surface exposing catalog/cart/order data for partner sites; enforces rate limits + JWT-style client credentials.
*   **POS Offline Queue:** IndexedDB-backed storage of POS sales when offline, encrypted and replayed sequentially after reconnect.
*   **Stripe Connect:** Payment platform enabling per-tenant onboarding, application fees, payouts, and Stripe Terminal hardware support.
*   **FFmpeg Worker Pod:** Dedicated Kubernetes deployment subscribed to CRITICAL queue for video transcoding, isolated from web pods.
*   **Runbook:** `docs/operations/runbook.md` manual containing deployment, rollback, incident, and observability procedures referenced by ops teams.
*   **Release Readiness Report:** Artifact summarizing verification results, coverage metrics, load/chaos findings, and go/no-go decisions before pilot launch.
