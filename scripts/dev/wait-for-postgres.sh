#!/bin/sh
# wait-for-postgres.sh
#
# Waits for PostgreSQL to be ready before proceeding.
# Uses pg_isready to check connection status.
#
# Usage:
#   ./scripts/dev/wait-for-postgres.sh [host] [port] [user] [database]
#
# Example:
#   ./scripts/dev/wait-for-postgres.sh localhost 5432 appuser storefront_dev

set -e

# Default values (can be overridden by arguments or environment)
HOST="${1:-${DB_HOST:-localhost}}"
PORT="${2:-${DB_PORT:-5432}}"
USER="${3:-${DB_USER:-appuser}}"
DATABASE="${4:-${DB_NAME:-storefront_dev}}"

# Maximum number of attempts (30 seconds with 1 second intervals)
MAX_ATTEMPTS=30
ATTEMPT=0

echo "Waiting for PostgreSQL at ${HOST}:${PORT} (database: ${DATABASE}, user: ${USER})..."

until pg_isready -h "$HOST" -p "$PORT" -U "$USER" -d "$DATABASE" > /dev/null 2>&1; do
  ATTEMPT=$((ATTEMPT + 1))

  if [ $ATTEMPT -ge $MAX_ATTEMPTS ]; then
    echo "ERROR: PostgreSQL did not become ready within ${MAX_ATTEMPTS} seconds"
    echo ""
    echo "Troubleshooting:"
    echo "  1. Ensure Docker Compose is running: docker compose ps"
    echo "  2. Check PostgreSQL logs: docker compose logs postgres"
    echo "  3. Verify connection settings in .env file"
    echo ""
    exit 1
  fi

  printf "."
  sleep 1
done

echo ""
echo "PostgreSQL is ready!"
exit 0
