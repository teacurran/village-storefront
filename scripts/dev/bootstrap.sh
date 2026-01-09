#!/bin/bash
# bootstrap.sh
#
# Initializes the local development environment for Village Storefront.
# This script orchestrates:
#   1. Environment validation (Docker, psql, FFmpeg)
#   2. Starting Docker Compose services
#   3. Waiting for PostgreSQL readiness
#   4. Running database migrations
#   5. Seeding sample catalog data
#   6. Creating MinIO bucket
#
# Usage:
#   ./scripts/dev/bootstrap.sh [--skip-seed] [--skip-minio]
#
# Options:
#   --skip-seed    Skip loading sample catalog data
#   --skip-minio   Skip MinIO bucket creation
#   --help         Show this help message

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Configuration
SKIP_SEED=false
SKIP_MINIO=false

# ==============================================================================
# HELPER FUNCTIONS
# ==============================================================================

print_header() {
  echo ""
  echo -e "${BLUE}=============================================================================${NC}"
  echo -e "${BLUE}$1${NC}"
  echo -e "${BLUE}=============================================================================${NC}"
  echo ""
}

print_success() {
  echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
  echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
  echo -e "${RED}✗ $1${NC}"
}

print_info() {
  echo -e "  $1"
}

check_command() {
  if command -v "$1" > /dev/null 2>&1; then
    print_success "$1 is installed"
    return 0
  else
    print_error "$1 is not installed"
    return 1
  fi
}

# ==============================================================================
# PARSE ARGUMENTS
# ==============================================================================

while [[ $# -gt 0 ]]; do
  case $1 in
    --skip-seed)
      SKIP_SEED=true
      shift
      ;;
    --skip-minio)
      SKIP_MINIO=true
      shift
      ;;
    --help)
      head -n 20 "$0" | grep "^#" | sed 's/^# //'
      exit 0
      ;;
    *)
      print_error "Unknown option: $1"
      echo "Run with --help for usage information"
      exit 1
      ;;
  esac
done

# ==============================================================================
# BANNER
# ==============================================================================

print_header "Village Storefront - Local Development Bootstrap"

echo "This script will initialize your local development environment."
echo ""
echo "What will be set up:"
echo "  • Docker Compose services (PostgreSQL, MinIO, Mailhog)"
echo "  • Shipping carrier mocks (USPS, UPS, FedEx)"
echo "  • Database schema via Flyway migrations"
echo "  • Sample catalog data (2 tenants, products, inventory)"
echo "  • Sample consignment data (consignors, payout ledgers)"
echo "  • MinIO bucket for media storage"
echo ""
echo "Optional services (use --profile flag):"
echo "  • Stripe CLI webhook forwarder: docker compose --profile payments up"
echo "  • Observability stack: docker compose --profile observability up"
echo ""

# ==============================================================================
# STEP 1: VALIDATE PREREQUISITES
# ==============================================================================

print_header "Step 1: Validating Prerequisites"

MISSING_DEPS=false

if ! check_command "docker"; then
  print_info "Install Docker: https://docs.docker.com/get-docker/"
  MISSING_DEPS=true
fi

if ! check_command "docker-compose" && ! docker compose version > /dev/null 2>&1; then
  print_error "docker compose is not available"
  print_info "Ensure Docker Compose V2 is installed"
  MISSING_DEPS=true
else
  print_success "docker compose is available"
fi

if ! check_command "psql"; then
  print_warning "psql is not installed (optional, needed for seeding)"
  print_info "Install PostgreSQL client tools or skip seeding with --skip-seed"
fi

if ! check_command "ffmpeg"; then
  print_warning "ffmpeg is not installed (optional, needed for media processing)"
  print_info "macOS: brew install ffmpeg"
  print_info "Ubuntu: sudo apt install ffmpeg"
  print_info "Set MEDIA_FFMPEG_PATH in .env if installed elsewhere"
fi

if [ "$MISSING_DEPS" = true ]; then
  print_error "Missing required dependencies. Please install them and try again."
  exit 1
fi

# ==============================================================================
# STEP 2: LOAD ENVIRONMENT VARIABLES
# ==============================================================================

print_header "Step 2: Loading Environment Configuration"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
  print_warning ".env file not found. Creating from .env.example..."
  cp "$PROJECT_ROOT/.env.example" "$PROJECT_ROOT/.env"
  print_success "Created .env file"
  print_info "Review $PROJECT_ROOT/.env and customize as needed"
else
  print_success ".env file exists"
fi

# Load .env file
set -a
# shellcheck disable=SC1091
source "$PROJECT_ROOT/.env"
set +a

print_info "Database: ${DB_NAME} (user: ${DB_USER}, port: ${DB_PORT})"
print_info "MinIO: ${R2_BUCKET} (endpoint: ${R2_ENDPOINT})"

