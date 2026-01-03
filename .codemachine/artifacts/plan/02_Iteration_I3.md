<!-- anchor: iteration-3-plan -->
### Iteration 3: Consignment, Advanced Inventory & Reporting

*   **Iteration ID:** `I3`
*   **Goal:** Deliver consignment domain parity (ConsignCloud-level), multi-location inventory tooling, background report projections, and tenant-facing portals.
*   **Prerequisites:** `I1`, `I2`
*   **Retrospective Carryover:**
    - Protect CI budget by reusing caches for heavy integration suites; capture metrics for each new suite and update ADR-002 appendix.
    - Expand documentation while implementing features to avoid large catch-up tasks; each task must note doc deliverables explicitly.
    - Keep bilingual assets in sync; when generating new templates include Spanish placeholders.
*   **Iteration Milestones & Exit Criteria:**
    1. Consignment entities/services/payout ledger implemented with APIs + portal endpoints and tests.
    2. Multi-location inventory transfer workflows + barcode printing ready for admin UI integration.
    3. Reporting projection service building sales/inventory/consignment aggregates plus export job queue.
    4. Consignment payout flow diagram + Stripe Connect automation spec documented.
    5. Notification/email templates for consignor lifecycle events, localized and feature-flag controlled.
    6. Background job improvements (priority tuning, metrics, dashboards) supporting new workloads.

<!-- anchor: task-i3-t1 -->
*   **Task 3.1:**
    *   **Task ID:** `I3.T1`
    *   **Description:** Implement consignment domain: Panache entities (`Consignor`, `ConsignmentItem`, `PayoutBatch`), services for intake, commission schedules, portal APIs, admin endpoints, MapStruct DTOs, repository/unit tests.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** ERD, OpenAPI spec, payment ADRs.
    *   **Input Files:** [`docs/diagrams/datamodel_erd.puml`, `api/v1/openapi.yaml`, `docs/adr/ADR-003-checkout-saga.md`]
    *   **Target Files:** [`src/main/java/com/village/consignment/**`, `tests/backend/ConsignmentServiceTest.java`, `tests/backend/ConsignmentControllerIT.java`, `docs/consignment/domain.md`]
    *   **Deliverables:** Domain models, service APIs, REST controllers (admin + portal), DTO conversions, documentation describing data flows + commission rules.
    *   **Acceptance Criteria:** CRUD + payout calculations pass tests, tenant filters applied, portal endpoints require vendor auth tokens, docs articulate commission/tax logic.
    *   **Testing Guidance:** Use Testcontainers for payout ledger, stub Stripe Connect interactions, verify multi-tenant restrictions.
    *   **Observability Hooks:** Add structured logs for consignor actions, metrics for pending payout amount per tenant, and log audit events for portal access.
    *   **Dependencies:** `I2.T1`, `I2.T4`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t2 -->
*   **Task 3.2:**
    *   **Task ID:** `I3.T2`
    *   **Description:** Extend inventory to support multi-location, transfers, adjustments, barcode printing: services, REST endpoints, job coordination for label generation, admin UI API contract.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Inventory module base, ERD, OpenAPI spec.
    *   **Input Files:** [`src/main/java/com/village/inventory/**`, `api/v1/openapi.yaml`, `docs/diagrams/datamodel_erd.puml`]
    *   **Target Files:** [`src/main/java/com/village/inventory/transfer/**`, `tests/backend/InventoryTransferIT.java`, `docs/inventory/multi_location.md`, `api/v1/openapi.yaml`]
    *   **Deliverables:** Transfer services, adjustments, label job enqueue logic, doc describing workflows + barcode formats.
    *   **Acceptance Criteria:** Transfers enforce source/destination validations, RLS protects tenant data, label job triggers background queue, docs include sequence + API usage.
    *   **Testing Guidance:** Integration tests verifying quantity updates + audit logging; include barcode data validation harness.
    *   **Observability Hooks:** Emit metrics for transfer volume and queue depth; add logs for adjustment reason codes.
    *   **Dependencies:** `I2.T1`, `I1.T6`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t3 -->
