#!/usr/bin/env bash
# tenant_seed.sh
#
# Seeds the development database with sample tenant, store, admin user, and test data.
# This script is designed to be idempotent and can be run multiple times safely.
#
# Usage:
#   ./scripts/dev/tenant_seed.sh [--catalog] [--consignment] [--reset]
#
# Options:
#   --catalog      Load sample catalog data (products, variants, categories, inventory)
#   --consignment  Load sample consignment data (consignors, payout ledgers)
#   --reset        Drop and recreate database before seeding (WARNING: destructive)
#   --help         Show this help message
#
# Examples:
#   ./scripts/dev/tenant_seed.sh                           # Load only tenant/admin data
#   ./scripts/dev/tenant_seed.sh --catalog --consignment  # Load all sample data
#   ./scripts/dev/tenant_seed.sh --reset --catalog        # Reset DB and reload catalog
#
# Environment Variables (from .env):
#   DB_NAME, DB_USER, DB_PASSWORD, DB_PORT

set -euo pipefail

# ==============================================================================
# CONFIGURATION & COLORS
# ==============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Script directory resolution (handles symlinks)
if [[ -L "$0" ]]; then
    SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "$0" 2>/dev/null || readlink "$0")")" && pwd)"
else
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
fi
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Flags
LOAD_CATALOG=false
LOAD_CONSIGNMENT=false
RESET_DB=false

# ==============================================================================
# HELPER FUNCTIONS
# ==============================================================================

info() {
    echo -e "${BLUE}ℹ${NC} $*"
}

success() {
    echo -e "${GREEN}✓${NC} $*"
}

warn() {
    echo -e "${YELLOW}⚠${NC} $*"
}

error() {
    echo -e "${RED}✗${NC} $*"
}

print_header() {
    echo ""
    echo -e "${BLUE}=============================================================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}=============================================================================${NC}"
    echo ""
}

# ==============================================================================
# PARSE ARGUMENTS
# ==============================================================================

while [[ $# -gt 0 ]]; do
    case $1 in
        --catalog)
            LOAD_CATALOG=true
            shift
            ;;
        --consignment)
            LOAD_CONSIGNMENT=true
            shift
            ;;
        --reset)
            RESET_DB=true
            shift
            ;;
        --help)
            head -n 25 "$0" | grep "^#" | sed 's/^# //'
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            echo "Run with --help for usage information"
            exit 1
            ;;
    esac
done

# ==============================================================================
# BANNER
# ==============================================================================

print_header "Village Storefront - Tenant Seed Script"

echo "This script seeds the development database with sample tenant and test data."
echo ""
echo "What will be seeded:"
echo "  • Sample tenants (techgadgets, artisancrafts)"
echo "  • Admin user accounts with credentials"
echo "  • Roles and permissions"

if [ "$LOAD_CATALOG" = true ]; then
    echo "  • Catalog data (products, variants, categories, inventory)"
fi

if [ "$LOAD_CONSIGNMENT" = true ]; then
    echo "  • Consignment data (consignors, payout ledgers)"
fi

if [ "$RESET_DB" = true ]; then
    echo ""
    warn "RESET MODE: Database will be dropped and recreated!"
    warn "Press Ctrl+C within 5 seconds to abort..."
    sleep 5
fi

echo ""

# ==============================================================================
# STEP 1: VALIDATE PREREQUISITES
# ==============================================================================

print_header "Step 1: Validating Prerequisites"

# Check for psql
if ! command -v psql > /dev/null 2>&1; then
    error "psql command not found"
    info "Install PostgreSQL client tools:"
    info "  macOS: brew install postgresql"
    info "  Ubuntu: sudo apt install postgresql-client"
    exit 1
fi
success "psql is available"

# ==============================================================================
# STEP 2: LOAD ENVIRONMENT CONFIGURATION
# ==============================================================================

print_header "Step 2: Loading Environment Configuration"

if [ ! -f "$PROJECT_ROOT/.env" ]; then
    error ".env file not found at $PROJECT_ROOT/.env"
    info "Create it from .env.example: cp .env.example .env"
    exit 1
fi

# Source .env file
set -a
# shellcheck disable=SC1091
source "$PROJECT_ROOT/.env"
set +a

success "Environment loaded from .env"
info "Database: ${DB_NAME} (user: ${DB_USER}, host: localhost:${DB_PORT})"

# ==============================================================================
# STEP 3: WAIT FOR POSTGRESQL READINESS
# ==============================================================================

print_header "Step 3: Checking PostgreSQL Connection"

info "Verifying PostgreSQL is ready..."

