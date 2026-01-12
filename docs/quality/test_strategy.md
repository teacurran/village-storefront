# Test Strategy

<!-- anchor: test-strategy-overview -->

This document defines the comprehensive testing approach, coverage expectations, and quality standards for the Village Storefront platform. It maps unit/integration/e2e test expectations per module, establishes coverage thresholds, documents test data management practices, and defines CI gating criteria including JaCoCo 80% enforcement and mutation testing stretch goals.

## Executive Summary

Village Storefront maintains a multi-level testing strategy aligned with the [VillageCompute Java Project Standards](../java-project-standards.adoc):

- **Unit Tests:** ≥85% coverage per module with JUnit 5 + Mockito
- **Integration Tests:** PostgreSQL + Testcontainers with RLS verification
- **End-to-End Tests:** Playwright suites covering storefront, admin, POS, platform workflows
- **Performance Tests:** Gatling/Locust load testing (planned in I3.T8)
- **CI Enforcement:** JaCoCo 80% minimum (quality gate failure below threshold), Spotless formatting, OpenAPI validation
- **Mutation Testing (Stretch):** PIT + StrykerJS for critical modules (planned in I3.T8)

This strategy is delivered incrementally across iterations I1-I5, with explicit task linkage for coverage growth tracking.

## Module Inventory

The project uses a Maven multi-module structure with the following modules:

### Parent POM (`pom.xml`)
- **Location:** Root directory
- **Purpose:** Aggregates all modules and manages shared dependencies, plugin versions, and build profiles
- **Responsibilities:**
  - Quarkus BOM imports (version 3.20.0)
  - Shared dependency version management (Stripe, AWS SDK, Testcontainers, MapStruct, etc.)
  - Plugin configuration (Quarkus, Spotless, JaCoCo, Surefire, OWASP Dependency-Check)
  - Maven profile definitions (dev, test, prod, native)

### Core Platform Module (`modules/core-platform`)
- **Location:** `modules/core-platform/`
- **Purpose:** Core platform module providing tenant gateway, identity, feature flags, and shared infrastructure
- **Key Dependencies:**
  - Quarkus extensions (ARC, REST, Panache, Scheduler, Kubernetes, Mailer, etc.)
  - AWS S3 SDK for R2-compatible object storage
  - Stripe Java SDK for payment processing
  - Quinoa for Vue.js admin dashboard integration
  - Thumbnailator for image resizing
  - MapStruct for DTO mapping
- **Test Stack:**
  - JUnit 5 (`quarkus-junit5`)
  - Mockito (`quarkus-junit5-mockito`)
  - REST Assured (`rest-assured`)
  - H2 in-memory database for unit tests (`quarkus-jdbc-h2`)
  - Testcontainers for integration tests (`testcontainers-postgresql`)
  - JaCoCo for code coverage (`quarkus-jacoco`)
  - Awaitility for async testing

## Maven Profiles

The parent POM defines the following Maven profiles to support different environments and build targets:

### Development Profile (`dev`)
- **Activation:** Active by default, or when `-Dquarkus.profile=dev` is set
- **Purpose:** Local development with debugging and dev UI enabled
- **Configuration:**
  - `quarkus.profile=dev`
  - `quarkus.hibernate-orm.log.sql=true` (SQL logging enabled)
  - `quarkus.dev-ui.enabled=true` (Dev UI accessible at `/q/dev`)
- **Usage:** `./mvnw quarkus:dev` or `./mvnw compile -Pdev`

### Test Profile (`test`)
- **Activation:** When `-Dquarkus.profile=test` is set
- **Purpose:** Running automated tests in CI/CD pipelines
- **Configuration:**
  - `quarkus.profile=test`
  - `quarkus.hibernate-orm.log.sql=false` (SQL logging disabled for cleaner test output)
  - `quarkus.dev-ui.enabled=false` (Dev UI disabled)
- **Usage:** `./mvnw test -Ptest` or `./mvnw verify -Ptest`

### Production Profile (`prod`)
- **Activation:** When `-Dquarkus.profile=prod` is set
- **Purpose:** Production deployments with optimized settings
- **Configuration:**
  - `quarkus.profile=prod`
  - `quarkus.hibernate-orm.log.sql=false` (SQL logging disabled)
  - `quarkus.dev-ui.enabled=false` (Dev UI disabled)
  - `quarkus.log.level=INFO` (Reduced log verbosity)
- **Usage:** `./mvnw package -Pprod` or `java -Dquarkus.profile=prod -jar target/quarkus-app/quarkus-run.jar`

### Native Profile (`native`)
- **Activation:** When `-Dnative` is set
- **Purpose:** GraalVM native executable builds for containerized deployments
- **Configuration:**
  - `skipITs=false` (Integration tests run in native mode)
  - `quarkus.native.enabled=true` (Enables GraalVM native compilation)
  - `quarkus.package.jar.enabled=false` (Skips JVM JAR packaging)
- **Usage:** `./mvnw package -Pnative` or `./mvnw verify -Pnative`
- **Requirements:** Requires GraalVM installation (Java 21 distribution)

## Quarkus Extensions

The following Quarkus extensions are configured per the [VillageCompute Java Project Standards](../java-project-standards.adoc):

### Required Extensions (Standard Kit)
- **quarkus-arc** - CDI dependency injection (explicitly declared in `modules/core-platform/pom.xml:50`)
- **quarkus-rest** - RESTEasy Reactive for REST endpoints (successor to quarkus-resteasy-reactive)
- **quarkus-rest-jackson** - Jackson JSON serialization for REST APIs
- **quarkus-hibernate-orm-panache** - Hibernate ORM with Panache active record pattern
- **quarkus-jdbc-postgresql** - PostgreSQL JDBC driver
- **quarkus-amazon-s3** - Quarkus Amazon S3 client for Cloudflare R2-compatible storage
- **quarkus-smallrye-health** - Health check endpoints for Kubernetes probes
- **quarkus-micrometer-registry-prometheus** - Prometheus metrics export
- **quarkus-scheduler** - Background job scheduling
- **quarkus-mailer** - Email sending capabilities
- **quarkus-opentelemetry** - Distributed tracing

