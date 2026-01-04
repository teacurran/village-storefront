# Village Storefront API Specifications

This directory contains the authoritative OpenAPI specifications for the Village Storefront platform.

## Directory Structure

```
api/
└── v1/
    └── openapi.yaml       # OpenAPI 3.0.3 specification for API v1
```

## OpenAPI Specification

The canonical API specification is maintained in `api/v1/openapi.yaml`. This file defines:

- **Authentication schemes**: JWT bearer tokens and API keys (per ADR-001)
- **Endpoint definitions**: Organized by tags (Storefront, Admin, Headless, Platform)
- **Reusable components**: Schemas (Money, Address, Pagination, ProblemDetails)
- **Parameters**: Idempotency keys, pagination, tenant domain overrides
- **Error formats**: RFC 7807 Problem Details for all error responses

### API Tags

| Tag | Description | Primary Users |
|-----|-------------|---------------|
| **System** | Health checks, metadata | Infrastructure, monitoring |
| **Authentication** | Login, token refresh, logout | All clients |
| **Storefront** | Product browsing, cart, checkout | Customer-facing storefronts |
| **Admin** | Product/order management | Store administrators |
| **Headless** | API-first commerce operations | Custom frontends, mobile apps |
| **Platform** | Tenant management, billing | Platform administrators |

## Validation

The spec is validated using [Spectral](https://stoplight.io/open-source/spectral) with the OpenAPI ruleset.

### Lint the specification

```bash
# From project root
npx @stoplight/spectral-cli lint api/v1/openapi.yaml
```

**Requirement:** The spec must pass Spectral validation (0 errors) before merging to main branch.

### Current lint status

✅ Passes validation (1 warning about unused `TenantDomain` parameter - acceptable)

## Synchronization with Runtime

The Quarkus runtime serves the OpenAPI spec at runtime from `src/main/resources/openapi/api.yaml`.

### Sync Process

**Option 1: Manual Copy (Temporary)**

```bash
# Copy authoritative spec to Quarkus resources directory
cp api/v1/openapi.yaml src/main/resources/openapi/api.yaml
```

**Option 2: Build-Time Sync (Recommended for production)**

Add Maven resource filtering to `pom.xml` to automatically copy `api/v1/openapi.yaml` to `src/main/resources/openapi/api.yaml` during build:

```xml
<build>
  <resources>
    <resource>
      <directory>api/v1</directory>
      <includes>
        <include>openapi.yaml</include>
      </includes>
      <targetPath>${project.build.directory}/classes/openapi</targetPath>
      <filtering>false</filtering>
    </resource>
  </resources>
</build>
```

**Option 3: Symbolic Link (Development only)**

```bash
# Warning: May not work on all platforms/CI systems
ln -sf $(pwd)/api/v1/openapi.yaml src/main/resources/openapi/api.yaml
```

### Verification

After syncing, verify the spec is accessible in dev mode:

```bash
# Start Quarkus dev mode
./mvnw quarkus:dev

# Access Swagger UI
open http://localhost:8080/q/swagger-ui

# Or fetch raw OpenAPI JSON
curl http://localhost:8080/q/openapi
```

## Spec-First Development Workflow

This project follows a **spec-first** API design approach:

1. **Design phase**: Update `api/v1/openapi.yaml` with new endpoints/schemas
2. **Validate**: Run `npx @stoplight/spectral-cli lint api/v1/openapi.yaml`
3. **Generate types** (future): Use `openapi-generator` to generate DTOs from schemas
4. **Implement**: Write JAX-RS resources matching `operationId` values
5. **Sync**: Copy spec to `src/main/resources/openapi/api.yaml`
6. **Test**: Verify endpoints match spec using contract testing

### Benefits

- **API contracts defined before implementation** → prevents API drift
- **Client SDK generation** → consistent types across Java, TypeScript, etc.
- **API documentation** → always up-to-date (spec = docs)
- **Breaking change detection** → tooling can compare spec versions

## References

- **Architecture**: `docs/architecture_overview.md#section-5-api-contracts`
- **Tenancy**: `docs/adr/ADR-001-tenancy.md` (tenant resolution, JWT claims)
- **Standards**: `docs/java-project-standards.adoc` (OpenAPI conventions)

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-01-02 | Initial OpenAPI baseline with auth, catalog, checkout, platform admin placeholders |

---

**Maintained by:** Architecture Team
**Review frequency:** Updated per iteration as new endpoints are implemented
