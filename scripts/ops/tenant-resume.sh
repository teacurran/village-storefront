#!/bin/bash
#
# Tenant Resume Script
#
# Resumes a suspended tenant by restoring feature flags from backup and
# setting status back to 'active'.
#
# Usage:
#   ./tenant-resume.sh --tenant-id <uuid>
#
# Environment Variables:
#   PGHOST          - PostgreSQL host (default: localhost)
#   PGPORT          - PostgreSQL port (default: 5432)
#   PGDATABASE      - PostgreSQL database (default: storefront)
#   PGUSER          - PostgreSQL user (default: storefront)
#   PGPASSWORD      - PostgreSQL password (required)
#
# Exit Codes:
#   0 - Resume successful
#   1 - Resume failed
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

while [[ $# -gt 0 ]]; do
    case $1 in
        --tenant-id)
            TENANT_ID="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 --tenant-id <uuid>"
            echo ""
            echo "Example:"
            echo "  $0 --tenant-id 123e4567-e89b-12d3-a456-426614174000"
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

if [ -z "${PGPASSWORD:-}" ]; then
    echo "ERROR: PGPASSWORD environment variable not set" >&2
    exit 1
fi

# Logging
log() {
    echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $1"
}

log "========================================="
log "Tenant Resume"
log "========================================="
log "Tenant ID: $TENANT_ID"
log "========================================="

# Helper function to run SQL
run_sql() {
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A -c "$1"
}

# Step 1: Validate tenant exists and is suspended
log "Step 1: Validating tenant..."

TENANT_STATUS=$(run_sql "SELECT status FROM tenants WHERE id = '$TENANT_ID';" || echo "NOT_FOUND")

if [ "$TENANT_STATUS" = "NOT_FOUND" ] || [ -z "$TENANT_STATUS" ]; then
    log "ERROR: Tenant not found: $TENANT_ID"
    exit 1
fi

if [ "$TENANT_STATUS" = "active" ]; then
    log "WARNING: Tenant is already active"
    exit 0
fi

if [ "$TENANT_STATUS" = "deleted" ]; then
    log "ERROR: Cannot resume deleted tenant"
    exit 1
fi

if [ "$TENANT_STATUS" != "suspended" ]; then
    log "ERROR: Tenant status is '$TENANT_STATUS' (expected 'suspended')"
    exit 1
fi

log "Tenant status: $TENANT_STATUS (suspended)"

# Step 2: Check for feature flag backup
log "Step 2: Checking for feature flag backup..."

BACKUP_TABLE="feature_flags_backup_$(echo "$TENANT_ID" | tr -d '-')"

BACKUP_EXISTS=$(run_sql "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = '$BACKUP_TABLE');" || echo "f")

if [ "$BACKUP_EXISTS" = "f" ]; then
    log "WARNING: Feature flag backup not found: $BACKUP_TABLE"
    log "Will resume tenant without restoring feature flags"
    log "Emergency kill switches will remain active - manual cleanup required"
else
    FLAG_COUNT=$(run_sql "SELECT COUNT(*) FROM $BACKUP_TABLE;")
    log "Found feature flag backup: $BACKUP_TABLE ($FLAG_COUNT flags)"
fi

# Step 3: Delete current feature flags (emergency kill switches)
log "Step 3: Removing emergency kill switches..."

DELETED_COUNT=$(run_sql "DELETE FROM feature_flags WHERE tenant_id = '$TENANT_ID'; SELECT ROW_COUNT();")
log "Deleted $DELETED_COUNT emergency feature flags"

# Step 4: Restore feature flags from backup
if [ "$BACKUP_EXISTS" = "t" ]; then
    log "Step 4: Restoring feature flags from backup..."

    run_sql "INSERT INTO feature_flags SELECT * FROM $BACKUP_TABLE;"

    log "Feature flags restored from backup"

    # Drop backup table
    run_sql "DROP TABLE $BACKUP_TABLE;"
    log "Backup table dropped: $BACKUP_TABLE"
else
    log "Step 4: Skipping feature flag restoration (no backup)"
fi

# Step 5: Update tenant status to 'active'
log "Step 5: Updating tenant status to 'active'..."

run_sql "UPDATE tenants SET status = 'active', updated_at = NOW() WHERE id = '$TENANT_ID';"

log "Tenant status updated to 'active'"

# Step 6: Log platform command for audit trail
log "Step 6: Recording platform command..."

run_sql "INSERT INTO platform_commands (tenant_id, action, reason, actor_id, metadata, occurred_at)
VALUES (
    '$TENANT_ID',
    'resume_tenant',
    'Tenant resumed after suspension',
    'platform-ops',
    '{\"backup_table\": \"$BACKUP_TABLE\", \"backup_existed\": $BACKUP_EXISTS}',
    NOW()
);"

log "Platform command recorded"

# Step 7: Cache invalidation
log "Step 7: Cache invalidation..."

log "Cache will be invalidated on next application poll (TTL: 5 minutes)"
log "For immediate effect, restart application pods:"
log "  kubectl rollout restart deployment village-storefront -n storefront"

# Summary
log "========================================="
log "Tenant resume completed successfully!"
log "========================================="
log ""
log "Tenant: $TENANT_ID"
log "Status: active"

if [ "$BACKUP_EXISTS" = "t" ]; then
    log "Feature flags: restored from backup"
else
    log "Feature flags: NOT restored (backup missing)"
    log "WARNING: Manual feature flag configuration may be required"
fi

log ""
log "To suspend this tenant again:"
log "  ./tenant-suspend.sh --tenant-id $TENANT_ID --reason \"<reason>\" --ticket \"<ticket>\""
log ""
log "========================================="

exit 0
