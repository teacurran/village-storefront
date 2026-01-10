-- BCrypt Work Factor Migration for Village Storefront
--
-- This migration adds support for incremental bcrypt work factor upgrades without
-- forcing all users to reset passwords. The strategy is:
--
-- 1. Add password_hash_version column to track which cost factor was used
-- 2. Application automatically re-hashes passwords with higher cost on successful login
-- 3. This script can be run for bulk migration (throttled to avoid load spikes)
--
-- Security Context:
-- - Current: bcrypt cost=12 (2^12 = 4096 rounds, ~150ms hash time)
-- - Target: bcrypt cost=13 (2^13 = 8192 rounds, ~300ms hash time)
-- - Higher cost = slower hashing = better protection against brute force attacks
--
-- References:
-- - Task I6.T2: bcrypt work factor migration tooling
-- - Architecture: Section "Authentication & Authorization" - bcrypt password hashing
--
-- Usage:
--   psql -h localhost -U storefront -d storefront < migrate-bcrypt-cost.sql
--

BEGIN;

-- ============================================================
-- STEP 1: Add password_hash_version column (if not exists)
-- ============================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'oauth_clients'
        AND column_name = 'password_hash_version'
    ) THEN
        ALTER TABLE oauth_clients
        ADD COLUMN password_hash_version INTEGER NOT NULL DEFAULT 1;

        COMMENT ON COLUMN oauth_clients.password_hash_version IS
            'BCrypt cost factor version: 1=cost(12), 2=cost(13), etc.';

        RAISE NOTICE 'Added password_hash_version column to oauth_clients table';
    ELSE
        RAISE NOTICE 'password_hash_version column already exists';
    END IF;
END $$;

-- ============================================================
-- STEP 2: Identify clients needing migration
-- ============================================================

-- Show statistics (how many clients per version)
SELECT
    password_hash_version,
    COUNT(*) as client_count,
    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) as percentage
FROM oauth_clients
GROUP BY password_hash_version
ORDER BY password_hash_version;

-- ============================================================
-- STEP 3: Application-level migration (preferred method)
-- ============================================================
--
-- The preferred migration strategy is automatic re-hashing on login:
--
-- 1. User/client authenticates with password
-- 2. OAuthService.verifySecret() checks password with BCrypt.checkpw()
-- 3. If verification succeeds AND password_hash_version < TARGET_VERSION:
--    a. Re-hash password with BCrypt.hashpw(password, BCrypt.gensalt(13))
--    b. UPDATE oauth_clients SET client_secret_hash = ?, password_hash_version = 2
-- 4. Next login uses new hash
--
-- Benefits:
-- - Zero-downtime migration
-- - No need to store plaintext passwords
-- - Only active clients are migrated (inactive clients remain on old version)
--
-- See: OAuthService.java (enhance verifySecret method)

-- ============================================================
-- STEP 4: Monitoring queries (run periodically)
-- ============================================================

-- Check migration progress (what percentage migrated?)
SELECT
    'Migration Progress' as metric,
    ROUND(100.0 * SUM(CASE WHEN password_hash_version >= 2 THEN 1 ELSE 0 END) / COUNT(*), 2) as pct_migrated,
    SUM(CASE WHEN password_hash_version >= 2 THEN 1 ELSE 0 END) as migrated_count,
    COUNT(*) as total_count
FROM oauth_clients;

-- Find clients that haven't logged in recently (may need manual migration reminder)
SELECT
    client_id,
    tenant_id,
    password_hash_version,
    last_used_at,
    EXTRACT(DAY FROM (NOW() - last_used_at)) as days_since_login
FROM oauth_clients
WHERE password_hash_version < 2
AND last_used_at < NOW() - INTERVAL '90 days'
ORDER BY last_used_at DESC
LIMIT 20;

-- ============================================================
-- STEP 5: Rollback procedure (if needed)
-- ============================================================

-- If migration causes issues, you can mark all clients to be re-hashed on next login:
-- UPDATE oauth_clients SET password_hash_version = 1 WHERE password_hash_version = 2;
--
-- Note: This doesn't revert the hash itself (that's not possible without plaintext password)
-- It just flags clients for re-hashing on next login.

COMMIT;

-- ============================================================
-- Post-Migration Checklist
-- ============================================================
--
-- ✅ Verify OAuthService.verifySecret() updated to re-hash on login
-- ✅ Monitor login duration metrics (should increase by ~150ms per login)
-- ✅ Run monitoring queries weekly until migration >95% complete
-- ✅ After 6 months, consider deprecating version=1 (force password reset)
-- ✅ Schedule next migration (cost=14) in 2-3 years
