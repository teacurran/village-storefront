<!-- anchor: iteration-2-plan -->
### Iteration 2: Commerce Domain & Experience Foundations

*   **Iteration ID:** `I2`
*   **Goal:** Implement catalog/inventory services, cart APIs, storefront Qute layout, and admin SPA scaffolding so real data can flow through tenant-aware surfaces.
*   **Prerequisites:** `I1`
*   **Retrospective Carryover:**
    - Apply iteration-1 retro: ensure manifest entries created immediately after each artifact to avoid scramble.
    - CI runtime targets (<12 min) monitor early; document any pipeline hot spots discovered while adding UI bundles.
    - Keep ADR addendums short and reference existing anchors; avoid duplicating context.
*   **Iteration Milestones & Exit Criteria:**
    1. Catalog + inventory modules compiled with Panache entities, repositories, service tests, and data loaders tied to ERD.
    2. Storefront Qute layout + Tailwind pipeline render sample tenant pages referencing feature flags and Money formatting.
    3. Admin SPA bootstrapped (Vue 3, Vite, PrimeVue, Tailwind design tokens) with authentication guard and API client wiring.
    4. Cart + pricing services exposed via REST + spec-updated endpoints; unit/integration coverage meeting ≥85% branch coverage.
    5. Checkout sequence diagram + address validation adapter spec ready for payment iteration.
    6. Multi-tenant integration tests verifying PostgreSQL RLS + Panache filters for catalog/cart endpoints.

<!-- anchor: task-i2-t1 -->
*   **Task 2.1:**
    *   **Task ID:** `I2.T1`
    *   **Description:** Implement catalog + inventory domain: Panache entities, repositories, service layer with tenant filters, import/export DTOs, MapStruct mappers, and unit tests; include fixture loaders + sample data script.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** ERD, OpenAPI schemas, ADR-001.
    *   **Input Files:** [`docs/diagrams/datamodel_erd.puml`, `api/v1/openapi.yaml`, `docs/adr/ADR-001-tenancy.md`]
    *   **Target Files:** [`src/main/java/com/village/catalog/**`, `src/main/java/com/village/inventory/**`, `tests/backend/CatalogServiceTest.java`, `tools/scripts/sample_catalog_loader.sql`]
    *   **Deliverables:** Domain models covering products/variants/categories/collections/inventory levels, service methods for CRUD + search placeholders, SQL loader script, doc updates referencing DTO mapping.
    *   **Acceptance Criteria:** Entities include tenant_id + versioning, repositories enforce Panache filters, service tests cover CRUD + variant explosion, loader script seeds dev DB without violating constraints, README instructions for running loader.
    *   **Testing Guidance:** Add Quarkus @Native tests for repository filtering, run integration tests with H2/PostgreSQL containers, measure coverage >85% for catalog module.
    *   **Observability Hooks:** Instrument service methods with structured logs (tenant_id, product_id) and define Micrometer metrics placeholders for catalog queries.
    *   **Dependencies:** `I1.T4`, `I1.T6`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t2 -->
*   **Task 2.2:**
    *   **Task ID:** `I2.T2`
    *   **Description:** Build storefront base: Qute layout, Tailwind config ingestion from tenant tokens, shared components (Hero, ProductCard, Footer), PrimeUI integration, Money formatting helpers, localization scaffolding (en/es message bundles).
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Architecture overview, design token spec, catalog service outputs.
    *   **Input Files:** [`docs/architecture_overview.md`, `docs/diagrams/component_overview.puml`, `src/main/resources/messages/messages.properties`]
    *   **Target Files:** [`src/main/resources/qute/templates/**`, `tailwind.config.js`, `src/main/resources/messages/messages.properties`, `src/main/resources/messages/messages_es.properties`, `tests/storefront/StorefrontRenderingTest.java`]
    *   **Deliverables:** Base layout with responsive grid, hero/category/product partials, Tailwind pipeline with tenant override hook, bilingual message bundles, SSR smoke tests.
    *   **Acceptance Criteria:** Qute renders sample data via dev mode, CSS generated per tenant tokens, message bundles fallback gracefully, tests verify multi-tenant theming, docs describe customizing tokens.
    *   **Testing Guidance:** Run `mvn test -Dtest=StorefrontRenderingTest` plus visual diff (Percy or screenshot script) for two sample tenants.
    *   **Observability Hooks:** Add server-side timing logs for Qute templates and note instrumentation plan for LCP metrics.
    *   **Dependencies:** `I2.T1`.
    *   **Parallelizable:** Limited (blocks on catalog data readiness).