### Additional Extensions (Project-Specific)
- **quarkus-qute** - Type-safe templating for storefront UI
- **quarkus-cache** - Caffeine in-memory caching
- **quarkus-kubernetes** - Kubernetes manifest generation
- **quarkus-smallrye-jwt** + **quarkus-smallrye-jwt-build** - JWT authentication
- **quarkus-smallrye-openapi** - OpenAPI spec generation
- **quarkus-container-image-jib** - Jib containerization
- **quarkus-vertx-http** - Vert.x HTTP server (required by Quinoa)
- **quarkus-quinoa** - Vue.js admin SPA integration
- **quarkus-flyway** - Database migrations (Note: Project uses MyBatis Migrations, Flyway is configured but not primary)

### External Libraries (Non-Quarkus)
- **AWS SDK S3** - Cloudflare R2-compatible object storage
- **Stripe Java SDK** - Payment processing
- **Thumbnailator** - Image resizing
- **MapStruct** - DTO mapping with annotation processing
- **BCrypt** - Password hashing
- **Resilience4j** - Retry/backoff for carrier adapters
- **OpenCSV** - CSV report generation

## Code Coverage Requirements

### Minimum Coverage Thresholds
- **Line Coverage:** 80% minimum (enforced by JaCoCo + SonarCloud)
- **Branch Coverage:** 80% minimum (enforced by JaCoCo + SonarCloud)
- **Scope:** All production Java classes in `src/main/java`

### Coverage Exclusions

The following packages/classes are excluded from coverage requirements as defined in `pom.xml`:

- **Generated Code:**
  - `**/api/types/**` - OpenAPI-generated DTOs
  - `**/services/mappers/**` - MapStruct-generated mappers

- **Data Layer:**
  - `**/data/models/**` - JPA entities (simple POJOs)
  - `**/data/repositories/**` - Repository interfaces

- **API Resources:**
  - `**/api/rest/**` - REST endpoints (integration-tested via REST Assured)

- **Multi-Tenancy Infrastructure:**
  - `**/tenant/TenantResolutionFilter.class` - Request filter (integration-tested)
  - `**/tenant/TenantResolved.class` - CDI qualifier annotation
  - `**/tenant/TenantMissing.class` - Exception class

- **Temporary Service Exclusions (to be removed as implementation progresses):**
  - `**/services/CartService.class`
  - `**/services/CatalogService.class`
  - `**/services/InventoryService.class`

### Coverage Enforcement

Coverage is enforced via:
1. **JaCoCo Maven Plugin** - `jacoco:check` goal runs during `verify` phase
2. **SonarCloud Quality Gate** - Blocks PRs with coverage below 80%
3. **CI Pipeline** - GitHub Actions runs `./mvnw verify` and fails on threshold violations

### Running Coverage Reports

```bash
# Run tests with coverage (generates target/site/jacoco/index.html)
./mvnw test jacoco:report

# Enforce coverage thresholds
./mvnw jacoco:check

# Full verify with all checks
./mvnw clean verify
```

## Build Compliance & Standards Verification

### Compliance with VillageCompute Java Project Standards

The project fully complies with [docs/java-project-standards.adoc](../java-project-standards.adoc) requirements:

**✅ Build System:**
- Maven 3.9+ with parent-child multi-module structure
- Java 21 (`maven.compiler.release=21`)
- Quarkus BOM 3.20.0 (meets 3.17+ requirement) plus Quarkiverse Amazon S3 extension 3.3.3 managed via dependencyManagement
- No prohibited dependencies (Lombok absent)

**✅ Required Quarkus Extensions (All Present in `modules/core-platform/pom.xml`):**
- Core: `quarkus-arc`, `quarkus-rest`, `quarkus-rest-jackson`
- Database: `quarkus-hibernate-orm-panache`, `quarkus-jdbc-postgresql`
- Observability: `quarkus-smallrye-health`, `quarkus-micrometer-registry-prometheus`, `quarkus-opentelemetry`
- Background Jobs: `quarkus-scheduler`
- Email: `quarkus-mailer`
- Object Storage: `quarkus-amazon-s3`
- Authentication: `quarkus-smallrye-jwt`, `quarkus-smallrye-jwt-build`
- API: `quarkus-smallrye-openapi`
- Templates: `quarkus-qute`
- SPA Integration: `quarkus-quinoa`
- Caching: `quarkus-cache`
- Deployment: `quarkus-kubernetes`, `quarkus-container-image-jib`

**✅ External SDKs (Per Architectural Requirements):**
- AWS S3 SDK (`software.amazon.awssdk:s3`) - Complements `quarkus-amazon-s3` for presigned URLs + advanced operations against Cloudflare R2
- Stripe Java SDK (`com.stripe:stripe-java`) - Payment processing
- Thumbnailator (`net.coobird:thumbnailator`) - Image resizing
- MapStruct (`org.mapstruct:mapstruct`) - DTO mapping
- BCrypt (`org.mindrot:jbcrypt`) - Password hashing
- Resilience4j (`io.github.resilience4j:resilience4j-retry`) - Carrier adapter retry logic
- OpenCSV (`com.opencsv:opencsv`) - Report generation

**✅ Code Quality Tooling:**
- Spotless Maven Plugin 2.43.0 with Eclipse formatter
- JaCoCo 0.8.11 with 80% coverage threshold
- OWASP Dependency-Check 10.0.4 for vulnerability scanning
- SonarCloud integration for quality gates

