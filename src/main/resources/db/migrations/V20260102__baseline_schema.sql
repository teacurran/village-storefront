-- ============================================================================
-- Village Storefront - Baseline Database Schema
-- ============================================================================
-- Migration: V20260102__baseline_schema.sql
-- Description: Initial schema creation for multi-tenant ecommerce platform
-- References:
--   - Architecture: docs/architecture_overview.md Section 5
--   - Tenancy ADR: docs/adr/ADR-001-tenancy.md
--   - OpenAPI Contract: api/v1/openapi.yaml
--   - ERD Diagram: docs/diagrams/datamodel_erd.puml
--
-- MyBatis Migrations format: +migrate Up / +migrate Down
-- ============================================================================

-- +migrate Up
-- ============================================================================
-- MIGRATION UP: Create all tables, indexes, and constraints
-- ============================================================================

-- ----------------------------------------------------------------------------
-- SCHEMA VERSIONING (Flyway compatibility for external tooling)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schema_version_history (
    installed_rank BIGSERIAL PRIMARY KEY,
    version VARCHAR(50),
    description VARCHAR(200),
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INTEGER,
    installed_by VARCHAR(100) NOT NULL DEFAULT CURRENT_USER,
    installed_on TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    execution_time INTEGER NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT TRUE
);

COMMENT ON TABLE schema_version_history IS 'Compatibility table mirroring flyway_schema_history for external tooling/monitoring';
COMMENT ON COLUMN schema_version_history.script IS 'MyBatis migration filename to keep parity with Flyway metadata consumers';

-- ----------------------------------------------------------------------------
-- TENANCY MODULE
-- ----------------------------------------------------------------------------
-- Foundation tables for multi-tenant architecture
-- Every tenant-scoped table will reference tenants(id) with ON DELETE CASCADE

-- TODO: Enable RLS once policies defined in ADR-001 follow-up
-- ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;

CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subdomain VARCHAR(63) UNIQUE NOT NULL,  -- RFC 1035 label max length
    name VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, suspended, deleted
    settings JSONB DEFAULT '{}',  -- Tenant-specific config (theme, locale, features)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tenants_status CHECK (status IN ('active', 'suspended', 'deleted')),
    CONSTRAINT chk_tenants_subdomain_format CHECK (subdomain ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$')
);

CREATE INDEX idx_tenants_status ON tenants(status);
CREATE INDEX idx_tenants_created_at ON tenants(created_at DESC);

COMMENT ON TABLE tenants IS 'Core tenant table; each tenant is a separate merchant store';
COMMENT ON COLUMN tenants.subdomain IS 'DNS-safe subdomain (storename.platform.com)';
COMMENT ON COLUMN tenants.settings IS 'JSONB blob for tenant-specific configuration';

-- Custom domain mapping (many-to-one with tenants)
CREATE TABLE custom_domains (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    domain VARCHAR(253) UNIQUE NOT NULL,  -- RFC 1035 FQDN max length
    verified BOOLEAN DEFAULT FALSE,
    verification_token VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_custom_domains_domain_format CHECK (domain ~ '^[a-z0-9]([a-z0-9-\.]{0,251}[a-z0-9])?$')
);

CREATE INDEX idx_custom_domains_tenant_id ON custom_domains(tenant_id);
CREATE INDEX idx_custom_domains_domain ON custom_domains(domain);
CREATE INDEX idx_custom_domains_verified ON custom_domains(verified) WHERE verified = TRUE;

COMMENT ON TABLE custom_domains IS 'Custom domain mappings for tenant stores (e.g., shop.example.com)';
COMMENT ON COLUMN custom_domains.verification_token IS 'Token for DNS TXT record verification';

-- ----------------------------------------------------------------------------
-- IDENTITY MODULE
-- ----------------------------------------------------------------------------
-- User authentication, authorization, and API key management

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),  -- Nullable for OAuth-only users
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, suspended, deleted
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone VARCHAR(20),
    metadata JSONB DEFAULT '{}',  -- Custom user attributes
    email_verified BOOLEAN DEFAULT FALSE,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_tenant_email UNIQUE (tenant_id, email),
    CONSTRAINT chk_users_status CHECK (status IN ('active', 'suspended', 'deleted')),
    CONSTRAINT chk_users_email_format CHECK (email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$')
);

