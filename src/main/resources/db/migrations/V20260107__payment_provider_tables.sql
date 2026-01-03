-- Payment Provider Framework Tables
-- Task I4.T1: PaymentProvider + Stripe Integration
-- Adds tables for payment intents, webhook events, connected accounts, and platform fee configuration

-- ============================================================================
-- Webhook Events Table
-- ============================================================================
-- Stores all webhook events from payment providers for idempotency and audit
CREATE TABLE webhook_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,                  -- 'stripe', 'paypal', etc.
    provider_event_id VARCHAR(255) NOT NULL UNIQUE, -- Provider's event ID
    event_type VARCHAR(100) NOT NULL,               -- 'payment_intent.succeeded', etc.
    payload TEXT NOT NULL,                          -- Raw JSON payload
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processing_error TEXT,
    received_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_webhook_provider_event_id ON webhook_events(provider_event_id);
CREATE INDEX idx_webhook_tenant_id ON webhook_events(tenant_id);
CREATE INDEX idx_webhook_processed ON webhook_events(processed);
CREATE INDEX idx_webhook_event_type ON webhook_events(event_type);
CREATE INDEX idx_webhook_received_at ON webhook_events(received_at);

COMMENT ON TABLE webhook_events IS 'Webhook events from payment providers with idempotency tracking';
COMMENT ON COLUMN webhook_events.provider_event_id IS 'Unique event ID from provider, used for duplicate detection';
COMMENT ON COLUMN webhook_events.processed IS 'Whether event has been successfully processed';

