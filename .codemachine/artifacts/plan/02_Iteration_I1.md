<!-- anchor: directives-and-process -->
## 4. Directives & Strict Process

*   Honor command discipline: autonomous agents must avoid `ls`/exploratory commands unless debugging approved incidents; rely on repo map above and task-scoped paths.
*   Follow internal blueprinting + single-write policy when generating artifacts (docs, diagrams, specs); buffer complete content before writing, then verify via scripted line-count checks.
*   Maintain spec-first flow: diagrams, OpenAPI, and ADRs precede code; code tasks block until upstream artifacts land in version control with reviewable anchors.
*   Enforce quality gates continuously (Spotless, JaCoCo ≥80%, linting, CI) rather than deferring to final iteration; document deviations via ADR addendum referencing this section.
*   Provide traceable deliverables: every artifact carries metadata (owner, date, iteration) and anchor references for manifest indexing.
*   Keep manifest synchronized: after each artifact merge, update `plan_manifest.json` entry placeholders with anchor keys for automated retrieval.
*   Capture retrospective notes at iteration end to refine directives for later iterations (include summary at top of `02_Iteration_I2.md`). 
*   Notify platform ops + security leads when ADRs or specs change tenant isolation guarantees to maintain governance alignment.

<!-- anchor: iteration-plan-overview -->
## 5. Iteration Plan

*   **Total Iterations Planned:** 5
*   **Iteration Dependencies:** I1 delivers foundation artifacts enabling domain implementation. I2 builds storefront/catalog/checkout skeletons atop I1 specs. I3 layers consignment/inventory depth referencing I2 APIs. I4 finalizes payments/media/checkout polish built on I2/I3. I5 hardens admin, platform ops, deployment, observability depending on all prior iterations.

<!-- anchor: iteration-1-plan -->
### Iteration 1: Architectural Foundations & Multi-Tenant Guardrails

*   **Iteration ID:** `I1`
*   **Goal:** Produce authoritative specs (OpenAPI seed, component diagram, ERD, tenant routing flow), initial ADRs, and CI/quality scaffolding so downstream iterations can build confidently.
*   **Prerequisites:** None.
*   **Iteration Milestones & Exit Criteria:**
    1. Architecture overview + ADR-001 merged with traceable anchors and change log.
    2. OpenAPI baseline validated via lint plus mocked contract tests for tenant/auth endpoints.
    3. Component + ERD diagrams render successfully and referenced from overview.
    4. Tenant filter prototype passes unit tests covering subdomain/custom-domain/404 paths.
    5. CI workflow enforcing Spotless + JaCoCo + OpenAPI lint green on main branch.
    6. Manifest entries for all new artifacts submitted for downstream retrieval tooling.

<!-- anchor: task-i1-t1 -->
*   **Task 1.1:**
    *   **Task ID:** `I1.T1`
    *   **Description:** Audit existing repo assets, ingest `docs/java-project-standards.adoc` + competitor research, and craft `docs/architecture_overview.md` overview plus ADR-001 describing layered modular monolith + tenancy approach; include risk register + decision log template.
    *   **Agent Type Hint:** `ArchitectureAgent`
    *   **Inputs:** Requirements brief, standards doc, competitor research notes.
    *   **Input Files:** [`docs/java-project-standards.adoc`, `.codemachine/inputs/competitor-research.md`]
    *   **Target Files:** [`docs/architecture_overview.md`, `docs/adr/ADR-001-tenancy.md`]
    *   **Deliverables:** Updated overview doc linking sections 1-3, ADR covering tenancy + TenantContext contract, decision log template snippet.
    *   **Acceptance Criteria:** Documents cite constraints, reference sections, include decision/rationale/implications; cross-link anchors inserted for manifest; reviewers can trace requirements to architecture choices.
    *   **Testing Guidance:** Run Markdown lint + link checker, solicit review from commerce + platform stakeholders, and confirm risk table addresses assumptions listed in Section 1.6.
    *   **Observability Hooks:** Document mandatory structured-log fields (tenant_id, store_id, correlation_id) plus baseline metrics expected from each module.
    *   **Dependencies:** None.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t2 -->
