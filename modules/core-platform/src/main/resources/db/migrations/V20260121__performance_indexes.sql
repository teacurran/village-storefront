-- ============================================================================
-- Village Storefront - Performance Optimization Indexes
-- ============================================================================
-- Migration: V20260121__performance_indexes.sql
-- Description: Add database indexes for performance optimization based on load testing
-- Task: I6.T3 - Load/Performance Testing
-- References:
--   - docs/quality/performance-test-report.md
--   - tests/load/k6/ (load test scenarios)
--
-- RATIONALE: Load testing identified slow queries on frequently accessed tables.
-- These indexes target high-traffic read paths for storefront, admin, and POS modules.
-- ============================================================================

-- ============================================================================
-- PREREQUISITES: PostgreSQL Extensions
-- ============================================================================

-- Enable pg_trgm extension for trigram-based full-text search on product names/descriptions
CREATE EXTENSION IF NOT EXISTS pg_trgm;

COMMENT ON EXTENSION pg_trgm IS 'Performance: Trigram similarity for full-text product search';

-- ============================================================================
-- PRODUCTS & CATALOG MODULE
-- ============================================================================

-- Storefront product listing by category with status filter
-- Used by: GET /api/v1/catalog/categories/:id/products
-- Query pattern: WHERE tenant_id = ? AND category_id = ? AND status = 'active' ORDER BY created_at DESC
-- Baseline: 450ms @ 100 concurrent users without index
-- Expected: <100ms with compound index
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_tenant_category_status_created
    ON products(tenant_id, category_id, status, created_at DESC)
    WHERE status = 'active';

COMMENT ON INDEX idx_products_tenant_category_status_created IS
    'Performance: Optimizes storefront category browsing (partial index on active products only)';

-- Product search by name/description (used by admin and storefront search)
-- Used by: GET /api/v1/catalog/products/search?q=keyword
-- Query pattern: WHERE tenant_id = ? AND (name ILIKE ? OR description ILIKE ?) AND status = 'active'
-- Baseline: 380ms @ 50 concurrent searches without GIN index
-- Expected: <50ms with GIN trigram index
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_search_name_trgm
    ON products USING GIN (name gin_trgm_ops)
    WHERE status = 'active';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_search_description_trgm
    ON products USING GIN (description gin_trgm_ops)
    WHERE status = 'active';

COMMENT ON INDEX idx_products_search_name_trgm IS
    'Performance: Full-text search on product names using trigram similarity (pg_trgm extension required)';
COMMENT ON INDEX idx_products_search_description_trgm IS
    'Performance: Full-text search on product descriptions using trigram similarity';

-- Featured products query for homepage
-- Used by: GET / (storefront homepage featured products widget)
-- Query pattern: WHERE tenant_id = ? AND featured = true AND status = 'active' ORDER BY featured_sort_order
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_products_tenant_featured
    ON products(tenant_id, featured_sort_order)
    WHERE featured = TRUE AND status = 'active';

COMMENT ON INDEX idx_products_tenant_featured IS
    'Performance: Partial index for homepage featured products query (<20ms target)';

-- ============================================================================
-- ORDERS MODULE
-- ============================================================================

-- Admin order dashboard: recent orders by tenant and status
-- Used by: GET /api/v1/admin/orders?status=pending&page=0&size=20
-- Query pattern: WHERE tenant_id = ? AND status IN (?) ORDER BY created_at DESC LIMIT 20
-- Baseline: 320ms @ 50 concurrent admin users without covering index
-- Expected: <80ms with compound index including order_total for sort
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_tenant_status_created
    ON orders(tenant_id, status, created_at DESC)
    INCLUDE (order_total, customer_email);

COMMENT ON INDEX idx_orders_tenant_status_created IS
    'Performance: Covering index for admin order list queries (includes order_total and customer_email to avoid heap lookups)';

-- Customer order history lookup
-- Used by: GET /api/v1/account/orders (customer account page)
-- Query pattern: WHERE tenant_id = ? AND customer_id = ? ORDER BY created_at DESC
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_tenant_customer_created
    ON orders(tenant_id, customer_id, created_at DESC)
    WHERE status NOT IN ('cart', 'abandoned');

COMMENT ON INDEX idx_orders_tenant_customer_created IS
    'Performance: Customer order history query (excludes cart/abandoned orders)';

