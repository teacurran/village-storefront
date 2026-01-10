#!/bin/bash
#
# Full Database Restore Script
#
# Restores PostgreSQL database from R2 backup (base backup + optional PITR via WAL replay).
# Supports both full restore (latest backup) and point-in-time recovery (PITR).
#
# Usage:
#   # Full restore (latest backup)
#   ./restore-full-backup.sh
#
#   # Point-in-time recovery to specific timestamp
#   ./restore-full-backup.sh --target-time "2026-01-10 14:30:00 UTC"
#
#   # Specify backup date explicitly
#   ./restore-full-backup.sh --backup-date "2026-01-09"
#
# Environment Variables:
#   R2_ENDPOINT          - Cloudflare R2 endpoint URL
#   R2_BUCKET            - R2 bucket name for backups
#   R2_ACCESS_KEY_ID     - R2 access key
#   R2_SECRET_ACCESS_KEY - R2 secret key
#   RESTORE_TARGET       - Directory to restore database to (default: /var/lib/postgresql/restore)
#   PGDATA               - PostgreSQL data directory (default: /var/lib/postgresql/data)
#
# Exit Codes:
#   0 - Restore successful
#   1 - Restore failed
#
# References:
#   - Task: I6.T5 - Data Ops + DR Automation
#   - DR Playbook: docs/operations/dr_playbook.md
#   - PostgreSQL PITR: https://www.postgresql.org/docs/current/continuous-archiving.html

set -euo pipefail

# Configuration
R2_ENDPOINT="${R2_ENDPOINT:-https://account.r2.cloudflarestorage.com}"
R2_BUCKET="${R2_BUCKET:-village-storefront-backups}"
R2_ACCESS_KEY_ID="${R2_ACCESS_KEY_ID:-}"
R2_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:-}"
RESTORE_TARGET="${RESTORE_TARGET:-/var/lib/postgresql/restore}"
PGDATA="${PGDATA:-/var/lib/postgresql/data}"
BACKUP_PREFIX="postgres/daily"
WAL_PREFIX="postgres/wal"

# Parse arguments
TARGET_TIME=""
BACKUP_DATE=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --target-time)
            TARGET_TIME="$2"
            shift 2
            ;;
        --backup-date)
            BACKUP_DATE="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [--target-time \"YYYY-MM-DD HH:MM:SS UTC\"] [--backup-date \"YYYY-MM-DD\"]"
            exit 0
            ;;
        *)
            echo "ERROR: Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

# Logging
log() {
    echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $1"
}

log "========================================="
log "PostgreSQL Database Restore"
log "========================================="
log "R2 Bucket: $R2_BUCKET"
log "Restore Target: $RESTORE_TARGET"
log "Target Time: ${TARGET_TIME:-latest}"
log "========================================="

# Validate prerequisites
if ! command -v aws &> /dev/null; then
    log "ERROR: AWS CLI not found. Please install: pip install awscli"
    exit 1
fi

if [ -z "$R2_ACCESS_KEY_ID" ] || [ -z "$R2_SECRET_ACCESS_KEY" ]; then
    log "ERROR: R2 credentials not set. Set R2_ACCESS_KEY_ID and R2_SECRET_ACCESS_KEY environment variables."
    exit 1
fi

# Step 1: Find latest base backup (or specified backup date)
log "Step 1: Finding latest base backup..."

if [ -z "$BACKUP_DATE" ]; then
    # Find latest backup
    LATEST_BACKUP=$(AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
                    AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
                    aws s3 ls "s3://$R2_BUCKET/$BACKUP_PREFIX/" \
                        --endpoint-url "$R2_ENDPOINT" \
                        --region auto | sort | tail -n 1 | awk '{print $4}')

    if [ -z "$LATEST_BACKUP" ]; then
        log "ERROR: No backups found in R2 bucket: s3://$R2_BUCKET/$BACKUP_PREFIX/"
        exit 1
    fi
else
    LATEST_BACKUP="backup-${BACKUP_DATE}.tar.gz"
fi

log "Latest backup: $LATEST_BACKUP"

# Step 2: Download base backup
log "Step 2: Downloading base backup from R2..."

mkdir -p /tmp/restore
BACKUP_FILE="/tmp/restore/$LATEST_BACKUP"

AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
aws s3 cp "s3://$R2_BUCKET/$BACKUP_PREFIX/$LATEST_BACKUP" "$BACKUP_FILE" \
    --endpoint-url "$R2_ENDPOINT" \
    --region auto

if [ ! -f "$BACKUP_FILE" ]; then
    log "ERROR: Backup download failed: $BACKUP_FILE"
    exit 1
fi

log "Backup downloaded: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | awk '{print $1}'))"

# Step 3: Extract base backup
log "Step 3: Extracting base backup..."

mkdir -p "$RESTORE_TARGET"
tar -xzf "$BACKUP_FILE" -C "$RESTORE_TARGET"

log "Backup extracted to: $RESTORE_TARGET"

# Step 4: Download WAL files (if PITR requested or for full recovery)
if [ -n "$TARGET_TIME" ] || [ "$RESTORE_TARGET" != "$PGDATA" ]; then
    log "Step 4: Downloading WAL files for recovery..."

    mkdir -p "$RESTORE_TARGET/pg_wal"

    # Download all WAL files (recovery will stop at target time or latest)
    AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
    aws s3 sync "s3://$R2_BUCKET/$WAL_PREFIX/" "$RESTORE_TARGET/pg_wal/" \
        --endpoint-url "$R2_ENDPOINT" \
        --region auto \
        --quiet

    WAL_COUNT=$(find "$RESTORE_TARGET/pg_wal" -type f | wc -l)
    log "Downloaded $WAL_COUNT WAL files"
else
    log "Step 4: Skipping WAL download (full restore without PITR)"
fi

# Step 5: Create recovery configuration (if PITR requested)
if [ -n "$TARGET_TIME" ]; then
    log "Step 5: Creating recovery.conf for PITR..."

    # PostgreSQL 12+ uses recovery.signal + postgresql.conf
    touch "$RESTORE_TARGET/recovery.signal"

    # Append recovery settings to postgresql.auto.conf
    cat >> "$RESTORE_TARGET/postgresql.auto.conf" <<EOF

# Recovery settings for PITR (generated by restore-full-backup.sh)
restore_command = 'cp $RESTORE_TARGET/pg_wal/%f %p'
recovery_target_time = '$TARGET_TIME'
recovery_target_action = 'promote'
EOF

    log "Recovery configuration created - target time: $TARGET_TIME"
else
    log "Step 5: No PITR requested - full recovery to latest WAL"
fi

# Step 6: Validate restoration
log "Step 6: Validating restored database..."

# Check for critical files
REQUIRED_FILES=("PG_VERSION" "postgresql.conf")
for FILE in "${REQUIRED_FILES[@]}"; do
    if [ ! -f "$RESTORE_TARGET/$FILE" ]; then
        log "ERROR: Required file missing: $RESTORE_TARGET/$FILE"
        exit 1
    fi
done

PG_VERSION=$(cat "$RESTORE_TARGET/PG_VERSION")
log "PostgreSQL version: $PG_VERSION"

# Step 7: Provide next steps
log "========================================="
log "Restore preparation completed successfully!"
log "========================================="
log ""
log "Next steps:"
log "1. Stop PostgreSQL if running:"
log "   sudo systemctl stop postgresql"
log ""
log "2. Backup current PGDATA (if exists):"
log "   sudo mv $PGDATA $PGDATA.backup-$(date +%Y%m%d)"
log ""
log "3. Move restored data to PGDATA:"
log "   sudo mv $RESTORE_TARGET $PGDATA"
log "   sudo chown -R postgres:postgres $PGDATA"
log ""
log "4. Start PostgreSQL:"
log "   sudo systemctl start postgresql"
log ""
log "5. Verify restoration:"
log "   psql -c \"SELECT COUNT(*) FROM tenants;\""
log "   psql -c \"SELECT MAX(created_at) FROM orders;\""
log ""
log "6. Check PostgreSQL logs for recovery completion:"
log "   tail -f /var/log/postgresql/postgresql.log"
log ""

if [ -n "$TARGET_TIME" ]; then
    log "PITR Target: $TARGET_TIME"
    log "PostgreSQL will recover to this point and then promote to primary."
fi

log "========================================="
log "Restore preparation duration: $SECONDS seconds"
log "========================================="

# Cleanup
rm -f "$BACKUP_FILE"

exit 0
