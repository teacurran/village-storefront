<!-- anchor: iteration-4-plan -->
### Iteration 4: Checkout, Payments, Media Pipeline & Loyalty/POS Enhancements

*   **Iteration ID:** `I4`
*   **Goal:** Ship production-ready checkout orchestration with Stripe payments, loyalty/gift card support, media processing pipeline, and POS offline flows.
*   **Prerequisites:** `I1`, `I2`, `I3`
*   **Retrospective Carryover:**
    - Document operational toggles (feature flags) alongside implementation to aid release mgmt.
    - Keep diagrams + ADRs current while coding; do not postpone modeling updates.
    - Reuse background job improvements to avoid reinventing queue logic.
*   **Iteration Milestones & Exit Criteria:**
    1. PaymentProvider + Stripe Connect implementations with webhooks, payouts, disputes support.
    2. Checkout orchestrator orchestrating cart, address validation, shipping rates, loyalty/gift cards, payments, audit logging.
    3. Media pipeline (images + video) with FFmpeg/Thumbnailator jobs, signed URLs, CDN metadata, tenant quotas.
    4. Loyalty program ledger + redemption logic integrated with checkout + admin UI.
    5. POS offline workflows + Stripe Terminal bridging documented and partially implemented.
    6. Media pipeline sequence diagram + docs ready for ops handoff.

<!-- anchor: task-i4-t1 -->
*   **Task 4.1:**
    *   **Task ID:** `I4.T1`
    *   **Description:** Implement PaymentProvider framework + Stripe providers (PaymentProvider, PaymentMethodProvider, MarketplaceProvider, WebhookHandler); include Connect onboarding, payout scheduling, fee calculation, webhook ingestion, integration tests.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** ADRs, OpenAPI, Stripe docs.
    *   **Input Files:** [`api/v1/openapi.yaml`, `docs/adr/ADR-004-consignment-payouts.md`, `docs/diagrams/sequence_checkout_payment.mmd`]
    *   **Target Files:** [`src/main/java/com/village/payment/**`, `tests/backend/StripeProviderTest.java`, `tests/backend/StripeWebhookIT.java`, `docs/payments/stripe_connect.md`]
    *   **Deliverables:** Payment provider interfaces + Stripe implementation, webhook idempotency storage, docs describing onboarding + platform fees.
    *   **Acceptance Criteria:** Stripe sandbox integration works end-to-end, platform fees configurable per tenant, webhooks persisted + idempotent, docs show onboarding steps.
    *   **Testing Guidance:** Use Stripe CLI/webhook emulator, integration tests verifying failure handling, include contract tests for PaymentProvider interface.
    *   **Observability Hooks:** Emit logs for payment lifecycle (intent created, succeeded, failed) w/ tenant/payment ids; metrics for webhook latency + payout backlog.
    *   **Dependencies:** `I2.T4`, `I3.T1`, `I3.T3`.
    *   **Parallelizable:** Limited.

