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
| **Validate Code Style & Specs** | Spotless formatting, OpenAPI linting, PlantUML diagram validation, npm helper health | `npm run lint`, `npm run lint:openapi`, `npm run diagrams:check` |
| **Test (matrix: JVM & Native)** | JVM tests with JaCoCo 80% line/branch coverage gate + native profile verification | `npm run test` (runs `./mvnw verify jacoco:report`), `./mvnw verify -Pnative` |
| **Admin SPA (conditional)** | Future Vue admin lint/test when `modules/core-platform/src/main/webui/` exists | `npm run lint` / `npm test` inside SPA workspace |
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
npm run lint:openapi
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
- **PostgreSQL client tools** (optional, for manual database access)
- **FFmpeg** (optional, required for media processing features)

### Local Development Setup

#### Option 1: Automated Bootstrap (Recommended)

The fastest way to get started is using the bootstrap script:

1. **Clone the repository:**
   ```bash
   git clone https://github.com/teacurran/village-storefront.git
   cd village-storefront
   ```

2. **Run the bootstrap script:**
   ```bash
   ./scripts/dev/bootstrap.sh
   ```

   This script will:
   - Validate prerequisites (Docker, psql, FFmpeg)
   - Create `.env` from `.env.example` if needed
   - Start Docker Compose services (PostgreSQL, MinIO, Mailhog)
   - Start shipping carrier mocks (USPS, UPS, FedEx)
   - Wait for PostgreSQL to be ready
   - Run Flyway database migrations
   - Seed sample catalog + staff data (2 tenants, admin/staff logins, products, inventory)
   - Seed sample consignment data (consignors, payout ledgers for QA testing)
   - Create MinIO bucket for media storage

3. **Start the development server:**
   ```bash
   npm install
   npm run dev
   ```

   The application will start at `http://localhost:8080` with hot reload enabled.

#### Option 2: Manual Setup

If you prefer manual control:

1. **Clone and configure environment:**
   ```bash
   git clone https://github.com/teacurran/village-storefront.git
   cd village-storefront
   cp .env.example .env
   # Edit .env with your configuration
   ```

2. **Start Docker Compose services:**
   ```bash
   cd docker
   docker compose up -d
   cd ..
   ```

3. **Wait for PostgreSQL and run migrations:**
   ```bash
   ./scripts/dev/wait-for-postgres.sh
   ./mvnw -pl modules/core-platform flyway:migrate
   ```

4. **Load sample data (optional, creates tenants + staff accounts):**
   ```bash
   # Load catalog data (products, inventory, staff accounts)
   psql -h localhost -U appuser -d storefront_dev -f tools/scripts/sample_catalog_loader.sql

   # Load consignment data (consignors, payout ledgers for QA)
   psql -h localhost -U appuser -d storefront_dev -f tools/scripts/sample_consignment_loader.sql
   ```

5. **Install dependencies and start development server:**
   ```bash
   npm install
   npm run dev
   ```

   The application will start at `http://localhost:8080` with hot reload enabled.

## Build & Development Commands

### Maven Multi-Module Structure

The project uses a Maven parent-child module structure:

- **Parent POM**: `pom.xml` (root directory) - manages dependencies and plugin configuration
- **Module**: `modules/core-platform` - core platform code (tenant gateway, identity, feature flags)

All Maven commands run from the root directory. Use `-pl modules/core-platform` to target the specific module, or omit it to build all modules.

**Quickstart for local development:**

```bash
# Automated bootstrap (recommended)
./scripts/dev/bootstrap.sh

# Install dependencies and start Quarkus dev mode
npm install
npm run dev

# Or run Quarkus directly:
./mvnw -pl modules/core-platform quarkus:dev
```

**Manual approach:**

```bash
# Start local services (PostgreSQL, MinIO, Mailhog)
cd docker && docker compose up -d && cd ..

# Run database migrations
./mvnw -pl modules/core-platform flyway:migrate

# Load sample data (optional)
psql -h localhost -U appuser -d storefront_dev -f tools/scripts/sample_catalog_loader.sql

# Start Quarkus dev mode with hot reload
./mvnw -pl modules/core-platform quarkus:dev
```

### Java/Maven Commands

