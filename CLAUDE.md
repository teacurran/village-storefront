# Village Storefront

A SaaS ecommerce platform built with Java Quarkus for VillageCompute.

## Project Overview

Village Storefront is a multi-tenant ecommerce platform that allows merchants to create and manage their own online stores. Each store is accessible via subdomain (storename.platform.com) or custom domain. The platform supports physical products with variants, digital products, consignment vendor management, and integrated payment processing.

## Project Standards

This project follows the VillageCompute Java Project Standards. See `docs/java-project-standards.adoc` for the complete reference.

### Key Requirements

- **Java 21** (LTS) minimum
- **Quarkus** framework
- **Maven** build system
- **PostgreSQL** database
- **MyBatis Migrations** for schema changes
- **Spotless** for code formatting
- **JaCoCo** with 80% code coverage requirement (enforced by SonarCloud)
- **OpenAPI spec-first** REST API design
- **Qute templates** for customer-facing storefront (all paths except `/admin/*`)
- **Vue.js 3** with Quinoa for admin dashboard (`/admin/*` only)

### Build Commands

```bash
# Compile
./mvnw compile

# Run tests
./mvnw test

# Run tests with coverage report
./mvnw test jacoco:report

# Apply code formatting
./mvnw spotless:apply

# Run development server
./mvnw quarkus:dev

# Run migrations
cd migrations && mvn migration:up -Dmigration.env=development
```

### Package Structure

```
src/main/java/villagecompute/storefront/
├── api/
│   ├── rest/         # REST resources
│   └── types/        # API DTOs (generated from OpenAPI)
├── config/           # Configuration classes
├── data/
│   ├── models/       # JPA entities
│   └── repositories/ # Data access layer
├── exceptions/       # Custom exceptions
├── integration/      # External service integrations (Stripe, shipping)
├── jobs/             # Background jobs and handlers
├── services/         # Business logic
└── util/             # Utilities
```

### Multi-Tenancy

- Each store is a tenant identified by subdomain or custom domain
- Tenant resolution happens via HTTP request headers
- All data is tenant-scoped in the database

### Authentication & Sessions

- **JWT tokens**: Stateless auth with short-lived access + refresh tokens
- **Session logging**: All login activity written to database for reporting
- **Impersonation**: Platform admins can impersonate store users (all actions logged)
- **No Redis**: Caffeine in-memory caching, JWT eliminates session storage need

### Code Quality Requirements

- **Coverage**: 80% line and branch coverage (enforced by SonarCloud quality gate)
- **Defects**: 0 bugs, 0 vulnerabilities (APPI quality profile)
- **Formatting**: Spotless with Eclipse formatter (run `./mvnw spotless:apply`)
- **CI**: All PRs must pass SonarCloud quality gate before merge

### Code Standards

- All exceptions extend `RuntimeException` (no throws declarations needed)
- All JSON data marshalled through defined Type classes (no direct `JsonNode` traversal)
- Named queries use constants with `QUERY_` prefix
- Use `Parameters.with()` for type-safe parameter binding
- Line length: 120 characters
- Indentation: 4 spaces for Java, 2 spaces for XML/YAML/JSON

### Deployment

- **Native compilation**: GraalVM for native executables (~50-100MB containers, <100ms cold start)
- **Kubernetes**: Quarkus Kubernetes extension generates manifests
- **Target**: k3s cluster
- **Container**: Distroless or Alpine-based minimal image

```bash
# Build native image
./mvnw package -Pnative

# Build container
./mvnw package -Pnative -Dquarkus.container-image.build=true

# Generate K8s manifests
./mvnw package -Dquarkus.kubernetes.deploy=true
```

### Local Development

```bash
# Start local services (PostgreSQL, Mailpit, Jaeger)
docker-compose up -d

# Run migrations
cd migrations && mvn migration:up -Dmigration.env=development

# Start Quarkus in dev mode
./mvnw quarkus:dev
```