*   **Task 1.2:**
    *   **Task ID:** `I1.T2`
    *   **Description:** Bootstrap spec-first OpenAPI baseline (`api/v1/openapi.yaml`) covering tenant resolution, auth, catalog placeholder, checkout placeholder, and platform admin endpoints; include reusable schemas (Money, Address, Pagination) and security schemes.
    *   **Agent Type Hint:** `APIDesignAgent`
    *   **Inputs:** Requirements (sections on APIs, auth, headless), ADR-001 decisions.
    *   **Input Files:** [`docs/architecture_overview.md`, `.codemachine/inputs/competitor-research.md`]
    *   **Target Files:** [`api/v1/openapi.yaml`]
    *   **Deliverables:** Valid OpenAPI 3.0 YAML passing `spectral` lint (document lint command inline), with section comments linking planned endpoints.
    *   **Acceptance Criteria:** Lints cleanly, defines tagging strategy (Storefront, Admin, Headless, Platform), enumerates auth flows + idempotency headers, includes placeholder schemas for core entities.
    *   **Testing Guidance:** Execute `spectral lint api/v1/openapi.yaml` and `swagger-cli validate`, then generate sample client SDK to ensure schema completeness; add CI snippet verifying commands.
    *   **Observability Hooks:** Specify standard headers (`X-Trace-Id`, `X-Tenant-Id`, `X-Impersonation-Id`) and Problem Details extensions so logging/tracing remain consistent.
    *   **Dependencies:** `I1.T1`.
    *   **Parallelizable:** Yes (after ADR ready).

<!-- anchor: task-i1-t3 -->
*   **Task 1.3:**
    *   **Task ID:** `I1.T3`
    *   **Description:** Produce PlantUML component diagram (`docs/diagrams/component_overview.puml`) showing bounded contexts, adapters, queues, and external services; embed legend + anchor references for major flows.
    *   **Agent Type Hint:** `DiagrammingAgent`
    *   **Inputs:** ADR-001, architecture overview, requirements components.
    *   **Input Files:** [`docs/architecture_overview.md`, `docs/adr/ADR-001-tenancy.md`]
    *   **Target Files:** [`docs/diagrams/component_overview.puml`]
    *   **Deliverables:** PlantUML diagram renderable via CLI with annotation of Quarkus modules, background workers, integrations.
    *   **Acceptance Criteria:** Diagram compiles, contains all key modules from Section 2, indicates planned PlantUML include library, exports PNG for review (document command), matches textual description.
    *   **Testing Guidance:** Run PlantUML CLI locally + via CI, attach rendered PNG in docs, and capture diff vs reference image to guard against drift.
    *   **Observability Hooks:** Embed callouts labeling metrics/tracing owners (checkout spans, job depth gauges) to align with Section 6 verification strategy.
    *   **Dependencies:** `I1.T1`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i1-t4 -->
*   **Task 1.4:**
    *   **Task ID:** `I1.T4`
    *   **Description:** Draft PlantUML ERD + initial MyBatis migration scaffolding; define schema for tenant, user, product, variant, catalog taxonomy, carts/orders/payments, consignment, loyalty, audit tables with tenant_id + metadata columns.
    *   **Agent Type Hint:** `DatabaseAgent`
    *   **Inputs:** OpenAPI baseline, component diagram, requirements (data model, tenancy, RLS, retention).
    *   **Input Files:** [`api/v1/openapi.yaml`, `docs/diagrams/component_overview.puml`]
    *   **Target Files:** [`docs/diagrams/datamodel_erd.puml`, `src/main/resources/db/migrations/V20260102__baseline_schema.sql`]
    *   **Deliverables:** ERD diagram + SQL migration with tables, indexes, constraints placeholders, comments for RLS to be added later.
    *   **Acceptance Criteria:** Diagram renders, migration applies cleanly against dev PostgreSQL, includes tenant_id + audit columns, sets up `flyway_schema_history` equivalent per standards, cross-references Section 5 contract.
    *   **Testing Guidance:** Apply migration against disposable PostgreSQL container, run rollback dry-run, and assert required indexes exist via `psql \\d` output logged in PR.
    *   **Observability Hooks:** Annotate schema comments describing audit/partition columns and capture TODO for RLS policies + retention jobs referenced in Section 6.
    *   **Dependencies:** `I1.T2`, `I1.T3`.
    *   **Parallelizable:** No (await diagrams/specs).

