<!-- anchor: directives-process -->
## 4. DIRECTIVES & STRICT PROCESS

- Honor the foundation blueprint: every deliverable must cite Sections 1–6 when making architectural choices, and no deviation ships without an ADR.
- Follow spec-first discipline—diagrams, ERDs, and OpenAPI definitions precede implementation so later agents inherit stable contracts.
- Enforce tenant safety, observability, feature flag governance, and security controls at creation time instead of retrofitting; background jobs, filters, and logs must embed tenant metadata.
- Store all artifacts in the directories defined in Section 3, using descriptive filenames + anchors to maximize autonomous agent discoverability.
- Acceptance criteria always include Spotless + JaCoCo compliance, schema validation where applicable, and documentation updates so CI gates remain green.

<!-- anchor: iteration-plan-overview -->
## 5. Iteration Plan

* **Total Iterations Planned:** 5 (Setup/Core Models, Multi-Tenancy Services, Consignment & Media Foundations, Loyalty & Platform Ops, POS & Headless Completion).
* **Iteration Dependencies:** `I1` lays scaffolding and core specs for `I2`; `I2` produces tenant-aware services enabling `I3` (consignment/media); `I3` outputs feed `I4` (loyalty/platform admin); `I4` unlocks `I5` (POS/offline/headless polishing). Testing + ADR work from earlier iterations becomes required context for later automation.

<!-- anchor: iteration-1-plan -->
### Iteration 1: Platform Foundation & Spec-First Assets

* **Iteration ID:** `I1`
* **Goal:** Establish Quarkus multi-module workspace, governance docs, base diagrams, ERD, OpenAPI seed, Tenant filter skeleton, and CI/CD hooks enforcing standards.
* **Prerequisites:** None
* **Tasks:**

<!-- anchor: task-i1-t1 -->
* **Task 1.1:**
    * **Task ID:** `I1.T1`
    * **Description:** Bootstrap the Maven multi-module Quarkus project (core platform + admin UI module placeholders), configure Spotless, JaCoCo (80%), and shared plugins, seed README with architecture commitments, and stub package structure per Section 3.
    * **Agent Type Hint:** `SetupAgent`
    * **Inputs:** Section 2 Standard Kit, Section 3 directory structure, docs/java-project-standards directives.
    * **Input Files**: ["docs/java-project-standards.adoc", ".codemachine/inputs/competitor-research.md"]
    * **Target Files:** ["pom.xml", "README.md", "src/main/java/com/village/storefront/**", "src/main/resources/application.properties", "ui-storefront/", "src/main/webui/"]
    * **Deliverables:** Multi-module Maven build, baseline Quarkus app with placeholders for tenant, identity, catalog modules, README summary of stack + commands.
    * **Acceptance Criteria:**
        - `mvn clean verify` succeeds with Spotless + JaCoCo wired and default modules present.
        - README documents build/run commands plus module overview referencing Sections 1–2.
        - Package skeleton mirrors directory tree, including placeholder classes (TenantContext, CatalogService) with TODO comments.
    * **Dependencies:** None
    * **Parallelizable:** No

<!-- anchor: task-i1-t2 -->
* **Task 1.2:**
    * **Task ID:** `I1.T2`
    * **Description:** Author the Component Diagram (PlantUML) covering Tenant Gateway, Identity, Storefront, Admin SPA, Catalog, Consignment, Checkout, Payments, Loyalty, POS, Media, Jobs, Reporting, Platform Admin, and external systems.
    * **Agent Type Hint:** `DiagrammingAgent`
    * **Inputs:** Section 2 Core Architecture, Section 2.1 artifact list.
    * **Input Files**: ["docs/java-project-standards.adoc", "README.md"]
    * **Target Files:** ["docs/diagrams/component-overview.puml"]
    * **Deliverables:** PlantUML diagram with labeled interfaces + notes on GraalVM/Kubernetes context.
    * **Acceptance Criteria:**
        - Diagram renders via `plantuml` CLI without errors.
        - Each component shows inbound/outbound dependencies + technology notes (Stripe, R2, PostgreSQL).
        - File header references `I1.T2` and Section 2 for traceability.
    * **Dependencies:** `I1.T1`
    * **Parallelizable:** Yes

<!-- anchor: task-i1-t3 -->
* **Task 1.3:**
    * **Task ID:** `I1.T3`
    * **Description:** Draft the multi-tenant ERD (Mermaid) showing Tenant, StoreUser, Customer, SessionLog, Product, Variant, InventoryLocation/Level, Consignor, ConsignmentItem, Cart, Order, PaymentIntent, AuditEvent, FeatureFlag, BackgroundJob.
    * **Agent Type Hint:** `DatabaseAgent`
    * **Inputs:** Section 2 Data Model overview, docs/java-project-standards RLS guidance.
    * **Input Files**: ["docs/java-project-standards.adoc", "docs/diagrams/component-overview.puml"]
    * **Target Files:** ["docs/diagrams/tenant-erd.mmd"]
    * **Deliverables:** Mermaid ERD capturing primary keys, foreign keys, tenant_id presence, and notes on RLS policies.
    * **Acceptance Criteria:**
        - Mermaid preview passes `mmdc` or mermaid-cli lint.
        - Every tenant-scoped table shows `tenant_id` plus indexes described in notes.
        - Document includes commentary on partitioned tables (SessionLog, AuditEvent) linking to Section 5.
    * **Dependencies:** `I1.T2`
    * **Parallelizable:** Yes

