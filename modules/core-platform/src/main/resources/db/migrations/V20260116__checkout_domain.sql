-- Checkout and order orchestration schema changes (Task I3.T1)
-- Introduces promotions, idempotency keys, enriched orders/order_line_items, and aligns payment intents with UUID order ids

-- Promotions table ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS promotions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code VARCHAR(50) NOT NULL,
    description TEXT,
    discount_type VARCHAR(20) NOT NULL,
    discount_value NUMERIC(19,4) NOT NULL,
    minimum_order_amount NUMERIC(19,4),
    max_uses INTEGER,
    current_uses INTEGER NOT NULL DEFAULT 0,
    starts_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    metadata JSONB DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_promotions_code UNIQUE (tenant_id, code)
);

CREATE INDEX IF NOT EXISTS idx_promotions_tenant ON promotions(tenant_id);

COMMENT ON TABLE promotions IS 'Tenant-scoped promotion codes for cart discounts';
COMMENT ON COLUMN promotions.discount_type IS 'PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING';

-- Idempotency key store -----------------------------------------------------
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    result JSONB,
    error JSONB,
    response_code INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours',
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_idempotency_keys_tenant_operation ON idempotency_keys(tenant_id, operation_type);

COMMENT ON TABLE idempotency_keys IS 'Stores request idempotency metadata to guard checkout/payment retries';

-- Orders table enrichments --------------------------------------------------
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS customer_email VARCHAR(255) NOT NULL DEFAULT 'unknown@village.local';

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS subtotal_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS shipping_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS promotion_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS payment_intent_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS metadata JSONB DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS paid_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fulfilled_at TIMESTAMPTZ;

-- Align legacy status values with new enum
UPDATE orders SET status = 'PENDING_PAYMENT' WHERE status = 'pending';
UPDATE orders SET status = 'PROCESSING' WHERE status = 'confirmed';
UPDATE orders SET status = 'PROCESSING' WHERE status = 'processing';
UPDATE orders SET status = 'SHIPPED' WHERE status = 'shipped';
UPDATE orders SET status = 'DELIVERED' WHERE status = 'delivered';
UPDATE orders SET status = 'CANCELLED' WHERE status = 'cancelled';
UPDATE orders SET status = 'REFUNDED' WHERE status = 'refunded';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'chk_orders_status' AND table_name = 'orders'
    ) THEN
        ALTER TABLE orders DROP CONSTRAINT chk_orders_status;
    END IF;
END $$;

ALTER TABLE orders
    ADD CONSTRAINT chk_orders_status CHECK (status IN ('PENDING_PAYMENT','PAID','PROCESSING','SHIPPED','DELIVERED','CANCELLED','REFUNDED'));

COMMENT ON COLUMN orders.subtotal_amount IS 'Sum of line item amounts before discounts/tax';
COMMENT ON COLUMN orders.total_amount IS 'Final amount charged after discounts/tax/shipping';

-- Order line items snapshot fields -----------------------------------------
ALTER TABLE order_line_items
    ADD COLUMN IF NOT EXISTS product_id UUID,
    ADD COLUMN IF NOT EXISTS product_name VARCHAR(500),
    ADD COLUMN IF NOT EXISTS variant_name VARCHAR(500),
    ADD COLUMN IF NOT EXISTS sku VARCHAR(100),
    ADD COLUMN IF NOT EXISTS subtotal NUMERIC(19,4) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS vendor_id UUID,
    ADD COLUMN IF NOT EXISTS commission_rate NUMERIC(5,4),
    ADD COLUMN IF NOT EXISTS metadata JSONB DEFAULT '{}'::jsonb;

COMMENT ON COLUMN order_line_items.subtotal IS 'Computed as unit_price * quantity at order time';

-- Payment intents: align order_id with UUID primary key ---------------------
DROP INDEX IF EXISTS idx_payment_order_id;
ALTER TABLE payment_intents DROP COLUMN IF EXISTS order_id;
ALTER TABLE payment_intents ADD COLUMN order_id UUID;
CREATE INDEX IF NOT EXISTS idx_payment_order_id ON payment_intents(order_id);
