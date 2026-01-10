<!-- anchor: iteration-5-plan -->
### Iteration 5: Loyalty, Reporting, Platform Admin & Headless APIs

*   **Iteration ID:** `I5`
*   **Goal:** Implement advanced programs (loyalty, gift cards, store credit), reporting + retention pipelines, platform admin console with impersonation governance, headless APIs/OAuth, and automated consignment payouts.
*   **Prerequisites:** `I1`–`I4`
*   **Tasks:**

<!-- anchor: task-i5-t1 -->
*   **Task 5.1:**
    *   **Task ID:** `I5.T1`
    *   **Description:** Build Loyalty module (ledger, tier definitions, accrual/redemption services, nightly jobs, OpenAPI endpoints) plus integration into checkout + admin UI.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Loyalty requirements, checkout orchestration.
    *   **Input Files:** [`modules/loyalty/src/main/java/...`, `modules/checkout-orders/src/main/java/...`, `api/storefront-admin-platform.yaml`]
    *   **Target Files:** [`migrations/mybatis/20240713_loyalty.sql`, `modules/loyalty/src/main/java/.../LoyaltyLedgerEntity.java`, `LoyaltyService.java`, `TierService.java`, `modules/loyalty/src/test/java/...`, `modules/checkout-orders/src/main/java/.../CheckoutOrchestrator.java`, `src/main/webui/src/views/Loyalty/*`] 
    *   **Deliverables:** Ledger schema, accrual/reservation logic, nightly job (Quartz/Scheduler) releasing expired reservations, APIs for admin + customer, UI components (earn/redeem), tests verifying two-phase commit.
    *   **Acceptance Criteria:** Points accrue/reserve/refund flows tested; checkout integration toggled via feature flag; Admin UI displays tier progress; metrics exported for loyalty KPIs.
    *   **Dependencies:** `I3.T1`, `I4.T2`.
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t2 -->
*   **Task 5.2:**
    *   **Task ID:** `I5.T2`
    *   **Description:** Implement Reporting & Retention pipeline (domain_events poller, aggregate tables, scheduled exports, archival to R2 JSONL, retention doc updates).
    *   **Agent Type Hint:** `DataEngineeringAgent`
    *   **Inputs:** Domain events, ERD, Section 5 data governance.
    *   **Input Files:** [`modules/reporting/src/main/java/...`, `docs/architecture/data/reporting-retention.md`, `docs/architecture/async/job-catalog.md`]
    *   **Target Files:** [`modules/reporting/src/main/java/.../ProjectionWorker.java`, `AggregateRepository.java`, `ReportExportJob.java`, `modules/reporting/src/test/java/...`, `docs/architecture/data/reporting-retention.md`, `docs/architecture/async/job-catalog.md`]
    *   **Deliverables:** Worker to transform domain events into aggregates (sales KPI, inventory, loyalty, consignment), export job + API, retention doc describing partition + archive steps.
    *   **Acceptance Criteria:** Worker processes backlog idempotently; exports upload to R2 with manifest; doc lists retention windows + automation schedule; Prometheus metrics for job lag.
    *   **Dependencies:** `I3.T1` events, `I4.T6` UI scaffolding.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t3 -->
*   **Task 5.3:**
    *   **Task ID:** `I5.T3`
    *   **Description:** Deliver Platform Admin console backend + UI (store list, plan/billing, impersonation approvals, health metrics, audit exports) plus impersonation logging enhancements.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Platform admin requirements, audit schema.
    *   **Input Files:** [`modules/platform-ops/src/main/java/...`, `src/main/webui/src/views/Platform`, `api/storefront-admin-platform.yaml`, `docs/architecture/governance/feature-flags.md`]
    *   **Target Files:** [`modules/platform-ops/src/main/java/.../PlatformStoreResource.java`, `ImpersonationController.java`, `AuditExportResource.java`, `src/main/webui/src/views/Platform/Overview.vue`, `StoreDirectory.vue`, `ImpersonationLogs.vue`, `stores/platform.ts`, `modules/platform-ops/src/test/java/...`]
    *   **Deliverables:** APIs (store listing, impersonation start/stop, audit exports, health metrics), UI dashboard with KPIs + filters, impersonation banner + session log, feature flag controls, tests verifying RBAC.
    *   **Acceptance Criteria:** Platform scope tokens enforce access; impersonation workflow logs reason/ticket, TTL, SSE notifications; UI displays store health cards; export job statuses show.
    *   **Dependencies:** `I3.T5`, `I4.T2`, `I4.T6`.
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t4 -->
*   **Task 5.4:**
    *   **Task ID:** `I5.T4`
    *   **Description:** Implement Headless API + OAuth client credential management (tenant-level clients, scopes, rate limiting, documentation portal) plus sample integration.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** API spec, Identity service.
    *   **Input Files:** [`modules/core-platform/src/main/java/.../OAuthClientService.java`, `api/storefront-admin-platform.yaml`, `docs/architecture/governance/feature-flags.md`]
    *   **Target Files:** [`migrations/mybatis/20240713_oauth_clients.sql`, `modules/core-platform/src/main/java/.../OAuthClientEntity.java`, `OAuthClientResource.java`, `modules/core-platform/src/test/java/...`, `docs/architecture/ops/headless-guide.md`, `src/main/webui/src/views/Settings/Headless.vue`]
    *   **Deliverables:** OAuth client issuance UI + API, scopes (catalog:read, cart:write, orders:read), rate limit enforcement (token bucket), docs describing usage + sample calls, sample Next.js script.
    *   **Acceptance Criteria:** Token issuance/resv validated; rate limiting returns 429 with ProblemDetails; docs include curl + JS examples; integration test ensures headless order flows function.
    *   **Dependencies:** `I1.T5`, `I1.T6`, `I3.T1`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t5 -->