-- ============================================================================
-- Payment Intents Table
-- ============================================================================
-- Tracks payment lifecycle with provider-agnostic status
CREATE TABLE payment_intents (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,                    -- 'stripe', 'paypal', etc.
    provider_payment_id VARCHAR(255) NOT NULL UNIQUE, -- Provider's payment ID
    order_id BIGINT,                                  -- Link to orders table
    amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,                     -- ISO 4217 currency code
    status VARCHAR(50) NOT NULL,                      -- PENDING, AUTHORIZED, CAPTURED, etc.
    capture_method VARCHAR(20) NOT NULL,              -- AUTOMATIC, MANUAL
    amount_captured DECIMAL(19,4),
    amount_refunded DECIMAL(19,4),
    client_secret VARCHAR(500),                       -- For client-side confirmation
    payment_method_id VARCHAR(255),                   -- Provider payment method ID
    customer_id VARCHAR(255),                         -- Provider customer ID
    idempotency_key VARCHAR(255),                     -- Client idempotency key
    metadata TEXT,                                    -- JSON metadata
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_payment_provider_id ON payment_intents(provider_payment_id);
CREATE INDEX idx_payment_tenant_id ON payment_intents(tenant_id);
CREATE INDEX idx_payment_order_id ON payment_intents(order_id);
CREATE INDEX idx_payment_status ON payment_intents(status);
CREATE INDEX idx_payment_idempotency_key ON payment_intents(idempotency_key);
CREATE INDEX idx_payment_created_at ON payment_intents(created_at);

COMMENT ON TABLE payment_intents IS 'Payment intent tracking with provider-agnostic status';
COMMENT ON COLUMN payment_intents.capture_method IS 'AUTOMATIC: capture immediately, MANUAL: capture later';
COMMENT ON COLUMN payment_intents.idempotency_key IS 'Client-provided key to prevent duplicate payment creation';

-- ============================================================================
-- Connected Accounts Table (Stripe Connect, PayPal Commerce, etc.)
-- ============================================================================
-- Tracks marketplace connected account onboarding and capabilities
CREATE TABLE connect_accounts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,                        -- 'stripe', 'paypal', etc.
    provider_account_id VARCHAR(255) NOT NULL UNIQUE,     -- Provider's account ID
    consignor_id UUID,                                    -- Link to consignors table
    email VARCHAR(255),
    business_name VARCHAR(255),
    country VARCHAR(2),                                   -- ISO 3166-1 alpha-2
    onboarding_status VARCHAR(50) NOT NULL,               -- PENDING, IN_PROGRESS, COMPLETED, etc.
    onboarding_url VARCHAR(1000),                         -- Onboarding URL for account holder
    capabilities_enabled TEXT,                            -- JSON array of enabled capabilities
    payouts_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    charges_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    metadata TEXT,                                        -- JSON metadata
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_connect_provider_account_id ON connect_accounts(provider_account_id);
CREATE INDEX idx_connect_tenant_id ON connect_accounts(tenant_id);
CREATE INDEX idx_connect_consignor_id ON connect_accounts(consignor_id);
CREATE INDEX idx_connect_onboarding_status ON connect_accounts(onboarding_status);

COMMENT ON TABLE connect_accounts IS 'Connected accounts for marketplace functionality (Stripe Connect, etc.)';
COMMENT ON COLUMN connect_accounts.onboarding_status IS 'PENDING, IN_PROGRESS, COMPLETED, RESTRICTED, DISABLED';
COMMENT ON COLUMN connect_accounts.payouts_enabled IS 'Whether account can receive payouts';

-- ============================================================================
-- Platform Fee Configuration Table
-- ============================================================================
-- Tenant-specific fee schedules for marketplace transactions
CREATE TABLE platform_fee_configs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    fee_percentage DECIMAL(5,4) NOT NULL,           -- e.g., 0.0500 for 5%
    fixed_fee_amount DECIMAL(19,4),                 -- Optional fixed fee per transaction
    currency VARCHAR(3) NOT NULL,                   -- Currency for fixed fee
    minimum_fee DECIMAL(19,4),                      -- Minimum platform fee
    maximum_fee DECIMAL(19,4),                      -- Maximum platform fee
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMP NOT NULL,
    effective_to TIMESTAMP,
    notes TEXT,                                     -- Admin notes
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_fee_config_tenant_id ON platform_fee_configs(tenant_id);
CREATE INDEX idx_fee_config_active ON platform_fee_configs(active);
CREATE INDEX idx_fee_config_effective_dates ON platform_fee_configs(effective_from, effective_to);

COMMENT ON TABLE platform_fee_configs IS 'Platform fee configuration per tenant for marketplace revenue';
COMMENT ON COLUMN platform_fee_configs.fee_percentage IS 'Percentage fee (e.g., 0.0500 = 5%)';
COMMENT ON COLUMN platform_fee_configs.minimum_fee IS 'Minimum fee charged regardless of percentage calculation';

-- ============================================================================
-- Foreign Key Constraints
-- ============================================================================

-- Payment intents to orders (when order table exists)
-- ALTER TABLE payment_intents ADD CONSTRAINT fk_payment_order
--     FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL;

-- Connected accounts to consignors
ALTER TABLE connect_accounts ADD CONSTRAINT fk_connect_consignor
    FOREIGN KEY (consignor_id) REFERENCES consignors(id) ON DELETE CASCADE;

-- ============================================================================
-- Permissions (Row Level Security)
-- ============================================================================
-- Enable RLS on all payment tables for multi-tenancy

ALTER TABLE webhook_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_intents ENABLE ROW LEVEL SECURITY;
ALTER TABLE connect_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_fee_configs ENABLE ROW LEVEL SECURITY;

-- Policies will be defined per environment based on tenant isolation strategy
-- Example policy (adjust based on actual RLS implementation):
-- CREATE POLICY webhook_events_tenant_isolation ON webhook_events
--     USING (tenant_id = current_setting('app.current_tenant')::TEXT);

-- ============================================================================
-- Sample Data for Development
-- ============================================================================
-- Insert default platform fee configuration for existing tenants

INSERT INTO platform_fee_configs (
    tenant_id,
    fee_percentage,
    fixed_fee_amount,
    currency,
    minimum_fee,
    maximum_fee,
    active,
    effective_from,
    notes,
    created_at,
    updated_at
)
SELECT
    t.id,
    0.0300,                                 -- 3% default fee
    0.30,                                   -- $0.30 fixed fee
    'USD',
    0.50,                                   -- $0.50 minimum
    NULL,                                   -- No maximum
    TRUE,
    NOW(),
    'Default platform fee configuration',
    NOW(),
    NOW()
FROM tenants t
WHERE NOT EXISTS (
    SELECT 1 FROM platform_fee_configs pfc WHERE pfc.tenant_id = t.id
);

-- ============================================================================
-- End of Migration
-- ============================================================================
