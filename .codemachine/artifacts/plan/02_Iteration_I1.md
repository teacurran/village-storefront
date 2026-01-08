<!-- anchor: iteration-plan -->
## 5. Iteration Plan

*   **Total Iterations Planned:** 6
*   **Iteration Dependencies:** I1 establishes architecture, tenancy, and specs consumed by all later work; I2 builds domain models atop I1 infrastructure; I3 layers checkout/payments/consignment flows atop catalog; I4 introduces storefront/admin/POS experiences plus media; I5 adds advanced programs (loyalty, reporting, headless, platform admin); I6 hardens deployment, observability, and testing to production quality.

<!-- anchor: iteration-1-plan -->
### Iteration 1: Multi-Tenant Foundation & Specifications

*   **Iteration ID:** `I1`
*   **Goal:** Stand up repository scaffolding, tenancy enforcement, base migrations, and initial architectural artifacts/specifications so downstream teams can implement business features safely.
*   **Prerequisites:** None.
*   **Tasks:**

<!-- anchor: task-i1-t1 -->
*   **Task 1.1:**
    *   **Task ID:** `I1.T1`
    *   **Description:** Initialize Maven multi-module workspace (`pom.xml`, module folders) plus baseline GitHub Actions pipeline and Spotless/JaCoCo configuration aligned to `docs/java-project-standards.adoc`.
    *   **Agent Type Hint:** `SetupAgent`
    *   **Inputs:** Foundation blueprint, java standards doc, repo README expectations.
    *   **Input Files:** [`docs/java-project-standards.adoc`, `README.md`]
    *   **Target Files:** [`pom.xml`, `modules/core-platform/pom.xml`, `.github/workflows/ci.yml`, `README.md`]
    *   **Deliverables:** Maven parent + modules, CI workflow covering Spotless + JaCoCo + native build smoke, updated README explaining build targets.
    *   **Acceptance Criteria:** Build passes `mvn -pl modules/core-platform clean verify`; CI workflow triggers on PR and enforces Spotless & coverage ≥80%; README includes quickstart for Maven + Quarkus dev mode.
    *   **Dependencies:** None.
    *   **Parallelizable:** No (foundational).

<!-- anchor: task-i1-t2 -->
*   **Task 1.2:**
    *   **Task ID:** `I1.T2`
    *   **Description:** Author PlantUML System Context + Container + Component diagrams describing actors, Quarkus modules, worker pods, and adapters; document diagram intent.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Requirements brief, Section 2 Core Architecture.
    *   **Input Files:** [`docs/java-project-standards.adoc`, `.codemachine/artifacts/plan/01_Plan_Overview_and_Setup.md`]
    *   **Target Files:** [`docs/diagrams/system-context.puml`, `docs/diagrams/container.puml`, `docs/diagrams/component.puml`, `docs/architecture/ops/deployment-architecture.md`]
    *   **Deliverables:** Three diagrams plus Markdown commentary linking modules to deployment/integration concerns.
    *   **Acceptance Criteria:** PlantUML renders without errors; diagrams include all actors (merchants, staff, consignors, platform admins, headless partners) and integrations (Stripe, R2, carriers, DNS, FFmpeg); Markdown summarises responsibilities + links to diagram anchors.
    *   **Dependencies:** `I1.T1` (repo structure for docs).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t3 -->
*   **Task 1.3:**
    *   **Task ID:** `I1.T3`
    *   **Description:** Produce Mermaid ERD capturing multi-tenant schema (Tenant, StoreUser, Customer, SessionLog, FeatureFlag, Product, Variant, Inventory, Consignor, ConsignmentItem, Cart, Order, PaymentIntent, Shipment, Return, LoyaltyLedger, GiftCard, StoreCredit, BackgroundJob, WebhookEvent, AuditEvent, DomainEvent, PlatformCommand, MediaAsset, RateLimitBucket) with RLS annotations.
    *   **Agent Type Hint:** `DatabaseAgent`
    *   **Inputs:** Requirements data model, Section 2.1 artifacts list.
    *   **Input Files:** [`docs/java-project-standards.adoc`, `.codemachine/artifacts/plan/01_Plan_Overview_and_Setup.md`]
    *   **Target Files:** [`docs/diagrams/erd.mmd`, `docs/architecture/data/reporting-retention.md`]
    *   **Deliverables:** ERD source, short narrative describing tenancy columns, indices, partition strategy.
    *   **Acceptance Criteria:** Mermaid renders; each table includes tenant_id + metadata; doc explains RLS policy template, partitioned tables, archival approach; reviewed with Identity + Reporting stakeholders.
    *   **Dependencies:** `I1.T1` (docs path).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t4 -->
