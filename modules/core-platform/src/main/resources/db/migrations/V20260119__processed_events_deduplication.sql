-- ============================================================================
-- Village Storefront - Processed Events Deduplication Table
-- ============================================================================
-- Migration: V20260119__processed_events_deduplication.sql
-- Description: Create table to track processed domain events for idempotent aggregation
-- References:
--   - Task: I5.T2 - Reporting & Retention Pipeline (idempotency fix)
--   - Pattern: Event deduplication for lookback window overlap prevention
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Create processed events tracking table
-- ============================================================================

-- ----------------------------------------------------------------------------
-- PROCESSED DOMAIN EVENTS
-- ----------------------------------------------------------------------------
-- Tracks which domain events have been processed by aggregators to prevent
-- double-counting when lookback window causes event reprocessing

CREATE TABLE IF NOT EXISTS processed_domain_events (
    event_id UUID PRIMARY KEY,
    processor_name VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_events_processor ON processed_domain_events(processor_name);
CREATE INDEX idx_processed_events_timestamp ON processed_domain_events(processed_at DESC);

COMMENT ON TABLE processed_domain_events IS 'Tracks processed domain events to ensure idempotent aggregation (prevents double-counting in lookback window)';
COMMENT ON COLUMN processed_domain_events.event_id IS 'Domain event UUID that has been successfully aggregated';
COMMENT ON COLUMN processed_domain_events.processor_name IS 'Name of processor that handled this event (domain_event_aggregator)';
COMMENT ON COLUMN processed_domain_events.processed_at IS 'Timestamp when event was processed';

-- ============================================================================
-- MIGRATION DOWN: Drop processed events tracking table
-- ============================================================================

-- +migrate Down

DROP TABLE IF EXISTS processed_domain_events CASCADE;
