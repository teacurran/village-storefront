-- ============================================================================
-- Village Storefront - Catalog & Inventory RLS Policies
-- ============================================================================
-- Migration: V20260115__catalog_inventory_rls_policies.sql
-- Description: Enable Row Level Security on new catalog/inventory tables
-- References:
--   - Task: I2.T2 - Catalog/Inventory RLS Policy Enforcement
--   - Migration: V20260114__catalog_inventory_extensions.sql (table definitions)
--   - Migration: V20260113__enable_rls_policies.sql (RLS helper functions)
--   - ADR-001: Tenant-scoped RLS policies
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Enable RLS and create policies for new tables
-- ============================================================================

-- ----------------------------------------------------------------------------
-- COLLECTIONS TABLE RLS
-- ----------------------------------------------------------------------------

ALTER TABLE collections ENABLE ROW LEVEL SECURITY;
ALTER TABLE collections FORCE ROW LEVEL SECURITY;

CREATE POLICY collections_isolation_policy ON collections
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

COMMENT ON POLICY collections_isolation_policy ON collections IS 'RLS policy: restrict collections to current tenant';

-- ----------------------------------------------------------------------------
-- PRODUCT_COLLECTIONS JOIN TABLE RLS
-- ----------------------------------------------------------------------------

ALTER TABLE product_collections ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_collections FORCE ROW LEVEL SECURITY;

CREATE POLICY product_collections_isolation_policy ON product_collections
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

COMMENT ON POLICY product_collections_isolation_policy ON product_collections IS 'RLS policy: restrict product-collection links to current tenant';

-- ----------------------------------------------------------------------------
-- INVENTORY_LOCATIONS TABLE RLS
-- ----------------------------------------------------------------------------

ALTER TABLE inventory_locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_locations FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_locations_isolation_policy ON inventory_locations
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

COMMENT ON POLICY inventory_locations_isolation_policy ON inventory_locations IS 'RLS policy: restrict inventory locations to current tenant';

-- ----------------------------------------------------------------------------
-- INVENTORY_ADJUSTMENTS TABLE RLS
-- ----------------------------------------------------------------------------

ALTER TABLE inventory_adjustments ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_adjustments FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_adjustments_isolation_policy ON inventory_adjustments
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

COMMENT ON POLICY inventory_adjustments_isolation_policy ON inventory_adjustments IS 'RLS policy: restrict inventory adjustments to current tenant';

-- ----------------------------------------------------------------------------
-- INVENTORY_TRANSFERS TABLE RLS
-- ----------------------------------------------------------------------------

ALTER TABLE inventory_transfers ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_transfers FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_transfers_isolation_policy ON inventory_transfers
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

COMMENT ON POLICY inventory_transfers_isolation_policy ON inventory_transfers IS 'RLS policy: restrict inventory transfers to current tenant';

-- ----------------------------------------------------------------------------
-- INVENTORY_TRANSFER_LINES TABLE RLS
-- ----------------------------------------------------------------------------

ALTER TABLE inventory_transfer_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_transfer_lines FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_transfer_lines_isolation_policy ON inventory_transfer_lines
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

COMMENT ON POLICY inventory_transfer_lines_isolation_policy ON inventory_transfer_lines IS 'RLS policy: restrict inventory transfer lines to current tenant';

-- ============================================================================
-- END MIGRATION UP
-- ============================================================================

-- Down migration handled via MyBatis migrations (omitted in Flyway).
