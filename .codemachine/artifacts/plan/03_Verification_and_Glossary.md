<!-- anchor: verification-and-integration-strategy -->
## 6. Verification and Integration Strategy

*   **Testing Levels:**
    - **Unit:** Required on every module (Panache repositories, services, Vue components) with mocks for external adapters; enforce factory tests for TenantContext, FeatureToggle, loyalty math, ledger updates, and background job handlers.
    - **Integration:** Use Quarkus dev services + docker-compose stack to run tenant-aware API flows (catalog CRUD, checkout saga, consignment balance, payment webhooks, media upload handshake) with real Postgres RLS + MinIO + Mock carriers; include Playwright/Cypress runs for storefront/admin/POS with multi-tenant fixtures.
    - **End-to-End:** Staging deployments run nightly synthetic journeys (guest checkout, staff fulfillment, consignment payout, loyalty redemption, POS offline queue flush, headless order) plus cross-tenant impersonation/resume; baseline snapshots stored for regression diffing.
*   **CI/CD:**
    - GitHub Actions pipeline triggered on PR + main; stages: lint (Spotless, ESLint, Stylelint), unit/integration (Maven + Vitest), OpenAPI lint (Spectral), security scans (OWASP dep check/Trivy), native build + Docker image, Cypress/Playwright e2e, SonarCloud gates, artifact signing, preview deploy to dev (k3s overlay) with smoke tests, manual approval for staging/prod, blue/green switch with health + e2e smoke gating, feature flag toggles captured in release notes.
*   **Code Quality Gates:**
    - Spotless + ESLint/Prettier must pass; JaCoCo coverage ≥80% per module; SonarCloud quality gate (no blocker/critical issues, maintainability rating ≥A); OWASP dep check no high/critical vulnerabilities; Quarkus `mvn verify -Dnative -pl modules/*` must pass before merge; TypeScript strict mode enforced; Storybook/axe accessibility snapshots must pass before UI merge.
*   **Artifact Validation:**
    - PlantUML/Mermaid diagrams validated via CI rendering step; OpenAPI spec diff + lint per PR; MyBatis migrations tested against throwaway Postgres with RLS verification script; DelayedJob payloads validated via JSON schema; docker-compose health check ensures local stack parity; documentation builds (MkDocs/pandoc) produce site verifying anchor references; release packages include diagram PNG exports + README version stamp.

<!-- anchor: glossary -->
## 7. Glossary

*   **TenantContext:** Request-scoped CDI bean capturing tenant_id, store metadata, feature flags; all services fetch scoped data through it to honor RLS.
*   **FeatureToggle Service:** Caffeine-backed resolver layering platform defaults, tenant overrides, and emergency kill switches; documented in `feature-flags.md`.
*   **DelayedJob:** Database queue pattern storing job payload JSON, priority, attempts; processed by dedicated worker pods for emails, media, payouts, reports.
*   **PaymentProvider:** Interface encapsulating payment processor operations (intent, capture, refund, payout, webhook handling); Stripe Connect is first implementation.
*   **Domain Event:** Immutable JSON row capturing aggregate change (ProductPublished, OrderPaid) used for projections, reports, and integrations.
*   **Consignment Ledger (Pending/Available):** Balance mechanism crediting pending amounts immediately after sale, moving to available post-refund window, supporting Stripe payouts.
*   **Media Pipeline:** Upload workflow (presigned URL → MinIO/R2 → validation → FFmpeg/Thumbnailator job) generating tenant-scoped variants served via signed URLs.
*   **POS Offline Queue:** Encrypted IndexedDB storage capturing transactions when offline, replayed sequentially via REST once connectivity restored.
*   **Headless API Client:** OAuth client credentials issued per tenant for catalog/cart/order scopes; rate limited via token bucket combining Caffeine + Postgres counters.
*   **Platform Command:** Audit-tracked action executed by platform admins (suspend store, toggle flag, run DR command), stored with reason/ticket reference and surfaced in audit exports.
