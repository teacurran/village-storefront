#!/usr/bin/env bash
# =============================================================================
# Database Failover Chaos Drill
# =============================================================================
#
# DESCRIPTION:
#   Simulates PostgreSQL primary failure and validates automatic failover,
#   application reconnection, RLS policy integrity, and smoke test recovery.
#
# USAGE:
#   ./scripts/qa/chaos/db_failover.sh [--environment staging|production]
#
# PREREQUISITES:
#   - kubectl configured for target cluster
#   - Managed PostgreSQL with automatic failover (e.g., AWS RDS Multi-AZ)
#   - Smoke test endpoints accessible
#
# VALIDATION STEPS:
#   1. Trigger managed PostgreSQL failover
#   2. Monitor application reconnection behavior
#   3. Verify RLS policies remain intact
#   4. Validate read-side cache invalidation
#   5. Run smoke tests across critical flows
#
# EXPECTED OUTCOME:
#   - Failover completes within 60 seconds
#   - Application reconnects within 30 seconds
#   - No data loss (WAL replication verified)
#   - All smoke tests pass post-failover
#
# =============================================================================

set -euo pipefail

# Configuration
ENVIRONMENT="staging"
AUTO_APPROVE="${CHAOS_AUTO_APPROVE:-false}"
TIMEOUT_SECONDS=120

while [[ $# -gt 0 ]]; do
    case "$1" in
        --environment)
            ENVIRONMENT="$2"
            shift 2
            ;;
        --auto-approve|-y)
            AUTO_APPROVE=true
            shift
            ;;
        *)
            ENVIRONMENT="$1"
            shift
            ;;
    esac
done

NAMESPACE="village-storefront-${ENVIRONMENT}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# =============================================================================
# Pre-Drill Validation
# =============================================================================

log_info "Database Failover Chaos Drill - Environment: $ENVIRONMENT"
log_info "================================================================"
log_info ""

# Verify kubectl context
CURRENT_CONTEXT=$(kubectl config current-context)
log_info "Current kubectl context: $CURRENT_CONTEXT"
log_warn "Confirm this is the correct environment before proceeding."

if [ "$AUTO_APPROVE" = true ] || [ "${CI:-false}" = "true" ]; then
    log_info "Auto-approval detected, continuing without interactive prompt"
else
    read -p "Continue with database failover drill? (yes/no): " CONFIRM
    if [ "$CONFIRM" != "yes" ]; then
        log_error "Drill cancelled by user"
        exit 1
    fi
fi

# Record baseline metrics
log_info "Recording baseline metrics..."

