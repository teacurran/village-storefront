<!-- anchor: iteration-3-plan -->
### Iteration 3: Consignment Domain, Media Pipeline, and Reporting Foundations

* **Iteration ID:** `I3`
* **Goal:** Extend data model + specs for consignment/lifecycle features, implement consignment APIs + payout orchestration hooks, formalize media upload + processing flows (including diagrams), stand up DelayedJob workers with FFmpeg integration, and seed reporting projections consuming domain events.
* **Prerequisites:** `I1` + `I2` deliverables (ERD, OpenAPI, ADRs, identity/catalog services, checkout diagram).
* **Tasks:**

<!-- anchor: task-i3-t1 -->
* **Task 3.1:**
    * **Task ID:** `I3.T1`
    * **Description:** Update Mermaid ERD with consignment-specific entities (Consignor, ConsignmentItem, CommissionRule, PayoutBatch), POS device tables, and media metadata tables; annotate archive policies.
    * **Agent Type Hint:** `DatabaseAgent`
    * **Inputs:** Section 2 data model, consignment requirements, ADRs.
    * **Input Files**: ["docs/diagrams/tenant-erd.mmd", "docs/adr/0001-delayed-job-governance.md", "docs/adr/0002-feature-flag-governance.md"]
    * **Target Files:** ["docs/diagrams/tenant-erd.mmd"]
    * **Deliverables:** Revised ERD with new tables + relationships, notes on partitioning/archival for audit-heavy tables.
    * **Acceptance Criteria:**
        - All new entities include tenant_id, created_by metadata, and indexes.
        - Diagram callouts mention Stripe Connect IDs + encryption fields (tax info).
        - Version history appended in file comments referencing `I3.T1`.
    * **Dependencies:** `I2.T5`
    * **Parallelizable:** Yes

<!-- anchor: task-i3-t2 -->
* **Task 3.2:**
    * **Task ID:** `I3.T2`
    * **Description:** Create PlantUML sequence diagram for consignment payout authorization (Flow B) featuring Admin SPA, Identity, Feature Flags, Consignment module, Inventory, Payment layer, Stripe, Background Jobs, Reporting, Audit store.
    * **Agent Type Hint:** `DiagrammingAgent`
    * **Inputs:** Section 3 flow B narrative, updated ERD.
    * **Input Files**: ["docs/diagrams/tenant-erd.mmd", "docs/diagrams/component-overview.puml"]
    * **Target Files:** ["docs/diagrams/seq-consignment-payout.puml"]
    * **Deliverables:** Sequence diagram annotated with impersonation markers, payout states, job triggers.
    * **Acceptance Criteria:**
        - Diagram renders; includes notes about audit + reporting updates.
        - Highlights feature flag gating autopayout vs manual.
        - Links to Payment Provider interfaces for future providers.
    * **Dependencies:** `I3.T1`
    * **Parallelizable:** Yes

<!-- anchor: task-i3-t3 -->
* **Task 3.3:**
    * **Task ID:** `I3.T3`
    * **Description:** Implement Consignor + Consignment APIs (registration, batch intake, assignment to products), commission rule engine, payout batch persistence, and vendor portal endpoints referencing OpenAPI updates.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Updated ERD, consignment sequence diagram, ADRs.
    * **Input Files**: ["api/openapi-base.yaml", "api/openapi-catalog.yaml", "docs/diagrams/seq-consignment-payout.puml", "src/main/java/com/village/storefront/consignment/**"]
    * **Target Files:** ["src/main/java/com/village/storefront/consignment/*.java", "src/main/resources/db/migrations/0005_consignment.sql", "api/openapi-catalog.yaml", "api/openapi-base.yaml", "src/test/java/com/village/storefront/consignment/ConsignmentResourceTest.java"]
    * **Deliverables:** REST resources for consignors/items/batches, Panache entities, MapStruct mappers, commission calculators with unit tests, and OpenAPI schemas.
    * **Acceptance Criteria:**
        - Tests verify tenant isolation, commission math variations, and RLS enforcement.
        - Vendor portal endpoints require scoped tokens; OpenAPI documents OAuth scopes.
        - Payout batches emit domain events for Reporting subscription.
    * **Dependencies:** `I2.T4`, `I2.T5`
    * **Parallelizable:** No

<!-- anchor: task-i3-t4 -->
* **Task 3.4:**
    * **Task ID:** `I3.T4`
    * **Description:** Implement media upload service: presigned URL endpoint, upload session table, checksum validation, storage client abstraction (S3/R2), and admin UI integration stub; update OpenAPI spec.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Section 3 media pipeline notes, ERD updates, feature flag ADR.
    * **Input Files**: ["api/openapi-base.yaml", "docs/diagrams/tenant-erd.mmd", "src/main/java/com/village/storefront/media/**"]
    * **Target Files:** ["src/main/java/com/village/storefront/media/*.java", "src/main/resources/db/migrations/0006_media.sql", "api/openapi-base.yaml", "src/test/java/com/village/storefront/media/MediaResourceTest.java"]
    * **Deliverables:** REST endpoints for upload request + completion, storage adapter interface, configuration placeholders for Cloudflare R2 credentials, tests mocking storage client.
    * **Acceptance Criteria:**
        - Presigned URLs scoped per tenant + TTL; tests verify validation of file size + MIME types.
        - Upload completion enqueues background job referencing ADR payload schema.
        - OpenAPI spec documents handshake sequence + error responses.
    * **Dependencies:** `I2.T6`, `I2.T5`
    * **Parallelizable:** No

