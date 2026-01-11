<!-- anchor: iteration-5-plan -->
### Iteration 5: Platform Admin, Headless APIs, Deployment Hardening, and Final QA

*   **Iteration ID:** `I5`
*   **Goal:** Complete SaaS governance (platform admin console, impersonation/audit tooling), finalize headless APIs, lock down observability + Kubernetes deployments, and execute release validation so MVP can onboard pilot tenants.
*   **Prerequisites:** `I1`–`I4`
*   **Iteration KPIs:** Platform console API latency <300 ms, impersonation audit completeness 100%, headless API response <200 ms, Kubernetes blue/green success rate 100%, and release candidate passes all smoke suites.
*   **Iteration Risks & Mitigations:**
    - Audit retention gaps → run archival job dry-runs + add alerts for partition drift.
    - Platform console permission errors → enforce RBAC guard rails + include regression tests.
    - Headless API abuse → implement OAuth scopes + rate limit buckets with monitoring dashboards.
*   **Tasks:**

<!-- anchor: task-i5-t1 -->
*   **Task 5.1:**
    *   **Task ID:** `I5.T1`
    *   **Description:** Implement platform admin backend (store listing, plan changes, suspend/resume, impersonation session management, platform metrics APIs) respecting governance guardrails and audit logging.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Section 14 requirements, Clarification 5, ERD (platform tables), Tenant blueprint.
    *   **Input Files:** ["src/main/java/com/village/platform", "docs/architecture/platform_ops.md", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/java/com/village/platform/PlatformAdminResource.java", "src/main/java/com/village/platform/ImpersonationService.java", "src/main/java/com/village/platform/StoreMetricsService.java", "src/test/java/com/village/platform/PlatformAdminResourceIT.java"]
    *   **Deliverables:** REST APIs with RBAC, impersonation session CRUD, plan management endpoints, tests verifying audit entries + tenant safety.
    *   **Acceptance Criteria:**
        - Suspend/resume updates tenant + triggers feature flags; actions logged with reason/ticket.
        - Impersonation tokens expire/renew per policy, storing events in audit tables.
        - Platform metrics API aggregates store KPIs using read models from reporting module.
    *   **Dependencies:** [`I1.T2`, `I3.T9`, `I4.T8`]
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t2 -->
*   **Task 5.2:**
    *   **Task ID:** `I5.T2`
    *   **Description:** Build platform admin console UI (Vue) with dashboards, store directory, impersonation toolbar, alert feed, support queue integration, and feature flag management panel.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Task `I5.T1`, design system tokens, feature flag docs.
    *   **Input Files:** ["src/main/webui/src", "api/openapi.yaml", "docs/architecture/platform_ops.md"]
    *   **Target Files:** ["src/main/webui/src/views/PlatformDashboard.vue", "src/main/webui/src/components/ImpersonationBanner.vue", "src/main/webui/src/views/PlatformStores.vue", "src/main/webui/src/stores/platform.ts", "src/main/webui/src/components/FeatureFlagPanel.vue", "tests/e2e/admin/platform.spec.ts"]
    *   **Deliverables:** UI views/stores/components, impersonation banner, e2e tests verifying console navigation + impersonation flows.
    *   **Acceptance Criteria:**
        - Dashboard shows KPIs, queue depth, alerts; store table supports filters + actions.
        - Impersonation banner persists across app, includes exit button + timer, disables destructive actions if reason missing.
        - Playwright spec covers impersonation start/stop and verifies audit log entry visible.
    *   **Dependencies:** [`I5.T1`]
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t3 -->
*   **Task 5.3:**
    *   **Task ID:** `I5.T3`
    *   **Description:** Finalize headless APIs (catalog/cart/order/customer read/write scopes) with OAuth client credential issuance, rate limiting buckets, and documentation for partner integration.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** OpenAPI spec, Section 13 headless requirements, TenantContext.
    *   **Input Files:** ["src/main/java/com/village/headless", "api/openapi.yaml", "docs/quality/test_strategy.md"]
    *   **Target Files:** ["src/main/java/com/village/headless/HeadlessClientResource.java", "src/main/java/com/village/headless/OAuthClientService.java", "src/main/java/com/village/headless/RateLimitBucketRepository.java", "src/test/java/com/village/headless/HeadlessApiTest.java", "docs/architecture/tenant_isolation.md"]
    *   **Deliverables:** OAuth client issuance endpoints, scope enforcement, rate limit storage, spec updates, tests verifying quotas + tenant scoping.
    *   **Acceptance Criteria:**
        - Client credentials stored hashed, scopes enforced at filter level, responses include rate-limit headers.
        - Spec documents scopes + sample requests; docs describe onboarding + revocation.
        - Tests simulate abuse (exceeding rate) and confirm 429 responses + logging.
    *   **Dependencies:** [`I2.T4`, `I3.T6`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t4 -->
*   **Task 5.4:**
    *   **Task ID:** `I5.T4`
    *   **Description:** Implement custom domain + SSL automation workflow (domain verification job, cert-manager integration, UI for status) as described in Clarification 1 and Section 14.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Tenant blueprint, runbook, Kubernetes requirements.
    *   **Input Files:** ["src/main/java/com/village/domain", "docs/architecture/platform_ops.md", "infra/kustomize/base"]
    *   **Target Files:** ["src/main/java/com/village/domain/CustomDomainResource.java", "src/main/java/com/village/domain/DomainValidationJob.java", "src/main/java/com/village/domain/CertificateEventHandler.java", "src/test/java/com/village/domain/DomainValidationJobTest.java", "src/main/webui/src/views/DomainSettingsView.vue", "infra/kustomize/base/cert-manager/issuer.yaml"]
    *   **Deliverables:** API/UI for domain management, background job validating DNS + requesting certs, Kubernetes issuer manifests.
    *   **Acceptance Criteria:**
        - Domain states (PENDING/ACTIVE/FAILED) persisted, job retries with backoff, errors produce actionable messages.
        - UI displays DNS instructions, verification status, certificate expiry warnings.
        - Manifests include cert-manager ClusterIssuer + RBAC, documented in runbook.
    *   **Dependencies:** [`I1.T2`, `I4.T7`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t5 -->
*   **Task 5.5:**
    *   **Task ID:** `I5.T5`
    *   **Description:** Hardening observability + alerting: configure OpenTelemetry exporter, Prometheus scraping, Grafana dashboards, alert rules for checkout/media/POS, and log shipping policies referencing Section 3.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Runbook, metrics from previous iterations, infrastructure stack.
    *   **Input Files:** ["src/main/resources/application.properties", "docs/operations/runbook.md", "infra/kustomize/base", "docs/architecture/platform_ops.md"]
    *   **Target Files:** ["infra/kustomize/base/observability/otel-collector.yaml", "infra/kustomize/base/prometheus-rules.yaml", "docs/operations/runbook.md", "docs/architecture/platform_ops.md"]
    *   **Deliverables:** Configs + manifests for telemetry stacks, documentation of dashboards + alert thresholds.
    *   **Acceptance Criteria:**
        - Otel collector exports traces to Jaeger; Prometheus rules cover queue depth, webhook latency, POS offline queue.
        - Dashboards defined (grafana JSON or doc) for SLO monitoring, referenced in runbook.
        - Alert routing described with PagerDuty/Statuspage hooks.
    *   **Dependencies:** [`I4.T7`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t6 -->
*   **Task 5.6:**
    *   **Task ID:** `I5.T6`
    *   **Description:** Finalize Kubernetes manifests + Kustomize overlays (dev/staging/prod) with HPAs per module, PodDisruptionBudgets, sealed secrets for Stripe/SMTP, and blue/green deployment workflow in CI/CD.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Infrastructure directories, runbook, CI pipeline.
    *   **Input Files:** ["infra/kustomize/base", "infra/kustomize/overlays", "infra/github-actions/workflows/ci.yaml", "docs/operations/runbook.md"]
    *   **Target Files:** ["infra/kustomize/base/deployment.yaml", "infra/kustomize/base/media-worker-deployment.yaml", "infra/kustomize/overlays/staging/kustomization.yaml", "infra/github-actions/workflows/deploy.yaml", "docs/operations/runbook.md"]
    *   **Deliverables:** Updated manifests, overlay configs, deploy workflow referencing blue/green, documentation with commands + approvals.
    *   **Acceptance Criteria:**
        - Each module deployment includes resources, probes, HPAs, PDBs; overlays inject env-specific configs + secrets.
        - Deploy workflow handles canary, smoke tests, and rollback steps with approval gates.
        - Runbook includes quick reference for applying overlays + verifying rollout.
    *   **Dependencies:** [`I1.T7`, `I4.T6`]
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t7 -->
*   **Task 5.7:**
    *   **Task ID:** `I5.T7`
    *   **Description:** Execute verification plan: full regression (unit/integration/e2e), load tests for checkout + POS, chaos drills (database failover, worker crash), and produce release readiness report summarizing coverage + open risks.
    *   **Agent Type Hint:** `QAAgent`
    *   **Inputs:** Test strategy doc, e2e suites, runbook.
    *   **Input Files:** ["docs/quality/test_strategy.md", "scripts/qa/run_e2e.sh", "tests" , "README.md"]
    *   **Target Files:** ["docs/quality/test_strategy.md", "docs/operations/runbook.md", "docs/architecture/platform_ops.md", "reports/release_readiness.md"]
    *   **Deliverables:** Updated test strategy with actual coverage metrics, release readiness report, load/chaos test scripts + results summarised.
    *   **Acceptance Criteria:**
        - Regression matrix completed, coverage numbers recorded, gating thresholds met or risk-accepted.
        - Load test demonstrates checkout handles target throughput; POS offline/online switch validated.
        - Chaos drill documented with remediation steps; release readiness report signed off by leads.
    *   **Dependencies:** [`I5.T1`–`I5.T6`]
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t8 -->
*   **Task 5.8:**
    *   **Task ID:** `I5.T8`
    *   **Description:** Finalize security/privacy features: data export/delete tooling, session management UI, encryption key rotation scripts, GDPR-ready documentation.
    *   **Agent Type Hint:** `SecurityAgent`
    *   **Inputs:** Section Safety Net, loyalty/gift card modules, session log partitions.
    *   **Input Files:** ["src/main/java/com/village/security", "src/main/webui/src/views", "docs/architecture/tenant_isolation.md", "docs/operations/runbook.md"]
    *   **Target Files:** ["src/main/java/com/village/security/DataExportResource.java", "src/main/java/com/village/security/DataDeletionJob.java", "src/main/webui/src/views/AccountSecurityView.vue", "docs/architecture/tenant_isolation.md", "docs/operations/runbook.md"]
    *   **Deliverables:** APIs/jobs for export/delete, admin UI to trigger/manage requests, docs on key rotation + privacy workflow.
    *   **Acceptance Criteria:**
        - Export job streams hot + archived data (JSONL/CSV) with manifest; delete job soft-deletes, triggers purge per retention.
        - UI shows active sessions, revoke button, and privacy controls; audit logs capture all actions.
        - Documentation outlines encryption key rotation schedule + manual overrides.
    *   **Dependencies:** [`I3.T9`, `I4.T8`]
    *   **Parallelizable:** Yes.

*   **Exit Criteria:**
    - Platform admin console + APIs operational with impersonation logging, headless APIs documented and rate limited, custom domain workflows validated in staging.
    - Kubernetes overlays/CI-CD deliver blue/green deploys with observability + alerts configured; release readiness report approved with mitigation plans logged.
    - Security/privacy tooling (data export/delete, session UI) available, and verification plan executed with signed checklist for pilot launch.
