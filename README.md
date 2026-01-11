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

### Platform Capabilities

The platform provides comprehensive SaaS ecommerce capabilities:

- **🏢 Multi-Tenant SaaS:** Subdomain/custom-domain routing, tenant-scoped data isolation via PostgreSQL RLS, automated tenant provisioning, and comprehensive audit trails
- **🛍️ Commerce Primitives:** Product catalog with variants, categories, collections, multi-location inventory management, shopping carts, checkout orchestration, order management, shipping integration, returns processing, loyalty programs, POS system, gift cards, store credit, and sales reports
- **💳 Payment Processing:** Stripe Connect for cards and digital wallets, platform fee management, automated payouts, webhook idempotency, and PaymentProvider abstraction for future payment rails
- **🎨 Dual Frontends:** Qute/Tailwind/PrimeUI storefront for customers plus Vue 3/PrimeVue admin SPA (with POS shell and consignor portal), all internationalization-ready (EN/ES) and themeable per tenant
- **📦 Consignment Management:** Vendor onboarding, commission-based pricing, automated payout calculations with double-entry ledger, approval workflows, and comprehensive audit logging
- **📸 Media Pipeline:** Cloudflare R2 storage, FFmpeg video transcoding, image optimization with Thumbnailator, signed URLs for access control, and background job processing via DelayedJob queues
- **🔌 Headless APIs:** OpenAPI spec-first REST APIs, OAuth 2.0 scopes, rate limiting, signed media URLs, enabling embedded storefronts or partner channel integrations
- **📊 Observability & Compliance:** OpenTelemetry tracing, Prometheus metrics, Grafana dashboards, structured logging, feature flags for progressive rollout, impersonation governance, and automated data retention/archival pipelines

**👉 For New Developers:** Start with the [Developer Guide](docs/architecture/developer-guide.md) for setup, coding standards, and contribution guidelines.

**👉 For Operations:** See the [Runbook Index](docs/architecture/ops/runbook-index.md) for incident response procedures and the [Hypercare Plan](docs/architecture/ops/hypercare-plan.md) for post-launch monitoring.

## Continuous Integration & Quality Gates

The GitHub Actions workflow at `.github/workflows/ci.yml` runs on every push/PR and enforces the engineering guardrails mandated in `docs/java-project-standards.adoc`. The pipeline is fully parallelized and publishes lint/test artifacts plus job timing metrics so we can continuously harden the runtime.

| Stage | What it checks | Artifacts | Commands |
| --- | --- | --- | --- |
| **Validate Code Style & Specs** | Spotless formatting, OpenAPI linting, PlantUML diagram validation, npm helper health | lint-reports, openapi-spec, plantuml-diagrams | `npm run lint`, `npm run lint:openapi`, `npm run diagrams:check` |
| **Test (matrix: JVM & Native)** | JVM tests with JaCoCo 80% line/branch coverage gate + native profile verification | coverage-reports-jvm, test-results-jvm, native-build-info | `npm run test` (runs `./mvnw verify jacoco:report`), `./mvnw verify -Pnative` |
| **Integration (Failsafe profile)** | Quarkus ITs against shipping mocks via `-Pintegration-tests` | integration-test-results | `./mvnw verify -Pintegration-tests` |
| **Admin SPA (conditional)** | Future Vue admin lint/test when `modules/core-platform/src/main/webui/` exists | - | `npm run lint` / `npm test` inside SPA workspace |
| **SonarCloud** | Static analysis + duplicate coverage verification with blocking quality gate | - | `./mvnw verify` + `sonar-maven-plugin` |
| **Docker Build (opt-in)** | Pushes container images when `vars.DOCKER_ENABLED` is true | - | `docker buildx build` |

### Local Quality Gate Checklist

```bash
# Format + lint backend code
npm run lint

# JVM tests with JaCoCo 80% enforcement
npm run test

# Integration profile (failsafe ITs)
./mvnw verify -Pintegration-tests

# Optional native profile tests (slow)
npm run test:native

# Validate published specs and diagrams
npm run lint:openapi
npm run diagrams:check

# Regenerate diagram images after edits
npm run diagrams:generate
```

