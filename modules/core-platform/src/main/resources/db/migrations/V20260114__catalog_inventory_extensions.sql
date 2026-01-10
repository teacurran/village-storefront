-- ============================================================================
-- Village Storefront - Catalog & Inventory Extensions
-- ============================================================================
-- Migration: V20260114__catalog_inventory_extensions.sql
-- Description: Create collections, inventory_locations, and product_collections
--              tables to complete catalog/inventory domain models
-- References:
--   - Task: I2.T2 - Catalog/Inventory MyBatis Migrations
--   - ERD: docs/diagrams/datamodel_erd.mmd (collections, inventory_locations)
--   - ADR-001: Tenant-scoped RLS policies
--   - Architecture: docs/architecture/02_System_Structure_and_Data.md Section 3.6
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Create collections and inventory_locations tables
-- ============================================================================

-- ----------------------------------------------------------------------------
-- COLLECTIONS TABLE
-- ----------------------------------------------------------------------------
-- Collections are curator-defined product groupings (e.g., "Summer Sale")
-- Unlike hierarchical categories, collections are flat and support both
-- manual product assignment and automatic rules-based selection

CREATE TABLE collections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    description TEXT,
    image_url VARCHAR(500),
    display_order INTEGER DEFAULT 0,
    collection_type VARCHAR(20) NOT NULL DEFAULT 'manual',  -- manual, automatic
    selection_rules JSONB,  -- JSON rules for automatic collections
    published BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',  -- draft, active, archived, deleted
    seo_title VARCHAR(255),
    seo_description TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_collections_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_collections_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT chk_collections_type CHECK (collection_type IN ('manual', 'automatic')),
    CONSTRAINT chk_collections_status CHECK (status IN ('draft', 'active', 'archived', 'deleted')),
    CONSTRAINT chk_collections_slug_format CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE INDEX idx_collections_tenant_id ON collections(tenant_id);
CREATE INDEX idx_collections_tenant_id_status ON collections(tenant_id, status);
CREATE INDEX idx_collections_slug ON collections(tenant_id, slug);
CREATE INDEX idx_collections_published ON collections(published) WHERE published = TRUE;

COMMENT ON TABLE collections IS 'Curator-defined product groupings for merchandising and promotions; supports manual and automatic selection';
COMMENT ON COLUMN collections.collection_type IS 'Manual collections require explicit product assignment; automatic collections use selection_rules';
COMMENT ON COLUMN collections.selection_rules IS 'JSONB rules for automatic collections (e.g., {"tags":["summer"],"min_price":10.00})';
COMMENT ON COLUMN collections.version IS 'Optimistic locking version for concurrent updates';

-- ----------------------------------------------------------------------------
-- PRODUCT_COLLECTIONS JOIN TABLE
-- ----------------------------------------------------------------------------
-- Many-to-many relationship between products and collections

CREATE TABLE product_collections (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    collection_id UUID NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    display_order INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, product_id, collection_id)
);

CREATE INDEX idx_product_collections_tenant_id ON product_collections(tenant_id);
CREATE INDEX idx_product_collections_product_id ON product_collections(product_id);
CREATE INDEX idx_product_collections_collection_id ON product_collections(collection_id);

COMMENT ON TABLE product_collections IS 'Many-to-many join table for product-collection assignments';
COMMENT ON COLUMN product_collections.display_order IS 'Sort order within collection for featured product placement';

-- ----------------------------------------------------------------------------
-- INVENTORY_LOCATIONS TABLE
-- ----------------------------------------------------------------------------
-- Physical or virtual locations where inventory is tracked
-- (warehouses, retail stores, supplier locations, consignment depots)

CREATE TABLE inventory_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50),  -- warehouse, retail, dropship, consignor
    address JSONB,  -- Structured address object
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_inventory_locations_tenant_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_inventory_locations_tenant_id ON inventory_locations(tenant_id);
CREATE INDEX idx_inventory_locations_tenant_id_active ON inventory_locations(tenant_id, active);
CREATE INDEX idx_inventory_locations_code ON inventory_locations(tenant_id, code);

