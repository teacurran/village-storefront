# Task Briefing Package

This package contains all necessary information and strategic guidance for the Coder Agent.

---

## 1. Current Task Details

This is the full specification of the task you must complete.

```json
{
  "task_id": "I6.T4",
  "iteration_id": "I6",
  "iteration_goal": "Finalize CI/CD, security/performance validations, automated testing, DR/observability, and documentation to make the platform production-ready on Kubernetes.",
  "description": "Build comprehensive E2E test suite (Playwright/Cypress) covering multi-tenant flows: storefront guest checkout, auth checkout, admin catalog CRUD, consignment payout, loyalty redemption, POS offline, headless order; integrate to CI gating.",
  "agent_type_hint": "QAAgent",
  "inputs": "API spec, frontends, dev stack.",
  "target_files": [],
  "input_files": [],
  "deliverables": "Scripts with fixtures, multi-tenant data seeding, CI step running tests headless on staging environment, documentation on running locally.",
  "acceptance_criteria": "Tests deterministic (retry/resilience), run ≤20 min, fail pipeline when regressions occur; README instructs env setup; sample video/screenshot artifacts stored.",
  "dependencies": [],
  "parallelizable": false,
  "done": false
}
```

---

## 2. Architectural & Planning Context

The following are the relevant sections from the architecture and plan documents, which I found by analyzing the task description.

### Context: Test Strategy Overview (from docs/quality/test_strategy.md)

The project maintains a multi-level testing strategy:

- **Unit Tests:** ≥85% coverage per module with JUnit 5 + Mockito
- **Integration Tests:** PostgreSQL + Testcontainers with RLS verification
- **End-to-End Tests:** Playwright suites covering storefront, admin, POS, platform workflows
- **Performance Tests:** k6 load testing (already implemented in I5.T7)
- **CI Enforcement:** JaCoCo 80% minimum (quality gate failure below threshold), Spotless formatting, OpenAPI validation

### Context: Module Inventory

Core Platform Module (`modules/core-platform/`):
- Quarkus 3.20.0 with Java 21
- Multi-tenant architecture with RLS enforcement
- Key domains: Catalog, Inventory, Cart, Checkout, Payment (Stripe), Consignment, Loyalty, Gift Cards, POS
- Frontend: Qute templates (storefront) + Vue 3 + Quinoa (admin dashboard)

### Context: E2E Coverage Requirements

From iteration task descriptions across I2, I3, I4, I5:

**I2.T8:** Establish contract tests + QA data fixtures for catalog/inventory/storefront flows using Playwright (storefront) and REST-assured (API)

**I3.T8:** Expand e2e automation: Playwright checkout flow (guest + logged-in), REST-assured payment/discount validation, plus Stripe webhook replay tests

**I4.T8 (Implicit):** Consignment/loyalty reporting and exports need E2E validation

**I5.T7 (Completed):** Verification execution and release readiness report - load tests, chaos drills, comprehensive reporting

**I6.T4 (Current):** Consolidate all E2E tests into comprehensive suite with CI gating

### Context: Multi-Tenant Architecture

From `docs/architecture/tenant_isolation.md`:

- Each tenant identified by subdomain (tenant-a.test.local) or custom domain
- All data scoped by `tenant_id` with PostgreSQL RLS policies
- TenantContext resolved via TenantResolutionFilter from request headers
- Feature flags per-tenant stored in `feature_flags` table

### Context: CI/CD Integration Requirements

From `.github/workflows/ci.yml`:

- Tests must run in ≤20 minutes to avoid timeout
- Concurrency control: `cancel-in-progress: true`
- Artifacts: HTML reports, JSON results, JUnit XML, traces
- Node.js 20, Java 21 runtime environment
- Maven opts include local repo caching

---

## 3. Codebase Analysis & Strategic Guidance

The following analysis is based on my direct review of the current codebase. Use these notes and tips to guide your implementation.

### Relevant Existing Code

#### **File:** `tests/e2e/playwright/playwright.config.ts`
- **Summary:** Playwright configuration with multi-browser support (chromium, firefox, webkit, mobile) and CI reporter setup
- **Key Features:**
  - Test timeout: 60 seconds per test
  - Parallel execution: 4 workers in CI
  - Retries: 2 in CI, 0 locally
  - HTML/JSON/JUnit reporters configured
  - WebServer integration for local dev (quarkus:dev on port 8080)
  - Video recording enabled for debugging
- **Recommendation:** You SHOULD reuse this configuration without major changes. The timeout and worker settings are already optimized for the ≤20 min acceptance criteria.