CREATE INDEX idx_users_tenant_id ON users(tenant_id);
CREATE INDEX idx_users_tenant_id_status ON users(tenant_id, status);
CREATE INDEX idx_users_tenant_id_email ON users(tenant_id, email);
CREATE INDEX idx_users_email_verified ON users(email_verified) WHERE email_verified = TRUE;

COMMENT ON TABLE users IS 'User accounts scoped to tenant; supports both password and OAuth authentication';
COMMENT ON COLUMN users.password_hash IS 'Bcrypt hash; nullable for social login-only users';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    permissions JSONB DEFAULT '[]',  -- Array of permission strings
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_roles_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX idx_roles_tenant_id ON roles(tenant_id);

COMMENT ON TABLE roles IS 'RBAC roles scoped to tenant';
COMMENT ON COLUMN roles.permissions IS 'JSONB array of permission identifiers (e.g., ["products.write", "orders.read"])';

-- Many-to-many join table for user-role assignments
CREATE TABLE user_roles (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, user_id, role_id)
);

CREATE INDEX idx_user_roles_tenant_id ON user_roles(tenant_id);
CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles(role_id);

COMMENT ON TABLE user_roles IS 'Join table for many-to-many user-role relationships';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE api_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,  -- Nullable for service accounts
    key_hash VARCHAR(255) NOT NULL,  -- Hash of the API key
    key_prefix VARCHAR(20) NOT NULL,  -- First 8 chars for identification
    name VARCHAR(100) NOT NULL,
    scopes JSONB DEFAULT '[]',  -- Array of scope strings
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, revoked
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_api_keys_status CHECK (status IN ('active', 'revoked'))
);

CREATE INDEX idx_api_keys_tenant_id ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_user_id ON api_keys(user_id);
CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_status ON api_keys(status) WHERE status = 'active';

COMMENT ON TABLE api_keys IS 'API keys for programmatic access; scoped to tenant and optionally user';
COMMENT ON COLUMN api_keys.key_hash IS 'SHA-256 hash of the full API key (never store plaintext)';
COMMENT ON COLUMN api_keys.key_prefix IS 'First 8 characters for display in UI (e.g., "sk_live_")';

-- ----------------------------------------------------------------------------
-- CATALOG MODULE
-- ----------------------------------------------------------------------------
-- Product catalog, variants, categories, images, and inventory management

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES categories(id) ON DELETE SET NULL,  -- Nullable for top-level categories
    code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    description TEXT,
    display_order INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, deleted
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_categories_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_categories_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT chk_categories_status CHECK (status IN ('active', 'deleted')),
    CONSTRAINT chk_categories_slug_format CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE INDEX idx_categories_tenant_id ON categories(tenant_id);
CREATE INDEX idx_categories_parent_id ON categories(parent_id);
CREATE INDEX idx_categories_tenant_id_status ON categories(tenant_id, status);

COMMENT ON TABLE categories IS 'Hierarchical product categories (supports parent-child relationships)';
COMMENT ON COLUMN categories.parent_id IS 'Self-referencing FK for category tree; NULL = root category';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(20) NOT NULL DEFAULT 'physical',  -- physical, digital, service
    status VARCHAR(20) NOT NULL DEFAULT 'draft',  -- draft, active, archived, deleted
    metadata JSONB DEFAULT '{}',  -- Custom product attributes
    seo_title VARCHAR(255),
    seo_description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_products_tenant_sku UNIQUE (tenant_id, sku),
    CONSTRAINT uq_products_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT chk_products_type CHECK (type IN ('physical', 'digital', 'service')),
    CONSTRAINT chk_products_status CHECK (status IN ('draft', 'active', 'archived', 'deleted')),
    CONSTRAINT chk_products_slug_format CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE INDEX idx_products_tenant_id ON products(tenant_id);
CREATE INDEX idx_products_tenant_id_status ON products(tenant_id, status);
CREATE INDEX idx_products_tenant_id_type ON products(tenant_id, type);
CREATE INDEX idx_products_sku ON products(tenant_id, sku);
CREATE INDEX idx_products_slug ON products(tenant_id, slug);

