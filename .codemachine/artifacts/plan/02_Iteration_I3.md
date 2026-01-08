<!-- anchor: iteration-3-plan -->
### Iteration 3: Checkout, Payments, Consignment & Fulfillment Core

*   **Iteration ID:** `I3`
*   **Goal:** Build checkout/orchestration pipeline spanning cart persistence, address validation, shipping, Stripe payments, consignment attribution, and supporting APIs/async flows.
*   **Prerequisites:** `I1`, `I2`
*   **Tasks:**

<!-- anchor: task-i3-t1 -->
*   **Task 3.1:**
    *   **Task ID:** `I3.T1`
    *   **Description:** Implement Cart + Checkout services (cart storage, promo validation, saga orchestrator) with transactional safeguards, idempotency keys, and domain events (CartUpdated, OrderInitiated, OrderPaid).
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Catalog APIs, ERD, feature flags.
    *   **Input Files:** [`modules/checkout-orders/src/main/java/...`, `docs/diagrams/erd.mmd`, `api/storefront-admin-platform.yaml`]
    *   **Target Files:** [`modules/checkout-orders/src/main/java/.../CartService.java`, `CheckoutOrchestrator.java`, `OrderEntity.java`, `CartRepository.java`, `modules/checkout-orders/src/test/java/...`]
    *   **Deliverables:** Services managing guest/auth carts, promotions, line-level adjustments; Order aggregate with statuses/states; domain events persisted; tests for concurrency + RLS.
    *   **Acceptance Criteria:** Integration tests simulate multi-tenant carts + orders; idempotency key support validated; events recorded for reporting; documentation updated.
    *   **Dependencies:** `I2.T1-T2`.
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t2 -->
*   **Task 3.2:**
    *   **Task ID:** `I3.T2`
    *   **Description:** Extend OpenAPI + REST controllers for cart/checkout/order endpoints (storefront + admin), including ProblemDetails, rate limits, and webhook callback modeling.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Task I3.T1, API skeleton.
    *   **Input Files:** [`api/storefront-admin-platform.yaml`, `modules/checkout-orders/src/main/java/.../CheckoutResource.java`]
    *   **Target Files:** [`api/storefront-admin-platform.yaml`, `modules/checkout-orders/src/main/java/.../CartResource.java`, `CheckoutResource.java`, `OrderResource.java`, `modules/checkout-orders/src/test/java/.../CheckoutResourceTest.java`]
    *   **Deliverables:** Documented endpoints for cart operations, checkout steps, order admin operations; tests verifying success/errors and security scopes.
    *   **Acceptance Criteria:** Spec lint + contract tests pass; controllers use TenantContext & feature flags; e2e stub uses RestAssured to run sample checkout.
    *   **Dependencies:** `I3.T1`, `I1.T4`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t3 -->
*   **Task 3.3:**
    *   **Task ID:** `I3.T3`
    *   **Description:** Implement PaymentProvider abstraction + Stripe Connect provider (charge/capture/refund/payout, onboarding, webhook receiver) plus PaymentIntent/Refund entities + migrations.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Payment requirements, Stripe docs.
    *   **Input Files:** [`modules/payments/src/main/java/...`, `migrations/mybatis`, `docs/java-project-standards.adoc`]
    *   **Target Files:** [`modules/payments/src/main/java/.../PaymentProvider.java`, `StripePaymentProvider.java`, `WebhookHandler.java`, `modules/payments/src/test/java/...`, `migrations/mybatis/20240713_payments.sql`, `docs/architecture/async/job-catalog.md`]
    *   **Deliverables:** Interfaces + implementation, webhook endpoint, MyBatis migrations for payment tables, job catalog entry for payout reconciliation, tests mocking Stripe SDK.
    *   **Acceptance Criteria:** Stripe sandbox integration test passes (intent create/capture/refund), webhooks stored idempotently, payout job enqueues; provider annotated with feature flag for future processors.
    *   **Dependencies:** `I3.T1`, `I1.T7` (compose), `I1.T6`.
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t4 -->
*   **Task 3.4:**
    *   **Task ID:** `I3.T4`
    *   **Description:** Integrate address validation + shipping rate adapters (USPS Web Tools, UPS, FedEx) with caching + fallback table rates; expose shipping profiles + label endpoints.
    *   **Agent Type Hint:** `IntegrationAgent`
    *   **Inputs:** Shipping requirements, integration adapter pattern.
    *   **Input Files:** [`modules/integration/src/main/java/...`, `modules/checkout-orders/src/main/java/.../ShippingService.java`]
    *   **Target Files:** [`modules/integration/src/main/java/.../CarrierRateAdapter.java`, `USPSAdapter.java`, `UPSAdapter.java`, `FedExAdapter.java`, `modules/checkout-orders/src/main/java/.../ShippingService.java`, `modules/checkout-orders/src/test/java/.../ShippingServiceTest.java`, `docs/architecture/ops/catalog-runbook.md`]
    *   **Deliverables:** Adapter interfaces w/ retries/backoff, shipping service hooking into checkout, caching for 15 minutes, tests mocking carrier responses, runbook updates.
    *   **Acceptance Criteria:** Rate caching working (unit test verifying TTL), fallback table rate when carrier offline, logging includes correlation IDs, OpenAPI updated with rate endpoints.
    *   **Dependencies:** `I2` outputs, `I3.T1`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t5 -->
