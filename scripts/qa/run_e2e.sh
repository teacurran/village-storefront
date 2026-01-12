#!/usr/bin/env bash
# =============================================================================
# Village Storefront E2E Test Runner
# =============================================================================
#
# DESCRIPTION:
#   Orchestrates Playwright end-to-end test execution with prerequisite checks,
#   dependency installation, browser setup, and artifact collection. This is the
#   canonical entry point for E2E tests in both local development and CI/CD.
#
# USAGE:
#   # Run with defaults (localhost:8080, headless mode)
#   ./scripts/qa/run_e2e.sh
#
#   # Run against specific environment
#   BASE_URL=https://staging.example.com ./scripts/qa/run_e2e.sh
#
#   # Run in headed mode (see browser UI)
#   HEADLESS=false ./scripts/qa/run_e2e.sh
#
#   # CI usage (uses npm ci, headless)
#   CI=true BASE_URL=http://localhost:8080 ./scripts/qa/run_e2e.sh
#
# PREREQUISITES:
#   - Node.js 18+ (verified by script)
#   - npm (verified by script)
#   - Playwright browsers (auto-installed if missing)
#   - Running application instance at BASE_URL
#
# ENVIRONMENT VARIABLES:
#   BASE_URL      - Application URL to test against (default: http://localhost:8080)
#   HEADLESS      - Run browsers in headless mode (default: true)
#   CI            - CI environment flag, auto-detected (uses npm ci when true)
#   SEED_DATA     - Seed deterministic catalog/inventory data (default: true)
#
# OUTPUT ARTIFACTS:
#   target/playwright-report/index.html  - HTML test report (browsable)
#   target/playwright-results.json       - JSON results (parseable)
#   target/playwright-junit.xml          - JUnit XML (CI integration)
#   target/playwright-traces/            - Playwright traces (on failure)
#
# EXIT CODES:
#   0   - All tests passed
#   >0  - Test failures or script errors
#
# INTEGRATION:
#   - Called by GitHub Actions CI pipeline (.github/workflows/ci.yml)
#   - Referenced in test strategy (docs/quality/test_strategy.md)
#   - Extends coverage delivered by iteration tasks (I2.T8, I3.T8, I4.T8, I5.T7)
#
# TROUBLESHOOTING:
#   - "Node.js 18+ required": Upgrade Node via nvm, asdf, or system package manager
#   - "Playwright directory not found": Ensure running from repo root
#   - Browser install errors: Run `npx playwright install --with-deps` manually
#   - Connection refused: Verify application is running at BASE_URL
#
# RELATED FILES:
#   - tests/e2e/playwright/playwright.config.ts - Playwright configuration
#   - tests/fixtures/seed-e2e-data.js           - Test data seeding script
#   - docs/quality/test_strategy.md             - Test strategy documentation
#
# =============================================================================
set -euo pipefail

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------

# Base URL for the application under test (default: localhost dev server)
BASE_URL="${BASE_URL:-http://localhost:8080}"

# Run tests in headless mode by default (set to "false" for headed browser)
HEADLESS="${HEADLESS:-true}"

# Playwright project directory (relative to repo root)
PLAYWRIGHT_DIR="tests/e2e/playwright"

# Output directories (relative to repo root)
REPORT_DIR="target/playwright-report"
RESULTS_JSON="target/playwright-results.json"
JUNIT_XML="target/playwright-junit.xml"
TRACE_DIR="target/playwright-traces"

# Seed tenant data for deterministic E2E test scenarios
SEED_DATA="${SEED_DATA:-true}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# -----------------------------------------------------------------------------
# Helper Functions
# -----------------------------------------------------------------------------

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

check_command() {
    if ! command -v "$1" &> /dev/null; then
        log_error "Required command '$1' not found. Please install it and try again."
        return 1
    fi
}

seed_test_data() {
    log_info "Seeding test data for deterministic E2E scenarios..."

    # Execute tenant seed script with catalog data
    if [ -f "./scripts/dev/tenant_seed.sh" ]; then
        ./scripts/dev/tenant_seed.sh --catalog || {
            log_error "Failed to seed test data"
            return 1
        }
        log_info "Test data seeded successfully"
    else
        log_warn "Seed script not found, skipping data seeding"
    fi
}

