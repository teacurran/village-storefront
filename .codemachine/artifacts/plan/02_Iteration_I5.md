<!-- anchor: iteration-5-plan -->
### Iteration 5: Admin Experience, Platform Ops, Observability & Launch Readiness

*   **Iteration ID:** `I5`
*   **Goal:** Finalize admin UI modules, platform governance console, deployment/observability artifacts, and verification strategy needed for GA.
*   **Prerequisites:** `I1`, `I2`, `I3`, `I4`
*   **Retrospective Carryover:**
    - Bake post-iteration learnings into PR templates (anchor checklist, manifest updates, doc references).
    - Align release toggles with feature flag governance doc before writing code.
    - Keep ops + support in loop for new dashboards/runbooks; schedule reviews ahead of time.
*   **Iteration Milestones & Exit Criteria:**
    1. Admin SPA modules (orders, inventory, reporting, notifications, loyalty) wired to APIs with RBAC + feature flags.
    2. Platform admin console (SaaS governance, impersonation audit, system health) fully functional with audit logging.
    3. Deployment diagram + Kustomize overlays + GitHub Actions release workflow documented and validated.
    4. Observability stack dashboards (Prometheus/Grafana/Jaeger) with KPIs defined, runbooks updated.
    5. Verification suite (Playwright/Cypress + load tests + manifest-driven retrieval) automated in CI.
    6. Compliance automation (privacy exports/deletes, archival jobs) completed and documented.

<!-- anchor: task-i5-t1 -->
*   **Task 5.1:**
    *   **Task ID:** `I5.T1`
    *   **Description:** Complete admin SPA modules for orders, inventory, reporting, loyalty, notifications using Vue + PrimeVue; wire data tables, filters, detail panels, inline actions, SSE notifications, feature flags.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Backend APIs from prior iterations, design tokens, UX guidelines.
    *   **Input Files:** [`src/main/webui/admin-spa/src/modules/**`, `api/v1/openapi.yaml`, `tailwind.config.js`, `docs/notifications/playbook.md`]
    *   **Target Files:** [`src/main/webui/admin-spa/src/modules/orders/**`, `.../inventory/**`, `.../reporting/**`, `.../loyalty/**`, `.../notifications/**`, `tests/admin/OrdersDashboard.spec.ts`, `tests/admin/ReportingDashboard.spec.ts`]
    *   **Deliverables:** Feature-complete admin modules with RBAC gating, SSE-driven alerts, localization, Storybook stories, e2e tests.
    *   **Acceptance Criteria:** Modules compile, RBAC enforced, feature flags hide beta features, e2e tests green, Storybook updated, docs show usage.
    *   **Testing Guidance:** Cypress suites covering CRUD, filters, impersonation banners; Storybook visual diffs; performance budgets (TTI <2.5s) recorded.
    *   **Observability Hooks:** Instrument SPA with analytics events (view_* and action_*), log SSE connection health.
    *   **Dependencies:** `I2.T3`, `I3` outputs, `I4` flows.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t2 -->
*   **Task 5.2:**
    *   **Task ID:** `I5.T2`
    *   **Description:** Build platform admin console: store directory, impersonation control, health dashboards, support tooling, audit log viewer; integrate RBAC + MFA + audit logging.
    *   **Agent Type Hint:** `FullStackAgent`
    *   **Inputs:** Platform requirements, audit schema, impersonation ADR.
    *   **Input Files:** [`src/main/java/com/village/platformops/**`, `src/main/webui/admin-spa/src/modules/platform/**`, `docs/adr/ADR-001-tenancy.md`, `docs/adr/ADR-004-consignment-payouts.md`]
    *   **Target Files:** [`src/main/java/com/village/platformops/**`, `tests/backend/PlatformAdminIT.java`, `tests/admin/PlatformConsole.spec.ts`, `docs/platform/console.md`]
    *   **Deliverables:** APIs + UI modules for platform governance, impersonation banner, audit log viewer, system health charts, documentation.
    *   **Acceptance Criteria:** Platform console enforces RBAC, impersonation requires reason + ticket, system health pulls Prometheus summaries, audit viewer paginates w/ filters.
    *   **Testing Guidance:** Integration tests verifying RBAC, impersonation start/stop, audit logging; UI tests for dashboards.
    *   **Observability Hooks:** Record platform console actions to `PlatformCommand` table, metrics for impersonation count/duration.
    *   **Dependencies:** `I1.T5`, `I3.T3`, `I4.T1`.
    *   **Parallelizable:** Limited.

