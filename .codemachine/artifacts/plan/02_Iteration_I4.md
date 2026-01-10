<!-- anchor: iteration-4-plan -->
### Iteration 4: Frontend Experiences, Media Pipeline, POS Foundations

*   **Iteration ID:** `I4`
*   **Goal:** Deliver tenant-branded storefront flows, Vue admin/POS shell, media upload pipeline, and foundational UX/internationalization scaffolding to enable full-stack demos.
*   **Prerequisites:** `I1`–`I3`
*   **Tasks:**

<!-- anchor: task-i4-t1 -->
*   **Task 4.1:**
    *   **Task ID:** `I4.T1`
    *   **Description:** Build end-to-end storefront pages (home, category, product, cart, checkout, account) using Qute + Tailwind + PrimeUI components; integrate with catalog/checkout APIs and feature flags.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Catalog & checkout endpoints, design tokens.
    *   **Input Files:** [`src/main/resources/templates/storefront`, `src/main/resources/templates/emails`, `src/main/resources/messages/messages.properties`]
    *   **Target Files:** [`src/main/resources/templates/storefront/home.html`, `category.html`, `product.html`, `cart.html`, `checkout.html`, `account.html`, `src/main/resources/templates/storefront/partials/*`, `src/main/resources/messages/messages.properties`, `messages_es.properties`]
    *   **Deliverables:** Responsive templates, translations placeholders (EN/ES), component partials (header/nav/footer, product card, filter, loyalty badge), hooking to REST APIs.
    *   **Acceptance Criteria:** Pages render sample data; Accept-Language toggles (en/es) update text via MessageBundle; CI screenshot tests (Percy) baseline captured; LCP <2s with seeded data.
    *   **Dependencies:** `I2.T6`, `I3.T1-T2`.
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t2 -->
*   **Task 4.2:**
    *   **Task ID:** `I4.T2`
    *   **Description:** Scaffold Vue 3 + Vite admin SPA (routing, layouts, PrimeVue theme, Pinia stores) plus Quinoa integration in Maven build; implement dashboard + catalog management views hitting backend APIs.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Admin requirements, API spec.
    *   **Input Files:** [`src/main/webui/src`, `api/storefront-admin-platform.yaml`, `modules/catalog/...`]
    *   **Target Files:** [`src/main/webui/src/main.ts`, `router/index.ts`, `stores/catalog.ts`, `views/Dashboard.vue`, `views/Products/List.vue`, `components/navigation/*`, `vite.config.ts`, `package.json`, `quinoa.yaml`]
    *   **Deliverables:** Admin SPA with authentication bootstrap, navigation, dashboard cards, product table/editor views, API client generator referencing OpenAPI, tests via Vitest/Cypress.
    *   **Acceptance Criteria:** SPA builds via Quinoa + Quarkus; login gating works with JWT; product grid supports pagination filters; lint/test scripts added to CI; docs describing admin dev workflow.
    *   **Dependencies:** `I1.T1`, `I1.T4`, `I2.T1-T3`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t3 -->
*   **Task 4.3:**
    *   **Task ID:** `I4.T3`
    *   **Description:** Implement POS module (Vue route + service worker + IndexedDB offline storage) with product search, cart, tender entry, offline queue, and device status bar; integrate with checkout APIs.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** POS requirements, iteration 3 checkout.
    *   **Input Files:** [`src/main/webui/src/views/POS`, `src/main/webui/src/stores/pos.ts`, `src/main/webui/src/service-worker.ts`]
    *   **Target Files:** [`src/main/webui/src/views/POS/Register.vue`, `POSHardwarePanel.vue`, `stores/pos.ts`, `workers/offlineQueue.ts`, `service-worker.ts`, `src/main/webui/src/locales/en.json`, `es.json`]
    *   **Deliverables:** Offline-ready POS view, hardware status components, offline queue logic (encryption stub), sync UI, tests covering offline scenario.
    *   **Acceptance Criteria:** PWA build passes; offline queue stores transactions, syncs when online via mock; UI meets accessibility requirements (large buttons, focus states); docs for pairing hardware.
    *   **Dependencies:** `I4.T2`, `I3.T1`, `I3.T4` (shipping).
    *   **Parallelizable:** No.