*   **Task 3.3:**
    *   **Task ID:** `I3.T3`
    *   **Description:** Build reporting projection service + export jobs: domain event consumers, aggregate tables (sales by period, consignment payout, inventory aging), scheduled jobs, export API + storage to Cloudflare R2.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Domain events from catalog/orders/consignment, requirements (reports section).
    *   **Input Files:** [`docs/diagrams/component_overview.puml`, `docs/diagrams/datamodel_erd.puml`, `src/main/java/com/village/checkout/**`, `src/main/java/com/village/consignment/**`]
    *   **Target Files:** [`src/main/java/com/village/reporting/**`, `src/main/java/com/village/jobs/**`, `tests/backend/ReportingProjectionTest.java`, `tests/backend/ReportExportIT.java`, `docs/reporting/projections.md`]
    *   **Deliverables:** Projection modules, SQL for aggregates, R2 upload client wrapper, scheduled job definitions, documentation detailing data freshness and retention.
    *   **Acceptance Criteria:** Aggregates update within SLA (<15 min), exports available via signed URLs, tests verify domain event consumption + R2 upload stub.
    *   **Testing Guidance:** Simulate event streams, use test double for R2, ensure scheduled jobs tested via Quarkus scheduler harness.
    *   **Observability Hooks:** Metrics for job duration, aggregate lag, export queue depth; structured logs for job start/finish.
    *   **Dependencies:** `I2.T4`, `I3.T1`.
    *   **Parallelizable:** Limited (requires domain events).

<!-- anchor: task-i3-t4 -->
*   **Task 3.4:**
    *   **Task ID:** `I3.T4`
    *   **Description:** Create consignment payout flow diagram + automation spec: Mermaid diagram covering admin approval -> Stripe Connect transfer -> reporting -> notification; ADR capturing automation + compliance guard rails.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Consignment services, reporting module, Stripe requirements.
    *   **Input Files:** [`src/main/java/com/village/consignment/**`, `docs/adr/ADR-001-tenancy.md`, `docs/reporting/projections.md`]
    *   **Target Files:** [`docs/diagrams/sequence_consignment_payout.mmd`, `docs/adr/ADR-004-consignment-payouts.md`]
    *   **Deliverables:** Diagram + ADR with state transitions, audit logging guidelines, failure handling.
    *   **Acceptance Criteria:** Diagram renders, ADR explains Stripe Connect Express onboarding handoff, includes impersonation logging instructions.
    *   **Testing Guidance:** Peer review with finance/security, ensure diagram cross-links to Section 2 components.
    *   **Observability Hooks:** Document metrics to emit (payout latency, failure counts) and log schema for audit events.
    *   **Dependencies:** `I3.T1`, `I3.T3`.
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t5 -->
*   **Task 3.5:**
    *   **Task ID:** `I3.T5`
    *   **Description:** Build notification/email templates + service for consignor lifecycle: intake confirmation, sale notification, payout summary, expiration alerts; integrate with Quarkus Mailer + feature flags.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Consignment domain, design tokens, localization strategy.
    *   **Input Files:** [`src/main/java/com/village/consignment/**`, `docs/architecture_overview.md`, `src/main/resources/messages/messages.properties`]
    *   **Target Files:** [`src/main/resources/templates/email/consignment/**`, `src/main/java/com/village/notifications/**`, `tests/backend/NotificationServiceTest.java`, `docs/notifications/playbook.md`]
    *   **Deliverables:** Email/Qute templates (EN/ES), notification service with queue integration, doc describing throttle + feature flags.
    *   **Acceptance Criteria:** Templates render with sample data, i18n placeholders resolved, notifications enqueue background jobs, doc lists environment domain filtering rules.
    *   **Testing Guidance:** Snapshot tests for templates, integration tests verifying Mailer stub, manual proof w/ Mailhog.
    *   **Observability Hooks:** Add metrics for notification volume, bounce tracking, and structured logs including tenant/consignor ids.
    *   **Dependencies:** `I3.T1`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t6 -->