COMMENT ON TABLE products IS 'Core product table (master SKU); variants represent sellable configurations';
COMMENT ON COLUMN products.type IS 'Product type affects shipping/inventory behavior';
COMMENT ON COLUMN products.metadata IS 'JSONB for custom attributes (colors, materials, etc.)';

-- TODO: Enable RLS policy per ADR-001 (tenant_id scoped, cascades alongside products)
CREATE TABLE product_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    sku VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    attributes JSONB DEFAULT '{}',  -- Variant-specific attributes (size, color)
    price NUMERIC(19,4) NOT NULL,  -- Base price; maps to Money schema in API
    compare_at_price NUMERIC(19,4),  -- Original price for discount display
    cost NUMERIC(19,4),  -- Cost of goods sold (COGS)
    weight NUMERIC(10,2),
    weight_unit VARCHAR(10) DEFAULT 'kg',  -- kg, lb, oz, g
    barcode VARCHAR(100),
    requires_shipping BOOLEAN DEFAULT TRUE,
    taxable BOOLEAN DEFAULT TRUE,
    position INTEGER DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, archived, deleted
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_variants_tenant_sku UNIQUE (tenant_id, sku),
    CONSTRAINT chk_product_variants_status CHECK (status IN ('active', 'archived', 'deleted')),
    CONSTRAINT chk_product_variants_weight_unit CHECK (weight_unit IN ('kg', 'lb', 'oz', 'g')),
    CONSTRAINT chk_product_variants_price_positive CHECK (price >= 0)
);

CREATE INDEX idx_product_variants_tenant_id ON product_variants(tenant_id);
CREATE INDEX idx_product_variants_product_id ON product_variants(product_id);
CREATE INDEX idx_product_variants_status ON product_variants(tenant_id, status);

COMMENT ON TABLE product_variants IS 'Sellable product variants (e.g., T-shirt in Red/Medium)';
COMMENT ON COLUMN product_variants.price IS 'NUMERIC(19,4) for precision; converted to Money schema {"amount":"19.99","currency":"USD"} at API layer per Section 5 contract';
COMMENT ON COLUMN product_variants.attributes IS 'JSONB for variant options (e.g., {"size":"M","color":"red"})';

-- Many-to-many join table for product-category relationships
CREATE TABLE product_categories (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    category_id UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    display_order INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, product_id, category_id)
);

CREATE INDEX idx_product_categories_tenant_id ON product_categories(tenant_id);
CREATE INDEX idx_product_categories_product_id ON product_categories(product_id);
CREATE INDEX idx_product_categories_category_id ON product_categories(category_id);

COMMENT ON TABLE product_categories IS 'Many-to-many join table for product-category assignments';

-- TODO: Enable RLS policy per ADR-001 (tenant_id scoped with parent product/variant links)
CREATE TABLE product_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    variant_id UUID REFERENCES product_variants(id) ON DELETE CASCADE,  -- Nullable for product-level images
    url VARCHAR(500) NOT NULL,
    position INTEGER DEFAULT 0,
    alt_text VARCHAR(255),
    width INTEGER,
    height INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_product_images_tenant_id ON product_images(tenant_id);
CREATE INDEX idx_product_images_product_id ON product_images(product_id);
CREATE INDEX idx_product_images_variant_id ON product_images(variant_id);

COMMENT ON TABLE product_images IS 'Product and variant images; variant_id NULL indicates product-level image';
COMMENT ON COLUMN product_images.url IS 'CDN or storage URL for image asset';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE inventory_levels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    location VARCHAR(100) NOT NULL DEFAULT 'default',  -- Warehouse/store location identifier
    quantity INTEGER NOT NULL DEFAULT 0,
    reserved INTEGER NOT NULL DEFAULT 0,  -- Quantity reserved in pending orders
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_inventory_levels_tenant_variant_location UNIQUE (tenant_id, variant_id, location),
    CONSTRAINT chk_inventory_levels_quantity_nonnegative CHECK (quantity >= 0),
    CONSTRAINT chk_inventory_levels_reserved_nonnegative CHECK (reserved >= 0)
);

