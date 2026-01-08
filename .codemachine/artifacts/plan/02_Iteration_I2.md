<!-- anchor: iteration-2-plan -->
### Iteration 2: Catalog, Inventory, and Core Domain APIs

*   **Iteration ID:** `I2`
*   **Goal:** Implement catalog + inventory domain models, repositories, migrations, and API slices so downstream checkout/consignment flows interact with tenant-safe data services.
*   **Prerequisites:** `I1`
*   **Tasks:**

<!-- anchor: task-i2-t1 -->
*   **Task 2.1:**
    *   **Task ID:** `I2.T1`
    *   **Description:** Define Panache entities + repositories for Product, Variant, Category, Collection with base filters applying tenant_id + status; include DTO mappers + validation.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** ERD, feature flag service, TenantContext utilities.
    *   **Input Files:** [`docs/diagrams/erd.mmd`, `modules/catalog/src/main/java/...`]
    *   **Target Files:** [`modules/catalog/src/main/java/.../ProductEntity.java`, `VariantEntity.java`, `CategoryEntity.java`, `CollectionEntity.java`, `.../ProductRepository.java`, `.../ProductService.java`, `modules/catalog/src/test/java/...`]
    *   **Deliverables:** Entities + services with CRUD + pagination, DTO mappers (MapStruct), validation rules (status transitions, slug uniqueness), test coverage for tenant filters.
    *   **Acceptance Criteria:** Unit/integration tests confirm RLS/tenant filtering; MapStruct mappers compile native; repository methods expose query builders for search facets; documentation referencing new services added to module README.
    *   **Dependencies:** `I1.T5`.
    *   **Parallelizable:** No.

<!-- anchor: task-i2-t2 -->
*   **Task 2.2:**
    *   **Task ID:** `I2.T2`
    *   **Description:** Create MyBatis migrations for catalog/inventory tables (products, variants, categories, collections, inventory_locations/levels) with indexes, RLS policies, partition seeds for inventory logs.
    *   **Agent Type Hint:** `DatabaseAgent`
    *   **Inputs:** ERD, Task I2.T1 schema expectations.
    *   **Input Files:** [`migrations/mybatis`, `docs/diagrams/erd.mmd`]
    *   **Target Files:** [`migrations/mybatis/20240713_catalog.sql`, `migrations/mybatis/20240713_inventory.sql`]
    *   **Deliverables:** Forward/backward migrations, verification script ensuring RLS policies exist, README snippet for migration order.
    *   **Acceptance Criteria:** `mvn -pl modules/catalog quarkus:dev` with dev Postgres applies migrations clean; `psql` check shows RLS policy referencing `current_setting('app.tenant_id')`; rollback script passes dry run.
    *   **Dependencies:** `I2.T1` (attributes) + `I1.T5` (tenant config).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t3 -->
*   **Task 2.3:**
    *   **Task ID:** `I2.T3`
    *   **Description:** Extend OpenAPI spec + Quarkus resources for catalog (list/search products, variant matrix, categories/collections CRUD) and inventory endpoints; include pagination/filter parameter definitions.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Task I2.T1 output, API skeleton.
    *   **Input Files:** [`api/storefront-admin-platform.yaml`, `modules/catalog/src/main/java/.../ProductResource.java`]
    *   **Target Files:** [`api/storefront-admin-platform.yaml`, `modules/catalog/src/main/java/.../ProductResource.java`, `.../InventoryResource.java`, `modules/catalog/src/test/java/.../ProductResourceTest.java`]
    *   **Deliverables:** REST controllers (storefront + admin scopes) respecting TenantContext + feature flags; spec includes request/response schemas, ProblemDetails, RateLimit metadata.
    *   **Acceptance Criteria:** OpenAPI passes lint; Quarkus endpoints return 200/404/403 as expected; integration test uses RestAssured verifying tenant isolation; spec referencing new tags `Catalog` + `Inventory`.
    *   **Dependencies:** `I2.T1`, `I1.T4`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t4 -->