> **Tip:** The pipeline uses `tools/plantuml.jar` so the same commands work locally and in CI. Run `act pull_request` if you want a dry-run of the workflow before pushing.

### CI Artifacts & Caching Strategy

**Build Artifacts:**

The CI pipeline uploads several artifact types to enable debugging, compliance auditing, and downstream processes:

| Artifact | Retention | Purpose | Job |
| --- | --- | --- | --- |
| `openapi-spec` | 90 days | OpenAPI YAML specs enforcing spec-first development | validate |
| `plantuml-diagrams` | 90 days | PlantUML source + rendered PNG diagrams | validate |
| `lint-reports` | 14 days | Spotless formatting violation reports | validate |
| `coverage-reports-jvm` | 30 days | JaCoCo HTML reports + jacoco.exec for SonarCloud | test (jvm) |
| `test-results-jvm` | 30 days | Surefire XML test reports | test (jvm) |
| `integration-test-results` | 14 days | Failsafe XML + Quarkus logs for integration profile | integration-tests |
| `native-build-info` | 7 days | GraalVM native executable metadata | test (native) |
| `performance-test-results` | 30 days | k6 load test JSON + Lighthouse CI reports | performance-test |
| `lighthouse-reports` | 14 days | Lighthouse performance budgets | lighthouse-performance |
| `dependency-check-report` | 30 days | OWASP vulnerability scan HTML | security-scan |
| `trivy-scan-results` | 30 days | Container vulnerability SARIF | security-scan |

**Cache Layers:**

The workflow uses GitHub Actions cache to speed up builds and reduce external API calls:

- **Maven repository cache:** Automatically cached by `actions/setup-java@v4` with `cache: 'maven'` (JVM jobs)
- **GraalVM Maven cache:** Cached by `graalvm/setup-graalvm@v1` with `cache: 'maven'` (native builds)
- **npm dependency cache:** Cached by `actions/setup-node@v4` with `cache: 'npm'` for root and SPA workspaces
- **SonarCloud analysis cache:** Explicit cache at `~/.sonar/cache` using `actions/cache@v4` to avoid re-analyzing unchanged files
- **Docker layer cache:** BuildKit GHA cache (`type=gha`) for Docker image builds reduces native image rebuild time

**Cache Warming for GraalVM:**

Native builds benefit from warm caches when dependencies haven't changed:

1. First run: Downloads all Maven dependencies + GraalVM SDK (~5-10 min overhead)
2. Subsequent runs: Restores cached `.m2/repository` + GraalVM components (~30s overhead)
3. Cache key: Based on `pom.xml` hash, automatically invalidates when dependencies change
4. Concurrency control: `cancel-in-progress: true` prevents cache pollution from stale runs

**Local cache simulation:**

