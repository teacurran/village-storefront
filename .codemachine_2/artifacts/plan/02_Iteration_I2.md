<!-- anchor: iteration-2-plan -->
### Iteration 2: Catalog, Storefront Shell, and Spec-First APIs

*   **Iteration ID:** `I2`
*   **Goal:** Deliver tenant-aware catalog + inventory services, publish OpenAPI skeleton, bootstrap Qute storefront and Vue admin shells, and document async/media job behaviors to unblock checkout and advanced modules.
*   **Prerequisites:** `I1`
*   **Iteration KPIs:** Catalog APIs ≤200 ms p95 in dev, storefront home renders under 1.5 s server-side, OpenAPI lint passes with zero warnings, and Playwright smoke retains 100% pass rate.
*   **Iteration Risks & Mitigations:**
    - Variant explosion could degrade performance → enforce pagination defaults, add covering indexes from ERD, and monitor via integration tests.
    - Theme token drift between Qute + Vue layers → add automated snapshot tests referencing shared token endpoint and document sync script.
    - Async queue misconfiguration could block media ingestion → finalize blueprint + dev HPA plan before enabling endpoints.
*   **Tasks:**

<!-- anchor: task-i2-t1 -->
*   **Task 2.1:**
    *   **Task ID:** `I2.T1`
    *   **Description:** Implement catalog domain (Product, Variant, Category, Collection) with Panache repositories enforcing tenant filters, DTO mappers, and REST endpoints for CRUD plus status transitions (draft/active/scheduled/archived) consistent with spec.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** `I1.T4` ERD, tenant isolation blueprint, Section 3 contract patterns, competitor research callouts.
    *   **Input Files:** ["src/main/java/com/village/catalog", "docs/diagrams/domain_erd.puml", ".codemachine/inputs/competitor-research.md"]
    *   **Target Files:** ["src/main/java/com/village/catalog/ProductResource.java", "src/main/java/com/village/catalog/VariantResource.java", "src/main/java/com/village/catalog/service/ProductService.java", "src/main/java/com/village/catalog/model/ProductEntity.java", "src/test/java/com/village/catalog/ProductResourceTest.java"]
    *   **Deliverables:** REST endpoints with DTO mapping, validation annotations, Panache queries filtering by tenant + visibility windows, unit + integration tests hitting Postgres dev container.
    *   **Acceptance Criteria:**
        - CRUD endpoints return RFC7807 errors, enforce tenant_id, include pagination/filtering, and emit domain event placeholders.
        - MapStruct or manual mapper ensures domain vs persistence separation; serialization excludes internal columns.
        - Tests cover happy path + RLS violation attempts, achieving ≥85% package coverage with Quarkus test containers.
    *   **Dependencies:** [`I1.T2`, `I1.T5`]
    *   **Parallelizable:** No (core domain).

<!-- anchor: task-i2-t2 -->
*   **Task 2.2:**
    *   **Task ID:** `I2.T2`
    *   **Description:** Build inventory subsystem with InventoryLocation, InventoryLevel, adjustments, transfers, and low-stock alert scheduler stub referencing Clarifications on multi-location + consignment coordination; integrate with catalog via variant IDs only.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** ERD, Safety Net, Clarification 4, existing catalog services.
    *   **Input Files:** ["src/main/java/com/village/inventory", "docs/diagrams/domain_erd.puml", "docs/architecture/tenant_isolation.md"]
    *   **Target Files:** ["src/main/java/com/village/inventory/InventoryResource.java", "src/main/java/com/village/inventory/TransferService.java", "src/main/java/com/village/inventory/entity/InventoryLevelEntity.java", "src/test/java/com/village/inventory/InventoryLevelTest.java", "src/test/java/com/village/inventory/TransferServiceIT.java"]
    *   **Deliverables:** REST endpoints + services, scheduled job stubs, integration with audit events, unit/integration tests, config toggles for email alerts.
    *   **Acceptance Criteria:**
        - Stock adjustments transactional with optimistic locking + reason codes, event log written per adjustment.
        - Transfer endpoints log audit events, queue notifications, and emit domain event placeholders for reporting.
        - Scheduler stub logs and respects feature flag gating + SLA metrics instrumentation.
    *   **Dependencies:** [`I2.T1`]
    *   **Parallelizable:** No.