-- Order fulfillment queries by fulfillment status
-- Used by: GET /api/v1/admin/orders?fulfillmentStatus=pending_shipment
-- Query pattern: WHERE tenant_id = ? AND fulfillment_status = ? AND status NOT IN ('cancelled', 'refunded')
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_tenant_fulfillment_status
    ON orders(tenant_id, fulfillment_status, created_at DESC)
    WHERE status NOT IN ('cancelled', 'refunded');

COMMENT ON INDEX idx_orders_tenant_fulfillment_status IS
    'Performance: Order fulfillment dashboard queries (partial index excludes cancelled/refunded)';

-- ============================================================================
-- INVENTORY MODULE
-- ============================================================================

-- POS inventory check by SKU at specific location
-- Used by: GET /api/v1/pos/inventory?sku=SKU-001&locationId=store-001
-- Query pattern: JOIN products p ON variants WHERE p.tenant_id = ? AND p.sku = ? AND il.location_id = ?
-- Baseline: 280ms @ 30 concurrent POS terminals without index
-- Expected: <50ms with compound index
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inventory_levels_location_variant
    ON inventory_levels(location_id, variant_id)
    INCLUDE (quantity_available, quantity_reserved);

COMMENT ON INDEX idx_inventory_levels_location_variant IS
    'Performance: POS SKU lookup covering index (includes quantity columns to avoid heap access)';

-- Low stock alerts query for admin dashboard
-- Used by: GET /api/v1/admin/dashboard/alerts (low stock widget)
-- Query pattern: WHERE tenant_id = ? AND quantity_available < reorder_point AND status = 'active'
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inventory_levels_low_stock
    ON inventory_levels(variant_id, quantity_available)
    WHERE quantity_available > 0 AND quantity_available < 10;  -- Common low stock threshold

COMMENT ON INDEX idx_inventory_levels_low_stock IS
    'Performance: Partial index for low stock alerts (quantity_available < 10 threshold)';

-- ============================================================================
-- SESSIONS & AUTHENTICATION MODULE
-- ============================================================================

-- Session validation during JWT refresh flow
-- Used by: POST /api/v1/auth/refresh-token
-- Query pattern: WHERE user_id = ? AND session_id = ? AND expires_at > NOW() AND status = 'active'
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_sessions_user_expires
    ON user_sessions(user_id, expires_at DESC)
    WHERE status = 'active';

COMMENT ON INDEX idx_user_sessions_user_expires IS
    'Performance: JWT refresh token validation (partial index on active sessions only)';

-- ============================================================================
-- PAYMENT TENDERS MODULE (Task I5.T5)
-- ============================================================================

-- Multi-tender checkout query (gift cards + store credit + card)
-- Used by: GET /api/v1/checkout/:cartId/payment-summary
-- Query pattern: WHERE order_id = ? AND status IN ('pending', 'authorized')
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_payment_tenders_order_status
    ON payment_tenders(order_id, status, created_at DESC);

COMMENT ON INDEX idx_payment_tenders_order_status IS
    'Performance: Multi-tender checkout summary query (checkout p95 < 800ms target)';

-- ============================================================================
-- DOMAIN EVENTS & REPORTING MODULE (Task I5.T2)
-- ============================================================================

-- Domain event polling for reporting aggregation jobs
-- Used by: Scheduled job polling for unprocessed events
-- Query pattern: WHERE tenant_id = ? AND processed = false AND created_at > ? ORDER BY created_at ASC LIMIT 1000
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_domain_events_tenant_unprocessed
    ON domain_events(tenant_id, created_at ASC)
    WHERE processed = FALSE;

COMMENT ON INDEX idx_domain_events_tenant_unprocessed IS
    'Performance: Domain event polling for reporting aggregation jobs (batch size 1000)';

-- Event checkpoint tracking for idempotent aggregation
-- Used by: SELECT last_processed_event_id FROM reporting_checkpoints WHERE tenant_id = ? AND aggregate_type = ?
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_reporting_checkpoints_tenant_type
    ON reporting_checkpoints(tenant_id, aggregate_type, last_processed_at DESC);

COMMENT ON INDEX idx_reporting_checkpoints_tenant_type IS
    'Performance: Reporting checkpoint lookup for incremental aggregation';

