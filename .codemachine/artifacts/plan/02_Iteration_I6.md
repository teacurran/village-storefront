<!-- anchor: iteration-6-plan -->
### Iteration 6: Deployment Hardening, Security, E2E Validation & Release Readiness

*   **Iteration ID:** `I6`
*   **Goal:** Finalize CI/CD, security/performance validations, automated testing, DR/observability, and documentation to make the platform production-ready on Kubernetes.
*   **Prerequisites:** `I1`–`I5`
*   **Tasks:**

<!-- anchor: task-i6-t1 -->
*   **Task 6.1:**
    *   **Task ID:** `I6.T1`
    *   **Description:** Enhance GitHub Actions pipeline with build matrix (JVM + native + frontend), caching, Spectral lint, SonarCloud quality gate, artifact signing, Docker push, and deployment promotion workflow (blue/green) tied to feature flag toggles.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Existing `.github/workflows/ci.yml`, deployment requirements.
    *   **Input Files:** [`.github/workflows/ci.yml`, `.github/workflows/deploy.yml`, `infra/k8s/overlays/*`, `docs/architecture/ops/deployment-architecture.md`]
    *   **Target Files:** [`.github/workflows/ci.yml`, `.github/workflows/deploy.yml`, `docs/architecture/ops/deployment-architecture.md`, `docs/architecture/ops/release-runbook.md`]
    *   **Deliverables:** Upgraded pipeline with caching, SonarCloud integration, Docker image tagging, artifact signing (cosign/sigstore), auto deploy to dev/staging, manual prod approval, release runbook referencing feature flag gating.
    *   **Acceptance Criteria:** Pipeline passes for PR + main; Sonar gate enforced; signed container image stored; release runbook includes rollback steps; blue/green sample executed in staging.
    *   **Dependencies:** `I4.T7` (manifests), `I5` features.
    *   **Parallelizable:** No.

<!-- anchor: task-i6-t2 -->
*   **Task 6.2:**
    *   **Task ID:** `I6.T2`
    *   **Description:** Implement security hardening: threat models for payments/impersonation/media, bcrypt + JWT rotation tests, CSP headers, rate-limit enforcement, vulnerability scan automation.
    *   **Agent Type Hint:** `SecurityAgent`
    *   **Inputs:** Security requirements, feature modules.
    *   **Input Files:** [`docs/architecture/ops/security-threat-model.md`, `modules/core-platform/src/main/java/...`, `infra/k8s/base/ingress.yaml`]
    *   **Target Files:** [`docs/architecture/ops/security-threat-model.md`, `modules/core-platform/src/main/java/.../SecurityConfig.java`, `modules/core-platform/src/test/java/.../SecurityTests.java`, `infra/k8s/base/ingress.yaml`, `.github/workflows/security-scan.yml`]
    *   **Deliverables:** Threat model documents, JWT/bcrypt rotation tooling, rate limit filters, CSP headers, GitHub workflow for dependency + container scans (OWASP/Trivy).
    *   **Acceptance Criteria:** Threat model lists mitigations + backlog, rate limiting returns RFC 6585 responses, CSP headers verified via integration test, security workflow passes.
    *   **Dependencies:** All modules built.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i6-t3 -->
*   **Task 6.3:**
    *   **Task ID:** `I6.T3`
    *   **Description:** Load/performance testing (JMeter/k6) for storefront + checkout + admin + POS; tune Caffeine caches, database indexes, Quarkus configs; document budgets.
    *   **Agent Type Hint:** `PerformanceAgent`
    *   **Inputs:** Observability metrics, deployment environment.
    *   **Input Files:** [`tests/perf/k6`, `docs/architecture/ops/performance-report.md`, `modules/*/src/main/resources/application.properties`]
    *   **Target Files:** [`tests/perf/k6/storefront.js`, `checkout.js`, `admin.js`, `pos.js`, `docs/architecture/ops/performance-report.md`, `modules/*/src/main/resources/application.properties`]
    *   **Deliverables:** Scripts, automated run pipeline, tuning adjustments (caches, pool sizes, indexes), performance report referencing KPIs.
    *   **Acceptance Criteria:** KPIs met (LCP <2s, API <200ms median, checkout P95 <800ms); report stored; config changes committed with comments; pipeline job passes.
    *   **Dependencies:** `I4` frontends, `I3` checkout, `I5` features.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i6-t4 -->