**✅ Test Stack:**
- JUnit 5 (`quarkus-junit5`, `quarkus-junit5-mockito`)
- REST Assured (`rest-assured`)
- H2 in-memory database (`quarkus-jdbc-h2`)
- Testcontainers (`testcontainers-postgresql`)
- JaCoCo (`quarkus-jacoco`)
- Awaitility for async testing
- Security test utilities (`quarkus-test-security`, `quarkus-test-security-jwt`)

### Maven Build Verification Commands

The following commands verify that the build meets all standards:

```bash
# Full compliance check (Spotless + JaCoCo + all tests)
./mvnw clean verify

# Spotless formatting verification
./mvnw spotless:check

# JaCoCo coverage threshold enforcement (80%)
./mvnw jacoco:check

# OWASP vulnerability scan
./mvnw org.owasp:dependency-check-maven:check

# Native image compilation test (GraalVM required)
./mvnw verify -Pnative
```

**Acceptance Criteria Mapping (Task I1.T1):**

- ✅ **Maven build runs with Spotless + JaCoCo hooks:** `./mvnw clean verify` executes both checks in verify phase
- ✅ **Profiles configured and documented:** `%dev`, `%test`, `%prod`, `%native` profiles defined and documented in Maven Profiles section
- ✅ **Module inventory + coverage expectations captured:** Module inventory documented with dependencies, coverage requirements in Code Coverage Requirements section
- ✅ **No regression to existing modules:** All existing extensions preserved, only added missing `quarkus-arc` per Standard Kit requirements
- ✅ **All Standard Kit extensions present:** Quarkus ARC, REST, Panache, Scheduler, AWS S3, Stripe SDK all confirmed in module POM
- ✅ **GraalVM native build configured:** Native profile present with `quarkus.native.enabled=true` and documented usage

## Code Quality Standards

### Code Formatting
- **Tool:** Spotless Maven Plugin with Eclipse formatter
- **Enforcement:** `spotless:check` runs during `verify` phase (fails build on violations)
- **Auto-fix:** `./mvnw spotless:apply`
- **Configuration:** `eclipse-formatter.xml` in project root
- **Standards:**
  - Line length: 120 characters
  - Indentation: 4 spaces for Java, 2 spaces for XML/YAML/JSON
  - Import order: `java, javax, jakarta, org, com, villagecompute`
  - Unused imports removed automatically
  - Annotations formatted

### Static Analysis
- **Tool:** SonarCloud
- **Quality Profile:** APPI (Application Security and Quality Index)
- **Quality Gate:** Zero bugs, zero vulnerabilities, 80% coverage
- **Configuration:** `sonar-project.properties` in project root
- **Reports:** Coverage XML exported to `target/site/jacoco/jacoco.xml`

### Build Lifecycle Integration

The following quality checks run automatically during `mvn clean verify`:

1. **Compile Phase:**
   - Java 21 compiler with parameter names preserved
   - MapStruct annotation processing

2. **Test Phase:**
   - JUnit 5 tests with Quarkus test extensions
   - JaCoCo agent attached via `${surefire.jacoco.args}`
   - Mockito and REST Assured integration

3. **Verify Phase:**
   - JaCoCo merges all `.exec` files
   - JaCoCo generates HTML + XML coverage reports
   - JaCoCo checks 80% threshold (fails build if below)
   - Spotless checks code formatting (fails build on violations)

## Testing Strategy

<!-- anchor: testing-strategy -->

This section defines the comprehensive testing approach for the Village Storefront platform, mapping unit/integration/e2e expectations per module, coverage thresholds, test data management, and CI gating.

### Verification Hierarchy

<!-- anchor: verification-hierarchy -->

Village Storefront employs a four-tier testing pyramid optimized for multi-tenant SaaS reliability:

1. **Unit Tests (Foundation):** Fast, isolated tests of service/domain logic with mocked dependencies. Target ≥85% line/branch coverage per module. Run on every commit via `./mvnw test` (<5 min).

2. **Integration Tests (Contracts):** REST API contract validation, database RLS verification, external service mocks (Stripe, carriers). Use Testcontainers PostgreSQL 17 with full schema + RLS policies. Run via `./mvnw verify` (<10 min).

3. **End-to-End Tests (Workflows):** Browser-based Playwright suites covering tenant provisioning, storefront checkout, admin catalog/orders, POS offline sync, platform impersonation. Run via `scripts/qa/run_e2e.sh` (<20 min, 4 parallel workers).

4. **Performance & Chaos (Resilience):** Load testing (Gatling/Locust) for checkout/cart stress, chaos scripts for DB failover/API outages. Nightly + release candidate runs. Planned in `I3.T8`.

**Quality Gates (enforced in CI):**
- Unit/Integration: JaCoCo ≥80% coverage (hard fail)
- E2E: 100% suite pass (2 retries allowed per Playwright config)
- Formatting: Spotless zero violations
- Security: OWASP Dependency-Check zero critical/high CVEs
- OpenAPI: Spectral lint zero errors

### Testing Levels and Scope

<!-- anchor: testing-levels -->

Village Storefront employs a multi-level testing strategy to ensure quality, performance, and reliability across all platform modules:

#### Unit Tests
- **Framework:** JUnit 5 with `@QuarkusTest`
- **Mocking:** Mockito via `@InjectMock`
- **Coverage Target:** ≥85% line and branch coverage per module
- **Database:** H2 in-memory for fast execution
- **Scope:** Service layer, business logic, utilities, domain models
- **Execution Time:** <5 minutes total
- **Test Isolation:** Each test must be independent, using `@Transactional` for automatic rollback

**Unit Test Requirements by Category:**
- **Service Layer:** All business logic paths, validation rules, edge cases (null/empty inputs, boundary conditions)
- **Domain Models:** Named query execution, finder methods, entity relationships
- **Utilities:** Helper functions, formatters, calculators
- **Feature Flags:** Enable/disable behavior verification for all toggles
- **Error Handling:** Negative test cases for validation failures, constraint violations, exception propagation