<!-- anchor: task-i1-t4 -->
* **Task 1.4:**
    * **Task ID:** `I1.T4`
    * **Description:** Produce initial OpenAPI spec (YAML) covering auth (login, refresh, impersonation), tenant resolution endpoints, catalog read endpoints, and shared DTO components.
    * **Agent Type Hint:** `DocumentationAgent`
    * **Inputs:** Sections 2 & 5 (API style), ERD, component diagram.
    * **Input Files**: ["docs/diagrams/component-overview.puml", "docs/diagrams/tenant-erd.mmd"]
    * **Target Files:** ["api/openapi-base.yaml"]
    * **Deliverables:** Valid OpenAPI 3.0 spec with tags for `auth`, `tenants`, `catalog-public`, security schemes for JWT + OAuth client credentials, and RFC7807 error schema.
    * **Acceptance Criteria:**
        - `openapi-generator validate` (or `swagger-cli validate`) passes with no warnings.
        - Paths include tenant-aware segments and document feature flag metadata in `x-feature-flags`.
        - Components define DTOs referenced by future modules (Tenant, StoreUser, ProductSummary, ProblemDetail).
    * **Dependencies:** `I1.T3`
    * **Parallelizable:** Yes

<!-- anchor: task-i1-t5 -->
* **Task 1.5:**
    * **Task ID:** `I1.T5`
    * **Description:** Implement TenantContext, TenantFilter, and request-scope wiring plus stub Panache repositories enforcing tenant filters; include unit tests for subdomain + custom domain parsing.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Section 2 communication patterns, OpenAPI base spec.
    * **Input Files**: ["src/main/java/com/village/storefront/tenant/**", "api/openapi-base.yaml"]
    * **Target Files:** ["src/main/java/com/village/storefront/tenant/TenantContext.java", "src/main/java/com/village/storefront/tenant/TenantFilter.java", "src/test/java/com/village/storefront/tenant/TenantFilterTest.java"]
    * **Deliverables:** Working `@RequestScoped` context, JAX-RS ContainerRequestFilter, placeholder TenantService with TODO for DB lookup, and tests covering host parsing + 404 path.
    * **Acceptance Criteria:**
        - Tests cover subdomain + custom-domain resolution and fail invalid hosts with 404.
        - Filter populates MDC/structured logging fields for tenant_id + store_id.
        - Code passes Spotless + JaCoCo thresholds; TODOs reference upcoming tasks for DB integration.
    * **Dependencies:** `I1.T4`
    * **Parallelizable:** No

<!-- anchor: task-i1-t6 -->
* **Task 1.6:**
    * **Task ID:** `I1.T6`
    * **Description:** Write initial MyBatis migrations for Tenant, Store, CustomDomain, StoreUser, Role, FeatureFlag tables including tenant_id columns, default data, and baseline RLS policies; document migration strategy in comments.
    * **Agent Type Hint:** `DatabaseAgent`
    * **Inputs:** ERD, docs/java-project-standards RLS section.
    * **Input Files**: ["docs/diagrams/tenant-erd.mmd", "docs/java-project-standards.adoc"]
    * **Target Files:** ["src/main/resources/db/migrations/0001_create_tenant_tables.sql", "src/main/resources/db/migrations/0002_rss_policies.sql"]
    * **Deliverables:** Ordered migration scripts with up/down sections, test fixtures for verifying RLS via integration test harness.
    * **Acceptance Criteria:**
        - Scripts apply cleanly on PostgreSQL 17 container with MyBatis CLI.
        - RLS policies restrict select/update/delete to tenant context user.
        - Integration test (`TenantTablesIT`) proves unauthorized tenant_id access fails.
    * **Dependencies:** `I1.T5`
    * **Parallelizable:** No

<!-- anchor: task-i1-t7 -->
* **Task 1.7:**
    * **Task ID:** `I1.T7`
    * **Description:** Configure GitHub Actions workflow + ops scaffolding (ops/k8s base manifests, README snippet) covering Maven verify, Quinoa build placeholder, OpenAPI lint, and artifact uploads to `ops/k8s/base`.
    * **Agent Type Hint:** `DevOpsAgent`
    * **Inputs:** Section 2 Deployment, docs/java-project-standards CI rules.
    * **Input Files**: ["README.md", "ops/k8s/", "api/openapi-base.yaml"]
    * **Target Files:** [".github/workflows/ci.yml", "ops/k8s/base/deployment.yaml", "ops/k8s/base/service.yaml", "ops/scripts/smoke.sh"]
    * **Deliverables:** CI workflow with lint/test/build matrix (JVM + Native), placeholder Kubernetes manifests referencing image names, smoke script invoking `/q/health`.
    * **Acceptance Criteria:**
        - Workflow runs Spotless, unit tests, OpenAPI lint, Quinoa `npm run lint`, and uploads manifests as artifacts.
        - Base deployment includes resource requests/limits, readiness/liveness probes, ConfigMap/Secret placeholders.
        - Smoke script documented in README and used in CI job.
    * **Dependencies:** `I1.T1`
    * **Parallelizable:** Yes
* **Iteration Exit Criteria:**
  - Component + ERD diagrams reviewed with lead architect and linked inside README design references.
  - OpenAPI base spec approved with lint status recorded in CI artifacts.
  - Tenant filter + migrations validated against local PostgreSQL container with RLS tests proving isolation.
  - CI workflow demonstrates green run including Quinoa placeholder lint and smoke script execution logs.
* **Iteration Metrics:**
  - Target setup cycle time ≤ 5 days with zero CI regressions; track in planning doc.
  - Architecture artifacts stored under docs/diagrams with commit hashes referenced in plan_manifest.
