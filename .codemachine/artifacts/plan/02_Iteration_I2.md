<!-- anchor: iteration-2-plan -->
### Iteration 2: Multi-Tenancy Services, Governance ADRs, and Catalog/API Foundations

* **Iteration ID:** `I2`
* **Goal:** Formalize governance (job + feature flag ADRs), capture checkout orchestration in sequence diagram, implement identity/auth services with session logging, expose catalog + tenant APIs driven by spec-first contracts, scaffold admin SPA tooling, and define CI/CD pipeline visualization.
* **Prerequisites:** `I1` completed (project skeleton, diagrams, ERD, OpenAPI base, CI baseline).
* **Tasks:**

<!-- anchor: task-i2-t1 -->
* **Task 2.1:**
    * **Task ID:** `I2.T1`
    * **Description:** Write ADR `0001-delayed-job-governance` detailing queue naming, priority, worker scaling policy, payload schema expectations, and observability metrics referenced across jobs.
    * **Agent Type Hint:** `DocumentationAgent`
    * **Inputs:** Section 2 messaging/queues, Section 4 enforcement playbooks.
    * **Input Files**: ["docs/java-project-standards.adoc", "docs/diagrams/component-overview.puml", "jobs/workers/"]
    * **Target Files:** ["docs/adr/0001-delayed-job-governance.md"]
    * **Deliverables:** ADR with context/problem/decision/consequences, links to plan sections, checklists for worker pods.
    * **Acceptance Criteria:**
        - ADR follows markdown template, includes status = Accepted.
        - Documents queue taxonomy (CRITICAL/HIGH/DEFAULT/LOW/BULK) with SLA + owner mapping.
        - References instrumentation + retry policies; cross-links to future worker tasks.
    * **Dependencies:** `I1.T2`
    * **Parallelizable:** Yes

<!-- anchor: task-i2-t2 -->
* **Task 2.2:**
    * **Task ID:** `I2.T2`
    * **Description:** Draft ADR `0002-feature-flag-governance` describing flag lifecycle, owner metadata, expiry policies, audit logging, and required metadata in OpenAPI `x-feature-flags`.
    * **Agent Type Hint:** `DocumentationAgent`
    * **Inputs:** Section 3 enforcement playbooks, Section 5 contract patterns.
    * **Input Files**: ["README.md", "api/openapi-base.yaml", "docs/adr/0001-delayed-job-governance.md"]
    * **Target Files:** ["docs/adr/0002-feature-flag-governance.md"]
    * **Deliverables:** ADR with diagrams or tables describing overrides per tenant and CLI/admin UI responsibilities.
    * **Acceptance Criteria:**
        - Specifies schema for `feature_flags` table + API exposures.
        - Defines review cadence, owner fields, expiry enforcement automation.
        - Links to plan Section 3 + Section 5; referenced in README and future tasks.
    * **Dependencies:** `I2.T1`
    * **Parallelizable:** Yes

<!-- anchor: task-i2-t3 -->
* **Task 2.3:**
    * **Task ID:** `I2.T3`
    * **Description:** Produce PlantUML checkout sequence diagram capturing storefront request, TenantContext resolution, catalog calls, address validation, shipping rates, Stripe intent creation, loyalty hooks, reporting events (per Section 3 Proposed Architecture Flow A).
    * **Agent Type Hint:** `DiagrammingAgent`
    * **Inputs:** OpenAPI base spec, component diagram, Section 3 flow.
    * **Input Files**: ["docs/diagrams/component-overview.puml", "api/openapi-base.yaml", "docs/adr/0002-feature-flag-governance.md"]
    * **Target Files:** ["docs/diagrams/seq-checkout.puml"]
    * **Deliverables:** PlantUML with swim lanes for Shopper, Tenant Gateway, Storefront, Catalog, Checkout, Adapters, Stripe, Reporting.
    * **Acceptance Criteria:**
        - Diagram renders successfully and references plan Flow A steps.
        - Notes describe idempotency keys + feature flag gating.
        - File cross-links to relevant API endpoints in comments.
    * **Dependencies:** `I1.T4`
    * **Parallelizable:** Yes

<!-- anchor: task-i2-t4 -->
* **Task 2.4:**
    * **Task ID:** `I2.T4`
    * **Description:** Implement Identity & Session Service features: JWT/refresh issuance endpoints, session logging entity, refresh token persistence, impersonation audit hook stubs; update OpenAPI spec plus integration tests hitting PostgreSQL.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** OpenAPI base spec, ERD, ADRs for governance.
    * **Input Files**: ["api/openapi-base.yaml", "docs/diagrams/tenant-erd.mmd", "src/main/java/com/village/storefront/identity/**"]
    * **Target Files:** ["api/openapi-base.yaml", "src/main/java/com/village/storefront/identity/*.java", "src/test/java/com/village/storefront/identity/IdentityResourceTest.java", "src/main/resources/db/migrations/0003_identity.sql"]
    * **Deliverables:** REST endpoints for login/refresh/impersonation start-stop (stubs), Panache entities for session logs, service wiring hooking TenantContext, and tests hitting Quarkus devservices Postgres.
    * **Acceptance Criteria:**
        - Tests cover JWT generation, refresh, impersonation rejection when reason missing.
        - Session log writes include tenant_id, user_agent, ip fields mirroring Section 3 requirements.
        - OpenAPI spec updated with security schemes + responses; lint passes.
    * **Dependencies:** `I1.T6`
    * **Parallelizable:** No