*   **Task 3.5:**
    *   **Task ID:** `I3.T5`
    *   **Description:** Build Consignment domain foundations (entities, services, payouts ledger) tying variants to consignors, commission rules, intake + status tracking.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Consignment requirements, ERD.
    *   **Input Files:** [`modules/consignment/src/main/java/...`, `docs/diagrams/erd.mmd`, `migrations/mybatis`]
    *   **Target Files:** [`migrations/mybatis/20240713_consignment.sql`, `modules/consignment/src/main/java/.../ConsignorEntity.java`, `ConsignmentItemEntity.java`, `ConsignmentService.java`, `PayoutLedgerService.java`, `modules/consignment/src/test/java/...`]
    *   **Deliverables:** Data model + services for consignor registration, commission rules, balance ledger (pending/available), event emission (ConsignmentItemReceived, ConsignmentPayoutDue), unit/integration tests.
    *   **Acceptance Criteria:** Services enforce commission configs, ledger updates triggered by sale/refund events; tests verifying multi-tenant RLS; docs describing payout sweep logic.
    *   **Dependencies:** `I2` outputs, `I3.T1` (order events).
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t6 -->
*   **Task 3.6:**
    *   **Task ID:** `I3.T6`
    *   **Description:** Document Media + POS critical flows via Mermaid sequence diagrams (media upload→processing→delivery, POS offline queue→replay) referencing future implementation steps.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Requirements sections (media, POS), architecture plan.
    *   **Input Files:** [`docs/diagrams/media-flow.mmd`, `docs/diagrams/pos-offline.mmd`, `.codemachine/artifacts/plan/01_Plan_Overview_and_Setup.md`]
    *   **Target Files:** [`docs/diagrams/media-flow.mmd`, `docs/diagrams/pos-offline.mmd`, `docs/architecture/ops/deployment-architecture.md`]
    *   **Deliverables:** Sequence diagrams covering presigned uploads, FFmpeg workers, R2 storage, admin/storefront updates; POS offline capture/resume, encryption, replay; notes on metrics/alerts.
    *   **Acceptance Criteria:** Diagrams reference queue priorities, API endpoints, feature flags; runbook updated with cross-links; reviewed with Media/POS leads.
    *   **Dependencies:** `I1` outputs.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t7 -->
*   **Task 3.7:**
    *   **Task ID:** `I3.T7`
    *   **Description:** Expand docker-compose + dev bootstrap to include Stripe CLI forwarder, USPS mock, UPS/FedEx stubs, and seeding for consignment data to support QA.
    *   **Agent Type Hint:** `DevExAgent`
    *   **Inputs:** Local dev requirements, tasks I3.T3-T5 outputs.
    *   **Input Files:** [`docker/docker-compose.yml`, `scripts/dev/bootstrap.sh`]
    *   **Target Files:** [`docker/docker-compose.yml`, `scripts/dev/bootstrap.sh`, `README.md`]
    *   **Deliverables:** Additional services + docs for running Stripe CLI webhook tunnel, shipping mock APIs, consignment sample data, instructions for QA flows.
    *   **Acceptance Criteria:** Compose stack runs new services; README describes hooking Stripe CLI; sample data demonstrates multi-tenant consignment; tests referencing mocks run in CI.
    *   **Dependencies:** `I1.T7`, `I3.T3`, `I3.T4`, `I3.T5`.
    *   **Parallelizable:** Yes.