<!-- anchor: task-i2-t3 -->
*   **Task 2.3:**
    *   **Task ID:** `I2.T3`
    *   **Description:** Scaffold admin SPA + POS shared shell: Vue 3 + Vite + PrimeVue + Tailwind, Pinia stores (auth, tenant, catalog), API client generator from OpenAPI, basic routing + layout + command palette stub, Storybook baseline.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** OpenAPI spec, design tokens, CI workflow.
    *   **Input Files:** [`api/v1/openapi.yaml`, `tailwind.config.js`, `package.json`]
    *   **Target Files:** [`src/main/webui/admin-spa/src/**`, `src/main/webui/admin-spa/tailwind.config.cjs`, `src/main/webui/admin-spa/src/stores/**`, `src/main/webui/admin-spa/src/components/base/**`, `src/main/webui/admin-spa/storybook/**`, `tests/admin/AdminShell.spec.ts`]
    *   **Deliverables:** Running dev server, Storybook with atoms, generated API client types, command palette skeleton, documentation for npm scripts + CI integration.
    *   **Acceptance Criteria:** `npm run dev` works, `npm run build` produces hashed assets consumed via Quinoa, Storybook renders at least 5 base components with knobs, command palette toggles, Pinia stores hold auth context.
    *   **Testing Guidance:** Configure Vitest + Cypress smoke, ensure watchers for lint/test run in CI; include snapshot tests for atoms.
    *   **Observability Hooks:** Instrument SPA bootstrap to emit telemetry events (app load time, route changes) and log impersonation banners per Section 14 requirements.
    *   **Dependencies:** `I1.T6`, `I2.T1`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t4 -->
*   **Task 2.4:**
    *   **Task ID:** `I2.T4`
    *   **Description:** Implement cart + pricing services: persistent carts, cart lines, promotions placeholders, REST endpoints, Panache models, service tests, and API spec updates; include security guard rails for tenant isolation.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** OpenAPI spec, ERD, catalog services.
    *   **Input Files:** [`api/v1/openapi.yaml`, `docs/diagrams/datamodel_erd.puml`, `src/main/java/com/village/catalog/**`]
    *   **Target Files:** [`src/main/java/com/village/checkout/cart/**`, `tests/backend/CartServiceTest.java`, `tests/backend/CartControllerIT.java`, `api/v1/openapi.yaml`]
    *   **Deliverables:** Cart domain classes, services for add/update/remove/discount, REST resources tied to spec, integration tests hitting PostgreSQL with RLS.
    *   **Acceptance Criteria:** Unit/integration tests pass, endpoints match spec (response codes, Problem Details), Panache filters enforce tenant_id, concurrency handled via optimistic locking.
    *   **Testing Guidance:** Use Quarkus integration tests hitting Postgres Testcontainers, simulate concurrent updates, include HTTP contract tests referencing spec.
    *   **Observability Hooks:** Add log context for cartId, tenantId, customerId; define metrics (cart size, abandonment counter) for future dashboards.
    *   **Dependencies:** `I2.T1`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t5 -->
*   **Task 2.5:**
    *   **Task ID:** `I2.T5`
    *   **Description:** Author checkout + shipping/address validation sequence diagram plus adapter design: show flow from storefront -> checkout orchestrator -> address validation -> carrier adapter -> payment placeholder; include error handling and idempotency notes.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Requirements shipping/checkout sections, OpenAPI endpoints, catalog/cart flows.
    *   **Input Files:** [`docs/architecture_overview.md`, `api/v1/openapi.yaml`, `docs/diagrams/component_overview.puml`]
    *   **Target Files:** [`docs/diagrams/sequence_checkout_payment.mmd`, `docs/adr/ADR-003-checkout-saga.md`]
    *   **Deliverables:** Mermaid sequence diagram + ADR capturing saga + adapter contracts.
    *   **Acceptance Criteria:** Diagram renders, highlights retry/compensation points, ADR describes address validation + shipping caching strategy, references Section 5 contract.
    *   **Testing Guidance:** Validate Mermaid via CI script, include textual walkthrough appended to ADR; ensure adhesives tie to requirements IDs.
    *   **Observability Hooks:** Outline trace span names + logs for each step, specify metrics to collect (address validation latency, rate cache hit rate).
    *   **Dependencies:** `I2.T4`.
    *   **Parallelizable:** Limited.

