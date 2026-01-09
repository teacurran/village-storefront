-- Payment Refunds Table
-- Task I3.T3: PaymentProvider + Stripe Refunds
-- Adds refunds table for tracking payment refund lifecycle

-- ============================================================================
-- Refunds Table
-- ============================================================================
-- Tracks refund lifecycle with provider-agnostic status
CREATE TABLE refunds (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    payment_intent_id BIGINT NOT NULL REFERENCES payment_intents(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,                    -- 'stripe', 'paypal', etc.
    provider_refund_id VARCHAR(255) NOT NULL UNIQUE,  -- Provider's refund ID
    order_id UUID,                                    -- Link to orders table
    amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,                     -- ISO 4217 currency code
    status VARCHAR(50) NOT NULL,                      -- PENDING, SUCCEEDED, FAILED, CANCELLED
    reason VARCHAR(100),                              -- duplicate, fraudulent, requested_by_customer
    idempotency_key VARCHAR(255),                     -- Client idempotency key
    metadata TEXT,                                    -- JSON metadata
    failure_reason VARCHAR(500),                      -- Reason for failure if status = FAILED
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_refund_provider_id ON refunds(provider_refund_id);
CREATE INDEX idx_refund_tenant_id ON refunds(tenant_id);
CREATE INDEX idx_refund_payment_intent_id ON refunds(payment_intent_id);
CREATE INDEX idx_refund_order_id ON refunds(order_id);
CREATE INDEX idx_refund_status ON refunds(status);
CREATE INDEX idx_refund_created_at ON refunds(created_at);

COMMENT ON TABLE refunds IS 'Refund tracking with provider-agnostic status';
COMMENT ON COLUMN refunds.reason IS 'Refund reason: duplicate, fraudulent, requested_by_customer';
COMMENT ON COLUMN refunds.failure_reason IS 'Provider-specific failure reason if refund failed';

-- ============================================================================
-- Row Level Security
-- ============================================================================
-- Enable RLS for multi-tenancy
ALTER TABLE refunds ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- Default Feature Flags
-- ============================================================================
-- Ensure critical payment kill switches default to enabled state
INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'payments.stripe.enabled', TRUE, '{}', NOW(), NOW(), 'payments-team', 'CRITICAL', 30,
    'Emergency kill switch for Stripe payment processing',
    'Set enabled=false to immediately halt Stripe payment creation/capture requests'
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'payments.stripe.enabled');

INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'payments.payout.reconciliation.enabled', TRUE, '{}', NOW(), NOW(), 'payments-team', 'HIGH', 90,
    'Controls payout reconciliation workers to pause processing during provider incidents',
    'Disable the flag to stop enqueueing and processing payout reconciliation jobs')
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'payments.payout.reconciliation.enabled');

-- ============================================================================
-- End of Migration
-- ============================================================================