CREATE INDEX idx_inventory_levels_tenant_id ON inventory_levels(tenant_id);
CREATE INDEX idx_inventory_levels_variant_id ON inventory_levels(variant_id);
CREATE INDEX idx_inventory_levels_location ON inventory_levels(location);

COMMENT ON TABLE inventory_levels IS 'Inventory tracking per variant per location; supports multi-warehouse scenarios';
COMMENT ON COLUMN inventory_levels.reserved IS 'Quantity held for pending/processing orders (not available for new orders)';

-- ----------------------------------------------------------------------------
-- CART/ORDER/PAYMENT MODULE
-- ----------------------------------------------------------------------------
-- Shopping carts, checkout, orders, shipments, and payment processing

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE carts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,  -- Nullable for guest carts
    session_id VARCHAR(255),  -- For guest cart tracking
    metadata JSONB DEFAULT '{}',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_carts_tenant_id ON carts(tenant_id);
CREATE INDEX idx_carts_user_id ON carts(user_id);
CREATE INDEX idx_carts_session_id ON carts(session_id);
CREATE INDEX idx_carts_expires_at ON carts(expires_at);

COMMENT ON TABLE carts IS 'Shopping carts; supports both authenticated users and guest sessions';
COMMENT ON COLUMN carts.session_id IS 'Session identifier for guest carts (before user authentication)';

-- TODO: Enable RLS policy per ADR-001 (explicit tenant_id for cart-owned rows)
CREATE TABLE cart_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    cart_id UUID NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price NUMERIC(19,4) NOT NULL,  -- Snapshot price at add-to-cart time
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cart_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_cart_items_unit_price_nonnegative CHECK (unit_price >= 0)
);

CREATE INDEX idx_cart_items_cart_id ON cart_items(cart_id);
CREATE INDEX idx_cart_items_variant_id ON cart_items(variant_id);
CREATE INDEX idx_cart_items_tenant_id ON cart_items(tenant_id);

COMMENT ON TABLE cart_items IS 'Line items within shopping carts';
COMMENT ON COLUMN cart_items.unit_price IS 'Price snapshot to preserve pricing at time of add-to-cart';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_number VARCHAR(50) NOT NULL,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,  -- Nullable for guest checkouts
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, confirmed, processing, shipped, delivered, cancelled, refunded
    totals JSONB NOT NULL,  -- Order totals (subtotal, tax, shipping, total) as Money objects
    shipping_address JSONB,  -- Address schema per OpenAPI
    billing_address JSONB,  -- Address schema per OpenAPI
    customer_notes TEXT,
    internal_notes TEXT,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    cancelled_at TIMESTAMPTZ,
    cancellation_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_orders_tenant_order_number UNIQUE (tenant_id, order_number),
    CONSTRAINT chk_orders_status CHECK (status IN ('pending', 'confirmed', 'processing', 'shipped', 'delivered', 'cancelled', 'refunded')),
    CONSTRAINT chk_orders_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_orders_tenant_id ON orders(tenant_id);
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_tenant_id_status ON orders(tenant_id, status);
CREATE INDEX idx_orders_order_number ON orders(tenant_id, order_number);
CREATE INDEX idx_orders_created_at ON orders(created_at DESC);

COMMENT ON TABLE orders IS 'Customer orders (confirmed carts); status transitions from pending → confirmed → processing → shipped → delivered';
COMMENT ON COLUMN orders.totals IS 'JSONB with Money objects per OpenAPI CheckoutPreview.totals schema (subtotal, tax, shipping, discount, total)';
COMMENT ON COLUMN orders.shipping_address IS 'JSONB following OpenAPI Address schema';

-- TODO: Enable RLS policy per ADR-001 (explicit tenant_id to align with orders)
CREATE TABLE order_line_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    variant_id UUID NOT NULL REFERENCES product_variants(id) ON DELETE RESTRICT,  -- Prevent variant deletion if ordered
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    totals JSONB NOT NULL,  -- Line item totals (subtotal, tax, discount, total)
    product_snapshot JSONB,  -- Snapshot of product/variant data at order time
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_order_line_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_order_line_items_unit_price_nonnegative CHECK (unit_price >= 0)
);