```bash
# Compile the project (all modules)
./mvnw compile

# Compile specific module
./mvnw -pl modules/core-platform compile

# Run tests with coverage (all modules)
./mvnw test

# Run tests for specific module
./mvnw -pl modules/core-platform test

# Run tests and generate coverage report
./mvnw verify

# Run verify for specific module
./mvnw -pl modules/core-platform verify

# Check code coverage threshold (80%)
./mvnw jacoco:check

# Apply code formatting (Spotless) to all modules
./mvnw spotless:apply

# Apply formatting to specific module
./mvnw -pl modules/core-platform spotless:apply

# Check code formatting (without fixing)
./mvnw spotless:check

# Build JAR for JVM deployment
./mvnw package

# Build native executable (requires GraalVM)
./mvnw -pl modules/core-platform package -Pnative

# Build container image with native executable
./mvnw -pl modules/core-platform package -Pnative -Dquarkus.container-image.build=true

# Generate Kubernetes manifests
./mvnw -pl modules/core-platform package -Dquarkus.kubernetes.deploy=true

# Start development server
./mvnw -pl modules/core-platform quarkus:dev
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
npm run lint:openapi

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

### Loading Sample Catalog Data

For development and testing purposes, you can load sample catalog data (products, variants, categories, inventory):

```bash
# Ensure PostgreSQL is running
docker compose up -d

# Load sample data (creates Tech Gadgets store with products)
psql -h localhost -U appuser -d storefront_dev -f tools/scripts/sample_catalog_loader.sql

# Or if using password authentication:
PGPASSWORD=apppass psql -h localhost -U appuser -d storefront_dev -f tools/scripts/sample_catalog_loader.sql
```

This will create:
- 2 sample tenants (`techgadgets` and `artisancrafts`)
- 4 categories (Electronics, Smartphones, Audio, Accessories)
- 3 products (Wireless Earbuds, Phone Cases, USB-C Cables)
- 7 product variants with pricing
- 8 inventory records across multiple locations
- Tenant admin/staff users (password: `changeme123!`) for quick login

**Test tenant access:**
- Subdomain: `techgadgets` (use as Host header or in local DNS)
- Tenant ID: `a0000000-0000-0000-0000-000000000001`

**Default staff logins (after running the seed script or bootstrap):**
- `owner@techgadgets.local` (Store Owner role)
- `staff@techgadgets.local` (Staff role)
- `owner@artisancrafts.local` (Store Owner role)
- _Password for all accounts:_ `changeme123!`

**Verify data loading:**
```bash
psql -h localhost -U appuser -d storefront_dev -c "SELECT * FROM products WHERE tenant_id = 'a0000000-0000-0000-0000-000000000001'::uuid;"
```

### FFmpeg Installation (Media Processing)

FFmpeg is required for media processing features (video transcoding, thumbnail generation). The application will work without it, but media upload features will fail.

**macOS (Homebrew):**
```bash
brew install ffmpeg
```

**Ubuntu/Debian:**
```bash
sudo apt update
sudo apt install ffmpeg
```

**Windows:**
1. Download FFmpeg from [ffmpeg.org](https://ffmpeg.org/download.html)
2. Extract to `C:\ffmpeg`
3. Add `C:\ffmpeg\bin` to your PATH
4. Update `.env`: `MEDIA_FFMPEG_PATH=C:/ffmpeg/bin/ffmpeg.exe`

**Verify installation:**
```bash
ffmpeg -version
```

**Custom FFmpeg location:**

If FFmpeg is installed in a non-standard location, update your `.env` file:
```bash
# Default paths:
# macOS (Homebrew): /opt/homebrew/bin/ffmpeg
# Linux (apt): /usr/bin/ffmpeg
# Windows: C:/ffmpeg/bin/ffmpeg.exe
MEDIA_FFMPEG_PATH=/path/to/your/ffmpeg
```

### Local Services Access

After running `./scripts/dev/bootstrap.sh` or `docker compose up`, these services are available:

| Service | URL | Credentials | Purpose |
|---------|-----|-------------|---------|
| **Application** | http://localhost:8080 | - | Quarkus application (after `npm run dev`) |
| **Swagger UI** | http://localhost:8080/q/swagger-ui | - | Interactive API documentation |
| **PostgreSQL** | localhost:5432 | appuser / apppass | Database (connect via psql or IDE) |
| **MinIO Console** | http://localhost:9001 | minioadmin / minioadmin | S3-compatible storage web UI |
| **MinIO API** | http://localhost:9000 | minioadmin / minioadmin | S3 API endpoint |
| **Mailhog UI** | http://localhost:8025 | - | Email testing interface |
| **USPS Mock** | http://localhost:9100 | - | USPS Web Tools API simulator |
| **UPS Mock** | http://localhost:9101 | - | UPS API simulator |
| **FedEx Mock** | http://localhost:9102 | - | FedEx API simulator |
| **Stripe CLI** | (webhook forwarder) | - | Stripe webhook tunnel (requires `--profile payments` + `stripe login`) |
| **Jaeger UI** | http://localhost:16686 | - | Tracing (requires `--profile observability`) |

**Common operations:**

```bash
# View service logs
docker compose logs -f [postgres|minio|mailhog|usps-mock|ups-mock|fedex-mock]

