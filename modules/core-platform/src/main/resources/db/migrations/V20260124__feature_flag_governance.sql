-- Feature Flag Governance Metadata
--
-- Adds governance tracking fields to feature_flags table to prevent "flag debt"
-- per Blueprint Foundation Section 3 (Feature Flag Strategy) and
-- Rationale Section 4.1.12 (Feature Flag Discipline).
--
-- References:
-- - Task I5.T7: Feature flag governance + release process
-- - Architecture: 05_Rationale_and_Future.md Section 4.1.12
-- - Data Model: datamodel_erd.puml

-- Add governance columns to feature_flags table
ALTER TABLE feature_flags
    ADD COLUMN owner VARCHAR(255),
    ADD COLUMN risk_level VARCHAR(20) DEFAULT 'LOW' NOT NULL,
    ADD COLUMN review_cadence_days INTEGER DEFAULT 90,
    ADD COLUMN expiry_date TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_reviewed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN description TEXT,
    ADD COLUMN rollback_instructions TEXT;

-- Add index for expiry_date to support stale flag detection queries
CREATE INDEX idx_feature_flags_expiry ON feature_flags(expiry_date) WHERE expiry_date IS NOT NULL;

-- Add index for last_reviewed_at to support review backlog queries
CREATE INDEX idx_feature_flags_review ON feature_flags(last_reviewed_at, review_cadence_days) WHERE review_cadence_days IS NOT NULL;

-- Add check constraint for risk_level
ALTER TABLE feature_flags
    ADD CONSTRAINT chk_feature_flags_risk_level
    CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));

-- Update existing flags with default governance values
UPDATE feature_flags
SET
    owner = 'platform-team',
    risk_level = 'LOW',
    review_cadence_days = 90,
    last_reviewed_at = updated_at,
    description = 'Legacy flag - requires governance review'
WHERE owner IS NULL;

-- Make owner required for future flags
ALTER TABLE feature_flags
    ALTER COLUMN owner SET NOT NULL;

-- Comment on columns for documentation
COMMENT ON COLUMN feature_flags.owner IS 'Email or team identifier responsible for this flag lifecycle';
COMMENT ON COLUMN feature_flags.risk_level IS 'Impact level: LOW, MEDIUM, HIGH, CRITICAL (affects emergency kill switch priority)';
COMMENT ON COLUMN feature_flags.review_cadence_days IS 'How often this flag should be reviewed for removal (null = never expires)';
COMMENT ON COLUMN feature_flags.expiry_date IS 'Target date to remove this flag from codebase';
COMMENT ON COLUMN feature_flags.last_reviewed_at IS 'Last time owner confirmed flag is still needed';
COMMENT ON COLUMN feature_flags.description IS 'What this flag controls and why it exists';
COMMENT ON COLUMN feature_flags.rollback_instructions IS 'Steps to safely disable this flag if problems occur';