<!-- anchor: task-i3-t5 -->
* **Task 3.5:**
    * **Task ID:** `I3.T5`
    * **Description:** Author PlantUML sequence diagram for media pipeline (Flow C) from admin upload → presigned URL → background FFmpeg job → catalog media update → storefront notification.
    * **Agent Type Hint:** `DiagrammingAgent`
    * **Inputs:** Task `I3.T4`, Section 3 Flow C narrative.
    * **Input Files**: ["src/main/java/com/village/storefront/media/*.java", "docs/diagrams/component-overview.puml"]
    * **Target Files:** ["docs/diagrams/seq-media-processing.puml"]
    * **Deliverables:** Diagram with lanes for Merchant, Admin SPA, Identity, Feature Flags, Media Controller, R2, Job Worker, FFmpeg, Catalog, Reporting, Storefront.
    * **Acceptance Criteria:**
        - Highlights synchronous vs asynchronous operations, timeouts, retries.
        - Notes signed URL security and EXIF stripping requirement.
        - Referenced within README + plan manifest.
    * **Dependencies:** `I3.T4`
    * **Parallelizable:** Yes

<!-- anchor: task-i3-t6 -->
* **Task 3.6:**
    * **Task ID:** `I3.T6`
    * **Description:** Implement DelayedJob worker subsystem: job entity, leasing logic, worker CLI, FFmpeg invocation wrapper, metrics emission, and integration tests verifying retry/backoff/resume.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Job governance ADR, media task.
    * **Input Files**: ["docs/adr/0001-delayed-job-governance.md", "src/main/java/com/village/storefront/jobs/**", "tools/ffmpeg/"]
    * **Target Files:** ["src/main/java/com/village/storefront/jobs/*.java", "src/main/java/com/village/storefront/media/MediaProcessingJob.java", "src/test/java/com/village/storefront/jobs/JobWorkerTest.java", "tools/ffmpeg/version.md"]
    * **Deliverables:** Worker scheduler, queue CLI commands, FFmpeg wrapper verifying binary availability, metrics instrumentation hooking OpenTelemetry/Prometheus.
    * **Acceptance Criteria:**
        - Worker respects queue priorities + exponential backoff from ADR.
        - Job payload schema validated before execution; invalid payloads logged + moved to dead letter table.
        - Tests simulate FFmpeg failure + recovery; coverage >85% for worker module.
    * **Dependencies:** `I3.T4`
    * **Parallelizable:** No

<!-- anchor: task-i3-t7 -->
* **Task 3.7:**
    * **Task ID:** `I3.T7`
    * **Description:** Seed reporting projection service: domain event schema, projection tables (sales summary, consignment payouts, media processing metrics), ETL job templates, and OpenAPI endpoints for retrieving aggregated data.
    * **Agent Type Hint:** `BackendAgent`
    * **Inputs:** Domain events from tasks `I3.T3`/`I3.T4`, Section 3 Reporting notes.
    * **Input Files**: ["src/main/java/com/village/storefront/reporting/**", "docs/diagrams/seq-consignment-payout.puml", "src/main/resources/db/migrations/0007_reporting.sql"]
    * **Target Files:** ["src/main/java/com/village/storefront/reporting/*.java", "src/main/resources/db/migrations/0007_reporting.sql", "api/openapi-base.yaml", "src/test/java/com/village/storefront/reporting/ReportingResourceTest.java"]
    * **Deliverables:** Domain event envelope object, polling worker skeleton, reporting tables + DTOs, endpoints for payouts + media KPIs with SSE stub for dashboards.
    * **Acceptance Criteria:**
        - Projection worker consumes events idempotently; tests simulate duplicate events.
        - Aggregation tables include `data_freshness_timestamp` and indexes for filters.
        - API responses documented in OpenAPI with caching guidance.
    * **Dependencies:** `I3.T3`, `I3.T4`
    * **Parallelizable:** No

<!-- anchor: iteration-3-exit -->
* **Iteration Exit Criteria:**
  - Consignment APIs + vendor portal endpoints pass integration tests and reference updated specs.
  - Media upload service + worker stack validated through e2e test simulating upload + processing success failure cases.
  - Reporting service receives domain events from consignment and media modules with dashboards pulling sample data.
  - Updated diagrams committed + referenced inside README.

<!-- anchor: iteration-3-metrics -->
* **Iteration Metrics:**
  - Media pipeline throughput baseline recorded (image ≤5s, video job queue depth <100).
  - Consignment commission engine unit tests cover ≥90% of calculator logic.
  - Reporting ETL job queue wait time <5 minutes under test load.

<!-- anchor: iteration-3-risks -->
* **Iteration Risks & Mitigations:**
  - FFmpeg resource spikes could starve other pods—introduce Kubernetes resource limits + autoscale threshold metrics.
  - Consignor PII encryption must be validated with pgcrypto; plan stage secrets rotation for keys early.
  - Eventual payout automation depends on Stripe onboarding—coordinate with platform finance for test accounts.
<!-- anchor: iteration-3-followup -->
* **Iteration Follow-Up Actions:**
  - Schedule threat model session for media uploads + FFmpeg workers before production rollout.
  - Draft runbooks for consignment payout reconciliation referencing new reporting views.
