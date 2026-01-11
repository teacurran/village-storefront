<!-- anchor: iteration-4-plan -->
### Iteration 4: Consignment, Loyalty, POS, and Media Expansion

*   **Iteration ID:** `I4`
*   **Goal:** Reach feature parity for consignment vendors, loyalty/rewards, POS offline workflows, and full media processing (video/FFmpeg), preparing the platform for multi-channel sales and vendor payouts.
*   **Prerequisites:** `I1`–`I3`
*   **Iteration KPIs:** Consignment payout calc accuracy ±$0.01, loyalty ledger consistency 100%, POS offline replay success 99%, video processing success >99.5% with queue wait <10 min.
*   **Iteration Risks & Mitigations:**
    - Consignment payout timing errors → embed automated tests + reconciliation jobs and freeze payouts behind feature flag until reports validated.
    - POS offline conflicts → design deterministic merge rules, require unique offline IDs, and add monitoring to detect duplicates.
    - FFmpeg resource spikes → dedicate worker deployment with HPA + CPU/memory guards from Clarification 2.
*   **Tasks:**

<!-- anchor: task-i4-t1 -->
*   **Task 4.1:**
    *   **Task ID:** `I4.T1`
    *   **Description:** Implement consignment domain (Consignor, ConsignmentItem, intake batches, commission schedules) plus admin APIs for onboarding, cost basis entry, and status management; include encryption for tax fields via pgcrypto.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** ERD, Clarification 4, Payment provider docs.
    *   **Input Files:** ["src/main/java/com/village/consignment", "docs/diagrams/domain_erd.puml", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/java/com/village/consignment/ConsignorResource.java", "src/main/java/com/village/consignment/BatchIntakeService.java", "src/main/java/com/village/consignment/entity/ConsignmentItemEntity.java", "src/test/java/com/village/consignment/ConsignorResourceIT.java"]
    *   **Deliverables:** REST APIs, service layer, encryption helpers, tests verifying tenant isolation + commission calculations, SQL migrations for consignment tables + RLS policies.
    *   **Acceptance Criteria:**
        - Intake flow handles batch creation, auto SKU assignment, and ties to variants + inventory adjustments.
        - Commission rules stored per vendor/category, with validations + audit logging.
        - Tests cover encryption/decryption, data retention hooks, and sample payout math.
    *   **Dependencies:** [`I2.T1`, `I2.T2`]
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t2 -->
*   **Task 4.2:**
    *   **Task ID:** `I4.T2`
    *   **Description:** Build consignment vendor portal (Qute or SPA route) for balance view, item status, statements, and notification preferences; integrate with Stripe Express onboarding and payout schedule display.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Task `I4.T1`, payment provider data, theme tokens.
    *   **Input Files:** ["src/main/resources/qute/consignor", "src/main/java/com/village/consignment", "src/main/webui/src"]
    *   **Target Files:** ["src/main/resources/qute/pages/consignor-dashboard.html", "src/main/java/com/village/consignment/ConsignorPortalResource.java", "src/test/java/com/village/consignment/ConsignorPortalTest.java"]
    *   **Deliverables:** Portal page(s), backend resource returning aggregated data, tests verifying tenant scoping + impersonation restrictions.
    *   **Acceptance Criteria:**
        - Portal shows pending/available balances, payout history, notifications, and CTA for Stripe Express onboarding.
        - Access controlled via signed tokens; impersonation logs entries when platform admin enters portal.
        - UI meets accessibility criteria (WCAG 2.1 AA) and supports bilingual copy placeholders.
    *   **Dependencies:** [`I4.T1`, `I3.T3`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t3 -->
*   **Task 4.3:**
    *   **Task ID:** `I4.T3`
    *   **Description:** Implement loyalty module (ledger entries, point accrual, redemption reservations, tier management, expiration jobs) with admin configuration UI and storefront components (account + cart redemption slider).
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Clarification 7, Section 9 requirements, checkout flows from `I3`.
    *   **Input Files:** ["src/main/java/com/village/loyalty", "src/main/webui/src/views", "src/main/resources/qute/components", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/java/com/village/loyalty/LoyaltyResource.java", "src/main/java/com/village/loyalty/LoyaltyService.java", "src/main/java/com/village/loyalty/LedgerJob.java", "src/main/resources/qute/components/loyalty-panel.html", "src/main/webui/src/views/LoyaltyView.vue", "src/test/java/com/village/loyalty/LoyaltyServiceTest.java"]
    *   **Deliverables:** APIs/services/jobs, storefront partials, admin UI for tiers and redemption rules, tests covering accrual/reservation/expiration.
    *   **Acceptance Criteria:**
        - Points ledger records accrual/redemption with optimistic locking; reservation job cleans expired holds.
        - Storefront/cart redemption control enforces available balance + warns on expiration.
        - Admin UI supports tier creation, preview of rewards, and feature flag gating for pilot tenants.
    *   **Dependencies:** [`I3.T2`]
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t4 -->
*   **Task 4.4:**
    *   **Task ID:** `I4.T4`
    *   **Description:** Deliver gift card and store credit subsystems with APIs, admin CRUD, checkout integration, and ledger entries reused by loyalty/refund flows; include secure code generation/storage.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Section 8 payments, ERD support entities, refund tasks.
    *   **Input Files:** ["src/main/java/com/village/giftcard", "src/main/java/com/village/orders", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/java/com/village/giftcard/GiftCardResource.java", "src/main/java/com/village/giftcard/GiftCardService.java", "src/main/java/com/village/giftcard/StoreCreditLedger.java", "src/test/java/com/village/giftcard/GiftCardResourceIT.java", "src/main/webui/src/views/GiftCardView.vue"]
    *   **Deliverables:** Gift card/credit APIs, admin UI, checkout integration, tests verifying balance operations + security.
    *   **Acceptance Criteria:**
        - Gift card codes hashed + salted, redemption/resend flows audited.
        - Checkout and refunds integrate store credit gracefully with loyalty/reservations.
        - UI and APIs follow OpenAPI spec, coverage ≥85% across module.
    *   **Dependencies:** [`I3.T1`, `I3.T2`, `I3.T9`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t5 -->
*   **Task 4.5:**
    *   **Task ID:** `I4.T5`
    *   **Description:** Implement POS web shell (Vue) with offline storage (IndexedDB), hardware status footer, catalog search, cart, tender types (cash/card/store credit), and offline queue encryption per Clarifications.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** POS requirements, checkout APIs, loyalty/gift card modules.
    *   **Input Files:** ["src/main/webui/src", "api/openapi.yaml", "docs/architecture/background_jobs.md"]
    *   **Target Files:** ["src/main/webui/src/views/POSView.vue", "src/main/webui/src/stores/pos.ts", "src/main/webui/src/components/OfflineQueuePanel.vue", "src/main/webui/src/service-worker.ts", "tests/e2e/pos/offline.spec.ts"]
    *   **Deliverables:** POS UI, service worker/IndexedDB setup, offline queue logic, tests (unit + Playwright) verifying offline/online transitions.
    *   **Acceptance Criteria:**
        - POS handles barcode search, split payments, offline queue creation + replay.
        - Offline data encrypted, includes TTL + retry/backoff configuration.
        - Tests simulate loss of connectivity and verify queue flush when restored.
    *   **Dependencies:** [`I3.T2`, `I3.T3`, `I4.T4`]
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t6 -->
*   **Task 4.6:**
    *   **Task ID:** `I4.T6`
    *   **Description:** Extend media pipeline to video: integrate FFmpeg binary, define CRITICAL worker deployment, handle transcoding (MP4/HLS), poster extraction, and metadata storage with retries + metrics.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Async blueprint, FFmpeg clarification, docker stack.
    *   **Input Files:** ["src/main/java/com/village/media", "docs/diagrams/media_pipeline.mmd", "docker/docker-compose.yaml"]
    *   **Target Files:** ["src/main/java/com/village/media/VideoJobHandler.java", "src/main/java/com/village/media/MediaWorkerConfig.java", "src/test/java/com/village/media/VideoJobHandlerTest.java", "infra/kustomize/base/media-worker-deployment.yaml"]
    *   **Deliverables:** Video job handler, worker deployment config, tests simulating FFmpeg CLI invocation (mocked), metrics instrumentation.
    *   **Acceptance Criteria:**
        - Video jobs enforce timeouts, resource limits, output MP4 + optional HLS, and write poster frame.
        - Worker deployment uses separate container image with FFmpeg + queue subscription.
        - Tests ensure failure retries + error logging; docs describe scaling + troubleshooting.
    *   **Dependencies:** [`I2.T7`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t7 -->
*   **Task 4.7:**
    *   **Task ID:** `I4.T7`
    *   **Description:** Produce operations runbook (docs/operations/runbook.md) covering deployment, blue/green, background job tuning, Stripe/FFmpeg incident response, POS offline handling, and SLA dashboards.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Outputs from Tasks `I3.T3`, `I3.T5`, `I4.T5`, `I4.T6`, Section 3 Operational guardrails.
    *   **Input Files:** ["docs/operations/runbook.md", "docs/architecture/background_jobs.md", "docs/architecture/platform_ops.md"]
    *   **Target Files:** ["docs/operations/runbook.md"]
    *   **Deliverables:** Detailed runbook with checklists, alert tables, escalation matrix, referencing diagrams.
    *   **Acceptance Criteria:**
        - Runbook describes rollback/killswitch steps for checkout, payments, POS, media, consignment payouts.
        - Includes metrics + dashboards mapping, on-call rotations, scripts for tenant isolation verification.
        - Reviewed notes for future automation tasks logged in doc.
    *   **Dependencies:** [`I4.T5`, `I4.T6`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t8 -->
*   **Task 4.8:**
    *   **Task ID:** `I4.T8`
    *   **Description:** Implement loyalty/consignment reporting aggregates and scheduled exports (CSV/JSON) plus admin UI to request/download statements, ensuring background jobs handle hot + archived data ranges.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Reporting blueprint, loyalty + consignment modules, background jobs doc.
    *   **Input Files:** ["src/main/java/com/village/reporting", "docs/architecture/background_jobs.md", "src/main/webui/src/views", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/java/com/village/reporting/ReportJobService.java", "src/main/java/com/village/reporting/ConsignmentAggregateView.java", "src/main/webui/src/views/ReportsView.vue", "src/test/java/com/village/reporting/ReportJobServiceTest.java"]
    *   **Deliverables:** Aggregate builders, scheduled job, download endpoints, admin UI for requesting exports, tests verifying partition-aware queries.
    *   **Acceptance Criteria:**
        - Reports respect tenant_id + retention policies, storing manifest metadata for archived pulls.
        - Admin UI shows status/progress, allows cancellation, and surfaces download link with TTL.
        - Jobs emit Prometheus metrics for queue time and completion.
    *   **Dependencies:** [`I4.T1`, `I4.T3`]
    *   **Parallelizable:** No.

*   **Exit Criteria:**
    - Consignment, loyalty, gift card, and POS modules feature-complete behind pilot flags, with media video processing running on dedicated workers and runbook authored.
    - Reporting exports for consignment/loyalty available with tested background jobs; metrics/dashboards configured per runbook instructions.
    - QA automation for POS offline + loyalty redemption integrated into CI with documentation for pilot tenant enablement.
