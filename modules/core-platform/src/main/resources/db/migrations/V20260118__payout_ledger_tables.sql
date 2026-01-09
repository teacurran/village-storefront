-- Payout Ledger Tables
-- Task I3.T5: Consignment Payout Ledger
-- Adds payout_ledger and payout_ledger_entries tables for tracking consignor balances

-- ============================================================================
-- Payout Ledger Table
-- ============================================================================
-- Tracks pending and available balances for each consignor
CREATE TABLE payout_ledger (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    consignor_id UUID NOT NULL REFERENCES consignors(id) ON DELETE CASCADE,
    pending_balance DECIMAL(19,4) NOT NULL DEFAULT 0,     -- Unsettled sales
    available_balance DECIMAL(19,4) NOT NULL DEFAULT 0,   -- Ready for payout
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',           -- ISO 4217 currency code
    last_updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_payout_ledger_tenant_consignor UNIQUE (tenant_id, consignor_id)
);

CREATE INDEX idx_payout_ledger_tenant_id ON payout_ledger(tenant_id);
CREATE INDEX idx_payout_ledger_consignor_id ON payout_ledger(consignor_id);
CREATE INDEX idx_payout_ledger_available_balance ON payout_ledger(available_balance) WHERE available_balance > 0;

COMMENT ON TABLE payout_ledger IS 'Balance sheet for consignors tracking pending and available payout balances';
COMMENT ON COLUMN payout_ledger.pending_balance IS 'Unsettled sales awaiting hold period';
COMMENT ON COLUMN payout_ledger.available_balance IS 'Settled balance ready for immediate payout';

-- ============================================================================
-- Payout Ledger Entries Table
-- ============================================================================
-- Immutable transaction log for all balance changes
CREATE TABLE payout_ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ledger_id UUID NOT NULL REFERENCES payout_ledger(id) ON DELETE CASCADE,
    entry_type VARCHAR(20) NOT NULL,                      -- SALE, REFUND, SETTLEMENT, PAYOUT, ADJUSTMENT
    amount DECIMAL(19,4) NOT NULL,                        -- Positive = credit, Negative = debit
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    description VARCHAR(500),
    reference_id UUID,                                    -- order_id, payout_batch_id, etc.
    reference_type VARCHAR(50),                           -- ORDER, PAYOUT_BATCH, CONSIGNMENT_ITEM
    pending_balance_after DECIMAL(19,4) NOT NULL,        -- Snapshot of pending balance after transaction
    available_balance_after DECIMAL(19,4) NOT NULL,      -- Snapshot of available balance after transaction
    metadata JSONB,                                       -- Additional context (commission details, etc.)
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payout_ledger_entries_tenant_id ON payout_ledger_entries(tenant_id);
CREATE INDEX idx_payout_ledger_entries_ledger_id ON payout_ledger_entries(ledger_id);
CREATE INDEX idx_payout_ledger_entries_entry_type ON payout_ledger_entries(entry_type);
CREATE INDEX idx_payout_ledger_entries_reference ON payout_ledger_entries(reference_type, reference_id);
CREATE INDEX idx_payout_ledger_entries_created_at ON payout_ledger_entries(created_at DESC);

COMMENT ON TABLE payout_ledger_entries IS 'Immutable audit log of all consignor balance transactions';
COMMENT ON COLUMN payout_ledger_entries.entry_type IS 'Transaction type: SALE, REFUND, SETTLEMENT, PAYOUT, ADJUSTMENT';
COMMENT ON COLUMN payout_ledger_entries.amount IS 'Transaction amount (positive for credits, negative for debits)';
COMMENT ON COLUMN payout_ledger_entries.pending_balance_after IS 'Snapshot of pending balance after this transaction';
COMMENT ON COLUMN payout_ledger_entries.available_balance_after IS 'Snapshot of available balance after this transaction';

-- ============================================================================
-- Row Level Security
-- ============================================================================
-- Enable RLS for multi-tenancy
ALTER TABLE payout_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE payout_ledger_entries ENABLE ROW LEVEL SECURITY;

-- Policies will be added in a separate RLS policy migration

-- ============================================================================
-- Feature Flags
-- ============================================================================
-- Add feature flag for automated payout settlement
INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'consignment.payout.settlement.enabled', TRUE, '{"hold_period_days": 7}', NOW(), NOW(), 'consignment-team', 'HIGH', 30,
    'Controls automated settlement of pending balances to available after hold period',
    'Disable the flag to stop automated settlement jobs; manually trigger settlements if needed'
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'consignment.payout.settlement.enabled');

INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'consignment.payout.auto_sweep.enabled', FALSE, '{"min_balance": 50.00, "schedule": "weekly"}', NOW(), NOW(), 'consignment-team', 'HIGH', 90,
    'Controls automated payout batch creation and processing for consignors',
    'Disable the flag to stop auto-generating payout batches; payouts can still be triggered manually'
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'consignment.payout.auto_sweep.enabled');

-- ============================================================================
-- End of Migration
-- ============================================================================