<!-- anchor: task-i4-t2 -->
*   **Task 4.2:**
    *   **Task ID:** `I4.T2`
    *   **Description:** Implement checkout orchestrator: saga handling address validation, shipping rates (USPS/UPS/FedEx adapters), loyalty redemption, gift cards/store credit, payment capture, audit logging, error handling, retrieval endpoints.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Sequence diagram, cart services, payment provider, loyalty spec.
    *   **Input Files:** [`docs/diagrams/sequence_checkout_payment.mmd`, `src/main/java/com/village/checkout/cart/**`, `src/main/java/com/village/payment/**`, `docs/adr/ADR-003-checkout-saga.md`]
    *   **Target Files:** [`src/main/java/com/village/checkout/orchestrator/**`, `src/main/java/com/village/shipping/**`, `tests/backend/CheckoutSagaTest.java`, `tests/backend/ShippingAdapterIT.java`, `docs/checkout/saga.md`]
    *   **Deliverables:** Orchestrator service, adapter layer wrappers, audit + domain events, doc describing compensation + kill switches.
    *   **Acceptance Criteria:** Saga handles success/failure, integrates with payments + loyalty, shipping adapters stub external APIs, logs actions with trace IDs, tests cover success + failure + compensations.
    *   **Testing Guidance:** Build scenario matrix (guest vs auth, loyalty vs not), run integration tests with Testcontainers + mock carriers; include contract tests vs OpenAPI.
    *   **Observability Hooks:** Add tracing spans for each step, metrics for checkout latency, logs for failure funnel.
    *   **Dependencies:** `I2.T4`, `I4.T1`, `I4.T4`.
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t3 -->
*   **Task 4.3:**
    *   **Task ID:** `I4.T3`
    *   **Description:** Build media pipeline: upload negotiation endpoints, presigned URLs, tenant quotas, Thumbnailator for images, FFmpeg for video with HLS, background jobs, signed URL service, metadata persistence, API integration for storefront/admin.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Media requirements, ERD (MediaAsset/Derivative), job framework.
    *   **Input Files:** [`docs/diagrams/datamodel_erd.puml`, `docs/architecture_overview.md`, `src/main/java/com/village/jobs/**`]
    *   **Target Files:** [`src/main/java/com/village/media/**`, `tests/backend/MediaPipelineTest.java`, `docs/media/pipeline.md`, `src/main/resources/application.properties`]
    *   **Deliverables:** Media services, FFmpeg invocation wrapper, queue integration, doc describing storage layout + TTLs.
    *   **Acceptance Criteria:** Images/video processed with size tiers, signed URLs generated per tenant, quotas enforced, tests simulate FFmpeg via stub, doc explains failure retries.
    *   **Testing Guidance:** Use small media fixtures, run FFmpeg locally, ensure tests cover quota enforcement + failure case.
    *   **Observability Hooks:** Metrics for job duration, queue depth, storage usage; logs include mediaId + tenantId.
    *   **Dependencies:** `I1.T5`, `I3.T6`.
    *   **Parallelizable:** Limited.

<!-- anchor: task-i4-t4 -->
*   **Task 4.4:**
    *   **Task ID:** `I4.T4`
    *   **Description:** Implement loyalty + rewards module: point accrual rules, ledger, redemption engine, tier calculations, admin APIs, storefront components integration (cart summary), reporting hooks.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Requirements, ERD (LoyaltyLedger), checkout saga design.
    *   **Input Files:** [`docs/diagrams/datamodel_erd.puml`, `api/v1/openapi.yaml`, `docs/checkout/saga.md`]
    *   **Target Files:** [`src/main/java/com/village/loyalty/**`, `tests/backend/LoyaltyServiceTest.java`, `tests/backend/LoyaltyLedgerIT.java`, `docs/loyalty/program.md`]
    *   **Deliverables:** Ledger entity/service, accrual + redemption APIs, admin endpoints for configuration, documentation for tier logic.
    *   **Acceptance Criteria:** Points accrual + redemption operate per config, ledger persists with audit fields, checkout integrates, admin endpoints secured, docs outline formulas.
    *   **Testing Guidance:** Fuzz accrual scenarios, include integration tests verifying concurrency + ledger rollback.
    *   **Observability Hooks:** Add metrics for points earned, redemption volume, ledger lag; logs include tenant/customer/tier info.
    *   **Dependencies:** `I2.T4`, `I3.T3`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t5 -->
