<!-- anchor: iteration-3-plan -->
### Iteration 3: Cart, Checkout, Payments, and Fulfillment Core

*   **Iteration ID:** `I3`
*   **Goal:** Deliver persistent carts, checkout orchestration, Stripe payment integration, shipping rate adapters, and operational diagrams/tests so orders, refunds, and fulfillment flows reach production-ready maturity.
*   **Prerequisites:** `I1`, `I2`
*   **Iteration KPIs:** Checkout API p95 < 300 ms pre-payment, Stripe webhook latency < 1 s, cart persistence success rate 99.5%, and Playwright checkout smoke passes across tenant themes.
*   **Iteration Risks & Mitigations:**
    - Stripe native build flakiness → rely on test mode keys + retry guard in CI, add sandbox webhooks to compose stack.
    - Shipping API instability → implement carrier fallback to table rates + caching from Clarifications and feature flags for carriers.
    - Cart race conditions → enforce optimistic locking and document saga compensations for loyalty/gift cards.
*   **Tasks:**

<!-- anchor: task-i3-t1 -->
*   **Task 3.1:**
    *   **Task ID:** `I3.T1`
    *   **Description:** Build persistent Cart domain (cart, cart items, discount entries) with REST endpoints for add/update/remove/save-for-later, linking to customer/guest sessions and storing feature flag snapshots.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** ERD, OpenAPI skeleton, catalog APIs, TenantContext modules.
    *   **Input Files:** ["src/main/java/com/village/cart", "docs/diagrams/domain_erd.puml", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/java/com/village/cart/CartResource.java", "src/main/java/com/village/cart/CartService.java", "src/main/java/com/village/cart/model/CartEntity.java", "src/test/java/com/village/cart/CartResourceIT.java"]
    *   **Deliverables:** Cart REST endpoints, service logic, Panache entities, tests verifying multi-tenant isolation + concurrency.
    *   **Acceptance Criteria:**
        - Cart operations idempotent, persist loyalty snapshots, and expire via scheduler hook.
        - Endpoints enforce JWT + guest tokens, return Problem Details on errors.
        - Tests include concurrency scenario verifying optimistic locking.
    *   **Dependencies:** [`I2.T1`, `I2.T4`]
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t2 -->
*   **Task 3.2:**
    *   **Task ID:** `I3.T2`
    *   **Description:** Implement checkout orchestrator service coordinating address validation, shipping rate service, loyalty redemption, gift cards, and cart finalization prior to payment intent creation.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Tasks `I3.T1`, `I2.T2`, Section 5 contract patterns, Clarification on loyalty reservations.
    *   **Input Files:** ["src/main/java/com/village/checkout", "docs/architecture/background_jobs.md", "docs/diagrams/media_pipeline.mmd"]
    *   **Target Files:** ["src/main/java/com/village/checkout/CheckoutResource.java", "src/main/java/com/village/checkout/CheckoutService.java", "src/main/java/com/village/checkout/AddressValidatorAdapter.java", "src/test/java/com/village/checkout/CheckoutServiceTest.java"]
    *   **Deliverables:** REST endpoint plus orchestrator, integration with inventory/loyalty placeholders, tests mocking adapters.
    *   **Acceptance Criteria:**
        - Checkout returns totals summary, shipping options, and provisional loyalty reservation.
        - Integrates with TenantContext + feature flags; invalid addresses produce actionable errors.
        - Tests cover happy path, invalid shipping, and loyalty reservation collisions.
    *   **Dependencies:** [`I3.T1`, `I2.T2`]
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t3 -->
*   **Task 3.3:**
    *   **Task ID:** `I3.T3`
    *   **Description:** Integrate Stripe Connect for payment intents, application fees, payouts, and webhook ingestion; include repository for PaymentIntent/Refund entities and CLI tooling for webhook tunneling in dev.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Payment architecture clarifications, Task `I3.T2`, Async blueprint, docker stack.
    *   **Input Files:** ["src/main/java/com/village/payment", "docs/architecture/background_jobs.md", "docker/docker-compose.yaml"]
    *   **Target Files:** ["src/main/java/com/village/payment/StripePaymentProvider.java", "src/main/java/com/village/payment/PaymentIntentResource.java", "src/main/java/com/village/payment/WebhookHandler.java", "src/test/java/com/village/payment/StripePaymentProviderTest.java", "scripts/dev/stripe_tunnel.sh"]
    *   **Deliverables:** Payment provider implementation, webhook endpoint persisting payloads + idempotency, tests mocking Stripe SDK, dev script for stripe-cli.
    *   **Acceptance Criteria:**
        - Payment intents created with application fee + metadata, webhook updates payment + order states.
        - Idempotency keys stored and validated; secrets pulled from env.
        - Tests simulate success/failure webhooks + refund path, achieving ≥80% coverage.
    *   **Dependencies:** [`I3.T2`]
    *   **Parallelizable:** No.

<!-- anchor: task-i3-t4 -->
*   **Task 3.4:**
    *   **Task ID:** `I3.T4`
    *   **Description:** Author PlantUML checkout + payment sequence diagram capturing tenant resolution, cart orchestration, Stripe interactions, loyalty hold/release, shipping rate adapters, audit logging.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Tasks `I3.T1`–`I3.T3`, architecture clarifications, Section 3 communication patterns.
    *   **Input Files:** ["docs/diagrams/checkout_sequence.puml"]
    *   **Target Files:** ["docs/diagrams/checkout_sequence.puml", "docs/architecture/platform_ops.md"]
    *   **Deliverables:** Sequence diagram + textual summary used by ops + QA to understand saga steps.
    *   **Acceptance Criteria:**
        - Diagram validated, includes TenantContext, AddressValidator, Stripe, Inventory, Loyalty, Audit.
        - Summary lists failure hooks + compensations for shipping failures, payment declines.
    *   **Dependencies:** [`I3.T1`, `I3.T2`, `I3.T3`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t5 -->
*   **Task 3.5:**
    *   **Task ID:** `I3.T5`
    *   **Description:** Implement shipping integration adapters (USPS/UPS/FedEx placeholders) + caching layer abiding 15-minute TTL, plus fallback table-rate configuration.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Clarifications shipping, Integration adapter layer, Async blueprint.
    *   **Input Files:** ["src/main/java/com/village/shipping", "docs/architecture/background_jobs.md", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/java/com/village/shipping/ShippingRateService.java", "src/main/java/com/village/shipping/adapters/CarrierAdapter.java", "src/test/java/com/village/shipping/ShippingRateServiceTest.java", "docs/architecture/platform_ops.md"]
    *   **Deliverables:** Adapter interface + default USPS/UPS/FedEx skeletons pulling mock responses, caching layer, fallback config docs.
    *   **Acceptance Criteria:**
        - Rate service caches per origin/destination/weight, invalidates per TTL, logs metrics.
        - Table-rate fallback toggled via feature flag + config file.
        - Tests stub adapters and verify fallback behavior when adapter fails.
    *   **Dependencies:** [`I3.T2`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t6 -->
*   **Task 3.6:**
    *   **Task ID:** `I3.T6`
    *   **Description:** Extend OpenAPI spec + admin/storefront consumers for cart/checkout/payment/shipping endpoints; regenerate client stubs and update docs/test strategy accordingly.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Tasks `I3.T1`–`I3.T5`, spec file, test strategy.
    *   **Input Files:** ["api/openapi.yaml", "README.md", "docs/quality/test_strategy.md"]
    *   **Target Files:** ["api/openapi.yaml", "README.md", "docs/quality/test_strategy.md"]
    *   **Deliverables:** Updated spec, changelog snippet, guidance for client regen.
    *   **Acceptance Criteria:**
        - Spec includes cart/checkout/payment schemas with examples, hooking into security scopes.
        - README change log outlines new endpoints + compatibility notes.
        - Test strategy updated with coverage requirements for checkout + payment.
    *   **Dependencies:** [`I3.T1`, `I3.T2`, `I3.T3`, `I3.T5`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t7 -->
*   **Task 3.7:**
    *   **Task ID:** `I3.T7`
    *   **Description:** Build admin order dashboard (Vue) showing statuses, filters, timeline view, and inline actions (capture/refund/note) wired to new APIs with optimistic updates + error toasts.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Admin SPA base, OpenAPI spec, payment endpoints, audit log requirements.
    *   **Input Files:** ["src/main/webui/src/views", "src/main/webui/src/stores", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/webui/src/views/OrdersView.vue", "src/main/webui/src/components/OrderTimeline.vue", "src/main/webui/src/stores/orders.ts", "src/main/webui/src/components/RefundDialog.vue"]
    *   **Deliverables:** Vue views/stores/components, integration tests (Vitest) for store logic, screenshot updates for docs.
    *   **Acceptance Criteria:**
        - Order list supports filtering, pagination, inline status badges; timeline shows audit entries.
        - Action dialogs call APIs, handle loading/error states, and refresh list automatically.
        - Unit tests cover store actions; E2E update ensures order view loads sample data.
    *   **Dependencies:** [`I3.T3`, `I3.T6`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t8 -->
*   **Task 3.8:**
    *   **Task ID:** `I3.T8`
    *   **Description:** Expand e2e automation: Playwright checkout flow (guest + logged-in), REST-assured payment/discount validation, plus Stripe webhook replay tests using stripe-cli fixtures.
    *   **Agent Type Hint:** `QAAgent`
    *   **Inputs:** Completed checkout/payment endpoints, e2e runner script, stripe tunnel.
    *   **Input Files:** ["tests/e2e/storefront", "tests/e2e/api", "scripts/qa/run_e2e.sh", "scripts/dev/stripe_tunnel.sh"]
    *   **Target Files:** ["tests/e2e/storefront/checkout.spec.ts", "tests/e2e/api/payment.spec.ts", "scripts/qa/run_e2e.sh", "docs/quality/test_strategy.md"]
    *   **Deliverables:** Playwright spec for checkout, API tests verifying Stripe webhooks, doc updates describing data requirements + secrets.
    *   **Acceptance Criteria:**
        - Playwright spec covers address entry, shipping selection, Stripe test card, order confirmation, screenshot artifact.
        - API tests simulate webhook payloads, verifying idempotency + order status transitions.
        - CI gating ensures checkout suite runs nightly + on release candidates.
    *   **Dependencies:** [`I3.T2`, `I3.T3`, `I3.T6`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i3-t9 -->
*   **Task 3.9:**
    *   **Task ID:** `I3.T9`
    *   **Description:** Implement refund + return initiation endpoints (backend) with audit/event logging, plus admin UI controls hooking into PaymentProvider and Inventory modules for restocking.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Payment + inventory modules, OpenAPI spec, reporting requirements.
    *   **Input Files:** ["src/main/java/com/village/orders", "src/main/java/com/village/payment", "api/openapi.yaml"]
    *   **Target Files:** ["src/main/java/com/village/orders/RefundResource.java", "src/main/java/com/village/orders/ReturnResource.java", "src/main/java/com/village/orders/service/RefundService.java", "src/test/java/com/village/orders/RefundServiceIT.java"]
    *   **Deliverables:** Refund/return APIs, service logic for stock adjustments + audit events, tests verifying multi-tenant safety.
    *   **Acceptance Criteria:**
        - Refund API handles partial/full amounts, writes PaymentIntent + AuditEvent records, triggers loyalty adjustments.
        - Return endpoint queues inventory adjustments + notifications, referencing consignment data when needed.
        - Tests cover concurrency + unauthorized access scenarios.
    *   **Dependencies:** [`I3.T3`, `I3.T5`]
    *   **Parallelizable:** No.

*   **Exit Criteria:**
    - Checkout/cart/payment/shipping endpoints live behind feature flags with contract + e2e tests green, Stripe sandbox transactions succeed end-to-end.
    - Sequence diagrams + OpenAPI spec updated, order dashboard + refund UI functional for pilot tenants, and ops runbooks describe webhook remediation.
    - QA automation plus manual scripts validated by platform team, ensuring rollback/killswitch steps documented for checkout flows.
