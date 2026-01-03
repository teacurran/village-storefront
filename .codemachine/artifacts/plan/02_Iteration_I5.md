<!-- anchor: iteration-5-plan -->
### Iteration 5: POS, Headless API Completion, and Operational Hardening

* **Iteration ID:** `I5`
* **Goal:** Finalize headless APIs, deliver POS offline workflows (diagram + implementation), integrate shipping/returns orchestration, polish payments/ledger (gift cards/store credit), harden performance + operational tooling, and prep GA artifacts + plan manifest references.
* **Prerequisites:** `I1`–`I4` outputs (loyalty, platform admin, reporting, media pipeline, ADRs).
* **Tasks:**

<!-- anchor: task-i5-t1 -->
* **Task 5.1:**
    * **Task ID:** `I5.T1`
    * **Description:** Complete headless API suite: finalize OpenAPI headless spec covering catalog/cart/order/customer endpoints with OAuth scopes, implement rate limiting middleware, and add integration tests simulating static-site usage.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Existing OpenAPI specs, Tenant filter, Identity scopes.
    * **Input Files**: ["api/openapi-headless.yaml", "api/openapi-base.yaml", "src/main/java/com/village/storefront/headless/**"]
    * **Target Files:** ["api/openapi-headless.yaml", "src/main/java/com/village/storefront/headless/*.java", "src/test/java/com/village/storefront/headless/HeadlessApiIT.java"]
    * **Deliverables:** Separate spec file for headless APIs, Quarkus resources implementing endpoints, tests verifying rate limiting + OAuth scopes.
    * **Acceptance Criteria:**
        - `swagger-cli validate` passes for headless spec referencing shared components.
        - OAuth client credentials with scopes `catalog:read`, `cart:write`, `orders:read` enforced by annotations.
        - Integration tests simulate multi-tenant clients to ensure RLS + rate limiting hold.
    * **Dependencies:** `I2.T5`, `I4.T5`
    * **Parallelizable:** No

<!-- anchor: task-i5-t2 -->
* **Task 5.2:**
    * **Task ID:** `I5.T2`
    * **Description:** Draw POS Offline Flow diagram (Mermaid flowchart) covering device login, local queueing, encryption, sync, reconciliation, and conflict handling aligned with Section 3 Flow narrative.
    * **Agent Type Hint:** `DiagrammingAgent`
    * **Inputs:** Section 3 POS requirements, ADRs, existing checkout diagram.
    * **Input Files**: ["docs/diagrams/component-overview.puml", "docs/adr/0001-delayed-job-governance.md"]
    * **Target Files:** ["docs/diagrams/pos-offline-flow.mmd"]
    * **Deliverables:** Flowchart with nodes for Device, Identity, Checkout API, Offline Storage, Sync Worker, Reporting, Audit.
    * **Acceptance Criteria:**
        - Diagram renders and indicates encryption steps + duplicate prevention.
        - Captures manual override path + support escalation trigger.
        - Linked from README + POS docs.
    * **Dependencies:** `I3.T6`
    * **Parallelizable:** Yes

<!-- anchor: task-i5-t3 -->
* **Task 5.3:**
    * **Task ID:** `I5.T3`
    * **Description:** Implement POS backend + SPA module: device registration, PIN auth, quick cart entry hooking into checkout endpoints, offline queue serialization, reconciliation API, and admin UI for monitoring devices.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** POS diagram, checkout services, identity.
    * **Input Files**: ["docs/diagrams/pos-offline-flow.mmd", "src/main/java/com/village/storefront/pos/**", "src/main/webui/src/views/pos/**"]
    * **Target Files:** ["src/main/java/com/village/storefront/pos/*.java", "src/main/resources/db/migrations/0011_pos.sql", "src/test/java/com/village/storefront/pos/PosResourceTest.java", "src/main/webui/src/views/pos/*.vue", "src/main/webui/src/stores/pos.ts"]
    * **Deliverables:** POS REST endpoints + Vue views, offline queue encryption utilities, device heartbeat metrics, tests verifying offline to online reconciliation.
    * **Acceptance Criteria:**
        - Offline queue encrypted with rotated keys; duplicates prevented via UUID idempotency.
        - Device dashboards show heartbeat + queue depth; tests confirm unauthorized devices blocked.
        - Integration tests simulate network drop + replay success.
    * **Dependencies:** `I5.T2`, `I2.T6`
    * **Parallelizable:** No

<!-- anchor: task-i5-t4 -->
* **Task 5.4:**
    * **Task ID:** `I5.T4`
    * **Description:** Integrate shipping + returns orchestration: USPS/UPS/FedEx adapters, rate caching, label generation jobs, RMA endpoints, restocking logic, and admin UI flows for returns.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Section 7 shipping requirements, Integration adapter layer, reporting service.
    * **Input Files**: ["src/main/java/com/village/storefront/shipping/**", "src/main/java/com/village/storefront/returns/**", "api/openapi-base.yaml", "docs/diagrams/seq-checkout.puml"]
    * **Target Files:** ["src/main/java/com/village/storefront/shipping/*.java", "src/main/java/com/village/storefront/returns/*.java", "src/test/java/com/village/storefront/shipping/ShippingAdapterTest.java", "src/main/resources/db/migrations/0012_shipping.sql", "src/main/webui/src/views/orders/returns.vue"]
    * **Deliverables:** Adapter interfaces + implementations (sandbox), rate caching service, label job integration, RMA endpoints + UI, tests covering retries + fallback.
    * **Acceptance Criteria:**
        - Rate caching honors 15-minute TTL; tests simulate fallback table rates.
        - Label jobs push metrics + integrate with background worker instrumentation.
        - Returns update inventory + consignment states; audit logs capture actions.
    * **Dependencies:** `I3.T6`, `I3.T3`
    * **Parallelizable:** No

