# Village Storefront Testing Strategy

This document describes the comprehensive testing approach for Village Storefront, including test types, tools, execution cadence, and quality expectations.

## Table of Contents

- [Overview](#overview)
- [Testing Pyramid](#testing-pyramid)
- [Test Types](#test-types)
- [Tools and Frameworks](#tools-and-frameworks)
- [Execution Cadence](#execution-cadence)
- [Quality Gates](#quality-gates)
- [Local Development](#local-development)
- [CI/CD Integration](#cicd-integration)
- [Metrics and Reporting](#metrics-and-reporting)
- [Troubleshooting](#troubleshooting)

## Overview

Village Storefront employs a multi-layered testing strategy designed to ensure:
- **Functional correctness**: All features work as specified
- **Performance**: System meets SLO targets (95th percentile < 300ms for APIs)
- **Reliability**: Services remain available and recover gracefully from failures
- **Security**: Tenant isolation and data protection mechanisms function correctly
- **Documentation accuracy**: Architecture and plan documentation remain synchronized

### Testing Principles

1. **Shift Left**: Catch issues early through unit and integration tests
2. **Test in Production-like Environments**: Use containers and realistic data
3. **Automate Everything**: All tests run in CI without manual intervention
4. **Fast Feedback**: Unit tests run in < 2 minutes, full suite in < 20 minutes
5. **Maintainable**: Tests follow Page Object Model and use clear abstractions

## Testing Pyramid

```
              /\
             /  \  E2E Tests (Playwright, Cypress)
            /----\
           /      \ Integration Tests (Testcontainers)
          /--------\
         /          \ Unit Tests (JUnit, Vitest)
        /------------\
```

- **Unit Tests (70%)**: Fast, isolated tests of business logic
- **Integration Tests (20%)**: Test module interactions, database, external services
- **E2E Tests (10%)**: Full user journey tests across UI and API

## Test Types

### 1. Unit Tests

**Tools**: JUnit 5, Mockito, AssertJ (backend), Vitest (frontend)

**Scope**:
- Business logic in services
- Data mappers and DTOs
- Utility functions
- Component logic

**Coverage Target**: ≥80% line and branch coverage

**Execution**: On every commit via `./mvnw test`

**Location**:
- Backend: `src/test/java/villagecompute/storefront/`
- Admin SPA: `src/main/webui/src/**/__tests__/`

### 2. Integration Tests

**Tools**: Quarkus Test, Testcontainers, RestAssured

**Scope**:
- REST API endpoints
- Database queries with RLS policies
- Background job execution
- External service integration (Stripe stubs, S3 mocks)
- Tenant isolation enforcement

**Coverage Target**: All critical API endpoints, tenant boundary tests

**Execution**: Part of `./mvnw verify`

**Location**: `src/test/java/villagecompute/storefront/` (marked with `@QuarkusTest`)

### 3. End-to-End Tests

#### 3a. Playwright (Storefront & Platform Console)

**Location**: `tests/e2e/playwright/`

**Scope**:
- Storefront checkout flow (product browsing → cart → checkout → order confirmation)
- Admin dashboard login and navigation
- Platform console tenant management and impersonation flows

**Browsers**: Chromium, Firefox, WebKit (desktop), Mobile Chrome/Safari

**Execution**:
- Nightly: Full suite across all browsers
- PR: Chromium only
- Manual: `npm run test:e2e`

**Key Features**:
- Page Object Model pattern for maintainability
- Automatic retry on failure (2 retries in CI)
- Video recording on failure
- Screenshot capture on assertion failures

#### 3b. Cypress (POS Offline)

**Location**: `tests/admin/`

**Scope**:
- POS offline queue management (add transaction while offline)
- Device pairing and Stripe Terminal pairing
- Transaction sync when back online
- Failed transaction retry

**Execution**:
- Nightly: Full suite
- Manual: `npm run test:cypress`

**Key Features**:
- Custom commands for offline simulation
- IndexedDB inspection for queue validation
- Component testing for admin UI widgets

### 4. Load/Performance Tests

**Tool**: k6

**Location**: `tests/load/k6/`

**Scenarios**:
- **Checkout flow** (`checkout.js`): Tests cart → shipping → payment → order placement
- **Media upload** (`media-upload.js`): Tests upload negotiation → upload → processing pipeline

**Performance Targets**:
- API p95 < 300ms
- Checkout p95 < 500ms
- Media processing avg < 5s
- Error rate < 5%

**Execution**:
- Nightly: Full load test
- Pre-release: Smoke test (reduced load)
- Manual: `k6 run tests/load/k6/checkout.js`

**Output**: JSON results archived for trend analysis

### 5. Manifest Validation

**Tool**: Python script

**Location**: `tests/manifest/anchor_validation.py`

**Scope**:
Validates that all anchors referenced in:
- `.codemachine/artifacts/plan/plan_manifest.json`
- `.codemachine/artifacts/architecture/architecture_manifest.json`

...actually exist in the corresponding Markdown files.

**Execution**:
- On every commit
- Before generating documentation

**Why This Matters**: Ensures architectural decisions and plan references remain valid as code evolves.

## Tools and Frameworks

| Layer | Tool | Purpose |
|-------|------|---------|
| Backend Unit | JUnit 5, Mockito | Java unit testing |
| Backend Integration | Quarkus Test, Testcontainers | Integration testing with containers |
| Frontend Unit | Vitest | Vue component unit tests |
| Frontend E2E | Playwright | Cross-browser storefront/platform tests |
| Admin/POS E2E | Cypress | POS offline scenarios, admin flows |
| Load Testing | k6 | Performance and stress testing |
| API Testing | RestAssured | REST API contract validation |
| Documentation | Python | Manifest anchor validation |
| Coverage | JaCoCo, c8 | Code coverage reporting |
| Quality Gate | SonarCloud | Static analysis and quality enforcement |

## Execution Cadence

### On Every Commit (CI Trigger)

✅ **Run immediately**:
- Backend unit tests
- Backend integration tests (JVM mode)
- Frontend unit tests (Admin SPA)
- Spotless formatting check
- OpenAPI spec validation
- PlantUML diagram validation
- Manifest anchor validation

⏱️ **Duration**: ~12 minutes

### Nightly (Scheduled)

✅ **Run at 2 AM UTC**:
- Full Playwright E2E suite (all browsers)
- Full Cypress POS offline suite
- k6 load tests (full load profile)
- Native build + integration tests

⏱️ **Duration**: ~45 minutes

**Reporting**: Results posted to dedicated Slack channel, metrics exported to Grafana

### Pre-Release (Manual/Tag Trigger)

✅ **Run on release branches**:
- All CI checks
- Load test smoke run (reduced duration)
- Manual UAT checklist validation

⏱️ **Duration**: ~20 minutes

### On-Demand (workflow_dispatch)

✅ **Manually triggered**:
- Individual test suite execution
- Load tests against staging environment
- Specific test suite re-runs after fixes

## Quality Gates

Tests must pass these gates before merge:

### Coverage Gates
- ✅ Backend: ≥80% line + branch coverage (JaCoCo)
- ✅ Frontend: ≥75% line coverage (c8/Vitest)
- ❌ **Blocker**: Coverage drops below threshold

### Performance Gates
- ✅ Checkout API p95 < 300ms
- ✅ Order placement p95 < 500ms
- ✅ Media processing avg < 5s
- ❌ **Blocker**: Performance degrades > 10%

### Reliability Gates
- ✅ E2E test pass rate > 95%
- ✅ API error rate < 5%
- ❌ **Warning**: Flaky tests (intermittent failures)

### Documentation Gates
- ✅ All manifest anchors resolvable
- ✅ OpenAPI spec valid
- ✅ PlantUML diagrams renderable
- ❌ **Blocker**: Broken documentation references

## Local Development

### Running Tests Locally

#### All Backend Tests
```bash
./mvnw test                    # Unit tests only
./mvnw verify                  # Unit + integration tests
npm run test                   # Runs ./mvnw verify with coverage
npm run test:native            # Native mode tests (slower)
```

#### Frontend Tests
```bash
npm run spa:test               # Admin SPA unit tests
npm run spa:test:coverage      # With coverage report
```

#### E2E Tests
```bash
# Playwright
cd tests/e2e/playwright
npm install
npx playwright install --with-deps
npm test                       # Headless
npm run test:headed            # With browser UI
npm run test:debug             # Debug mode

# Cypress
cd tests/admin
npm install
npm run open                   # Interactive mode
npm test                       # Headless
```

#### Load Tests
```bash
cd tests/load/k6

# Install k6 first (see k6/README.md)
k6 run checkout.js
k6 run media-upload.js

# With custom base URL
BASE_URL=http://staging.example.com k6 run checkout.js
```

#### Manifest Validation
```bash
cd tests/manifest
pip install -r requirements.txt
python anchor_validation.py --verbose
```

### Prerequisites

- **Java 21** (Temurin or GraalVM)
- **Node 20+** (for Playwright, Cypress, Admin SPA)
- **Python 3.11+** (for manifest validation)
- **k6** (install via package manager, see load test README)
- **Docker** (for Testcontainers, Postgres)

### Local Test Database

Tests use Testcontainers to spin up ephemeral PostgreSQL instances. For faster local iteration:

```bash
# Start persistent test database
docker-compose up -d postgres

# Run migrations
cd migrations && mvn migration:up -Dmigration.env=test

# Configure tests to use existing DB
export QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/storefront_test
```

## CI/CD Integration

### Workflow Files

| Workflow | File | Trigger | Purpose |
|----------|------|---------|---------|
| CI | `.github/workflows/ci.yml` | Every push/PR | Linting, unit tests, integration tests |
| Test Suite | `.github/workflows/test_suite.yml` | Nightly, manual | E2E, load tests, manifest validation |
| Release | `.github/workflows/release.yml` | Tag push | Pre-release validation + deployment |

### Observability Hooks

- `test_suite.yml` exports metrics for every suite (Playwright, Cypress, k6 checkout/media, manifest validation) via `tools/publish-test-metrics.cjs`. Provide `GRAFANA_PUSH_URL` + `GRAFANA_API_KEY` GitHub secrets to push JSON payloads directly into Grafana Cloud/Agent HTTP ingest.
- The workflow writes a consolidated Markdown summary to `GITHUB_STEP_SUMMARY` and posts a PR comment tagged with `<!-- test-suite-summary -->` so reviewers always see the latest pass/fail state without expanding the Actions UI.

### Artifacts

All test runs produce artifacts:
- **Coverage reports**: `target/site/jacoco/` (retained 30 days)
- **E2E videos**: `target/playwright-videos/`, `target/cypress-videos/` (retained 14 days)
- **Screenshots**: On failure only (retained 7 days)
- **Load test results**: JSON summaries (retained 30 days)
- **Test results**: JUnit XML, JSON (retained 30 days)

### Notifications

- ❌ **Failures**: Posted to `#ci-alerts` Slack channel
- ✅ **Nightly summary**: Posted to `#qa-reports` Slack channel
- 📊 **Metrics**: Exported to Grafana for trend analysis
- 📝 **GitHub PR comment**: `test_suite.yml` updates an inline summary table whenever the suite runs on pull requests, mirroring the details written to `$GITHUB_STEP_SUMMARY`.

## Metrics and Reporting

### Test Execution Metrics

Tracked via Grafana dashboards:
- Test pass/fail rate (by suite)
- Test execution duration trends
- Flaky test identification
- Coverage trends over time
- Grafana receives structured payloads from `tools/publish-test-metrics.cjs`, letting dashboards overlay Playwright/Cypress pass counts, k6 latency/error rates, and manifest anchor coverage on a single panel without scraping logs.

### Performance Metrics

From k6 load tests:
- Request duration (p50, p95, p99)
- Error rates
- Throughput (requests/second)
- Resource utilization (CPU, memory)

### Quality Metrics

From SonarCloud:
- Code coverage %
- Technical debt ratio
- Code smells, bugs, vulnerabilities
- Duplicate code %

## Troubleshooting

### Flaky Tests

**Symptom**: Tests pass/fail intermittently

**Common Causes**:
1. Race conditions in async operations
2. Timing-dependent assertions
3. Non-deterministic test data
4. External service flakiness

**Remediation**:
- Use Playwright auto-waiting instead of `sleep()`
- Implement retry logic for external calls
- Seed test data deterministically
- Mock external services in integration tests
- Tag flaky tests with `@Flaky` and investigate

### Slow Tests

**Symptom**: Test suite taking > 20 minutes

**Diagnosis**:
```bash
# Identify slowest tests
./mvnw test -Dsurefire.printSummary=true | grep "Time elapsed"

# Profile Playwright tests
npx playwright test --reporter=json | jq '.suites[].specs[].tests[] | {title, duration}'
```

**Optimization**:
- Parallelize independent tests
- Use test fixtures to reduce setup time
- Optimize database queries in integration tests
- Reduce `sleep()` durations where safe

### Coverage Drops

**Symptom**: Coverage falls below 80%

**Diagnosis**:
- Check `target/site/jacoco/index.html` for uncovered packages
- Review PR diff for new code without tests

**Remediation**:
- Add unit tests for new business logic
- Add integration tests for new API endpoints
- Exclude generated code from coverage (e.g., DTOs, mappers)

### Load Test Failures

**Symptom**: k6 tests fail thresholds

**Diagnosis**:
```bash
# Review detailed results
jq '.metrics' tests/load/k6/checkout-results.json

# Check for error patterns
jq '.metrics.http_req_failed' tests/load/k6/checkout-results.json
```

**Remediation**:
- Profile slow endpoints with APM tools
- Check database connection pool configuration
- Review N+1 query issues
- Verify external service latency (Stripe, S3)
- Scale up test environment resources

### Manifest Validation Failures

**Symptom**: Anchor validation script fails

**Diagnosis**:
```bash
cd tests/manifest
python anchor_validation.py --verbose
```

**Common Issues**:
1. Anchor renamed in markdown but not in manifest
2. Anchor deleted but still referenced
3. Typo in anchor name

**Remediation**:
- Update manifest JSON to match markdown changes
- Remove deleted anchors from manifests
- Ensure anchors follow `kebab-case` convention

## Smoke vs. Regression Cadence

### Smoke Tests (Quick Validation)

**When**: Before deployment, after critical fixes

**Scope**:
- Core checkout flow (Playwright)
- Admin login
- POS offline queue (single scenario)
- Basic API health checks

**Duration**: ~5 minutes

**Command**:
```bash
# Playwright smoke tests
npm run test:e2e -- --grep "@smoke"

# Cypress smoke tests
npm run test:cypress -- --spec "e2e/pos-offline.cy.ts"
```

### Regression Tests (Full Validation)

**When**: Nightly, pre-release

**Scope**:
- Full Playwright suite (all browsers)
- Full Cypress suite
- Load tests
- Manifest validation
- Native build tests

**Duration**: ~45 minutes

**Command**: Triggered automatically by `.github/workflows/test_suite.yml`

## Best Practices

### Writing Maintainable Tests

1. **Use Page Object Model**: Encapsulate page interactions in reusable classes
2. **Follow AAA Pattern**: Arrange, Act, Assert structure
3. **One assertion per test**: Or logically grouped assertions
4. **Descriptive test names**: `should reject login with invalid credentials`
5. **Avoid test interdependencies**: Each test should be independent
6. **Clean up after tests**: Use `@AfterEach` / `afterEach()` hooks

### Data Management

- **Test fixtures**: Use factory methods to create test data
- **Database state**: Reset between test classes, not test methods (for speed)
- **Isolation**: Each test should create its own tenant/user data
- **Realistic data**: Use production-like data volumes for load tests

### Debugging Failures

1. **Review artifacts**: Screenshots, videos, logs
2. **Run locally**: Reproduce with `--headed` flag
3. **Add logging**: Temporary debug statements in tests
4. **Bisect**: Use `git bisect` to find when test started failing
5. **Isolate**: Run single test file to reduce noise

---

## Appendix

### Test Data Seeding

For E2E tests, seed test data via:
```bash
# Seed test tenant + products
./mvnw quarkus:dev -Dquarkus.profile=test-data

# Seed via API
curl -X POST http://localhost:8080/api/internal/seed-test-data
```

### Test Credentials

Stored in `.env.test` (gitignored):
```
TEST_ADMIN_EMAIL=admin@test.tenant
TEST_ADMIN_PASSWORD=TestPassword123!
TEST_POS_DEVICE_ID=TEST-DEVICE-001
TEST_POS_PASSCODE=1234
```

### CI Environment Variables

Set in GitHub repository secrets:
- `SONAR_TOKEN`: SonarCloud authentication
- `DOCKER_USERNAME`, `DOCKER_PASSWORD`: Docker Hub credentials
- `STRIPE_TEST_API_KEY`: Stripe test mode key (for load tests)

---

**Document Version**: 1.0
**Last Updated**: 2026-01-03
**Owner**: QA Team
**Review Cadence**: Quarterly