*   **Task 5.5:**
    *   **Task ID:** `I5.T5`
    *   **Description:** Launch Gift card + Store credit services (entities, APIs, checkout integration, admin UI, reporting) with compliance logging.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Requirements (gift card/store credit), checkout + loyalty integration.
    *   **Input Files:** [`modules/payments/src/main/java/...`, `modules/checkout-orders/src/main/java/...`, `api/storefront-admin-platform.yaml`, `src/main/webui/src/views/Customers`]
    *   **Target Files:** [`migrations/mybatis/20240713_giftcards.sql`, `modules/payments/src/main/java/.../GiftCardService.java`, `StoreCreditService.java`, `modules/payments/src/test/java/...`, `modules/checkout-orders/src/main/java/.../CheckoutOrchestrator.java`, `src/main/webui/src/views/GiftCards.vue`, `AccountBalance.vue`]
    *   **Deliverables:** Gift card issuance/redemption, store credit ledger, checkout logic stacking rules, admin UI for management, reporting aggregator entries.
    *   **Acceptance Criteria:** API tests for issuance/redeem/refund; checkout ensures gift card + loyalty stacking rules enforced; UI surfaces balances; events recorded for reporting.
    *   **Dependencies:** `I3.T1`, `I5.T1` (loyalty), `I3.T3` (payments).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i5-t6 -->
*   **Task 5.6:**
    *   **Task ID:** `I5.T6`
    *   **Description:** Automate consignment payouts (Stripe Connect Express integration, payout scheduling job, statements, emails, portal updates).
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Consignment ledger, Stripe provider.
    *   **Input Files:** [`modules/consignment/src/main/java/...`, `modules/payments/src/main/java/.../StripePaymentProvider.java`, `docs/architecture/async/job-catalog.md`]
    *   **Target Files:** [`modules/consignment/src/main/java/.../PayoutScheduler.java`, `StripeConnectService.java`, `modules/consignment/src/test/java/...`, `src/main/webui/src/views/Consignors/Payouts.vue`, `docs/architecture/async/job-catalog.md`, `src/main/resources/templates/emails/consignor-payout.html`]
    *   **Deliverables:** Scheduled job moving pending→available balances, Stripe payout creation, statements stored to R2, portal + email updates.
    *   **Acceptance Criteria:** Jobs respects configurable windows, handles refunds/chargebacks, logs audit trail; UI shows payout status; notifications sent; tests with Stripe mocks.
    *   **Dependencies:** `I3.T5`, `I3.T3`, `I4.T5`.
    *   **Parallelizable:** No.

<!-- anchor: task-i5-t7 -->
*   **Task 5.7:**
    *   **Task ID:** `I5.T7`
    *   **Description:** Enhance structured logging, audit exports, and observability dashboards (Grafana/Jaeger) for loyalty, payments, consignment, headless APIs; update runbooks.
    *   **Agent Type Hint:** `ObservabilityAgent`
    *   **Inputs:** Modules implemented in this iteration.
    *   **Input Files:** [`modules/*/src/main/java`, `docs/architecture/ops/*`, `infra/k8s/base/*`]
    *   **Target Files:** [`modules/loyalty/src/main/java/.../LoyaltyMetrics.java`, `modules/payments/src/main/java/.../PaymentTelemetry.java`, `modules/consignment/src/main/java/.../ConsignmentTelemetry.java`, `docs/architecture/ops/runbooks/*.md`, `infra/k8s/base/prometheus-rules.yaml`]
    *   **Deliverables:** Additional metrics/loggers, Grafana dashboards, alert rules for loyalty/consignment/payout/Headless API anomalies, runbook updates referencing new metrics.
    *   **Acceptance Criteria:** Metrics exported with tenant/tier labels; alert rules defined; runbooks detail KPIs + troubleshooting; Jaeger traces show end-to-end flows.
    *   **Dependencies:** `I5.T1-T6`.
    *   **Parallelizable:** Yes.
