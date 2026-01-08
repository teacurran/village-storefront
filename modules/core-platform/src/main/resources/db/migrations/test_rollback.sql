-- ============================================================================
-- Village Storefront - Migration Rollback Test Script
-- ============================================================================
-- Script: test_rollback.sql
-- Description: Dry run test of migration rollback for catalog/inventory tables
-- Usage:
--   psql -U storefront -d storefront_test -f test_rollback.sql
--
-- This script creates a test transaction, applies Down migrations,
-- verifies tables are dropped, then rolls back the transaction.
-- ============================================================================

\echo '============================================================'
\echo 'Village Storefront - Rollback Dry Run Test'
\echo '============================================================'
\echo ''

-- Start transaction for dry run
BEGIN;

\echo 'Starting transaction (will be rolled back at end)...'
\echo ''

-- ----------------------------------------------------------------------------
-- Pre-rollback: Verify tables exist
-- ----------------------------------------------------------------------------
\echo '1. Checking tables before rollback...'
SELECT
    table_name,
    CASE
        WHEN table_name IN (
            'collections', 'product_collections', 'inventory_locations',
            'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
        ) THEN 'EXISTS'
        ELSE 'NOT FOUND'
    END AS status
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
    'collections', 'product_collections', 'inventory_locations',
    'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
  )
ORDER BY table_name;

\echo ''

-- ----------------------------------------------------------------------------
-- Pre-rollback: Check RLS policies
-- ----------------------------------------------------------------------------
\echo '2. Checking RLS policies before rollback...'
SELECT
    tablename,
    policyname
FROM pg_policies
WHERE schemaname = 'public'
  AND tablename IN (
    'collections', 'product_collections', 'inventory_locations',
    'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
  )
ORDER BY tablename;

\echo ''

-- ----------------------------------------------------------------------------
-- Execute Down migrations
-- ----------------------------------------------------------------------------
\echo '3. Executing Down migrations...'
\echo ''

\echo '   a) Dropping RLS policies (V20260115 Down)...'

-- Inventory transfer lines
DROP POLICY IF EXISTS inventory_transfer_lines_isolation_policy ON inventory_transfer_lines;
ALTER TABLE inventory_transfer_lines DISABLE ROW LEVEL SECURITY;

-- Inventory transfers
DROP POLICY IF EXISTS inventory_transfers_isolation_policy ON inventory_transfers;
ALTER TABLE inventory_transfers DISABLE ROW LEVEL SECURITY;

-- Inventory adjustments
DROP POLICY IF EXISTS inventory_adjustments_isolation_policy ON inventory_adjustments;
ALTER TABLE inventory_adjustments DISABLE ROW LEVEL SECURITY;

-- Inventory locations
DROP POLICY IF EXISTS inventory_locations_isolation_policy ON inventory_locations;
ALTER TABLE inventory_locations DISABLE ROW LEVEL SECURITY;

-- Product collections join table
DROP POLICY IF EXISTS product_collections_isolation_policy ON product_collections;
ALTER TABLE product_collections DISABLE ROW LEVEL SECURITY;

-- Collections
DROP POLICY IF EXISTS collections_isolation_policy ON collections;
ALTER TABLE collections DISABLE ROW LEVEL SECURITY;

\echo '   RLS policies dropped successfully ✓'
\echo ''

\echo '   b) Dropping tables (V20260114 Down)...'

-- Inventory module extensions
DROP TABLE IF EXISTS inventory_transfer_lines CASCADE;
DROP TABLE IF EXISTS inventory_transfers CASCADE;
DROP TABLE IF EXISTS inventory_adjustments_default;
DROP TABLE IF EXISTS inventory_adjustments_2026_03;
DROP TABLE IF EXISTS inventory_adjustments_2026_02;
DROP TABLE IF EXISTS inventory_adjustments_2026_01;
DROP TABLE IF EXISTS inventory_adjustments CASCADE;
DROP TABLE IF EXISTS inventory_locations CASCADE;

-- Catalog module extensions
DROP TABLE IF EXISTS product_collections CASCADE;
DROP TABLE IF EXISTS collections CASCADE;

\echo '   Tables dropped successfully ✓'
\echo ''

\echo '   c) Reverting inventory_levels extensions...'
ALTER TABLE inventory_levels DROP COLUMN IF EXISTS version;
\echo '   Inventory level version column removed ✓'
\echo ''

-- ----------------------------------------------------------------------------
-- Post-rollback: Verify tables are dropped
-- ----------------------------------------------------------------------------
\echo '4. Verifying tables after rollback...'
SELECT
    COALESCE(
        (SELECT COUNT(*)
         FROM information_schema.tables
         WHERE table_schema = 'public'
           AND table_name IN (
            'collections', 'product_collections', 'inventory_locations',
            'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
           )
        ), 0
    ) AS remaining_tables;

\echo ''
\echo 'Expected: remaining_tables = 0'
\echo ''

-- ----------------------------------------------------------------------------
-- Post-rollback: Verify RLS policies are dropped
-- ----------------------------------------------------------------------------
\echo '5. Verifying RLS policies after rollback...'
SELECT
    COALESCE(
        (SELECT COUNT(*)
         FROM pg_policies
         WHERE schemaname = 'public'
           AND tablename IN (
            'collections', 'product_collections', 'inventory_locations',
            'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
           )
        ), 0
    ) AS remaining_policies;

\echo ''
\echo 'Expected: remaining_policies = 0'
\echo ''

-- ----------------------------------------------------------------------------
-- Rollback transaction
-- ----------------------------------------------------------------------------
\echo '============================================================'
\echo 'Rolling back transaction (dry run complete)...'
ROLLBACK;

\echo 'Transaction rolled back successfully ✓'
\echo ''
\echo 'Dry run complete. All tables and policies restored.'
\echo '============================================================'