<!-- anchor: task-i5-t3 -->
*   **Task 5.3:**
    *   **Task ID:** `I5.T3`
    *   **Description:** Finalize deployment artifacts: PlantUML deployment diagram, Kustomize overlays per env, GitHub Actions release workflow (build, push, deploy), blue/green strategy doc, secrets management notes.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Architecture overview, Kubernetes manifests, CI pipeline.
    *   **Input Files:** [`docs/diagrams/component_overview.puml`, `k8s/base/**`, `k8s/overlays/**`, `.github/workflows/ci.yml`]
    *   **Target Files:** [`docs/diagrams/deployment_k8s.puml`, `k8s/overlays/dev|staging|prod/**`, `.github/workflows/release.yml`, `docs/operations/deployment.md`]
    *   **Deliverables:** Deployment diagram, overlays with resource limits/HPAs/PDBs, release workflow, doc describing promotion + rollback steps.
    *   **Acceptance Criteria:** Diagrams render, overlays deploy via `kubectl apply`, release workflow runs blue/green, documentation lists manual verification + rollback steps.
    *   **Testing Guidance:** Run dry-run deployments on staging, capture outputs; include automated smoke tests post-deploy.
    *   **Observability Hooks:** Document release metrics (deployment duration, success/failure) and integrate notifications to Slack/Ops.
    *   **Dependencies:** `I1.T6`, `I3.T6`, `I4.T3`.
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t4 -->
*   **Task 5.4:**
    *   **Task ID:** `I5.T4`
    *   **Description:** Establish observability dashboards + alerts: configure Prometheus/Grafana dashboards for KPIs, Jaeger traces, logging pipeline, alert catalog; integrate with Platform console.
    *   **Agent Type Hint:** `SREAgent`
    *   **Inputs:** Section 6 verification strategy, metrics emitted from previous iterations.
    *   **Input Files:** [`docs/operations/job_runbook.md`, `docs/media/pipeline.md`, `docs/checkout/saga.md`, `k8s/base/prometheus.yaml`]
    *   **Target Files:** [`docs/operations/observability.md`, `monitoring/grafana-dashboards/*.json`, `monitoring/prometheus-rules/*.yaml`, `docs/operations/alert_catalog.md`]
    *   **Deliverables:** Dashboard JSON, alert rules, doc describing KPIs + ownership, integration instructions for Platform console widgets.
    *   **Acceptance Criteria:** Dashboards cover KPIs per Section 4 component KPIs, alert rules tested, doc lists runbooks and escalate matrix.
    *   **Testing Guidance:** Use `promtool` to validate rules, simulate alerts via metric injection, record Grafana screenshots.
    *   **Observability Hooks:** Link dashboards to instrumentation fields, ensure correlation IDs appear in logs/traces.
    *   **Dependencies:** `I3.T3`, `I4.T1`-`I4.T7` outputs.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t5 -->
*   **Task 5.5:**
    *   **Task ID:** `I5.T5`
    *   **Description:** Build verification suite: Playwright end-to-end (storefront checkout, admin flows, platform console), Cypress POS offline scenarios, k6 load tests (checkout, media uploads), manifest-driven retrieval tests.
    *   **Agent Type Hint:** `QualityAgent`
    *   **Inputs:** APIs, UI modules, manifest anchors.
    *   **Input Files:** [`tests/e2e/playwright/**`, `tests/admin`, `tests/storefront`, `docs/plan_manifest_template.md`]
    *   **Target Files:** [`tests/e2e/playwright/*.ts`, `tests/load/k6/checkout.js`, `tests/manifest/anchor_validation.py`, `.github/workflows/test_suite.yml`, `docs/testing/strategy.md`]
    *   **Deliverables:** Full verification plan, scripts, CI workflow, docs describing smoke/regression cadence.
    *   **Acceptance Criteria:** Tests run in CI, results archived, load tests hit target throughput, manifest validation ensures anchors resolvable.
    *   **Testing Guidance:** Run nightly scheduled jobs for e2e, gather metrics, document flake triage process.
    *   **Observability Hooks:** Report metrics from tests to Grafana (pass/fail counts, duration) and add PR comments summarizing results.
    *   **Dependencies:** All prior tasks delivering surfaces.
    *   **Parallelizable:** Limited (depends on surfaces availability).