<!-- anchor: task-i2-t6 -->
*   **Task 2.6:**
    *   **Task ID:** `I2.T6`
    *   **Description:** Write multi-tenant integration tests verifying PostgreSQL RLS + Panache filters for catalog/cart endpoints; include dataset with two tenants, ensure unauthorized cross-tenant data blocked, document test harness usage.
    *   **Agent Type Hint:** `QualityAgent`
    *   **Inputs:** Catalog/cart services, migrations.
    *   **Input Files:** [`tests/backend/CatalogServiceTest.java`, `tests/backend/CartControllerIT.java`, `src/main/resources/db/migrations/V20260102__baseline_schema.sql`]
    *   **Target Files:** [`tests/backend/TenantIsolationIT.java`, `docs/quality/tenant_isolation.md`]
    *   **Deliverables:** Integration test suite + documentation describing how to run + extend tenant isolation tests.
    *   **Acceptance Criteria:** Tests fail if RLS disabled, document explains dataset + expected behavior, CI includes suite.
    *   **Testing Guidance:** Use Testcontainers with Postgres row-level policies, run tests under both JVM + native profiles.
    *   **Observability Hooks:** Capture metrics/logs for RLS policy invocation (e.g., custom Postgres logs) and note how to surface in Section 6 dashboards.
    *   **Dependencies:** `I2.T1`, `I2.T4`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t7 -->
*   **Task 2.7:**
    *   **Task ID:** `I2.T7`
    *   **Description:** Expose headless catalog/cart endpoints + search caching: implement OAuth client credential guard, rate-limited controllers, search query builder, and caching via Caffeine with invalidation hooks; update spec + docs for partner usage.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** OpenAPI spec, catalog/cart services, feature flag directives.
    *   **Input Files:** [`api/v1/openapi.yaml`, `src/main/java/com/village/catalog/**`, `src/main/java/com/village/checkout/cart/**`, `docs/architecture_overview.md`]
    *   **Target Files:** [`src/main/java/com/village/headless/**`, `tests/backend/HeadlessApiIT.java`, `docs/headless/usage.md`, `api/v1/openapi.yaml`]
    *   **Deliverables:** OAuth-scoped endpoints for `/api/v1/headless/catalog`, `/api/v1/headless/cart`, caching strategy, documentation for partner onboarding, updated spec tags/scopes.
    *   **Acceptance Criteria:** OAuth scopes enforced, rate limiting guard returns 429 properly, cache invalidates on product/cart updates, docs describe onboarding + quotas.
    *   **Testing Guidance:** Integration tests simulating two clients, verifying scope enforcement + rate limits; include contract tests referencing OpenAPI.
    *   **Observability Hooks:** Add counters for headless request volume, cache hit/miss metrics, and structured logs capturing client_id + tenant_id for analytics.
    *   **Dependencies:** `I2.T1`, `I2.T4`, `I1.T5`.
    *   **Parallelizable:** Yes.

*   **Iteration KPIs & Validation Strategy:**
    - Catalog service coverage ≥85%; import/export script validated on dataset ≥500 variants.
    - Storefront LCP (test env) ≤2s for seeded tenant; Qute rendering times logged.
    - Admin SPA build <20s dev + <90s CI; Storybook screenshot diffs attached.
    - Cart API response median ≤150ms (local benchmark) and rejects cross-tenant requests with 404.
    - RLS integration tests run automatically in CI matrix (JVM + native) with evidence recorded in workflow logs.
    - Checkout sequence diagram + ADR reviewed by payments + ops stakeholders.
*   **Iteration Risk Log & Mitigations:**
    - *UI performance regressions:* Tailwind build may bloat; mitigate via purge config + CSS budget report.
    - *Spec divergence:* Cart endpoints may drift; mitigate by regenerating OpenAPI clients each PR + running contract tests.
    - *Localization debt:* Message bundles might lag Spanish copy; mitigate via placeholder translation + TODO board.
    - *RLS misconfigurations:* Complex migrations risk mistakes; mitigate via TenantIsolationIT gating merges touching schema.
    - *Storybook drift:* Components might diverge; mitigate via per-PR Storybook previews + Percy diffs.
*   **Iteration Backlog & Follow-ups:**
    - Schedule I3 task for consignment-specific catalog extensions (commission display fields).
    - Draft UI copy guidelines for bilingual storefront to share with content team.
    - Plan for shipping carrier credential UX in I4 once adapter spec approved.
    - Document admin command palette search providers for expansion (orders, customers, products).