#### **File:** `tests/fixtures/tenants.ts`
- **Summary:** Multi-tenant test fixtures defining 3 test tenants (A, B, C) with complete user accounts, products, gift cards, loyalty configs
- **Key Data:**
  - **Tenant A:** subdomain `tenant-a.test.local`, loyalty enabled, 3 products, OAuth client configured
  - **Tenant B:** subdomain `tenant-b.test.local`, loyalty enabled (different rates), tech accessories
  - **Tenant C:** subdomain `tenant-c.test.local`, custom domain `custom-store.example.com`, loyalty DISABLED
  - Platform admin: `platform@villagecompute.com` with global access
- **Recommendation:** You MUST import and use these fixtures for all multi-tenant tests. The deterministic data is seeded via `scripts/dev/tenant_seed.sh --catalog`.

#### **File:** `tests/e2e/playwright/multi-tenant-isolation.spec.ts`
- **Summary:** Existing E2E tests verifying tenant data isolation (products, orders, feature flags)
- **Coverage:**
  - Product isolation between tenants (no ID overlap)
  - Cross-tenant access prevention (404 errors)
  - Storefront subdomain-based product filtering
  - Order isolation verification
  - Tenant-specific feature flag enforcement
- **Recommendation:** This is your TEMPLATE for writing multi-tenant tests. Follow the pattern: login to tenant A, fetch data, login to tenant B, verify no overlap.

#### **File:** `tests/e2e/playwright/pages/BasePage.ts`
- **Summary:** Base page object with common functionality (goto, fillField, clickButton, waitForTenantContext)
- **Key Method:** `waitForTenantContext()` - waits for `document.body.dataset.tenantLoaded === 'true'`
- **Recommendation:** All new page objects SHOULD extend `BasePage` and call `waitForTenantContext()` after navigation to ensure tenant branding has loaded.

#### **File:** `tests/e2e/playwright/headless-api.spec.ts`
- **Summary:** OAuth client credentials flow test for headless API order creation
- **Pattern:**
  - `beforeAll`: Authenticate with OAuth client_credentials grant
  - Tests: Use `request.post()` with `Authorization: Bearer ${accessToken}` header
  - Validates: Cart creation, item addition, shipping rates, checkout commit
- **Recommendation:** You SHOULD follow this pattern for all headless API tests. Store access token in `beforeAll` and reuse across test cases.

#### **File:** `scripts/qa/run_e2e.sh`
- **Summary:** E2E test orchestration script (prerequisite checks, data seeding, REST-assured contracts, Playwright execution, artifact collection)
- **Key Functions:**
  - `seed_test_data()`: Calls `scripts/dev/tenant_seed.sh --catalog`
  - `run_rest_assured_contracts()`: Runs `CatalogContractIT` and `PaymentContractIT` via Maven
  - Playwright execution: `cd tests/e2e/playwright && npm run test`
  - Artifact collection: HTML report, JSON results, JUnit XML, traces
- **Recommendation:** This script is your CI entry point. You SHOULD add new test suites by expanding Playwright specs, NOT by modifying this script (unless adding new test categories like visual regression).

### Implementation Tips & Notes

#### Multi-Tenant Test Strategy
- **Tip:** Every critical user flow MUST be tested across at least 2 tenants (e.g., Tenant A and Tenant B) to verify isolation
- **Tip:** Use `getTenantBaseUrl(tenant)` helper from `fixtures/tenants.ts` to construct tenant-specific URLs
- **Tip:** Feature flag variations: Test with Tenant A (loyalty enabled) and Tenant C (loyalty disabled) to verify feature gating

#### Page Object Pattern
- **Note:** Existing page objects in `tests/e2e/playwright/pages/`:
  - `AdminLoginPage.ts`, `StorefrontPage.ts`, `CartPage.ts`, `CheckoutPage.ts`, `ProductPage.ts`
  - `ConsignorPortalPage.ts`, `LoyaltyPage.ts`, `GiftCardPage.ts`
  - `PlatformConsolePage.ts`, `AdminDashboardPage.ts`, `AdminInventoryPage.ts`, `AdminOrderPage.ts`
- **Recommendation:** You SHOULD create page objects for any new flows (e.g., POS offline queue page) following the existing pattern

#### Test Determinism & Resilience
- **Warning:** Tests MUST be deterministic. Use `scripts/dev/tenant_seed.sh --catalog` to seed known data BEFORE running tests
- **Tip:** Use Playwright's `expect().toBeVisible()` with implicit retries instead of manual `waitFor()` calls
- **Tip:** For flaky network operations (Stripe webhooks, carrier APIs), use test doubles or mocks in E2E environment (see `docker/mocks/shipping/`)