# View all mock service logs
docker compose logs -f usps-mock ups-mock fedex-mock

# Stop all services
docker compose down

# Restart a specific service
docker compose restart postgres

# Connect to database
psql -h localhost -U appuser -d storefront_dev

# Check service status
docker compose ps

# Health check for mock services
curl http://localhost:9100/health  # USPS
curl http://localhost:9101/health  # UPS
curl http://localhost:9102/health  # FedEx
```

#### Payment & Shipping Mock Services

The development environment includes mock services for payment and shipping integrations to enable comprehensive QA testing without external API dependencies.

**Shipping Carrier Mocks:**

All shipping mocks start automatically with `docker compose up` and provide realistic API responses for:

- **USPS Mock** (port 9100): Simulates USPS Web Tools API
  - Rate calculation (`/ShippingAPI.dll?API=RateV4`)
  - Address validation (`/ShippingAPI.dll?API=Verify`)
  - Package tracking (`/ShippingAPI.dll?API=TrackV2`)

- **UPS Mock** (port 9101): Simulates UPS JSON API
  - Rating (`/api/rating/v1/Rate`)
  - Tracking (`/api/track/v1/details/{trackingNumber}`)
  - Address validation (`/api/addressvalidation/v1/1`)

- **FedEx Mock** (port 9102): Simulates FedEx JSON API
  - Rate quotes (`/rate/v1/rates/quotes`)
  - Tracking (`/track/v1/trackingnumbers`)
  - Address validation (`/country/v1/postal/validate`)

**Stripe CLI Webhook Forwarder:**

The Stripe CLI enables webhook testing in local development. It requires one-time authentication:

```bash
# First-time setup: Authenticate with Stripe
stripe login

# Start the webhook forwarder (forwards events to localhost:8080)
docker compose --profile payments up -d stripe-cli

# View webhook events
docker compose logs -f stripe-cli

# Stop webhook forwarder
docker compose --profile payments down
```

The Stripe CLI forwards webhook events from Stripe test mode to `http://localhost:8080/api/webhooks/stripe`, allowing you to test payment flows end-to-end with real Stripe test events.

**QA Test Data:**

The bootstrap script seeds consignment data for testing checkout/payment/payout flows:

- **3 Consignors** across 2 tenants
- **Payout Ledgers** with historical transactions
- **Mobile Accessories Hub** has $342.75 available balance (ready for payout testing)
- **Mixed Catalog** demonstrating consignment + store-owned products

Test scenarios:
1. Place order with consignment item → verify SALE ledger entry
2. Simulate settlement job → verify pending→available balance transfer
3. Process payout for consignor with available balance
4. Test Stripe webhooks for payment attribution
5. Test shipping rate calculation with carrier mocks
6. Test refund of consignment item → verify REFUND ledger entry

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
npm run lint:openapi

# Full local CI simulation (requires ~15 minutes)
./mvnw spotless:check && \
  npm run lint && \
  npm run lint:openapi && \
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
├── modules/                       # Maven modules
│   └── core-platform/             # Core platform module
│       ├── pom.xml                # Module POM
│       ├── src/
│       │   ├── main/
│       │   │   ├── java/villagecompute/storefront/
│       │   │   │   ├── api/
│       │   │   │   │   ├── rest/         # REST resources
│       │   │   │   │   └── types/        # API DTOs
│       │   │   │   ├── config/           # Configuration classes
│       │   │   │   ├── data/
│       │   │   │   │   ├── models/       # JPA entities
│       │   │   │   │   └── repositories/ # Data access layer
│       │   │   │   ├── exceptions/       # Custom exceptions
│       │   │   │   ├── integration/      # External service integrations
│       │   │   │   ├── jobs/             # Background jobs
│       │   │   │   ├── services/         # Business logic
│       │   │   │   ├── tenant/           # Multi-tenancy infrastructure
│       │   │   │   └── util/             # Utilities
│       │   │   ├── resources/
│       │   │   │   ├── application.properties
│       │   │   │   └── db/               # Database baseline schema
│       │   │   └── webui/                # Vue.js admin SPA (future)
│       │   └── test/
│       │       └── java/villagecompute/storefront/
│       └── target/                # Module build output
│           ├── site/jacoco/       # Coverage reports
│           └── surefire-reports/  # Test results
├── tools/                         # Node.js automation scripts
│   ├── install.cjs
│   ├── lint.cjs
│   ├── run.cjs
│   ├── test.cjs
│   └── README.md
├── docker-compose.yml             # Local development services
├── pom.xml                        # Maven parent POM
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

