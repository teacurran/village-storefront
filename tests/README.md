# Village Storefront Test Suites

This directory contains comprehensive test suites for the Village Storefront platform.

## Directory Structure

```
tests/
├── e2e/
│   └── playwright/          # Playwright end-to-end tests
│       ├── pages/           # Page Object Models
│       ├── *.spec.ts        # Test specifications
│       └── playwright.config.ts
├── admin/                   # Cypress tests for admin/POS
│   ├── e2e/                 # E2E test scenarios
│   ├── support/             # Custom commands and helpers
│   └── cypress.config.ts
├── storefront/              # Storefront-specific test fixtures (future)
├── load/
│   └── k6/                  # k6 load test scripts
│       ├── checkout.js      # Checkout flow load test
│       ├── media-upload.js  # Media pipeline load test
│       └── README.md        # k6 usage guide
└── manifest/                # Documentation validation
    ├── anchor_validation.py # Manifest anchor validator
    └── requirements.txt     # Python dependencies
```

## Quick Start

### Prerequisites

Install required dependencies:

```bash
# Install root dependencies
npm install

# Install Playwright browsers
cd tests/e2e/playwright
npm install
npx playwright install --with-deps

# Install Cypress
cd tests/admin
npm install

# Install k6 (macOS)
brew install k6

# Install Python dependencies
cd tests/manifest
pip install -r requirements.txt
```

### Running Tests

From project root:

```bash
# Backend unit + integration tests
npm test

# Playwright E2E tests
npm run test:e2e

# Playwright with UI (interactive)
npm run test:e2e:ui

# Cypress tests
npm run test:cypress

# Cypress interactive mode
npm run test:cypress:open

# k6 load tests
npm run test:k6

# Manifest validation
npm run test:manifest

# Run all tests
npm run test:all
```

## Test Suites

### 1. Playwright E2E Tests

**Location**: `tests/e2e/playwright/`

**Coverage**:
- **Storefront Flows**:
  - Guest checkout (complete product browsing → cart → checkout → order)
  - Authenticated checkout with saved payment methods
  - Loyalty points enrollment and redemption
  - Gift card balance check and redemption (full/partial)
  - Product search and cart management
- **Admin Dashboard**:
  - Catalog CRUD (create products with variants, edit, publish, unpublish)
  - Inventory management (stock adjustments, transfers, audit logs)
  - Order management (view details, process full/partial refunds, email verification)
- **Platform Console**:
  - Tenant management and health metrics
  - Impersonation with reason tracking and TTL enforcement
  - Audit trail verification for all impersonation actions
- **Consignment**:
  - Consignor portal login and payout requests
  - Stripe Express transfer initiation
  - Payout history tracking
- **Headless API**:
  - OAuth client credentials authentication
  - API-driven cart creation and order submission
  - Webhook verification
- **Multi-Tenant Isolation**:
  - Product/order isolation between tenants
  - Cross-tenant access prevention (API + UI)
  - Tenant-specific feature flags

**Key Features**:
- Page Object Model pattern with 10+ page objects
- Cross-browser testing (Chromium, Firefox, WebKit)
- Mobile testing (iOS Safari, Android Chrome)
- Multi-tenant test fixtures (3 test tenants)
- Auto-retry on failure (2 retries in CI)
- Always-on video recording for debugging
- Screenshot capture on assertion failures
- Deterministic test data seeding

**Test Counts**: 31+ E2E tests (Playwright + Cypress)
**Execution Time**: ~15 minutes (CI with 4 parallel workers)

**Documentation**: See [docs/testing/strategy.md](../docs/testing/strategy.md)

### 2. Cypress POS Offline Tests

**Location**: `tests/admin/`

**Coverage**:
- POS offline queue management
- Device pairing (POS + Stripe Terminal)
- Transaction sync when back online
- Failed transaction retry workflows

**Key Features**:
- Custom offline simulation commands
- IndexedDB inspection utilities
- Component testing support

### 3. k6 Load Tests

**Location**: `tests/load/k6/`

**Coverage**:
- Checkout API flow under load
- Media upload and processing pipeline

**Performance Targets**:
- API p95 < 300ms
- Checkout p95 < 500ms
- Media processing avg < 5s
- Error rate < 5%

**Documentation**: See `tests/load/k6/README.md`

### 4. Manifest Validation

**Location**: `tests/manifest/`

**Purpose**: Validates that all anchors referenced in plan and architecture manifests exist in the corresponding markdown files.

**Usage**:
```bash
cd tests/manifest
python anchor_validation.py --verbose
```

## CI/CD Integration

Tests are integrated into GitHub Actions workflows:

### CI Workflow (`.github/workflows/ci.yml`)
- Runs on every push/PR
- Executes: backend tests, SPA tests, linting, manifest validation
- Duration: ~12 minutes

### Test Suite Workflow (`.github/workflows/test_suite.yml`)
- Runs: nightly at 2 AM UTC, on-demand
- Executes: full E2E suite, load tests
- Duration: ~45 minutes

## Test Results

