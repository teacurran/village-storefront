# objective

This is a half finished quarkus applicaiton with a lot of errors.  tonights task is to refactor the app and fix all knowna nd unknown errors.

## Known issues:
1. The project contains TODO comments where logic needs to be impletmented (refer to .codemachine_2/inputs/specifications.md for original feature specification)
2. Named queries are refereced but never defined. for example this bit of code is using a constant that appears to define a named query, but is trying to use it as if the constant contains JPQL. `return find("#" + QUERY_FIND_BY_URL + " WHERE url = :url", Parameters.with("url", url)).firstResultOptional();` the constnat should be used to defined a named query, and the named query MUST be defined in a @NamedQuery annotation on the entity class so it gets validated at startup.  If JPQL concationation is required for sorting (not supported in Named queries) then the named query should be defined using a constnat for the JPQL portion.  the JPQL constnat then can be used to create a dynamic query, the portion used in the named query will benifit from validation at startup.
3. All finder methods that call named queries shoudl be defined as Static methods on the entity class.
4. Unit tests must cover 95% of all lines and code branches. Double check to make sure all unit tests make sense and validate correct logic according to the goals of the application.

## Technology Stack

### Backend
- **Java 21** (LTS) - minimum version
- **Quarkus** framework (latest stable, currently 3.26.x)
- **Maven** build system
- **PostgreSQL 17** database
- **MyBatis Migrations** for database schema changes
- **Panache** for ORM with ActiveRecord pattern
- **LangChain4j** for AI integration (model-agnostic, initially Claude)

### Frontend (Customer-Facing Storefront)
- **Qute templates** for server-rendered HTML
- **PrimeUI components** for interactive elements (cart, checkout)
- **Tailwind CSS** for styling
- All paths except `/admin/*` are rendered with Qute

### Frontend (Admin Dashboard - `/admin/*` only)
- **Vue.js 3 + Vite + TypeScript** (via Quinoa)
- **PrimeVue** for UI components
- **Tailwind CSS** for styling

### External APIs
- **Stripe** - Payment processing with Connect
- **USPS, UPS, FedEx** - Shipping rates and labels


## Architecture Constraints

### Database Access Pattern

All database access MUST be via **static methods on model entities** using the Panache ActiveRecord pattern. Do NOT create separate repository classes.

```java
@Entity
public class User extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_EMAIL = "User.findByEmail";

    @NamedQuery(name = QUERY_FIND_BY_EMAIL,
                query = "SELECT u FROM User u WHERE u.email = :email")

    public static Optional<User> findByEmail(String email) {
        return find("#" + QUERY_FIND_BY_EMAIL,
                    Parameters.with("email", email))
               .firstResultOptional();
    }
}
```

### Background Job Processing

Use the **Delayed Job pattern** for all asynchronous operations. Reference: `../village-calendar/src/main/java/villagecompute/calendar/services/DelayedJobService.java`

### REST API Design

- Traditional REST endpoints (not GraphQL)
- OpenAPI spec-first design when applicable
- Follow Quarkus REST best practices

### Code Standards

Follow VillageCompute Java Project Standards (`docs/java-project-standards.adoc`)

### Package Structure

```
src/main/java/villagecompute/storefront/
├── api/
│   ├── rest/              # REST resources
│   └── types/             # API DTOs (Type suffix)
├── config/                # Configuration classes
├── data/
│   └── models/            # JPA entities with static finder methods
├── exceptions/            # Custom exceptions
├── integration/
│   ├── stripe/            # Stripe payment client
│   ├── shipping/          # USPS, UPS, FedEx clients
│   └── storage/           # S3/R2 object storage client
├── jobs/                  # Delayed job handlers
├── services/              # Business logic
└── util/                  # Utilities
```

---

## Refactoring Decision Responses

Based on the Specification Review (`.codemachine_2/artifacts/requirements/00_Specification_Review.md`), the following decisions have been made:

### Decision 1: TODO Implementation Strategy

**Selected: Option B - Complete Implementation**

All TODO comments must be fully implemented before v1 release. This includes:
- Payment service integration (Stripe Connect)
- Multi-tenant isolation (TenantFilter, TenantContext)
- Media processing (image resize, video transcode)
- Consignment vendor balance tracking and payouts
- Email notification system
- OAuth flow completion
- WebP image conversion
- All other incomplete business logic

No TODOs are deferred to v2.

### Decision 2: Test Coverage Strategy

**Selected: Option B - Comprehensive Coverage with Minimal Mocking**

