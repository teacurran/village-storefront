-- POS Offline Queue and Device Management
-- Task I4.T7: POS offline queue + Stripe Terminal integration
-- ADR-001: Tenant-scoped data with RLS policies
-- References: gift_card_transactions.pos_device_id, job framework patterns

-- ========================================
-- POS Devices
-- ========================================

CREATE TABLE pos_devices (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    device_identifier VARCHAR(255) NOT NULL,  -- Hardware identifier (MAC address, serial)
    device_name VARCHAR(255) NOT NULL,  -- User-friendly name
    location_name VARCHAR(255),  -- Physical location

    -- Hardware details
    hardware_model VARCHAR(100),
    firmware_version VARCHAR(50),
    stripe_terminal_id VARCHAR(255),  -- Stripe Terminal reader ID

    -- Security
    encryption_key_hash VARCHAR(128) NOT NULL,  -- SHA-256 hash of device encryption key
    encryption_key_version INT NOT NULL DEFAULT 1,  -- For key rotation
    pairing_code VARCHAR(12),  -- Short code for initial pairing
    pairing_expires_at TIMESTAMPTZ,

    -- Status
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- pending, active, suspended, retired
    last_seen_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,

    -- Audit fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_pos_devices_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    CONSTRAINT uq_pos_devices_identifier UNIQUE (tenant_id, device_identifier)
);

CREATE INDEX idx_pos_devices_tenant_id ON pos_devices(tenant_id);
CREATE INDEX idx_pos_devices_status ON pos_devices(status) WHERE status IN ('PENDING', 'ACTIVE');
CREATE INDEX idx_pos_devices_last_seen ON pos_devices(last_seen_at DESC);
CREATE INDEX idx_pos_devices_stripe_terminal_id ON pos_devices(stripe_terminal_id) WHERE stripe_terminal_id IS NOT NULL;

COMMENT ON TABLE pos_devices IS 'Registered POS devices with offline queue capabilities';
COMMENT ON COLUMN pos_devices.encryption_key_hash IS 'Hash of symmetric key used for encrypting offline payloads';
COMMENT ON COLUMN pos_devices.pairing_code IS 'Temporary code for device registration workflow';

-- ========================================
-- POS Device Key Vault (encrypted keys per version)
-- ========================================

CREATE TABLE pos_device_keys (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES pos_devices(id) ON DELETE CASCADE,
    key_version INT NOT NULL,
    key_ciphertext BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pos_device_keys UNIQUE (device_id, key_version)
);

CREATE INDEX idx_pos_device_keys_device ON pos_device_keys(device_id);

COMMENT ON TABLE pos_device_keys IS 'Encrypted POS device keys stored per version for offline payload decryption';

-- ========================================
-- POS Offline Queue
-- ========================================

