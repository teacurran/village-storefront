-- ============================================================================
-- Village Storefront - Platform Admin Tables
-- ============================================================================
-- Migration: V20260111__platform_admin_tables.sql
-- Description: Platform admin console tables for governance, impersonation, and audit
-- References:
--   - Task I5.T2: Platform admin console
--   - ADR-004: Consignment payouts (platform_commands pattern)
--   - Architecture: 04_Operational_Architecture.md Section 3.8
--   - Blueprint: 01_Blueprint_Foundation.md Section 4.0 & 5.0
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Create platform admin tables
-- ============================================================================

-- ----------------------------------------------------------------------------
-- PLATFORM COMMANDS (Audit Log for Platform Operations)
-- ----------------------------------------------------------------------------
-- Immutable audit trail for all platform-level administrative actions
-- including store management, impersonation, configuration changes, etc.
--
-- This table is NOT tenant-scoped (tenant_id nullable) since platform admins
-- operate across all tenants. Actions targeting specific tenants record the
-- target_tenant_id in the metadata JSONB.

CREATE TABLE platform_commands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type VARCHAR(50) NOT NULL, -- 'platform_admin', 'system', 'automation'
    actor_id UUID,                   -- Platform admin user ID (nullable for system actions)
    actor_email VARCHAR(255),        -- Denormalized for audit trail stability
    action VARCHAR(100) NOT NULL,    -- 'impersonate_start', 'impersonate_stop', 'suspend_store', etc.
    target_type VARCHAR(50),         -- 'tenant', 'user', 'feature_flag', etc.
    target_id UUID,                  -- ID of the entity being acted upon
    reason TEXT,                     -- Required for impersonation and sensitive operations
    ticket_number VARCHAR(100),      -- External ticket reference (Jira, Zendesk, etc.)
    impersonation_context JSONB,     -- { session_id, target_user_id, target_tenant_id }
    metadata JSONB,                  -- Additional context specific to the action
    ip_address INET,                 -- Source IP for security auditing
    user_agent TEXT,                 -- Browser/client info
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT chk_actor_type CHECK (actor_type IN ('platform_admin', 'system', 'automation')),
    CONSTRAINT chk_impersonation_requires_reason CHECK (
        action NOT LIKE 'impersonate_%' OR (reason IS NOT NULL AND length(trim(reason)) >= 10)
    ),
    CONSTRAINT chk_impersonation_requires_ticket CHECK (
        action NOT LIKE 'impersonate_%' OR (ticket_number IS NOT NULL AND length(trim(ticket_number)) >= 3)
    )
);

CREATE INDEX idx_platform_commands_occurred_at ON platform_commands (occurred_at DESC);
CREATE INDEX idx_platform_commands_actor_id ON platform_commands (actor_id) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_platform_commands_action ON platform_commands (action);
CREATE INDEX idx_platform_commands_target ON platform_commands (target_type, target_id) WHERE target_id IS NOT NULL;
CREATE INDEX idx_platform_commands_ticket ON platform_commands (ticket_number) WHERE ticket_number IS NOT NULL;
CREATE INDEX idx_platform_commands_impersonation ON platform_commands USING GIN (impersonation_context) WHERE impersonation_context IS NOT NULL;

COMMENT ON TABLE platform_commands IS 'Immutable audit log for platform-level administrative actions (cross-tenant scope)';
COMMENT ON COLUMN platform_commands.actor_type IS 'Type of entity performing the action: platform_admin, system, automation';
COMMENT ON COLUMN platform_commands.reason IS 'Human-readable justification, required for impersonation (min 10 chars)';
COMMENT ON COLUMN platform_commands.ticket_number IS 'External support ticket reference for traceability';
COMMENT ON COLUMN platform_commands.impersonation_context IS 'JSON: { session_id, target_user_id, target_tenant_id, started_at }';
COMMENT ON COLUMN platform_commands.metadata IS 'Action-specific context (store suspension reason, feature flag changes, etc.)';

-- ----------------------------------------------------------------------------
-- IMPERSONATION SESSIONS
-- ----------------------------------------------------------------------------
-- Tracks active and historical impersonation sessions for platform admins.
-- Enables quick lookup of current impersonations and correlation with audit logs.

CREATE TABLE impersonation_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    platform_admin_id UUID NOT NULL,        -- Platform admin performing impersonation
    platform_admin_email VARCHAR(255) NOT NULL,
    target_tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    target_user_id UUID,                     -- User being impersonated (nullable = tenant admin mode)
    target_user_email VARCHAR(255),
    reason TEXT NOT NULL,
    ticket_number VARCHAR(100) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,                    -- NULL = session still active
    start_command_id UUID REFERENCES platform_commands(id),
    end_command_id UUID REFERENCES platform_commands(id),
    ip_address INET NOT NULL,
    user_agent TEXT,

    -- Constraints
    CONSTRAINT chk_session_end_after_start CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT chk_reason_length CHECK (length(trim(reason)) >= 10)
);