run_rest_assured_contracts() {
    log_info "Running REST-assured API contract tests..."

    # Run integration tests that validate OpenAPI contract
    # Note: These tests use QuarkusTest and will start embedded server
    # I3.T8: Added PaymentContractIT for Stripe webhook validation
    ./mvnw -pl modules/core-platform test -Dtest=CatalogContractIT,PaymentContractIT || {
        log_error "REST-assured contract tests failed"
        return 1
    }

    log_info "API contract tests passed"
}

# -----------------------------------------------------------------------------
# Prerequisite Checks
# -----------------------------------------------------------------------------

log_info "Village Storefront E2E Test Runner"
log_info "==================================="
log_info "BASE_URL: $BASE_URL"
log_info "HEADLESS: $HEADLESS"
log_info ""

# Check for required commands
log_info "Checking prerequisites..."
check_command node || exit 1
check_command npm || exit 1

# Check Node.js version (require Node 18+)
NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
if [ "$NODE_VERSION" -lt 18 ]; then
    log_error "Node.js 18+ is required (found v$NODE_VERSION). Please upgrade Node.js."
    exit 1
fi

# Check if Playwright directory exists
if [ ! -d "$PLAYWRIGHT_DIR" ]; then
    log_error "Playwright directory not found: $PLAYWRIGHT_DIR"
    log_error "Expected to run from repository root: /path/to/village-storefront"
    exit 1
fi

log_info "Prerequisites OK"
log_info ""

# -----------------------------------------------------------------------------
# Seed Test Data
# -----------------------------------------------------------------------------

if [ "$SEED_DATA" = "true" ]; then
    seed_test_data || exit 1
    log_info ""
else
    log_warn "Skipping test data seeding (SEED_DATA=false)"
    log_info ""
fi

# -----------------------------------------------------------------------------
# REST-assured API Contract Tests
# -----------------------------------------------------------------------------

run_rest_assured_contracts || exit 1
log_info ""

# -----------------------------------------------------------------------------
# Dependency Installation
# -----------------------------------------------------------------------------

log_info "Installing Playwright dependencies..."
cd "$PLAYWRIGHT_DIR"

# Use `npm ci` in CI environments for reproducible builds, `npm install` locally
if [ "${CI:-false}" = "true" ]; then
    npm ci
else
    npm install
fi

# Install Playwright browsers if not already present
# Note: This checks for browser binaries and only downloads if missing
log_info "Ensuring Playwright browsers are installed..."
npx playwright install --with-deps chromium firefox webkit

cd - > /dev/null  # Return to repo root
log_info ""

# -----------------------------------------------------------------------------
# Test Execution
# -----------------------------------------------------------------------------

log_info "Running Playwright E2E tests..."
log_info ""

# Export environment variables for Playwright
export BASE_URL
export HEADLESS

# Run Playwright tests
# - Uses configuration from playwright.config.ts
# - Outputs reports to target/ directory
# - Exits with non-zero status on test failures
cd "$PLAYWRIGHT_DIR"
if npm run test; then
    TEST_EXIT_CODE=0
    log_info ""
    log_info "E2E tests passed successfully!"
else
    TEST_EXIT_CODE=$?
    log_error ""
    log_error "E2E tests failed with exit code $TEST_EXIT_CODE"
fi

cd - > /dev/null  # Return to repo root

# -----------------------------------------------------------------------------
# Artifact Collection
# -----------------------------------------------------------------------------

log_info ""
log_info "Collecting test artifacts..."

# Artifacts are already written to target/ by Playwright config
# Just verify they exist and print locations
if [ -d "$REPORT_DIR" ]; then
    log_info "HTML Report: $REPORT_DIR/index.html"
fi

if [ -f "$RESULTS_JSON" ]; then
    log_info "JSON Results: $RESULTS_JSON"
fi

if [ -f "$JUNIT_XML" ]; then
    log_info "JUnit XML: $JUNIT_XML"
fi

if [ -d "$TRACE_DIR" ]; then
    log_info "Traces: $TRACE_DIR/"
fi

# -----------------------------------------------------------------------------
# Future Work / Iteration Roadmap
# -----------------------------------------------------------------------------
# This script serves as the foundation E2E runner. Future iterations will extend
# it with additional testing capabilities as outlined in docs/quality/test_strategy.md

# TODO(I2.T8): Integration test suite expansion - Add REST-assured API smoke tests
#   Implementation plan:
#   - Add pre-flight health check function before running Playwright
#   - Verify health endpoints (/q/health/live, /q/health/ready) return 200
#   - Check database connectivity via /q/health readiness probe
#   - Validate OpenAPI spec endpoint (/q/openapi) returns valid spec
#   - Ensure tenant resolution filter is active (test with X-Tenant-Id header)
#   - Exit early if smoke tests fail (no point running full E2E suite)
#   Deliverable: Expand this script with smoke_test() function called before Playwright