<!-- anchor: task-i1-t5 -->
*   **Task 1.5:**
    *   **Task ID:** `I1.T5`
    *   **Description:** Prototype Tenant Access Gateway + FeatureToggle skeleton (Quarkus filter, Caffeine cache) and capture tenant-routing sequence diagram; include smoke tests verifying host parsing, custom domain lookup, and context propagation.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** ADR-001, ERD (tenant/custom_domains tables), OpenAPI security sections.
    *   **Input Files:** [`docs/adr/ADR-001-tenancy.md`, `docs/diagrams/datamodel_erd.puml`]
    *   **Target Files:** [`src/main/java/com/village/tenant/TenantFilter.java`, `src/main/java/com/village/tenant/TenantContext.java`, `docs/diagrams/sequence_tenant_routing.mmd`, `tests/backend/TenantFilterTest.java`]
    *   **Deliverables:** Minimal but compiling Quarkus filter + context bean, Caffeine config stub, unit tests for host parsing, Mermaid diagram of routing path.
    *   **Acceptance Criteria:** Quarkus dev mode builds filter, tests cover subdomain/custom domain, diagram outlines host parsing→TenantContext→FeatureToggle; comments referencing Section 2/5 anchors.
    *   **Testing Guidance:** Cover success/failure variants using parameterized tests, run `mvn test -Dtest=TenantFilterTest`, and document dev services config for DNS mocks.
    *   **Observability Hooks:** Add TODO structured logs (resolution latency, unknown host warnings) and outline metrics (counter per tenant, gauge for cache size) for future Section 6 instrumentation.
    *   **Dependencies:** `I1.T4`.
    *   **Parallelizable:** No (requires schema + context definitions).

<!-- anchor: task-i1-t6 -->
*   **Task 1.6:**
    *   **Task ID:** `I1.T6`
    *   **Description:** Establish CI/CD scaffolding: GitHub Actions workflow running Maven (Spotless, tests, JaCoCo), npm lint/test for admin SPA, OpenAPI lint, PlantUML check; add `.github/workflows/ci.yml`, configure JaCoCo 80% rule, document pipeline in README + ADR-002 (quality gates).
    *   **Agent Type Hint:** `DevOpsAgent`
    *   **Inputs:** Standards doc, existing pom, iteration artifacts (OpenAPI, diagrams) for validation.
    *   **Input Files:** [`docs/java-project-standards.adoc`, `pom.xml`, `package.json`]
    *   **Target Files:** [`.github/workflows/ci.yml`, `pom.xml`, `package.json`, `docs/adr/ADR-002-quality-gates.md`, `README.md`]
    *   **Deliverables:** Workflow file with matrix (JVM + native), npm scripts invoked, code coverage enforcement, README CI badge + instructions; ADR describing guardrails.
    *   **Acceptance Criteria:** Workflow references correct commands, fails on formatting/coverage, README documents usage, ADR records trade-offs.
    *   **Testing Guidance:** Execute workflow via GitHub Actions plus local `act` dry-run, capture screenshots/log links, and intentionally break formatting to confirm failure path.
    *   **Observability Hooks:** Configure workflow to upload coverage + lint reports as artifacts and emit job timing metrics for future optimization.
    *   **Dependencies:** `I1.T2`, `I1.T3`, `I1.T4` (for validation targets).
    *   **Parallelizable:** Yes (after upstream artifacts exist).

*   **Iteration KPIs & Validation Strategy:**
    - Architecture documents reviewed with at least two stakeholders; review notes logged beside ADR links.
    - OpenAPI validation automated within CI (spectral or equivalent) and invoked locally with documented command in README.
    - Diagram renders verified via `plantuml` CLI; PNG exports attached to PR for visual confirmation.
    - Tenant filter unit tests achieve ≥90% branch coverage; test harness seeds mock domains and ensures context cleanup.
    - CI workflow run recorded in GitHub Actions with proof of Spotless + JaCoCo thresholds enforced; README includes badge linking to latest status.
    - Manifest entries drafted for each new anchor (architecture overview, ADRs, diagrams, spec) to keep downstream agent lookup consistent.
*   **Iteration Risk Log & Mitigations:**
    - *Spec drift:* Downstream feature squads might bypass spec updates; mitigation—merge checklist demands updated OpenAPI/diagram anchors before domain PR approval.
    - *Migration brittleness:* Early schema may omit indexes; mitigation—document TODOs and schedule `I2` backlog card for performance audit once catalog modeling boots.
    - *CI instability:* Native builds prolong pipeline; mitigation—configure caching + matrix concurrency and measure runtimes in ADR-002 appendix.
    - *Tenant filter regressions:* Host parsing edge cases could slip; mitigation—expand fixture set + synthetic tests per new tenant onboarding scenario.
    - *Anchor omissions:* Missing anchors break manifest; mitigation—manifest review gate integrated into PR template with checkbox per artifact.
*   **Iteration Backlog & Follow-ups:**
    - Create ADR-003 for PaymentProvider extensibility (draft outline scheduled for `I2` once checkout flows evolve).
    - Prepare skeleton Feature Flag governance doc (ties into Section 4 directives) to be filled in `I5`.
    - Open ticket for automated PlantUML render job hooking into CI artifacts (target `I2`).
    - Draft plan for repository-wide developer onboarding doc referencing produced specs; deliverable scheduled early `I2`.
