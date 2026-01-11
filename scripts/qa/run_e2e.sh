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
#   E2E_PLACEHOLDER_CMD - Override placeholder stub command logged before suites run
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

# Placeholder command proves CI wiring until Cypress + expanded Playwright suites land
E2E_PLACEHOLDER_CMD="${E2E_PLACEHOLDER_CMD:-"echo '[stub] TODO(I2.T8/I3.T8): wire Playwright + Cypress suites via scripts/qa/run_e2e.sh'"}"

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

run_placeholder_stub() {
    log_warn "Executing placeholder E2E command (replace when suites are wired): $E2E_PLACEHOLDER_CMD"
    bash -c "$E2E_PLACEHOLDER_CMD"
    log_warn "Placeholder command completed; proceeding to Playwright orchestration."
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
# Placeholder (TODO wiring for Cypress + additional suites)
# -----------------------------------------------------------------------------

run_placeholder_stub

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

# TODO(I3.T8): Performance + chaos testing implementation
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

# TODO(I5.T7): Release readiness report generation
#   - Add release_readiness_report() function that aggregates all test results
#   - Parse JaCoCo coverage XML: modules/core-platform/target/site/jacoco/jacoco.xml
#   - Parse Playwright JSON results: target/playwright-results.json
#   - Parse performance results (if available): target/gatling/results.json
#   - Parse mutation testing results (if available): target/pitest/mutations.xml
#   - Generate consolidated HTML report: target/release-readiness-report.html
#   - Include sections:
#     * Final coverage metrics (unit/integration/e2e/mutation)
#     * Performance benchmarks vs. budgets
#     * Unresolved risks from test failures
#     * Rollback plans
#     * Tenant onboarding checklist
#     * Platform governance approval checklist
#   Deliverable: Expand this script to generate release-readiness-report.html

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