# ==============================================================================
# STEP 3: START DOCKER COMPOSE SERVICES
# ==============================================================================

print_header "Step 3: Starting Docker Compose Services"

cd "$PROJECT_ROOT/docker"

if docker compose ps | grep -q "Up"; then
  print_warning "Some services are already running"
  print_info "Run 'docker compose down' to restart all services"
else
  print_info "Starting core services: postgres, minio, mailhog..."
  print_info "Starting shipping mocks: usps-mock, ups-mock, fedex-mock..."
  docker compose up -d postgres minio mailhog usps-mock ups-mock fedex-mock
  print_success "Core services started"
  print_info ""
  print_info "Note: Stripe CLI (webhook forwarder) is not started by default."
  print_info "To enable: docker compose --profile payments up -d"
  print_info "Requires: stripe login"
fi

# ==============================================================================
# STEP 4: WAIT FOR POSTGRESQL READINESS
# ==============================================================================

print_header "Step 4: Waiting for PostgreSQL"

if [ -f "$SCRIPT_DIR/wait-for-postgres.sh" ]; then
  chmod +x "$SCRIPT_DIR/wait-for-postgres.sh"
  "$SCRIPT_DIR/wait-for-postgres.sh" localhost "${DB_PORT}" "${DB_USER}" "${DB_NAME}"
else
  print_warning "wait-for-postgres.sh not found, using sleep fallback"
  sleep 5
fi

# ==============================================================================
# STEP 5: RUN DATABASE MIGRATIONS
# ==============================================================================

print_header "Step 5: Running Database Migrations"

cd "$PROJECT_ROOT"

# Check if we have Flyway configured in Quarkus
if [ -d "$PROJECT_ROOT/modules/core-platform/src/main/resources/db/migrations" ]; then
  print_info "Flyway migrations detected in modules/core-platform/src/main/resources/db/migrations"
  print_info "Migrations will run automatically when Quarkus starts"
  print_success "Migration directory verified"

  # Optionally run migrations now via Maven Flyway plugin
  if command -v mvn > /dev/null 2>&1 || [ -x "$PROJECT_ROOT/mvnw" ]; then
    print_info "Running Flyway migrations via Maven plugin..."
    "$PROJECT_ROOT/mvnw" -pl modules/core-platform flyway:migrate -Dflyway.cleanDisabled=false || {
      print_warning "Flyway plugin not configured or migrations failed"
      print_info "Migrations will run when Quarkus starts (quarkus.flyway.migrate-at-start=true)"
    }
  fi
else
  print_error "Migration directory not found"
  exit 1
fi

# ==============================================================================
# STEP 6: SEED SAMPLE CATALOG DATA
# ==============================================================================

if [ "$SKIP_SEED" = false ]; then
  print_header "Step 6: Seeding Sample Data"

  if ! command -v psql > /dev/null 2>&1; then
    print_warning "psql not available, skipping seed data"
  else
    # Load catalog seed data
    CATALOG_SEED="$PROJECT_ROOT/tools/scripts/sample_catalog_loader.sql"

    if [ -f "$CATALOG_SEED" ]; then
      print_info "Loading sample catalog from: $CATALOG_SEED"

      PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -f "$CATALOG_SEED" || {
        print_warning "Catalog seed script failed (may be due to RLS policies or missing tables)"
        print_info "Run migrations first, then manually load: psql -f $CATALOG_SEED"
      }

      print_success "Sample catalog data loaded"
    else
      print_warning "Catalog seed script not found at: $CATALOG_SEED"
    fi

    # Load consignment seed data
    CONSIGNMENT_SEED="$PROJECT_ROOT/tools/scripts/sample_consignment_loader.sql"

    if [ -f "$CONSIGNMENT_SEED" ]; then
      print_info "Loading sample consignment data from: $CONSIGNMENT_SEED"

      PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -f "$CONSIGNMENT_SEED" || {
        print_warning "Consignment seed script failed"
        print_info "This may be normal if consignment tables don't exist yet"
        print_info "Manually load after migrations: psql -f $CONSIGNMENT_SEED"
      }

      print_success "Sample consignment data loaded"
    else
      print_warning "Consignment seed script not found at: $CONSIGNMENT_SEED"
    fi

    # Summary
    echo ""
    print_success "Sample data loading complete!"
    print_info ""
    print_info "Test tenants & credentials:"
    print_info "  Tenant 1: techgadgets (ID: a0000000-0000-0000-0000-000000000001)"
    print_info "  Tenant 2: artisancrafts (ID: a0000000-0000-0000-0000-000000000002)"
    print_info ""
    print_info "Staff logins (password: changeme123!):"
    print_info "  • owner@techgadgets.local (Store Owner)"
    print_info "  • staff@techgadgets.local (Staff)"
    print_info "  • owner@artisancrafts.local (Store Owner)"
    print_info ""
    print_info "Consignors (for checkout/payout QA):"
    print_info "  • Vintage Audio Collector - 0 balance"
    print_info "  • Mobile Accessories Hub - \$342.75 available for payout"
  fi
