# Village Storefront Developer Guide

**Version:** 1.0
**Last Updated:** 2026-01-10
**Audience:** Backend Engineers, Full-Stack Engineers, QA Engineers

---

## Table of Contents

1. [Getting Started](#getting-started)
2. [Project Structure & Modules](#project-structure--modules)
3. [Coding Standards & Patterns](#coding-standards--patterns)
4. [Testing Strategy](#testing-strategy)
5. [Development Workflow](#development-workflow)
6. [Debugging & Troubleshooting](#debugging--troubleshooting)
7. [Contributing Guidelines](#contributing-guidelines)

---

## 1. Getting Started

### Prerequisites

Before you begin, ensure you have:

- **Java 21+** ([Adoptium Temurin](https://adoptium.net/))
- **Node.js 18+** (for npm build tools and admin SPA)
- **Docker** (for local development services)
- **PostgreSQL client** (optional, for manual database access)
- **FFmpeg** (optional, for media processing features)
- **Git** (obviously!)

### One-Command Setup

The fastest way to get a working development environment:

```bash
# Clone repository
git clone https://github.com/teacurran/village-storefront.git
cd village-storefront

# Bootstrap environment (starts Docker services, runs migrations, seeds data)
./scripts/dev/bootstrap.sh

# Start development server with hot reload
npm run dev
```

Visit `http://localhost:8080` - you should see the storefront homepage.

**Default Test Accounts:**
- Tenant: `techgadgets` (subdomain: `techgadgets.localhost:8080`)
- Admin: `owner@techgadgets.local` / `changeme123!`
- Staff: `staff@techgadgets.local` / `changeme123!`

For detailed setup instructions, see [README.md § Quick Start](../../README.md#quick-start).

### Your First Commit

1. **Create feature branch:**
   ```bash
   git checkout -b feature/add-my-feature
   ```

2. **Make changes and verify locally:**
   ```bash
   npm run lint     # Check formatting
   npm test         # Run tests with coverage
   ```

3. **Commit with descriptive message:**
   ```bash
   git commit -m "feat: add product search by category"
   ```

4. **Push and create PR:**
   ```bash
   git push origin feature/add-my-feature
   # Create PR on GitHub - all CI checks must pass
   ```

---

## 2. Project Structure & Modules

### Maven Multi-Module Layout

The project uses a Maven parent-child module structure:

```
village-storefront/
├── pom.xml                          # Parent POM (dependency management)
├── modules/
│   └── core-platform/               # Core platform module
│       ├── pom.xml
│       └── src/
│           ├── main/
│           │   ├── java/villagecompute/storefront/
│           │   ├── resources/
│           │   └── webui/           # Vue.js admin SPA (Quinoa)
│           └── test/
├── docker/                          # Docker Compose services
├── scripts/                         # Dev/ops automation scripts
├── tools/                           # Build tools (PlantUML, sample data)
└── docs/                            # Documentation
```

**Important:** All Maven commands run from the root directory. Use `-pl modules/core-platform` to target the specific module, or omit it to build all modules.

### Package Structure

```
src/main/java/villagecompute/storefront/
├── api/
│   ├── rest/              # REST resources (@Path endpoints)
│   │   ├── catalog/       # Catalog, product, variant resources
│   │   ├── checkout/      # Cart, checkout, order resources
│   │   ├── consignment/   # Consignment, payout resources
│   │   ├── identity/      # Auth, user, tenant resources
│   │   └── ...
│   └── types/             # API DTOs (generated from OpenAPI specs)
│       ├── catalog/
│       ├── checkout/
│       └── ...
├── config/                # Configuration classes (@ConfigMapping, CDI)
│   ├── StripeConfig.java
│   ├── MediaConfig.java
│   └── ...
├── data/
│   ├── models/            # JPA entities (Panache ORM)
│   │   ├── tenant/        # Tenant, CustomDomain
│   │   ├── catalog/       # Product, Variant, Category
│   │   ├── checkout/      # Cart, Order, OrderItem
│   │   └── ...
│   └── repositories/      # Data access layer (optional custom queries)
├── exceptions/            # Custom exceptions (extend RuntimeException)
│   ├── TenantNotFoundException.java
│   ├── CheckoutException.java
│   └── ...
├── integration/           # External service integrations
│   ├── stripe/            # Stripe payment provider
│   ├── shipping/          # Carrier integrations (UPS, FedEx, USPS)
│   └── media/             # R2 storage, FFmpeg transcoding
├── jobs/                  # Background jobs and handlers
│   ├── JobHandler.java    # Job execution interface
│   ├── handlers/          # Specific job implementations
│   │   ├── CatalogImportJobHandler.java
│   │   ├── MediaTranscodeJobHandler.java
│   │   └── ...
│   └── scheduler/         # Quartz scheduler config
├── services/              # Business logic
│   ├── catalog/           # Catalog, inventory services
│   ├── checkout/          # Checkout orchestration, order services
│   ├── consignment/       # Consignment payout, ledger services
│   ├── identity/          # Auth, user, tenant services
│   └── ...
└── util/                  # Utilities
    ├── TenantContext.java # ThreadLocal tenant context
    └── ...
```

### Key Abstractions

#### TenantContext (Multi-Tenancy)

**Purpose:** Provides ThreadLocal access to the current tenant ID for all tenant-scoped operations.

**Usage:**
```java
// Set by TenantRequestFilter on every HTTP request
TenantContext.setCurrentTenant(tenant);

// Access in services, repositories, jobs
UUID tenantId = TenantContext.getCurrentTenantId();

// All queries MUST filter by tenantId
Product.find("tenant_id = ?1 AND sku = ?2", tenantId, sku).firstResult();
```

**Critical Rules:**
- NEVER query entities without filtering by `TenantContext.getCurrentTenantId()`
- All entities MUST extend `TenantAwareEntity` (includes `tenant_id` column)
- Background jobs MUST serialize `tenantId` in payload and restore context before execution

For architecture details, see [ADR-001: Multi-Tenancy Strategy](../adr/ADR-001-tenancy.md).

#### FeatureToggle (Feature Flags)

**Purpose:** Enable/disable features per tenant for progressive rollout and A/B testing.

**Usage:**
```java
@Inject FeatureToggleService featureToggleService;

if (featureToggleService.isEnabled("loyalty_program")) {
    // Execute loyalty logic
}
```

**Configuration:** Feature flags stored in `tenant.settings` JSONB column and cached in Caffeine.

#### BackgroundJob (Async Processing)

**Purpose:** Enqueue jobs for asynchronous execution (media transcoding, catalog imports, payouts).

**Usage:**
```java
@Inject JobService jobService;

// Enqueue job
Job job = jobService.enqueue(
    "catalog:import",
    Map.of("file_url", fileUrl, "tenant_id", tenantId)
);

// Job handler implementation
@ApplicationScoped
public class CatalogImportJobHandler implements JobHandler {
    @Override
    public void handle(Job job) {
        // Restore tenant context from payload
        UUID tenantId = UUID.fromString(job.getPayload().get("tenant_id"));
        TenantContext.setCurrentTenant(tenantRepository.findById(tenantId));

        // Execute import logic
        // ...
    }
}
```

For job architecture, see [Job Runbook](../operations/job_runbook.md).

---

## 3. Coding Standards & Patterns

### Mandatory Standards

For complete reference, see [docs/java-project-standards.adoc](../java-project-standards.adoc).

**Key Requirements:**
- **Java 21** minimum (use records, pattern matching, text blocks)
- **Spotless formatting** (120-char line length, 4-space indent) - run `npm run lint` or `./mvnw spotless:apply`
- **80% code coverage** (enforced by JaCoCo + SonarCloud quality gate)
- **No Lombok** (use Java records for immutable DTOs, write explicit getters/setters)
- **RuntimeException only** (no throws declarations, wrap checked exceptions in RuntimeException)

### Tenant Filtering Pattern (CRITICAL)

**Rule:** Every query MUST filter by tenant ID to prevent cross-tenant data leakage.

**Bad Example (SECURITY VIOLATION):**
```java
// WRONG: Returns products across ALL tenants!
List<Product> products = Product.find("sku = ?1", sku).list();
```

**Good Example:**
```java
// CORRECT: Filters by current tenant
UUID tenantId = TenantContext.getCurrentTenantId();
List<Product> products = Product.find("tenant_id = ?1 AND sku = ?2", tenantId, sku).list();
```

**Enforcement:** Code review checklist includes tenant filtering verification. PostgreSQL RLS policies provide defense-in-depth (queries without tenant_id filter will fail).

### Exception Handling Pattern

**Rule:** All exceptions extend `RuntimeException` (no throws declarations).

**Pattern:**
```java
// Custom exception
public class CheckoutException extends RuntimeException {
    public CheckoutException(String message) {
        super(message);
    }

    public CheckoutException(String message, Throwable cause) {
        super(message, cause);
    }
}

// Usage
if (cart.getItems().isEmpty()) {
    throw new CheckoutException("Cannot checkout empty cart");
}

// Wrap checked exceptions
try {
    stripeClient.charge(paymentIntent);
} catch (StripeException e) {
    throw new PaymentException("Stripe charge failed", e);
}
```

### JSON Data Marshalling Pattern

**Rule:** All JSON data MUST be marshalled through defined Type classes (no direct `JsonNode` traversal).

**Bad Example:**
```java
// WRONG: Direct JsonNode traversal (fragile, no type safety)
JsonNode settings = tenant.getSettings();
String theme = settings.get("theme").asText();
```

**Good Example:**
```java
// CORRECT: Define Type class
public record TenantSettings(
    String theme,
    String locale,
    Map<String, Boolean> features
) {}

// Deserialize to type
ObjectMapper mapper = new ObjectMapper();
TenantSettings settings = mapper.treeToValue(tenant.getSettings(), TenantSettings.class);
String theme = settings.theme();
```

### Named Query Pattern

**Rule:** Use constants with `QUERY_` prefix for named queries.

```java
public class Product extends TenantAwareEntity {
    public static final String QUERY_FIND_BY_SKU = "Product.findBySku";

    @NamedQuery(
        name = QUERY_FIND_BY_SKU,
        query = "SELECT p FROM Product p WHERE p.tenantId = :tenantId AND p.sku = :sku"
    )
    // ...
}

// Usage
Product product = Product.find("#" + Product.QUERY_FIND_BY_SKU,
    Parameters.with("tenantId", tenantId).and("sku", sku)).firstResult();
```

---

## 4. Testing Strategy

### Test Pyramid

```
         ┌──────────────┐
         │  E2E Tests   │  (Playwright/Cypress, critical user flows)
         │   5% tests   │
         └──────┬───────┘
       ┌────────┴────────┐
       │ Integration Tests│  (Quarkus @QuarkusTest, DB + REST)
       │   20% tests      │
       └────────┬─────────┘
    ┌───────────┴──────────┐
    │    Unit Tests        │  (Mockito, business logic isolation)
    │    75% tests         │
    └──────────────────────┘
```

### Unit Tests

**Purpose:** Test business logic in isolation (services, validators, calculators).

**Pattern:**
```java
@ExtendWith(MockitoExtension.class)
class CheckoutOrchestratorTest {

    @Mock InventoryService inventoryService;
    @Mock PaymentService paymentService;
    @InjectMocks CheckoutOrchestrator orchestrator;

    @Test
    void checkout_shouldReserveInventory_whenStockAvailable() {
        // Arrange
        UUID tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(createTestTenant(tenantId));

        Cart cart = createTestCart();
        when(inventoryService.reserve(any(), any())).thenReturn(true);

        // Act
        Order order = orchestrator.checkout(cart);

        // Assert
        assertNotNull(order);
        verify(inventoryService).reserve(tenantId, cart.getItems());
    }
}
```

**Key Practices:**
- Mock external dependencies (Stripe, carrier APIs, R2 storage)
- Set `TenantContext` in test setup
- Test edge cases (empty cart, insufficient inventory, payment failures)

### Integration Tests

**Purpose:** Test full stack (REST → Service → Repository → Database) with real PostgreSQL.

**Pattern:**
```java
@QuarkusTest
@TestHTTPEndpoint(ProductResource.class)
class ProductResourceIT {

    @Test
    @TestSecurity(user = "admin", roles = "ADMIN")
    void createProduct_shouldPersistToDatabase_whenValidRequest() {
        ProductRequest request = new ProductRequest(
            "TEST-SKU-001",
            "Test Product",
            "Description",
            Money.of(BigDecimal.valueOf(29.99), "USD")
        );

        given()
            .header("Host", "techgadgets.localhost")  // Tenant routing
            .contentType(ContentType.JSON)
            .body(request)
        .when()
            .post()
        .then()
            .statusCode(201)
            .body("sku", equalTo("TEST-SKU-001"));

        // Verify database state
        Product product = Product.find("sku", "TEST-SKU-001").firstResult();
        assertNotNull(product);
        assertEquals("Test Product", product.getName());
    }
}
```

**Key Practices:**
- Use `@QuarkusTest` for full CDI + database
- Set tenant via `Host` header
- Use `@TestSecurity` for auth
- Clean up test data in `@AfterEach` (or use transactions)

### E2E Tests

**Purpose:** Test critical user flows in browser (checkout, consignment signup, admin operations).

**Location:** `tests/e2e/playwright/` and `tests/e2e/cypress/`

**Example:**
```typescript
// playwright/checkout.spec.ts
test('checkout flow completes successfully', async ({ page }) => {
  // Navigate to storefront
  await page.goto('http://techgadgets.localhost:8080');

  // Add product to cart
  await page.click('[data-testid="product-card"]');
  await page.click('[data-testid="add-to-cart"]');

  // Checkout
  await page.click('[data-testid="cart-icon"]');
  await page.click('[data-testid="checkout-button"]');

  // Fill shipping
  await page.fill('#shipping-address', '123 Main St');
  await page.fill('#shipping-city', 'San Francisco');
  // ...

  // Assert order confirmation
  await expect(page.locator('[data-testid="order-confirmation"]')).toBeVisible();
});
```

For E2E test details, see implementation in Task I6.T4.

### Coverage Requirements

**Enforced by SonarCloud quality gate:**
- **80% line coverage** (minimum)
- **80% branch coverage** (minimum)
- **0 bugs, 0 vulnerabilities** (APPI quality profile)

**Generate coverage report locally:**
```bash
npm test                    # Runs tests with JaCoCo
open target/site/jacoco/index.html  # View HTML report
```

**If coverage fails:**
1. Identify uncovered lines: `target/site/jacoco/index.html`
2. Add unit tests for uncovered business logic
3. Add integration tests for uncovered REST endpoints
4. Exclude generated code from coverage (e.g., OpenAPI types)

For CI/CD quality gates, see [ADR-002: Quality Gates](../adr/ADR-002-quality-gates.md).

---

## 5. Development Workflow

### Feature Branch Workflow

```
main (protected)
  ↓
  └─→ feature/add-loyalty-program (your branch)
        ├─→ commit 1: feat: add loyalty points entity
        ├─→ commit 2: feat: add points accrual service
        ├─→ commit 3: test: add loyalty service tests
        └─→ PR → CI checks → Code review → Merge to main
```

**Branch Naming:**
- `feature/` - New features
- `fix/` - Bug fixes
- `refactor/` - Code refactoring
- `docs/` - Documentation updates

### Local Quality Checks

**Before pushing, run:**
```bash
# 1. Format code (fixes Spotless violations)
npm run lint

# 2. Run tests with coverage
npm test

# 3. (Optional) Validate OpenAPI specs
npm run lint:openapi

# 4. (Optional) Validate diagrams
npm run diagrams:check
```

**Fix common issues:**
- **Spotless violations:** Run `npm run lint` (auto-fixes formatting)
- **Test failures:** Fix failing tests before pushing
- **Coverage below 80%:** Add tests for uncovered code

### Pull Request Process

1. **Create PR on GitHub** with descriptive title:
   - `feat: add product search by category`
   - `fix: resolve checkout race condition`
   - `refactor: extract shipping adapter interface`

2. **PR template checklist** (auto-populated):
   - [ ] All tests passing locally
   - [ ] Code coverage ≥80%
   - [ ] No Spotless violations
   - [ ] ADR created (if architectural change)
   - [ ] Documentation updated (if user-facing change)
   - [ ] Tenant filtering verified (if data access changes)

3. **CI pipeline stages** (automatic):
   - **VALIDATE:** Formatting, OpenAPI lint, diagram validation (~2 min)
   - **TEST-JVM & TEST-NATIVE:** Unit + integration tests, JaCoCo coverage (~15-30 min)
   - **SONARCLOUD:** Static analysis, 80% coverage gate, security scan (~5-8 min)

4. **Code review:**
   - Request review from domain expert (see [Runbook Index](ops/runbook-index.md) for ownership)
   - Address review feedback
   - Re-request review after changes

5. **Merge:**
   - All CI checks pass ✅
   - 1+ approvals ✅
   - Squash merge to main (keeps history clean)

### Deployment Process

**Production deployments use blue/green strategy with feature flags.**

For deployment details, see:
- [Release Runbook](ops/release-runbook.md) - Release process, feature flags, rollback
- [Deployment Architecture](ops/deployment-architecture.md) - Kubernetes setup, networking

---

## 6. Debugging & Troubleshooting

### Common Issues

#### "TenantContext not available"

**Symptom:** `IllegalStateException: TenantContext not set`

**Cause:** Code executing outside HTTP request context (background job, scheduled task) without tenant context.

**Solution:**
```java
// Background job: Serialize tenant ID in payload, restore before execution
@Override
public void handle(Job job) {
    UUID tenantId = UUID.fromString(job.getPayload().get("tenant_id"));
    Tenant tenant = tenantRepository.findById(tenantId);
    TenantContext.setCurrentTenant(tenant);

    // Now tenant context is available
    // ...
}
```

#### "Coverage below 80%"

**Symptom:** CI fails with "SonarCloud quality gate failed: coverage 75%"

**Solution:**
1. Open JaCoCo report: `target/site/jacoco/index.html`
2. Identify uncovered classes/methods
3. Add unit tests for business logic
4. Add integration tests for REST endpoints
5. Exclude generated code (e.g., OpenAPI types) in `pom.xml`:
   ```xml
   <sonar.coverage.exclusions>
     **/api/types/**,
     **/config/**
   </sonar.coverage.exclusions>
   ```

#### "Spotless violations"

**Symptom:** CI fails with "Spotless check failed: 15 violations"

**Solution:**
```bash
# Auto-fix formatting
npm run lint
# Or directly:
./mvnw spotless:apply

# Commit fixed files
git add -A
git commit --amend --no-edit
git push --force-with-lease
```

#### "Native build fails"

**Symptom:** `./mvnw verify -Pnative` fails with reflection errors

**Cause:** GraalVM native compilation requires reflection configuration for classes loaded dynamically.

**Solution:**
1. Check GraalVM compatibility: [ADR-002: Quality Gates](../adr/ADR-002-quality-gates.md)
2. Add reflection config: `src/main/resources/META-INF/native-image/reflect-config.json`
3. Consult Quarkus native build guide: https://quarkus.io/guides/building-native-image

### Debugging Tools

#### Quarkus Dev UI

**URL:** http://localhost:8080/q/dev

**Features:**
- **Config editor:** View/edit application.properties
- **Health checks:** Database connection, external services
- **OpenAPI:** Interactive Swagger UI
- **Continuous testing:** Auto-run tests on code changes

#### Swagger UI (API Testing)

**URL:** http://localhost:8080/q/swagger-ui

**Usage:**
- Explore all REST endpoints
- Test API calls with sample payloads
- View request/response schemas

#### Logs

**Location:** `target/quarkus.log` (or console in dev mode)

**Structured JSON logging:** All logs include `tenant_id`, `correlation_id`, `trace_id` for tracing.

**Example query:**
```bash
# Find all checkout errors for tenant
grep '"tenant_id":"550e8400-e29b-41d4-a716-446655440000"' target/quarkus.log | grep '"level":"ERROR"'
```

For observability details, see [Observability Framework](../operations/observability.md).

#### Database Access

**Connection:**
```bash
psql -h localhost -U appuser -d storefront_dev
```

**Common queries:**
```sql
-- List all tenants
SELECT id, subdomain, name, status FROM tenants;

-- Check product count per tenant
SELECT tenant_id, COUNT(*) FROM products GROUP BY tenant_id;

-- View recent orders
SELECT * FROM orders ORDER BY created_at DESC LIMIT 10;
```

---

## 7. Contributing Guidelines

### ADR Creation

**When to create an ADR:**
- Architectural changes (e.g., new integration pattern, data model change)
- Cross-cutting concerns (e.g., new caching strategy, security policy)
- Significant trade-offs (e.g., performance vs. maintainability)

**Process:**
1. Draft ADR using template: [docs/adr/ADR-README.md](../adr/ADR-README.md)
2. Request review from architecture team (Slack: #architecture-team)
3. Present in architecture review meeting (Tuesdays 2-3 PM EST)
4. Update ADR with feedback, get approval
5. Update status to `Accepted`, merge to main
6. Reference ADR in implementation PR

### Documentation Updates

**When to update docs:**
- **README:** User-facing features (setup, build commands, quick start)
- **Developer Guide (this doc):** Internal patterns, debugging, workflow
- **Runbooks:** Operational procedures (alerts, incident response)
- **ADRs:** Architectural decisions

**Review process:**
- Documentation changes require 1+ approval (same as code)
- Cross-check links (no broken references)
- Run spell check before committing

### Security Considerations

**Critical rules:**
- **Never commit secrets** (API keys, passwords, tokens)
- **Use .env for local config** (never commit `.env`, only `.env.example`)
- **Validate tenant isolation** in all tests (verify queries filter by tenant_id)
- **Sanitize user input** (prevent XSS, SQL injection)
- **Use parameterized queries** (never string concatenation)

**Reporting vulnerabilities:**
- Email: security@villagecompute.com
- Do NOT create public GitHub issue

---

## Quick Reference

### Essential Commands

```bash
# Development
npm run dev              # Start Quarkus dev mode with hot reload
npm run lint             # Format code (Spotless)
npm test                 # Run tests with coverage

# Database
./mvnw -pl modules/core-platform flyway:migrate  # Run migrations
psql -h localhost -U appuser -d storefront_dev   # Connect to DB

# CI/CD
npm run lint:openapi     # Validate OpenAPI specs
npm run diagrams:check   # Validate PlantUML diagrams

# Build
./mvnw clean package     # Build JAR
./mvnw verify -Pnative   # Build native image (slow)
```

### Key Documents

- **Setup:** [README.md](../../README.md)
- **Standards:** [java-project-standards.adoc](../java-project-standards.adoc)
- **Architecture:** [architecture_overview.md](../architecture_overview.md)
- **ADRs:** [adr/ADR-README.md](../adr/ADR-README.md)
- **Runbooks:** [ops/runbook-index.md](ops/runbook-index.md)

### Getting Help

- **Slack:** #engineering-team (general), #team-backend (Java/Quarkus)
- **Office Hours:** Fridays 2-3 PM EST (Tech Lead)
- **Documentation:** This guide, README, ADRs, runbooks

---

**Welcome to the team! 🚀**