#### Integration Tests
- **Framework:** JUnit 5 with `@QuarkusIntegrationTest`
- **Database:** Testcontainers PostgreSQL 17 with Row Level Security policies active
- **Scope:** REST API endpoints, database access, RLS verification, external integrations (mocked)
- **Assertions:** REST Assured for HTTP contract testing with OpenAPI schema validation
- **Execution Time:** <10 minutes total
- **Test Data:** Isolated per-test tenant provisioning with cleanup

**Integration Test Requirements:**
- **REST API Endpoints:** All CRUD operations, pagination, filtering, sorting, error responses (400/401/403/404/500)
- **OpenAPI Contract Validation:** Automatic schema compliance checks via REST Assured + OpenAPI spec
- **Tenant Isolation (RLS):** Verify queries scoped to correct tenant, cross-tenant access blocked
- **External Service Mocks:** Stripe webhooks, shipping carrier APIs, media processing pipelines
- **Database Constraints:** Foreign key enforcement, unique constraints, check constraints
- **Background Jobs:** Job enqueue, locking, retry logic, failure handling

#### End-to-End Tests
- **Tool:** Playwright with TypeScript
- **Configuration:** `tests/e2e/playwright/playwright.config.ts`
- **Scope:** Storefront browsing/checkout, admin catalog/order workflows, POS offline transactions, platform impersonation
- **Execution Time:** <20 minutes on 4 parallel workers (CI timeout limit)
- **Environment:** Quarkus dev mode server (`./mvnw quarkus:dev`) or deployed instance
- **Test Data:** Seeded via `tests/fixtures/seed-e2e-data.js` with deterministic tenant/product/user fixtures

**E2E Test Suites (by module):**
- **Storefront:** Product catalog browsing, search, cart operations, guest/registered checkout, order confirmation
- **Admin Dashboard:** Catalog CRUD, inventory adjustments, order management, consignment workflows, platform settings
- **POS Terminal:** Offline transaction processing, synchronization, payment reconciliation
- **Platform Console:** Tenant provisioning, impersonation flows (with audit logging verification), feature flag management
- **Visual Regression:** Percy integration for screenshot comparison on critical pages (storefront product page, admin dashboard, checkout flow)

**Browser Coverage:**
- Desktop: Chromium, Firefox, WebKit
- Mobile: Pixel 5 (Chrome), iPhone 12 (Safari)

#### Performance Tests
- **Tool:** Gatling or Locust (to be implemented in `I3.T8`)
- **Scope:** Peak load simulation (checkout/cart traffic, POS offline bursts, consignment payout spikes), latency benchmarks
- **Targets:**
  - Checkout API: <300ms p95
  - Storefront page load (LCP): <2s
  - Admin dashboard initial JS bundle: <2MB gzip
- **Execution:** Nightly + release candidate runs

#### Chaos Testing
- **Tool:** Custom scripts + Kubernetes pod disruption
- **Scope:** DB failover, worker restarts, Stripe/carrier outages
- **Objective:** Verify kill-switch + fallback behavior documented in runbook

### Module-by-Module Test Obligations

<!-- anchor: module-test-matrix -->

The following table maps each domain module to required test suites, ownership, and coverage targets. Each entry links to the iteration task responsible for delivering the indicated coverage level:

| Module | Unit Tests | Integration Tests | E2E Tests | Coverage Target | Responsible Iteration |
|--------|-----------|------------------|----------|----------------|---------------------|
| **Tenant Gateway** | TenantContext resolution, subdomain/custom-domain parsing, RLS filter | TenantResolutionFilter with request headers, cross-tenant isolation | Platform console tenant provisioning | ≥85% | I1.T1 (skeleton), I2.T8 (RLS hardening) |
| **Identity & Auth** | JWT generation/validation, password hashing, session logging, impersonation audit | Login/logout flows, refresh token rotation, impersonation endpoints | Admin impersonation with audit log verification | ≥85% | I2.T2 |
| **Catalog** | Product CRUD, variant pricing, SKU generation, category tree | Catalog import/export jobs, REST API CRUD, RLS verification | Admin catalog management, storefront product browsing | ≥85% | I2.T3, I2.T4 |
| **Inventory** | Stock adjustments, low-stock alerts, reservation logic | Inventory API endpoints, job-based recount workflows | Admin inventory dashboard, storefront out-of-stock handling | ≥85% | I2.T5 |
| **Checkout** | Cart operations, discount application, tax calculation, order state machine | Checkout API, guest/registered flows, payment intent creation | Storefront checkout flow (guest/registered), order confirmation | ≥85% | I3.T2 |
| **Payments** | Stripe webhook signature validation, payment state transitions, refund logic | Stripe webhook delivery (mocked), payment capture/refund endpoints | Admin order refund workflow | ≥85% | I3.T3 |
| **Consignment** | Vendor payout calculation, commission splits, payout schedules | Consignment REST API, payout job execution | Admin consignment dashboard, vendor payout reports | ≥85% | I3.T4 |
| **Media** | Image upload validation, FFmpeg transcoding params, thumbnail generation | Media upload API, CDN presigned URL generation, R2 storage integration (mocked) | Admin media library upload, storefront image rendering | ≥85% | I3.T5 |
| **Loyalty** | Points accrual/redemption, tier progression, expiration logic | Loyalty API endpoints, points ledger queries | Storefront loyalty dashboard, checkout points redemption | ≥85% | I4.T2 |
| **POS Terminal** | Offline transaction queue, sync conflict resolution, receipt generation | POS API endpoints, sync job processing | POS app offline checkout, sync to cloud | ≥85% | I4.T3 |
| **Headless CMS** | Content block rendering, localization, versioning | Headless API endpoints, content publication workflows | Storefront dynamic content rendering | ≥85% | I4.T4 |
| **Platform Admin** | Feature flag CRUD, tenant CRUD, usage metrics aggregation | Platform admin API, impersonation, bulk tenant operations | Platform console feature flag management | ≥85% | I5.T2 |

