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
- Storefront checkout flow (product browsing → cart → checkout → order)
- Admin dashboard login and navigation
- Platform console tenant management and impersonation

**Key Features**:
- Page Object Model pattern
- Cross-browser testing (Chromium, Firefox, WebKit)
- Mobile testing (iOS Safari, Android Chrome)
- Auto-retry on failure
- Video/screenshot capture

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

### Playwright Tests Failing

```bash
# Run with headed browser to see what's happening
npm run test:e2e:headed

# Debug specific test
npm run test:e2e:debug
```

### Cypress Tests Failing

```bash
# Open Cypress UI for interactive debugging
npm run test:cypress:open
```

### Load Tests Not Meeting Thresholds

1. Check application logs for errors
2. Review database query performance
3. Profile slow endpoints
4. Verify external service availability

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
