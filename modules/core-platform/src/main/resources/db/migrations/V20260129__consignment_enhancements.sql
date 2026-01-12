-- ============================================================================
-- Village Storefront - Consignment Domain Enhancements
-- ============================================================================
-- Migration: V20260129__consignment_enhancements.sql
-- Description: Add intake batches, commission schedules, encryption support
-- Task: I4.T1 - Consignment domain completion
-- References:
--   - Architecture: §3.2.6 Consignment Module
--   - ERD: Consignor, ConsignmentItem, IntakeBatch, CommissionSchedule
--   - Clarification 4: Batch intake + commission structures
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Add intake batches, commission schedules, and encryption
-- ============================================================================

-- Enable pgcrypto extension for encrypted fields
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ----------------------------------------------------------------------------
-- Intake Batches Table
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS intake_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    consignor_id UUID NOT NULL REFERENCES consignors(id) ON DELETE CASCADE,
    batch_name VARCHAR(255) NOT NULL,
    notes TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, completed, cancelled
    total_items INTEGER NOT NULL DEFAULT 0,
    processed_items INTEGER NOT NULL DEFAULT 0,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_intake_batches_status CHECK (status IN ('pending', 'completed', 'cancelled'))
);

CREATE INDEX idx_intake_batches_tenant_id ON intake_batches(tenant_id);
CREATE INDEX idx_intake_batches_consignor_id ON intake_batches(consignor_id);
CREATE INDEX idx_intake_batches_status ON intake_batches(tenant_id, status);
CREATE INDEX idx_intake_batches_created_at ON intake_batches(created_at DESC);

COMMENT ON TABLE intake_batches IS 'Batch intake tracking for consignment items received from vendors';
COMMENT ON COLUMN intake_batches.batch_name IS 'Descriptive name for the intake batch';
COMMENT ON COLUMN intake_batches.total_items IS 'Total items expected in batch';
COMMENT ON COLUMN intake_batches.processed_items IS 'Successfully processed items count';

-- ----------------------------------------------------------------------------
-- Commission Schedules Table
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS commission_schedules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    consignor_id UUID NOT NULL REFERENCES consignors(id) ON DELETE CASCADE,
    category_id UUID,  -- nullable for default/consignor-wide schedules
    commission_rate NUMERIC(5,2) NOT NULL,  -- percentage (e.g., 15.00 for 15%)
    effective_from DATE NOT NULL,
    effective_until DATE,  -- nullable for no expiration
    priority INTEGER NOT NULL DEFAULT 0,  -- higher priority wins in conflicts
    notes VARCHAR(500),
    metadata JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, expired, superseded
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255),
    CONSTRAINT chk_commission_schedules_rate CHECK (commission_rate >= 0 AND commission_rate <= 100),
    CONSTRAINT chk_commission_schedules_status CHECK (status IN ('active', 'expired', 'superseded')),
    CONSTRAINT chk_commission_schedules_dates CHECK (effective_until IS NULL OR effective_until >= effective_from)
);

CREATE INDEX idx_commission_schedules_tenant_id ON commission_schedules(tenant_id);
CREATE INDEX idx_commission_schedules_consignor_id ON commission_schedules(consignor_id);
CREATE INDEX idx_commission_schedules_category_id ON commission_schedules(category_id) WHERE category_id IS NOT NULL;
CREATE INDEX idx_commission_schedules_effective ON commission_schedules(consignor_id, effective_from, effective_until, status);
CREATE INDEX idx_commission_schedules_priority ON commission_schedules(consignor_id, priority DESC);

COMMENT ON TABLE commission_schedules IS 'Commission rate schedules for consignors, supporting category-specific and time-bound rates';
COMMENT ON COLUMN commission_schedules.category_id IS 'Category ID for category-specific rates; NULL for consignor-wide default';
COMMENT ON COLUMN commission_schedules.priority IS 'Conflict resolution priority; higher values win';
COMMENT ON COLUMN commission_schedules.metadata IS 'Additional metadata for reporting and auditing';

