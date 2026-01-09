-- Migration: V20260112__domain_events_table.sql
-- Description: Create domain_events table for event sourcing and reporting projections
-- Task: I2.T4 - Inventory service domain events
-- References: Architecture Section 4.0 (domain events for catalog/inventory)

CREATE TABLE domain_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    metadata JSONB,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Index for tenant-scoped queries
CREATE INDEX idx_domain_events_tenant_aggregate
    ON domain_events(tenant_id, aggregate_type, aggregate_id);

-- Index for event type queries (reporting consumers)
CREATE INDEX idx_domain_events_tenant_type_occurred
    ON domain_events(tenant_id, event_type, occurred_at DESC);

-- Index for temporal queries
CREATE INDEX idx_domain_events_occurred_at
    ON domain_events(occurred_at DESC);

-- Enable Row-Level Security
ALTER TABLE domain_events ENABLE ROW LEVEL SECURITY;

-- RLS Policy: Tenant isolation
CREATE POLICY domain_events_tenant_isolation ON domain_events
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);

-- Grant permissions
GRANT SELECT, INSERT ON domain_events TO storefront_app;

COMMENT ON TABLE domain_events IS 'Immutable domain events for event sourcing and reporting projections';
COMMENT ON COLUMN domain_events.aggregate_type IS 'Type of aggregate (e.g., INVENTORY_LEVEL, INVENTORY_TRANSFER)';
COMMENT ON COLUMN domain_events.event_type IS 'Type of event (e.g., INVENTORY_ADJUSTED, TRANSFER_INITIATED)';
COMMENT ON COLUMN domain_events.payload IS 'Event-specific data as JSON';
COMMENT ON COLUMN domain_events.metadata IS 'Correlation IDs, user context, trace information';
