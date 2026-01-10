-- Media Pipeline Tables
-- Task I4.T3: Media Processing Pipeline with Upload Negotiation and FFmpeg
-- Adds tables for media assets, derivatives, quotas, and signed URL tracking

-- ============================================================================
-- Media Assets Table
-- ============================================================================
-- Stores original uploaded media files with metadata
CREATE TABLE media_assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    asset_type VARCHAR(20) NOT NULL,                    -- 'image', 'video'
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,                  -- R2/S3 object key with tenant prefix
    mime_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64),                        -- SHA-256 checksum for integrity
    width INTEGER,                                      -- For images and videos
    height INTEGER,                                     -- For images and videos
    duration_seconds INTEGER,                           -- For videos only
    status VARCHAR(20) NOT NULL DEFAULT 'uploading',    -- 'uploading', 'pending', 'processing', 'ready', 'failed'
    processing_error TEXT,                              -- Error message if status = 'failed'
    metadata JSONB DEFAULT '{}',                        -- Additional metadata (EXIF, codec info, etc.)
    uploaded_by UUID,                                   -- User who uploaded (nullable)
    download_attempts INTEGER NOT NULL DEFAULT 0,       -- Track downloads for digital products
    signature_version INTEGER NOT NULL DEFAULT 1,       -- Used to invalidate previously signed URLs
    max_download_attempts INTEGER,                      -- Null = unlimited
    original_usage_tracked BOOLEAN NOT NULL DEFAULT FALSE, -- Tracks if original bytes counted toward quota
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_media_assets_tenant_id ON media_assets(tenant_id);
CREATE INDEX idx_media_assets_storage_key ON media_assets(storage_key);
CREATE INDEX idx_media_assets_status ON media_assets(status);
CREATE INDEX idx_media_assets_asset_type ON media_assets(asset_type);
CREATE INDEX idx_media_assets_created_at ON media_assets(created_at);
CREATE INDEX idx_media_assets_uploaded_by ON media_assets(uploaded_by);

COMMENT ON TABLE media_assets IS 'Original uploaded media files with processing status tracking';
COMMENT ON COLUMN media_assets.storage_key IS 'Object storage key with tenant prefix for multi-tenant isolation';
COMMENT ON COLUMN media_assets.status IS 'uploading: negotiation in progress; pending: uploaded but not processed; processing: derivatives being generated; ready: derivatives available; failed: processing error';
COMMENT ON COLUMN media_assets.download_attempts IS 'Tracks download count for digital products (Foundation §6.0 requirement: max 5 attempts)';
COMMENT ON COLUMN media_assets.original_usage_tracked IS 'Prevents double-counting original uploads when retries occur';