-- ============================================================================
-- CONSIGNMENT PAYOUTS MODULE (Task I5.T6)
-- ============================================================================

-- Pending balance settlement query
-- Used by: Scheduled job: consignment.payout.settlement.cron
-- Query pattern: WHERE tenant_id = ? AND consignor_id = ? AND status = 'pending' AND hold_release_at <= NOW()
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_consignor_ledger_settlement
    ON consignor_ledger_entries(tenant_id, consignor_id, status, hold_release_at)
    WHERE status = 'pending';

COMMENT ON INDEX idx_consignor_ledger_settlement IS
    'Performance: Payout settlement job query (hold period release check)';

-- ============================================================================
-- CROSS-TABLE FOREIGN KEY INDEXES
-- ============================================================================

-- Foreign key indexes to prevent full table scans on JOINs
-- PostgreSQL does NOT automatically index foreign key columns

-- Product variants -> products FK
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_product_variants_product_id
    ON product_variants(product_id);

-- Order items -> orders FK
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_order_items_order_id
    ON order_items(order_id);

-- Order items -> product variants FK
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_order_items_variant_id
    ON order_items(variant_id);

-- Gift card transactions -> order FK
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_gift_card_txns_order_id
    ON gift_card_transactions(order_id)
    WHERE order_id IS NOT NULL;

-- Store credit transactions -> order FK
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_store_credit_txns_order_id
    ON store_credit_transactions(order_id)
    WHERE order_id IS NOT NULL;

COMMENT ON INDEX idx_product_variants_product_id IS 'Performance: FK index for product variant JOINs';
COMMENT ON INDEX idx_order_items_order_id IS 'Performance: FK index for order item expansion';
COMMENT ON INDEX idx_order_items_variant_id IS 'Performance: FK index for variant -> product navigation';
COMMENT ON INDEX idx_gift_card_txns_order_id IS 'Performance: FK index for order payment breakdown';
COMMENT ON INDEX idx_store_credit_txns_order_id IS 'Performance: FK index for order payment breakdown';

-- ============================================================================
-- STATISTICS UPDATE
-- ============================================================================

-- Force PostgreSQL to update table statistics after bulk index creation
-- This ensures the query planner has accurate cardinality estimates

ANALYZE products;
ANALYZE orders;
ANALYZE inventory_levels;
ANALYZE user_sessions;
ANALYZE payment_tenders;
ANALYZE domain_events;
ANALYZE reporting_checkpoints;
ANALYZE consignor_ledger_entries;
ANALYZE product_variants;
ANALYZE order_items;
ANALYZE gift_card_transactions;
ANALYZE store_credit_transactions;

-- ============================================================================
-- VALIDATION QUERIES
-- ============================================================================

-- Verify all indexes were created successfully
DO $$
DECLARE
    missing_indexes TEXT[];
    idx_name TEXT;
    expected_indexes TEXT[] := ARRAY[
        'idx_products_tenant_category_status_created',
        'idx_products_search_name_trgm',
        'idx_products_search_description_trgm',
        'idx_products_tenant_featured',
        'idx_orders_tenant_status_created',
        'idx_orders_tenant_customer_created',
        'idx_orders_tenant_fulfillment_status',
        'idx_inventory_levels_location_variant',
        'idx_inventory_levels_low_stock',
        'idx_user_sessions_user_expires',
        'idx_payment_tenders_order_status',
        'idx_domain_events_tenant_unprocessed',
        'idx_reporting_checkpoints_tenant_type',
        'idx_consignor_ledger_settlement',
        'idx_product_variants_product_id',
        'idx_order_items_order_id',
        'idx_order_items_variant_id',
        'idx_gift_card_txns_order_id',
        'idx_store_credit_txns_order_id'
    ];
BEGIN
    FOREACH idx_name IN ARRAY expected_indexes
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = idx_name) THEN
            missing_indexes := array_append(missing_indexes, idx_name);
        END IF;
    END LOOP;

    IF array_length(missing_indexes, 1) > 0 THEN
        RAISE WARNING 'Performance index migration incomplete. Missing indexes: %', missing_indexes;
    ELSE
        RAISE NOTICE 'All performance indexes created successfully (% indexes)', array_length(expected_indexes, 1);
    END IF;
END $$;