# Wait for PostgreSQL using wait-for-postgres.sh if available
if [ -f "$SCRIPT_DIR/wait-for-postgres.sh" ]; then
    if ! "$SCRIPT_DIR/wait-for-postgres.sh" localhost "${DB_PORT}" "${DB_USER}" "${DB_NAME}"; then
        error "PostgreSQL is not ready or connection failed"
        info "Ensure Docker Compose is running: docker compose up -d"
        exit 1
    fi
else
    # Fallback: try direct connection
    if ! PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -c "SELECT 1" > /dev/null 2>&1; then
        error "Cannot connect to PostgreSQL"
        info "Ensure Docker Compose is running: docker compose up -d"
        exit 1
    fi
fi

success "PostgreSQL is ready"

# ==============================================================================
# STEP 4: RESET DATABASE (if requested)
# ==============================================================================

if [ "$RESET_DB" = true ]; then
    print_header "Step 4: Resetting Database"

    warn "Dropping database: ${DB_NAME}"

    # Drop database (connect to postgres DB to drop target)
    PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${DB_PORT}" -U "${DB_USER}" -d postgres <<EOF
DROP DATABASE IF EXISTS ${DB_NAME};
CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};
EOF

    success "Database reset complete"
    info "Database ${DB_NAME} recreated"
    echo ""
    info "You must run migrations before seeding data:"
    info "  cd $PROJECT_ROOT"
    info "  ./mvnw -pl modules/core-platform flyway:migrate"
    echo ""
    info "Or start Quarkus dev mode (migrations run automatically):"
    info "  npm run dev"
    echo ""
    warn "This script will now exit. Run migrations and re-run this script."
    exit 0
fi

# ==============================================================================
# STEP 5: SEED CORE TENANTS + ADMIN USERS
# ==============================================================================

print_header "Step 5: Creating Core Tenants and Admin Accounts"

info "Ensuring techgadgets/artisancrafts tenants, roles, and admin users exist"

PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" <<'SQL'
-- Ensure extensions required for UUID/password helpers
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Tenants
INSERT INTO tenants (id, subdomain, name, status, settings, created_at, updated_at)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'techgadgets', 'Tech Gadgets Online', 'active', '{"currency": "USD", "timezone": "America/New_York"}'::jsonb, NOW(), NOW()),
    ('a0000000-0000-0000-0000-000000000002', 'artisancrafts', 'Artisan Crafts Collective', 'active', '{"currency": "USD", "timezone": "America/Los_Angeles"}'::jsonb, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Roles
INSERT INTO roles (id, tenant_id, name, description, permissions, created_at, updated_at)
VALUES
    ('f0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'Store Owner', 'Full access to tenant admin features', '["*"]'::jsonb, NOW(), NOW()),
    ('f0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', 'Store Owner', 'Full access to tenant admin features', '["*"]'::jsonb, NOW(), NOW()),
    ('f0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'Staff', 'Scoped to catalog/order/inventory management', '["catalog:*","orders:*","inventory:*"]'::jsonb, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Users
INSERT INTO users (id, tenant_id, email, password_hash, status, first_name, last_name, email_verified, created_at, updated_at)
VALUES
    ('e0000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'owner@techgadgets.local', crypt('changeme123!', gen_salt('bf')), 'active', 'Tessa', 'Gadget', TRUE, NOW(), NOW()),
    ('e0000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', 'owner@artisancrafts.local', crypt('changeme123!', gen_salt('bf')), 'active', 'Aria', 'Craft', TRUE, NOW(), NOW()),
    ('e0000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'staff@techgadgets.local', crypt('changeme123!', gen_salt('bf')), 'active', 'Sam', 'Fulfillment', TRUE, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- User role assignments
INSERT INTO user_roles (tenant_id, user_id, role_id, assigned_at, created_at, updated_at)
VALUES
    ('a0000000-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-000000000001', 'f0000000-0000-0000-0000-000000000001', NOW(), NOW(), NOW()),
    ('a0000000-0000-0000-0000-000000000002', 'e0000000-0000-0000-0000-000000000002', 'f0000000-0000-0000-0000-000000000002', NOW(), NOW(), NOW()),
    ('a0000000-0000-0000-0000-000000000001', 'e0000000-0000-0000-0000-000000000003', 'f0000000-0000-0000-0000-000000000003', NOW(), NOW(), NOW())
ON CONFLICT DO NOTHING;
SQL

success "Core tenant + admin data ensured"

# ==============================================================================
# STEP 6: LOAD CATALOG DATA
# ==============================================================================

if [ "$LOAD_CATALOG" = true ]; then
    print_header "Step 6: Loading Sample Catalog Data"

    CATALOG_SEED="$PROJECT_ROOT/tools/scripts/sample_catalog_loader.sql"

    if [ ! -f "$CATALOG_SEED" ]; then
        error "Catalog seed script not found: $CATALOG_SEED"
        exit 1
    fi

    info "Loading sample catalog from: $CATALOG_SEED"
    info "This creates:"
    info "  • 2 tenants (techgadgets, artisancrafts)"
    info "  • 3 roles (Store Owner x2, Staff)"
    info "  • 4 staff user accounts"
    info "  • 4 categories"
    info "  • 3 products with 7 variants"
    info "  • 8 inventory records"

    PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
        -v ON_ERROR_STOP=1 -f "$CATALOG_SEED"

    success "Sample catalog data loaded"
else
    info "Skipping catalog data (use --catalog to load products/inventory)"
fi

# ==============================================================================
# STEP 7: LOAD CONSIGNMENT DATA
# ==============================================================================

if [ "$LOAD_CONSIGNMENT" = true ]; then
    print_header "Step 7: Loading Sample Consignment Data"

    CONSIGNMENT_SEED="$PROJECT_ROOT/tools/scripts/sample_consignment_loader.sql"

    if [ ! -f "$CONSIGNMENT_SEED" ]; then
        error "Consignment seed script not found: $CONSIGNMENT_SEED"
        exit 1
    fi

    info "Loading sample consignment data from: $CONSIGNMENT_SEED"
    info "This creates:"
    info "  • 3 consignors across 2 tenants"
    info "  • Payout ledger entries with test balances"
    info "  • Mobile Accessories Hub: \$342.75 available for payout testing"

    PGPASSWORD="${DB_PASSWORD}" psql -h localhost -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" \
        -v ON_ERROR_STOP=1 -f "$CONSIGNMENT_SEED"

    success "Sample consignment data loaded"
else
    info "Skipping consignment data (use --consignment to load consignor/payout data)"
fi

# ==============================================================================
# COMPLETION SUMMARY
# ==============================================================================

print_header "Seed Complete!"

echo "Sample data has been loaded into the ${DB_NAME} database."
echo ""

echo -e "${GREEN}Sample Tenants:${NC}"
echo "  • techgadgets       (ID: a0000000-0000-0000-0000-000000000001)"
echo "  • artisancrafts     (ID: a0000000-0000-0000-0000-000000000002)"
echo ""
echo -e "${GREEN}Staff Login Credentials (password: changeme123!):${NC}"
echo "  • owner@techgadgets.local      (Store Owner role)"
echo "  • staff@techgadgets.local      (Staff role)"
echo "  • owner@artisancrafts.local    (Store Owner role)"
echo ""

if [ "$LOAD_CATALOG" = true ]; then
    echo -e "${GREEN}Catalog Sample Data:${NC}"
    echo "  • 4 categories, 3 products, 7 variants"
    echo "  • Inventory seeded for multiple warehouse locations"
    echo ""
fi

if [ "$LOAD_CONSIGNMENT" = true ]; then
    echo -e "${GREEN}Consignors (for payout testing):${NC}"
    echo "  • Vintage Audio Collector  - \$0.00 balance"
    echo "  • Mobile Accessories Hub   - \$342.75 available for payout"
    echo "  • Oak & Pine Woodworks     - \$0.00 balance"
    echo ""
fi

echo -e "${GREEN}Database Connection:${NC}"
echo "  Host:     localhost:${DB_PORT}"
echo "  Database: ${DB_NAME}"
echo "  User:     ${DB_USER}"
echo ""
echo -e "${GREEN}Useful Commands:${NC}"
echo "  • Connect to database:"
echo "    ${BLUE}psql -h localhost -p ${DB_PORT} -U ${DB_USER} -d ${DB_NAME}${NC}"
echo ""
echo "  • Verify tenant data:"
echo "    ${BLUE}psql -h localhost -p ${DB_PORT} -U ${DB_USER} -d ${DB_NAME} -c \"SELECT * FROM tenants;\"${NC}"
echo ""
echo "  • Reset and reseed everything:"
echo "    ${BLUE}./scripts/dev/tenant_seed.sh --reset${NC}"
echo "    ${BLUE}./mvnw -pl modules/core-platform flyway:migrate${NC}"
echo "    ${BLUE}./scripts/dev/tenant_seed.sh --catalog --consignment${NC}"
echo ""
echo "  • Clean slate (remove all data, keep schema):"
echo "    ${BLUE}docker compose down -v${NC}"
echo "    ${BLUE}docker compose up -d${NC}"
echo "    ${BLUE}./mvnw -pl modules/core-platform flyway:migrate${NC}"
echo "    ${BLUE}./scripts/dev/tenant_seed.sh --catalog --consignment${NC}"
echo ""

echo -e "${GREEN}Next Steps:${NC}"
echo "  1. Start Quarkus dev mode:"
echo "     ${BLUE}npm run dev${NC}"
echo ""
echo "  2. Access the application:"
echo "     ${BLUE}http://localhost:8080${NC}"
echo ""
echo "Happy coding! 🚀"
echo ""