```bash
# Pre-warm Maven cache before native build
./mvnw dependency:go-offline

# Native build with warm cache (saves ~5-8 minutes)
./mvnw package -Pnative
```

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

   > **Note:** The bootstrap script automatically calls `scripts/dev/tenant_seed.sh` to load sample data. See [Managing Sample Data](#managing-sample-data) below for manual seeding options.

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
   # Using the tenant seed script (recommended)
   ./scripts/dev/tenant_seed.sh --catalog --consignment

   # Or manually via psql:
   psql -h localhost -U appuser -d storefront_dev -f tools/scripts/sample_catalog_loader.sql
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

**Module Inventory:**

| Module | Artifact ID | Purpose | Key Dependencies |
|--------|-------------|---------|------------------|
| Parent | `village-storefront-parent` | Aggregator and shared config | Quarkus BOM, Spotless, JaCoCo, OWASP |
| Core Platform | `core-platform` | Tenant gateway, identity, catalog, orders | Quarkus REST, Panache, Stripe, AWS S3, Quinoa |

> Module audit: RESTEasy Reactive, Panache, Scheduler, Stripe SDK, and AWS S3 integration (`quarkus-amazon-s3` + AWS SDK) are all wired into `modules/core-platform`, satisfying the Standard Kit requirements.

**Maven Profiles:**

| Profile | Activation | Purpose | Key Properties |
|---------|------------|---------|----------------|
| `dev` | Default | Local development with debugging | `quarkus.hibernate-orm.log.sql=true`, Dev UI enabled |
| `test` | `-Ptest` or `-Dquarkus.profile=test` | CI/CD test execution | SQL logging disabled, Dev UI disabled |
| `prod` | `-Pprod` or `-Dquarkus.profile=prod` | Production deployments | `quarkus.log.level=INFO`, optimized settings |
| `native` | `-Pnative` or `-Dnative` | GraalVM native executable builds | `quarkus.native.enabled=true`, requires GraalVM |

**Build Standards Compliance:**

The project fully complies with [VillageCompute Java Project Standards](docs/java-project-standards.adoc):

- ✅ Java 21 with Maven multi-module structure
- ✅ Quarkus 3.20.0 with all required extensions (RESTEasy Reactive, Panache, Scheduler, AWS S3, Stripe SDK, Kubernetes, Mailer, OpenTelemetry, etc.)
- ✅ Spotless formatter + JaCoCo 80% coverage enforcement
- ✅ OWASP Dependency-Check for vulnerability scanning
- ✅ GraalVM native image support via `native` profile

**Verify build compliance:**
```bash
# Run all quality gates (Spotless + JaCoCo + tests)
./mvnw clean verify

# Check code formatting
./mvnw spotless:check

# Enforce 80% coverage threshold
./mvnw jacoco:check
```

See [Test Strategy](docs/quality/test_strategy.md) for complete module inventory, extension list, coverage expectations, and compliance verification details.

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
# Start all core services (default)
cd docker && docker compose up -d

# Start with optional services
docker compose --profile payments up -d              # Add Stripe CLI webhook forwarder
docker compose --profile observability up -d         # Add Jaeger tracing
docker compose --profile payments --profile observability up -d  # Both profiles

# View service logs
docker compose logs -f [postgres|minio|mailhog|usps-mock|ups-mock|fedex-mock]

# View all mock service logs
docker compose logs -f usps-mock ups-mock fedex-mock

# Stop all services (keeps volumes/data)
docker compose down

# Stop and remove volumes (destroys data)
docker compose down -v

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

### Managing Sample Data

The project includes a dedicated tenant seed script (`scripts/dev/tenant_seed.sh`) for flexible management of sample data in your local development environment.

#### Using the Tenant Seed Script

**Basic usage (load only tenants and admin users):**
```bash
./scripts/dev/tenant_seed.sh
```

**Load all sample data (recommended for full development):**
```bash
./scripts/dev/tenant_seed.sh --catalog --consignment
```

**Available options:**
- `--catalog` - Load sample catalog data (products, variants, categories, inventory)
- `--consignment` - Load consignment data (consignors, payout ledgers)
- `--reset` - Drop and recreate database (WARNING: destructive)
- `--help` - Show usage information

#### Sample Data Contents

When you run `./scripts/dev/tenant_seed.sh --catalog --consignment`, you'll get:

**Tenants:**
- `techgadgets` (ID: a0000000-0000-0000-0000-000000000001)
- `artisancrafts` (ID: a0000000-0000-0000-0000-000000000002)

**Staff Login Credentials (password: `changeme123!`):**
- `owner@techgadgets.local` (Store Owner role)
- `staff@techgadgets.local` (Staff role)
- `owner@artisancrafts.local` (Store Owner role)

**Catalog Data:**
- 4 categories (Electronics, Smartphones, Audio, Accessories)
- 3 products with 7 variants
- 8 inventory records across multiple locations

**Consignment Data:**
- 3 consignors across 2 tenants
- Payout ledger entries with test balances
- Mobile Accessories Hub: $342.75 available for payout testing

#### Cleanup and Reset Procedures

**Option 1: Reset database with fresh schema (destroys all data):**
```bash
# Stop services and remove volumes
cd docker && docker compose down -v

# Start services
docker compose up -d

# Wait for PostgreSQL
./scripts/dev/wait-for-postgres.sh

# Run migrations to create fresh schema
./mvnw -pl modules/core-platform flyway:migrate

# Reload sample data
./scripts/dev/tenant_seed.sh --catalog --consignment
```

**Option 2: Use the seed script's reset mode (WARNING: destructive):**
```bash
# This drops and recreates the database
./scripts/dev/tenant_seed.sh --reset

# Then run migrations
./mvnw -pl modules/core-platform flyway:migrate

# Then reload data
./scripts/dev/tenant_seed.sh --catalog --consignment
```

**Option 3: Manual cleanup via psql:**
```bash
# Connect to database
psql -h localhost -U appuser -d storefront_dev

# Drop specific tables or truncate data
TRUNCATE TABLE products, product_variants, inventory CASCADE;
TRUNCATE TABLE consignors, consignment_items, consignment_payout_ledger CASCADE;

# Or drop all tenant data
DELETE FROM tenants WHERE subdomain IN ('techgadgets', 'artisancrafts');
```

#### Environment Variables

The seed script reads configuration from your `.env` file:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_NAME` | storefront_dev | PostgreSQL database name |
| `DB_USER` | appuser | Database user |
| `DB_PASSWORD` | apppass | Database password |
| `DB_PORT` | 5432 | PostgreSQL port |

**Required Tools:**
- `psql` (PostgreSQL client) - Install via `brew install postgresql` (macOS) or `apt install postgresql-client` (Ubuntu)
- Docker Compose (for running PostgreSQL)

### Local Media Pipeline Flow

The platform includes a complete media ingestion and processing pipeline for images and videos. In local development, this uses MinIO (S3-compatible storage) and processes media asynchronously via background jobs.

#### Architecture Overview

```
Upload Request → Presigned URL → Client Upload → Completion Hook → Background Job → Derivatives
     ↓              ↓                  ↓                ↓                  ↓              ↓
 Negotiate     Generate URL      Direct to S3    Persist metadata   FFmpeg/Thumbnailator  Store variants
   Quota         (15 min TTL)      (bypass app)    + Enqueue job    (resize, transcode)   Update quota
```

#### Local Development Stack

**Storage:** MinIO (started via `docker/docker-compose.yaml`)
- Endpoint: `http://localhost:9000`
- Console: `http://localhost:9001` (credentials in `.env`)
- Bucket: `village-storefront-media` (auto-created by bootstrap script)

**Processing:**
- **Images:** Thumbnailator generates 4 derivatives (thumbnail 150px, small 400px, medium 800px, large 1600px)
- **Videos:** FFmpeg creates HLS variants (720p, 480p, 360p) + poster frame (requires FFmpeg binary installed)

#### Media Upload Flow (End-to-End)

**1. Upload Negotiation (POST `/api/v1/media/upload/negotiate`)**

Client requests presigned upload URL:

```bash
curl -X POST http://localhost:8080/api/v1/media/upload/negotiate \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: a0000000-0000-0000-0000-000000000001" \
  -d '{
    "filename": "product-photo.jpg",
    "contentType": "image/jpeg",
    "fileSize": 524288,
    "assetType": "image"
  }'
```

Response includes:
- `assetId`: UUID for tracking
- `storageKey`: Tenant-scoped path (`{tenant}/media/image/{assetId}/original/{hash}_{filename}`)
- `presignedUrl`: Direct upload URL (valid 15 minutes)
- `expiresAt`: URL expiration timestamp
- `remainingQuotaBytes`: Tenant quota remaining

**Security Enforcements:**
- Tenant ID prefix in storage key (prevents cross-tenant access)
- Hashed filename component (SHA-256 truncated to 12 chars for cache-busting)
- Size validation (rejects zero/negative sizes)
- MIME type validation (only `image/*` and `video/*` allowed)
- Quota enforcement (returns 413 if tenant quota exceeded)

**2. Client Upload to Presigned URL (PUT to S3)**

Client uploads directly to MinIO/R2 (bypasses application server):

```bash
curl -X PUT "{presignedUrl}" \
  -H "Content-Type: image/jpeg" \
  --upload-file product-photo.jpg
```

**3. Upload Completion (POST `/api/v1/media/{assetId}/complete`)**

Client notifies platform that upload succeeded:

```bash
curl -X POST http://localhost:8080/api/v1/media/{assetId}/complete \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: a0000000-0000-0000-0000-000000000001" \
  -d '{"checksumSha256": "abc123..."}'
```

This triggers:
- Metadata persistence (`MediaAsset` record created with status `pending`)
- Quota usage tracking (original file size added to tenant quota)
- Background job enqueue (`MediaJobService.enqueueProcessingJob()`)
- Priority assignment (DEFAULT for images, LOW for videos)

**4. Background Processing (`MediaJobService` scheduled dispatcher)**

Workers poll the queue every 3 seconds (configurable via `media.processing.dispatch-interval`):

```
Job Dispatcher → Download from S3 → Process (FFmpeg/Thumbnailator) → Upload derivatives → Update status
     ↓                ↓                        ↓                              ↓                  ↓
Poll queue      Temp file            Generate variants            S3 PUT (hashed keys)    Mark 'ready'
(priority)    (cleanup on fail)     (width/height/duration)     (quotas updated)        (emit metrics)
```

**Processing Details:**
- **Images:** 4 JPEG derivatives, metadata extraction (width/height), WebP conversion (future)
- **Videos:** HLS master playlist + 3 variants (720p/480p/360p), MPEG-TS segments, poster frame JPEG
- **Quotas:** Derivative sizes added to tenant usage
- **Failures:** Asset marked `failed`, error logged, retry via DelayedJob pattern

**5. Signed Download URLs (GET `/api/v1/media/{assetId}/download`)**

Client requests time-limited download URL:

```bash
curl http://localhost:8080/api/v1/media/{assetId}/download \
  -H "X-Tenant-ID: a0000000-0000-0000-0000-000000000001"
```

Response:
- `url`: Presigned download URL (valid 24 hours, configurable via `media.signed-url.expiry-hours`)
- `expiresAt`: URL expiration timestamp
- `remainingAttempts`: Download attempts left (default 5, configurable via `media.signed-url.max-download-attempts`)

**Security Features:**
- Download attempt tracking (prevents abuse)
- Access logging (`MediaAccessLog` table with signature version)
- Tenant isolation (cannot generate URLs for other tenants' assets)
- TTL enforcement (URLs auto-expire)

#### Queue Priority Logic

Media jobs use differentiated priorities based on asset type and tenant needs:

| Asset Type | Default Priority | Rationale | Target SLA |
|------------|-----------------|-----------|------------|
| Image | DEFAULT | Interactive use (product photos), moderate processing time (~5-15s) | < 30s |
| Video | LOW | Batch workload, heavy processing time (5-30+ min for HLS transcoding) | < 5m |
| Critical Override | CRITICAL | Urgent assets (homepage hero images, flash sales) via manual API flag | < 1s |

**Configuration:**
- Queue capacities: CRITICAL (50), HIGH (200), DEFAULT (500), LOW (250)
- Retry policies: CRITICAL (aggressive), others (default exponential backoff)
- Worker scaling: Independent HPA per priority via `media_processing_queue_depth` metric

#### Monitoring & Metrics

**Prometheus Metrics (emitted by `MediaService` and `MediaJobService`):**

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `media.upload.negotiate` | Counter | tenant, type | Upload negotiation requests |
| `media.upload.completed` | Counter | tenant, type | Successful upload completions |
| `media.quota.exceeded` | Counter | tenant | Quota enforcement rejections |
| `media.job.enqueued` | Counter | tenant, priority | Jobs added to queue |
| `media.job.success` | Counter | tenant, type | Successful processing jobs |
| `media.job.failed` | Counter | tenant, type | Failed processing jobs |
| `media.download.issued` | Counter | tenant, type | Signed download URLs generated |
| `media_processing_queue_depth` | Gauge | priority | Current queue depth per priority |

**Example Queries:**

```promql
# Upload success rate (last 5 minutes)
rate(media.upload.completed[5m]) / rate(media.upload.negotiate[5m])

# Queue depth by priority
media_processing_queue_depth{priority="default"}

# Processing failure rate
rate(media.job.failed[5m]) / rate(media.job.success[5m] + media.job.failed[5m])
```

#### Local Testing Tips

**1. Verify MinIO Connectivity:**
```bash
# List buckets (should include village-storefront-media)
aws --endpoint-url=http://localhost:9000 s3 ls

# Inspect media objects
aws --endpoint-url=http://localhost:9000 s3 ls s3://village-storefront-media/
```

**2. Trigger Manual Processing:**
```bash
# Complete an upload, then drain queue synchronously
curl -X POST http://localhost:8080/api/v1/media/{assetId}/complete \
  -H "X-Tenant-ID: {tenant}" -d '{}'

# Check asset status (should be 'pending' → 'processing' → 'ready')
curl http://localhost:8080/api/v1/media/{assetId} -H "X-Tenant-ID: {tenant}"
```

**3. Inspect Job Logs:**
```bash
# Watch processing logs
./mvnw quarkus:dev | grep "Processing media job"

# Check for errors
./mvnw quarkus:dev | grep "Failed to process media asset"
```

**4. Test Quota Enforcement:**
```bash
# Set low quota for tenant via psql
psql -h localhost -U appuser -d storefront_dev -c \
  "UPDATE media_quotas SET quota_bytes = 1024 WHERE tenant_id = '{tenant}';"

# Attempt oversized upload (should return 413)
curl -X POST http://localhost:8080/api/v1/media/upload/negotiate \
  -H "X-Tenant-ID: {tenant}" -d '{"filename":"huge.mp4","contentType":"video/mp4","fileSize":1048576,"assetType":"video"}'
```

#### Production Differences

When deploying to production with Cloudflare R2:

1. **Storage Client:** `R2MediaStorageClient` replaces `StubMediaStorageClient` (auto-wired via Quarkus profile)
2. **Signed URLs:** Use Cloudflare R2 presigned URL format (S3-compatible with auth v4 signatures)
3. **Worker Pods:** Separate Kubernetes deployments per priority queue with HPAs watching `media_processing_queue_depth`
4. **FFmpeg:** Containerized with resource limits (CPU/memory quotas prevent runaway transcoding)
5. **Monitoring:** Grafana dashboards track queue depth, SLA compliance, quota usage trends

**Environment Variables (`.env` → Kubernetes ConfigMap):**
```bash
R2_ENDPOINT_URL=https://{account_id}.r2.cloudflarestorage.com
R2_ACCESS_KEY={cloudflare_r2_access_key}
R2_SECRET_KEY={cloudflare_r2_secret_key}
R2_BUCKET_NAME=village-storefront-media
R2_REGION=auto
```

See `docs/architecture/background_jobs.md` for queue architecture details and `docs/operations/media_runbook.md` for production incident response procedures.

#### Troubleshooting

**Error: "psql: connection refused"**
- Ensure Docker Compose services are running: `docker compose ps`
- Start services: `cd docker && docker compose up -d`
- Wait for PostgreSQL: `./scripts/dev/wait-for-postgres.sh`

**Error: "relation does not exist"**
- Run database migrations first: `./mvnw -pl modules/core-platform flyway:migrate`
- Or start Quarkus (migrations run automatically): `npm run dev`

**Error: "duplicate key value violates unique constraint"**
- Data may already exist. Options:
  - Use `--reset` flag to drop and recreate database
  - Skip seeding: bootstrap already loaded data
  - Manually delete conflicting records via psql

**Data not visible after seeding:**
- Verify tenant context in your queries: `SELECT * FROM tenants;`
- Check RLS policies if accessing via application
- Ensure you're using the correct database: `\c storefront_dev` in psql

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

3. **Integration Stage (Failsafe profile)** (~8-12 min):
   - Quarkus integration tests via `./mvnw verify -Pintegration-tests`
   - Uses shipping carrier mock services for deterministic API calls
   - Publishes `integration-test-results` artifact for debugging

4. **Quality Gate** (~5-8 min):
   - SonarCloud analysis
   - Coverage enforcement (80%)
   - Security vulnerability scan

5. **Docker Build** (~15-20 min, main/beta only):
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

# Run integration profile (failsafe ITs)
./mvnw verify -Pintegration-tests

# Lint OpenAPI spec
npm run lint:openapi

# Full local CI simulation (requires ~15 minutes)
./mvnw spotless:check && \
  npm run lint && \
  npm run lint:openapi && \
  ./mvnw verify && \
  ./mvnw verify -Pintegration-tests && \
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

- **`x-tenant-scope`**: `none`, `required`, or `optional` (indicates tenant resolution requirement)
- **`x-feature-flags`**: Array of feature flag keys that must be enabled for this operation
- **`x-rate-limit`**: Rate limiting metadata (limit, window, scope)
- **`x-required-scopes`**: OAuth scopes or permission keys that must be granted to call the operation

Example:
```yaml
/api/v1/admin/products:
  get:
    summary: List products
    x-tenant-scope: required
    x-feature-flags:
      - catalog.management.enabled
    x-rate-limit:
      limit: 1000
      window: 60s
      scope: user
    x-required-scopes:
      - catalog:read

```

#### Spec Change Workflow

Follow this loop whenever the OpenAPI contract changes:

1. **Edit the spec** at `api/v1/openapi.yaml`, ensuring each operation declares `x-tenant-scope`, `x-feature-flags`, `x-rate-limit`, and `x-required-scopes`.
2. **Lint + diff** – run `npm run lint:openapi` (Spectral) and `npm run openapi:diff` to compare against the previous release artifact.
3. **Format + validate** – execute `node tools/lint.cjs` (Spotless) and `node tools/test.cjs` to keep the Java + contract tests green.
4. **Regenerate SDKs** – use the TypeScript/Java generation commands below so downstream apps stay in sync.
5. **Run contract tests** – execute the Schemathesis or REST-assured workflow below before sending the PR.
6. **Commit spec + generated artifacts**, then push for review.

#### SDK Generation

The OpenAPI specification can be used to generate type-safe client SDKs for consuming the API from external applications.

**Generate TypeScript SDK:**

```bash
# Install OpenAPI Generator
npm install -g @openapitools/openapi-generator-cli

# Generate TypeScript axios client
openapi-generator-cli generate \
  -i api/v1/openapi.yaml \
  -g typescript-axios \
  -o generated/typescript-client \
  --additional-properties=npmName=@villagecompute/storefront-client,npmVersion=1.0.0

# Use in your application
cd generated/typescript-client
npm install
npm run build
```

**Generate Java SDK:**

```bash
# Generate Java client using Quarkus REST Client
openapi-generator-cli generate \
  -i api/v1/openapi.yaml \
  -g java \
  -o generated/java-client \
  --library microprofile \
  --additional-properties=groupId=com.villagecompute,artifactId=storefront-client,artifactVersion=1.0.0

cd generated/java-client
mvn clean install
```

**Integration with CI:**

To keep SDKs up-to-date, add SDK generation to your CI pipeline:

```yaml
# .github/workflows/ci.yml (add to publish stage)
- name: Generate and publish SDK
  if: github.ref == 'refs/heads/main'
  run: |
    npm run generate:sdk:typescript
    npm run generate:sdk:java
    # Publish to package registry
```

#### Contract Testing

Contract tests verify that the backend implementation matches the OpenAPI specification.

**Using Schemathesis (Python):**

```bash
# Install schemathesis
pip install schemathesis

# Run contract tests against dev server
./mvnw quarkus:dev &
sleep 5  # Wait for server to start

schemathesis run \
  api/v1/openapi.yaml \
  --base-url http://localhost:8080/api/v1 \
  --checks all \
  --hypothesis-max-examples=50

# Stop dev server
kill %1
```

**Using REST-assured (Java):**

Add contract validation to integration tests:

```java
import io.restassured.module.jsv.JsonSchemaValidator;

@QuarkusTest
public class OpenAPIContractTest {

    @Test
    public void testProductListMatchesSpec() {
        given()
            .auth().oauth2(getAdminToken())
            .when()
            .get("/api/v1/catalog/products")
            .then()
            .statusCode(200)
            .body(matchesJsonSchemaInClasspath("schemas/ProductListResponse.json"));
    }
}
```

**Integration with CI:**

```yaml
# .github/workflows/ci.yml
- name: Contract tests
  run: |
    ./mvnw quarkus:dev &
    sleep 10
    schemathesis run api/v1/openapi.yaml --base-url http://localhost:8080/api/v1
    kill %1
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
./mvnw verify -Pintegration-tests

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

### For Developers

- **[Developer Guide](docs/architecture/developer-guide.md)** - Setup, project structure, coding standards, testing strategy, debugging
- **[Java Standards](docs/java-project-standards.adoc)** - Technology stack, build system, code quality requirements
- **[ADR Index](docs/adr/ADR-README.md)** - Architectural Decision Records registry and template
  - [ADR-001: Multi-Tenancy](docs/adr/ADR-001-tenancy.md)
  - [ADR-002: CI/CD Quality Gates](docs/adr/ADR-002-quality-gates.md)
  - [ADR-003: Checkout Saga](docs/adr/ADR-003-checkout-saga.md)
  - [ADR-004: Consignment Payouts](docs/adr/ADR-004-consignment-payouts.md)

### For Operations

- **[Runbook Index](docs/architecture/ops/runbook-index.md)** - Central directory of all operational runbooks
- **[Hypercare Plan](docs/architecture/ops/hypercare-plan.md)** - First 30 days post-launch monitoring and incident response
- **[Observability Dashboard Guide](docs/architecture/ops/observability-dashboard.md)** - Using Grafana, Prometheus, Jaeger for monitoring and debugging
- **[Observability Framework](docs/operations/observability.md)** - Implementation details for logging, tracing, metrics
- **[Deployment Architecture](docs/architecture/ops/deployment-architecture.md)** - Kubernetes setup, networking, infrastructure
- **[Release Runbook](docs/architecture/ops/release-runbook.md)** - Release process, feature flags, rollback procedures

### Component-Specific Runbooks

- **[Catalog & Inventory](docs/operations/catalog_inventory_runbook.md)** - Product catalog, inventory management
- **[Payments](docs/operations/payments_runbook.md)** - Stripe integration, refunds, webhooks
- **[Consignment](docs/operations/consignment_runbook.md)** - Vendor payouts, ledger reconciliation
- **[Media Pipeline](docs/operations/media_runbook.md)** - FFmpeg transcoding, R2 storage
- **[Background Jobs](docs/operations/job_runbook.md)** - Job queue management, delayed job processing
- **[Disaster Recovery](docs/operations/dr_playbook.md)** - Backup/restore, PITR, catastrophic failure recovery

### Architecture & Design

- **[Architecture Overview](docs/architecture_overview.md)** - System design, data model, deployment architecture
- **[Component Diagram](docs/diagrams/component_overview.puml)** ([PNG](docs/diagrams/component_overview.png)) - Layered modular monolith architecture showing all modules, worker pods, integrations (Stripe, carriers, R2, FFmpeg, GitHub Actions, Kubernetes), queues, feature flags, and observability toolchain
- **[Storefront Theming](docs/storefront-theming.md)** - Tailwind, design tokens, localization
- **[Feature Flag Governance](docs/feature_flags/governance.md)** - Kill switches, rollout process, flag lifecycle
- **[API Spec](api/v1/openapi.yaml)** - OpenAPI specification
- **[Diagrams](docs/diagrams/)** - PlantUML source files (system context, containers, components, ERD)

## License

UNLICENSED - Proprietary software for VillageCompute internal use.

## Support

- **GitHub Issues:** https://github.com/teacurran/village-storefront/issues
- **Architecture Team:** Contact via Slack #village-storefront
- **Documentation:** See `docs/` directory

---

**Built with ❤️ by VillageCompute**