*   **Task 6.4:**
    *   **Task ID:** `I6.T4`
    *   **Description:** Build comprehensive E2E test suite (Playwright/Cypress) covering multi-tenant flows: storefront guest checkout, auth checkout, admin catalog CRUD, consignment payout, loyalty redemption, POS offline, headless order; integrate to CI gating.
    *   **Agent Type Hint:** `QAAgent`
    *   **Inputs:** API spec, frontends, dev stack.
    *   **Input Files:** [`test/e2e/playwright.config.ts`, `test/e2e/specs/*`, `.github/workflows/ci.yml`]
    *   **Target Files:** [`test/e2e/specs/storefront.spec.ts`, `checkout.spec.ts`, `admin.spec.ts`, `consignment.spec.ts`, `pos-offline.spec.ts`, `headless.spec.ts`, `.github/workflows/ci.yml`, `README.md`]
    *   **Deliverables:** Scripts with fixtures, multi-tenant data seeding, CI step running tests headless on staging environment, documentation on running locally.
    *   **Acceptance Criteria:** Tests deterministic (retry/resilience), run ≤20 min, fail pipeline when regressions occur; README instructs env setup; sample video/screenshot artifacts stored.
    *   **Dependencies:** All modules.
    *   **Parallelizable:** No.

<!-- anchor: task-i6-t5 -->
*   **Task 6.5:**
    *   **Task ID:** `I6.T5`
    *   **Description:** Establish data ops + DR automation: WAL backups, R2 archive verification, restore scripts, DR drill documentation, tenant suspension/resume scripts.
    *   **Agent Type Hint:** `DataOpsAgent`
    *   **Inputs:** Existing migrations, retention doc `I5.T2`.
    *   **Input Files:** [`docs/architecture/data/reporting-retention.md`, `infra/k8s/base/cronjobs.yaml`, `scripts/ops/*`, `modules/platform-ops/src/main/java/...`]
    *   **Target Files:** [`infra/k8s/base/cronjobs.yaml`, `scripts/ops/backup.sh`, `scripts/ops/restore.sh`, `docs/architecture/ops/dr-playbook.md`, `docs/architecture/data/reporting-retention.md`, `modules/platform-ops/src/main/java/.../TenantLifecycleService.java`]
    *   **Deliverables:** CronJobs for backups/archives, restore automation, DR playbook, platform command scripts for suspend/resume + domain cleanup.
    *   **Acceptance Criteria:** Backup/restore tested in staging; playbook lists RTO/RPO; scripts parameterized by environment; tenant suspension script updates feature flags + DNS status.
    *   **Dependencies:** `I5.T2`, `I3.T5`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i6-t6 -->
*   **Task 6.6:**
    *   **Task ID:** `I6.T6`
    *   **Description:** Final documentation sweep (developer guide, API portal, runbooks, onboarding docs, ADR register), plus hypercare plan for post-launch KPI monitoring.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** All prior docs.
    *   **Input Files:** [`README.md`, `docs/architecture/**/*`, `.codemachine/artifacts/plan/*.md`]
    *   **Target Files:** [`README.md`, `docs/architecture/developer-guide.md`, `docs/architecture/ops/runbook-index.md`, `docs/architecture/ops/observability-dashboard.md`, `docs/adr/ADR-README.md`, `docs/adr/ADR-0001-multi-tenant-architecture.md`, `docs/adr/ADR-0002-payment-provider-abstraction.md`]
    *   **Deliverables:** Updated README (capabilities, dev setup, commands), developer guide (modules, coding standards, testing strategy), runbook index, ADR log referencing major choices, hypercare plan referencing metrics + alert thresholds.
    *   **Acceptance Criteria:** Docs cross-linked; ADRs include status/resolution; developer guide covers setup/test/deploy; runbook index lists owners; hypercare plan outlines first 30 days metrics + rotations.
    *   **Dependencies:** All iterations complete.
    *   **Parallelizable:** Yes.
