-- Migration: V20260130__loyalty_redemption_reservations.sql
-- Description: Create loyalty_redemption_reservations table for cart holds
--              including optimistic locking + tenant RLS policies.

CREATE TABLE IF NOT EXISTS loyalty_redemption_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    member_id UUID NOT NULL REFERENCES loyalty_members(id) ON DELETE CASCADE,
    cart_id UUID NOT NULL,
    order_id UUID,
    points_reserved INTEGER NOT NULL CHECK (points_reserved > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active','released','expired','consumed')),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '15 minutes'),
    idempotency_key VARCHAR(255),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_loyalty_reservations_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_loyalty_reservations_member ON loyalty_redemption_reservations(member_id);
CREATE INDEX idx_loyalty_reservations_cart ON loyalty_redemption_reservations(cart_id);
CREATE INDEX idx_loyalty_reservations_expires ON loyalty_redemption_reservations(expires_at);

COMMENT ON TABLE loyalty_redemption_reservations IS 'Temporary holds on loyalty points while a cart is in checkout.';
COMMENT ON COLUMN loyalty_redemption_reservations.points_reserved IS 'Points reserved for redemption while checkout completes.';
COMMENT ON COLUMN loyalty_redemption_reservations.idempotency_key IS 'Idempotency key tying reservations to checkout retries.';

ALTER TABLE loyalty_redemption_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE loyalty_redemption_reservations FORCE ROW LEVEL SECURITY;
CREATE POLICY loyalty_reservations_isolation_policy ON loyalty_redemption_reservations
    USING (tenant_id = current_setting('app.current_tenant')::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant')::uuid);
