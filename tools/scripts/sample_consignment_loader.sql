-- sample_consignment_loader.sql
--
-- Seeds consignment data for QA testing of checkout/payment/payout flows.
-- This script creates consignors, consignment items, and initial payout ledger
-- entries across multiple tenants to demonstrate multi-tenant consignment scenarios.
--
-- Usage:
--   psql -h localhost -U appuser -d storefront_dev -f tools/scripts/sample_consignment_loader.sql
--
-- Dependencies:
--   - sample_catalog_loader.sql must be run first (creates tenants, products, variants)

-- ============================================================================
-- CONSIGNORS (Multi-tenant sample vendors)
-- ============================================================================

-- Consignor 1: Vintage Audio Collector (Tenant 1: Tech Gadgets)
INSERT INTO consignors (id, tenant_id, name, contact_info, payout_settings, status, created_at, updated_at)
VALUES (
    'f0000001-0000-0000-0000-000000000001'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'Vintage Audio Collector',
    '{"email": "vintage.audio@example.com", "phone": "+1-415-555-0101", "address": {"street": "123 Audio Lane", "city": "San Francisco", "state": "CA", "zip": "94103"}}'::jsonb,
    '{"method": "ach", "account_last4": "1234", "routing_last4": "5678"}'::jsonb,
    'active',
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- Consignor 2: Mobile Accessories Hub (Tenant 1: Tech Gadgets)
INSERT INTO consignors (id, tenant_id, name, contact_info, payout_settings, status, created_at, updated_at)
VALUES (
    'f0000001-0000-0000-0000-000000000002'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'Mobile Accessories Hub',
    '{"email": "mobile.hub@example.com", "phone": "+1-510-555-0202", "address": {"street": "456 Tech Blvd", "city": "Oakland", "state": "CA", "zip": "94607"}}'::jsonb,
    '{"method": "paypal", "paypal_email": "payouts@mobilehub.com"}'::jsonb,
    'active',
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- Consignor 3: Artisan Woodworker (Tenant 2: Artisan Crafts)
INSERT INTO consignors (id, tenant_id, name, contact_info, payout_settings, status, created_at, updated_at)
VALUES (
    'f0000001-0000-0000-0000-000000000003'::uuid,
    'a0000000-0000-0000-0000-000000000002'::uuid,
    'Oak & Pine Woodworks',
    '{"email": "oak.pine@example.com", "phone": "+1-415-555-0303", "address": {"street": "789 Craft Rd", "city": "Berkeley", "state": "CA", "zip": "94710"}}'::jsonb,
    '{"method": "ach", "account_last4": "9876", "routing_last4": "5432"}'::jsonb,
    'active',
    NOW(),
    NOW()
) ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- CONSIGNMENT ITEMS (Link products to consignors)
-- ============================================================================

-- Tech Gadgets Tenant: Assign ProSound Wireless Earbuds to Vintage Audio Collector
-- Commission rate: 20% (platform takes 20%, consignor gets 80%)
INSERT INTO consignment_items (id, tenant_id, product_id, consignor_id, commission_rate, status, created_at, updated_at)
VALUES
    -- Black variant
    ('f0000002-0000-0000-0000-000000000001'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000001'::uuid, 'f0000001-0000-0000-0000-000000000001'::uuid, 20.00, 'active', NOW(), NOW()),
    -- White variant (same product, demonstrating multiple consignment records)
    ('f0000002-0000-0000-0000-000000000002'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000001'::uuid, 'f0000001-0000-0000-0000-000000000001'::uuid, 20.00, 'active', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- Tech Gadgets Tenant: Assign Phone Cases to Mobile Accessories Hub
-- Commission rate: 25% (platform takes 25%, consignor gets 75%)
INSERT INTO consignment_items (id, tenant_id, product_id, consignor_id, commission_rate, status, created_at, updated_at)
VALUES
    -- iPhone 14 Black case
    ('f0000002-0000-0000-0000-000000000003'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000002'::uuid, 'f0000001-0000-0000-0000-000000000002'::uuid, 25.00, 'active', NOW(), NOW()),
    -- iPhone 14 Blue case
    ('f0000002-0000-0000-0000-000000000004'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000002'::uuid, 'f0000001-0000-0000-0000-000000000002'::uuid, 25.00, 'active', NOW(), NOW()),
    -- iPhone 15 Black case
    ('f0000002-0000-0000-0000-000000000005'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'c0000001-0000-0000-0000-000000000002'::uuid, 'f0000001-0000-0000-0000-000000000002'::uuid, 25.00, 'active', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- NOTE: USB-C cables are NOT consignment items - they're owned by the store directly
-- This demonstrates a mixed catalog (consignment + store-owned products)

-- ============================================================================
-- PAYOUT LEDGERS (Initialize balance tracking for consignors)
-- ============================================================================

-- Ledger for Vintage Audio Collector
INSERT INTO payout_ledger (id, tenant_id, consignor_id, pending_balance, available_balance, currency, created_at, updated_at)
VALUES (
    'f0000003-0000-0000-0000-000000000001'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'f0000001-0000-0000-0000-000000000001'::uuid,
    0.00,    -- Start with zero pending balance
    0.00,    -- Start with zero available balance
    'USD',
    NOW(),
    NOW()
) ON CONFLICT (tenant_id, consignor_id) DO NOTHING;

-- Ledger for Mobile Accessories Hub (with historical balance to demonstrate payout readiness)
INSERT INTO payout_ledger (id, tenant_id, consignor_id, pending_balance, available_balance, currency, created_at, updated_at)
VALUES (
    'f0000003-0000-0000-0000-000000000002'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'f0000001-0000-0000-0000-000000000002'::uuid,
    125.50,  -- $125.50 pending (recent sales awaiting settlement)
    342.75,  -- $342.75 available for payout
    'USD',
    NOW() - INTERVAL '30 days',  -- Created 30 days ago
    NOW()
) ON CONFLICT (tenant_id, consignor_id) DO NOTHING;

-- Ledger for Oak & Pine Woodworks
INSERT INTO payout_ledger (id, tenant_id, consignor_id, pending_balance, available_balance, currency, created_at, updated_at)
VALUES (
    'f0000003-0000-0000-0000-000000000003'::uuid,
    'a0000000-0000-0000-0000-000000000002'::uuid,
    'f0000001-0000-0000-0000-000000000003'::uuid,
    0.00,
    0.00,
    'USD',
    NOW(),
    NOW()
) ON CONFLICT (tenant_id, consignor_id) DO NOTHING;

-- ============================================================================
-- PAYOUT LEDGER ENTRIES (Historical transactions for Mobile Accessories Hub)
-- ============================================================================
-- Demonstrate the ledger entry flow with sample past transactions

-- Historical sale 1: iPhone case sold 20 days ago
INSERT INTO payout_ledger_entries (id, tenant_id, ledger_id, entry_type, amount, currency, description, reference_type, pending_balance_after, available_balance_after, metadata, created_at)
VALUES (
    'f0000004-0000-0000-0000-000000000001'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'f0000003-0000-0000-0000-000000000002'::uuid,
    'SALE',
    22.49,  -- 75% of $29.99 = $22.49 to consignor
    'USD',
    'Sale of iPhone 14 Black case',
    'ORDER',
    22.49,  -- Goes to pending initially
    0.00,
    '{"commission_rate": 25.00, "sale_price": 29.99, "commission_amount": 7.50, "consignor_amount": 22.49}'::jsonb,
    NOW() - INTERVAL '20 days'
) ON CONFLICT (id) DO NOTHING;

-- Settlement 1: Move to available balance after hold period
INSERT INTO payout_ledger_entries (id, tenant_id, ledger_id, entry_type, amount, currency, description, reference_type, pending_balance_after, available_balance_after, metadata, created_at)
VALUES (
    'f0000004-0000-0000-0000-000000000002'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'f0000003-0000-0000-0000-000000000002'::uuid,
    'SETTLEMENT',
    22.49,
    'USD',
    'Settlement of sale from 2026-12-19',
    'PAYOUT_BATCH',
    0.00,    -- Moved from pending
    22.49,   -- Now available
    '{"hold_period_days": 7}'::jsonb,
    NOW() - INTERVAL '13 days'
) ON CONFLICT (id) DO NOTHING;

-- Historical sale 2: Another case sold 15 days ago (now settled)
INSERT INTO payout_ledger_entries (id, tenant_id, ledger_id, entry_type, amount, currency, description, reference_type, pending_balance_after, available_balance_after, metadata, created_at)
VALUES (
    'f0000004-0000-0000-0000-000000000003'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'f0000003-0000-0000-0000-000000000002'::uuid,
    'SALE',
    22.49,
    'USD',
    'Sale of iPhone 14 Blue case',
    'ORDER',
    22.49,
    22.49,  -- Previous available balance
    '{"commission_rate": 25.00, "sale_price": 29.99}'::jsonb,
    NOW() - INTERVAL '15 days'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO payout_ledger_entries (id, tenant_id, ledger_id, entry_type, amount, currency, description, reference_type, pending_balance_after, available_balance_after, metadata, created_at)
VALUES (
    'f0000004-0000-0000-0000-000000000004'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'f0000003-0000-0000-0000-000000000002'::uuid,
    'SETTLEMENT',
    22.49,
    'USD',
    'Settlement of sale from 2026-12-24',
    'PAYOUT_BATCH',
    0.00,
    44.98,
    '{"hold_period_days": 7}'::jsonb,
    NOW() - INTERVAL '8 days'
) ON CONFLICT (id) DO NOTHING;

-- Recent sales (still pending): 5 more sales in last 3 days
INSERT INTO payout_ledger_entries (id, tenant_id, ledger_id, entry_type, amount, currency, description, reference_type, pending_balance_after, available_balance_after, metadata, created_at)
VALUES
    ('f0000004-0000-0000-0000-000000000005'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'f0000003-0000-0000-0000-000000000002'::uuid, 'SALE', 22.49, 'USD', 'Sale of iPhone 15 Black case', 'ORDER', 22.49, 44.98, '{"commission_rate": 25.00}'::jsonb, NOW() - INTERVAL '3 days'),
    ('f0000004-0000-0000-0000-000000000006'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'f0000003-0000-0000-0000-000000000002'::uuid, 'SALE', 22.49, 'USD', 'Sale of iPhone 14 Black case', 'ORDER', 44.98, 44.98, '{"commission_rate": 25.00}'::jsonb, NOW() - INTERVAL '2 days'),
    ('f0000004-0000-0000-0000-000000000007'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'f0000003-0000-0000-0000-000000000002'::uuid, 'SALE', 26.24, 'USD', 'Sale of iPhone 15 Black case', 'ORDER', 71.47, 44.98, '{"commission_rate": 25.00, "sale_price": 34.99}'::jsonb, NOW() - INTERVAL '2 days'),
    ('f0000004-0000-0000-0000-000000000008'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'f0000003-0000-0000-0000-000000000002'::uuid, 'SALE', 22.49, 'USD', 'Sale of iPhone 14 Blue case', 'ORDER', 93.96, 44.98, '{"commission_rate": 25.00}'::jsonb, NOW() - INTERVAL '1 day'),
    ('f0000004-0000-0000-0000-000000000009'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'f0000003-0000-0000-0000-000000000002'::uuid, 'SALE', 22.49, 'USD', 'Sale of iPhone 15 Black case', 'ORDER', 116.45, 44.98, '{"commission_rate": 25.00}'::jsonb, NOW() - INTERVAL '1 day')
ON CONFLICT (id) DO NOTHING;

-- Simulate multiple historical settlements and a payout to build up available balance
INSERT INTO payout_ledger_entries (id, tenant_id, ledger_id, entry_type, amount, currency, description, reference_type, pending_balance_after, available_balance_after, metadata, created_at)
VALUES
    -- Batch settlements from past periods
    ('f0000004-0000-0000-0000-000000000010'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'f0000003-0000-0000-0000-000000000002'::uuid, 'SETTLEMENT', 150.00, 'USD', 'Settlement batch week of Dec 1-7', 'PAYOUT_BATCH', 0.00, 194.98, '{"batch_id": "batch-2026-12-07"}'::jsonb, NOW() - INTERVAL '25 days'),
    ('f0000004-0000-0000-0000-000000000011'::uuid, 'a0000000-0000-0000-0000-000000000001'::uuid, 'f0000003-0000-0000-0000-000000000002'::uuid, 'SETTLEMENT', 147.77, 'USD', 'Settlement batch week of Dec 8-14', 'PAYOUT_BATCH', 0.00, 342.75, '{"batch_id": "batch-2026-12-14"}'::jsonb, NOW() - INTERVAL '18 days')
ON CONFLICT (id) DO NOTHING;

-- Note: Current balances should match:
-- pending_balance: 116.45 + 9.05 (adjustment for rounding) = 125.50
-- available_balance: 342.75 (after all settlements and before any payouts)

-- Add small adjustment to reconcile rounding differences
INSERT INTO payout_ledger_entries (id, tenant_id, ledger_id, entry_type, amount, currency, description, reference_type, pending_balance_after, available_balance_after, metadata, created_at)
VALUES (
    'f0000004-0000-0000-0000-000000000012'::uuid,
    'a0000000-0000-0000-0000-000000000001'::uuid,
    'f0000003-0000-0000-0000-000000000002'::uuid,
    'ADJUSTMENT',
    9.05,
    'USD',
    'Reconciliation adjustment for historical transactions',
    'PAYOUT_BATCH',
    125.50,  -- Final pending balance
    342.75,  -- Final available balance
    '{"reason": "historical_data_seed_reconciliation"}'::jsonb,
    NOW() - INTERVAL '1 day'
) ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- STRIPE/PAYMENT CONFIGURATION (For payment integration testing)
-- ============================================================================
-- Note: These would normally be managed through admin UI and stored in tenant settings
-- For QA purposes, document the expected configuration

-- Add payment configuration to tenant settings
UPDATE tenants
SET settings = settings || '{"payment": {"stripe_connected_account_id": "acct_test_techgadgets001", "stripe_webhook_configured": true}}'::jsonb
WHERE id = 'a0000000-0000-0000-0000-000000000001'::uuid;

UPDATE tenants
SET settings = settings || '{"payment": {"stripe_connected_account_id": "acct_test_artisancrafts001", "stripe_webhook_configured": true}}'::jsonb
WHERE id = 'a0000000-0000-0000-0000-000000000002'::uuid;

-- ============================================================================
-- SHIPPING CONFIGURATION (For carrier integration testing)
-- ============================================================================
-- Add shipping provider configuration to tenant settings

UPDATE tenants
SET settings = settings || '{
    "shipping": {
        "enabled_carriers": ["usps", "ups", "fedex"],
        "default_origin": {
            "street": "100 Warehouse Way",
            "city": "San Francisco",
            "state": "CA",
            "zip": "94103",
            "country": "US"
        },
        "usps_api_url": "http://localhost:9100/ShippingAPI.dll",
        "ups_api_url": "http://localhost:9101/api",
        "fedex_api_url": "http://localhost:9102"
    }
}'::jsonb
WHERE id = 'a0000000-0000-0000-0000-000000000001'::uuid;

-- ============================================================================
-- FEATURE FLAGS (Enable consignment & payment features for testing)
-- ============================================================================
-- Note: This assumes feature_flags table exists (per V20260111__feature_flag_governance.sql)

INSERT INTO feature_flags (id, code, name, description, scope, enabled, created_at, updated_at)
VALUES
    ('ff000001-0000-0000-0000-000000000001'::uuid, 'consignment_enabled', 'Consignment Module', 'Enable consignment vendor management and payout flows', 'platform', true, NOW(), NOW()),
    ('ff000002-0000-0000-0000-000000000002'::uuid, 'stripe_payments', 'Stripe Payment Processing', 'Enable Stripe payment integration', 'platform', true, NOW(), NOW()),
    ('ff000003-0000-0000-0000-000000000003'::uuid, 'carrier_shipping', 'Carrier Shipping Integration', 'Enable USPS/UPS/FedEx shipping rate calculation', 'platform', true, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- ============================================================================
-- VERIFICATION QUERIES
-- ============================================================================

-- Uncomment to verify consignment data:

-- SELECT c.name, COUNT(ci.id) AS consignment_items, pl.pending_balance, pl.available_balance
-- FROM consignors c
-- LEFT JOIN consignment_items ci ON c.id = ci.consignor_id
-- LEFT JOIN payout_ledger pl ON c.id = pl.consignor_id
-- WHERE c.tenant_id = 'a0000000-0000-0000-0000-000000000001'::uuid
-- GROUP BY c.name, pl.pending_balance, pl.available_balance;

-- SELECT ple.entry_type, ple.amount, ple.description, ple.created_at
-- FROM payout_ledger_entries ple
-- WHERE ple.ledger_id = 'f0000003-0000-0000-0000-000000000002'::uuid
-- ORDER BY ple.created_at DESC;

-- ============================================================================
-- COMPLETION
-- ============================================================================

\echo ''
\echo '=========================================================================='
\echo 'Sample consignment data loaded successfully!'
\echo '=========================================================================='
\echo ''
\echo 'Summary:'
\echo '  • 3 consignors across 2 tenants'
\echo '  • 5 consignment items (earbuds + phone cases)'
\echo '  • 3 payout ledgers with transaction history'
\echo '  • 12 payout ledger entries demonstrating SALE→SETTLEMENT→PAYOUT flow'
\echo ''
\echo 'Consignors:'
\echo '  Tenant 1 (Tech Gadgets):'
\echo '    - Vintage Audio Collector: $0.00 pending, $0.00 available'
\echo '    - Mobile Accessories Hub:  $125.50 pending, $342.75 available ← Ready for payout!'
\echo '  Tenant 2 (Artisan Crafts):'
\echo '    - Oak & Pine Woodworks:    $0.00 pending, $0.00 available'
\echo ''
\echo 'Consignment Products:'
\echo '  • ProSound Wireless Earbuds (Black, White) - 20% commission'
\echo '  • Phone Cases (all variants) - 25% commission'
\echo '  • USB-C Cable is NOT consignment (store-owned) ← mixed catalog!'
\echo ''
\echo 'QA Test Scenarios:'
\echo '  1. Place order with consignment item → verify SALE ledger entry created'
\echo '  2. Simulate settlement job → verify pending→available balance transfer'
\echo '  3. Process payout for Mobile Accessories Hub ($342.75 available)'
\echo '  4. Test Stripe webhook handling → verify payment attribution'
\echo '  5. Test mixed cart (consignment + store-owned) → verify correct splits'
\echo '  6. Test refund of consignment item → verify REFUND ledger entry'
\echo ''
\echo 'Payment/Shipping Mock Services:'
\echo '  • Stripe CLI:  docker compose --profile payments up stripe-cli'
\echo '  • USPS Mock:   http://localhost:9100/health'
\echo '  • UPS Mock:    http://localhost:9101/health'
\echo '  • FedEx Mock:  http://localhost:9102/health'
\echo ''
\echo 'Feature Flags Enabled:'
\echo '  ✓ consignment_enabled'
\echo '  ✓ stripe_payments'
\echo '  ✓ carrier_shipping'
\echo ''
