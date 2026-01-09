-- Shipping integration schema additions (Task I3.T4)
-- Introduces shipping_profiles (per-tenant carrier configuration) and shipping_labels (label + tracking records)

-- Shipping profiles store carrier preferences, credentials, and origin addresses
CREATE TABLE IF NOT EXISTS shipping_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    enabled_carriers VARCHAR(255) NOT NULL,
    origin_address JSONB NOT NULL,
    carrier_credentials JSONB DEFAULT '{}'::jsonb,
    metadata JSONB DEFAULT '{}'::jsonb,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_shipping_profiles_tenant ON shipping_profiles(tenant_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_shipping_profiles_default
    ON shipping_profiles(tenant_id) WHERE is_default = TRUE;

COMMENT ON TABLE shipping_profiles IS 'Tenant-scoped shipping configurations (origin, carriers, credentials)';
COMMENT ON COLUMN shipping_profiles.enabled_carriers IS 'Comma-separated carrier codes (USPS,UPS,FEDEX,...)';

-- Shipping labels persist label/tracking metadata emitted to carriers
CREATE TABLE IF NOT EXISTS shipping_labels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    carrier_code VARCHAR(20) NOT NULL,
    service_level VARCHAR(50) NOT NULL,
    tracking_number VARCHAR(100) NOT NULL,
    label_url VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    cost NUMERIC(10,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    estimated_delivery TIMESTAMPTZ,
    shipped_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    carrier_metadata JSONB DEFAULT '{}'::jsonb,
    correlation_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_shipping_labels_tenant ON shipping_labels(tenant_id);
CREATE INDEX IF NOT EXISTS idx_shipping_labels_order ON shipping_labels(order_id);

COMMENT ON TABLE shipping_labels IS 'Carrier label + tracking metadata linked to orders';
COMMENT ON COLUMN shipping_labels.status IS 'CREATED, VOIDED, REFUNDED, IN_TRANSIT, DELIVERED, EXCEPTION';