*   **Task 1.4:**
    *   **Task ID:** `I1.T4`
    *   **Description:** Draft OpenAPI 3.0 skeleton covering versioning, security schemes (JWT + OAuth client credentials), shared components (Money, Address, Pagination, Problem Details), and stub paths for tenants, auth, catalog, checkout, headless, platform admin; integrate with Quarkus and set up lint.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Requirements list, architecture plan, API contract style.
    *   **Input Files:** [`api/storefront-admin-platform.yaml` (if exists), `.codemachine/artifacts/plan/01_Plan_Overview_and_Setup.md`]
    *   **Target Files:** [`api/storefront-admin-platform.yaml`, `modules/core-platform/src/main/resources/application.properties`]
    *   **Deliverables:** Validated OpenAPI (passes `spectral lint` or equivalent), Quarkus config pointing to spec for code generation, README snippet on spec workflow.
    *   **Acceptance Criteria:** `npm run lint:openapi` (or Maven plugin) succeeds; spec defines servers, tags, security schemes, base schemas; CI ensures spec diff check.
    *   **Dependencies:** `I1.T1`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t5 -->
*   **Task 1.5:**
    *   **Task ID:** `I1.T5`
    *   **Description:** Implement TenantContext, TenantResolver (subdomain/custom domain), Request filter, and baseline MyBatis migrations for Tenant + StoreUser tables including RLS policies; add Caffeine caching shim.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Requirements (multi-tenancy & SSL), ERD.
    *   **Input Files:** [`docs/diagrams/erd.mmd`, `modules/core-platform/src/main/java/...`]
    *   **Target Files:** [`modules/core-platform/src/main/java/.../TenantContext.java`, `.../TenantResolver.java`, `.../TenantFilter.java`, `migrations/mybatis/*.sql`, `modules/core-platform/src/test/java/...`]
    *   **Deliverables:** Request filter populating TenantContext, Caffeine caching for host lookups, migrations creating tenants/store_users with RLS policies, unit/integration tests hitting dev Postgres profile.
    *   **Acceptance Criteria:** Multi-tenant filter enforces 404 on unknown domain, populates feature flag placeholder; integration test using Quarkus dev service demonstrates RLS preventing cross-tenant selection; migration rollbacks tested; lint passes.
    *   **Dependencies:** `I1.T3` (schema reference).
    *   **Parallelizable:** No (core path).

<!-- anchor: task-i1-t6 -->
*   **Task 1.6:**
    *   **Task ID:** `I1.T6`
    *   **Description:** Create Feature Flag governance matrix + README guidance; implement FeatureToggle service skeleton referencing PostgreSQL storage + Caffeine; include emergency kill switches for checkout/media/impersonation.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Section 4 directives, requirements.
    *   **Input Files:** [`docs/architecture/governance/feature-flags.md`, `modules/core-platform/src/main/java/...`]
    *   **Target Files:** [`docs/architecture/governance/feature-flags.md`, `modules/core-platform/src/main/java/.../FeatureToggleService.java`, `modules/core-platform/src/test/java/.../FeatureToggleServiceTest.java`]
    *   **Deliverables:** Governance doc (owner, rollout, expiry fields), service + repository stub, tests verifying cache + override precedence.
    *   **Acceptance Criteria:** Service resolves platform default + tenant override + emergency flags; governance doc lists initial kill switches; README references process.
    *   **Dependencies:** `I1.T5` (tenant context).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t7 -->
*   **Task 1.7:**
    *   **Task ID:** `I1.T7`
    *   **Description:** Provision local development tooling: docker-compose for Postgres, MinIO (S3), Mailhog, FFmpeg dependency validation; scripts for migrations and DelayedJob tables; update README quickstart.
    *   **Agent Type Hint:** `DevExAgent`
    *   **Inputs:** Requirements (local dev), docker instructions from blueprint.
    *   **Input Files:** [`docker/compose.yml`, `scripts/dev/*.sh`]
    *   **Target Files:** [`docker/docker-compose.yml`, `scripts/dev/bootstrap.sh`, `README.md`]
    *   **Deliverables:** Compose stack wired to Quarkus dev profile, helper script running migrations + seeding sample tenants, docs for FFmpeg install.
    *   **Acceptance Criteria:** `docker compose up` starts dependencies, bootstrap script seeds tenant + staff; README outlines steps; Quarkus dev mode connects via `.env` variables.
    *   **Dependencies:** `I1.T1`, `I1.T5`.
    *   **Parallelizable:** Yes.