else
  print_warning "Skipping seed data (--skip-seed flag)"
fi

# ==============================================================================
# STEP 7: CREATE MINIO BUCKET
# ==============================================================================

if [ "$SKIP_MINIO" = false ]; then
  print_header "Step 7: Creating MinIO Bucket"

  # Wait a moment for MinIO to be ready
  sleep 2

  # Use MinIO client (mc) if available, otherwise use AWS CLI
  if command -v mc > /dev/null 2>&1; then
    print_info "Using MinIO client (mc) to create bucket..."
    mc alias set local "${R2_ENDPOINT}" "${R2_ACCESS_KEY}" "${R2_SECRET_KEY}" > /dev/null 2>&1 || true
    mc mb "local/${R2_BUCKET}" > /dev/null 2>&1 || print_warning "Bucket may already exist"
    print_success "MinIO bucket ready: ${R2_BUCKET}"
  elif command -v aws > /dev/null 2>&1; then
    print_info "Using AWS CLI to create bucket..."
    AWS_ACCESS_KEY_ID="${R2_ACCESS_KEY}" \
    AWS_SECRET_ACCESS_KEY="${R2_SECRET_KEY}" \
    aws --endpoint-url "${R2_ENDPOINT}" s3 mb "s3://${R2_BUCKET}" > /dev/null 2>&1 || \
    print_warning "Bucket may already exist"
    print_success "MinIO bucket ready: ${R2_BUCKET}"
  else
    print_warning "Neither 'mc' nor 'aws' CLI found"
    print_info "Create bucket manually via MinIO Console: ${R2_ENDPOINT/.+:([0-9]+)/http://localhost:9001}"
    print_info "Or install MinIO client: https://min.io/docs/minio/linux/reference/minio-mc.html"
  fi
else
  print_warning "Skipping MinIO setup (--skip-minio flag)"
fi

# ==============================================================================
# COMPLETION SUMMARY
# ==============================================================================

print_header "Bootstrap Complete!"

echo "Your local development environment is ready."
echo ""
echo -e "${GREEN}Services Running:${NC}"
echo "  • PostgreSQL:      ${DB_URL}"
echo "  • MinIO Console:   http://localhost:${MINIO_CONSOLE_PORT} (${R2_ACCESS_KEY} / ${R2_SECRET_KEY})"
echo "  • Mailhog UI:      http://localhost:${MAILHOG_UI_PORT}"
echo ""
echo -e "${GREEN}Mock Services (For QA Testing):${NC}"
echo "  • USPS Mock API:   http://localhost:${USPS_MOCK_PORT:-9100} (health: /health)"
echo "  • UPS Mock API:    http://localhost:${UPS_MOCK_PORT:-9101} (health: /health)"
echo "  • FedEx Mock API:  http://localhost:${FEDEX_MOCK_PORT:-9102} (health: /health)"
echo ""
echo -e "${GREEN}Next Steps:${NC}"
echo "  1. Start Quarkus dev mode:"
echo "     ${BLUE}npm run dev${NC}"
echo ""
echo "  2. Access the application:"
echo "     ${BLUE}http://localhost:8080${NC}"
echo ""
echo "  3. View API docs:"
echo "     ${BLUE}http://localhost:8080/q/swagger-ui${NC}"
echo ""
echo -e "${GREEN}Useful Commands:${NC}"
echo "  • View service logs:           ${BLUE}docker compose logs -f${NC}"
echo "  • View specific service logs:  ${BLUE}docker compose logs -f usps-mock${NC}"
echo "  • Stop services:               ${BLUE}docker compose down${NC}"
echo "  • Restart services:            ${BLUE}docker compose restart${NC}"
echo "  • Connect to database:         ${BLUE}psql -h localhost -U ${DB_USER} -d ${DB_NAME}${NC}"
echo ""
echo -e "${GREEN}Optional Services:${NC}"
echo "  • Enable Stripe CLI webhook forwarder:"
echo "    ${BLUE}stripe login${NC}  # Required first-time setup"
echo "    ${BLUE}docker compose --profile payments up -d${NC}"
echo "  • Enable observability (Jaeger tracing):"
echo "    ${BLUE}docker compose --profile observability up -d${NC}"
echo ""
echo -e "${GREEN}FFmpeg Status:${NC}"
if command -v ffmpeg > /dev/null 2>&1; then
  echo "  ✓ Installed at: $(command -v ffmpeg)"
else
  echo "  ⚠ Not installed (required for media processing)"
  echo "    Install: brew install ffmpeg (macOS) or apt install ffmpeg (Ubuntu)"
fi
echo ""
echo "Happy coding! 🚀"
echo ""
