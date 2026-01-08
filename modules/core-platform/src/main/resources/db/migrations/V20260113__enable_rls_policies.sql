-- ============================================================================
-- Village Storefront - Row Level Security (RLS) Policies
-- ============================================================================
-- Migration: V20260113__enable_rls_policies.sql
-- Description: Enable PostgreSQL Row Level Security on tenant-scoped tables
--              to enforce data isolation at the database layer
-- References:
--   - ADR-001 Tenancy Strategy (Section 3: Row Level Security)
--   - Architecture: docs/architecture_overview.md Section 3.0 (Rulebook)
--   - Task: I1.T5 (Tenant Access Gateway Prototype)
--
-- IMPORTANT: This migration implements mandatory RLS policies per ADR-001.
-- All tenant-scoped queries MUST set app.tenant_id session variable before
-- accessing protected tables.
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Enable RLS and create policies
-- ============================================================================

-- ----------------------------------------------------------------------------
-- HELPER FUNCTIONS
-- ----------------------------------------------------------------------------
-- Security definer function to safely set tenant context for current session
-- This function is called by application code to establish tenant scope
CREATE OR REPLACE FUNCTION set_current_tenant_id(p_tenant_id UUID)
RETURNS VOID AS $$
BEGIN
    PERFORM set_config('app.tenant_id', p_tenant_id::TEXT, FALSE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION set_current_tenant_id IS 'Set tenant ID for current session (used by TenantContext)';

-- Function to get current tenant ID from session variable
-- Returns NULL if not set (will cause RLS to block all rows)
CREATE OR REPLACE FUNCTION get_current_tenant_id()
RETURNS UUID AS $$
BEGIN
    RETURN NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID;
EXCEPTION
    WHEN OTHERS THEN
        RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION get_current_tenant_id IS 'Get tenant ID from session variable (returns NULL if not set)';

-- ----------------------------------------------------------------------------
-- TENANCY MODULE RLS POLICIES
-- ----------------------------------------------------------------------------

-- Tenants table: Allow access to own tenant record only
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON tenants
    FOR ALL
    USING (id = get_current_tenant_id());

COMMENT ON POLICY tenant_isolation_policy ON tenants IS 'RLS policy: users can only access their own tenant record';

-- Custom domains table: Restrict to current tenant
ALTER TABLE custom_domains ENABLE ROW LEVEL SECURITY;
ALTER TABLE custom_domains FORCE ROW LEVEL SECURITY;

CREATE POLICY custom_domains_isolation_policy ON custom_domains
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY custom_domains_isolation_policy ON custom_domains IS 'RLS policy: restrict custom domains to current tenant';

-- ----------------------------------------------------------------------------
-- IDENTITY MODULE RLS POLICIES
-- ----------------------------------------------------------------------------

-- Users table: Restrict to current tenant
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;

CREATE POLICY users_isolation_policy ON users
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY users_isolation_policy ON users IS 'RLS policy: restrict users to current tenant';

-- Roles table: Restrict to current tenant
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;

CREATE POLICY roles_isolation_policy ON roles
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY roles_isolation_policy ON roles IS 'RLS policy: restrict roles to current tenant';

-- User roles join table: Restrict to current tenant
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;

CREATE POLICY user_roles_isolation_policy ON user_roles
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY user_roles_isolation_policy ON user_roles IS 'RLS policy: restrict user-role assignments to current tenant';

-- API keys table: Restrict to current tenant
ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY api_keys_isolation_policy ON api_keys
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY api_keys_isolation_policy ON api_keys IS 'RLS policy: restrict API keys to current tenant';

-- ----------------------------------------------------------------------------
-- CATALOG MODULE RLS POLICIES
-- ----------------------------------------------------------------------------

-- Categories table: Restrict to current tenant
ALTER TABLE categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE categories FORCE ROW LEVEL SECURITY;

CREATE POLICY categories_isolation_policy ON categories
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY categories_isolation_policy ON categories IS 'RLS policy: restrict categories to current tenant';

-- Products table: Restrict to current tenant
ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;

CREATE POLICY products_isolation_policy ON products
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY products_isolation_policy ON products IS 'RLS policy: restrict products to current tenant';

-- Product variants table: Restrict to current tenant
ALTER TABLE product_variants ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_variants FORCE ROW LEVEL SECURITY;

CREATE POLICY product_variants_isolation_policy ON product_variants
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY product_variants_isolation_policy ON product_variants IS 'RLS policy: restrict product variants to current tenant';

-- Product categories join table: Restrict to current tenant
ALTER TABLE product_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_categories FORCE ROW LEVEL SECURITY;

CREATE POLICY product_categories_isolation_policy ON product_categories
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY product_categories_isolation_policy ON product_categories IS 'RLS policy: restrict product-category links to current tenant';

-- Product images table: Restrict to current tenant
ALTER TABLE product_images ENABLE ROW LEVEL SECURITY;
ALTER TABLE product_images FORCE ROW LEVEL SECURITY;

CREATE POLICY product_images_isolation_policy ON product_images
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY product_images_isolation_policy ON product_images IS 'RLS policy: restrict product images to current tenant';

-- Inventory levels table: Restrict to current tenant
ALTER TABLE inventory_levels ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_levels FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_levels_isolation_policy ON inventory_levels
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY inventory_levels_isolation_policy ON inventory_levels IS 'RLS policy: restrict inventory levels to current tenant';

-- ----------------------------------------------------------------------------
-- CART/ORDER/PAYMENT MODULE RLS POLICIES
-- ----------------------------------------------------------------------------

-- Carts table: Restrict to current tenant
ALTER TABLE carts ENABLE ROW LEVEL SECURITY;
ALTER TABLE carts FORCE ROW LEVEL SECURITY;

CREATE POLICY carts_isolation_policy ON carts
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY carts_isolation_policy ON carts IS 'RLS policy: restrict carts to current tenant';

-- Cart items table: Restrict to current tenant
ALTER TABLE cart_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE cart_items FORCE ROW LEVEL SECURITY;

CREATE POLICY cart_items_isolation_policy ON cart_items
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY cart_items_isolation_policy ON cart_items IS 'RLS policy: restrict cart items to current tenant';

-- Orders table: Restrict to current tenant
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders FORCE ROW LEVEL SECURITY;

CREATE POLICY orders_isolation_policy ON orders
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY orders_isolation_policy ON orders IS 'RLS policy: restrict orders to current tenant';

-- Order line items table: Restrict to current tenant
ALTER TABLE order_line_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_line_items FORCE ROW LEVEL SECURITY;

CREATE POLICY order_line_items_isolation_policy ON order_line_items
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY order_line_items_isolation_policy ON order_line_items IS 'RLS policy: restrict order line items to current tenant';

-- Shipments table: Restrict to current tenant
ALTER TABLE shipments ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipments FORCE ROW LEVEL SECURITY;

CREATE POLICY shipments_isolation_policy ON shipments
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY shipments_isolation_policy ON shipments IS 'RLS policy: restrict shipments to current tenant';

-- Payment methods table: Restrict to current tenant
ALTER TABLE payment_methods ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_methods FORCE ROW LEVEL SECURITY;

CREATE POLICY payment_methods_isolation_policy ON payment_methods
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY payment_methods_isolation_policy ON payment_methods IS 'RLS policy: restrict payment methods to current tenant';

-- Payments table: Restrict to current tenant
ALTER TABLE payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments FORCE ROW LEVEL SECURITY;

CREATE POLICY payments_isolation_policy ON payments
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY payments_isolation_policy ON payments IS 'RLS policy: restrict payments to current tenant';

-- Refunds table: Restrict to current tenant
ALTER TABLE refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE refunds FORCE ROW LEVEL SECURITY;

CREATE POLICY refunds_isolation_policy ON refunds
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY refunds_isolation_policy ON refunds IS 'RLS policy: restrict refunds to current tenant';

-- ----------------------------------------------------------------------------
-- CONSIGNMENT MODULE RLS POLICIES
-- ----------------------------------------------------------------------------

-- Consignors table: Restrict to current tenant
ALTER TABLE consignors ENABLE ROW LEVEL SECURITY;
ALTER TABLE consignors FORCE ROW LEVEL SECURITY;

CREATE POLICY consignors_isolation_policy ON consignors
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY consignors_isolation_policy ON consignors IS 'RLS policy: restrict consignors to current tenant';

-- Consignment items table: Restrict to current tenant
ALTER TABLE consignment_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE consignment_items FORCE ROW LEVEL SECURITY;

CREATE POLICY consignment_items_isolation_policy ON consignment_items
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY consignment_items_isolation_policy ON consignment_items IS 'RLS policy: restrict consignment items to current tenant';

-- Payout batches table: Restrict to current tenant
ALTER TABLE payout_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE payout_batches FORCE ROW LEVEL SECURITY;

CREATE POLICY payout_batches_isolation_policy ON payout_batches
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY payout_batches_isolation_policy ON payout_batches IS 'RLS policy: restrict payout batches to current tenant';

-- Payout line items table: Restrict to current tenant
ALTER TABLE payout_line_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE payout_line_items FORCE ROW LEVEL SECURITY;

CREATE POLICY payout_line_items_isolation_policy ON payout_line_items
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY payout_line_items_isolation_policy ON payout_line_items IS 'RLS policy: restrict payout line items to current tenant';

-- ----------------------------------------------------------------------------
-- LOYALTY MODULE RLS POLICIES
-- ----------------------------------------------------------------------------

-- Loyalty programs table: Restrict to current tenant
ALTER TABLE loyalty_programs ENABLE ROW LEVEL SECURITY;
ALTER TABLE loyalty_programs FORCE ROW LEVEL SECURITY;

CREATE POLICY loyalty_programs_isolation_policy ON loyalty_programs
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY loyalty_programs_isolation_policy ON loyalty_programs IS 'RLS policy: restrict loyalty programs to current tenant';

-- Loyalty members table: Restrict to current tenant
ALTER TABLE loyalty_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE loyalty_members FORCE ROW LEVEL SECURITY;

CREATE POLICY loyalty_members_isolation_policy ON loyalty_members
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY loyalty_members_isolation_policy ON loyalty_members IS 'RLS policy: restrict loyalty members to current tenant';

-- Loyalty transactions table: Restrict to current tenant
ALTER TABLE loyalty_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE loyalty_transactions FORCE ROW LEVEL SECURITY;

CREATE POLICY loyalty_transactions_isolation_policy ON loyalty_transactions
    FOR ALL
    USING (tenant_id = get_current_tenant_id());

COMMENT ON POLICY loyalty_transactions_isolation_policy ON loyalty_transactions IS 'RLS policy: restrict loyalty transactions to current tenant';

-- ============================================================================
-- END MIGRATION UP
-- ============================================================================

-- +migrate Down
-- ============================================================================
-- MIGRATION DOWN: Disable RLS and drop policies
-- ============================================================================

-- ----------------------------------------------------------------------------
-- LOYALTY MODULE - Remove RLS
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS loyalty_transactions_isolation_policy ON loyalty_transactions;
ALTER TABLE loyalty_transactions DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS loyalty_members_isolation_policy ON loyalty_members;
ALTER TABLE loyalty_members DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS loyalty_programs_isolation_policy ON loyalty_programs;
ALTER TABLE loyalty_programs DISABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- CONSIGNMENT MODULE - Remove RLS
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS payout_line_items_isolation_policy ON payout_line_items;
ALTER TABLE payout_line_items DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payout_batches_isolation_policy ON payout_batches;
ALTER TABLE payout_batches DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS consignment_items_isolation_policy ON consignment_items;
ALTER TABLE consignment_items DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS consignors_isolation_policy ON consignors;
ALTER TABLE consignors DISABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- CART/ORDER/PAYMENT MODULE - Remove RLS
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS refunds_isolation_policy ON refunds;
ALTER TABLE refunds DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payments_isolation_policy ON payments;
ALTER TABLE payments DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS payment_methods_isolation_policy ON payment_methods;
ALTER TABLE payment_methods DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS shipments_isolation_policy ON shipments;
ALTER TABLE shipments DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS order_line_items_isolation_policy ON order_line_items;
ALTER TABLE order_line_items DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS orders_isolation_policy ON orders;
ALTER TABLE orders DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS cart_items_isolation_policy ON cart_items;
ALTER TABLE cart_items DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS carts_isolation_policy ON carts;
ALTER TABLE carts DISABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- CATALOG MODULE - Remove RLS
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS inventory_levels_isolation_policy ON inventory_levels;
ALTER TABLE inventory_levels DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS product_images_isolation_policy ON product_images;
ALTER TABLE product_images DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS product_categories_isolation_policy ON product_categories;
ALTER TABLE product_categories DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS product_variants_isolation_policy ON product_variants;
ALTER TABLE product_variants DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS products_isolation_policy ON products;
ALTER TABLE products DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS categories_isolation_policy ON categories;
ALTER TABLE categories DISABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- IDENTITY MODULE - Remove RLS
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS api_keys_isolation_policy ON api_keys;
ALTER TABLE api_keys DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS user_roles_isolation_policy ON user_roles;
ALTER TABLE user_roles DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS roles_isolation_policy ON roles;
ALTER TABLE roles DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS users_isolation_policy ON users;
ALTER TABLE users DISABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- TENANCY MODULE - Remove RLS
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS custom_domains_isolation_policy ON custom_domains;
ALTER TABLE custom_domains DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_policy ON tenants;
ALTER TABLE tenants DISABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- HELPER FUNCTIONS - Drop
-- ----------------------------------------------------------------------------
DROP FUNCTION IF EXISTS get_current_tenant_id();
DROP FUNCTION IF EXISTS set_current_tenant_id(UUID);

-- ============================================================================
-- END MIGRATION DOWN
-- ============================================================================
