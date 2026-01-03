<!-- anchor: iteration-4-plan -->
### Iteration 4: Loyalty, Platform Admin, and Impersonation Governance

* **Iteration ID:** `I4`
* **Goal:** Add loyalty/rewards assets (diagram + implementation), expand checkout + reporting to use loyalty, build platform admin console APIs with impersonation safeguards, document impersonation flow via sequence diagram, and introduce multi-currency/FX services plus enhanced observability + audit tooling.
* **Prerequisites:** `I1`–`I3` outputs (catalog, consignment, media, reporting, ADRs, diagrams).
* **Tasks:**

<!-- anchor: task-i4-t1 -->
* **Task 4.1:**
    * **Task ID:** `I4.T1`
    * **Description:** Create Loyalty Domain Model diagram (Mermaid) detailing LoyaltyProgram, Tier, LoyaltyLedgerEntry, TierRule, ExpirationPolicy, GiftCard, StoreCredit relationships and interactions with Customer + Order entities.
    * **Agent Type Hint:** `DiagrammingAgent`
    * **Inputs:** Section 2 data model, Section 9 loyalty requirements.
    * **Input Files**: ["docs/diagrams/tenant-erd.mmd", "docs/diagrams/component-overview.puml"]
    * **Target Files:** ["docs/diagrams/loyalty-model.mmd"]
    * **Deliverables:** Mermaid class/ER diagram with notes on accrual, redemption, expiration, and audit metadata.
    * **Acceptance Criteria:**
        - Diagram passes lint, includes multi-currency fields, references loyalty KPIs.
        - Links to reporting + checkout modules via annotations.
        - README updates mention new diagram.
    * **Dependencies:** `I3.T1`
    * **Parallelizable:** Yes

<!-- anchor: task-i4-t2 -->
* **Task 4.2:**
    * **Task ID:** `I4.T2`
    * **Description:** Implement Loyalty service: ledger entities, accrual/redemption logic, tier recalculation scheduled job, customer APIs for viewing history, admin endpoints for configuration; integrate with checkout service.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Loyalty diagram, ADRs, checkout code.
    * **Input Files**: ["docs/diagrams/loyalty-model.mmd", "src/main/java/com/village/storefront/checkout/**", "api/openapi-base.yaml"]
    * **Target Files:** ["src/main/java/com/village/storefront/loyalty/*.java", "src/test/java/com/village/storefront/loyalty/LoyaltyServiceTest.java", "src/main/resources/db/migrations/0008_loyalty.sql", "api/openapi-base.yaml"]
    * **Deliverables:** Ledger tables + Panache entities, service orchestrating accrual/redemption, scheduled job, REST endpoints + DTOs, integration tests verifying loyalty flows.
    * **Acceptance Criteria:**
        - Checkout orchestrator invokes loyalty service for accrual + redemption with compensating actions on failure.
        - Tier recalculation job logs metrics + respects ADR job governance.
        - Tests cover expiration + multi-currency conversions.
    * **Dependencies:** `I4.T1`, `I3.T7`
    * **Parallelizable:** No

<!-- anchor: task-i4-t3 -->
* **Task 4.3:**
    * **Task ID:** `I4.T3`
    * **Description:** Document platform impersonation flow via PlantUML (Flow D) covering Platform Admin Console, Identity, Feature Flags, Tenant Gateway, Admin SPA, Storefront, Audit events, Reporting.
    * **Agent Type Hint:** `DiagrammingAgent`
    * **Inputs:** Section 3 Flow D, Identity service, ADR for feature flags.
    * **Input Files**: ["docs/diagrams/component-overview.puml", "api/openapi-base.yaml"]
    * **Target Files:** ["docs/diagrams/seq-impersonation.puml"]
    * **Deliverables:** Sequence diagram with emphasis on reason codes, banner display, audit + reporting updates, token revocation.
    * **Acceptance Criteria:**
        - Diagram renders and references security constraints + reason field requirement.
        - Contains notes about logging, audit, session updates.
        - Linked from README + platform admin docs.
    * **Dependencies:** `I2.T4`
    * **Parallelizable:** Yes

<!-- anchor: task-i4-t4 -->
* **Task 4.4:**
    * **Task ID:** `I4.T4`
    * **Description:** Implement Platform Admin backend: SaaS dashboard APIs (store list, revenue, system health), impersonation endpoints (start/heartbeat/end), global reporting views, and admin-specific RBAC scopes.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Impersonation diagram, reporting service, identity.
    * **Input Files**: ["src/main/java/com/village/storefront/platformops/**", "docs/diagrams/seq-impersonation.puml", "src/main/java/com/village/storefront/reporting/**"]
    * **Target Files:** ["src/main/java/com/village/storefront/platformops/*.java", "src/test/java/com/village/storefront/platformops/PlatformOpsResourceTest.java", "api/openapi-base.yaml", "src/main/resources/db/migrations/0009_platformops.sql"]
    * **Deliverables:** REST endpoints for store management, impersonation audit retrieval, platform metrics; RBAC annotations; tests verifying super-user scopes.
    * **Acceptance Criteria:**
        - Endpoints enforce MFA + reason codes; tests cover unauthorized access.
        - Platform metrics use reporting aggregates with data freshness metadata.
        - Swagger docs highlight `platform` scope + rate limits.
    * **Dependencies:** `I4.T3`, `I3.T7`
    * **Parallelizable:** No