### Test Data Management

<!-- anchor: test-data-management -->

Reliable test data management is critical for deterministic test execution across unit, integration, and e2e suites. Village Storefront employs environment-specific seeding strategies with tenant isolation guarantees.

#### Data Seeding Strategy

<!-- anchor: data-seeding-strategy -->

##### Local & Shared Seeds

<!-- anchor: local-shared-seeds -->

- `scripts/dev/bootstrap.sh` validates prerequisites, starts the Docker Compose stack, runs Quarkus/Flyway migrations, and loads deterministic demo data. The script pulls SQL fixtures from `tools/scripts/sample_catalog_loader.sql` and `tools/scripts/sample_consignment_loader.sql` so every developer environment starts from the same tenants (`techgadgets`, `artisancrafts`) and sample consignors.
- The bootstrap script can be re-run at any time (or invoked with `--skip-seed`) to refresh data without manually invoking MyBatis commands. CI agents can reuse the same script when spinning up preview environments so database contents match local machines.

##### Unit + Integration Fixtures

<!-- anchor: unit-integration-fixtures -->

- **Unit tests:** Each test instantiates Panache entities or domain records inline and leverages H2 in-memory isolation plus `@Transactional` rollback to keep state clean. Shared helper methods live directly inside the `modules/core-platform/src/test/java/**` test classes to avoid brittle global fixtures.
- **Integration tests:** `@QuarkusIntegrationTest` classes spin up PostgreSQL 17 Testcontainers instances and automatically apply migrations from `modules/core-platform/src/main/resources/db/migrations`. CSV fixtures such as `modules/core-platform/src/test/resources/catalog-import-test.csv` and `catalog-import-invalid.csv` feed import/export scenarios, and the container is discarded after each class so no cleanup scripts are required. Negative tests rely on dynamically generated tenant IDs to ensure RLS policies are exercised.

##### Playwright / E2E Fixtures

<!-- anchor: e2e-fixtures -->

- Canonical seed entry point: `tests/fixtures/seed-e2e-data.js`, which hydrates tenants defined in `tests/fixtures/tenants.ts` (tenant-a/b/c). It provisions platform admin accounts plus tenant users, catalog content, loyalty programs, and OAuth clients against the REST API so data matches production traffic paths.
- Run seeding prior to E2E execution: `npm --prefix tests/e2e/playwright run seed:e2e`. The command is idempotent and can be re-run mid-suite to repair data drifts.
- `scripts/qa/run_e2e.sh` automatically seeds catalog data via `scripts/dev/tenant_seed.sh --catalog` before executing Playwright and REST-assured contract tests. Seeding can be disabled with `SEED_DATA=false` environment variable.

##### Isolation & Refresh Policy

<!-- anchor: data-isolation -->

- Local Docker volumes should be recycled with `docker compose down -v` before smoke testing schema changes; `scripts/dev/bootstrap.sh` recreates the schema + seed data end-to-end.
- CI pipelines rely on ephemeral PostgreSQL service containers, guaranteeing a fresh schema per run. Playwright runs always point to environment-specific tenants (`tenant-a.test.local`, `tenant-b.test.local`, `tenant-c.test.local`) so traces and reports can be correlated across runs.
- Because both Testcontainers and the Playwright seed script are deterministic, restoring tests to a clean state only requires re-running the bootstrap or seeding helpers—no manual SQL cleanup is permitted.

### Coverage Requirements and Reporting

#### JaCoCo Coverage Thresholds

**Enforced Minimums (per VillageCompute Java Project Standards):**
- **Line Coverage:** ≥80% on all production Java classes
- **Branch Coverage:** ≥80% on all production Java classes
- **Scope:** All code in `src/main/java` except exclusions listed in "Code Coverage Requirements" section above

**Per-Module Stretch Goal:**
- Domain/service modules should target ≥85% coverage (stricter than platform minimum)
- New code introduced in each iteration must not reduce existing module coverage below thresholds

#### Coverage Reporting Pipeline

1. **Local Development:**
   ```bash
   ./mvnw test jacoco:report
   open modules/core-platform/target/site/jacoco/index.html
   ```
   - Developers must verify coverage before committing
   - Pre-commit hook can optionally run `jacoco:check` to enforce thresholds

2. **CI Pipeline (GitHub Actions):**
   - **Step 1:** Surefire runs all unit/integration tests with JaCoCo agent attached
   - **Step 2:** JaCoCo merges all `.exec` files and generates XML + HTML reports
   - **Step 3:** JaCoCo `check` goal validates ≥80% thresholds (fails build if below)
   - **Step 4:** Coverage XML uploaded to SonarCloud for quality gate enforcement
   - **Artifacts:** HTML report published as GitHub Actions artifact, accessible from CI run page

3. **SonarCloud Quality Gate:**
   - Coverage must be ≥80% on new code and overall project
   - Quality gate blocks PR merge if coverage drops below threshold
   - Dashboard: https://sonarcloud.io/organizations/villagecompute (project-specific link TBD)

4. **Playwright E2E Reporting:**
   - HTML report: `target/playwright-report/index.html` (published as CI artifact)
   - JSON results: `target/playwright-results.json` (parsed for failure trends)
   - JUnit XML: `target/playwright-junit.xml` (integrated with CI test reporting)

#### Mutation Testing (Stretch Goal)