<!-- anchor: task-i5-t6 -->
*   **Task 5.6:**
    *   **Task ID:** `I5.T6`
    *   **Description:** Automate compliance workflows: privacy export/delete, archival job verification, consent management, audit export UI; integrate with platform console + docs.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Requirements (privacy, audit), reporting module, archival pipeline.
    *   **Input Files:** [`docs/data_governance.md`, `src/main/java/com/village/reporting/**`, `src/main/java/com/village/platformops/**`, `docs/operations/observability.md`]
    *   **Target Files:** [`src/main/java/com/village/compliance/**`, `tests/backend/ComplianceIT.java`, `docs/compliance/privacy.md`, `docs/operations/archive_runbook.md`]
    *   **Deliverables:** APIs + jobs for export/delete, audit log for actions, docs covering process + retention.
    *   **Acceptance Criteria:** Requests queue + notify, exports zipped JSONL to R2, delete workflow soft-deletes then purges after retention, docs describe manual review.
    *   **Testing Guidance:** Integration tests using sample tenant data, verify exports zipped + hashed, run manual Drills.
    *   **Observability Hooks:** Metrics for export queue, deletion backlog, audit logs referencing platform command IDs.
    *   **Dependencies:** `I3.T3`, `I5.T2`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t7 -->
*   **Task 5.7:**
    *   **Task ID:** `I5.T7`
    *   **Description:** Finalize feature flag governance + release process: create governance doc, dashboards, automation for stale flag detection, CLI for toggles, integration with platform console.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Section 3 enforcement playbooks, feature flag service, platform console.
    *   **Input Files:** [`docs/architecture_overview.md`, `docs/operations/observability.md`, `src/main/java/com/village/featureflag/**`, `src/main/webui/admin-spa/src/modules/platform/**`]
    *   **Target Files:** [`docs/feature_flags/governance.md`, `tools/featureflag-cli/`, `monitoring/grafana-dashboards/feature_flags.json`]
    *   **Deliverables:** Governance doc w/ owner/expiry fields, CLI for toggles + audits, dashboard tracking adoption, automation hooking to PR template.
    *   **Acceptance Criteria:** CLI lists flags + toggles states, stale flags flagged via job, doc explains process + rollback.
    *   **Testing Guidance:** Unit tests for CLI, integration test hitting API, manual dry-run toggling sample flag.
    *   **Observability Hooks:** Dashboard showing enablement progress, alerts for expired flags, log entries for CLI actions.
    *   **Dependencies:** `I1.T5`, `I2.T3`, `I5.T2`.
    *   **Parallelizable:** Yes.

*   **Iteration KPIs & Validation Strategy:**
    - Admin modules achieve >90% route coverage with e2e tests and meet performance budgets.
    - Platform console impersonation logs show 100% reason coverage; audit exports respond <5s for 90-day ranges.
    - Release workflow executes blue/green within 15 minutes; rollback instructions tested.
    - Observability dashboards published with owners + SLA targets; alerts tested via simulated events.
    - Verification suite runs nightly and before releases; failure triage SLA <24h.
    - Compliance jobs process export/delete requests within SLA (export <24h, delete <30 days) and log metrics.
*   **Iteration Risk Log & Mitigations:**
    - *Scope creep:* Admin UI might absorb extra modules; mitigation—lock backlog, defer non-blocking features.
    - *Release automation errors:* Mitigate via staged dry-runs + manual checkpoints.
    - *Alert fatigue:* Mitigate by prioritizing severity + mapping ownership.
    - *Compliance gaps:* Mitigate by involving legal/security reviewers.
    - *Flag governance drift:* Mitigate via automation (Task 5.7) with weekly review.
*   **Iteration Backlog & Follow-ups:**
    - Prepare GA readiness checklist summarizing all artifacts.
    - Plan customer beta rollout schedule with ops.
    - Outline Phase 2/3 roadmap transitions (subscriptions, services, marketplace) referencing plan outcomes.
    - Create knowledge base for support referencing platform console + compliance tools.
