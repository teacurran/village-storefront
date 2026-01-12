#!/usr/bin/env bash
# =============================================================================
# Worker Pod Crash Chaos Drill
# =============================================================================
#
# DESCRIPTION:
#   Force-kills all worker pods to simulate catastrophic failure and validates
#   job recovery, queue integrity, and graceful degradation behavior.
#
# USAGE:
#   ./scripts/qa/chaos/worker_crash.sh [--environment staging|production]
#
# PREREQUISITES:
#   - kubectl configured for target cluster
#   - Worker pods deployed with HPA enabled
#   - Background job system operational
#
# VALIDATION STEPS:
#   1. Enqueue test jobs across priorities
#   2. Force-kill all worker pods (simulate crash)
#   3. Monitor worker auto-scaling and restart
#   4. Verify in-flight jobs retry correctly
#   5. Confirm no jobs moved to DLQ incorrectly
#   6. Validate queue drains to baseline
#
# EXPECTED OUTCOME:
#   - Workers restart within 2 minutes
#   - In-flight jobs retry automatically
#   - No jobs lost or incorrectly moved to DLQ
#   - Queue drains to baseline within 10 minutes
#
# =============================================================================

set -euo pipefail

# Configuration
ENVIRONMENT="staging"
AUTO_APPROVE="${CHAOS_AUTO_APPROVE:-false}"
TIMEOUT_SECONDS=600

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

log_info "Worker Crash Chaos Drill - Environment: $ENVIRONMENT"
log_info "================================================================"
log_info ""

# Verify kubectl context
CURRENT_CONTEXT=$(kubectl config current-context)
log_info "Current kubectl context: $CURRENT_CONTEXT"
log_warn "This will force-kill all worker pods. Confirm before proceeding."

if [ "$AUTO_APPROVE" = true ] || [ "${CI:-false}" = "true" ]; then
    log_info "Auto-approval detected, continuing without interactive prompt"
else
    read -p "Continue with worker crash drill? (yes/no): " CONFIRM
    if [ "$CONFIRM" != "yes" ]; then
        log_error "Drill cancelled by user"
        exit 1
    fi
fi

# Record baseline metrics
log_info "Recording baseline metrics..."

WORKER_COUNT_BEFORE=$(kubectl get pods -n $NAMESPACE -l component=worker --no-headers | wc -l | tr -d ' ')
QUEUE_DEPTH_BEFORE=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -tAc "SELECT COUNT(*) FROM media_processing_jobs WHERE status = 'pending';" 2>/dev/null || echo "0")

log_info "Baseline worker pods: $WORKER_COUNT_BEFORE"
log_info "Baseline queue depth: $QUEUE_DEPTH_BEFORE"

# =============================================================================
# Enqueue Test Jobs
# =============================================================================

log_info ""
log_info "Step 1: Enqueuing test jobs across priorities..."

# Insert test jobs directly into database
kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront <<EOF > /dev/null 2>&1
INSERT INTO media_processing_jobs (tenant_id, job_type, priority, payload, status, created_at)
SELECT
    'test-tenant-chaos',
    'image_resize',
    CASE
        WHEN s <= 10 THEN 'CRITICAL'
        WHEN s <= 30 THEN 'HIGH'
        ELSE 'DEFAULT'
    END,
    '{"source": "/test/image-' || s || '.jpg", "target": "/test/thumb-' || s || '.jpg"}',
    'pending',
    NOW()
FROM generate_series(1, 100) AS s;
EOF

TEST_JOBS_ENQUEUED=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -tAc "SELECT COUNT(*) FROM media_processing_jobs WHERE tenant_id = 'test-tenant-chaos';" 2>/dev/null || echo "0")

log_info "✓ Enqueued $TEST_JOBS_ENQUEUED test jobs (10 CRITICAL, 20 HIGH, 70 DEFAULT)"

# =============================================================================
# Force-Kill Worker Pods
# =============================================================================

log_info ""
log_info "Step 2: Force-killing all worker pods..."

KILL_START=$(date +%s)

kubectl delete pods -n $NAMESPACE -l component=worker --force --grace-period=0 || {
    log_error "Failed to force-kill worker pods"
    exit 1
}

log_info "✓ Worker pods killed (simulated crash, no graceful shutdown)"

# Wait for pods to terminate
sleep 5

# =============================================================================
# Monitor Worker Restart
# =============================================================================

log_info ""
log_info "Step 3: Monitoring worker pod restart and auto-scaling..."

RESTART_TIMEOUT=120
RESTARTED=false

for i in $(seq 1 $RESTART_TIMEOUT); do
    sleep 1

    WORKER_COUNT_NOW=$(kubectl get pods -n $NAMESPACE -l component=worker --field-selector=status.phase=Running --no-headers | wc -l | tr -d ' ')

    if [ "$WORKER_COUNT_NOW" -ge "$WORKER_COUNT_BEFORE" ]; then
        RESTART_END=$(date +%s)
        RESTART_TIME=$((RESTART_END - KILL_START))
        log_info "✓ Workers restarted in ${RESTART_TIME}s ($WORKER_COUNT_NOW/$WORKER_COUNT_BEFORE pods running)"
        RESTARTED=true
        break
    fi

    if [ $((i % 10)) -eq 0 ]; then
        log_warn "Still waiting for workers... ($WORKER_COUNT_NOW/$WORKER_COUNT_BEFORE pods, ${i}s elapsed)"
    fi
done

