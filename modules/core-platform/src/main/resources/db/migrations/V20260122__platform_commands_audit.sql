-- Platform Commands Audit Table
--
-- Tracks all platform-level administrative operations (tenant suspension/resume,
-- domain cleanup, emergency interventions) for compliance and incident investigation.
--
-- References:
--   - Task: I6.T5 - Data Ops + DR Automation
--   - Architecture: 04_Operational_Architecture.md (Section 3.5 - Data Operations)

CREATE TABLE platform_commands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id),
    action VARCHAR(100) NOT NULL,
    reason TEXT NOT NULL,
    ticket_number VARCHAR(100),
    actor_id VARCHAR(255) NOT NULL,  -- 'platform-ops' or specific admin user ID
    metadata JSONB DEFAULT '{}',
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_platform_commands_action CHECK (action IN (
        'suspend_tenant',
        'resume_tenant',
        'delete_tenant',
        'force_logout',
        'domain_cleanup',
        'feature_flag_override',
        'emergency_kill_switch',
        'data_export',
        'impersonate_start',
        'impersonate_end'
    ))
);

-- Index for tenant-specific audit queries
CREATE INDEX idx_platform_commands_tenant ON platform_commands(tenant_id, occurred_at DESC);

-- Index for action-based filtering
CREATE INDEX idx_platform_commands_action ON platform_commands(action, occurred_at DESC);

-- Index for ticket correlation
CREATE INDEX idx_platform_commands_ticket ON platform_commands(ticket_number) WHERE ticket_number IS NOT NULL;

-- Index for actor accountability
CREATE INDEX idx_platform_commands_actor ON platform_commands(actor_id, occurred_at DESC);

COMMENT ON TABLE platform_commands IS 'Immutable audit log for platform administrative operations';
COMMENT ON COLUMN platform_commands.action IS 'Type of platform operation performed';
COMMENT ON COLUMN platform_commands.reason IS 'Business justification for operation';
COMMENT ON COLUMN platform_commands.ticket_number IS 'Support ticket or incident number (if applicable)';
COMMENT ON COLUMN platform_commands.actor_id IS 'User or system that executed the command';
COMMENT ON COLUMN platform_commands.metadata IS 'Additional context (previous values, affected resources, etc.)';