*   **Task 2.4:**
    *   **Task ID:** `I2.T4`
    *   **Description:** Implement Inventory service (multi-location levels, adjustments, transfers) with Panache repositories, domain services enforcing reason codes, integration events for reporting.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Requirements (inventory), migrations.
    *   **Input Files:** [`modules/catalog/src/main/java/.../Inventory*`, `docs/diagrams/erd.mmd`]
    *   **Target Files:** [`modules/catalog/src/main/java/.../InventoryLocationEntity.java`, `InventoryLevelEntity.java`, `InventoryAdjustmentService.java`, `InventoryTransferService.java`, `modules/catalog/src/test/java/...`]
    *   **Deliverables:** Services supporting create/update/transfer/adjust operations, domain events (InventoryAdjusted, TransferInitiated) persisted to `domain_events`, tests verifying concurrency + RLS.
    *   **Acceptance Criteria:** Transfers handle validations (matching tenant, quantities, statuses); events recorded with JSON payload; tests run against dev Postgres; API docs updated referencing adjustments.
    *   **Dependencies:** `I2.T2`.
    *   **Parallelizable:** No.

<!-- anchor: task-i2-t5 -->
*   **Task 2.5:**
    *   **Task ID:** `I2.T5`
    *   **Description:** Build CSV import/export foundation for catalog (async job, schema validation, sample template) leveraging DelayedJob queue.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Async job policies, catalog service.
    *   **Input Files:** [`modules/catalog/src/main/java/.../ImportJobHandler.java`, `docs/architecture/async/job-catalog.md`]
    *   **Target Files:** [`docs/architecture/async/job-catalog.md`, `modules/catalog/src/main/java/.../CatalogImportJobHandler.java`, `modules/catalog/src/main/java/.../CatalogExportJobHandler.java`, `modules/catalog/src/test/java/.../CatalogImportJobHandlerTest.java`, `docs/templates/catalog-import-sample.csv`]
    *   **Deliverables:** Job handlers, job catalog doc entry (payload schema, queues, retries), CLI or REST endpoint for import/export, sample CSV.
    *   **Acceptance Criteria:** Job enqueues with DEFAULT priority, processes rows idempotently, logs errors per row; export job streams CSV to R2/MinIO; documentation describes operator workflow.
    *   **Dependencies:** `I2.T1`, `I1.T7` (local stack), `I1.T6` (flags for beta import).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t6 -->
*   **Task 2.6:**
    *   **Task ID:** `I2.T6`
    *   **Description:** Create storefront Qute partials + Tailwind tokens for catalog browsing (hero, product card, filter panel) plus design token export script hooking into database theme seed.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Design system spec, product endpoints.
    *   **Input Files:** [`src/main/resources/templates/storefront/partials`, `src/main/resources/application.properties`, `src/main/webui/src/locales/en.json`]
    *   **Target Files:** [`src/main/resources/templates/storefront/catalog.html`, `src/main/resources/templates/storefront/_product-card.html`, `src/main/resources/templates/storefront/_filters.html`, `src/main/resources/templates/storefront/_theme.css`, `scripts/dev/export-theme-tokens.ts`]
    *   **Deliverables:** Initial storefront screens hitting catalog API, responsive product cards, filter panel, theme token exporter referencing tenant branding.
    *   **Acceptance Criteria:** Dev mode renders sample catalog with seeded data; Tailwind build uses exported tokens; Lighthouse passes basic performance (LCP <2.5s) on seeded data; tests verifying translations exist for EN/ES placeholders.
    *   **Dependencies:** `I2.T1`, `I2.T3`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i2-t7 -->
*   **Task 2.7:**
    *   **Task ID:** `I2.T7`
    *   **Description:** Instrument observability for catalog/inventory APIs (OpenTelemetry spans, Prometheus metrics, structured logs) plus add component-level runbook entries.
    *   **Agent Type Hint:** `ObservabilityAgent`
    *   **Inputs:** Observability requirements, tasks T2.1-T2.4 outputs.
    *   **Input Files:** [`modules/catalog/src/main/java/...`, `docs/architecture/ops/deployment-architecture.md`]
    *   **Target Files:** [`modules/catalog/src/main/java/.../CatalogMetrics.java`, `modules/catalog/src/main/resources/application.properties`, `docs/architecture/ops/catalog-runbook.md`]
    *   **Deliverables:** Metrics (request latency histograms, import job gauges), tracing instrumentation, runbook describing alerts and feature flags.
    *   **Acceptance Criteria:** `/q/metrics` exposes catalog + inventory metrics; spans show tenant_id attribute; runbook lists KPIs + alert thresholds; tests ensure metrics registered.
    *   **Dependencies:** `I2.T1`-`I2.T4`.
    *   **Parallelizable:** Yes.
