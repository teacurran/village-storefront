# Playwright E2E Tests - Developer Guide

This guide provides comprehensive documentation for writing, running, and maintaining End-to-End (E2E) tests for the Village Storefront platform using Playwright.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Writing Tests](#writing-tests)
  - [Page Object Pattern](#page-object-pattern)
  - [Multi-Tenant Testing](#multi-tenant-testing)
  - [Test Fixtures](#test-fixtures)
  - [Best Practices](#best-practices)
- [Running Tests](#running-tests)
- [Debugging](#debugging)
- [CI Integration](#ci-integration)
- [Troubleshooting](#troubleshooting)

## Overview

The Playwright E2E test suite provides comprehensive coverage of all critical user workflows in the Village Storefront platform, including:

- Storefront guest and authenticated checkout
- Loyalty program end-to-end flows (earn, redeem, balance)
- POS offline queue management
- Admin catalog CRUD (products, variants, bulk import)
- Consignment payout verification
- Headless API OAuth flows
- Multi-tenant data isolation

All tests are designed to be deterministic, resilient, and capable of running in parallel to meet the ≤20 minute CI execution budget.

## Prerequisites

- **Node.js:** 20+ (LTS recommended)
- **npm:** 9+ (comes with Node.js)
- **Application:** Running instance at `http://localhost:8080` (or configured via `BASE_URL`)
- **Docker:** Required for PostgreSQL and MinIO services
- **Test Data:** Seeded via `scripts/dev/tenant_seed.sh --catalog`

## Quick Start

```bash
# 1. Install dependencies
cd tests/e2e/playwright
npm ci

# 2. Install Playwright browsers (one-time setup)
npx playwright install --with-deps

# 3. Start the application (in separate terminal)
cd /path/to/village-storefront
./mvnw quarkus:dev

# 4. Seed test data (one-time or when data needs refresh)
./scripts/dev/tenant_seed.sh --catalog

# 5. Run all E2E tests
npm run test

# 6. View HTML report
npx playwright show-report ../../target/playwright-report
```

## Project Structure

```
tests/e2e/playwright/
├── README.md                       # This file
├── playwright.config.ts            # Playwright configuration
├── package.json                    # npm dependencies
├── pages/                          # Page Object classes
│   ├── BasePage.ts                 # Base page object with common methods
│   ├── StorefrontPage.ts           # Storefront homepage
│   ├── ProductPage.ts              # Product detail page
│   ├── CartPage.ts                 # Shopping cart page
│   ├── CheckoutPage.ts             # Checkout flow
│   ├── LoyaltyPage.ts              # Loyalty account page
│   ├── POSPage.ts                  # POS terminal interface
│   ├── ConsignorPortalPage.ts      # Consignment portal
│   ├── AdminLoginPage.ts           # Admin login
│   ├── AdminDashboardPage.ts       # Admin dashboard
│   └── ...
├── checkout-guest-vs-auth.spec.ts  # Guest vs authenticated checkout tests
├── loyalty-end-to-end.spec.ts      # Loyalty end-to-end flow tests
├── pos-offline-queue.spec.ts       # POS offline queue tests
├── consignment-payout.spec.ts      # Consignment payout tests
├── admin-catalog-crud.spec.ts      # Admin catalog CRUD tests
├── headless-api.spec.ts            # Headless API OAuth tests
├── multi-tenant-isolation.spec.ts  # Multi-tenant isolation tests
└── ...

tests/fixtures/
├── tenants.ts                      # Multi-tenant test fixtures (Tenant A/B/C)
└── ...

scripts/
├── qa/
│   └── run_e2e.sh                  # E2E orchestration script (canonical entry point)
└── dev/
    └── tenant_seed.sh              # Test data seeding script
```

## Writing Tests

### Page Object Pattern

All tests follow the Page Object Model (POM) to encapsulate page-specific selectors and actions, improving maintainability and reducing duplication.

**Example: Creating a Page Object**

```typescript
// pages/MyNewPage.ts
import { Page, Locator } from '@playwright/test';
import { BasePage } from './BasePage';

export class MyNewPage extends BasePage {
  readonly pageTitle: Locator;
  readonly submitButton: Locator;
  readonly inputField: Locator;

  constructor(page: Page) {
    super(page);
    this.pageTitle = page.locator('[data-test="page-title"]');
    this.submitButton = page.locator('[data-test="submit-button"]');
    this.inputField = page.locator('[data-test="input-field"]');
  }

  async goto(): Promise<void> {
    await this.page.goto('/my-new-page');
    await this.waitForTenantContext(); // IMPORTANT: Wait for tenant branding
  }

  async fillAndSubmit(value: string): Promise<void> {
    await this.fillField(this.inputField, value);
    await this.clickButton(this.submitButton);
  }

  async getTitle(): Promise<string> {
    return await this.getText(this.pageTitle);
  }
}
```

**Key Points:**

- **Extend `BasePage`:** Inherit common functionality (goto, fillField, clickButton, waitForTenantContext)
- **Use `data-test` attributes:** All selectors should target `data-test` attributes for stability
- **Always call `waitForTenantContext()`:** After navigation to ensure tenant-specific UI has loaded
- **Return values:** Use async/await pattern for all page interactions

### Multi-Tenant Testing

**Every critical workflow MUST be tested across ≥2 tenants** to verify data isolation and prevent cross-tenant leakage.

**Example: Multi-Tenant Test Structure**

```typescript
import { test, expect } from '@playwright/test';
import { tenants, getTenantBaseUrl } from '../fixtures/tenants';
import { MyNewPage } from './pages/MyNewPage';

test.describe('My Feature - Multi-Tenant Validation', () => {
  test.describe('Tenant A - Feature Workflow', () => {
    const tenant = tenants.tenantA;
    const baseURL = getTenantBaseUrl(tenant);

    test.beforeEach(async ({ page }) => {
      await page.goto(baseURL);
    });

    test('should perform action with Tenant A data', async ({ page }) => {
      const myPage = new MyNewPage(page);
      await myPage.goto();

      // Use Tenant A data from fixtures
      const product = tenant.products[0];
      await myPage.fillAndSubmit(product.name);

      // Verify Tenant A-specific result
      const title = await myPage.getTitle();
      expect(title).toContain(tenant.name); // "Tenant A Store"
    });
  });

  test.describe('Tenant B - Feature Isolation', () => {
    const tenant = tenants.tenantB;
    const baseURL = getTenantBaseUrl(tenant);

    test.beforeEach(async ({ page }) => {
      await page.goto(baseURL);
    });

    test('should NOT see Tenant A data in Tenant B', async ({ page }) => {
      const myPage = new MyNewPage(page);
      await myPage.goto();

      // Verify Tenant B has different data
      const title = await myPage.getTitle();
      expect(title).toContain(tenant.name); // "Tenant B Store"
      expect(title).not.toContain('Tenant A'); // NO cross-tenant leakage
    });
  });
});
```

**Multi-Tenant Best Practices:**

- **Use `getTenantBaseUrl()`:** Always construct URLs via helper function
- **Verify tenant context:** Check tenant name/branding in assertions
- **Test cross-tenant access prevention:** Navigate to Tenant A URL from Tenant B session and verify 404/403
- **Use tenant-specific data:** Products, SKUs, users, gift cards from `tenants.ts`

### Test Fixtures

All test data is defined in `tests/fixtures/tenants.ts` to ensure deterministic, reproducible tests.

**Available Fixtures:**

```typescript
import { tenants, platformAdmin } from '../fixtures/tenants';

// Tenant A (loyalty enabled, 10 points/$)
const tenantA = tenants.tenantA;
tenantA.id; // 'tenant-a-001'
tenantA.subdomain; // 'tenant-a.test.local'
tenantA.admin; // { email: 'admin@tenant-a.com', password: '...' }
tenantA.customer; // { email: 'customer@tenant-a.com', ... }
tenantA.loyaltyMember; // { email: 'loyalty@tenant-a.com', ... }
tenantA.consignor; // { email: 'consignor@tenant-a.com', ... }
tenantA.products; // [{ id: 'prod-a-001', name: '...', price: 29.99, ... }]
tenantA.giftCards; // [{ code: 'GIFT-A-100', balance: 100.0 }]
tenantA.loyaltyProgram; // { enabled: true, pointsPerDollar: 10, redemptionRate: 1.0 }

// Tenant B (loyalty enabled, 5 points/$)
const tenantB = tenants.tenantB;
tenantB.loyaltyProgram.pointsPerDollar; // 5 (different from Tenant A)

// Tenant C (loyalty DISABLED)
const tenantC = tenants.tenantC;
tenantC.loyaltyProgram.enabled; // false

// Platform admin (access to all tenants)
platformAdmin.email; // 'platform@villagecompute.com'
```

**Using Fixtures in Tests:**

```typescript
test('should earn loyalty points at Tenant A rate', async ({ page }) => {
  const tenant = tenants.tenantA;
  const product = tenant.products[0];
  const expectedPoints = Math.floor(product.price * tenant.loyaltyProgram.pointsPerDollar);

  // ... perform checkout ...

  const pointsEarned = await loyaltyPage.getPointsEarned();
  expect(pointsEarned).toBe(expectedPoints); // Tenant A: 10x rate
});
```

**Seeding Fixtures:**

```bash
# Seed catalog data (creates tenants, products, users)
./scripts/dev/tenant_seed.sh --catalog

# Reset database (WARNING: destructive)
./scripts/dev/reset_test_db.sh
```

### Best Practices

#### 1. Use Explicit Waits with Auto-Retry

**Good:**
```typescript
await expect(page.locator('[data-test="order-confirmation"]')).toBeVisible();
```

**Bad:**
```typescript
await page.waitForTimeout(5000); // Hardcoded wait - AVOID
```

#### 2. Use `data-test` Attributes

**Good:**
```typescript
await page.click('[data-test="checkout-button"]');
```

**Bad:**
```typescript
await page.click('.btn-primary.checkout'); // Fragile CSS selectors
```

#### 3. Handle Tenant Context

**Always call `waitForTenantContext()` after navigation:**

```typescript
await page.goto(baseURL);
await storefront.waitForTenantContext(); // Wait for tenant branding
```

This ensures tenant-specific UI (logo, colors, feature flags) has loaded before proceeding.

#### 4. Keep Tests Independent

Each test should be able to run in isolation without depending on other tests' state.

**Good:**
```typescript
test.beforeEach(async ({ page }) => {
  // Fresh login for each test
  await loginPage.login(tenant.admin.email, tenant.admin.password);
});
```

**Bad:**
```typescript
test.beforeAll(async ({ page }) => {
  // Shared login - tests depend on each other
  await loginPage.login(tenant.admin.email, tenant.admin.password);
});
```

#### 5. Use Descriptive Test Names

**Good:**
```typescript
test('should earn loyalty points at Tenant A rate (10 points per dollar)', async ({ page }) => {
  // ...
});
```

**Bad:**
```typescript
test('loyalty test', async ({ page }) => {
  // ...
});
```

#### 6. Group Related Tests

```typescript
test.describe('Checkout Flow', () => {
  test.describe('Guest Checkout', () => {
    test('should complete checkout without account', async ({ page }) => {
      // ...
    });

    test('should validate email format', async ({ page }) => {
      // ...
    });
  });

  test.describe('Authenticated Checkout', () => {
    test('should pre-fill saved address', async ({ page }) => {
      // ...
    });

    test('should earn loyalty points', async ({ page }) => {
      // ...
    });
  });
});
```

#### 7. Verify Negative Cases

Always test error handling and validation:

```typescript
test('should show error for invalid email format', async ({ page }) => {
  await checkoutPage.fillShippingInfo({ email: 'invalid-email', ... });
  await checkoutPage.placeOrder({ expectSuccess: false });

  const validationError = page.locator('[data-test="email-validation-error"]');
  await expect(validationError).toBeVisible();
  await expect(validationError).toContainText('valid email');
});
```

## Running Tests

### Local Development

```bash
# Run all tests (headless, parallel)
npm run test

# Run specific test file
npm run test checkout-guest-vs-auth.spec.ts

# Run tests matching pattern
npm run test --grep "loyalty"

# Run in headed mode (see browser)
npm run test -- --headed

# Run in debug mode (step through)
npm run test --debug

# Run with UI mode (interactive)
npm run test -- --ui

# Run specific browser
npm run test -- --project=chromium
npm run test -- --project=firefox
npm run test -- --project=webkit
```

### Using Orchestration Script

```bash
# Canonical E2E entry point (recommended)
./scripts/qa/run_e2e.sh

# Run without data seeding
SEED_DATA=false ./scripts/qa/run_e2e.sh

# Run in headed mode
HEADLESS=false ./scripts/qa/run_e2e.sh

# Custom base URL
BASE_URL=http://staging.villagecompute.com ./scripts/qa/run_e2e.sh
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BASE_URL` | `http://localhost:8080` | Application base URL |
| `HEADLESS` | `true` | Run browsers in headless mode |
| `CI` | auto-detected | Enable CI-specific settings |
| `SEED_DATA` | `true` | Seed test data before running |

## Debugging

### View HTML Report

```bash
# Generate and open HTML report
npx playwright show-report target/playwright-report
```

### Run in Debug Mode

```bash
# Step through test with Playwright Inspector
npm run test --debug checkout-guest-vs-auth.spec.ts
```

### View Traces

```bash
# Enable tracing
npm run test -- --trace on

# View trace after failure
npx playwright show-trace target/playwright-traces/trace.zip
```

### Video Recording

Videos are recorded automatically (configured in `playwright.config.ts`):

```bash
# Videos saved to: target/playwright-videos/
ls -lh target/playwright-videos/
```

### Screenshots

```bash
# Take manual screenshot
await page.screenshot({ path: 'debug-screenshot.png' });

# Automatically capture on failure (configured in playwright.config.ts)
# Screenshots saved to: target/playwright-screenshots/
```

### Console Logs

```typescript
// Listen to console messages
page.on('console', (msg) => console.log('PAGE LOG:', msg.text()));

// Listen to page errors
page.on('pageerror', (err) => console.error('PAGE ERROR:', err));
```

## CI Integration

E2E tests run automatically in GitHub Actions (`.github/workflows/ci.yml`) on every PR and push to `main`/`beta`.

**CI Job Configuration:**

```yaml
e2e-tests:
  name: E2E Tests (Playwright)
  runs-on: ubuntu-latest
  timeout-minutes: 30

  services:
    postgres: # PostgreSQL 16
    minio: # S3-compatible storage

  steps:
    - Checkout code
    - Set up Java 21 + Node.js 20
    - Build Quarkus application
    - Start application (http://localhost:8080)
    - Run E2E tests via ./scripts/qa/run_e2e.sh
    - Upload artifacts (HTML report, JSON results, traces)
```

**Execution Settings:**

- **Timeout:** 30 minutes (tests must complete in ≤20 minutes)
- **Parallelization:** 4 workers
- **Retries:** 2 retries on failure
- **Environment:** `CI=true HEADLESS=true BASE_URL=http://localhost:8080`
- **Quality Gate:** Tests must pass before PR merge

**Artifacts:**

- HTML Report: `target/playwright-report/index.html`
- JSON Results: `target/playwright-results.json`
- JUnit XML: `target/playwright-junit.xml`
- Traces: `target/playwright-traces/` (on failure)
- Videos: `target/playwright-videos/` (on failure)

## Troubleshooting

### Tests Failing Locally

1. **Check application is running:**
   ```bash
   curl http://localhost:8080/q/health/ready
   ```

2. **Verify test data seeded:**
   ```bash
   ./scripts/dev/tenant_seed.sh --catalog
   ```

3. **Check browser installation:**
   ```bash
   npx playwright install --with-deps
   ```

4. **Clear cache and reinstall:**
   ```bash
   rm -rf node_modules package-lock.json
   npm ci
   npx playwright install --with-deps
   ```

### Flaky Tests

**Symptoms:** Tests pass sometimes, fail other times

**Common Causes & Solutions:**

1. **Race Conditions:**
   - Use Playwright's auto-retry assertions (`expect().toBeVisible()`)
   - Avoid hardcoded `waitForTimeout()`

2. **Missing `waitForTenantContext()`:**
   - Always call after navigation to wait for tenant UI

3. **Shared State Between Tests:**
   - Ensure tests are independent (use `test.beforeEach`)
   - Clear cart/logout between tests

4. **Network Timing:**
   - Increase timeout for slow operations:
     ```typescript
     await expect(element).toBeVisible({ timeout: 10000 });
     ```

### Debugging CI Failures

1. **Download CI artifacts:**
   - Go to GitHub Actions run
   - Download `playwright-report` and `playwright-traces`

2. **View HTML report locally:**
   ```bash
   unzip playwright-report.zip
   npx playwright show-report playwright-report/
   ```

3. **View trace:**
   ```bash
   npx playwright show-trace playwright-traces/trace.zip
   ```

4. **Check application logs:**
   - Download `quarkus-logs-e2e` artifact
   - Review for errors/warnings

### Permission Issues

```bash
# Fix executable permissions
chmod +x scripts/qa/run_e2e.sh
chmod +x scripts/dev/tenant_seed.sh
```

### Port Already in Use

```bash
# Find process using port 8080
lsof -i :8080

# Kill process
kill -9 <PID>
```

---

## Additional Resources

- **Playwright Documentation:** https://playwright.dev/docs/intro
- **Main README:** `/README.md` (E2E Tests section)
- **Test Strategy:** `/docs/quality/test_strategy.md`
- **Test Fixtures:** `/tests/fixtures/tenants.ts`
- **CI Workflow:** `/.github/workflows/ci.yml`

---

**Questions or issues?** File a bug report or reach out to the QA team.

Happy testing! 🎭
