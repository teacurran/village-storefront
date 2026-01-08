-- ============================================================================
-- Village Storefront - Compliance Privacy Tables
-- ============================================================================
-- Migration: V20260112__compliance_privacy_tables.sql
-- Description: Privacy request + consent tracking tables for GDPR/CCPA automation
-- References:
--   - Task I5.T6: Automate compliance workflows
--   - Blueprint Section 5: Data Governance Notes
--   - Operational Architecture Section 3.15: Compliance personas
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- PRIVACY REQUESTS (export/delete workflows)
-- ============================================================================

CREATE TABLE privacy_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    request_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    requester_email VARCHAR(255) NOT NULL,
    subject_email VARCHAR(255),
    subject_identifier_hash VARCHAR(64),
    reason TEXT,
    ticket_number VARCHAR(100),
    parameters JSONB,
    approval_notes TEXT,
    approved_by_email VARCHAR(255),
    approved_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    result_url TEXT,
    error_message TEXT,
    platform_command_id UUID REFERENCES platform_commands(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_privacy_requests_tenant_status ON privacy_requests (tenant_id, status);
CREATE INDEX idx_privacy_requests_tenant_type ON privacy_requests (tenant_id, request_type);
CREATE INDEX idx_privacy_requests_created_at ON privacy_requests (tenant_id, created_at DESC);

COMMENT ON TABLE privacy_requests IS 'Privacy export/delete workflow requests with approval + audit metadata';
COMMENT ON COLUMN privacy_requests.subject_identifier_hash IS 'SHA-256 hash of the subject identifier stored for job payloads';

-- ============================================================================
-- MARKETING CONSENTS (per-channel opt-in/out timeline)
-- ============================================================================

CREATE TABLE marketing_consents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel VARCHAR(50) NOT NULL,
    consented BOOLEAN NOT NULL,
    consent_source VARCHAR(100) NOT NULL,
    consent_method VARCHAR(50),
    ip_address VARCHAR(45),
    user_agent TEXT,
    notes TEXT,
    consented_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_marketing_consents_user_channel ON marketing_consents (tenant_id, user_id, channel, consented_at DESC);
CREATE INDEX idx_marketing_consents_channel ON marketing_consents (tenant_id, channel, consented_at DESC);

COMMENT ON TABLE marketing_consents IS 'Immutable timeline of marketing consent changes per user/channel';

-- ============================================================================
-- PRIVACY DELETIONS (retention + purge scheduling metadata)
-- ============================================================================

CREATE TABLE privacy_deletions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    privacy_request_id UUID NOT NULL UNIQUE REFERENCES privacy_requests(id) ON DELETE CASCADE,
    subject_identifier_hash VARCHAR(64) NOT NULL,
    soft_deleted_at TIMESTAMPTZ,
    purge_after TIMESTAMPTZ NOT NULL,
    purge_job_id UUID,
    purge_job_enqueued_at TIMESTAMPTZ,
    purged_at TIMESTAMPTZ,
    status VARCHAR(50) NOT NULL
);

CREATE INDEX idx_privacy_deletions_purge_after ON privacy_deletions (purge_after);
CREATE INDEX idx_privacy_deletions_status ON privacy_deletions (status);

COMMENT ON TABLE privacy_deletions IS 'Tracks soft-delete + purge lifecycle per privacy request (retention SLA)';

-- +migrate Down
DROP TABLE IF EXISTS privacy_deletions CASCADE;
DROP TABLE IF EXISTS marketing_consents CASCADE;
DROP TABLE IF EXISTS privacy_requests CASCADE;
