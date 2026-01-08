-- Gift Card and Store Credit Tables
-- Task I4.T6: Gift card/store credit modules with checkout/POS integration
-- ADR-001: Tenant-scoped data with RLS policies
-- References: loyalty_transactions pattern, payment_intents audit columns

-- ========================================
-- Gift Cards
-- ========================================

CREATE TABLE gift_cards (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code VARCHAR(32) NOT NULL,  -- Secure alphanumeric code (e.g., XXXX-XXXX-XXXX-XXXX)
    code_hash VARCHAR(128) NOT NULL,  -- SHA-256 hash for secure lookups
    initial_balance NUMERIC(12, 2) NOT NULL,
    current_balance NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, redeemed, expired, cancelled

    -- Purchaser/recipient info
    purchaser_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    purchaser_email VARCHAR(255),
    recipient_email VARCHAR(255),
    recipient_name VARCHAR(255),
    personal_message TEXT,

    -- Lifecycle tracking
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    fully_redeemed_at TIMESTAMPTZ,

    -- Order association
    source_order_id BIGINT,  -- Order that purchased this gift card

    -- Audit fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_gift_cards_status CHECK (status IN ('active', 'redeemed', 'expired', 'cancelled')),
    CONSTRAINT chk_gift_cards_balance CHECK (current_balance >= 0 AND current_balance <= initial_balance),
    CONSTRAINT chk_gift_cards_initial_balance CHECK (initial_balance > 0),
    CONSTRAINT uq_gift_cards_code_hash UNIQUE (code_hash)  -- Global uniqueness for security
);

CREATE INDEX idx_gift_cards_tenant_id ON gift_cards(tenant_id);
CREATE INDEX idx_gift_cards_code_hash ON gift_cards(code_hash);  -- Fast lookups
CREATE INDEX idx_gift_cards_status ON gift_cards(status) WHERE status = 'active';
CREATE INDEX idx_gift_cards_expires_at ON gift_cards(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_gift_cards_purchaser_user_id ON gift_cards(purchaser_user_id) WHERE purchaser_user_id IS NOT NULL;

COMMENT ON TABLE gift_cards IS 'Gift cards issued by tenants for customer gifting or promotional purposes';
COMMENT ON COLUMN gift_cards.code IS 'Display code shown to customer (not indexed for security)';
COMMENT ON COLUMN gift_cards.code_hash IS 'SHA-256 hash of code for secure lookups without exposing raw codes';
COMMENT ON COLUMN gift_cards.status IS 'active: usable; redeemed: fully used; expired: past expiration; cancelled: voided by admin';

-- ========================================
-- Gift Card Transactions
-- ========================================

CREATE TABLE gift_card_transactions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    gift_card_id BIGINT NOT NULL REFERENCES gift_cards(id) ON DELETE CASCADE,
    order_id BIGINT,  -- Order where redemption occurred
    amount NUMERIC(12, 2) NOT NULL,  -- Positive for load/refund, negative for redemption
    transaction_type VARCHAR(20) NOT NULL,  -- issued, redeemed, refunded, adjusted, expired
    balance_after NUMERIC(12, 2) NOT NULL,

    -- Idempotency and metadata
    idempotency_key VARCHAR(255),  -- Prevents duplicate redemptions
    reason TEXT,
    metadata JSONB DEFAULT '{}',

    -- POS offline support
    pos_device_id BIGINT,  -- If redeemed via POS offline
    offline_synced_at TIMESTAMPTZ,  -- When offline txn was synced

    -- Audit fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_gift_card_txn_type CHECK (transaction_type IN ('issued', 'redeemed', 'refunded', 'adjusted', 'expired')),
    CONSTRAINT chk_gift_card_txn_balance CHECK (balance_after >= 0),
    CONSTRAINT uq_gift_card_txn_idempotency UNIQUE (idempotency_key) WHERE idempotency_key IS NOT NULL
);

CREATE INDEX idx_gift_card_txn_tenant_id ON gift_card_transactions(tenant_id);
CREATE INDEX idx_gift_card_txn_gift_card_id ON gift_card_transactions(gift_card_id);
CREATE INDEX idx_gift_card_txn_order_id ON gift_card_transactions(order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_gift_card_txn_created_at ON gift_card_transactions(created_at DESC);
CREATE INDEX idx_gift_card_txn_idempotency_key ON gift_card_transactions(idempotency_key) WHERE idempotency_key IS NOT NULL;

COMMENT ON TABLE gift_card_transactions IS 'Ledger of all gift card balance mutations';
COMMENT ON COLUMN gift_card_transactions.idempotency_key IS 'Checkout-provided key to prevent duplicate redemptions during retries';

-- ========================================
-- Store Credit
-- ========================================

CREATE TABLE store_credit_accounts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    balance NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, suspended, closed

    -- Metadata
    notes TEXT,
    metadata JSONB DEFAULT '{}',

    -- Audit fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_store_credit_balance CHECK (balance >= 0),
    CONSTRAINT chk_store_credit_status CHECK (status IN ('active', 'suspended', 'closed')),
    CONSTRAINT uq_store_credit_tenant_user UNIQUE (tenant_id, user_id)  -- One account per user per tenant
);