Results are archived as GitHub Actions artifacts:
- **Coverage reports**: 30 days retention
- **E2E videos/screenshots**: 7-14 days retention
- **Load test results**: 30 days retention

## Configuration

### Environment Variables

Tests can be configured via environment variables:

```bash
# Base URL for tests
BASE_URL=http://localhost:8080

# API URL (if different)
API_URL=http://localhost:8080/api/v1

# Test credentials (set in .env.test)
TEST_ADMIN_EMAIL=admin@test.tenant
TEST_ADMIN_PASSWORD=TestPassword123!
```

### Test Data

Tests use:
- **Testcontainers**: Ephemeral Postgres instances for integration tests
- **Mock data**: Generated via factory methods in tests
- **Seed scripts**: Available for local development

## Troubleshooting

### E2E Test Setup Issues

#### DNS Resolution Fails
**Symptom**: Tests fail with "Unable to resolve tenant-a.test.local"

**Solution**:
1. Verify `/etc/hosts` entries are correct
2. Restart terminal session
3. Flush DNS cache:
   - macOS: `sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder`
   - Linux: `sudo systemd-resolve --flush-caches`
   - Windows: `ipconfig /flushdns`

#### Missing Test Data
**Symptom**: "Product not found" or "User does not exist" errors

**Solution**: Re-run seeding script:
```bash
cd tests/e2e/playwright
npm run seed:e2e
```

#### Database Connection Errors
**Symptom**: "Connection refused" or "ECONNREFUSED 5432"

**Solution**:
```bash
# Check Docker containers
docker compose ps

# Restart PostgreSQL
docker compose restart postgres

# View PostgreSQL logs
docker compose logs postgres
```

#### Application Health Check Timeout
**Symptom**: "Timeout waiting for http://localhost:8080/q/health/live"

**Solution**:
1. Check Quarkus startup logs for errors
2. Verify no port conflicts: `lsof -i :8080`
3. Increase timeout in test configuration

### Playwright Tests Failing

```bash
# Run with headed browser to see what's happening
npm run test:e2e:headed

# Debug specific test with breakpoints
npm run test:e2e:debug

# Run single test file
cd tests/e2e/playwright
npm test -- storefront-flows.spec.ts

# View test report
npx playwright show-report
```

**Common Issues**:
- **Flaky tests**: Use `waitForResponse()` instead of fixed timeouts
- **Selector not found**: Verify `data-test` attributes exist in UI
- **Video not recorded**: Ensure `video: 'on'` in `playwright.config.ts`
- **Test passing locally but failing in CI**: Check for timing issues or resource constraints

### Cypress Tests Failing

```bash
# Open Cypress UI for interactive debugging
npm run test:cypress:open

# Run in headed mode
cd tests/admin
npx cypress run --headed --browser chrome

# View video recordings
open cypress/videos/
```

### Load Tests Not Meeting Thresholds

1. Check application logs for errors
2. Review database query performance
3. Profile slow endpoints
4. Verify external service availability
5. Check Grafana dashboards for bottlenecks

### Manifest Validation Errors

```bash
# Run with verbose output
cd tests/manifest
python anchor_validation.py --verbose
```

Common issues:
- Anchor renamed in markdown but not in manifest
- Anchor deleted but still referenced in manifest
- Typo in anchor name

### CI-Specific Issues

#### Tests Pass Locally but Fail in CI
**Common Causes**:
1. Timing issues (CI is slower) → Add more generous timeouts
2. Missing dependencies → Verify npm ci runs successfully
3. Database state → Ensure seeding runs before tests
4. Resource constraints → Check CI worker specs

**Debug Strategy**:
- Download video artifacts from failed CI run
- Review GitHub Actions logs
- Run locally with `CI=true npm test`

#### Video/Screenshot Artifacts Not Uploaded
**Solution**: Verify paths in `.github/workflows/test_suite.yml` match output directories in `playwright.config.ts`

## Performance Benchmarks

Expected test execution times:
- Backend unit tests: ~2 minutes
- Backend integration tests: ~5 minutes
- Playwright E2E (single browser): ~8 minutes
- Cypress POS tests: ~5 minutes
- k6 load tests: ~6 minutes
- Manifest validation: ~10 seconds

**Full suite (nightly)**: ~45 minutes

## Contributing

When adding new tests:

1. **Follow existing patterns**: Use Page Object Model for UI tests
2. **Write descriptive test names**: `should allow checkout with gift card`
3. **Keep tests independent**: Each test should be runnable in isolation
4. **Add to appropriate suite**: E2E vs integration vs unit
5. **Update documentation**: Add to this README if introducing new test type

## Additional Resources

- [Testing Strategy Documentation](../docs/testing/strategy.md)
- [Java Project Standards](../docs/java-project-standards.adoc)
- [Playwright Documentation](https://playwright.dev/)
- [Cypress Documentation](https://docs.cypress.io/)
- [k6 Documentation](https://k6.io/docs/)

## Support

For questions or issues with tests:
- Check [docs/testing/strategy.md](../docs/testing/strategy.md)
- Review test artifacts in CI
- Open issue in GitHub repository