CREATE INDEX idx_order_line_items_tenant_id ON order_line_items(tenant_id);
CREATE INDEX idx_order_line_items_order_id ON order_line_items(order_id);
CREATE INDEX idx_order_line_items_variant_id ON order_line_items(variant_id);

COMMENT ON TABLE order_line_items IS 'Line items within orders; immutable once order confirmed';
COMMENT ON COLUMN order_line_items.product_snapshot IS 'JSONB snapshot of product/variant details at purchase time (preserve against future edits)';

-- TODO: Enable RLS policy per ADR-001 (explicit tenant_id to align with orders)
CREATE TABLE shipments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    carrier VARCHAR(100),
    tracking_number VARCHAR(255),
    tracking_url VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, in_transit, delivered, failed
    shipped_at TIMESTAMPTZ,
    estimated_delivery_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_shipments_status CHECK (status IN ('pending', 'in_transit', 'delivered', 'failed'))
);

CREATE INDEX idx_shipments_tenant_id ON shipments(tenant_id);
CREATE INDEX idx_shipments_order_id ON shipments(order_id);
CREATE INDEX idx_shipments_status ON shipments(status);
CREATE INDEX idx_shipments_tracking_number ON shipments(tracking_number);

COMMENT ON TABLE shipments IS 'Shipment tracking for orders (supports multiple shipments per order for partial fulfillment)';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE payment_methods (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,  -- card, bank_account, wallet
    provider_id VARCHAR(255),  -- External payment provider's customer/payment method ID
    last_four VARCHAR(4),
    brand VARCHAR(50),  -- visa, mastercard, amex, etc.
    expiry_month INTEGER,
    expiry_year INTEGER,
    billing_address JSONB,  -- Address schema per OpenAPI
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payment_methods_type CHECK (type IN ('card', 'bank_account', 'wallet')),
    CONSTRAINT chk_payment_methods_expiry_month CHECK (expiry_month BETWEEN 1 AND 12)
);

CREATE INDEX idx_payment_methods_tenant_id ON payment_methods(tenant_id);
CREATE INDEX idx_payment_methods_user_id ON payment_methods(user_id);
CREATE INDEX idx_payment_methods_is_default ON payment_methods(user_id, is_default) WHERE is_default = TRUE;

COMMENT ON TABLE payment_methods IS 'Saved payment methods for users (tokenized via Stripe/payment provider)';
COMMENT ON COLUMN payment_methods.provider_id IS 'Stripe PaymentMethod ID or equivalent from payment provider';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    payment_method_id UUID REFERENCES payment_methods(id) ON DELETE SET NULL,  -- Nullable for deleted payment methods
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, processing, succeeded, failed, cancelled
    provider VARCHAR(50),  -- stripe, paypal, square, etc.
    provider_transaction_id VARCHAR(255),  -- External transaction ID for reconciliation
    provider_response JSONB,  -- Raw response from payment provider
    metadata JSONB DEFAULT '{}',
    error_message TEXT,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payments_status CHECK (status IN ('pending', 'processing', 'succeeded', 'failed', 'cancelled')),
    CONSTRAINT chk_payments_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_payments_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_payments_tenant_id ON payments(tenant_id);
CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_provider_transaction_id ON payments(provider_transaction_id);

COMMENT ON TABLE payments IS 'Payment transactions for orders; append-only audit trail';
COMMENT ON COLUMN payments.provider_response IS 'JSONB blob of full provider webhook/API response for debugging';

-- TODO: Enable RLS policy per ADR-001 (tenant_id stored alongside payment reference)
CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,  -- Prevent payment deletion if refunded
    amount NUMERIC(19,4) NOT NULL,
    reason TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, processing, succeeded, failed
    provider_refund_id VARCHAR(255),  -- External refund transaction ID
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_refunds_status CHECK (status IN ('pending', 'processing', 'succeeded', 'failed')),
    CONSTRAINT chk_refunds_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_refunds_tenant_id ON refunds(tenant_id);
CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);
CREATE INDEX idx_refunds_status ON refunds(status);

COMMENT ON TABLE refunds IS 'Refund transactions (partial or full) for payments; append-only audit trail';

