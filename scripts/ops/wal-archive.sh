#!/bin/bash
#
# WAL Archiving Script for PostgreSQL
#
# This script is called by PostgreSQL's archive_command to archive Write-Ahead Log (WAL) segments
# to Cloudflare R2 for point-in-time recovery (PITR).
#
# Usage (configured in postgresql.conf):
#   archive_mode = on
#   archive_command = '/usr/local/bin/wal-archive.sh %p %f'
#
# Parameters:
#   $1 (%p) - Full path to WAL file to archive
#   $2 (%f) - WAL file name only
#
# Exit codes:
#   0 - Archive successful
#   1 - Archive failed (PostgreSQL will retry)
#
# References:
#   - Task: I6.T5 - Data Ops + DR Automation
#   - PostgreSQL Docs: https://www.postgresql.org/docs/current/continuous-archiving.html
#   - DR Playbook: docs/operations/dr_playbook.md

set -euo pipefail

# Configuration (can be overridden via environment variables)
R2_ENDPOINT="${R2_ENDPOINT:-https://account.r2.cloudflarestorage.com}"
R2_BUCKET="${R2_BUCKET:-village-storefront-backups}"
R2_ACCESS_KEY_ID="${R2_ACCESS_KEY_ID:-}"
R2_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:-}"
WAL_PREFIX="${WAL_PREFIX:-postgres/wal}"
LOG_FILE="${WAL_ARCHIVE_LOG:-/var/log/postgresql/wal-archive.log}"

# Validate parameters
if [ "$#" -ne 2 ]; then
    echo "ERROR: Invalid arguments. Usage: $0 <wal_path> <wal_filename>" >&2
    exit 1
fi

WAL_PATH="$1"
WAL_FILE="$2"

# Validate WAL file exists
if [ ! -f "$WAL_PATH" ]; then
    echo "ERROR: WAL file not found: $WAL_PATH" >&2
    exit 1
fi

# Log function
log() {
    echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $1" >> "$LOG_FILE" 2>&1 || true
}

log "Starting WAL archive - file=$WAL_FILE, path=$WAL_PATH"

# Upload to R2 using AWS CLI (S3-compatible)
R2_KEY="${WAL_PREFIX}/${WAL_FILE}"

# Use AWS CLI with custom endpoint
AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
aws s3 cp "$WAL_PATH" "s3://${R2_BUCKET}/${R2_KEY}" \
    --endpoint-url "$R2_ENDPOINT" \
    --region auto \
    --no-progress \
    >> "$LOG_FILE" 2>&1

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    log "WAL archive successful - key=$R2_KEY, size=$(stat -f%z "$WAL_PATH" 2>/dev/null || stat -c%s "$WAL_PATH")"
    exit 0
else
    log "ERROR: WAL archive failed - file=$WAL_FILE, exit_code=$EXIT_CODE"
    exit 1
fi
