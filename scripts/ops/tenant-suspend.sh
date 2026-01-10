#!/bin/bash
#
# Tenant Suspension Script
#
# Suspends a tenant by setting status to 'suspended', backing up feature flags,
# and activating emergency kill switches.
#
# Usage:
#   ./tenant-suspend.sh --tenant-id <uuid> --reason "<reason>" --ticket <ticket-number>
#
# Environment Variables:
#   PGHOST          - PostgreSQL host (default: localhost)
#   PGPORT          - PostgreSQL port (default: 5432)
#   PGDATABASE      - PostgreSQL database (default: storefront)
#   PGUSER          - PostgreSQL user (default: storefront)
#   PGPASSWORD      - PostgreSQL password (required)
#
# Exit Codes:
#   0 - Suspension successful
#   1 - Suspension failed
#
# References:
#   - Task: I6.T5 - Data Ops + DR Automation
#   - Architecture: 04_Operational_Architecture.md (Section 3.2.1 - Tenant Suspension)

set -euo pipefail

# Configuration
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-storefront}"
PGUSER="${PGUSER:-storefront}"

# Parse arguments
TENANT_ID=""
REASON=""
TICKET_NUMBER=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --tenant-id)
            TENANT_ID="$2"
            shift 2
            ;;
        --reason)
            REASON="$2"
            shift 2
            ;;
        --ticket)
            TICKET_NUMBER="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 --tenant-id <uuid> --reason \"<reason>\" --ticket <ticket-number>"
            echo ""
            echo "Example:"
            echo "  $0 --tenant-id 123e4567-e89b-12d3-a456-426614174000 \\"
            echo "     --reason \"Payment failure - 90 days overdue\" \\"
            echo "     --ticket \"SUPPORT-12345\""
            exit 0
            ;;
        *)
            echo "ERROR: Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

# Validate arguments
if [ -z "$TENANT_ID" ]; then
    echo "ERROR: --tenant-id is required" >&2
    exit 1
fi

if [ -z "$REASON" ]; then
    echo "ERROR: --reason is required" >&2
    exit 1
fi

if [ -z "$TICKET_NUMBER" ]; then
    echo "ERROR: --ticket is required (use 'MANUAL' if no ticket)" >&2
    exit 1
fi

if [ -z "${PGPASSWORD:-}" ]; then
    echo "ERROR: PGPASSWORD environment variable not set" >&2
    exit 1
fi

# Logging
log() {
    echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $1"
}

log "========================================="
log "Tenant Suspension"
log "========================================="
log "Tenant ID: $TENANT_ID"
log "Reason: $REASON"
log "Ticket: $TICKET_NUMBER"
log "========================================="

# Helper function to run SQL
run_sql() {
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -c "$1"
}

# Step 1: Validate tenant exists and is not already suspended
log "Step 1: Validating tenant..."

TENANT_STATUS=$(run_sql "SELECT status FROM tenants WHERE id = '$TENANT_ID';" || echo "NOT_FOUND")

if [ "$TENANT_STATUS" = "NOT_FOUND" ] || [ -z "$TENANT_STATUS" ]; then
    log "ERROR: Tenant not found: $TENANT_ID"
    exit 1
fi

if [ "$TENANT_STATUS" = "suspended" ]; then
    log "WARNING: Tenant is already suspended"
    log "Run tenant-resume.sh to resume this tenant"
    exit 0
fi

if [ "$TENANT_STATUS" = "deleted" ]; then
    log "ERROR: Cannot suspend deleted tenant"
    exit 1
fi

log "Tenant status: $TENANT_STATUS (active)"

# Step 2: Backup current feature flags
log "Step 2: Backing up feature flags..."

BACKUP_TABLE="feature_flags_backup_$(echo "$TENANT_ID" | tr -d '-')"

run_sql "DROP TABLE IF EXISTS $BACKUP_TABLE;" || true

run_sql "CREATE TABLE $BACKUP_TABLE AS
    SELECT * FROM feature_flags WHERE tenant_id = '$TENANT_ID';"

FLAG_COUNT=$(run_sql "SELECT COUNT(*) FROM $BACKUP_TABLE;")
log "Backed up $FLAG_COUNT feature flags to table: $BACKUP_TABLE"

# Step 3: Update tenant status to 'suspended'
log "Step 3: Updating tenant status to 'suspended'..."

run_sql "UPDATE tenants SET status = 'suspended', updated_at = NOW() WHERE id = '$TENANT_ID';"

log "Tenant status updated"

# Step 4: Set emergency kill switches
log "Step 4: Activating emergency kill switches..."

run_sql "INSERT INTO feature_flags (tenant_id, flag_key, flag_value, reason, updated_by, created_at, updated_at)
VALUES
    ('$TENANT_ID', 'checkout.kill-switch', 'true', 'Tenant suspension: $REASON', 'platform-ops', NOW(), NOW()),
    ('$TENANT_ID', 'admin.access.disabled', 'true', 'Tenant suspension: $REASON', 'platform-ops', NOW(), NOW()),
    ('$TENANT_ID', 'storefront.maintenance-mode', 'true', 'Tenant suspension: $REASON', 'platform-ops', NOW(), NOW())
ON CONFLICT (tenant_id, flag_key)
DO UPDATE SET
    flag_value = EXCLUDED.flag_value,
    reason = EXCLUDED.reason,
    updated_by = EXCLUDED.updated_by,
    updated_at = NOW();"

log "Kill switches activated (checkout, admin, storefront)"

# Step 5: Log platform command for audit trail
log "Step 5: Recording platform command..."

run_sql "INSERT INTO platform_commands (tenant_id, action, reason, ticket_number, actor_id, metadata, occurred_at)
VALUES (
    '$TENANT_ID',
    'suspend_tenant',
    '$REASON',
    '$TICKET_NUMBER',
    'platform-ops',
    '{\"backup_table\": \"$BACKUP_TABLE\", \"previous_status\": \"$TENANT_STATUS\"}',
    NOW()
);"

log "Platform command recorded"

# Step 6: Invalidate tenant cache (emit event)
log "Step 6: Cache invalidation..."

# Note: Cache invalidation happens automatically via TenantUpdated CDI event
# when application observes database changes. For immediate effect, can restart
# application pods or wait for TTL expiry (typically 5 minutes).

log "Cache will be invalidated on next application poll (TTL: 5 minutes)"
log "For immediate effect, restart application pods:"
log "  kubectl rollout restart deployment village-storefront -n storefront"

# Summary
log "========================================="
log "Tenant suspension completed successfully!"
log "========================================="
log ""
log "Tenant: $TENANT_ID"
log "Status: suspended"
log "Feature flags backed up to: $BACKUP_TABLE"
log "Kill switches: checkout, admin, storefront"
log ""
log "To resume this tenant:"
log "  ./tenant-resume.sh --tenant-id $TENANT_ID"
log ""
log "========================================="

exit 0