*   **Task 3.6:**
    *   **Task ID:** `I3.T6`
    *   **Description:** Enhance background job framework: priority tuning, retry policies, dead-letter queue, Prometheus metrics, dashboard docs; ensure new queues handle reporting + payout loads.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Existing DelayedJob tables, reporting/export requirements, consignment jobs.
    *   **Input Files:** [`src/main/java/com/village/jobs/**`, `docs/architecture_overview.md`, `docs/reporting/projections.md`]
    *   **Target Files:** [`src/main/java/com/village/jobs/config/**`, `tests/backend/JobSchedulerTest.java`, `docs/operations/job_runbook.md`, `k8s/base/deployment-workers.yaml`]
    *   **Deliverables:** Config updates, retry policy definitions, metrics instrumentation, runbook updates, Kubernetes worker tweaks.
    *   **Acceptance Criteria:** Jobs expose Prometheus metrics (queue depth, failure rate), dead-letter handling documented, runbook instructions for pausing/resuming queues.
    *   **Testing Guidance:** Simulate job failures, ensure retries/backoff recorded; include integration tests verifying metrics endpoints.
    *   **Observability Hooks:** Add trace IDs to job payload logs and dashboards showing queue lengths per priority.
    *   **Dependencies:** `I1.T6`, `I3.T3`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t7 -->
*   **Task 3.7:**
    *   **Task ID:** `I3.T7`
    *   **Description:** Implement consignor portal UI (Vue module) consuming new APIs: dashboards, balance charts, payout requests, notification center; integrate localization + responsive design.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Consignment APIs, design tokens, notification templates.
    *   **Input Files:** [`src/main/java/com/village/consignment/**`, `tailwind.config.js`, `src/main/webui/admin-spa/storybook/**`]
    *   **Target Files:** [`src/main/webui/admin-spa/src/modules/consignor/**`, `src/main/webui/admin-spa/src/locales/en.json`, `src/main/webui/admin-spa/src/locales/es.json`, `tests/admin/ConsignorPortal.spec.ts`]
    *   **Deliverables:** Vue module with dashboard widgets, forms for payout requests, localized copy, test coverage, Storybook stories.
    *   **Acceptance Criteria:** Module builds, responsive layout validated, translations exist, e2e test passes (Cypress), docs describe launch instructions.
    *   **Testing Guidance:** Use Cypress scenario covering login -> view -> payout request; include visual regression snapshots.
    *   **Observability Hooks:** Emit frontend analytics events for portal usage and tie to backend audit logs.
    *   **Dependencies:** `I3.T1`, `I3.T5`.
    *   **Parallelizable:** Limited.

*   **Iteration KPIs & Validation Strategy:**
    - Consignment service tests ≥85% coverage; payout calculations fuzz-tested with 100+ scenarios.
    - Inventory transfer flow processes 1k items in <5 min dev benchmark; queue metrics captured.
    - Reporting aggregates refresh <15m with dashboards showing freshness timestamp.
    - Notifications deliver bilingual emails; unit tests confirm translations exist.
    - Background job metrics exported to Prometheus + dashboards screenshot attached to PR.
    - Consignor portal e2e success rate ≥95% across supported browsers.
*   **Iteration Risk Log & Mitigations:**
    - *Financial correctness:* Commission math errors costly; mitigation—dual reviewer sign-off + fuzz tests.
    - *Queue overload:* Reporting + payouts may starve other jobs; mitigation—priority segmentation + autoscaling from Task 3.6.
    - *Portal security:* Vendor tokens must be scoped; mitigation—pen tests + audit logging.
    - *Localization accuracy:* Spanish content might lag; mitigation—include placeholders + translation backlog.
    - *Export privacy:* Reports contain PII; mitigation—encrypt archives + access logging.
*   **Iteration Backlog & Follow-ups:**
    - Plan I4 task for automated Stripe Connect Express onboarding UI improvements.
    - Schedule design review for inventory UI once backend ready.
    - Draft metrics spec for platform-level consignment dashboards (I5).
    - Capture future requirement for consignment contract attachments stored in R2.