#### CI Performance Budget
- **Critical:** Tests MUST complete in ≤20 minutes. Current config uses 4 parallel workers
- **Optimization:** Group related tests in same describe block to share setup overhead (e.g., `beforeAll` for login)
- **Note:** Video recording is enabled (`video: 'on'`) which adds ~5-10% overhead. This is acceptable for debugging value

#### Artifact Storage
- **Locations (from playwright.config.ts):**
  - HTML report: `target/playwright-report/index.html`
  - JSON results: `target/playwright-results.json`
  - JUnit XML: `target/playwright-junit.xml`
  - Traces: Automatically captured on first retry (not all tests)
- **CI Integration:** These paths are already configured for GitHub Actions artifact upload (see `.github/workflows/ci.yml`)

#### Missing Coverage Areas (Your Focus)

Based on task description "covering multi-tenant flows: storefront guest checkout, auth checkout, admin catalog CRUD, consignment payout, loyalty redemption, POS offline, headless order", here's what exists and what's missing:

**✅ Already Implemented:**
- Multi-tenant isolation (products, orders, feature flags)
- Headless API flow (OAuth + order creation)
- Basic storefront navigation
- Admin login

**⚠️ Partially Implemented (needs expansion):**
- Storefront checkout (exists but needs guest vs. auth split)
- Admin catalog CRUD (needs comprehensive coverage)
- Loyalty redemption (loyalty page exists but checkout integration missing)

**❌ Missing (you MUST implement):**
- **POS offline queue flow:** Submit transaction offline, verify queue, sync when online
- **Consignment payout verification:** Portal balance view, payout schedule check
- **Guest vs authenticated checkout comparison:** Side-by-side flows showing address persistence
- **End-to-end loyalty flow:** Earn points on purchase, redeem on next order, verify balance
- **Multi-tenant checkout:** Same checkout flow on Tenant A and B to verify isolation

#### Example Test Structure (Template)

```typescript
import { test, expect } from '@playwright/test';
import { tenants, getTenantBaseUrl } from '../../fixtures/tenants';
import { StorefrontPage } from './pages/StorefrontPage';
import { CheckoutPage } from './pages/CheckoutPage';

test.describe('Storefront Guest Checkout Flow (Multi-Tenant)', () => {
  test('should complete guest checkout on Tenant A', async ({ page }) => {
    const tenant = tenants.tenantA;
    const baseURL = getTenantBaseUrl(tenant);

    const storefront = new StorefrontPage(page);
    const checkout = new CheckoutPage(page);

    await page.goto(baseURL);
    await storefront.waitForTenantContext();

    // Add product to cart
    await storefront.addProductToCart(tenant.products[0].id);

    // Proceed to checkout
    await storefront.gotoCart();
    await page.click('[data-test="checkout-button"]');

    // Fill guest checkout form
    await checkout.fillGuestInfo({
      email: 'guest@example.com',
      firstName: 'Guest',
      lastName: 'User',
    });

    // ... continue checkout flow

    // Verify order confirmation
    await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();
  });

  test('should complete guest checkout on Tenant B (isolation check)', async ({ page }) => {
    // Repeat same flow with Tenant B data
    // Verify tenant-specific branding, products, pricing
  });
});
```

### CI Integration Notes

**GitHub Actions Job (Add to `.github/workflows/ci.yml`):**

You will need to add a new job similar to:

```yaml
e2e-tests:
  name: E2E Tests (Playwright)
  runs-on: ubuntu-latest
  timeout-minutes: 25  # Buffer for 20 min test + setup
  needs: [unit-tests, security-scan]  # Run after other validations

  services:
    postgres:
      image: postgres:17
      env:
        POSTGRES_DB: storefront_test
        POSTGRES_USER: test
        POSTGRES_PASSWORD: test
      options: >-
        --health-cmd pg_isready
        --health-interval 10s
        --health-timeout 5s
        --health-retries 5
      ports:
        - 5432:5432

  steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Set up JDK ${{ env.JAVA_VERSION }}
      uses: actions/setup-java@v4
      with:
        java-version: ${{ env.JAVA_VERSION }}
        distribution: 'temurin'
        cache: 'maven'

    - name: Set up Node.js
      uses: actions/setup-node@v4
      with:
        node-version: ${{ env.NODE_VERSION }}
        cache: 'npm'
        cache-dependency-path: tests/e2e/playwright/package-lock.json

    - name: Start Quarkus application
      run: |
        ./mvnw -pl modules/core-platform clean package -DskipTests
        java -jar modules/core-platform/target/quarkus-app/quarkus-run.jar &
        sleep 30  # Wait for startup

    - name: Run E2E tests
      env:
        BASE_URL: http://localhost:8080
        CI: true
      run: ./scripts/qa/run_e2e.sh

    - name: Upload Playwright report
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: playwright-report
        path: target/playwright-report/
        retention-days: 30

    - name: Upload test results
      if: always()
      uses: actions/upload-artifact@v4
      with:
        name: playwright-results
        path: |
          target/playwright-results.json
          target/playwright-junit.xml
        retention-days: 90
```