<!-- anchor: task-i4-t5 -->
* **Task 4.5:**
    * **Task ID:** `I4.T5`
    * **Description:** Build multi-currency display + FX service: scheduled job fetching rates, Money value object, API for conversion, integration with catalog and checkout DTOs; update OpenAPI specs + tests.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Section 3 multi-currency assumptions, loyalty/calc requirements.
    * **Input Files**: ["src/main/java/com/village/storefront/common/money/**", "api/openapi-base.yaml", "src/main/resources/db/migrations/0010_fx.sql"]
    * **Target Files:** ["src/main/java/com/village/storefront/money/*.java", "src/test/java/com/village/storefront/money/MoneyServiceTest.java", "src/main/resources/db/migrations/0010_fx.sql", "ops/scripts/fetch_fx_rates.sh"]
    * **Deliverables:** Money classes with integer minor units, FX job, caching, conversion API endpoints, tests verifying rounding + fallback.
    * **Acceptance Criteria:**
        - FX job caches daily rates, logs timestamp + source; tests simulate stale data fallback.
        - Checkout + catalog DTOs include `displayCurrency` vs `settlementCurrency` fields.
        - Ops script documented for manual re-run.
    * **Dependencies:** `I2.T5`, `I3.T7`
    * **Parallelizable:** Yes

<!-- anchor: task-i4-t6 -->
* **Task 4.6:**
    * **Task ID:** `I4.T6`
    * **Description:** Enhance observability + audit logging: implement correlation ID propagation, OpenTelemetry span enrichers for loyalty + platform endpoints, structured logging fields for impersonation and tenant plan, dashboards showing KPIs (Grafana JSON).
    * **Agent Type Hint:** `DevOpsAgent`
    * **Inputs:** Section 3 observability, Section 4 KPIs.
    * **Input Files**: ["src/main/java/com/village/storefront/common/logging/**", "ops/k8s/base/deployment.yaml", "docs/diagrams/seq-impersonation.puml"]
    * **Target Files:** ["src/main/java/com/village/storefront/common/logging/TracingFilter.java", "src/main/resources/application.properties", "ops/observability/grafana-loyalty.json"]
    * **Deliverables:** Logging filter injecting IDs, OpenTelemetry instrumentation, Grafana dashboards for loyalty accrual + impersonation, doc updates.
    * **Acceptance Criteria:**
        - Logs include tenant_id, store_id, impersonationId, loyaltyTier; sample entries documented.
        - Grafana dashboard JSON validated via lint script and referenced in README.
        - Telemetry extends to Quinoa admin SPA fetches through response headers.
    * **Dependencies:** `I4.T2`, `I4.T3`
    * **Parallelizable:** Yes

<!-- anchor: task-i4-t7 -->
* **Task 4.7:**
    * **Task ID:** `I4.T7`
    * **Description:** Update Admin SPA to expose platform admin workspace: dashboards for store metrics, impersonation controls with reason entry, loyalty configuration UI, localization updates for Spanish.
    * **Agent Type Hint:** `FrontendAgent`
    * **Inputs:** Platform ops APIs, loyalty endpoints, SPA code.
    * **Input Files**: ["src/main/webui/src/**", "api/openapi-base.yaml", "docs/diagrams/seq-impersonation.puml"]
    * **Target Files:** ["src/main/webui/src/views/platform/*.vue", "src/main/webui/src/stores/platform.ts", "src/main/webui/src/locales/en.json", "src/main/webui/src/locales/es.json"]
    * **Deliverables:** Vue views for dashboards + impersonation, store modules hitting APIs, localization strings, tests verifying reason enforcement + Spanish translations.
    * **Acceptance Criteria:**
        - UI shows impersonation banner + exit CTA; tests confirm reason required.
        - Dashboards pull data freshness timestamps from reporting API.
        - Localization coverage ensures new strings exist in en/es files.
    * **Dependencies:** `I4.T4`, `I4.T2`
    * **Parallelizable:** No

<!-- anchor: iteration-4-exit -->
* **Iteration Exit Criteria:**
  - Loyalty service integrated and validated with checkout, reporting, and dashboards, with job metrics visible.
  - Platform admin APIs + UI enforce impersonation governance, audit logging, and display system KPIs.
  - Multi-currency conversions applied to catalog + checkout; FX job documented with runbook.
  - Observability dashboards + structured logs rolled out and referenced in README/ops docs.

<!-- anchor: iteration-4-metrics -->
* **Iteration Metrics:**
  - Loyalty ledger operations maintain <20ms overhead; monitored via Grafana panels.
  - Platform admin API latency <300ms P95; impersonation audit logs available within 30s.
  - Multi-currency rates refreshed daily with <5% drift vs external benchmark.

<!-- anchor: iteration-4-risks -->
* **Iteration Risks & Actions:**
  - Loyalty point miscalculations risk trust; add simulation tests with recorded fixtures.
  - Impersonation misuse mitigated with SOC review + security signoff before release.
  - FX provider outages handled via fallback caching; document manual override steps and escalate thresholds.

<!-- anchor: iteration-4-followup -->
* **Iteration Follow-Up Actions:**
  - Schedule compliance review for platform admin logging + impersonation visibility.
  - Prepare user training materials for loyalty config + platform dashboards.
