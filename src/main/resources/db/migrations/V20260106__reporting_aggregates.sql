-- ============================================================================
-- Village Storefront - Reporting Aggregates Schema
-- ============================================================================
-- Migration: V20260106__reporting_aggregates.sql
-- Description: Create reporting projection tables and export job tracking
-- References:
--   - Task: I3.T3 - Reporting Projection Service
--   - Architecture: docs/architecture/02_System_Structure_and_Data.md
--   - Architecture: docs/architecture/04_Operational_Architecture.md (Section 3.6)
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Create reporting aggregate tables
-- ============================================================================

-- ----------------------------------------------------------------------------
-- SALES BY PERIOD AGGREGATE
-- ----------------------------------------------------------------------------
-- Read-optimized table for sales reporting by time period

CREATE TABLE sales_by_period_aggregates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_amount DECIMAL(19, 4) NOT NULL DEFAULT 0,
    item_count INTEGER NOT NULL DEFAULT 0,
    order_count INTEGER NOT NULL DEFAULT 0,
    data_freshness_timestamp TIMESTAMPTZ NOT NULL,
    job_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sales_period UNIQUE (tenant_id, period_start, period_end)
);

CREATE INDEX idx_sales_aggregates_tenant ON sales_by_period_aggregates(tenant_id);
CREATE INDEX idx_sales_aggregates_period ON sales_by_period_aggregates(period_start, period_end);
CREATE INDEX idx_sales_aggregates_freshness ON sales_by_period_aggregates(data_freshness_timestamp);

COMMENT ON TABLE sales_by_period_aggregates IS 'Pre-computed sales totals by period for dashboards and exports';
COMMENT ON COLUMN sales_by_period_aggregates.data_freshness_timestamp IS 'Timestamp of last refresh for SLA monitoring';
COMMENT ON COLUMN sales_by_period_aggregates.job_name IS 'Name of scheduled job that produced this aggregate';

-- ----------------------------------------------------------------------------
-- CONSIGNMENT PAYOUT AGGREGATE
-- ----------------------------------------------------------------------------
-- Read-optimized table for consignment payout reporting

CREATE TABLE consignment_payout_aggregates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    consignor_id UUID NOT NULL REFERENCES consignors(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_owed DECIMAL(19, 4) NOT NULL DEFAULT 0,
    item_count INTEGER NOT NULL DEFAULT 0,
    items_sold INTEGER NOT NULL DEFAULT 0,
    data_freshness_timestamp TIMESTAMPTZ NOT NULL,
    job_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_consignment_payout_period UNIQUE (tenant_id, consignor_id, period_start, period_end)
);

CREATE INDEX idx_consignment_aggregates_tenant ON consignment_payout_aggregates(tenant_id);
CREATE INDEX idx_consignment_aggregates_consignor ON consignment_payout_aggregates(consignor_id);
CREATE INDEX idx_consignment_aggregates_period ON consignment_payout_aggregates(period_start, period_end);
CREATE INDEX idx_consignment_aggregates_freshness ON consignment_payout_aggregates(data_freshness_timestamp);

COMMENT ON TABLE consignment_payout_aggregates IS 'Pre-computed consignment payout amounts by consignor and period';
COMMENT ON COLUMN consignment_payout_aggregates.total_owed IS 'Total amount owed to consignor for period';
COMMENT ON COLUMN consignment_payout_aggregates.items_sold IS 'Number of consignment items sold in period';

-- ----------------------------------------------------------------------------
-- INVENTORY AGING AGGREGATE
-- ----------------------------------------------------------------------------
-- Read-optimized table for inventory aging analysis

CREATE TABLE inventory_aging_aggregates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    location_id UUID NOT NULL REFERENCES inventory_locations(id) ON DELETE CASCADE,
    days_in_stock INTEGER NOT NULL DEFAULT 0,
    quantity INTEGER NOT NULL DEFAULT 0,
    first_received_at TIMESTAMPTZ,
    data_freshness_timestamp TIMESTAMPTZ NOT NULL,
    job_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_inventory_aging UNIQUE (tenant_id, variant_id, location_id)
);

CREATE INDEX idx_inventory_aging_tenant ON inventory_aging_aggregates(tenant_id);
CREATE INDEX idx_inventory_aging_variant ON inventory_aging_aggregates(variant_id);
CREATE INDEX idx_inventory_aging_location ON inventory_aging_aggregates(location_id);
CREATE INDEX idx_inventory_aging_days ON inventory_aging_aggregates(days_in_stock DESC);
CREATE INDEX idx_inventory_aging_freshness ON inventory_aging_aggregates(data_freshness_timestamp);

COMMENT ON TABLE inventory_aging_aggregates IS 'Pre-computed inventory aging metrics for slow-mover analysis';
COMMENT ON COLUMN inventory_aging_aggregates.days_in_stock IS 'Number of days variant has been at this location';
COMMENT ON COLUMN inventory_aging_aggregates.first_received_at IS 'Timestamp of first receipt at location';

-- ----------------------------------------------------------------------------
-- REPORT JOBS
-- ----------------------------------------------------------------------------
-- Background job tracking for report generation and exports

CREATE TABLE report_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    report_type VARCHAR(50) NOT NULL,  -- sales_by_period, consignment_payout, inventory_aging
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, running, completed, failed
    requested_by VARCHAR(255),
    parameters TEXT,  -- JSON payload with report parameters
    result_url VARCHAR(2048),  -- Signed URL to download result
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_report_job_status CHECK (status IN ('pending', 'running', 'completed', 'failed'))
);

CREATE INDEX idx_report_jobs_tenant ON report_jobs(tenant_id);
CREATE INDEX idx_report_jobs_status ON report_jobs(status) WHERE status IN ('pending', 'running');
CREATE INDEX idx_report_jobs_type ON report_jobs(report_type);
CREATE INDEX idx_report_jobs_created ON report_jobs(created_at DESC);

COMMENT ON TABLE report_jobs IS 'Background job queue for async report generation and exports';
COMMENT ON COLUMN report_jobs.parameters IS 'JSON blob with report-specific parameters (date ranges, filters, etc.)';
COMMENT ON COLUMN report_jobs.result_url IS 'Cloudflare R2 signed URL for downloading generated report';

-- ============================================================================
-- MIGRATION DOWN: Drop all reporting tables
-- ============================================================================

-- +migrate Down

DROP TABLE IF EXISTS report_jobs CASCADE;
DROP TABLE IF EXISTS inventory_aging_aggregates CASCADE;
DROP TABLE IF EXISTS consignment_payout_aggregates CASCADE;
DROP TABLE IF EXISTS sales_by_period_aggregates CASCADE;
