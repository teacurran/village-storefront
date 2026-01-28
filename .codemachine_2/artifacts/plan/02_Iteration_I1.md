<!-- anchor: iteration-plan-overview -->
## 5. Iteration Plan

*   **Total Iterations Planned:** 5
*   **Iteration Dependencies:** `I1` lays down tenant-aware foundation and diagrams; `I2` builds core catalog/storefront flows atop that base; `I3` depends on `I2` for catalog/cart APIs before enabling checkout/payments; `I4` extends commerce with consignment/loyalty/POS using `I3` checkout services; `I5` finalizes platform admin, reporting, and deployment hardening relying on previous modules.

<!-- anchor: iteration-1-plan -->
### Iteration 1: Platform Foundation & Architecture Baselines

*   **Iteration ID:** `I1`
*   **Goal:** Establish multi-tenant Quarkus skeleton, env tooling, and authoritative architecture artifacts (component diagram, ERD, tenant isolation blueprint, test strategy) so downstream teams can safely parallelize feature work.
*   **Prerequisites:** None
*   **Tasks:**

<!-- anchor: task-i1-t1 -->
*   **Task 1.1:**
    *   **Task ID:** `I1.T1`
    *   **Description:** Audit existing Maven modules against `docs/java-project-standards.adoc`, add missing Quarkus extensions (RESTEasy Reactive, Panache, Scheduler, AWS S3, Stripe SDK), and configure Maven profiles for GraalVM native + JVM builds with Spotless and JaCoCo baselines.
    *   **Agent Type Hint:** `SetupAgent`
    *   **Inputs:** Architecture Section 2, Standard kit references, existing `pom.xml`.
    *   **Input Files:** ["docs/java-project-standards.adoc", "pom.xml"]
    *   **Target Files:** ["pom.xml", "docs/quality/test_strategy.md"]
    *   **Deliverables:** Updated Maven config with enforced plugins, profile docs inside test strategy, initial module map in README snippet.
    *   **Acceptance Criteria:**
        - Maven build runs `mvn clean verify` with Spotless + JaCoCo hooks locally.
        - Profiles `%dev/%test/%prod` configured with Quarkus extension list documented.
        - README/test strategy captures module inventory + coverage expectations.
        - No regression to existing modules; CI dry run passing logged in plan notes.
    *   **Dependencies:** []
    *   **Parallelizable:** No (baseline build chain must stabilize first).

<!-- anchor: task-i1-t2 -->
*   **Task 1.2:**
    *   **Task ID:** `I1.T2`
    *   **Description:** Document Tenant Access Gateway + RLS enforcement plan detailing subdomain/custom-domain resolution, TenantContext lifecycle, Panache filters, and PostgreSQL policy templates with rollback guidance.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Section 2 Core Architecture, Section 6 Safety Net, Clarification 1 + 6.
    *   **Input Files:** ["docs/java-project-standards.adoc", ".codemachine/inputs/competitor-research.md"]
    *   **Target Files:** ["docs/architecture/tenant_isolation.md", "src/main/java/com/village/tenant/TenantContext.java", "src/main/java/com/village/tenant/TenantFilter.java"]
    *   **Deliverables:** Markdown blueprint, scaffolded TenantContext bean + JAX-RS filter skeleton with TODOs referencing spec, sample RLS SQL snippet.
    *   **Acceptance Criteria:**
        - Markdown covers subdomain + custom domain resolution, RLS policy template, test hooks.
        - TenantContext + TenantFilter compile and include logging + TODOs for later services.
        - Document cross-links to ERD + component diagram placeholders.
    *   **Dependencies:** [`I1.T1`]
    *   **Parallelizable:** Yes (after Maven baseline complete).