CREATE INDEX idx_impersonation_sessions_active ON impersonation_sessions (platform_admin_id, started_at DESC) WHERE ended_at IS NULL;
CREATE INDEX idx_impersonation_sessions_target_tenant ON impersonation_sessions (target_tenant_id, started_at DESC);
CREATE INDEX idx_impersonation_sessions_target_user ON impersonation_sessions (target_user_id) WHERE target_user_id IS NOT NULL;
CREATE INDEX idx_impersonation_sessions_ticket ON impersonation_sessions (ticket_number) WHERE ticket_number IS NOT NULL;

COMMENT ON TABLE impersonation_sessions IS 'Tracks platform admin impersonation sessions for security and audit';
COMMENT ON COLUMN impersonation_sessions.target_user_id IS 'Nullable: NULL means impersonating as tenant admin';
COMMENT ON COLUMN impersonation_sessions.ended_at IS 'NULL indicates active session';

-- ----------------------------------------------------------------------------
-- PLATFORM ADMIN ROLES
-- ----------------------------------------------------------------------------
-- RBAC for platform administrators. Scoped above tenant-level.
-- Note: Tenant-level user roles are stored in users.metadata JSONB.

CREATE TABLE platform_admin_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,     -- Platform admin email (not linked to tenant users)
    role VARCHAR(50) NOT NULL,              -- 'super_admin', 'support', 'ops', 'read_only'
    permissions JSONB NOT NULL DEFAULT '[]', -- Array of permission strings
    mfa_enforced BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(20) NOT NULL DEFAULT 'active', -- 'active', 'suspended', 'deleted'
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,                        -- Platform admin who created this role

    -- Constraints
    CONSTRAINT chk_role_valid CHECK (role IN ('super_admin', 'support', 'ops', 'read_only'))
);

CREATE INDEX idx_platform_admin_roles_status ON platform_admin_roles (status) WHERE status = 'active';

COMMENT ON TABLE platform_admin_roles IS 'RBAC for platform administrators (cross-tenant permissions)';
COMMENT ON COLUMN platform_admin_roles.permissions IS 'JSON array of permission strings: ["impersonate", "suspend_tenant", "view_audit"]';
COMMENT ON COLUMN platform_admin_roles.mfa_enforced IS 'Whether MFA is required for this admin (default: true)';

-- ----------------------------------------------------------------------------
-- SYSTEM HEALTH SNAPSHOTS
-- ----------------------------------------------------------------------------
-- Periodic snapshots of system health metrics for historical trending.
-- Complements real-time Prometheus metrics with persisted data points.

CREATE TABLE system_health_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_count INTEGER NOT NULL,
    active_tenant_count INTEGER NOT NULL,
    total_users INTEGER NOT NULL,
    active_sessions INTEGER NOT NULL,
    job_queue_depth INTEGER NOT NULL,
    failed_jobs_24h INTEGER NOT NULL,
    avg_response_time_ms DECIMAL(10,2),
    p95_response_time_ms DECIMAL(10,2),
    error_rate_percent DECIMAL(5,2),
    disk_usage_percent DECIMAL(5,2),
    db_connection_count INTEGER,
    metrics JSONB,                           -- Additional Prometheus metrics snapshot

    CONSTRAINT chk_snapshot_percentages CHECK (
        (error_rate_percent IS NULL OR error_rate_percent >= 0 AND error_rate_percent <= 100) AND
        (disk_usage_percent IS NULL OR disk_usage_percent >= 0 AND disk_usage_percent <= 100)
    )
);

CREATE INDEX idx_health_snapshots_time ON system_health_snapshots (snapshot_at DESC);

COMMENT ON TABLE system_health_snapshots IS 'Historical system health metrics for platform monitoring dashboards';
COMMENT ON COLUMN system_health_snapshots.metrics IS 'Additional metrics from Prometheus as JSON snapshot';

-- ============================================================================
-- MIGRATION DOWN: Drop all platform admin tables
-- ============================================================================

-- +migrate Down
DROP TABLE IF EXISTS system_health_snapshots CASCADE;
DROP TABLE IF EXISTS platform_admin_roles CASCADE;
DROP TABLE IF EXISTS impersonation_sessions CASCADE;
DROP TABLE IF EXISTS platform_commands CASCADE;