COMMENT ON TABLE inventory_locations IS 'Physical or virtual inventory storage locations scoped to tenant';
COMMENT ON COLUMN inventory_locations.type IS 'Location type affects fulfillment logic (warehouse, retail, dropship, consignor)';
COMMENT ON COLUMN inventory_locations.address IS 'JSONB address object (line1, line2, city, state, postal_code, country)';
COMMENT ON COLUMN inventory_locations.active IS 'Inactive locations cannot accept new inventory transactions';

-- ----------------------------------------------------------------------------
-- INVENTORY_LEVELS EXTENSION (OPTIMISTIC LOCKING)
-- ----------------------------------------------------------------------------
-- Add version column to support @Version field in InventoryLevel entity

ALTER TABLE inventory_levels
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN inventory_levels.version IS 'Optimistic locking version for concurrent inventory updates';

-- ----------------------------------------------------------------------------
-- INVENTORY_ADJUSTMENTS TABLE
-- ----------------------------------------------------------------------------
-- Audit trail for inventory changes (cycle counts, damage, theft, corrections)

CREATE TABLE inventory_adjustments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    location_id UUID NOT NULL REFERENCES inventory_locations(id) ON DELETE CASCADE,
    quantity_change INTEGER NOT NULL,  -- Positive = addition, negative = reduction
    quantity_before INTEGER NOT NULL,
    quantity_after INTEGER NOT NULL,
    reason VARCHAR(50) NOT NULL,  -- CYCLE_COUNT, DAMAGE, RETURN, SHRINKAGE, FOUND, OTHER
    adjusted_by VARCHAR(255) NOT NULL,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_inventory_adjustments_reason CHECK (reason IN ('CYCLE_COUNT', 'DAMAGE', 'RETURN', 'SHRINKAGE', 'FOUND', 'OTHER')),
    CONSTRAINT chk_inventory_adjustments_quantity_nonnegative CHECK (quantity_before >= 0 AND quantity_after >= 0),
    CONSTRAINT chk_inventory_adjustments_balance CHECK (quantity_before + quantity_change = quantity_after)
) PARTITION BY RANGE (created_at);

-- Seed partitions for current and upcoming months plus default catch-all
CREATE TABLE IF NOT EXISTS inventory_adjustments_2026_01 PARTITION OF inventory_adjustments
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE IF NOT EXISTS inventory_adjustments_2026_02 PARTITION OF inventory_adjustments
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

CREATE TABLE IF NOT EXISTS inventory_adjustments_2026_03 PARTITION OF inventory_adjustments
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE IF NOT EXISTS inventory_adjustments_default PARTITION OF inventory_adjustments
    DEFAULT;

CREATE INDEX idx_inventory_adjustments_tenant_created_at ON inventory_adjustments(tenant_id, created_at DESC);
CREATE INDEX idx_inventory_adjustments_variant_created_at ON inventory_adjustments(variant_id, created_at DESC);
CREATE INDEX idx_inventory_adjustments_location_created_at ON inventory_adjustments(location_id, created_at DESC);

COMMENT ON TABLE inventory_adjustments IS 'Append-only audit log of all inventory quantity changes across locations (partitioned by month)';
COMMENT ON COLUMN inventory_adjustments.quantity_change IS 'Change in quantity (positive = increase, negative = decrease)';
COMMENT ON COLUMN inventory_adjustments.adjusted_by IS 'User or system that performed the adjustment';

-- ----------------------------------------------------------------------------
-- INVENTORY_TRANSFERS TABLE
-- ----------------------------------------------------------------------------
-- Move inventory between locations (warehouse-to-store, store-to-store)