<!-- anchor: task-i1-t3 -->
*   **Task 1.3:**
    *   **Task ID:** `I1.T3`
    *   **Description:** Produce PlantUML C4 component diagram capturing modules, worker pods, and integrations (Stripe, carriers, R2, GitHub Actions, Kubernetes) referencing Section 2 architecture narrative.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** Section 2 components, system architecture blueprint, Clarifications 2–5.
    *   **Input Files:** ["docs/architecture/tenant_isolation.md"]
    *   **Target Files:** ["docs/diagrams/component_overview.puml"]
    *   **Deliverables:** Annotated PlantUML diagram plus rendered PNG (optional) referenced in README.
    *   **Acceptance Criteria:**
        - Diagram passes PlantUML syntax check and highlights module responsibilities + external edges.
        - Includes notes on queues, feature flags, observability toolchain.
        - README gains link to artifact (if README touched, cite change in task notes).
    *   **Dependencies:** [`I1.T2`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t4 -->
*   **Task 1.4:**
    *   **Task ID:** `I1.T4`
    *   **Description:** Author ERD (PlantUML) covering tenant-scoped entities plus support tables (FeatureFlag, BackgroundJob, DomainEvent, GiftCard, SubscriptionPlan, PosDevice, MediaAsset) with commentary on tenant_id usage and indexes.
    *   **Agent Type Hint:** `DatabaseAgent`
    *   **Inputs:** Section 2 data overview, Clarifications 3–5, Safety Net assumptions.
    *   **Input Files:** ["docs/architecture/tenant_isolation.md"]
    *   **Target Files:** ["docs/diagrams/domain_erd.puml"]
    *   **Deliverables:** PlantUML ERD + textual summary snippet appended to `docs/architecture/tenant_isolation.md`.
    *   **Acceptance Criteria:**
        - ERD passes PlantUML validation, includes tenant_id on every table, and marks partitioned tables.
        - Comments list key indexes + relationships for consignment, loyalty, jobs.
        - Data-types align with PostgreSQL 17 recommendations.
    *   **Dependencies:** [`I1.T2`]
    *   **Parallelizable:** Yes (with T1.3).

<!-- anchor: task-i1-t5 -->
*   **Task 1.5:**
    *   **Task ID:** `I1.T5`
    *   **Description:** Implement base Quarkus modules for Tenant Access Gateway, Identity skeleton, and shared `platform-kit` utilities (money, feature flags, tracing helpers) with placeholder endpoints + tests to verify dependency wiring and TenantContext injection.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Tasks `I1.T1`–`I1.T4`, architecture rules.
    *   **Input Files:** ["src/main/java/com/village/tenant/TenantContext.java", "docs/architecture/tenant_isolation.md"]
    *   **Target Files:** ["src/main/java/com/village/tenant/TenantFilter.java", "src/main/java/com/village/tenant/TenantResolverService.java", "src/main/java/com/village/platformkit/FeatureToggleService.java", "src/main/java/com/village/identity/IdentityResource.java", "src/test/java/com/village/tenant/TenantFilterTest.java"]
    *   **Deliverables:** Compiling Quarkus resources with placeholder endpoints, CDI beans, unit tests verifying tenant header parsing + feature flag caching.
    *   **Acceptance Criteria:**
        - `mvn test` green with new tests covering TenantFilter happy/sad paths.
        - FeatureToggle service caches per tenant with TTL config stub.
        - Identity endpoint returns HTTP 501 placeholder yet enforces TenantContext presence.
    *   **Dependencies:** [`I1.T1`, `I1.T2`]
    *   **Parallelizable:** No (touches shared base classes).

<!-- anchor: task-i1-t6 -->
*   **Task 1.6:**
    *   **Task ID:** `I1.T6`
    *   **Description:** Draft comprehensive test strategy mapping unit/integration/e2e expectations per module, coverage thresholds, test data mgmt, and CI gating incl. JaCoCo 80% + mutation testing stretch goals.
    *   **Agent Type Hint:** `DocumentationAgent`
    *   **Inputs:** Section 6 Verification strategy preview, Section 2 architecture.
    *   **Input Files:** ["docs/quality/test_strategy.md", "docs/java-project-standards.adoc"]
    *   **Target Files:** ["docs/quality/test_strategy.md", "scripts/qa/run_e2e.sh"]
    *   **Deliverables:** Updated strategy doc plus stub e2e runner script referencing Playwright/Cypress suites.
    *   **Acceptance Criteria:**
        - Document outlines per-module suites, data seeding, coverage criteria, and reporting pipeline.
        - Script stub runs placeholder command and includes TODO for actual tests.
        - Links to iteration tasks that will satisfy coverage increments.
    *   **Dependencies:** [`I1.T1`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t7 -->
*   **Task 1.7:**
    *   **Task ID:** `I1.T7`
    *   **Description:** Build developer docker-compose stack (PostgreSQL 17, MinIO mimicking R2, Mailhog, Stripe CLI) with Quarkus devservices wiring + onboarding instructions.
    *   **Agent Type Hint:** `SetupAgent`
    *   **Inputs:** Infrastructure requirements from Overview + Clarifications (media processing/Stripe).
    *   **Input Files:** ["docker/docker-compose.yaml", "README.md"]
    *   **Target Files:** ["docker/docker-compose.yaml", "README.md", "scripts/dev/tenant_seed.sh"]
    *   **Deliverables:** Compose file, seed script generating sample tenant + data, README section guiding setup.
    *   **Acceptance Criteria:**
        - `docker-compose up` starts services with documented env vars and volume mounts.
        - Seed script inserts sample tenant + store + admin user; README explains cleanup.
        - Compose integrates MinIO bucket with credentials matching Quarkus dev config.
    *   **Dependencies:** [`I1.T1`]
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t8 -->
*   **Task 1.8:**
    *   **Task ID:** `I1.T8`
    *   **Description:** Configure GitHub Actions baseline workflow executing build, tests, native image dry run, and artifact upload (OpenAPI placeholder + diagrams) with cache warming for GraalVM.
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Task outputs from `I1.T1`, `I1.T6`, docker tooling from `I1.T7`.
    *   **Input Files:** ["infra/github-actions/workflows/ci.yaml"]
    *   **Target Files:** ["infra/github-actions/workflows/ci.yaml", "README.md"]
    *   **Deliverables:** Workflow YAML, status badge snippet, caching strategy docs.
    *   **Acceptance Criteria:**
        - Workflow has lint, unit, integration (profile), and native build jobs with concurrency groups.
        - Artifacts include coverage report + placeholder openapi stub to enforce spec-first.
        - README shows CI badge referencing GitHub Actions.
    *   **Dependencies:** [`I1.T1`, `I1.T6`, `I1.T7`]
    *   **Parallelizable:** No (ties multiple prerequisites).