## Storefront Development

The customer-facing storefront uses **Qute templates** with **Tailwind CSS** for server-side rendering (SSR). This approach provides excellent SEO, fast initial page loads, and progressive enhancement.

### Storefront Stack

- **Templates:** Qute (type-safe, compiled templates)
- **Styling:** Tailwind CSS (utility-first, responsive design)
- **Components:** PrimeUI (progressively enhanced widgets)
- **Localization:** ResourceBundle (en/es message bundles)
- **Theming:** CSS custom properties (tenant-specific color overrides)

### Tailwind Setup

```bash
# Install Tailwind (one-time setup)
npm install -D tailwindcss

# Generate CSS (development with watch mode)
npx tailwindcss -i src/main/resources/css/input.css \
                -o src/main/resources/META-INF/resources/static/css/tailwind.css \
                --watch

# Build for production (minified)
npx tailwindcss -i src/main/resources/css/input.css \
                -o src/main/resources/META-INF/resources/static/css/tailwind.css \
                --minify
```

### Template Structure

```
src/main/resources/templates/
├── base.html              # Base layout with header/footer
├── StorefrontResource/
│   └── index.html         # Homepage template
└── components/
    ├── header.html        # Navigation header
    ├── footer.html        # Footer with links
    ├── hero.html          # Hero banner section
    └── product-card.html  # Product card component
```

### Creating New Storefront Pages

1. **Create template** in `src/main/resources/templates/StorefrontResource/`:

```html
{#include base.html}

{#content}
<div class="max-w-8xl mx-auto px-4 py-12">
    <h1 class="text-4xl font-bold mb-6">{pageTitle}</h1>
    <p>{msg:custom_message}</p>
</div>
{/content}

{/include}
```

2. **Add resource method** in `StorefrontResource.java`:

```java
@GET
@Path("/custom")
public TemplateInstance customPage() {
    return Templates.customPage()
        .data("pageTitle", "Custom Page")
        .data("tenantName", getTenantName());
}
```

3. **Add message keys** to `messages.properties` and `messages_es.properties`

### Theming & Localization

See [docs/storefront-theming.md](docs/storefront-theming.md) for complete guide on:
- Customizing tenant colors via design tokens
- Adding new message translations
- Using Money formatting helpers
- Testing multi-tenant themes

## API Documentation

The REST API follows an **OpenAPI spec-first** approach:

- **Specification:** `api/v1/openapi.yaml`
- **Interactive Docs:** `http://localhost:8080/q/swagger-ui` (dev mode)
- **Linting:** `npm run lint:openapi` (uses Spectral)

All API types are generated from the OpenAPI spec to ensure contract-first development.

### OpenAPI Workflow

The project uses a **spec-first** approach where the OpenAPI specification is the source of truth for all API contracts. This ensures consistency between documentation, client SDKs, and backend implementation.

#### Editing the OpenAPI Specification

1. **Edit the spec:** Modify `api/v1/openapi.yaml` to add or update endpoints, schemas, or parameters
2. **Lint the spec:** Run `npm run lint:openapi` to validate your changes
3. **Fix any errors:** Spectral will report issues with schema references, missing descriptions, or spec violations
4. **Review changes:** The spec defines the contract - ensure all required fields, security schemes, and examples are documented

#### OpenAPI Specification Structure

The spec at `api/v1/openapi.yaml` includes:

- **Versioning:** All endpoints use `/api/v1` prefix with URL-based versioning
- **Security Schemes:**
  - `bearerAuth`: JWT tokens for user sessions (access + refresh tokens)
  - `apiKeyAuth`: Long-lived API keys for server-to-server integrations
  - `oauthClientCredentials`: OAuth 2.0 client credentials flow for headless API access
- **Shared Components:**
  - `Money`: Currency amounts with ISO 4217 codes
  - `Address`: Physical addresses for shipping/billing
  - `PaginationMetadata`: Standardized pagination metadata
  - `ProblemDetails`: RFC 7807 error responses