<!-- anchor: task-i5-t5 -->
* **Task 5.5:**
    * **Task ID:** `I5.T5`
    * **Description:** Finalize payment ledger features: gift cards, store credit, refund workflows, payout audit enhancements, webhook hardening; ensure PaymentProvider interface ready for PayPal/others.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Payment layer, loyalty service, reporting.
    * **Input Files**: ["src/main/java/com/village/storefront/payments/**", "api/openapi-base.yaml", "docs/diagrams/seq-consignment-payout.puml"]
    * **Target Files:** ["src/main/java/com/village/storefront/payments/GiftCardService.java", "src/main/java/com/village/storefront/payments/StoreCreditService.java", "src/test/java/com/village/storefront/payments/GiftCardServiceTest.java", "src/main/resources/db/migrations/0013_payments.sql"]
    * **Deliverables:** Gift card issuance/redemption APIs, store credit ledger, refund enhancements, webhook validators, documentation for PaymentProvider extension points.
    * **Acceptance Criteria:**
        - Gift card codes hashed, stored with status + expiration; tests cover concurrency.
        - Store credit integrates with checkout + loyalty for redemption priority.
        - Webhook handlers enforce idempotency + signature validation; logs include provider metadata.
    * **Dependencies:** `I3.T7`, `I4.T2`
    * **Parallelizable:** Yes

<!-- anchor: task-i5-t6 -->
* **Task 5.6:**
    * **Task ID:** `I5.T6`
    * **Description:** Performance + reliability hardening: load tests for checkout/storefront/admin, media queue stress tests, POS offline soak tests, and adjustments to HPAs/Kubernetes manifests based on findings.
    * **Agent Type Hint:** `DevOpsAgent`
    * **Inputs:** Observability dashboards, CI outputs, ops scripts.
    * **Input Files**: ["tests/perf/**", "ops/k8s/overlays/**", "ops/scripts/smoke.sh"]
    * **Target Files:** ["tests/perf/checkout-jmeter.plan", "tests/perf/pos-offline-plan.md", "ops/k8s/overlays/prod/hpa.yaml", "docs/operational/perf-readout.md"]
    * **Deliverables:** Perf test plans, scripts, updated HPAs, readout summarizing bottlenecks + mitigations.
    * **Acceptance Criteria:**
        - Checkout meets <800ms P95 under load; POS offline flush <60s; metrics recorded.
        - HPAs updated with CPU/memory/custom metrics thresholds.
        - Readout documents future scaling roadmap + gating issues.
    * **Dependencies:** `I5.T3`, `I3.T6`
    * **Parallelizable:** No

<!-- anchor: task-i5-t7 -->
* **Task 5.7:**
    * **Task ID:** `I5.T7`
    * **Description:** Release readiness + documentation sweep: update README, ops runbooks, plan manifest references, ADR status table, and prepare GA checklist (security signoff, DR drill summary, tenant onboarding guide).
    * **Agent Type Hint:** `DocumentationAgent`
    * **Inputs:** Outputs from all iterations, ops docs, plan files.
    * **Input Files**: ["README.md", "docs/adr/*.md", "ops/observability/*.json", ".codemachine/artifacts/plan/plan_manifest.json"]
    * **Target Files:** ["README.md", "docs/runbooks/*.md", "docs/release/ga-checklist.md", ".codemachine/artifacts/plan/plan_manifest.json"]
    * **Deliverables:** Updated documentation referencing final artifacts, GA checklist, manifest entries for new diagrams/tasks.
    * **Acceptance Criteria:**
        - README includes final architecture summary, build instructions, and artifact index.
        - GA checklist covers security, performance, observability, DR, tenant onboarding.
        - Plan manifest enumerates anchors + file paths for retrieval.
    * **Dependencies:** `I5.T1`–`I5.T6`
    * **Parallelizable:** No

<!-- anchor: iteration-5-exit -->
* **Iteration Exit Criteria:**
  - Headless + POS features pass e2e tests, diagrams linked, and offline flows validated.
  - Shipping/returns + payments/gift cards integrate with reporting + audit pipelines.
  - Performance tests + HPAs updated demonstrating readiness for GA.
  - Documentation + manifest refreshed for downstream agents + support teams.

<!-- anchor: iteration-5-metrics -->
* **Iteration Metrics:**
  - POS offline queue flush success >99% within 60s target; track via observability dashboards.
  - Headless API error rate <0.2% under load; OAuth rate limiting logs kept per tenant.
  - Shipping label job latency <2 minutes end-to-end during load testing.

<!-- anchor: iteration-5-risks -->
* **Iteration Risks & Mitigations:**
  - Carrier API sandbox limits may throttle tests; implement stub servers + configure retry/backoff.
  - Offline POS encryption keys must rotate; coordinate with security for device updates.
  - Gift card/store credit fraud risk mitigated via audit logs + configurable thresholds.

<!-- anchor: iteration-5-followup -->
* **Iteration Follow-Up Actions:**
  - Schedule GA go/no-go review including platform ops, support, finance.
  - Plan backlog items for future payment providers + services/bookings after GA.