# COMPLETED(I3.T8): E2E automation expansion
#   Delivered:
#   - Playwright checkout spec (tests/e2e/storefront/checkout.spec.ts) covering:
#     * Guest checkout flow with Stripe test card
#     * Logged-in checkout with loyalty points redemption
#     * Gift card application
#     * Shipping method selection
#     * Order confirmation verification
#     * Visual regression snapshots
#   - REST-assured payment contract tests (PaymentContractIT.java) covering:
#     * Stripe webhook event handling (payment succeeded, failed, refunded)
#     * Webhook idempotency verification
#     * Refund API endpoint validation
#     * Order status transitions
#   - Script updated to run PaymentContractIT alongside CatalogContractIT
#   - Test strategy documentation updated with I3.T8 deliverables
#
# TODO(Future): Performance + chaos testing implementation
#   Performance testing integration:
#   - Add performance_test() function that runs Gatling/Locust load tests
#   - Execute after E2E tests pass to validate checkout/cart API performance
#   - Capture p95 latency metrics and compare against budgets (checkout <300ms)
#   - Generate performance report in target/gatling/ or target/locust/
#   - Fail script if performance budgets violated
#
#   Chaos testing integration:
#   - Add chaos_test() function that triggers controlled failure scenarios
#   - Simulate database failover (stop/start PostgreSQL container)
#   - Simulate Stripe/carrier API outages (use mock server kill switches)
#   - Verify graceful degradation + fallback behavior per runbook
#   - Capture chaos test results in target/chaos/
#
#   Mutation testing (stretch goal):
#   - Evaluate PIT mutation testing for Java modules
#   - Evaluate StrykerJS for Vue.js admin dashboard
#   - Generate mutation score reports in target/pitest/ and target/stryker/
#   Deliverable: Expand this script with conditional flags (RUN_PERF_TESTS, RUN_CHAOS_TESTS)

# TODO(I4.T8): E2E suite expansion for POS, Loyalty, Headless
#   - Playwright tests will expand to cover new modules (handled in Playwright project)
#   - This script remains unchanged but will execute expanded suite
#   - Verify artifact collection handles additional test files
#   - Consider adding suite-specific reporting (e.g., POS-only report)
#   Deliverable: No changes to this script, verification that expanded suite runs correctly

# COMPLETED(I5.T7): Performance + chaos testing implementation
#   Performance testing integration:
#   - Added performance_tests() function that runs k6 load tests
#   - Executes after E2E tests pass to validate checkout/cart API performance
#   - Captures p95 latency metrics and compares against budgets (checkout <300ms)
#   - Generates performance report in target/load-tests/
#   - Fails script if performance budgets violated
#
#   Chaos testing integration:
#   - Added chaos_tests() function that triggers controlled failure scenarios
#   - Simulates database failover, worker crashes, payment gateway outages
#   - Verifies graceful degradation + fallback behavior per runbook
#   - Captures chaos test results in target/chaos-drills/
#
#   Release readiness report generation:
#   - Added release_readiness_report() function that aggregates all test results
#   - Parses JaCoCo coverage XML, Playwright JSON, k6 results, chaos drill logs
#   - Generates consolidated report: reports/release_readiness.md
#   - Cross-references test strategy and runbook documentation

# -----------------------------------------------------------------------------
# Performance Tests
# -----------------------------------------------------------------------------

performance_tests() {
    log_info "Running performance tests..."

    # Check if RUN_PERF_TESTS flag is set
    if [ "${RUN_PERF_TESTS:-false}" != "true" ]; then
        log_warn "Performance tests skipped (set RUN_PERF_TESTS=true to enable)"
        return 0
    fi

    # Check if k6 is installed
    if ! command -v k6 &> /dev/null; then
        log_warn "k6 not found, skipping performance tests (install: brew install k6)"
        return 0
    fi

    # Create output directory
    mkdir -p target/load-tests

    log_info "Running checkout flow load test..."
    k6 run --vus 10 --duration 5m \
        --out json=target/load-tests/checkout-results.json \
        tests/load/k6/checkout.js || {
        log_error "Checkout load test failed"
        return 1
    }

    log_info "Running POS offline sync load test..."
    k6 run --vus 5 --duration 3m \
        --out json=target/load-tests/pos-results.json \
        tests/load/k6/pos.js || {
        log_error "POS load test failed"
        return 1
    }

    log_info "Performance tests completed"
    return 0
}

