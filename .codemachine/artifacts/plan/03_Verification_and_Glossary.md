<!-- anchor: verification-and-integration-strategy -->
## 5. Verification and Integration Strategy

*   **Testing Levels:**
    - **Unit Tests:** Required for every module (catalog, consignment, checkout, payments, loyalty, media, feature flags). Aim for ≥85% coverage per module, focusing on business logic, tenant isolation, commission math, loyalty tiers, POS offline queue handlers.
    - **Integration Tests:** Use Quarkus + Testcontainers or native tests to verify Postgres RLS, job queues, Stripe/FFmpeg stubs, reporting aggregates, headless APIs. Run in both JVM + native profiles to detect GraalVM quirks.
    - **End-to-End Tests:** Playwright for storefront/checkout, Cypress for admin/POS and consignor portal, POS offline scenarios, Platform console impersonation flows. Schedule nightly plus per-release runs with screenshot diffs.
    - **Load/Performance Tests:** k6 suites exercising checkout, cart, catalog search, media uploads, consignment payouts; ensures latency budgets (<200ms API median, <2s storefront). Run pre-release and during significant schema/index changes.
    - **Security/Compliance Tests:** Automated privacy export/delete flows, impersonation reason enforcement, feature flag audits, Stripe webhook replay tests, rate-limit guard tests.
*   **CI/CD Expectations:**
    - GitHub Actions `ci.yml` (I1.T6) runs Spotless, Maven tests (JVM + native), npm lint/test, OpenAPI lint, PlantUML validation, coverage gating (JaCoCo ≥80%).
    - Dedicated workflows: `release.yml` (I5.T3) for blue/green deploy, `test_suite.yml` (I5.T5) for E2E/load/manifest checks, nightly scheduled jobs for long-running suites.
    - Build caching enabled for Maven + npm to keep runtime <12 minutes on CI, <20 minutes for full suite.
*   **Quality Gates:**
    - Spotless + ESLint + Stylelint must pass; Redwood-coded gating ensures no formatting drift.
    - JaCoCo coverage (backend) ≥80% overall, ≥75% per module; Vitest/Cypress coverage tracked for admin SPA and POS.
    - OpenAPI diff review: spec changes require docs/regression plan; PR template includes checklist referencing anchors.
    - Manifest validation: custom script ensures anchors referenced in `plan_manifest.json` exist in Markdown files.
*   **Artifact Validation:**
    - Diagrams (PlantUML/Mermaid) rendered via CI script; PNGs stored as artifacts for review.
    - ADRs linted for metadata completeness (status, context, decision, consequences).
    - OpenAPI spec validated (`spectral`, `swagger-cli`), clients regenerated for admin/headless to detect breaking changes.
    - Kustomize overlays validated via `kubectl apply --dry-run=client`; release workflow performs smoke tests using synthetic tenant before traffic shift.
    - Observability dashboards (Grafana JSON) validated with `grafana-toolkit`, Prometheus rules with `promtool check rules`.
    - Compliance exports hashed + verified; audit logs cross-checked to ensure traceability across impersonation flows.
*   **Integration Strategy:**
    - All modules integrate via CDI/service interfaces; rely on contract tests derived from OpenAPI to detect API drift.
    - Feature flags gate new behavior; release process includes canary tenants with Observability watchers before global rollout.
    - Background jobs orchestrated by priority queues; monitoring ensures new workloads (media, reporting, compliance) do not starve CRITICAL queues.
    - Platform console aggregates metrics from Prometheus + domain services; ensures ops/support visibility before GA.

<!-- anchor: glossary -->
## 6. Glossary

*   **ADR:** Architecture Decision Record capturing rationale/implications for structural choices (e.g., tenancy, checkout saga, consignment payouts).
*   **Caffeine Cache:** In-process caching layer for feature flags, tenant resolution, search caching, headless APIs.
*   **CDI:** Contexts and Dependency Injection in Quarkus enabling module boundaries.
*   **DelayedJob:** Database-backed queue pattern with CRITICAL→BULK priorities powering background workers.
*   **HLS:** HTTP Live Streaming output produced by media pipeline for video playback.
*   **Panache:** Quarkus ORM abstraction used for repositories/entities with tenant filters.
*   **PlatformCommand:** Audit entity capturing platform admin actions, impersonation operations, and compliance changes.
*   **POS Offline Queue:** Encrypted storage of in-store transactions processed without network connectivity, replayed through checkout once online.
*   **TenantContext:** Request-scoped bean with tenant_id/store+flag info used for all service calls.
*   **Feature Flag Governance:** Process + tooling ensuring each runtime toggle has owner, expiry, manifest entry, and monitoring (Task I5.T7).
*   **Manifest Anchor:** Unique identifier linking plan sections/tasks to manifest entries for downstream agent retrieval.
*   **Saga:** Long-running transaction coordination pattern used in checkout to orchestrate address validation, shipping, payments, loyalty, gift cards, refunds.
*   **Stripe Connect:** Payment platform enabling merchant payouts, platform fees, and consignment vendor payments.