*   **Task 4.5:**
    *   **Task ID:** `I4.T5`
    *   **Description:** Document media pipeline sequence (upload -> processing -> CDN) via Mermaid + ops runbook; include scaling guidance, failure scenarios, kill switches, capacity planning.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Media implementation, job policies.
    *   **Input Files:** [`src/main/java/com/village/media/**`, `docs/media/pipeline.md`, `k8s/base/deployment-workers.yaml`]
    *   **Target Files:** [`docs/diagrams/sequence_media_pipeline.mmd`, `docs/operations/media_runbook.md`]
    *   **Deliverables:** Diagram + runbook with kill-switch instructions, queue scaling, troubleshooting.
    *   **Acceptance Criteria:** Diagram renders, runbook outlines detection/response steps, references Section 6 verification metrics.
    *   **Testing Guidance:** Walkthrough runbook w/ simulated failure, gather feedback from ops.
    *   **Observability Hooks:** Document metrics/dashboards for pipeline health (processing backlog, error counts).
    *   **Dependencies:** `I4.T3`.
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t6 -->
*   **Task 4.6:**
    *   **Task ID:** `I4.T6`
    *   **Description:** Enhance gift cards + store credit modules and integrate with checkout & POS: APIs for issuance/redemption, ledger, admin UI wiring, POS offline usage.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Checkout saga, loyalty spec, POS requirements.
    *   **Input Files:** [`docs/diagrams/datamodel_erd.puml`, `api/v1/openapi.yaml`, `src/main/java/com/village/checkout/orchestrator/**`, `src/main/java/com/village/pos/**`]
    *   **Target Files:** [`src/main/java/com/village/giftcard/**`, `src/main/java/com/village/storecredit/**`, `tests/backend/GiftCardServiceTest.java`, `tests/backend/StoreCreditIT.java`, `docs/payments/giftcard.md`]
    *   **Deliverables:** Gift card/store credit services, endpoints, integration with checkout/POS, docs for issuance + redemption.
    *   **Acceptance Criteria:** Gift card codes unique + secure, redemption atomic, checkout + POS flows handle partial payments, docs describe lifecycle.
    *   **Testing Guidance:** Integration tests with multi-tender payments, offline POS redemption scenario, load tests for gift card lookups.
    *   **Observability Hooks:** Track issuance/redemption metrics, log suspicious activity, expose ledger health to reporting.
    *   **Dependencies:** `I4.T2`, `I4.T4`.
    *   **Parallelizable:** Limited.

<!-- anchor: task-i4-t7 -->
*   **Task 4.7:**
    *   **Task ID:** `I4.T7`
    *   **Description:** Implement POS offline queue + Stripe Terminal integration: offline storage encryption, sync jobs, UI states, hardware pairing service, documentation.
    *   **Agent Type Hint:** `FrontendAgent` + `BackendAgent` pairing
    *   **Inputs:** POS requirements, job framework, payment provider.
    *   **Input Files:** [`src/main/webui/admin-spa/src/modules/pos/**`, `src/main/java/com/village/payment/**`, `docs/operations/job_runbook.md`]
    *   **Target Files:** [`src/main/webui/admin-spa/src/modules/pos/offline/**`, `src/main/java/com/village/pos/offline/**`, `tests/admin/POSOffline.spec.ts`, `tests/backend/POSOfflineIT.java`, `docs/pos/offline.md`]
    *   **Deliverables:** Offline queue manager, encryption keys, sync job hooking into checkout, UI indicators + hold/resume features, doc for staff training.
    *   **Acceptance Criteria:** Offline queue persists encrypted payloads, sync resumes automatically, UI highlights offline state, Stripe Terminal flows validated in sandbox.
    *   **Testing Guidance:** Simulate offline mode via service worker, run integration tests ensuring duplicates prevented.
    *   **Observability Hooks:** Gauge offline queue depth, metrics for sync success/failure, logs tagging device/location.
    *   **Dependencies:** `I4.T1`, `I4.T2`, `I3.T6`.
    *   **Parallelizable:** No.

*   **Iteration KPIs & Validation Strategy:**
    - PaymentProvider success ≥99% in sandbox load test; webhook latency median <1s.
    - Checkout saga 95th percentile latency <800ms (excluding external calls) measured via integration harness.
    - Media pipeline processes standard image <5s, video <10m; queue metrics accessible.
    - Loyalty ledger integrity cross-checked vs orders; automated job recalculates tiers nightly.
    - Gift card/store credit coverage ≥85% tests, POS offline queue flush <60s on reconnection.
    - Runbook (media + POS) reviewed by ops + support with sign-off recorded.
*   **Iteration Risk Log & Mitigations:**
    - *Stripe API changes:* Monitor release notes; mitigate via feature flag to fall back to basic card flow.
    - *FFmpeg resource spikes:* Use dedicated worker pool + Kubernetes limits.
    - *Saga complexity:* Risk of cascade failures; mitigate with circuit breakers + compensating actions tests.
    - *Offline data loss:* Enforce encryption + checksum; include manual reconciliation instructions.
    - *Gift card fraud:* Add rate limits + audit alerts for suspicious behavior.
*   **Iteration Backlog & Follow-ups:**
    - Plan I5 work for platform payment observability dashboards.
    - Schedule accessibility audit for checkout + POS flows.
    - Create backlog item for multi-currency conversions in loyalty displays.
    - Document future addition of PayPal provider hooking into PaymentProvider interface.
