-- ============================================================================
-- Village Storefront - RLS Policy Verification Script
-- ============================================================================
-- Script: verify_rls_policies.sql
-- Description: Verify that all tenant-scoped tables have RLS policies enabled
--              and that policies reference current_setting('app.tenant_id')
-- Usage:
--   psql -U storefront -d storefront_dev -f verify_rls_policies.sql
-- ============================================================================

\echo '============================================================'
\echo 'Village Storefront - RLS Policy Verification'
\echo '============================================================'
\echo ''

-- ----------------------------------------------------------------------------
-- Check 1: Verify helper functions exist
-- ----------------------------------------------------------------------------
\echo 'Check 1: Verifying RLS helper functions...'
SELECT
    proname AS function_name,
    pg_get_function_identity_arguments(oid) AS arguments
FROM pg_proc
WHERE proname IN ('get_current_tenant_id', 'set_current_tenant_id')
ORDER BY proname;

\echo ''

-- ----------------------------------------------------------------------------
-- Check 2: List all tenant-scoped tables with RLS status
-- ----------------------------------------------------------------------------
\echo 'Check 2: Verifying RLS is enabled on tenant-scoped tables...'
SELECT
    schemaname,
    tablename,
    rowsecurity AS rls_enabled,
    CASE
        WHEN rowsecurity THEN 'ENABLED ✓'
        ELSE 'MISSING ✗'
    END AS status
FROM pg_tables
WHERE schemaname = 'public'
  AND tablename IN (
    -- Tenancy module
    'tenants', 'custom_domains',
    -- Identity module
    'users', 'roles', 'user_roles', 'api_keys',
    -- Catalog module
    'categories', 'products', 'product_variants', 'product_categories',
    'product_images', 'collections', 'product_collections',
    -- Inventory module
    'inventory_levels', 'inventory_locations', 'inventory_adjustments',
    'inventory_transfers', 'inventory_transfer_lines',
    -- Cart/Order/Payment module
    'carts', 'cart_items', 'orders', 'order_line_items', 'shipments',
    'payment_methods', 'payments', 'refunds',
    -- Consignment module
    'consignors', 'consignment_items', 'payout_batches', 'payout_line_items',
    -- Loyalty module
    'loyalty_programs', 'loyalty_members', 'loyalty_transactions'
  )
ORDER BY tablename;

\echo ''

-- ----------------------------------------------------------------------------
-- Check 3: Verify policies reference get_current_tenant_id()
-- ----------------------------------------------------------------------------
\echo 'Check 3: Verifying policies reference current_setting(''app.tenant_id'')...'
SELECT
    schemaname,
    tablename,
    policyname,
    CASE
        WHEN pg_get_expr(polqual, polrelid) LIKE '%app.tenant_id%' THEN 'VALID ✓'
        ELSE 'INVALID ✗'
    END AS policy_check,
    pg_get_expr(polqual, polrelid) AS policy_definition
FROM pg_policies
WHERE schemaname = 'public'
  AND tablename IN (
    -- Catalog/Inventory tables (new in V20260114/V20260115)
    'collections', 'product_collections', 'inventory_locations',
    'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
  )
ORDER BY tablename;

\echo ''

-- ----------------------------------------------------------------------------
-- Check 4: Count policies per table
-- ----------------------------------------------------------------------------
\echo 'Check 4: Policy count per tenant-scoped table...'
SELECT
    tablename,
    COUNT(*) AS policy_count,
    string_agg(policyname, ', ') AS policy_names
FROM pg_policies
WHERE schemaname = 'public'
  AND tablename IN (
    'collections', 'product_collections', 'inventory_locations',
    'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
  )
GROUP BY tablename
ORDER BY tablename;

\echo ''

-- ----------------------------------------------------------------------------
-- Check 5: Verify tenant_id columns exist
-- ----------------------------------------------------------------------------
\echo 'Check 5: Verifying tenant_id columns exist on RLS tables...'
SELECT
    c.table_name,
    c.column_name,
    c.data_type,
    CASE
        WHEN c.column_name = 'tenant_id' AND c.data_type = 'uuid' THEN 'VALID ✓'
        ELSE 'MISSING ✗'
    END AS status
FROM information_schema.columns c
WHERE c.table_schema = 'public'
  AND c.table_name IN (
    'collections', 'product_collections', 'inventory_locations',
    'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
  )
  AND c.column_name = 'tenant_id'
ORDER BY c.table_name;

\echo ''

-- ----------------------------------------------------------------------------
-- Check 6: Verify foreign key constraints to tenants table
-- ----------------------------------------------------------------------------
\echo 'Check 6: Verifying tenant_id foreign keys...'
SELECT
    tc.table_name,
    tc.constraint_name,
    kcu.column_name,
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name,
    rc.delete_rule
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu
    ON tc.constraint_name = kcu.constraint_name
    AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage AS ccu
    ON ccu.constraint_name = tc.constraint_name
    AND ccu.table_schema = tc.table_schema
JOIN information_schema.referential_constraints AS rc
    ON tc.constraint_name = rc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_schema = 'public'
  AND tc.table_name IN (
    'collections', 'product_collections', 'inventory_locations',
    'inventory_adjustments', 'inventory_transfers', 'inventory_transfer_lines'
  )
  AND kcu.column_name = 'tenant_id'
ORDER BY tc.table_name;

\echo ''

-- ----------------------------------------------------------------------------
-- Summary
-- ----------------------------------------------------------------------------
\echo '============================================================'
\echo 'Verification Summary'
\echo '============================================================'
\echo ''
\echo 'If all checks show ENABLED/VALID status, RLS is properly configured.'
\echo 'Any MISSING/INVALID entries require investigation.'
\echo ''
\echo 'Expected Results:'
\echo '  - Check 1: 2 functions (get_current_tenant_id, set_current_tenant_id)'
\echo '  - Check 2: All tables show "ENABLED ✓"'
\echo '  - Check 3: Policies show "VALID ✓" with current_setting(''app.tenant_id'')'
\echo '  - Check 4: Each table has exactly 1 policy'
\echo '  - Check 5: All tables show "VALID ✓" for tenant_id UUID column'
\echo '  - Check 6: All tenant_id FKs point to tenants(id) with CASCADE delete'
\echo ''