CREATE TABLE inventory_transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    source_location_id UUID NOT NULL REFERENCES inventory_locations(id) ON DELETE RESTRICT,
    destination_location_id UUID NOT NULL REFERENCES inventory_locations(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, IN_TRANSIT, RECEIVED, CANCELLED
    initiated_by VARCHAR(255),
    expected_arrival_date TIMESTAMPTZ,
    carrier VARCHAR(100),
    tracking_number VARCHAR(255),
    shipping_cost INTEGER,
    barcode_job_id UUID,
    notes TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_inventory_transfers_status CHECK (status IN ('PENDING', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT chk_inventory_transfers_different_locations CHECK (source_location_id != destination_location_id)
);

CREATE INDEX idx_inventory_transfers_tenant_id ON inventory_transfers(tenant_id);
CREATE INDEX idx_inventory_transfers_status ON inventory_transfers(tenant_id, status);
CREATE INDEX idx_inventory_transfers_source_location ON inventory_transfers(source_location_id);
CREATE INDEX idx_inventory_transfers_destination_location ON inventory_transfers(destination_location_id);
CREATE INDEX idx_inventory_transfers_tracking_number ON inventory_transfers(tracking_number);
CREATE INDEX idx_inventory_transfers_expected_arrival ON inventory_transfers(expected_arrival_date);

COMMENT ON TABLE inventory_transfers IS 'Transfer batches for moving inventory between locations with audit metadata';
COMMENT ON COLUMN inventory_transfers.status IS 'Lifecycle: PENDING → IN_TRANSIT → RECEIVED';
COMMENT ON COLUMN inventory_transfers.initiated_by IS 'User or system that initiated the transfer';

-- ----------------------------------------------------------------------------
-- INVENTORY_TRANSFER_LINES TABLE
-- ----------------------------------------------------------------------------
-- Line items within inventory transfers

CREATE TABLE inventory_transfer_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    transfer_id UUID NOT NULL REFERENCES inventory_transfers(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL,
    received_quantity INTEGER DEFAULT 0,  -- Quantity confirmed at destination
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_inventory_transfer_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_inventory_transfer_lines_received_nonnegative CHECK (received_quantity >= 0),
    CONSTRAINT chk_inventory_transfer_lines_received_lte_quantity CHECK (received_quantity <= quantity)
);

CREATE INDEX idx_inventory_transfer_lines_tenant_id ON inventory_transfer_lines(tenant_id);
CREATE INDEX idx_inventory_transfer_lines_transfer_id ON inventory_transfer_lines(transfer_id);
CREATE INDEX idx_inventory_transfer_lines_variant_id ON inventory_transfer_lines(variant_id);

COMMENT ON TABLE inventory_transfer_lines IS 'Individual line items within inventory transfer batches';
COMMENT ON COLUMN inventory_transfer_lines.received_quantity IS 'Quantity confirmed received at destination (supports partial receipts)';

-- ============================================================================
-- END MIGRATION UP
-- ============================================================================

-- +migrate Down
-- ============================================================================
-- MIGRATION DOWN: Drop tables in reverse dependency order
-- ============================================================================

-- Inventory module extensions
DROP TABLE IF EXISTS inventory_transfer_lines CASCADE;
DROP TABLE IF EXISTS inventory_transfers CASCADE;
DROP TABLE IF EXISTS inventory_adjustments_default;
DROP TABLE IF EXISTS inventory_adjustments_2026_03;
DROP TABLE IF EXISTS inventory_adjustments_2026_02;
DROP TABLE IF EXISTS inventory_adjustments_2026_01;
DROP TABLE IF EXISTS inventory_adjustments CASCADE;
DROP TABLE IF EXISTS inventory_locations CASCADE;

-- Catalog module extensions
DROP TABLE IF EXISTS product_collections CASCADE;
DROP TABLE IF EXISTS collections CASCADE;

-- Inventory levels revert
ALTER TABLE inventory_levels
    DROP COLUMN IF EXISTS version;

-- ============================================================================
-- END MIGRATION DOWN
-- ============================================================================
