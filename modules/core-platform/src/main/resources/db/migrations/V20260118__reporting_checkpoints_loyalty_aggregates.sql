-- ============================================================================
-- Village Storefront - Reporting Checkpoints & Loyalty Aggregates
-- ============================================================================
-- Migration: V20260118__reporting_checkpoints_loyalty_aggregates.sql
-- Description: Create reporting checkpoint table for domain event processing watermarks and loyalty aggregates
-- References:
--   - Task: I5.T2 - Reporting & Retention Pipeline
--   - Architecture: docs/architecture/datamodel_tenancy_narrative.md (Partition Maintenance)
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Create reporting checkpoint and loyalty aggregate tables
-- ============================================================================

-- ----------------------------------------------------------------------------
-- REPORTING CHECKPOINTS
-- ----------------------------------------------------------------------------
-- Watermark/checkpoint tracking for idempotent domain event processing
-- Each processor (sales, inventory, loyalty, consignment) maintains independent checkpoint

CREATE TABLE reporting_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    processor_name VARCHAR(100) NOT NULL UNIQUE,
    last_processed_at TIMESTAMPTZ NOT NULL,
    events_processed BIGINT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reporting_checkpoints_processor ON reporting_checkpoints(processor_name);
CREATE INDEX idx_reporting_checkpoints_processed_at ON reporting_checkpoints(last_processed_at DESC);

COMMENT ON TABLE reporting_checkpoints IS 'Watermark tracking for idempotent domain event processing';
COMMENT ON COLUMN reporting_checkpoints.processor_name IS 'Unique name of event processor (sales_aggregator, inventory_aggregator, etc.)';
COMMENT ON COLUMN reporting_checkpoints.last_processed_at IS 'Timestamp of last successfully processed event (watermark)';
COMMENT ON COLUMN reporting_checkpoints.events_processed IS 'Total count of events processed by this processor';
COMMENT ON COLUMN reporting_checkpoints.last_error IS 'Last error message for debugging failed processing';

-- Initialize checkpoints for all processors
INSERT INTO reporting_checkpoints (id, processor_name, last_processed_at, events_processed)
VALUES
    (gen_random_uuid(), 'sales_aggregator', NOW(), 0),
    (gen_random_uuid(), 'inventory_aggregator', NOW(), 0),
    (gen_random_uuid(), 'loyalty_aggregator', NOW(), 0),
    (gen_random_uuid(), 'consignment_aggregator', NOW(), 0);

-- ----------------------------------------------------------------------------
-- LOYALTY AGGREGATES
-- ----------------------------------------------------------------------------
-- Read-optimized table for loyalty program reporting

CREATE TABLE loyalty_aggregates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    period_date DATE NOT NULL,
    points_earned INTEGER NOT NULL DEFAULT 0,
    points_redeemed INTEGER NOT NULL DEFAULT 0,
    active_members INTEGER NOT NULL DEFAULT 0,
    tier_distribution JSONB,  -- {"Bronze": 100, "Silver": 50, "Gold": 10}
    data_freshness_timestamp TIMESTAMPTZ NOT NULL,
    job_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_loyalty_period UNIQUE (tenant_id, period_date)
);

CREATE INDEX idx_loyalty_aggregates_tenant ON loyalty_aggregates(tenant_id);
CREATE INDEX idx_loyalty_aggregates_period ON loyalty_aggregates(period_date DESC);
CREATE INDEX idx_loyalty_aggregates_freshness ON loyalty_aggregates(data_freshness_timestamp);

COMMENT ON TABLE loyalty_aggregates IS 'Pre-computed loyalty program metrics by period';
COMMENT ON COLUMN loyalty_aggregates.points_earned IS 'Total points earned across all customers for this period';
COMMENT ON COLUMN loyalty_aggregates.points_redeemed IS 'Total points redeemed across all customers for this period';
COMMENT ON COLUMN loyalty_aggregates.active_members IS 'Count of customers with non-zero points balance';
COMMENT ON COLUMN loyalty_aggregates.tier_distribution IS 'JSON object with counts per tier (e.g., {"Bronze": 100, "Silver": 50})';

-- ============================================================================
-- MIGRATION DOWN: Drop reporting checkpoint and loyalty aggregate tables
-- ============================================================================

-- +migrate Down

DROP TABLE IF EXISTS loyalty_aggregates CASCADE;
DROP TABLE IF EXISTS reporting_checkpoints CASCADE;