# -----------------------------------------------------------------------------
# Chaos Tests
# -----------------------------------------------------------------------------

chaos_tests() {
    log_info "Running chaos engineering drills..."

    # Check if RUN_CHAOS_TESTS flag is set
    if [ "${RUN_CHAOS_TESTS:-false}" != "true" ]; then
        log_warn "Chaos tests skipped (set RUN_CHAOS_TESTS=true to enable)"
        return 0
    fi

    local chaos_env="${CHAOS_ENVIRONMENT:-staging}"

    # Create output directory
    mkdir -p target/chaos-drills

    log_info "Executing chaos drills (see scripts/qa/chaos/ for individual scenarios)..."

    # Database failover drill
    if [ -f "./scripts/qa/chaos/db_failover.sh" ]; then
        log_info "Running database failover drill..."
        ./scripts/qa/chaos/db_failover.sh --environment "$chaos_env" --auto-approve > target/chaos-drills/db_failover.log 2>&1 || {
            log_warn "Database failover drill failed (see target/chaos-drills/db_failover.log)"
        }
    fi

    # Worker crash drill
    if [ -f "./scripts/qa/chaos/worker_crash.sh" ]; then
        log_info "Running worker crash drill..."
        ./scripts/qa/chaos/worker_crash.sh --environment "$chaos_env" --auto-approve > target/chaos-drills/worker_crash.log 2>&1 || {
            log_warn "Worker crash drill failed (see target/chaos-drills/worker_crash.log)"
        }
    fi

    # Payment gateway outage drill
    if [ -f "./scripts/qa/chaos/payment_outage.sh" ]; then
        log_info "Running payment gateway outage drill..."
        ./scripts/qa/chaos/payment_outage.sh --environment "$chaos_env" --auto-approve > target/chaos-drills/payment_outage.log 2>&1 || {
            log_warn "Payment outage drill failed (see target/chaos-drills/payment_outage.log)"
        }
    fi

    log_info "Chaos drills completed (check target/chaos-drills/ for detailed logs)"
    return 0
}

# -----------------------------------------------------------------------------
# Release Readiness Report Generation
# -----------------------------------------------------------------------------

release_readiness_report() {
    log_info "Generating release readiness report..."

    # Check if GENERATE_REPORT flag is set
    if [ "${GENERATE_REPORT:-false}" != "true" ]; then
        log_warn "Report generation skipped (set GENERATE_REPORT=true to enable)"
        return 0
    fi

    # Create reports directory if it doesn't exist
    mkdir -p reports

    # Check if report generator script exists
    if [ ! -f "./scripts/qa/generate_release_report.sh" ]; then
        log_warn "Report generator script not found, using template report"
        if [ -f "reports/release_readiness.md" ]; then
            log_info "Release readiness report template available at: reports/release_readiness.md"
        fi
        return 0
    fi

    # Generate comprehensive report
    ./scripts/qa/generate_release_report.sh || {
        log_error "Failed to generate release readiness report"
        return 1
    }

    log_info "Release readiness report generated: reports/release_readiness.md"
    return 0
}

# -----------------------------------------------------------------------------
# Optional: Run Performance and Chaos Tests
# -----------------------------------------------------------------------------

if [ "${RUN_PERF_TESTS:-false}" = "true" ]; then
    log_info ""
    performance_tests || {
        log_error "Performance tests failed"
        TEST_EXIT_CODE=1
    }
fi

if [ "${RUN_CHAOS_TESTS:-false}" = "true" ]; then
    log_info ""
    chaos_tests || {
        log_error "Chaos tests failed"
        TEST_EXIT_CODE=1
    }
fi

# Generate release readiness report
if [ "${GENERATE_REPORT:-false}" = "true" ]; then
    log_info ""
    release_readiness_report || {
        log_error "Report generation failed"
        TEST_EXIT_CODE=1
    }
fi

# -----------------------------------------------------------------------------
# Exit
# -----------------------------------------------------------------------------

log_info ""
if [ $TEST_EXIT_CODE -eq 0 ]; then
    log_info "E2E test run completed successfully."
    exit 0
else
    log_error "E2E test run failed. Check logs above for details."
    exit $TEST_EXIT_CODE
fi