<!-- anchor: task-i2-t3 -->
*   **Task 2.3:**
    *   **Task ID:** `I2.T3`
    *   **Description:** Scaffold Qute storefront shell (layout, partials, theme token loader) with sample product grid pulling from catalog endpoints, multi-tenant theme injection (CSS variables derived from DB JSON), and Accept-Language stub logic storing visitor preference cookie.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Architecture Section 2, Directory structure, Task `I2.T1` outputs, design tokens spec.
    *   **Input Files:** ["src/main/resources/qute", "src/main/java/com/village/catalog/ProductResource.java", "docs/architecture/tenant_isolation.md"]
    *   **Target Files:** ["src/main/resources/qute/layouts/base.html", "src/main/resources/qute/pages/home.html", "src/main/resources/qute/components/product-card.html", "src/main/java/com/village/storefront/ThemeProvider.java", "src/test/java/com/village/storefront/ThemeProviderTest.java"]
    *   **Deliverables:** Reusable layout, hero/grid partials, theme provider bean, integration test ensuring tenant-specific branding output.
    *   **Acceptance Criteria:**
        - Storefront home renders sample catalog list per tenant with caching fallback + 404 for unknown tenant.
        - Theme provider fetches tokens from DB or fallback JSON with caching + invalidation hook triggered via CDI event.
        - I18n stub reads Accept-Language and sets cookie + request attribute, ready for translation bundles.
    *   **Dependencies:** [`I2.T1`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t4 -->
*   **Task 2.4:**
    *   **Task ID:** `I2.T4`
    *   **Description:** Create OpenAPI v3 skeleton covering catalog, inventory, theme, tenant, and auth endpoints with reusable components, security schemes (JWT + OAuth client credentials), feature-flag metadata, and error schema templates.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Section 2 contract style, Task outputs `I2.T1`–`I2.T3`, `I1.T6` test strategy.
    *   **Input Files:** ["api/openapi.yaml", "README.md"]
    *   **Target Files:** ["api/openapi.yaml", "README.md"]
    *   **Deliverables:** Spec file with tags, schemas, error objects, `x-feature-flags` fields, README instructions for generating SDK stubs + contract tests.
    *   **Acceptance Criteria:**
        - Spec validates via `spectral lint` and includes schema references for all implemented endpoints.
        - SecuritySchemes define bearer + client creds with scopes; operations cite required scopes + rate limit hints.
        - README updated with spec change workflow + instructions to regenerate TypeScript/Java clients.
    *   **Dependencies:** [`I2.T1`, `I2.T2`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t5 -->
*   **Task 2.5:**
    *   **Task ID:** `I2.T5`
    *   **Description:** Bootstrap Vue 3 admin SPA (Quinoa) with routing, authentication guard, Tailwind token ingestion, shared PrimeVue theme, and placeholder views for dashboard/products/inventory referencing OpenAPI client stubs.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Section 1 overview, Directory structure, Task `I2.T4` spec, Theme provider APIs.
    *   **Input Files:** ["src/main/webui/src", "api/openapi.yaml", "src/main/resources/qute/layouts/base.html"]
    *   **Target Files:** ["src/main/webui/src/main.ts", "src/main/webui/src/router/index.ts", "src/main/webui/src/stores/catalog.ts", "src/main/webui/src/views/DashboardView.vue", "src/main/webui/tailwind.config.cjs", "src/main/webui/src/components/TokenPreview.vue"]
    *   **Deliverables:** Bootstrapped SPA with auth guard stub, Pinia stores hitting mock endpoints, Tailwind tokens loader bridging backend theme API, CI build script update referencing Quinoa.
    *   **Acceptance Criteria:**
        - SPA builds via Quinoa, loads inside Quarkus `/admin` route, uses dynamic config from backend endpoint hooking TenantContext.
        - Stores fetch data from catalog endpoints with mocked auth token + loading skeleton states.
        - Style guide page displays color/typography tokens using CSS variables, verifying contrast levels with automated check.
    *   **Dependencies:** [`I1.T5`, `I2.T3`, `I2.T4`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t6 -->
*   **Task 2.6:**
    *   **Task ID:** `I2.T6`
    *   **Description:** Document async processing blueprint (media, reports, payouts) with Mermaid diagrams showing DelayedJob lifecycle, queue priorities, worker pods, FFmpeg isolation, SLA table, and monitoring hooks.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Clarifications 2–4, Section 5 contract patterns, Task `I1.T7` (tooling), background job scheduler requirements.
    *   **Input Files:** ["docs/architecture/background_jobs.md", "docs/architecture/tenant_isolation.md"]
    *   **Target Files:** ["docs/architecture/background_jobs.md", "docs/diagrams/media_pipeline.mmd", "docs/architecture/platform_ops.md"]
    *   **Deliverables:** Mermaid diagrams, detailed SLA table, backlog of metrics to collect, operations notes for queue tuning.
    *   **Acceptance Criteria:**
        - Document covers queue table schema, job payload versioning, worker HPA triggers, circuit breaker strategy.
        - Media pipeline diagram references FFmpeg containers, signed URL flows, failure retries, backoff policy.
        - Ops doc lists dashboards/alerts needed plus manual remediation steps.
    *   **Dependencies:** [`I1.T3`, `I1.T7`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t7 -->
*   **Task 2.7:**
    *   **Task ID:** `I2.T7`
    *   **Description:** Implement media ingestion endpoints for images (upload request, completion hook, sync thumbnail creation) leveraging MinIO in dev, queue integration for heavy jobs, and tests for validation/signed URL generation + metadata storage.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Async blueprint, Directory structure, R2 requirements, Task `I2.T6` metrics.
    *   **Input Files:** ["src/main/java/com/village/media", "docs/architecture/background_jobs.md", "docker/docker-compose.yaml"]
    *   **Target Files:** ["src/main/java/com/village/media/MediaResource.java", "src/main/java/com/village/media/MediaService.java", "src/main/java/com/village/media/MediaJobPayload.java", "src/test/java/com/village/media/MediaResourceTest.java", "src/test/java/com/village/media/MediaServiceIT.java", "README.md"]
    *   **Deliverables:** REST endpoints, service layer hitting S3 SDK, queue enqueue logic, tests mocking MinIO + verifying signed URLs, README snippet describing local media flow.
    *   **Acceptance Criteria:**
        - Upload request enforces tenant-specific path + size/mime validation; completion persists metadata + job entry.
        - Signed URLs include hashed filenames + TTL, using Cloudflare-compatible settings; errors log audit events.
        - Tests confirm security rules (tenant_id path, size enforcement) and queue integration placing CRITICAL or DEFAULT priority appropriately.
    *   **Dependencies:** [`I2.T6`]
    *   **Parallelizable:** No (touches queue + storage config).

<!-- anchor: task-i2-t8 -->
*   **Task 2.8:**
    *   **Task ID:** `I2.T8`
    *   **Description:** Establish contract tests + QA data fixtures for catalog/inventory/storefront flows using Playwright (storefront) and REST-assured (API), seeded via docker scripts for deterministic tenants.
    *   **Agent Type Hint:** `QAAgent`
    *   **Inputs:** Tasks `I2.T1`–`I2.T3`, `I2.T4`, test strategy doc, seed scripts.
    *   **Input Files:** ["scripts/qa/run_e2e.sh", "src/test/java/com/village/catalog", "src/main/webui", "scripts/dev/tenant_seed.sh"]
    *   **Target Files:** ["tests/e2e/storefront/catalog.spec.ts", "tests/e2e/api/catalog_contract.spec.ts", "scripts/dev/tenant_seed.sh", "scripts/qa/run_e2e.sh", "docs/quality/test_strategy.md"]
    *   **Deliverables:** Playwright spec hitting home/category + visual snapshot, REST-assured contract tests verifying OpenAPI responses, updated seed data with sample catalog + inventory to support tests, CI job wiring.
    *   **Acceptance Criteria:**
        - Playwright run passes headless, produces artifacts stored via CI; spec asserts theme tokens + cart add stub.
        - REST-assured tests validate JSON schema from OpenAPI, ensuring 100% coverage for implemented endpoints.
        - Seed script populates sample catalog/inventory referenced by tests; e2e script wiring documented in README/test strategy.
    *   **Dependencies:** [`I2.T1`, `I2.T2`, `I2.T3`, `I2.T4`]
    *   **Parallelizable:** Yes.

*   **Exit Criteria:**
    - Catalog + inventory endpoints deployed behind feature flags, storefront + admin shells consuming them with documented smoke tests.
    - OpenAPI spec + diagrams stored under version control with lint jobs referenced in CI, and async/media blueprint approved by platform ops.
    - QA automation (Playwright + REST-assured) running in CI with seeded tenant data, producing artifacts for observability dashboards.