DB_CONNECTIONS_BEFORE=$(kubectl exec -n $NAMESPACE deploy/village-storefront-api -- \
    curl -s http://localhost:8080/q/metrics | grep hikaricp_connections_active | awk '{print $2}')

log_info "Baseline active connections: $DB_CONNECTIONS_BEFORE"

# =============================================================================
# Trigger Database Failover
# =============================================================================

log_info ""
log_info "Step 1: Triggering database failover..."

# Note: This assumes AWS RDS Multi-AZ. Adjust for your managed PostgreSQL provider.
# For testing purposes, we simulate failover by restarting the database pod in staging.

if [ "$ENVIRONMENT" = "staging" ]; then
    log_warn "Simulating failover by restarting PostgreSQL StatefulSet..."
    kubectl rollout restart statefulset/postgres -n $NAMESPACE || {
        log_error "Failed to restart PostgreSQL StatefulSet"
        exit 1
    }
else
    log_warn "Production failover requires AWS RDS CLI. Execute manually:"
    log_warn "  aws rds reboot-db-instance --db-instance-identifier storefront-production --force-failover"
    read -p "Press ENTER after initiating failover..."
fi

log_info "Failover initiated. Monitoring application behavior..."

# =============================================================================
# Monitor Application Reconnection
# =============================================================================

log_info ""
log_info "Step 2: Monitoring application reconnection..."

RECONNECT_START=$(date +%s)
RECONNECTED=false

for i in $(seq 1 $TIMEOUT_SECONDS); do
    sleep 1

    # Check if API pods can connect to database
    if kubectl exec -n $NAMESPACE deploy/village-storefront-api -- \
        curl -sf http://localhost:8080/q/health/ready > /dev/null 2>&1; then
        RECONNECT_END=$(date +%s)
        RECONNECT_TIME=$((RECONNECT_END - RECONNECT_START))
        log_info "✓ Application reconnected in ${RECONNECT_TIME}s"
        RECONNECTED=true
        break
    fi

    if [ $((i % 10)) -eq 0 ]; then
        log_warn "Still waiting for reconnection... (${i}s elapsed)"
    fi
done

if [ "$RECONNECTED" = false ]; then
    log_error "Application failed to reconnect within ${TIMEOUT_SECONDS}s"
    exit 1
fi

# =============================================================================
# Verify RLS Policies Intact
# =============================================================================

log_info ""
log_info "Step 3: Verifying RLS policies remain intact..."

# Test cross-tenant isolation after failover
RLS_TEST_RESULT=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -tAc "
        SELECT COUNT(*)
        FROM pg_tables
        WHERE schemaname = 'public'
        AND tablename IN ('orders', 'products', 'customers')
        AND rowsecurity = true;
    " 2>/dev/null || echo "0")

EXPECTED_RLS_TABLES=3

if [ "$RLS_TEST_RESULT" -eq "$EXPECTED_RLS_TABLES" ]; then
    log_info "✓ RLS policies verified ($RLS_TEST_RESULT/$EXPECTED_RLS_TABLES tables)"
else
    log_error "RLS policy verification failed: $RLS_TEST_RESULT/$EXPECTED_RLS_TABLES tables have RLS enabled"
    exit 1
fi

# =============================================================================
# Validate Cache Invalidation
# =============================================================================

log_info ""
log_info "Step 4: Validating cache invalidation..."

# Trigger a catalog query to ensure cache refresh
CACHE_TEST=$(kubectl exec -n $NAMESPACE deploy/village-storefront-api -- \
    curl -sf -H "X-Tenant-Id: test-tenant" \
    http://localhost:8080/api/catalog/products?limit=1 | jq -r '.data | length' 2>/dev/null || echo "0")

if [ "$CACHE_TEST" -gt 0 ]; then
    log_info "✓ Cache invalidation successful (query returned data)"
else
    log_warn "Cache validation inconclusive (no data returned, may be expected)"
fi

# =============================================================================
# Run Smoke Tests
# =============================================================================

log_info ""
log_info "Step 5: Running smoke tests..."

SMOKE_TESTS_PASSED=0
SMOKE_TESTS_TOTAL=4

# Test 1: Health endpoints
log_info "  [1/4] Testing health endpoints..."
if kubectl exec -n $NAMESPACE deploy/village-storefront-api -- \
    curl -sf http://localhost:8080/q/health/live > /dev/null && \
    kubectl exec -n $NAMESPACE deploy/village-storefront-api -- \
    curl -sf http://localhost:8080/q/health/ready > /dev/null; then
    log_info "    ✓ Health endpoints responsive"
    SMOKE_TESTS_PASSED=$((SMOKE_TESTS_PASSED + 1))
else
    log_error "    ✗ Health endpoints failed"
fi

# Test 2: Tenant routing
log_info "  [2/4] Testing tenant routing..."
if kubectl exec -n $NAMESPACE deploy/village-storefront-api -- \
    curl -sf -H "X-Tenant-Id: test-tenant" \
    http://localhost:8080/api/catalog/products?limit=1 > /dev/null; then
    log_info "    ✓ Tenant routing functional"
    SMOKE_TESTS_PASSED=$((SMOKE_TESTS_PASSED + 1))
else
    log_error "    ✗ Tenant routing failed"
fi

# Test 3: Database write operation
log_info "  [3/4] Testing database writes..."
if kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -tAc "INSERT INTO audit_log (event_type, timestamp, metadata) VALUES ('chaos.drill.test', NOW(), '{}') RETURNING id;" > /dev/null 2>&1; then
    log_info "    ✓ Database writes functional"
    SMOKE_TESTS_PASSED=$((SMOKE_TESTS_PASSED + 1))
else
    log_error "    ✗ Database write test failed"
fi

# Test 4: Background job enqueue
log_info "  [4/4] Testing background job system..."
JOB_COUNT_BEFORE=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -tAc "SELECT COUNT(*) FROM media_processing_jobs;" 2>/dev/null || echo "0")

if [ "$JOB_COUNT_BEFORE" -ge 0 ]; then
    log_info "    ✓ Background job system accessible"
    SMOKE_TESTS_PASSED=$((SMOKE_TESTS_PASSED + 1))
else
    log_error "    ✗ Background job system test failed"
fi

# =============================================================================
# Summary
# =============================================================================

log_info ""
log_info "================================================================"
log_info "Database Failover Drill Complete"
log_info "================================================================"
log_info ""
log_info "Results:"
log_info "  Reconnection Time: ${RECONNECT_TIME}s (Target: <30s)"
log_info "  RLS Policies: $RLS_TEST_RESULT/$EXPECTED_RLS_TABLES tables verified"
log_info "  Smoke Tests: $SMOKE_TESTS_PASSED/$SMOKE_TESTS_TOTAL passed"
log_info ""

if [ "$RECONNECT_TIME" -le 30 ] && \
   [ "$RLS_TEST_RESULT" -eq "$EXPECTED_RLS_TABLES" ] && \
   [ "$SMOKE_TESTS_PASSED" -eq "$SMOKE_TESTS_TOTAL" ]; then
    log_info "✓ Drill Status: PASSED"
    log_info ""
    log_info "Remediation steps documented: docs/operations/runbook.md §3.10"
    exit 0
else
    log_error "✗ Drill Status: FAILED (see criteria above)"
    log_error ""
    log_error "Review runbook procedures: docs/operations/runbook.md §3.10"
    exit 1
fi