CREATE TABLE pos_offline_queue (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    device_id BIGINT NOT NULL REFERENCES pos_devices(id) ON DELETE CASCADE,

    -- Encrypted payload
    encrypted_payload BYTEA NOT NULL,  -- AES-GCM encrypted transaction data
    encryption_key_version INT NOT NULL,
    encryption_iv BYTEA NOT NULL,  -- Initialization vector for AES-GCM

    -- Transaction metadata (unencrypted for observability)
    local_transaction_id VARCHAR(255) NOT NULL,  -- Client-side UUID for deduplication
    staff_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    transaction_timestamp TIMESTAMPTZ NOT NULL,  -- Client local time
    transaction_amount NUMERIC(12, 2),  -- For metrics (not authoritative)

    -- Sync status
    sync_status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',  -- queued, processing, completed, failed
    sync_priority VARCHAR(20) NOT NULL DEFAULT 'HIGH',  -- critical, high, default
    sync_started_at TIMESTAMPTZ,
    sync_completed_at TIMESTAMPTZ,
    sync_attempt_count INT NOT NULL DEFAULT 0,
    last_sync_error TEXT,

    -- Idempotency
    idempotency_key VARCHAR(255) NOT NULL,  -- Format: {tenantId}:{deviceId}:{localTxId}

    -- Audit fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_pos_offline_queue_status CHECK (sync_status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_pos_offline_queue_priority CHECK (sync_priority IN ('CRITICAL', 'HIGH', 'DEFAULT')),
    CONSTRAINT uq_pos_offline_queue_local_tx UNIQUE (device_id, local_transaction_id),
    CONSTRAINT uq_pos_offline_queue_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_pos_offline_queue_tenant_id ON pos_offline_queue(tenant_id);
CREATE INDEX idx_pos_offline_queue_device_id ON pos_offline_queue(device_id);
CREATE INDEX idx_pos_offline_queue_sync_status ON pos_offline_queue(sync_status) WHERE sync_status IN ('QUEUED', 'PROCESSING', 'FAILED');
CREATE INDEX idx_pos_offline_queue_created_at ON pos_offline_queue(created_at DESC);
CREATE INDEX idx_pos_offline_queue_idempotency ON pos_offline_queue(idempotency_key);
CREATE INDEX idx_pos_offline_queue_priority_created ON pos_offline_queue(sync_priority, created_at) WHERE sync_status = 'QUEUED';

COMMENT ON TABLE pos_offline_queue IS 'Encrypted offline POS transactions awaiting sync to server';
COMMENT ON COLUMN pos_offline_queue.encrypted_payload IS 'AES-GCM encrypted JSON containing cart, payment method, customer data';
COMMENT ON COLUMN pos_offline_queue.local_transaction_id IS 'Client-generated UUID prevents duplicate processing';
COMMENT ON COLUMN pos_offline_queue.idempotency_key IS 'Deterministic key for preventing duplicate charges on retry';

-- ========================================
-- POS Offline Transactions (Audit Trail)
-- ========================================

CREATE TABLE pos_offline_transactions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    device_id BIGINT NOT NULL REFERENCES pos_devices(id) ON DELETE CASCADE,
    queue_entry_id BIGINT NOT NULL REFERENCES pos_offline_queue(id) ON DELETE CASCADE,

    -- Original transaction data
    local_transaction_id VARCHAR(255) NOT NULL,
    staff_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    offline_timestamp TIMESTAMPTZ NOT NULL,

    -- Server-side result
    order_id BIGINT,  -- Created order (if applicable)
    payment_intent_id VARCHAR(255),  -- Stripe payment intent ID
    total_amount NUMERIC(12, 2) NOT NULL,

    -- Sync metadata
    synced_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sync_duration_ms INT,

    -- Audit fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_pos_offline_tx_queue_entry UNIQUE (queue_entry_id)
);

CREATE INDEX idx_pos_offline_tx_tenant_id ON pos_offline_transactions(tenant_id);
CREATE INDEX idx_pos_offline_tx_device_id ON pos_offline_transactions(device_id);
CREATE INDEX idx_pos_offline_tx_order_id ON pos_offline_transactions(order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_pos_offline_tx_synced_at ON pos_offline_transactions(synced_at DESC);

COMMENT ON TABLE pos_offline_transactions IS 'Audit log of successfully synced offline POS transactions';

-- ========================================
-- POS Activity Log
-- ========================================

CREATE TABLE pos_activity_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    device_id BIGINT REFERENCES pos_devices(id) ON DELETE CASCADE,

    -- Activity details
    activity_type VARCHAR(50) NOT NULL,  -- login, logout, cash_drawer_open, sync_started, sync_completed, etc.
    staff_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    metadata JSONB DEFAULT '{}',

    -- Timestamp
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_pos_activity_type CHECK (activity_type IN (
        'LOGIN', 'LOGOUT', 'CASH_DRAWER_OPEN', 'CASH_DRAWER_CLOSE',
        'SYNC_STARTED', 'SYNC_COMPLETED', 'SYNC_FAILED',
        'DEVICE_PAIRED', 'DEVICE_SUSPENDED', 'FIRMWARE_UPDATE',
        'OFFLINE_MODE_ENTERED', 'OFFLINE_MODE_EXITED'
    ))
);

CREATE INDEX idx_pos_activity_log_tenant_id ON pos_activity_log(tenant_id);
CREATE INDEX idx_pos_activity_log_device_id ON pos_activity_log(device_id);
CREATE INDEX idx_pos_activity_log_occurred_at ON pos_activity_log(occurred_at DESC);
CREATE INDEX idx_pos_activity_log_activity_type ON pos_activity_log(activity_type);
CREATE INDEX idx_pos_activity_log_staff_user_id ON pos_activity_log(staff_user_id) WHERE staff_user_id IS NOT NULL;

COMMENT ON TABLE pos_activity_log IS 'Audit trail of all POS device activities and state changes';

-- ========================================
-- Metrics Views
-- ========================================

-- Queue depth by device
CREATE OR REPLACE VIEW v_pos_offline_queue_depth AS
SELECT
    d.tenant_id,
    d.id AS device_id,
    d.device_name,
    COUNT(CASE WHEN q.sync_status = 'QUEUED' THEN 1 END) AS queued_count,
    COUNT(CASE WHEN q.sync_status = 'PROCESSING' THEN 1 END) AS processing_count,
    COUNT(CASE WHEN q.sync_status = 'FAILED' THEN 1 END) AS failed_count,
    MIN(CASE WHEN q.sync_status = 'QUEUED' THEN q.created_at END) AS oldest_queued_at,
    MAX(d.last_synced_at) AS last_successful_sync
FROM pos_devices d
LEFT JOIN pos_offline_queue q ON q.device_id = d.id
WHERE d.status = 'active'
GROUP BY d.tenant_id, d.id, d.device_name;

COMMENT ON VIEW v_pos_offline_queue_depth IS 'Real-time queue depth metrics per device for observability';

-- ========================================
-- Cleanup Policy
-- ========================================

-- Delete completed queue entries after 90 days (keep audit trail in pos_offline_transactions)
CREATE INDEX idx_pos_offline_queue_completed_cleanup ON pos_offline_queue(sync_completed_at)
WHERE sync_status = 'completed';

COMMENT ON INDEX idx_pos_offline_queue_completed_cleanup IS 'Supports automated cleanup job for synced transactions';