- **API Tags:**
  - `System`: Health checks and platform metadata
  - `Authentication`: Login, token refresh, session management
  - `Storefront`: Customer-facing catalog, cart, and checkout
  - `Admin`: Store management for products, orders, settings
  - `Vendor`: Consignor portal for tracking items and payouts
  - `Headless`: High-throughput API for custom frontends
  - `Platform`: Platform administration (tenant management)

#### Linting & Validation

```bash
# Validate OpenAPI spec with Spectral
npm run lint:openapi

# Common issues caught by linting:
# - Duplicate parameters in endpoint definitions
# - Missing operation descriptions
# - Invalid schema references (typos like CartDto vs Cart)
# - Undefined security scopes
# - Missing required fields in schemas
```

The CI pipeline enforces that `npm run lint:openapi` passes before merging PRs and runs a compatibility diff (`npm run openapi:diff`) against the base branch. This ensures:
- No breaking changes without version bumps
- All endpoints have complete documentation
- Security schemes are properly defined
- Schema references are valid
- Breaking changes are surfaced early with a structured diff summary
- A legacy alias `npm run openapi:lint` remains available for older scripts, but `lint:openapi` is preferred

#### Quarkus Integration

The Quarkus application is configured to serve and validate against the OpenAPI spec:

```properties
# modules/core-platform/src/main/resources/application.properties

# Serve OpenAPI spec at /openapi endpoint
quarkus.smallrye-openapi.path=/openapi

# Point to spec-first definition
quarkus.smallrye-openapi.store-schema-directory=api/v1

# Enable Swagger UI in dev mode
quarkus.swagger-ui.always-include=true
quarkus.swagger-ui.path=/q/swagger-ui
```

**Access the spec:**
- JSON format: `http://localhost:8080/openapi`
- Swagger UI: `http://localhost:8080/q/swagger-ui`

#### Custom OpenAPI Extensions

Add custom `x-` extensions when defining or updating endpoints to document additional metadata:

- `x-tenant-scope`: Indicates whether endpoint is single-tenant or cross-tenant
- `x-feature-flags`: Lists feature flags that control endpoint availability
- `x-rate-limit`: Documents rate limit policies per authentication method

Example:
```yaml
/api/v1/admin/products:
  get:
    summary: List products
    x-tenant-scope: single
    x-feature-flags: [catalog.advanced_search]
    x-rate-limit: 1000/min (authenticated)
```

#### Best Practices

1. **Always lint before committing:** Run `npm run lint:openapi` to catch issues early
2. **Use `$ref` for shared schemas:** Reuse components like `Money`, `Address`, and `PaginationMetadata`
3. **Document all security requirements:** Each endpoint must declare authentication via `security:` block
4. **Include examples:** Add realistic examples for request/response schemas
5. **Version breaking changes:** Increment version (e.g., `/api/v2`) for non-backward-compatible changes
6. **Add operation descriptions:** Every operation must have a `description:` field explaining its purpose
7. **Define OAuth scopes:** All scopes referenced in security must be defined in the security scheme

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

### QA Testing with Mock Services

For comprehensive testing of the checkout pipeline (payments, shipping, consignment), see the dedicated **[QA Testing Guide](docs/QA_TESTING_GUIDE.md)**.

The guide covers:
- Setting up Stripe CLI for webhook testing
- Testing shipping rate calculation with carrier mocks (USPS, UPS, FedEx)
- Multi-tenant consignment scenarios and payout flows
- Complete end-to-end checkout scenarios
- Troubleshooting common issues

**Quick mock service health check:**

```bash
# Verify all shipping mocks are running
./scripts/dev/wait-for-shipping-mocks.sh

# Test individual mock services
curl http://localhost:9100/health  # USPS Mock
curl http://localhost:9101/health  # UPS Mock
curl http://localhost:9102/health  # FedEx Mock
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
   npm run lint:openapi     # Validate specs
   ```
4. **Commit with descriptive message:** `git commit -m "feat: add product search"`
5. **Push and create PR:** All CI checks must pass before merge

### Code Review Checklist

- [ ] All tests pass (`./mvnw verify`)
- [ ] Code coverage ≥80% (`./mvnw jacoco:check`)
- [ ] Code formatted (`./mvnw spotless:check`)
- [ ] OpenAPI spec valid (`npm run lint:openapi`)
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
- **Storefront Theming:** `docs/storefront-theming.md` (Tailwind, design tokens, localization)
- **Feature Flag Governance:** `docs/feature_flags/governance.md` (Kill switches, rollout process, flag lifecycle)
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