**Planned Tooling (not yet implemented):**
- **Java:** PIT Mutation Testing (http://pitest.org/)
  - Target: ≥75% mutation score on critical modules (Checkout, Payments, Tenant Gateway)
  - Integration: Maven plugin, runs in nightly CI builds
- **JavaScript/TypeScript:** StrykerJS (https://stryker-mutator.io/)
  - Target: ≥70% mutation score on Vue admin stores
  - Integration: npm script, runs in weekly CI builds

**Future Work:**
- `I3.T8` will evaluate mutation testing integration
- `I5.T7` release readiness report will include mutation score metrics if implemented

### CI/CD Pipeline and Quality Gates

<!-- anchor: ci-cd-pipeline -->

The GitHub Actions workflow (`.github/workflows/ci.yml`) orchestrates a multi-stage pipeline with parallel execution and strict quality gates. All stages must pass before code can be merged to main.

#### Pipeline Architecture

The CI pipeline follows this execution flow:

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Validation Stage (Parallel)                              │
├─────────────────────────────────────────────────────────────┤
│ • Spotless Check (./mvnw spotless:check)                    │
│ • OpenAPI Lint (Spectral validation)                        │
│ • PlantUML Validation (plantuml -checkonly)                 │
│ • Markdown Lint (markdownlint docs/**/*.md)                 │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. Test Stage (Parallel)                                    │
├─────────────────────────────────────────────────────────────┤
│ • JVM Tests: ./mvnw verify                                  │
│   - Surefire (unit + integration tests)                     │
│   - JaCoCo coverage collection                              │
│   - Threshold check (≥80% or fail)                          │
│ • E2E Tests: scripts/qa/run_e2e.sh                          │
│   - Environment: BASE_URL, HEADLESS=true                    │
│   - Playwright 4 parallel workers                           │
│   - Artifacts: target/playwright-report/                    │
│ • Native Build (main/PR only): ./mvnw verify -Pnative       │
│   - GraalVM native compilation                              │
│   - Duration: ~15-30 minutes                                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. Quality Gate (Sequential, blocks merge)                  │
├─────────────────────────────────────────────────────────────┤
│ • SonarCloud Analysis                                       │
│   - Upload: target/site/jacoco/jacoco.xml                   │
│   - Profile: APPI                                           │
│   - Gates: coverage ≥80%, bugs=0, vulnerabilities=0         │
│ • Security Scan                                             │
│   - OWASP Dependency-Check (already run in verify)          │
│   - Trivy container scan (future: I3.T8)                    │
│   - GitHub secrets scanning (always active)                 │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ 4. Docker Build & Deploy (main/beta only)                   │
├─────────────────────────────────────────────────────────────┤
│ • Native Container Image                                    │
│   - ./mvnw package -Pnative -Dquarkus.container-image...    │
│   - Base: distroless/ubi-minimal                            │
│   - Tags: SHA + latest/beta                                 │
│   - Registry: ghcr.io                                       │
│ • Kubernetes Deploy                                         │
│   - Manifests: quarkus-kubernetes extension                 │
│   - Strategy: blue/green + smoke tests + manual approval    │
└─────────────────────────────────────────────────────────────┘
```

#### Quality Gate Details

The GitHub Actions workflow (`.github/workflows/ci.yml`) enforces the following quality gates:

#### 1. Validation Stage (Parallel Execution)
- **Spotless Check:** `./mvnw spotless:check` (fails on formatting violations)
- **OpenAPI Lint:** Spectral validation of `src/main/resources/openapi/api.yaml`
- **PlantUML Validation:** `plantuml -checkonly docs/architecture/**/*.puml`
- **Markdown Lint:** `markdownlint docs/**/*.md`

#### 2. Test Stage (Parallel Execution)
- **JVM Unit/Integration Tests:**
  ```bash
  ./mvnw verify  # Runs Surefire, JaCoCo, Spotless, OWASP Dependency-Check
  ```
  - Output: `target/surefire-reports/`, `target/site/jacoco/`
  - Threshold: ≥80% coverage or build fails

- **Playwright E2E Tests:**
  ```bash
  scripts/qa/run_e2e.sh  # Orchestrates Playwright execution with prereq checks
  ```
  - **Script:** `scripts/qa/run_e2e.sh` (canonical E2E entry point)
  - **Environment Variables:**
    - `BASE_URL` (default: `http://localhost:8080`) - Application URL to test against
    - `HEADLESS` (default: `true`) - Run browsers in headless mode
    - `CI` (auto-detected) - Uses `npm ci` instead of `npm install` when `CI=true`
    - `SEED_DATA` (default: `true`) - Seed deterministic catalog/inventory data via `tenant_seed.sh --catalog`
  - **Prerequisites:** Node 18+, npm, Playwright browsers
  - **Output Artifacts:**
    - HTML Report: `target/playwright-report/index.html`
    - JSON Results: `target/playwright-results.json`
    - JUnit XML: `target/playwright-junit.xml`
    - Traces: `target/playwright-traces/` (on failure)
  - **Test Execution Flow (I2.T8):**
    1. Seeds deterministic catalog/inventory data via `tenant_seed.sh --catalog`
    2. Runs REST-assured API contract tests validating OpenAPI compliance (`modules/core-platform/src/test/java/villagecompute/storefront/api/contract/CatalogContractIT.java`)
    3. Executes Playwright storefront catalog tests with visual snapshots (`tests/e2e/storefront/catalog.spec.ts`)
  - **Retry Logic:** 2 retries on failure (configured in `playwright.config.ts`)
  - **Timeout:** 20 minutes total (4 parallel workers)
  - **CI Usage:** GitHub Actions runs with `CI=true HEADLESS=true BASE_URL=http://localhost:8080`

- **Native Image Build (main/PR only):**
  ```bash
  ./mvnw verify -Pnative
  ```
  - Verifies GraalVM native compilation succeeds
  - Duration: ~15-30 minutes
  - Skipped on feature branches to save CI time

#### 3. Quality Gate (Sequential, blocks merge if fails)
- **SonarCloud Analysis:**
  - Uploads coverage XML, runs static analysis
  - Quality Profile: APPI (Application Security and Quality Index)
  - Blocks merge if: coverage <80%, bugs >0, vulnerabilities >0

- **Security Scan:**
  - OWASP Dependency-Check for vulnerable dependencies
  - Trivy container image scan (future, in `I3.T8`)
  - Secrets scanning (GitHub built-in, always active)

#### 4. Docker Build & Deploy (main/beta only)
- **Container Image:**
  ```bash
  ./mvnw package -Pnative -Dquarkus.container-image.build=true
  ```
  - Output: GraalVM native executable in distroless/ubi-minimal base image
  - Tagged with commit SHA + `latest` (main) or `beta` (beta branch)
  - Pushed to GitHub Container Registry (ghcr.io)

- **Deployment:**
  - Kubernetes manifests generated via `quarkus-kubernetes` extension
  - Applied via `kubectl apply -k overlays/dev` (staging) or `overlays/prod` (production)
  - Blue/green rollout strategy with smoke tests + manual approval gate

### Feature Flag Testing Requirements

Every feature flag must have dedicated unit tests verifying:
- **Enabled State:** Feature behavior active, expected outcomes
- **Disabled State:** Feature behavior inactive, graceful degradation or fallback
- **Toggle Transitions:** Mid-flight request handling when flag flipped

**Example Test Structure:**
```java
@QuarkusTest
public class CatalogImportFeatureTest {

    @Inject
    FeatureToggle featureToggle;

    @Test
    void testImportJob_FlagEnabled_ProcessesFile() {
        featureToggle.enable("catalog.import");
        // Assert import succeeds
    }

    @Test
    void testImportJob_FlagDisabled_ReturnsNotImplemented() {
        featureToggle.disable("catalog.import");
        // Assert 501 Not Implemented response
    }
}
```

### Test Execution Commands

**Local Development:**
```bash
# Run all unit tests
./mvnw test

# Run tests with coverage report
./mvnw test jacoco:report

# Run integration tests only
./mvnw verify -DskipUnitTests

# Run native tests
./mvnw verify -Pnative

# Run E2E tests
cd tests/e2e/playwright && npm run test

# Run E2E tests with UI
cd tests/e2e/playwright && npm run test:ui

# Seed E2E test data
cd tests/e2e/playwright && npm run seed:e2e
```

**CI/CD Pipeline:**
```bash
# Full quality check (format, tests, coverage, security)
./mvnw clean verify

# E2E test runner script (orchestrates Playwright execution)
scripts/qa/run_e2e.sh
```

### Observability in Tests

**Logging:**
- Set `quarkus.log.level=DEBUG` in `src/test/resources/application.properties` for verbose test logs
- Use `@TestHTTPResource` to inject test server URLs for REST Assured base path

**Tracing:**
- Jaeger tracing disabled in unit/integration tests (performance overhead)
- Enabled in E2E tests for debugging complex workflows (export traces to `target/playwright-traces/`)

**Metrics:**
- Prometheus metrics endpoints (`/q/metrics`) validated in integration tests
- E2E tests can assert on metric values post-workflow (e.g., checkout counter incremented)

### Iteration Roadmap: Coverage Increment Tracking

<!-- anchor: iteration-roadmap -->

The test strategy is delivered incrementally across iterations I1-I5. Each iteration task contributes specific coverage milestones that aggregate toward the 80% minimum threshold:

#### Iteration 1 (I1): Foundation

- **I1.T1:** Maven skeleton + Spotless/JaCoCo hooks configured (completed)
- **I1.T6:** Test strategy document + E2E runner script stub (this document + `scripts/qa/run_e2e.sh`)
- **Baseline Coverage:** Skeleton tests only (~20% coverage from existing TenantResolutionFilterTest, CatalogImportJobHandlerTest)

#### Iteration 2 (I2): Tenant + Identity + Catalog Core

- **I2.T2:** Identity service implementation with unit/integration tests (target: ≥85% identity module coverage)
  - JWT generation/validation tests
  - Password hashing negative cases
  - Session logging integration tests
  - Impersonation audit trail verification
- **I2.T3:** Catalog CRUD with REST-assured contract tests (target: ≥85% catalog module coverage)
  - Product/variant service unit tests
  - SKU generation edge cases
  - Category tree traversal tests
- **I2.T4:** Catalog import/export job handlers (already implemented, target: maintain existing coverage)
- **I2.T8:** Integration test suite expansion (completed)
  - REST-assured contract tests (`modules/core-platform/src/test/java/villagecompute/storefront/api/contract/CatalogContractIT.java`) validating catalog endpoints against OpenAPI spec
  - Playwright storefront tests (`tests/e2e/storefront/catalog.spec.ts`) covering home, category browsing, product detail, cart add, theme tokens, visual snapshots
  - Automated seeding via `tenant_seed.sh --catalog` providing deterministic techgadgets/artisancrafts catalog data
  - E2E runner script (`scripts/qa/run_e2e.sh`) orchestrates seed → REST-assured → Playwright execution flow
  - Testcontainers PostgreSQL with RLS verification
  - Tenant isolation negative tests
- **Cumulative Coverage Target:** ≥60% overall (identity + catalog + tenant modules fully tested)

#### Iteration 3 (I3): Checkout + Payments + Media

- **I3.T2:** Checkout service with cart/discount/tax tests (target: ≥85% checkout module coverage)
  - Order state machine unit tests
  - Discount application edge cases
  - Tax calculation boundary tests
- **I3.T3:** Payment service with Stripe webhook mocks (target: ≥85% payment module coverage)
  - Webhook signature validation tests
  - Payment state transitions (pending/captured/refunded)
  - Refund logic negative cases
- **I3.T5:** Media pipeline with FFmpeg integration tests (target: ≥85% media module coverage)
- **I3.T6:** API contract coverage for checkout/payment/shipping (this task)
  - **OpenAPI Contract Validation:**
    - Checkout endpoints (`/checkout/preview`, `/checkout/commit`) include security metadata (OAuth scopes `checkout:read`, `checkout:write`), rate limits (60-300 req/min), feature flags (`checkout.preview.enabled`, `checkout.order-creation.enabled`), and RFC7807 error schemas
    - Shipping endpoints (`/shipping/rates`, `/shipping/validate-address`, `/shipping/labels`, `/shipping/profiles/*`) include security metadata (OAuth scope `shipping:read`, `shipping:write`), rate limits (500-1000 req/min), and feature flags (`shipping.rate-shopping.enabled`)
    - Payment endpoints (`/admin/orders/{orderId}/refund`, `/webhooks/stripe`) include security metadata and idempotency key support
  - **Schema Completeness:**
    - All checkout/payment/shipping schemas reference shared components (`Money`, `Address`, `PaginationMetadata`, `ProblemDetails`)
    - Realistic examples provided for `CheckoutPreviewRequest`, `CheckoutPreview`, `ShippingRateRequest`, `ShippingProfile`, `OrderCreatedResponse`
    - Payment intent details embedded in `OrderDetail` schema with Stripe payment method references
  - **REST-assured Contract Tests:**
    - Validate `/checkout/preview` calculates correct totals with tax/shipping/discounts (sample test: `CheckoutContractIT.testCheckoutPreview_WithPromoCode_AppliesDiscount`)
    - Validate `/checkout/commit` creates order + charges payment with idempotency key handling (sample test: `CheckoutContractIT.testCheckoutCommit_IdempotentRetry_ReturnsCachedOrder`)
    - Validate `/shipping/rates` returns carrier quotes with fallback handling (sample test: `ShippingContractIT.testShippingRates_CarrierUnavailable_ReturnsFallback`)
    - Validate OpenAPI schema compliance for all requests/responses using REST Assured's JSON Schema Validator
  - **Playwright E2E Tests:**
    - Storefront checkout flow: cart review → address entry → shipping selection → payment → order confirmation (test file: `tests/e2e/storefront/checkout.spec.ts`)
    - Guest checkout: anonymous cart conversion to order with session ID tracking
    - Registered checkout: authenticated user with saved addresses and loyalty points redemption
    - Admin refund workflow: partial/full refund processing with payment state verification (test file: `tests/e2e/admin/orders.spec.ts`)
  - **Coverage Targets:**
    - REST-assured contract tests: 100% coverage of checkout/payment/shipping endpoints defined in OpenAPI spec
    - Playwright E2E tests: Guest checkout, registered checkout, admin refund, shipping rate selection scenarios
    - Schema validation: All request/response bodies must validate against OpenAPI schemas
- **I3.T8:** Performance + chaos testing implementation
  - Gatling/Locust load tests for checkout/cart APIs
  - Chaos scripts for DB failover, Stripe outages
  - Performance budgets enforcement (checkout <300ms p95)
  - **Stretch Goal:** Mutation testing evaluation (PIT + StrykerJS)
- **Cumulative Coverage Target:** ≥75% overall (checkout + payment + media modules added)

#### Iteration 4 (I4): Loyalty + POS + Headless

- **I4.T2:** Loyalty service tests (target: ≥85% loyalty module coverage)
  - Points accrual/redemption unit tests
  - Tier progression logic tests
  - Expiration rule edge cases
- **I4.T3:** POS offline sync tests (target: ≥85% POS module coverage)
  - Offline transaction queue tests
  - Sync conflict resolution unit tests
  - Receipt generation integration tests
- **I4.T4:** Headless CMS tests (target: ≥85% headless module coverage)
- **I4.T8:** E2E suite expansion
  - POS app offline checkout + sync scenarios
  - Loyalty redemption flow E2E tests
  - Headless content rendering E2E tests
- **Cumulative Coverage Target:** ≥80% overall (platform-wide 80% threshold achieved)

#### Iteration 5 (I5): Platform Admin + Release Readiness

- **I5.T2:** Platform admin module tests (target: ≥85% platform admin coverage)
  - Feature flag CRUD tests
  - Tenant bulk operations tests
  - Usage metrics aggregation tests
- **I5.T7:** Comprehensive verification plan execution
  - **Deliverable:** Release readiness report (`target/release-readiness-report.html`)
  - **Contents:**
    - Final coverage metrics (unit: ≥85%, integration: ≥80%, e2e: 100% pass)
    - Mutation score (if implemented in I3.T8): ≥75% for critical modules
    - Performance benchmarks (checkout API <300ms p95, storefront LCP <2s)
    - Unresolved risks + rollback plans
    - Tenant onboarding checklist
    - Platform governance approvals
- **Final Coverage Target:** ≥85% overall (stretch goal beyond 80% minimum)

### References

- [VillageCompute Java Project Standards](../java-project-standards.adoc) - Testing dependencies, coverage requirements
- [Section 6: Verification & Integration Strategy](.codemachine/artifacts/plan/03_Verification_and_Glossary.md) - Blueprint-level testing hierarchy
- [Playwright Configuration](../../tests/e2e/playwright/playwright.config.ts) - E2E test setup
- [E2E Seed Script](../../tests/fixtures/seed-e2e-data.js) - Test data provisioning

## CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/ci.yml`) enforces the quality gates described in the "CI/CD Pipeline and Quality Gates" section above

## Local Development Checklist

Before committing code, run:

```bash
# Format code
./mvnw spotless:apply

# Run tests with coverage
./mvnw verify

# Check coverage report
open modules/core-platform/target/site/jacoco/index.html

# Validate OpenAPI spec
npm run lint:openapi

# Validate PlantUML diagrams
npm run diagrams:check
```

## References

- [VillageCompute Java Project Standards](../java-project-standards.adoc)
- [ADR-002: CI/CD Quality Gates](../adr/ADR-002-quality-gates.md)
- [Developer Guide](../architecture/developer-guide.md)