-- ----------------------------------------------------------------------------
-- CONSIGNMENT MODULE
-- ----------------------------------------------------------------------------
-- Consignment vendor management and payout processing

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE consignors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    contact_info JSONB,  -- Email, phone, address
    payout_settings JSONB,  -- Bank account, PayPal, etc.
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, suspended, deleted
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_consignors_status CHECK (status IN ('active', 'suspended', 'deleted'))
);

CREATE INDEX idx_consignors_tenant_id ON consignors(tenant_id);
CREATE INDEX idx_consignors_status ON consignors(tenant_id, status);

COMMENT ON TABLE consignors IS 'Consignment vendors who provide products for sale on commission basis';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE consignment_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    consignor_id UUID NOT NULL REFERENCES consignors(id) ON DELETE CASCADE,
    commission_rate NUMERIC(5,2) NOT NULL,  -- Percentage (e.g., 15.00 for 15%)
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, sold, returned, deleted
    sold_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_consignment_items_status CHECK (status IN ('active', 'sold', 'returned', 'deleted')),
    CONSTRAINT chk_consignment_items_commission_rate CHECK (commission_rate BETWEEN 0 AND 100)
);

CREATE INDEX idx_consignment_items_tenant_id ON consignment_items(tenant_id);
CREATE INDEX idx_consignment_items_product_id ON consignment_items(product_id);
CREATE INDEX idx_consignment_items_consignor_id ON consignment_items(consignor_id);
CREATE INDEX idx_consignment_items_status ON consignment_items(status);

COMMENT ON TABLE consignment_items IS 'Products sold on consignment; tracks commission rate per item';
COMMENT ON COLUMN consignment_items.commission_rate IS 'Commission percentage taken by platform (remainder goes to consignor)';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE payout_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    consignor_id UUID NOT NULL REFERENCES consignors(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, processing, completed, failed
    processed_at TIMESTAMPTZ,
    payment_reference VARCHAR(255),  -- External payment reference (bank transfer ID, PayPal transaction)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payout_batches_status CHECK (status IN ('pending', 'processing', 'completed', 'failed')),
    CONSTRAINT chk_payout_batches_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_payout_batches_total_amount_nonnegative CHECK (total_amount >= 0)
);

CREATE INDEX idx_payout_batches_tenant_id ON payout_batches(tenant_id);
CREATE INDEX idx_payout_batches_consignor_id ON payout_batches(consignor_id);
CREATE INDEX idx_payout_batches_status ON payout_batches(status);

COMMENT ON TABLE payout_batches IS 'Periodic payouts to consignors for sold items';

-- TODO: Enable RLS policy per ADR-001 (tenant_id stored alongside payout_batches)
CREATE TABLE payout_line_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    batch_id UUID NOT NULL REFERENCES payout_batches(id) ON DELETE CASCADE,
    order_line_item_id UUID NOT NULL REFERENCES order_line_items(id) ON DELETE RESTRICT,
    item_subtotal NUMERIC(19,4) NOT NULL,  -- Original line item subtotal
    commission_amount NUMERIC(19,4) NOT NULL,  -- Platform commission
    net_payout NUMERIC(19,4) NOT NULL,  -- Amount paid to consignor (subtotal - commission)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payout_line_items_amounts_nonnegative CHECK (item_subtotal >= 0 AND commission_amount >= 0 AND net_payout >= 0)
);

CREATE INDEX idx_payout_line_items_tenant_id ON payout_line_items(tenant_id);
CREATE INDEX idx_payout_line_items_batch_id ON payout_line_items(batch_id);
CREATE INDEX idx_payout_line_items_order_line_item_id ON payout_line_items(order_line_item_id);

COMMENT ON TABLE payout_line_items IS 'Individual line items within payout batches; reconciles order line items to consignor payouts';

-- ----------------------------------------------------------------------------
-- LOYALTY MODULE
-- ----------------------------------------------------------------------------
-- Loyalty programs, member enrollment, and points/rewards tracking

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE loyalty_programs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    rules JSONB NOT NULL,  -- Points earning/redemption rules, tier thresholds
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_loyalty_programs_tenant_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_loyalty_programs_tenant_id ON loyalty_programs(tenant_id);
CREATE INDEX idx_loyalty_programs_active ON loyalty_programs(active) WHERE active = TRUE;

