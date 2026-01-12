-- ============================================================================
-- Village Storefront - Custom Domain SSL Automation
-- ============================================================================
-- Migration: V20260132__custom_domain_ssl_automation.sql
-- Description: Add status tracking, DNS challenge storage, and certificate
--              metadata for automated domain verification and SSL provisioning
-- References:
--   - Task I5.T4: Custom Domain SSL Automation
--   - Architecture: docs/architecture/tenant_isolation.md Section 1
--   - Runbook: docs/operations/runbook.md (Custom Domain SSL Renewal)
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Extend custom_domains table for SSL automation workflow
-- ============================================================================

-- Add status enum for domain verification lifecycle
CREATE TYPE domain_status AS ENUM ('PENDING', 'ACTIVE', 'FAILED');

ALTER TABLE custom_domains
    ADD COLUMN status domain_status NOT NULL DEFAULT 'PENDING',
    ADD COLUMN dns_challenge JSONB,
    ADD COLUMN last_verification_attempt TIMESTAMPTZ,
    ADD COLUMN verification_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN verification_error_message TEXT,
    ADD COLUMN certificate_expiry TIMESTAMPTZ;

-- Create index for efficient job queries (fetch PENDING or retry-eligible FAILED domains)
CREATE INDEX idx_custom_domains_status ON custom_domains(status);
CREATE INDEX idx_custom_domains_verification_attempt ON custom_domains(last_verification_attempt)
    WHERE status IN ('PENDING', 'FAILED');

-- Create index for certificate expiry warnings (< 30 days)
CREATE INDEX idx_custom_domains_certificate_expiry ON custom_domains(certificate_expiry)
    WHERE certificate_expiry IS NOT NULL AND status = 'ACTIVE';

COMMENT ON COLUMN custom_domains.status IS 'Domain verification lifecycle: PENDING (awaiting verification), ACTIVE (verified + cert issued), FAILED (verification failed)';
COMMENT ON COLUMN custom_domains.dns_challenge IS 'JSONB payload containing DNS TXT record requirements (e.g., {"type": "TXT", "name": "_acme-challenge", "value": "token123"})';
COMMENT ON COLUMN custom_domains.last_verification_attempt IS 'Timestamp of most recent verification attempt (used for exponential backoff)';
COMMENT ON COLUMN custom_domains.verification_retry_count IS 'Number of failed verification attempts (implements backoff: 1h, 4h, 12h, 24h)';
COMMENT ON COLUMN custom_domains.verification_error_message IS 'Human-readable error message displayed in UI (e.g., "DNS TXT record not found")';
COMMENT ON COLUMN custom_domains.certificate_expiry IS 'Expiration timestamp from cert-manager Certificate resource (triggers renewal warnings)';

-- Update existing domains to PENDING status (will be verified by DomainValidationJob)
UPDATE custom_domains
SET status = CASE
    WHEN verified = TRUE THEN 'ACTIVE'::domain_status
    ELSE 'PENDING'::domain_status
END
WHERE status = 'PENDING';

-- +migrate Down
-- ============================================================================
-- MIGRATION DOWN: Remove SSL automation fields
-- ============================================================================

DROP INDEX IF EXISTS idx_custom_domains_certificate_expiry;
DROP INDEX IF EXISTS idx_custom_domains_verification_attempt;
DROP INDEX IF EXISTS idx_custom_domains_status;

ALTER TABLE custom_domains
    DROP COLUMN IF EXISTS certificate_expiry,
    DROP COLUMN IF EXISTS verification_error_message,
    DROP COLUMN IF EXISTS verification_retry_count,
    DROP COLUMN IF EXISTS last_verification_attempt,
    DROP COLUMN IF EXISTS dns_challenge,
    DROP COLUMN IF EXISTS status;

DROP TYPE IF EXISTS domain_status;