-- ----------------------------------------------------------------------------
-- Enhance Consignment Items Table
-- ----------------------------------------------------------------------------
-- Add cost_basis and intake_batch_id columns to existing consignment_items table
ALTER TABLE consignment_items
    ADD COLUMN IF NOT EXISTS cost_basis NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS intake_batch_id UUID REFERENCES intake_batches(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS variant_id UUID REFERENCES product_variants(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_consignment_items_intake_batch_id ON consignment_items(intake_batch_id) WHERE intake_batch_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_consignment_items_variant_id ON consignment_items(variant_id) WHERE variant_id IS NOT NULL;

COMMENT ON COLUMN consignment_items.cost_basis IS 'Cost basis for consignor (what they paid for the item)';
COMMENT ON COLUMN consignment_items.intake_batch_id IS 'Reference to intake batch if item was received via batch intake';
COMMENT ON COLUMN consignment_items.variant_id IS 'Variant identifier linked to this consignment item for inventory adjustments';

-- ----------------------------------------------------------------------------
-- Enhance Consignors Table with Encrypted Tax Fields
-- ----------------------------------------------------------------------------
-- Add encrypted tax fields to consignors table
ALTER TABLE consignors
    ADD COLUMN IF NOT EXISTS tax_id_encrypted BYTEA,  -- encrypted SSN/EIN
    ADD COLUMN IF NOT EXISTS tax_id_key_version VARCHAR(20) DEFAULT 'v1',
    ADD COLUMN IF NOT EXISTS tax_id_last_rotated TIMESTAMPTZ;

COMMENT ON COLUMN consignors.tax_id_encrypted IS 'Encrypted tax identifier (SSN/EIN) using pgcrypto pgp_sym_encrypt';
COMMENT ON COLUMN consignors.tax_id_key_version IS 'Encryption key version for key rotation tracking';
COMMENT ON COLUMN consignors.tax_id_last_rotated IS 'Last key rotation timestamp for compliance auditing';

-- ----------------------------------------------------------------------------
-- Consignment Data Retention Hooks
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS consignment_retention_hooks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    resource_type VARCHAR(50) NOT NULL,
    resource_id UUID NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'scheduled', -- scheduled|archived|purged
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_consignment_retention_tenant ON consignment_retention_hooks(tenant_id, retain_until);
CREATE INDEX idx_consignment_retention_resource ON consignment_retention_hooks(resource_type, resource_id);

COMMENT ON TABLE consignment_retention_hooks IS 'Tracking table for consignment-specific data retention hooks (batches, payouts, tax docs)';
COMMENT ON COLUMN consignment_retention_hooks.retain_until IS 'Timestamp when the resource is eligible for archival/purge per retention policy';

-- ----------------------------------------------------------------------------
-- Row-Level Security Policies for New Tables
-- ----------------------------------------------------------------------------
-- Enable RLS on new tables
ALTER TABLE intake_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE commission_schedules ENABLE ROW LEVEL SECURITY;
ALTER TABLE consignment_retention_hooks ENABLE ROW LEVEL SECURITY;

-- Intake batches RLS policy
CREATE POLICY intake_batches_tenant_isolation ON intake_batches
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- Commission schedules RLS policy
CREATE POLICY commission_schedules_tenant_isolation ON commission_schedules
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- Retention hooks RLS policy
CREATE POLICY consignment_retention_tenant_isolation ON consignment_retention_hooks
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ----------------------------------------------------------------------------
-- Audit Logging for Commission Schedule Changes
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS commission_schedule_audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    schedule_id UUID NOT NULL,
    change_type VARCHAR(20) NOT NULL,  -- CREATED, UPDATED, EXPIRED, SUPERSEDED
    old_values JSONB,
    new_values JSONB,
    changed_by VARCHAR(255),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reason VARCHAR(500)
);

CREATE INDEX idx_commission_audit_schedule_id ON commission_schedule_audit_log(schedule_id);
CREATE INDEX idx_commission_audit_tenant_id ON commission_schedule_audit_log(tenant_id, changed_at DESC);

COMMENT ON TABLE commission_schedule_audit_log IS 'Audit trail for commission schedule changes, supporting compliance and reconciliation';

-- Trigger function for audit logging
CREATE OR REPLACE FUNCTION audit_commission_schedule_changes()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO commission_schedule_audit_log (tenant_id, schedule_id, change_type, new_values, changed_by)
        VALUES (NEW.tenant_id, NEW.id, 'CREATED', to_jsonb(NEW), NEW.created_by);
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO commission_schedule_audit_log (tenant_id, schedule_id, change_type, old_values, new_values, changed_by)
        VALUES (NEW.tenant_id, NEW.id, 'UPDATED', to_jsonb(OLD), to_jsonb(NEW), NEW.created_by);
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO commission_schedule_audit_log (tenant_id, schedule_id, change_type, old_values)
        VALUES (OLD.tenant_id, OLD.id, 'DELETED', to_jsonb(OLD));
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Attach trigger to commission_schedules table
CREATE TRIGGER commission_schedule_audit_trigger
    AFTER INSERT OR UPDATE OR DELETE ON commission_schedules
    FOR EACH ROW EXECUTE FUNCTION audit_commission_schedule_changes();

-- ----------------------------------------------------------------------------
-- Helper Functions for Encryption
-- ----------------------------------------------------------------------------
-- Function to encrypt tax ID with key version tracking
CREATE OR REPLACE FUNCTION encrypt_tax_id(plain_text TEXT, key_version TEXT DEFAULT 'v1')
RETURNS BYTEA AS $$
DECLARE
    encryption_key TEXT;
BEGIN
    -- In production, fetch key from KMS or environment-specific vault
    encryption_key := current_setting('app.pg_crypto_key', TRUE);
    IF encryption_key IS NULL OR encryption_key = '' THEN
        encryption_key := 'INSECURE_DEV_KEY_DO_NOT_USE_IN_PROD';
    END IF;

    -- Derive tenant-specific key (simplified for demo)
    encryption_key := encryption_key || '-' || key_version;

    RETURN pgp_sym_encrypt(plain_text, encryption_key);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to decrypt tax ID
CREATE OR REPLACE FUNCTION decrypt_tax_id(encrypted_data BYTEA, key_version TEXT DEFAULT 'v1')
RETURNS TEXT AS $$
DECLARE
    encryption_key TEXT;
BEGIN
    encryption_key := current_setting('app.pg_crypto_key', TRUE);
    IF encryption_key IS NULL OR encryption_key = '' THEN
        encryption_key := 'INSECURE_DEV_KEY_DO_NOT_USE_IN_PROD';
    END IF;

    encryption_key := encryption_key || '-' || key_version;

    RETURN pgp_sym_decrypt(encrypted_data, encryption_key);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION encrypt_tax_id IS 'Encrypt sensitive tax identifier with key version tracking';
COMMENT ON FUNCTION decrypt_tax_id IS 'Decrypt tax identifier using specified key version';

-- ----------------------------------------------------------------------------
-- Sample Data Retention Hooks
-- ----------------------------------------------------------------------------
-- Partition intake_batches by created_at for efficient archival
-- Note: In production, implement time-based partitioning for batches older than 2 years

-- Create archive table for completed batches (7-year retention per Clarification 4)
CREATE TABLE IF NOT EXISTS intake_batches_archive (
    LIKE intake_batches INCLUDING ALL
);

COMMENT ON TABLE intake_batches_archive IS 'Archive table for completed intake batches retained for 7 years per compliance requirements';

-- +migrate Down
-- ============================================================================
-- MIGRATION DOWN: Rollback consignment enhancements
-- ============================================================================

-- Drop audit trigger and function
DROP TRIGGER IF EXISTS commission_schedule_audit_trigger ON commission_schedules;
DROP FUNCTION IF EXISTS audit_commission_schedule_changes();

-- Drop helper functions
DROP FUNCTION IF EXISTS encrypt_tax_id(TEXT, TEXT);
DROP FUNCTION IF EXISTS decrypt_tax_id(BYTEA, TEXT);

-- Drop archive table
DROP TABLE IF EXISTS intake_batches_archive;

-- Drop audit log table
DROP TABLE IF EXISTS commission_schedule_audit_log;

-- Remove RLS policies
DROP POLICY IF EXISTS intake_batches_tenant_isolation ON intake_batches;
DROP POLICY IF EXISTS commission_schedules_tenant_isolation ON commission_schedules;
DROP POLICY IF EXISTS consignment_retention_tenant_isolation ON consignment_retention_hooks;

-- Remove encryption columns from consignors
ALTER TABLE consignors
    DROP COLUMN IF EXISTS tax_id_encrypted,
    DROP COLUMN IF EXISTS tax_id_key_version,
    DROP COLUMN IF EXISTS tax_id_last_rotated;

-- Remove new columns from consignment_items
ALTER TABLE consignment_items
    DROP COLUMN IF EXISTS cost_basis,
    DROP COLUMN IF EXISTS intake_batch_id,
    DROP COLUMN IF EXISTS variant_id;

-- Drop commission schedules table
DROP TABLE IF EXISTS commission_schedules;

-- Drop intake batches table
DROP TABLE IF EXISTS intake_batches;

-- Drop retention hooks table
DROP TABLE IF EXISTS consignment_retention_hooks;