if [ "$RESTARTED" = false ]; then
    log_error "Workers failed to restart within ${RESTART_TIMEOUT}s"
    exit 1
fi

# =============================================================================
# Verify Job Retry Behavior
# =============================================================================

log_info ""
log_info "Step 4: Verifying in-flight jobs retry correctly..."

# Wait for workers to start processing
log_info "Waiting 30s for workers to resume processing..."
sleep 30

JOBS_IN_FLIGHT=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -tAc "SELECT COUNT(*) FROM media_processing_jobs WHERE tenant_id = 'test-tenant-chaos' AND status = 'in_progress';" 2>/dev/null || echo "0")

JOBS_PENDING=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -tAc "SELECT COUNT(*) FROM media_processing_jobs WHERE tenant_id = 'test-tenant-chaos' AND status = 'pending';" 2>/dev/null || echo "0")

log_info "Job status: $JOBS_IN_FLIGHT in_progress, $JOBS_PENDING pending"

if [ "$JOBS_IN_FLIGHT" -gt 0 ] || [ "$JOBS_PENDING" -gt 0 ]; then
    log_info "✓ Jobs are being retried/processed"
else
    log_warn "No jobs currently processing (may have completed quickly)"
fi

# =============================================================================
# Check DLQ for False Positives
# =============================================================================

log_info ""
log_info "Step 5: Checking DLQ for incorrectly moved jobs..."

DLQ_FALSE_POSITIVES=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -tAc "SELECT COUNT(*) FROM dead_letter_queue WHERE metadata->>'tenant_id' = 'test-tenant-chaos' AND last_error LIKE '%crash%';" 2>/dev/null || echo "0")

if [ "$DLQ_FALSE_POSITIVES" -eq 0 ]; then
    log_info "✓ No false DLQ entries (crash-related jobs did not move to DLQ)"
else
    log_error "Found $DLQ_FALSE_POSITIVES jobs incorrectly moved to DLQ due to crash"
fi

# =============================================================================
# Monitor Queue Drain
# =============================================================================

log_info ""
log_info "Step 6: Monitoring queue drain to baseline..."

DRAIN_START=$(date +%s)
DRAIN_TIMEOUT=600
DRAINED=false

for i in $(seq 1 $DRAIN_TIMEOUT); do
    sleep 5

    JOBS_REMAINING=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
        psql -U storefront -d storefront -tAc "SELECT COUNT(*) FROM media_processing_jobs WHERE tenant_id = 'test-tenant-chaos' AND status IN ('pending', 'in_progress');" 2>/dev/null || echo "999")

    JOBS_COMPLETED=$(kubectl exec -n $NAMESPACE statefulset/postgres -- \
        psql -U storefront -d storefront -tAc "SELECT COUNT(*) FROM media_processing_jobs WHERE tenant_id = 'test-tenant-chaos' AND status = 'completed';" 2>/dev/null || echo "0")

    if [ "$JOBS_REMAINING" -eq 0 ]; then
        DRAIN_END=$(date +%s)
        DRAIN_TIME=$((DRAIN_END - DRAIN_START))
        log_info "✓ Queue drained in ${DRAIN_TIME}s ($JOBS_COMPLETED/$TEST_JOBS_ENQUEUED jobs completed)"
        DRAINED=true
        break
    fi

    if [ $((i % 20)) -eq 0 ]; then
        log_info "Queue draining... ($JOBS_COMPLETED/$TEST_JOBS_ENQUEUED completed, ${i}s elapsed)"
    fi
done

if [ "$DRAINED" = false ]; then
    log_warn "Queue did not fully drain within ${DRAIN_TIMEOUT}s"
    log_warn "Remaining jobs: $JOBS_REMAINING, Completed: $JOBS_COMPLETED"
fi

# =============================================================================
# Cleanup Test Jobs
# =============================================================================

log_info ""
log_info "Cleaning up test jobs..."

kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -c "DELETE FROM media_processing_jobs WHERE tenant_id = 'test-tenant-chaos';" > /dev/null 2>&1

kubectl exec -n $NAMESPACE statefulset/postgres -- \
    psql -U storefront -d storefront -c "DELETE FROM dead_letter_queue WHERE metadata->>'tenant_id' = 'test-tenant-chaos';" > /dev/null 2>&1

log_info "✓ Test data cleaned up"

# =============================================================================
# Summary
# =============================================================================

log_info ""
log_info "================================================================"
log_info "Worker Crash Drill Complete"
log_info "================================================================"
log_info ""
log_info "Results:"
log_info "  Worker Restart Time: ${RESTART_TIME}s (Target: <120s)"
log_info "  Jobs Completed: $JOBS_COMPLETED/$TEST_JOBS_ENQUEUED"
log_info "  DLQ False Positives: $DLQ_FALSE_POSITIVES (Target: 0)"
log_info "  Queue Drain Time: ${DRAIN_TIME}s (Target: <600s)"
log_info ""

if [ "$RESTART_TIME" -le 120 ] && \
   [ "$DLQ_FALSE_POSITIVES" -eq 0 ] && \
   [ "$DRAINED" = true ]; then
    log_info "✓ Drill Status: PASSED"
    log_info ""
    log_info "Remediation steps documented: docs/operations/runbook.md §4"
    exit 0
else
    log_error "✗ Drill Status: FAILED (see criteria above)"
    log_error ""
    log_error "Review runbook procedures: docs/operations/runbook.md §4"
    exit 1
fi
