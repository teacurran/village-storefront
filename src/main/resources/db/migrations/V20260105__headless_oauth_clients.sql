-- ============================================================================
-- Headless OAuth client tables for Task I2.T7
-- ============================================================================
-- Creates oauth_clients + oauth_client_scopes for client credentials flow.
-- ============================================================================

-- +migrate Up
CREATE TABLE IF NOT EXISTS oauth_clients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    client_id VARCHAR(64) NOT NULL UNIQUE,
    client_secret_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    rate_limit_per_minute INTEGER NOT NULL DEFAULT 5000,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

COMMENT ON TABLE oauth_clients IS 'OAuth client credentials for headless API access (client_credentials flow)';
COMMENT ON COLUMN oauth_clients.client_secret_hash IS 'BCrypt hashed client secret';
COMMENT ON COLUMN oauth_clients.rate_limit_per_minute IS 'Per-client throttle applied by HeadlessAuthFilter';

CREATE TABLE IF NOT EXISTS oauth_client_scopes (
    oauth_client_id UUID NOT NULL REFERENCES oauth_clients(id) ON DELETE CASCADE,
    scope VARCHAR(64) NOT NULL,
    PRIMARY KEY (oauth_client_id, scope)
);

COMMENT ON TABLE oauth_client_scopes IS 'Scopes assigned to each OAuth client (catalog:read, cart:write, etc.)';

CREATE INDEX IF NOT EXISTS idx_oauth_clients_tenant ON oauth_clients(tenant_id);
CREATE INDEX IF NOT EXISTS idx_oauth_clients_active ON oauth_clients(active);

-- +migrate Down
DROP TABLE IF EXISTS oauth_client_scopes;
DROP TABLE IF EXISTS oauth_clients;