CREATE INDEX idx_store_credit_tenant_id ON store_credit_accounts(tenant_id);
CREATE INDEX idx_store_credit_user_id ON store_credit_accounts(user_id);
CREATE INDEX idx_store_credit_status ON store_credit_accounts(status) WHERE status = 'active';

COMMENT ON TABLE store_credit_accounts IS 'Store credit balances for customers (refunds, loyalty conversions, promos)';
COMMENT ON COLUMN store_credit_accounts.status IS 'active: usable; suspended: temporarily blocked; closed: permanently disabled';

-- ========================================
-- Store Credit Transactions
-- ========================================

CREATE TABLE store_credit_transactions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES store_credit_accounts(id) ON DELETE CASCADE,
    order_id BIGINT,  -- Order associated with transaction
    gift_card_id BIGINT REFERENCES gift_cards(id) ON DELETE SET NULL,  -- If credit from gift card conversion
    amount NUMERIC(12, 2) NOT NULL,  -- Positive for credit, negative for debit
    transaction_type VARCHAR(20) NOT NULL,  -- issued, redeemed, refunded, adjusted, converted, expired
    balance_after NUMERIC(12, 2) NOT NULL,

    -- Idempotency and metadata
    idempotency_key VARCHAR(255),  -- Prevents duplicate redemptions
    reason TEXT,
    metadata JSONB DEFAULT '{}',

    -- POS offline support
    pos_device_id BIGINT,
    offline_synced_at TIMESTAMPTZ,

    -- Audit fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,

    CONSTRAINT chk_store_credit_txn_type CHECK (transaction_type IN ('issued', 'redeemed', 'refunded', 'adjusted', 'converted', 'expired')),
    CONSTRAINT chk_store_credit_txn_balance CHECK (balance_after >= 0),
    CONSTRAINT uq_store_credit_txn_idempotency UNIQUE (idempotency_key) WHERE idempotency_key IS NOT NULL
);

CREATE INDEX idx_store_credit_txn_tenant_id ON store_credit_transactions(tenant_id);
CREATE INDEX idx_store_credit_txn_account_id ON store_credit_transactions(account_id);
CREATE INDEX idx_store_credit_txn_order_id ON store_credit_transactions(order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_store_credit_txn_gift_card_id ON store_credit_transactions(gift_card_id) WHERE gift_card_id IS NOT NULL;
CREATE INDEX idx_store_credit_txn_created_at ON store_credit_transactions(created_at DESC);
CREATE INDEX idx_store_credit_txn_idempotency_key ON store_credit_transactions(idempotency_key) WHERE idempotency_key IS NOT NULL;

COMMENT ON TABLE store_credit_transactions IS 'Ledger of all store credit balance mutations';
COMMENT ON COLUMN store_credit_transactions.transaction_type IS 'converted: gift card balance moved to store credit';

-- ========================================
-- Checkout Integration Tables
-- ========================================

-- Payment tender split tracking (for multi-tender checkouts)
CREATE TABLE payment_tenders (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    order_id BIGINT NOT NULL,  -- Order being paid
    tender_type VARCHAR(20) NOT NULL,  -- card, gift_card, store_credit, loyalty_points
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',

    -- References to tender sources
    payment_intent_id BIGINT REFERENCES payment_intents(id) ON DELETE SET NULL,  -- Stripe/card payment
    gift_card_id BIGINT REFERENCES gift_cards(id) ON DELETE SET NULL,
    store_credit_account_id BIGINT REFERENCES store_credit_accounts(id) ON DELETE SET NULL,
    loyalty_transaction_id UUID,  -- Reference to loyalty_transactions if points used

    -- Transaction tracking
    transaction_id BIGINT,  -- FK to respective transaction table
    status VARCHAR(20) NOT NULL DEFAULT 'pending',  -- pending, authorized, captured, failed, refunded

    -- Audit fields
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_payment_tenders_tender_type CHECK (tender_type IN ('card', 'gift_card', 'store_credit', 'loyalty_points')),
    CONSTRAINT chk_payment_tenders_status CHECK (status IN ('pending', 'authorized', 'captured', 'failed', 'refunded')),
    CONSTRAINT chk_payment_tenders_amount CHECK (amount > 0)
);

CREATE INDEX idx_payment_tenders_tenant_id ON payment_tenders(tenant_id);
CREATE INDEX idx_payment_tenders_order_id ON payment_tenders(order_id);
CREATE INDEX idx_payment_tenders_gift_card_id ON payment_tenders(gift_card_id) WHERE gift_card_id IS NOT NULL;
CREATE INDEX idx_payment_tenders_store_credit_account_id ON payment_tenders(store_credit_account_id) WHERE store_credit_account_id IS NOT NULL;

COMMENT ON TABLE payment_tenders IS 'Multi-tender payment splits per order (supports gift card + credit card, etc.)';
COMMENT ON COLUMN payment_tenders.tender_type IS 'Type of payment method used for this portion of the order total';