<!-- anchor: task-i2-t5 -->
* **Task 2.5:**
    * **Task ID:** `I2.T5`
    * **Description:** Extend catalog and tenant APIs: implement product/category CRUD (server stubs), Panache repositories with tenant filters, and unit tests verifying RLS interactions; update OpenAPI spec `catalog-public` + `catalog-admin` tags accordingly.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** ERD, OpenAPI base, Tenant filter code.
    * **Input Files**: ["api/openapi-base.yaml", "src/main/java/com/village/storefront/catalog/**", "src/main/java/com/village/storefront/tenant/TenantContext.java"]
    * **Target Files:** ["src/main/java/com/village/storefront/catalog/*.java", "src/test/java/com/village/storefront/catalog/CatalogResourceTest.java", "api/openapi-catalog.yaml", "src/main/resources/db/migrations/0004_catalog.sql"]
    * **Deliverables:** Catalog service/resour­ces, DTO mappers, tests, and updated spec split `openapi-catalog.yaml` referencing shared components.
    * **Acceptance Criteria:**
        - Panache base repository automatically injects tenant filter; tests prove data leakage prevented.
        - OpenAPI documents filtering/pagination + ProblemDetails for validation errors.
        - Migrations create required indexes + JSONB custom_attributes field.
    * **Dependencies:** `I2.T4`
    * **Parallelizable:** No

<!-- anchor: task-i2-t6 -->
* **Task 2.6:**
    * **Task ID:** `I2.T6`
    * **Description:** Scaffold Admin SPA (Vue 3 + Vite + Tailwind + PrimeVue) via Quinoa: configure lint/test scripts, Tailwind tokens fetch stub, login shell using Identity endpoints, and localization scaffolding (en/es JSON).
    * **Agent Type Hint:** `FrontendAgent`
    * **Inputs:** Section 2 frontend stack, Identity endpoints, feature flag ADR.
    * **Input Files**: ["src/main/webui/package.json", "src/main/webui/vite.config.ts", "api/openapi-base.yaml"]
    * **Target Files:** ["src/main/webui/src/main.ts", "src/main/webui/src/router/index.ts", "src/main/webui/src/locales/en.json", "src/main/webui/src/locales/es.json", "src/main/webui/package.json", "src/main/webui/tailwind.config.js"]
    * **Deliverables:** Working SPA scaffold with auth layout, feature flag injection placeholder, unit tests (Vitest) verifying login form + localization toggle.
    * **Acceptance Criteria:**
        - `npm run test` + `npm run lint` succeed inside CI job; Quinoa build integrates with Maven profile.
        - Tailwind config reads design tokens placeholder and documents pipeline for pulling from DB.
        - Router enforces `/admin/*` guard requiring JWT + impersonation banner indicator slot.
    * **Dependencies:** `I2.T4`
    * **Parallelizable:** Yes

<!-- anchor: task-i2-t7 -->
* **Task 2.7:**
    * **Task ID:** `I2.T7`
    * **Description:** Create CI/CD pipeline diagram (Mermaid) describing GitHub Actions stages (lint/test/native build, Quinoa build, OpenAPI validation, manifest generation), artifact storage, and k3s deployment promotion, referencing Section 3 deployment view.
    * **Agent Type Hint:** `DiagrammingAgent`
    * **Inputs:** Section 2 deployment stack, CI workflow from `I1.T7`.
    * **Input Files**: [".github/workflows/ci.yml", "README.md"]
    * **Target Files:** ["docs/diagrams/ci-cd-pipeline.mmd"]
    * **Deliverables:** Mermaid diagram with nodes for developer commit, GitHub Actions jobs, container registry, k3s overlays, smoke tests.
    * **Acceptance Criteria:**
        - Diagram renders via mermaid-cli; includes annotations for SLO gates (Spotless, JaCoCo, OpenAPI lint).
        - Highlights blue/green deploy path and secret management.
        - Linked from README + plan manifest.
    * **Dependencies:** `I1.T7`
    * **Parallelizable:** Yes

<!-- anchor: iteration-2-exit -->
* **Iteration Exit Criteria:**
  - ADRs approved and referenced in README plus CI pipeline docs.
  - Checkout sequence diagram + CI/CD pipeline diagram committed and cross-linked.
  - Identity + catalog services expose working endpoints with passing integration + frontend tests.
  - Admin SPA bootstrap builds within CI and consumes feature flag metadata stub.

<!-- anchor: iteration-2-metrics -->
* **Iteration Metrics:**
  - Track auth endpoint latency + test coverage; target >85% for identity module.
  - Catalog RLS tests must cover at least one unhappy path per HTTP verb.
  - SPA bundle size baseline recorded (<2MB gzipped) to benchmark future work.
<!-- anchor: iteration-2-risks -->
* **Iteration Risk & Coordination Notes:**
  - Align auth + SPA teams on token shapes before QA; mismatched scopes stall later checkout integration.
  - Checkout diagram stakeholders (payments, catalog, loyalty) must sign off before implementation tasks start in `I3`.
  - Track ADR follow-ups as backlog items to update admin UI for flag management + background job monitoring dashboards.
  - Ensure CI/CD diagram reviewers from DevOps approve secrets handling depiction to avoid audit rework.
<!-- anchor: iteration-2-followup -->
* **Iteration Follow-Up Actions:**
  - Schedule security review for new auth endpoints focusing on rate limiting + brute force mitigation.
  - Draft onboarding guide for SPA developers describing Quinoa workflow, linting, and localization expectations.
