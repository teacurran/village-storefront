# Village Storefront

[![CI](https://github.com/teacurran/village-storefront/actions/workflows/ci.yml/badge.svg)](https://github.com/teacurran/village-storefront/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/badge/coverage-80%25-green)](https://sonarcloud.io/dashboard?id=teacurran_village-storefront)
[![License](https://img.shields.io/badge/license-UNLICENSED-red)](LICENSE)

A multi-tenant SaaS ecommerce platform built with Java Quarkus for VillageCompute.

## Overview

Village Storefront is a multi-tenant ecommerce platform that allows merchants to create and manage their own online stores. Each store is accessible via subdomain (`storename.platform.com`) or custom domain. The platform supports:

- **Physical products** with variants (size, color, etc.)
- **Digital products** (downloads, licenses)
- **Consignment vendor management**
- **Integrated payment processing** (Stripe Connect)
- **Multi-tenant architecture** with tenant isolation
- **Admin dashboard** (Vue.js 3 SPA)
- **Customer-facing storefront** (Qute templates with Tailwind CSS)

## Continuous Integration & Quality Gates

The GitHub Actions workflow at `.github/workflows/ci.yml` runs on every push/PR and enforces the engineering guardrails mandated in `docs/java-project-standards.adoc`. The pipeline is fully parallelized and publishes lint/test artifacts plus job timing metrics so we can continuously harden the runtime.

| Stage | What it checks | Commands |
| --- | --- | --- |
| **Validate Code Style & Specs** | Spotless formatting, OpenAPI linting, PlantUML diagram validation, npm helper health | `npm run lint`, `npm run openapi:lint`, `npm run diagrams:check` |
| **Test (matrix: JVM & Native)** | JVM tests with JaCoCo 80% line/branch coverage gate + native profile verification | `npm run test` (runs `./mvnw verify jacoco:report`), `./mvnw verify -Pnative` |
| **Admin SPA (conditional)** | Future Vue admin lint/test when `src/main/webui/` exists | `npm run lint` / `npm test` inside SPA workspace |
| **SonarCloud** | Static analysis + duplicate coverage verification with blocking quality gate | `./mvnw verify` + `sonar-maven-plugin` |
| **Docker Build (opt-in)** | Pushes container images when `vars.DOCKER_ENABLED` is true | `docker buildx build` |

### Local Quality Gate Checklist

```bash
# Format + lint backend code
npm run lint

# JVM tests with JaCoCo 80% enforcement
npm run test

# Optional native profile tests (slow)
npm run test:native

# Validate published specs and diagrams
npm run openapi:lint
npm run diagrams:check

# Regenerate diagram images after edits
npm run diagrams:generate
```

> **Tip:** The pipeline uses `tools/plantuml.jar` so the same commands work locally and in CI. Run `act pull_request` if you want a dry-run of the workflow before pushing.

## Technology Stack

- **Backend:** Java 21, Quarkus 3.17+, Maven
- **Database:** PostgreSQL 17 (multi-tenant with RLS)
- **Frontend (Admin):** Vue 3, PrimeVue, Tailwind CSS (via Quinoa)
- **Frontend (Storefront):** Qute templates, Tailwind CSS, PrimeUI
- **Authentication:** JWT tokens (stateless)
- **Caching:** Caffeine (in-memory)
- **Observability:** OpenTelemetry, Prometheus, Jaeger
- **Deployment:** Kubernetes (k3s), GraalVM native images

## Quick Start

### Prerequisites

- **Java 21+** ([Adoptium Temurin](https://adoptium.net/))
- **Node.js 18+** (for admin SPA and build tools)
- **Docker** (for local development services)
- **Maven** (included via wrapper: `./mvnw`)

### Local Development Setup

1. **Clone the repository:**
   ```bash
   git clone https://github.com/teacurran/village-storefront.git
   cd village-storefront
   ```

2. **Start local services (PostgreSQL, Mailpit, Jaeger):**
   ```bash
   docker-compose up -d
   ```

3. **Run database migrations:**
   ```bash
   cd migrations
   mvn migration:up -Dmigration.env=development
   cd ..
   ```

4. **Install dependencies and start development server:**
   ```bash
   npm install
   npm run dev
   ```

   The application will start at `http://localhost:8080` with hot reload enabled.

## Build & Development Commands

### Java/Maven Commands

```bash
# Compile the project
./mvnw compile

# Run tests with coverage
./mvnw test

# Run tests and generate coverage report
./mvnw verify

# Check code coverage threshold (80%)
./mvnw jacoco:check

# Apply code formatting (Spotless)
./mvnw spotless:apply

# Check code formatting (without fixing)
./mvnw spotless:check

# Build JAR for JVM deployment
./mvnw package

# Build native executable (requires GraalVM)
./mvnw package -Pnative

# Build container image with native executable
./mvnw package -Pnative -Dquarkus.container-image.build=true

# Generate Kubernetes manifests
./mvnw package -Dquarkus.kubernetes.deploy=true

# Start development server
./mvnw quarkus:dev
```

### npm Commands

These commands wrap the Node.js helper scripts in `tools/`:

```bash
# Install all dependencies (Java + npm)
npm install

# Start development server (runs Maven + Quarkus dev mode)
npm run dev

# Run linting checks (Java formatting via Spotless)
npm run lint

# Run all tests with coverage (JaCoCo gate enabled)
npm test

# Run native tests/profile (executes ./mvnw verify -Pnative)
npm run test:native

# Lint OpenAPI specification
npm run openapi:lint

# Validate PlantUML diagrams
npm run diagrams:check

# Generate PlantUML diagram images
npm run diagrams:generate

# Apply code formatting
npm run format

# Build production JAR
npm run build

# Build native executable
npm run build:native
```

### Database Migration Commands

From the `migrations/` directory:

```bash
# Check migration status
mvn migration:status -Dmigration.env=development

# Apply pending migrations
mvn migration:up -Dmigration.env=development

# Rollback last migration
mvn migration:down -Dmigration.env=development

# Create new migration
mvn migration:new -Dmigration.env=development -Dmigration.description="add_feature"
```

## Code Quality & CI/CD

### Quality Standards

- **Code Coverage:** 80% minimum (line and branch coverage)
- **Code Formatting:** Spotless with Eclipse formatter
- **Line Length:** 120 characters
- **Indentation:** 4 spaces for Java, 2 spaces for XML/YAML/JSON
- **Zero Defects:** No bugs or vulnerabilities allowed (enforced by SonarCloud)

### CI Pipeline

The project uses GitHub Actions for continuous integration. The pipeline runs:

1. **Validation Stage** (~2 min):
   - Spotless formatting check
   - npm lint
   - OpenAPI spec validation (Spectral)
   - PlantUML diagram validation

2. **Test Stage (Parallel)** (~10-30 min):
   - **JVM tests:** Maven verify with JaCoCo coverage
   - **Native tests:** GraalVM native build + integration tests (main/PR only)

3. **Quality Gate** (~5-8 min):
   - SonarCloud analysis
   - Coverage enforcement (80%)
   - Security vulnerability scan

4. **Docker Build** (~15-20 min, main/beta only):
   - Native container image build
   - Push to registry

See [ADR-002](docs/adr/ADR-002-quality-gates.md) for detailed CI/CD architecture and rationale.

### Running CI Checks Locally

Before pushing code, run the same checks that CI will execute:

```bash
# Check code formatting
./mvnw spotless:check

# Apply formatting fixes
./mvnw spotless:apply

# Run tests with coverage
./mvnw verify

# Lint OpenAPI spec
npm run openapi:lint

# Full local CI simulation (requires ~15 minutes)
./mvnw spotless:check && \
  npm run lint && \
  npm run openapi:lint && \
  ./mvnw verify && \
  ./mvnw jacoco:check
```

## Project Structure

```
village-storefront/
├── .github/
│   └── workflows/
│       └── ci.yml                 # GitHub Actions CI pipeline
├── api/
│   └── v1/
│       └── openapi.yaml           # OpenAPI 3.0.3 API specification
├── docs/
│   ├── adr/                       # Architecture Decision Records
│   │   ├── ADR-001-tenancy.md
│   │   └── ADR-002-quality-gates.md
│   ├── diagrams/                  # PlantUML architecture diagrams
│   └── java-project-standards.adoc
├── migrations/                    # MyBatis database migrations
│   ├── pom.xml
│   └── src/main/resources/
│       ├── environments/
│       └── scripts/
├── src/
│   ├── main/
│   │   ├── java/villagecompute/storefront/
│   │   │   ├── api/
│   │   │   │   ├── rest/         # REST resources
│   │   │   │   └── types/        # API DTOs
│   │   │   ├── config/           # Configuration classes
│   │   │   ├── data/
│   │   │   │   ├── models/       # JPA entities
│   │   │   │   └── repositories/ # Data access layer
│   │   │   ├── exceptions/       # Custom exceptions
│   │   │   ├── integration/      # External service integrations
│   │   │   ├── jobs/             # Background jobs
│   │   │   ├── services/         # Business logic
│   │   │   ├── tenant/           # Multi-tenancy infrastructure
│   │   │   └── util/             # Utilities
│   │   ├── resources/
│   │   │   ├── application.properties
│   │   │   └── db/               # Database baseline schema
│   │   └── webui/                # Vue.js admin SPA (future)
│   └── test/
│       └── java/villagecompute/storefront/
├── tools/                         # Node.js automation scripts
│   ├── install.cjs
│   ├── lint.cjs
│   ├── run.cjs
│   ├── test.cjs
│   └── README.md
├── target/                        # Maven build output
│   ├── site/jacoco/              # Coverage reports
│   └── surefire-reports/         # Test results
├── docker-compose.yml             # Local development services
├── pom.xml                        # Maven project configuration
├── package.json                   # npm scripts and dependencies
├── eclipse-formatter.xml          # Spotless/Eclipse formatter config
└── README.md                      # This file
```

## Multi-Tenancy Architecture

Each merchant store is a **tenant** identified by subdomain or custom domain. All data is logically isolated via `tenant_id` columns and Row-Level Security (RLS) policies.

### Tenant Resolution Flow

1. HTTP request arrives with `Host` header (e.g., `mystore.villagecompute.com`)
2. `TenantResolutionFilter` extracts subdomain/domain
3. Looks up tenant in `tenants` or `custom_domains` table
4. Sets `TenantContext.currentTenantId` in ThreadLocal
5. All subsequent queries automatically filter by `tenant_id`

See [ADR-001](docs/adr/ADR-001-tenancy.md) for detailed architecture and data model.

## API Documentation

The REST API follows an **OpenAPI spec-first** approach:

- **Specification:** `api/v1/openapi.yaml`
- **Interactive Docs:** `http://localhost:8080/q/swagger-ui` (dev mode)
- **Linting:** `npm run openapi:lint` (uses Spectral)

All API types are generated from the OpenAPI spec to ensure contract-first development.

## Testing

### Unit Tests

```bash
# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=HealthResourceTest

# Run tests with coverage report
./mvnw test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

### Integration Tests

```bash
# Run integration tests (requires test database)
./mvnw verify

# Run native integration tests
./mvnw verify -Pnative
```

### Test Database

Integration tests use H2 in-memory database by default (configured via `@QuarkusTest` profiles).

For local testing against PostgreSQL:
```bash
# Ensure docker-compose is running
docker-compose up -d

# Tests will use dev database (configured in application.properties)
./mvnw verify
```

## Deployment

### JVM Deployment

```bash
# Build JAR
./mvnw package

# Run JAR
java -jar target/quarkus-app/quarkus-run.jar
```

### Native Deployment

```bash
# Build native executable (requires GraalVM)
./mvnw package -Pnative

# Run native executable
./target/village-storefront-1.0-SNAPSHOT-runner
```

### Container Deployment

```bash
# Build container with native executable
./mvnw package -Pnative -Dquarkus.container-image.build=true

# Push to registry (configure in pom.xml)
docker push <registry>/village-storefront:latest
```

### Kubernetes Deployment

```bash
# Generate Kubernetes manifests
./mvnw package -Dquarkus.kubernetes.deploy=true

# Manifests output to: target/kubernetes/
kubectl apply -f target/kubernetes/
```

## Environment Configuration

### Development (default)

```properties
# PostgreSQL connection
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/storefront_dev
quarkus.datasource.username=appuser
quarkus.datasource.password=apppass

# Dev mode features
quarkus.hibernate-orm.log.sql=true
quarkus.dev-ui.enabled=true
```

### Production

```properties
# Production database (configure via environment variables)
quarkus.datasource.jdbc.url=${DB_URL}
quarkus.datasource.username=${DB_USER}
quarkus.datasource.password=${DB_PASS}

# Production optimizations
quarkus.hibernate-orm.log.sql=false
quarkus.dev-ui.enabled=false
```

## Contributing

1. **Read the standards:** `docs/java-project-standards.adoc`
2. **Create a feature branch:** `git checkout -b feature/my-feature`
3. **Make changes and test locally:**
   ```bash
   ./mvnw spotless:apply    # Format code
   ./mvnw verify            # Run tests
   npm run openapi:lint     # Validate specs
   ```
4. **Commit with descriptive message:** `git commit -m "feat: add product search"`
5. **Push and create PR:** All CI checks must pass before merge

### Code Review Checklist

- [ ] All tests pass (`./mvnw verify`)
- [ ] Code coverage ≥80% (`./mvnw jacoco:check`)
- [ ] Code formatted (`./mvnw spotless:check`)
- [ ] OpenAPI spec valid (`npm run openapi:lint`)
- [ ] No SonarCloud issues (checked in CI)
- [ ] ADR created for architectural changes
- [ ] Documentation updated (README, API docs, comments)

## Troubleshooting

### Java not found
Install Java 21+ from [Adoptium Temurin](https://adoptium.net/).

### Maven compilation errors
```bash
./mvnw clean compile
```

### Spotless formatting violations
```bash
./mvnw spotless:apply
```

### Tests failing
```bash
# Check test output
./mvnw test

# Run specific test for debugging
./mvnw test -Dtest=YourTestClass
```

### Coverage below 80%
```bash
# Generate coverage report
./mvnw test jacoco:report

# Open HTML report to see uncovered lines
open target/site/jacoco/index.html
```

### Native build failures
Ensure GraalVM is installed:
```bash
# Install via SDKMAN
sdk install java 21-graalce

# Or download from: https://www.graalvm.org/downloads/
```

### Database migration issues
```bash
# Check migration status
cd migrations
mvn migration:status -Dmigration.env=development

# Rollback and retry
mvn migration:down -Dmigration.env=development
mvn migration:up -Dmigration.env=development
```

## Documentation

- **Architecture Overview:** `docs/architecture_overview.md`
- **ADRs:** `docs/adr/` (Architecture Decision Records)
- **Java Standards:** `docs/java-project-standards.adoc`
- **API Spec:** `api/v1/openapi.yaml`
- **Diagrams:** `docs/diagrams/` (PlantUML source files)

## License

UNLICENSED - Proprietary software for VillageCompute internal use.

## Support

- **GitHub Issues:** https://github.com/teacurran/village-storefront/issues
- **Architecture Team:** Contact via Slack #village-storefront
- **Documentation:** See `docs/` directory

---

**Built with ❤️ by VillageCompute**