<!-- anchor: task-i4-t4 -->
*   **Task 4.4:**
    *   **Task ID:** `I4.T4`
    *   **Description:** Implement media upload pipeline (presigned URLs, validation, FFmpeg/Thumbnailator workers, metadata persistence) plus deployment diagram updates.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Media requirements, diagrams from `I3.T6`.
    *   **Input Files:** [`modules/media/src/main/java/...`, `worker/src/main/java/...`, `docs/diagrams/media-flow.mmd`]
    *   **Target Files:** [`modules/media/src/main/java/.../MediaController.java`, `MediaService.java`, `MediaRepository.java`, `worker/src/main/java/.../MediaWorker.java`, `modules/media/src/test/java/...`, `docs/diagrams/media-flow.mmd`, `docs/architecture/ops/deployment-architecture.md`]
    *   **Deliverables:** REST endpoints for upload request/complete, worker job definitions (IMAGE_VARIANT, VIDEO_TRANSCODE), integration with R2/MinIO, tests verifying checksum, size limits, job scheduling, updated diagram.
    *   **Acceptance Criteria:** Upload handshake works in dev; worker transcodes sample video via FFmpeg; R2 paths tenant-isolated; metrics/logging added; blueprint diagram refreshed.
    *   **Dependencies:** `I3.T6`, `I1.T7`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t5 -->
*   **Task 4.5:**
    *   **Task ID:** `I4.T5`
    *   **Description:** Implement Quarkus Mailer templates + localization (transactional emails for orders, consignment, payout) with environment domain filtering and tests.
    *   **Agent Type Hint:** `BackendAgent`
    *   **Inputs:** Email requirements, MessageBundle.
    *   **Input Files:** [`src/main/resources/templates/emails`, `modules/core-platform/src/main/java/.../MailService.java`, `src/main/resources/messages/*.properties`]
    *   **Target Files:** [`src/main/resources/templates/emails/order-confirmation.html`, `shipping-update.html`, `consignor-payout.html`, `modules/core-platform/src/main/java/.../MailService.java`, `modules/core-platform/src/test/java/.../MailServiceTest.java`]
    *   **Deliverables:** Email templates supporting EN/ES, service verifying domain filtering for non-prod, tests mocking SMTP.
    *   **Acceptance Criteria:** Emails render with theme tokens; non-prod filter prevents sending to real domains; integration test ensures mail queue captured; docs outline template editing process.
    *   **Dependencies:** `I4.T1`, `I3.T1`.
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t6 -->
*   **Task 4.6:**
    *   **Task ID:** `I4.T6`
    *   **Description:** Implement Admin reporting UI skeleton (charts, exports list) consuming aggregated metrics placeholders; integrate SSE notifications for job status.
    *   **Agent Type Hint:** `FrontendAgent`
    *   **Inputs:** Reporting requirements, Vue SPA.
    *   **Input Files:** [`src/main/webui/src/views/Reports`, `api/storefront-admin-platform.yaml`]
    *   **Target Files:** [`src/main/webui/src/views/Reports/Overview.vue`, `ReportJobList.vue`, `components/charts/*`, `stores/reports.ts`]
    *   **Deliverables:** Dashboard with KPI cards, chart placeholders (Chart.js), job history table, SSE listener hooking to backend.
    *   **Acceptance Criteria:** UI renders stub data from dev API; SSE updates job statuses; tokens reused for data freshness indicators; tests verifying chart accessibility.
    *   **Dependencies:** `I4.T2`, `I2.T7` (metrics), `I3.T7` (dev stack SSE).
    *   **Parallelizable:** Yes.

<!-- anchor: task-i4-t7 -->
*   **Task 4.7:**
    *   **Task ID:** `I4.T7`
    *   **Description:** Update Kubernetes deployment artifacts + Dockerfile to include Quarkus native storefront/admin assets, worker deployment scaling hints, cert-manager annotations, and FFmpeg binary packaging.
    *   **Agent Type Hint:** `InfraAgent`
    *   **Inputs:** Deployment requirements, Task I4.T4 outputs.
    *   **Input Files:** [`docker/Dockerfile`, `infra/k8s/base/deployment.yaml`, `infra/k8s/base/worker.yaml`, `docs/architecture/ops/deployment-architecture.md`]
    *   **Target Files:** [`docker/Dockerfile`, `infra/k8s/base/deployment.yaml`, `infra/k8s/base/worker.yaml`, `infra/k8s/base/ingress.yaml`, `infra/k8s/overlays/*`, `docs/architecture/ops/deployment-architecture.md`]
    *   **Deliverables:** Multi-stage Dockerfile bundling frontend assets + FFmpeg, Quarkus config for static resources, K8s manifests with env vars for TenantContext + queue envs, documentation on cert-manager + autoscaling.
    *   **Acceptance Criteria:** `mvn package -Dnative` image builds; `kubectl kustomize infra/k8s/overlays/dev` validates; docs highlight queue-specific pod settings; readiness/liveness probes updated.
    *   **Dependencies:** `I1.T1`, `I3.T4`, `I4.T4`.
    *   **Parallelizable:** Yes.