**Critical:** The CI job MUST fail when tests fail. This is already handled by `run_e2e.sh` which exits non-zero on test failure.

### Documentation Requirements

You MUST update the following documentation:

1. **README.md:** Add E2E test section with:
   - Prerequisites (Node.js 18+, running app at http://localhost:8080)
   - Quick start: `./scripts/qa/run_e2e.sh`
   - Environment variables: `BASE_URL`, `HEADLESS`, `SEED_DATA`
   - Viewing reports: `open target/playwright-report/index.html`

2. **docs/quality/test_strategy.md:** Update E2E coverage matrix with:
   - List of implemented test specs
   - Coverage by module (Catalog, Cart, Checkout, Loyalty, POS, Consignment, Headless)
   - Multi-tenant coverage notes
   - Performance budget validation (≤20 min)

3. **tests/e2e/playwright/README.md (create if missing):**
   - Architecture overview (Playwright + Page Objects)
   - Test fixture usage
   - Writing new tests (template + guidelines)
   - Debugging (traces, videos, headed mode)
   - CI integration notes

---

## 4. Gaps Analysis & Priority Focus

### High Priority (MUST Implement)

1. **POS Offline Flow Test:** Critical for retail operations, no existing coverage
2. **Multi-tenant checkout isolation:** Verify same product has different pricing/inventory across tenants
3. **Loyalty end-to-end flow:** Earn points, redeem, verify balance - tests business logic integrity
4. **Guest vs. Auth checkout comparison:** Tests authentication boundary and data persistence

### Medium Priority (SHOULD Implement)

1. **Consignment payout verification:** Portal exists but payout flow not tested
2. **Admin catalog CRUD expansion:** Basic product creation tested, need variant management, bulk import
3. **Gift card application in checkout:** Module exists, checkout integration not tested

### Low Priority (NICE to Have)

1. **Visual regression tests:** Existing storefront-visual.spec.ts could be expanded
2. **Accessibility testing:** WCAG 2.1 AA compliance checks with axe-core
3. **Performance monitoring:** Lighthouse CI integration

### Time Budget Allocation (20 min total)

Based on parallel execution (4 workers):

- Multi-tenant isolation: 2 min (already optimized)
- Storefront checkout flows: 5 min (guest + auth + multi-tenant)
- Admin CRUD: 3 min (catalog, inventory, orders)
- POS offline: 4 min (queue submit + sync)
- Loyalty + consignment: 3 min (combined flow)
- Headless API: 2 min (already optimized)
- Buffer: 1 min (retries, setup overhead)

**Note:** If you exceed 20 min, you MUST either:
- Increase worker parallelism (requires CI approval)
- Split tests into separate jobs (nightly vs. critical path)
- Optimize setup (reuse login sessions, reduce waits)

---

## 5. Quality Checklist

Before marking this task complete, verify:

- [ ] All test specs follow Playwright best practices (page objects, explicit waits, data-test attributes)
- [ ] Multi-tenant isolation verified for EVERY critical flow (minimum 2 tenants)
- [ ] Tests are deterministic (seed data loaded, no race conditions)
- [ ] CI integration added to `.github/workflows/ci.yml` with proper job dependencies
- [ ] README.md updated with E2E test instructions
- [ ] `docs/quality/test_strategy.md` updated with coverage matrix
- [ ] Test execution completes in ≤20 minutes (measured in CI)
- [ ] Artifacts (HTML/JSON/JUnit/videos) stored correctly in `target/` directories
- [ ] Pipeline fails on test regression (verified by intentionally breaking a test)

---

## 6. Success Criteria Validation

The task will be considered DONE when:

1. **Comprehensive Coverage:** All flows listed in task description have E2E tests (storefront guest checkout, auth checkout, admin catalog CRUD, consignment payout, loyalty redemption, POS offline, headless order)
2. **Multi-Tenant Validation:** Each flow tested on ≥2 tenants to verify isolation
3. **CI Integration:** Tests run automatically on every PR/push, fail pipeline on regression
4. **Documentation:** README has clear setup instructions, test strategy updated with coverage matrix
5. **Performance:** Full suite completes in ≤20 minutes with deterministic results
6. **Artifacts:** HTML reports, JSON results, videos available for debugging

Good luck! The foundation is already 70% complete - you're building on solid existing work. Focus on filling the gaps (POS offline, consignment payout, loyalty flow) and ensuring everything runs reliably in CI.
