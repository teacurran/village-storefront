-- Sample Catalog Data Loader Script
--
-- Populates development database with sample tenants, categories, products, variants, and inventory.
-- Designed to match the baseline schema from V20260102__baseline_schema.sql migration.
--
-- Usage:
--   psql -h localhost -U postgres -d village_storefront -f tools/scripts/sample_catalog_loader.sql
--
-- References:
--   - ERD: docs/diagrams/datamodel_erd.puml
--   - Migration: migrations/V20260102__baseline_schema.sql
--   - Task: I2.T1 (Catalog domain implementation)

-- ============================================================================
-- TENANTS
-- ============================================================================

-- Sample tenant 1: Tech Gadgets Store
INSERT INTO tenants (id, subdomain, name, status, settings, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'techgadgets',
    'Tech Gadgets Online',
    'active',
    '{"currency": "USD", "timezone": "America/New_York"}'::jsonb,
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- Sample tenant 2: Artisan Crafts Store
INSERT INTO tenants (id, subdomain, name, status, settings, created_at, updated_at)
VALUES (
    'a0000000-0000-0000-0000-000000000002'::uuid,
    'artisancrafts',
    'Artisan Crafts Collective',
    'active',
    '{"currency": "USD", "timezone": "America/Los_Angeles"}'::jsonb,
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- CATEGORIES (Tenant 1: Tech Gadgets)
-- ============================================================================

INSERT INTO categories (id, tenant_id, parent_id, code, name, slug, description, display_order, status, created_at, updated_at)
VALUES
    ('b0000001-0000-0000-0000-000000000001'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, NULL, 'ELECTRONICS', 'Electronics', 'electronics', 'Consumer electronics and gadgets', 1, 'active', NOW(), NOW()),
    ('b0000001-0000-0000-0000-000000000002'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'b0000001-0000-0000-0000-000000000001'::uuid, 'PHONES', 'Smartphones', 'smartphones', 'Latest smartphone models', 1, 'active', NOW(), NOW()),
    ('b0000001-0000-0000-0000-000000000003'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'b0000001-0000-0000-0000-000000000001'::uuid, 'AUDIO', 'Audio & Headphones', 'audio-headphones', 'Headphones, earbuds, and speakers', 2, 'active', NOW(), NOW()),
    ('b0000001-0000-0000-0000-000000000004'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, NULL, 'ACCESSORIES', 'Accessories', 'accessories', 'Tech accessories and peripherals', 2, 'active', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- PRODUCTS (Tenant 1: Tech Gadgets)
-- ============================================================================

-- Product 1: Wireless Earbuds
INSERT INTO products (id, tenant_id, sku, name, slug, description, type, status, metadata, seo_title, seo_description, created_at, updated_at)
VALUES (
    'c0000001-0000-0000-0000-000000000001'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'WE-PRO-001',
    'ProSound Wireless Earbuds',
    'prosound-wireless-earbuds',
    'Premium wireless earbuds with active noise cancellation and 24-hour battery life. Immersive audio experience for music lovers.',
    'physical',
    'active',
    '{"brand": "ProSound", "warranty": "1 year", "features": ["ANC", "Touch Controls", "Water Resistant"]}'::jsonb,
    'ProSound Wireless Earbuds - Premium Audio Experience',
    'Shop ProSound wireless earbuds with active noise cancellation, 24hr battery, and water resistance. Free shipping.',
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- Product 2: Phone Case
INSERT INTO products (id, tenant_id, sku, name, slug, description, type, status, metadata, created_at, updated_at)
VALUES (
    'c0000001-0000-0000-0000-000000000002'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'CASE-ULT-001',
    'Ultra-Slim Phone Case',
    'ultra-slim-phone-case',
    'Sleek and protective phone case with military-grade drop protection. Available in multiple colors.',
    'physical',
    'active',
    '{"brand": "ShieldTech", "material": "Polycarbonate", "dropProtection": "10ft"}'::jsonb,
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- Product 3: USB-C Cable
INSERT INTO products (id, tenant_id, sku, name, slug, description, type, status, metadata, created_at, updated_at)
VALUES (
    'c0000001-0000-0000-0000-000000000003'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'CABLE-USBC-001',
    'Braided USB-C Cable 6ft',
    'braided-usbc-cable-6ft',
    'Durable braided USB-C charging cable with fast charging support. 10,000+ bend tested.',
    'physical',
    'active',
    '{"brand": "ChargePro", "length": "6ft", "maxWattage": 100}'::jsonb,
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- PRODUCT CATEGORIES (Many-to-many relationships)
-- ============================================================================

INSERT INTO product_categories (tenant_id, product_id, category_id, display_order, created_at, updated_at)
VALUES
    ('a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000001'::uuid, 'b0000001-0000-0000-0000-000000000003'::uuid, 1, NOW(), NOW()),
    ('a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000002'::uuid, 'b0000001-0000-0000-0000-000000000004'::uuid, 1, NOW(), NOW()),
    ('a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000003'::uuid, 'b0000001-0000-0000-0000-000000000004'::uuid, 2, NOW(), NOW())
ON CONFLICT (tenant_id, product_id, category_id) DO NOTHING;

-- ============================================================================
-- PRODUCT VARIANTS (Pricing & SKUs)
-- ============================================================================

-- Variants for ProSound Wireless Earbuds (color options)
INSERT INTO product_variants (id, tenant_id, product_id, sku, name, attributes, price, compare_at_price, cost, weight, weight_unit, barcode, requires_shipping, taxable, position, status, created_at, updated_at)
VALUES
    ('d0000001-0000-0000-0000-000000000001'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000001'::uuid, 'WE-PRO-001-BLK', 'ProSound Wireless Earbuds - Black', '{"color": "Black"}'::jsonb, 149.99, 199.99, 75.00, 0.15, 'lb', '1234567890001', true, true, 1, 'active', NOW(), NOW()),
    ('d0000001-0000-0000-0000-000000000002'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000001'::uuid, 'WE-PRO-001-WHT', 'ProSound Wireless Earbuds - White', '{"color": "White"}'::jsonb, 149.99, 199.99, 75.00, 0.15, 'lb', '1234567890002', true, true, 2, 'active', NOW(), NOW()),
    ('d0000001-0000-0000-0000-000000000003'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000001'::uuid, 'WE-PRO-001-ROSE', 'ProSound Wireless Earbuds - Rose Gold', '{"color": "Rose Gold"}'::jsonb, 149.99, 199.99, 75.00, 0.15, 'lb', '1234567890003', true, true, 3, 'active', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Variants for Ultra-Slim Phone Case (multiple colors and models)
INSERT INTO product_variants (id, tenant_id, product_id, sku, name, attributes, price, cost, weight, weight_unit, requires_shipping, taxable, position, status, created_at, updated_at)
VALUES
    ('d0000001-0000-0000-0000-000000000004'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000002'::uuid, 'CASE-ULT-001-IP14-BLK', 'Ultra-Slim Case - iPhone 14 Black', '{"model": "iPhone 14", "color": "Black"}'::jsonb, 29.99, 8.00, 0.05, 'lb', true, true, 1, 'active', NOW(), NOW()),
    ('d0000001-0000-0000-0000-000000000005'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000002'::uuid, 'CASE-ULT-001-IP14-BLUE', 'Ultra-Slim Case - iPhone 14 Blue', '{"model": "iPhone 14", "color": "Blue"}'::jsonb, 29.99, 8.00, 0.05, 'lb', true, true, 2, 'active', NOW(), NOW()),
    ('d0000001-0000-0000-0000-000000000006'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000002'::uuid, 'CASE-ULT-001-IP15-BLK', 'Ultra-Slim Case - iPhone 15 Black', '{"model": "iPhone 15", "color": "Black"}'::jsonb, 34.99, 9.00, 0.05, 'lb', true, true, 3, 'active', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Variant for USB-C Cable (single variant)
INSERT INTO product_variants (id, tenant_id, product_id, sku, name, attributes, price, cost, weight, weight_unit, barcode, requires_shipping, taxable, position, status, created_at, updated_at)
VALUES
    ('d0000001-0000-0000-0000-000000000007'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000003'::uuid, 'CABLE-USBC-001-6FT', 'Braided USB-C Cable 6ft', '{"length": "6ft"}'::jsonb, 19.99, 5.00, 0.10, 'lb', '1234567890010', true, true, 1, 'active', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- INVENTORY LEVELS (Stock tracking)
-- ============================================================================

-- Inventory for ProSound Wireless Earbuds
INSERT INTO inventory_levels (id, tenant_id, variant_id, location, quantity, reserved, created_at, updated_at)
VALUES
    ('e0000001-0000-0000-0000-000000000001'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'd0000001-0000-0000-0000-000000000001'::uuid, 'warehouse-main', 150, 5, NOW(), NOW()),
    ('e0000001-0000-0000-0000-000000000002'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'd0000001-0000-0000-0000-000000000002'::uuid, 'warehouse-main', 120, 3, NOW(), NOW()),
    ('e0000001-0000-0000-0000-000000000003'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'd0000001-0000-0000-0000-000000000003'::uuid, 'warehouse-main', 80, 0, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Inventory for Phone Cases
INSERT INTO inventory_levels (id, tenant_id, variant_id, location, quantity, reserved, created_at, updated_at)
VALUES
    ('e0000001-0000-0000-0000-000000000004'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'd0000001-0000-0000-0000-000000000004'::uuid, 'warehouse-main', 500, 10, NOW(), NOW()),
    ('e0000001-0000-0000-0000-000000000005'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'd0000001-0000-0000-0000-000000000005'::uuid, 'warehouse-main', 450, 8, NOW(), NOW()),
    ('e0000001-0000-0000-0000-000000000006'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'd0000001-0000-0000-0000-000000000006'::uuid, 'warehouse-main', 300, 15, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Inventory for USB-C Cables
INSERT INTO inventory_levels (id, tenant_id, variant_id, location, quantity, reserved, created_at, updated_at)
VALUES
    ('e0000001-0000-0000-0000-000000000007'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'd0000001-0000-0000-0000-000000000007'::uuid, 'warehouse-main', 1000, 25, NOW(), NOW()),
    ('e0000001-0000-0000-0000-000000000008'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'd0000001-0000-0000-0000-000000000007'::uuid, 'store-main', 50, 2, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- VERIFICATION QUERIES
-- ============================================================================

-- Uncomment to verify data loading:

-- SELECT COUNT(*) AS tenant_count FROM tenants;
-- SELECT COUNT(*) AS category_count FROM categories;
-- SELECT COUNT(*) AS product_count FROM products;
-- SELECT COUNT(*) AS variant_count FROM product_variants;
-- SELECT COUNT(*) AS inventory_count FROM inventory_levels;

-- Sample product catalog query:
-- SELECT p.name, pv.sku, pv.price, il.quantity AS stock, il.location
-- FROM products p
-- JOIN product_variants pv ON p.id = pv.product_id
-- LEFT JOIN inventory_levels il ON pv.id = il.variant_id
-- WHERE p.tenant_id = 'a0000000-0000-0000-0000-000000000001'::uuid
-- AND p.status = 'active'
-- ORDER BY p.name, pv.position;

-- ============================================================================
-- COMPLETION
-- ============================================================================

\echo 'Sample catalog data loaded successfully!'
\echo 'Loaded: 2 tenants, 4 categories, 3 products, 7 variants, 8 inventory records'
\echo ''
\echo 'Test tenant credentials:'
\echo '  - Subdomain: techgadgets.localhost'
\echo '  - Tenant ID: a0000000-0000-0000-0000-000000000001'
\echo ''
\echo 'Verify with: SELECT * FROM products WHERE tenant_id = ''a0000000-0000-0000-0000-000000000001''::uuid;'