Requirements:
- **95% line coverage** across all packages
- **95% branch coverage** across all packages
- **Minimal or no mocking** - tests should use real implementations where possible
- Use `@QuarkusTest` with test containers for database integration
- Use WireMock or similar only for external API boundaries (Stripe, shipping carriers)
- Entity finders, services, and job handlers must be tested with real database interactions

Test approach:
1. Prefer integration tests over unit tests with mocks
2. Use `@TestTransaction` for database isolation
3. Use embedded/containerized PostgreSQL for realistic query validation
4. Mock only at system boundaries (external HTTP APIs)
5. Parameterized tests for edge cases and boundary values

### DRY Principles for Tests (Critical)

**Minimizing duplication in test code is mandatory.** Apply these principles rigorously:

1. **Constants for Repeated Strings**
   - Define constants for any string used more than once (URLs, error messages, test data values, JSON paths)
   - Place shared constants in a `TestConstants` class or as static fields in test base classes
   - Example: `private static final String VALID_EMAIL = "test@example.com";`

2. **Parameterized Tests for Similar Scenarios**
   - Use `@ParameterizedTest` with `@MethodSource`, `@CsvSource`, or `@ValueSource` for tests that vary only by input/output
   - Never copy-paste a test method to test different values
   - Example: Testing validation with valid/invalid emails, boundary values, edge cases

3. **Shared Test Fixtures**
   - Extract common setup into `@BeforeEach` methods or test base classes
   - Use factory methods for creating test entities (e.g., `createTestUser()`, `createTestStore()`, `createTestProduct()`)
   - Share WireMock stubs via helper methods

4. **Test Base Classes**
   - Create abstract base classes for common test infrastructure (e.g., `BaseIntegrationTest`, `BaseResourceTest`)
   - Centralize `@QuarkusTest` configuration, test containers, and common assertions

5. **Custom Assertions**
   - Extract repeated assertion patterns into reusable assertion methods
   - Example: `assertValidationError(response, "email", "must not be blank")`

6. **No Magic Numbers or Strings**
   - Every literal value that appears more than once must be a named constant
   - Test data builders preferred over inline object construction

### Decision 3: Named Query Architecture

**Selected: Option B - Hybrid Approach**

- Use `@NamedQuery` annotations for all simple lookup queries (validated at startup)
- For queries requiring dynamic sorting/filtering, define the static JPQL portion as a constant
- The JPQL constant can be used both in `@NamedQuery` (for validation) and composed with dynamic parts at runtime
- Never use raw string JPQL without a constant

Example pattern for dynamic queries:
```java
// Static portion validated at startup via @NamedQuery
public static final String JPQL_FIND_ACTIVE = "SELECT p FROM Product p WHERE p.status = 'ACTIVE'";
public static final String QUERY_FIND_ACTIVE = "Product.findActive";

@NamedQuery(name = QUERY_FIND_ACTIVE, query = JPQL_FIND_ACTIVE)

// Dynamic sorting uses the validated JPQL constant
public static List<Product> findActiveSorted(String sortField, String sortDir) {
    return find(JPQL_FIND_ACTIVE + " ORDER BY p." + sortField + " " + sortDir).list();
}
```

### Decision 4: Multi-Tenancy Implementation

**Selected: Option A - Application-Primary with RLS Safety Net**

- Primary isolation via Panache base class that applies `tenant_id` filter
- PostgreSQL RLS policies as defense-in-depth
- TenantContext as @RequestScoped CDI bean
- TenantFilter as JAX-RS ContainerRequestFilter
- All tenant-scoped queries MUST go through Panache base class

### Decision 5: Media Processing Strategy

**Selected: Option B - Async Processing with DelayedJob**

- Image processing: Immediate for small files, queued for large
- Video processing: Always queued via DelayedJob CRITICAL queue
- Worker pods with FFmpeg for video transcoding
- Thumbnailator for image operations
- WebP conversion via sejda-imageio plugin

### Decision 6: Payment Integration

**Selected: Option A - Stripe Connect with Express Accounts**

- Each store connects their own Stripe Express account
- Platform fee deducted via Stripe Connect application fees
- Consignment vendor payouts via Stripe Transfer API
- Gift cards and store credit as internal ledger (not Stripe)

---

## Implementation Priority Order

Based on dependencies and risk, implement in this order:

1. **Test Infrastructure** - Must be first to validate all other changes
2. **Named Query Refactoring** - Enables startup validation
3. **Multi-Tenant Isolation** - Core architectural requirement
4. **Payment Integration (Stripe)** - Unblocks checkout flow
5. **Media Processing** - Required for product images
6. **Consignment Balance Tracking** - Required for vendor payouts
7. **Email Notifications** - Lower priority, can run in background
