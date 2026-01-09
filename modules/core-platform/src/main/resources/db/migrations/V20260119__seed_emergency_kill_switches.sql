-- Emergency Kill Switch Seed Data
--
-- Seeds the 5 CRITICAL emergency kill switches defined in
-- docs/feature_flags/governance.md Emergency Kill Switch Matrix.
--
-- References:
-- - Task I1.T6: Feature flag governance + emergency kill switches
-- - Architecture: docs/feature_flags/governance.md
-- - Data Model: datamodel_erd.puml

-- ============================================================================
-- Emergency Kill Switch Seeding
-- ============================================================================

-- 1. Payments (Stripe) Kill Switch
INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'payments.stripe.enabled', TRUE, '{}', NOW(), NOW(), 'payments@example.com', 'CRITICAL', 30,
    'Master switch for Stripe payment processing',
    'Set enabled=false to disable all payment operations. Orders will fail gracefully with "Payment system unavailable" message. Check FeatureToggle.isPaymentsEnabled() in code.'
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'payments.stripe.enabled');

-- 2. Checkout Order Creation Kill Switch
INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'checkout.order-creation.enabled', TRUE, '{}', NOW(), NOW(), 'platform-team', 'CRITICAL', 30,
    'Emergency kill switch for cart-to-order conversion',
    'Set enabled=false to disable checkout button with maintenance notice. Orders will fail gracefully with "Checkout temporarily unavailable" message.'
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'checkout.order-creation.enabled');

-- 3. Media Upload Kill Switch
INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'media.uploads.enabled', TRUE, '{}', NOW(), NOW(), 'media-team', 'CRITICAL', 30,
    'Emergency kill switch for all media upload operations',
    'Set enabled=false to hide upload UI. Existing media still served but new uploads blocked. Check FeatureToggle.isMediaUploadEnabled() in code.'
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'media.uploads.enabled');

-- 4. Media Processing Kill Switch
INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'media.processing.enabled', TRUE, '{}', NOW(), NOW(), 'media-team', 'CRITICAL', 30,
    'Emergency kill switch for image resize/derivative generation',
    'Set enabled=false to accept uploads but queue processing. No derivatives created. Check FeatureToggle.isMediaProcessingEnabled() in code.'
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'media.processing.enabled');

-- 5. Admin Impersonation Kill Switch
INSERT INTO feature_flags (tenant_id, flag_key, enabled, config, created_at, updated_at, owner, risk_level,
    review_cadence_days, description, rollback_instructions)
SELECT NULL, 'admin.impersonation.enabled', TRUE, '{}', NOW(), NOW(), 'security-team', 'CRITICAL', 30,
    'Emergency kill switch for platform admin impersonation capability',
    'Set enabled=false to hide impersonation UI. Audit log continues tracking all attempts. Check FeatureToggle.isImpersonationEnabled() in code.'
WHERE NOT EXISTS (
    SELECT 1 FROM feature_flags WHERE tenant_id IS NULL AND flag_key = 'admin.impersonation.enabled');

-- ============================================================================
-- Verification Query (for manual testing)
-- ============================================================================
-- Run this query after migration to verify all 5 emergency kill switches exist:
--
-- SELECT flag_key, enabled, risk_level, owner, description
-- FROM feature_flags
-- WHERE tenant_id IS NULL
--   AND risk_level = 'CRITICAL'
--   AND flag_key IN (
--     'payments.stripe.enabled',
--     'checkout.order-creation.enabled',
--     'media.uploads.enabled',
--     'media.processing.enabled',
--     'admin.impersonation.enabled'
--   )
-- ORDER BY flag_key;
--
-- Expected: 5 rows returned, all enabled=true, risk_level=CRITICAL

-- ============================================================================
-- Cache Invalidation Note
-- ============================================================================
-- After manually updating these flags in production, ALWAYS invalidate the cache:
--
-- Via CLI:
--   node tools/featureflag-cli/featureflag.cjs invalidate <flag-id> --reason "Emergency disable"
--
-- Via SQL (use only if CLI unavailable):
--   UPDATE feature_flags SET updated_at = NOW() WHERE flag_key = 'payments.stripe.enabled' AND tenant_id IS NULL;
--   -- Then restart application pods or wait for cache TTL (15 minutes)

-- ============================================================================
-- End of Migration
-- ============================================================================
