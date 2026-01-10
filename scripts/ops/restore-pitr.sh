#!/bin/bash
#
# Point-in-Time Recovery (PITR) Script
#
# Streamlined script for point-in-time recovery to a specific timestamp.
# This is a wrapper around restore-full-backup.sh with PITR-specific validation.
#
# Usage:
#   ./restore-pitr.sh --target-time "2026-01-10 14:30:00 UTC"
#
# Environment Variables:
#   Same as restore-full-backup.sh
#
# Exit Codes:
#   0 - PITR preparation successful
#   1 - PITR preparation failed
#
# References:
#   - Task: I6.T5 - Data Ops + DR Automation
#   - DR Playbook: docs/operations/dr_playbook.md

set -euo pipefail

# Parse arguments
TARGET_TIME=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --target-time)
            TARGET_TIME="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 --target-time \"YYYY-MM-DD HH:MM:SS UTC\""
            echo ""
            echo "Example:"
            echo "  $0 --target-time \"2026-01-10 14:30:00 UTC\""
            exit 0
            ;;
        *)
            echo "ERROR: Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

# Validate target time is provided
if [ -z "$TARGET_TIME" ]; then
    echo "ERROR: --target-time is required for PITR" >&2
    echo "Usage: $0 --target-time \"YYYY-MM-DD HH:MM:SS UTC\"" >&2
    exit 1
fi

# Validate target time format (basic check)
if ! date -d "$TARGET_TIME" &>/dev/null 2>&1 && ! date -j -f "%Y-%m-%d %H:%M:%S %Z" "$TARGET_TIME" &>/dev/null 2>&1; then
    echo "ERROR: Invalid timestamp format: $TARGET_TIME" >&2
    echo "Expected format: YYYY-MM-DD HH:MM:SS UTC" >&2
    exit 1
fi

echo "========================================="
echo "Point-in-Time Recovery (PITR)"
echo "========================================="
echo "Target Time: $TARGET_TIME"
echo "========================================="
echo ""
echo "WARNING: This will prepare a database restore to the specified timestamp."
echo "Press Ctrl+C to cancel, or Enter to continue..."
read -r

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Call restore-full-backup.sh with target time
"$SCRIPT_DIR/restore-full-backup.sh" --target-time "$TARGET_TIME"

EXIT_CODE=$?

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo "========================================="
    echo "PITR preparation completed successfully!"
    echo "========================================="
    echo ""
    echo "The database has been prepared for point-in-time recovery to:"
    echo "  $TARGET_TIME"
    echo ""
    echo "After starting PostgreSQL, it will:"
    echo "1. Apply WAL files up to the target timestamp"
    echo "2. Automatically promote to primary when target is reached"
    echo "3. Become available for connections"
    echo ""
    echo "Verify recovery success by checking:"
    echo "  - PostgreSQL logs for 'recovery complete' message"
    echo "  - Latest transaction timestamp is <= target time"
    echo ""
else
    echo "ERROR: PITR preparation failed with exit code: $EXIT_CODE" >&2
    exit $EXIT_CODE
fi

exit 0
