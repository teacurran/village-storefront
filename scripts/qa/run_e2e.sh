#!/usr/bin/env bash
# Village Storefront E2E Test Runner
# Orchestrates Playwright test execution with prerequisite checks and artifact collection
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
# Future Work / TODOs
# -----------------------------------------------------------------------------

# TODO(I2.T8): Add REST-assured API smoke tests before running E2E tests
#   - Verify health endpoints (/q/health/live, /q/health/ready)
#   - Check database connectivity
#   - Validate OpenAPI spec endpoint (/q/openapi)
#   - Ensure tenant resolution filter is active

# TODO(I3.T8): Add performance test integration
#   - Run Gatling/Locust load tests after E2E tests pass
#   - Capture p95 latency metrics for checkout/cart APIs
#   - Enforce performance budgets (checkout <300ms, storefront LCP <2s)
#   - Generate performance report in target/gatling/

# TODO(I4.T8): Add chaos testing integration
#   - Trigger database failover scenarios
#   - Simulate Stripe/carrier API outages
#   - Verify kill-switch + fallback behavior
#   - Capture chaos test results in target/chaos/

# TODO(I5.T7): Generate release readiness report
#   - Aggregate coverage metrics (unit/integration/e2e/mutation)
#   - Parse test results for failure trends
#   - Include unresolved risks and rollback plans
#   - Output consolidated report to target/release-readiness-report.html

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