-- ============================================================================
-- Media Derivatives Table
-- ============================================================================
-- Stores processed variants of media assets (thumbnails, HLS segments, etc.)
CREATE TABLE media_derivatives (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    derivative_type VARCHAR(50) NOT NULL,               -- 'thumbnail', 'small', 'medium', 'large', 'hls_master', 'hls_720p', etc.
    storage_key VARCHAR(500) NOT NULL,                  -- R2/S3 object key
    mime_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    width INTEGER,                                      -- For images and video frames
    height INTEGER,                                     -- For images and video frames
    duration_seconds INTEGER,                           -- For video segments
    content_hash VARCHAR(64),                           -- Hash for cache busting per architecture §1.4
    metadata JSONB DEFAULT '{}',                        -- Derivative-specific metadata
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_media_derivatives_tenant_id ON media_derivatives(tenant_id);
CREATE INDEX idx_media_derivatives_asset_id ON media_derivatives(asset_id);
CREATE INDEX idx_media_derivatives_derivative_type ON media_derivatives(derivative_type);
CREATE INDEX idx_media_derivatives_storage_key ON media_derivatives(storage_key);
CREATE INDEX idx_media_derivatives_created_at ON media_derivatives(created_at);

COMMENT ON TABLE media_derivatives IS 'Processed media variants (thumbnails, transcoded video, HLS segments)';
COMMENT ON COLUMN media_derivatives.derivative_type IS 'Image: thumbnail, small, medium, large; Video: hls_master, hls_720p, hls_480p, hls_360p, poster';
COMMENT ON COLUMN media_derivatives.content_hash IS 'Hash for CDN cache-busting via hashed keys (Architecture §1.4)';

-- ============================================================================
-- Media Quotas Table
-- ============================================================================
-- Tracks storage quotas and usage per tenant
CREATE TABLE media_quotas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    quota_bytes BIGINT NOT NULL DEFAULT 10737418240,    -- 10GB default
    used_bytes BIGINT NOT NULL DEFAULT 0,
    warn_threshold DECIMAL(3,2) NOT NULL DEFAULT 0.80,  -- Warn at 80% usage
    enforce_quota BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT,                                         -- Admin notes
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_media_quotas_tenant_id ON media_quotas(tenant_id);
CREATE INDEX idx_media_quotas_used_bytes ON media_quotas(used_bytes);

COMMENT ON TABLE media_quotas IS 'Storage quota tracking per tenant';
COMMENT ON COLUMN media_quotas.quota_bytes IS 'Maximum storage in bytes (default 10GB)';
COMMENT ON COLUMN media_quotas.used_bytes IS 'Current usage in bytes (includes originals + derivatives)';
COMMENT ON COLUMN media_quotas.enforce_quota IS 'If false, quota is advisory only (for premium tenants)';

-- ============================================================================
-- Signed URL Access Log Table
-- ============================================================================
-- Tracks signed URL generation and access for digital product downloads
CREATE TABLE media_access_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    derivative_id UUID REFERENCES media_derivatives(id) ON DELETE CASCADE,
    signature_version INTEGER NOT NULL,                 -- Increment to invalidate old URLs
    expires_at TIMESTAMPTZ NOT NULL,                    -- URL expiry (default 24h per Architecture §1.4)
    accessed_at TIMESTAMPTZ,                            -- Null if never accessed
    access_ip INET,                                     -- IP address of accessor
    user_agent TEXT,                                    -- User agent string
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_media_access_logs_tenant_id ON media_access_logs(tenant_id);
CREATE INDEX idx_media_access_logs_asset_id ON media_access_logs(asset_id);
CREATE INDEX idx_media_access_logs_expires_at ON media_access_logs(expires_at);
CREATE INDEX idx_media_access_logs_created_at ON media_access_logs(created_at);

COMMENT ON TABLE media_access_logs IS 'Audit trail for signed URL generation and access (Foundation §6.0 requirement)';
COMMENT ON COLUMN media_access_logs.signature_version IS 'Signature version for URL invalidation per Architecture §1.4';
COMMENT ON COLUMN media_access_logs.expires_at IS '24-hour expiry per Foundation §6.0 and Architecture §1.4';

-- ============================================================================
-- Permissions (Row Level Security)
-- ============================================================================
-- Enable RLS on all media tables for multi-tenancy

ALTER TABLE media_assets ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_derivatives ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_quotas ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_access_logs ENABLE ROW LEVEL SECURITY;

-- Policies will be defined per environment based on tenant isolation strategy
-- Example policy (adjust based on actual RLS implementation):
-- CREATE POLICY media_assets_tenant_isolation ON media_assets
--     USING (tenant_id = current_setting('app.current_tenant')::UUID);

-- ============================================================================
-- Sample Data for Development
-- ============================================================================
-- Initialize quota records for existing tenants

INSERT INTO media_quotas (
    tenant_id,
    quota_bytes,
    used_bytes,
    warn_threshold,
    enforce_quota,
    notes,
    created_at,
    updated_at
)
SELECT
    t.id,
    10737418240,                                -- 10GB default
    0,                                          -- No usage initially
    0.80,                                       -- Warn at 80%
    TRUE,                                       -- Enforce quota
    'Default media quota for tenant',
    NOW(),
    NOW()
FROM tenants t
WHERE NOT EXISTS (
    SELECT 1 FROM media_quotas mq WHERE mq.tenant_id = t.id
);

-- ============================================================================
-- End of Migration
-- ============================================================================
