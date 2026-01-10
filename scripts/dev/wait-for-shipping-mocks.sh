#!/usr/bin/env bash
#
# wait-for-shipping-mocks.sh
# Waits for all shipping mock services to be healthy before proceeding.
# Used in CI and local testing to ensure mocks are ready before running tests.
#
# Task: I3.T7 - Local Development Tooling & QA Support
# Usage: ./scripts/dev/wait-for-shipping-mocks.sh [timeout_seconds]
#

set -euo pipefail

# Configuration
TIMEOUT_SECONDS=${1:-60}
USPS_URL="${USPS_MOCK_URL:-http://localhost:9100/health}"
UPS_URL="${UPS_MOCK_URL:-http://localhost:9101/health}"
FEDEX_URL="${FEDEX_MOCK_URL:-http://localhost:9102/health}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Wait for a single service to be healthy
wait_for_service() {
    local service_name=$1
    local health_url=$2
    local timeout=$3

    print_info "Waiting for ${service_name} at ${health_url}..."

    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        if curl -sf "${health_url}" > /dev/null 2>&1; then
            print_success "${service_name} is healthy"
            return 0
        fi

        sleep 1
        elapsed=$((elapsed + 1))

        # Print progress every 5 seconds
        if [ $((elapsed % 5)) -eq 0 ]; then
            print_info "Still waiting for ${service_name}... (${elapsed}s / ${timeout}s)"
        fi
    done

    print_error "${service_name} failed to become healthy after ${timeout} seconds"
    return 1
}

# Main script
main() {
    print_info "Checking shipping mock services health..."
    print_info "Timeout: ${TIMEOUT_SECONDS} seconds per service"
    echo ""

    local all_healthy=true

    # Check USPS mock
    if ! wait_for_service "USPS Mock (port 9100)" "${USPS_URL}" "${TIMEOUT_SECONDS}"; then
        all_healthy=false
    fi
    echo ""

    # Check UPS mock
    if ! wait_for_service "UPS Mock (port 9101)" "${UPS_URL}" "${TIMEOUT_SECONDS}"; then
        all_healthy=false
    fi
    echo ""

    # Check FedEx mock
    if ! wait_for_service "FedEx Mock (port 9102)" "${FEDEX_URL}" "${TIMEOUT_SECONDS}"; then
        all_healthy=false
    fi
    echo ""

    if [ "$all_healthy" = true ]; then
        print_success "✅ All shipping mock services are healthy and ready!"
        print_info "You can now run tests that depend on these services."
        echo ""
        echo "Test commands:"
        echo "  ./mvnw test -Dtest=ShippingServiceTest"
        echo "  ./mvnw test -Dtest=CheckoutE2ETest"
        echo "  npm run test"
        return 0
    else
        print_error "❌ One or more shipping mock services failed health checks"
        print_warning "Troubleshooting steps:"
        echo "  1. Check if services are running: docker compose ps"
        echo "  2. Check service logs: docker compose logs usps-mock ups-mock fedex-mock"
        echo "  3. Restart services: docker compose restart usps-mock ups-mock fedex-mock"
        echo "  4. Check port conflicts: sudo lsof -i :9100 -i :9101 -i :9102"
        return 1
    fi
}

# Run main function
main