COMMENT ON TABLE loyalty_programs IS 'Tenant-specific loyalty programs (e.g., VIP rewards, points program)';
COMMENT ON COLUMN loyalty_programs.rules IS 'JSONB for flexible rules (e.g., {"earn_rate":1,"redeem_rate":100,"tiers":["bronze","silver","gold"]})';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE loyalty_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    program_id UUID NOT NULL REFERENCES loyalty_programs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    points_balance INTEGER NOT NULL DEFAULT 0,
    tier VARCHAR(50),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_loyalty_members_tenant_program_user UNIQUE (tenant_id, program_id, user_id),
    CONSTRAINT chk_loyalty_members_points_balance_nonnegative CHECK (points_balance >= 0)
);

CREATE INDEX idx_loyalty_members_tenant_id ON loyalty_members(tenant_id);
CREATE INDEX idx_loyalty_members_program_id ON loyalty_members(program_id);
CREATE INDEX idx_loyalty_members_user_id ON loyalty_members(user_id);

COMMENT ON TABLE loyalty_members IS 'User enrollment in loyalty programs; tracks points balance and tier status';

-- TODO: Enable RLS policy per ADR-001
CREATE TABLE loyalty_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES loyalty_members(id) ON DELETE CASCADE,
    order_id UUID REFERENCES orders(id) ON DELETE SET NULL,  -- Nullable for manual adjustments
    points_delta INTEGER NOT NULL,  -- Positive for earning, negative for redemption
    transaction_type VARCHAR(50) NOT NULL,  -- earned, redeemed, adjusted, expired
    description TEXT,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_loyalty_transactions_type CHECK (transaction_type IN ('earned', 'redeemed', 'adjusted', 'expired'))
);

CREATE INDEX idx_loyalty_transactions_tenant_id ON loyalty_transactions(tenant_id);
CREATE INDEX idx_loyalty_transactions_member_id ON loyalty_transactions(member_id);
CREATE INDEX idx_loyalty_transactions_order_id ON loyalty_transactions(order_id);
CREATE INDEX idx_loyalty_transactions_created_at ON loyalty_transactions(created_at DESC);

COMMENT ON TABLE loyalty_transactions IS 'Append-only ledger of loyalty points transactions';
COMMENT ON COLUMN loyalty_transactions.points_delta IS 'Change in points (+ for earn, - for redeem/expire)';

-- ----------------------------------------------------------------------------
-- CROSS-CUTTING TABLES
-- ----------------------------------------------------------------------------
-- Audit logging, background jobs, and feature flags

-- Audit log (tenant_id nullable for platform-level actions)
CREATE TABLE audit_log_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,  -- Nullable for platform actions
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,  -- Nullable for system actions
    action VARCHAR(100) NOT NULL,  -- create, update, delete, login, impersonate, etc.
    entity_type VARCHAR(100),  -- Product, Order, User, etc.
    entity_id UUID,
    changes JSONB,  -- Before/after snapshots or delta
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_entries_tenant_id ON audit_log_entries(tenant_id);
CREATE INDEX idx_audit_log_entries_user_id ON audit_log_entries(user_id);
CREATE INDEX idx_audit_log_entries_action ON audit_log_entries(action);
CREATE INDEX idx_audit_log_entries_entity_type_id ON audit_log_entries(entity_type, entity_id);
CREATE INDEX idx_audit_log_entries_created_at ON audit_log_entries(created_at DESC);

COMMENT ON TABLE audit_log_entries IS 'Append-only audit trail for all significant actions (never delete rows)';
COMMENT ON COLUMN audit_log_entries.tenant_id IS 'Nullable for platform-level actions (e.g., tenant creation, admin impersonation)';

