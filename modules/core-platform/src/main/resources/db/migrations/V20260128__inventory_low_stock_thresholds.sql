-- ----------------------------------------------------------------------------
-- V20260128__inventory_low_stock_thresholds.sql
-- ----------------------------------------------------------------------------
-- Description: Adds safety stock and low stock threshold columns to inventory_levels
-- Context:
--   * Task I2.T2 - Low stock alert scheduler + safety stock tracking
--   * Clarification 4 - Multi-location coordination and consignment handoff
--   * Acceptance Criteria - Scheduler stub logs + config toggles for email alerts
-- ----------------------------------------------------------------------------

ALTER TABLE inventory_levels
    ADD COLUMN IF NOT EXISTS safety_stock INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS low_stock_threshold INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN inventory_levels.safety_stock IS
    'Minimum quantity buffer before resupply is triggered for the location';

COMMENT ON COLUMN inventory_levels.low_stock_threshold IS
    'Threshold for low stock alerts (quantity - reserved <= threshold)';

-- Backfill existing rows to ensure defaults are materialized
UPDATE inventory_levels
SET safety_stock = COALESCE(safety_stock, 0),
    low_stock_threshold = COALESCE(low_stock_threshold, 0);

-- ----------------------------------------------------------------------------
-- End of migration
-- ----------------------------------------------------------------------------
