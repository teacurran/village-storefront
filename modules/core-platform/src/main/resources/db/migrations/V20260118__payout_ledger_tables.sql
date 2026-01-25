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

-- Feature flag INSERTs moved to seed scripts (columns added in later migration)

-- ============================================================================
-- End of Migration
-- ============================================================================
