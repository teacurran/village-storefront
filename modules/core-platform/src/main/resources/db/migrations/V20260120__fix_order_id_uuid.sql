-- Fix order_id type mismatch: Orders use UUID but ledger tables use BIGINT
-- Task I5.T5: Gift card + Store credit checkout integration fix

-- Update gift_card_transactions.order_id to UUID
ALTER TABLE gift_card_transactions
    ALTER COLUMN order_id TYPE UUID USING NULL;  -- Cast to NULL for existing rows (no orders yet)

-- Update store_credit_transactions.order_id to UUID
ALTER TABLE store_credit_transactions
    ALTER COLUMN order_id TYPE UUID USING NULL;

-- Update payment_tenders.order_id to UUID
ALTER TABLE payment_tenders
    ALTER COLUMN order_id TYPE UUID USING NULL,
    ALTER COLUMN order_id SET NOT NULL;  -- Keep NOT NULL constraint

COMMENT ON COLUMN gift_card_transactions.order_id IS 'Order UUID where gift card was redeemed';
COMMENT ON COLUMN store_credit_transactions.order_id IS 'Order UUID where store credit was redeemed';
COMMENT ON COLUMN payment_tenders.order_id IS 'Order UUID being paid with this tender';
