# Test Strategy

This document defines the testing approach, coverage expectations, and quality standards for the Village Storefront platform.

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

### Unit Tests
- **Framework:** JUnit 5 with `@QuarkusTest`
- **Mocking:** Mockito via `@InjectMock`
- **Coverage Target:** 80% line and branch coverage
- **Database:** H2 in-memory for fast execution
- **Scope:** Service layer, business logic, utilities

### Integration Tests
- **Framework:** JUnit 5 with `@QuarkusIntegrationTest`
- **Database:** Testcontainers PostgreSQL for realistic testing
- **Scope:** REST API endpoints, database access, external integrations
- **Assertions:** REST Assured for HTTP testing

### Native Tests
- **Execution:** `./mvnw verify -Pnative`
- **Purpose:** Verify GraalVM native image compatibility
- **CI:** Runs on main branch and PRs targeting main
- **Duration:** ~15-30 minutes (longer than JVM tests)

### End-to-End Tests
- **Tool:** Playwright (future, for Vue.js admin SPA)
- **Scope:** Customer-facing storefront, admin dashboard workflows
- **Environment:** Docker Compose with full service stack

## CI/CD Pipeline

The GitHub Actions workflow (`.github/workflows/ci.yml`) enforces:

1. **Validation Stage:**
   - Spotless formatting check
   - OpenAPI spec validation (Spectral)
   - PlantUML diagram validation

2. **Test Stage (Parallel):**
   - JVM tests with JaCoCo coverage
   - Native tests (main/PR only)

3. **Quality Gate:**
   - SonarCloud analysis
   - Coverage enforcement (80%)
   - Security vulnerability scan (OWASP Dependency-Check)

4. **Docker Build (main/beta only):**
   - Native container image build
   - Push to registry

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