-- Background job queue (tenant_id nullable for system jobs)
CREATE TABLE delayed_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,  -- Nullable for system jobs
    queue VARCHAR(100) NOT NULL DEFAULT 'default',
    handler_class VARCHAR(255) NOT NULL,
    payload JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, locked, processing, completed, failed
    attempts INTEGER DEFAULT 0,
    last_error TEXT,
    run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_until TIMESTAMPTZ,
    locked_by VARCHAR(255),  -- Worker identifier
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_delayed_jobs_status CHECK (status IN ('pending', 'locked', 'processing', 'completed', 'failed')),
    CONSTRAINT chk_delayed_jobs_attempts_nonnegative CHECK (attempts >= 0)
);

CREATE INDEX idx_delayed_jobs_tenant_id ON delayed_jobs(tenant_id);
CREATE INDEX idx_delayed_jobs_queue_status_run_at ON delayed_jobs(queue, status, run_at) WHERE status = 'pending';
CREATE INDEX idx_delayed_jobs_locked_until ON delayed_jobs(locked_until);

COMMENT ON TABLE delayed_jobs IS 'Background job queue (email notifications, report generation, etc.)';
COMMENT ON COLUMN delayed_jobs.tenant_id IS 'Nullable for platform-wide system jobs (cleanup, aggregations)';

-- Feature flags (tenant_id nullable for global flags)
CREATE TABLE feature_flags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,  -- Nullable for global flags
    flag_key VARCHAR(100) NOT NULL,
    enabled BOOLEAN DEFAULT FALSE,
    config JSONB DEFAULT '{}',  -- Additional configuration for feature
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_feature_flags_tenant_flag_key UNIQUE (tenant_id, flag_key)
);

CREATE INDEX idx_feature_flags_tenant_id ON feature_flags(tenant_id);
CREATE INDEX idx_feature_flags_flag_key ON feature_flags(flag_key);
CREATE INDEX idx_feature_flags_enabled ON feature_flags(enabled) WHERE enabled = TRUE;

COMMENT ON TABLE feature_flags IS 'Feature toggles for gradual rollouts and A/B testing';
COMMENT ON COLUMN feature_flags.tenant_id IS 'Nullable for platform-wide flags; tenant-specific flags override global';

-- ============================================================================
-- END MIGRATION UP
-- ============================================================================

-- +migrate Down
-- ============================================================================
-- MIGRATION DOWN: Drop all tables in reverse dependency order
-- ============================================================================

-- Metadata compatibility table
DROP TABLE IF EXISTS schema_version_history CASCADE;

-- Cross-cutting tables
DROP TABLE IF EXISTS feature_flags CASCADE;
DROP TABLE IF EXISTS delayed_jobs CASCADE;
DROP TABLE IF EXISTS audit_log_entries CASCADE;

-- Loyalty module
DROP TABLE IF EXISTS loyalty_transactions CASCADE;
DROP TABLE IF EXISTS loyalty_members CASCADE;
DROP TABLE IF EXISTS loyalty_programs CASCADE;

-- Consignment module
DROP TABLE IF EXISTS payout_line_items CASCADE;
DROP TABLE IF EXISTS payout_batches CASCADE;
DROP TABLE IF EXISTS consignment_items CASCADE;
DROP TABLE IF EXISTS consignors CASCADE;

-- Cart/Order/Payment module
DROP TABLE IF EXISTS refunds CASCADE;
DROP TABLE IF EXISTS payments CASCADE;
DROP TABLE IF EXISTS payment_methods CASCADE;
DROP TABLE IF EXISTS shipments CASCADE;
DROP TABLE IF EXISTS order_line_items CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS cart_items CASCADE;
DROP TABLE IF EXISTS carts CASCADE;

-- Catalog module
DROP TABLE IF EXISTS inventory_levels CASCADE;
DROP TABLE IF EXISTS product_images CASCADE;
DROP TABLE IF EXISTS product_categories CASCADE;
DROP TABLE IF EXISTS product_variants CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS categories CASCADE;

-- Identity module
DROP TABLE IF EXISTS api_keys CASCADE;
DROP TABLE IF EXISTS user_roles CASCADE;
DROP TABLE IF EXISTS roles CASCADE;
DROP TABLE IF EXISTS users CASCADE;

-- Tenancy module
DROP TABLE IF EXISTS custom_domains CASCADE;
DROP TABLE IF EXISTS tenants CASCADE;

-- ============================================================================
-- END MIGRATION DOWN
-- ============================================================================
