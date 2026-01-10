# Checkout Implementation Guide

## Overview

This document provides implementation guidance for the Village Storefront checkout saga orchestrator built in Task I3.T1.

## Implemented Components

### 1. Domain Models

#### Promotion Entity
- **Location**: `data/models/Promotion.java`
- **Features**:
  - Discount types: PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING
  - Validation: expiry dates, usage limits, minimum order requirements
  - Automatic tenant injection via `@PrePersist`
  - Optimistic locking with `@Version`

#### IdempotencyKey Entity
- **Location**: `data/models/IdempotencyKey.java`
- **Features**:
  - Status tracking: PENDING, SUCCESS, FAILED
  - 24-hour TTL with automatic expiry
  - Unique constraint on (tenant_id, key)
  - Caches operation results for duplicate requests

#### Order & OrderLineItem Entities
- **Location**: `data/models/Order.java`, `data/models/OrderLineItem.java`
- **Features**:
  - Order status state machine: PENDING_PAYMENT → PAID → PROCESSING → SHIPPED → DELIVERED
  - Terminal states: CANCELLED, REFUNDED
  - Price snapshots for historical integrity
  - Consignment support (vendor attribution, commission rates)
  - Optimistic locking for concurrency control

### 2. Services

#### IdempotencyService
- **Location**: `services/IdempotencyService.java`
- **Features**:
  - Atomic key acquisition with race condition handling
  - Result caching for idempotent responses
  - Cleanup job for expired keys
  - HTTP 409 Conflict on duplicate PENDING requests

#### CartService (Enhanced)
- **Location**: `services/CartService.java`
- **New Features Added**:
  - `applyPromotion(cartId, promoCode)` - validates and applies discounts
  - `removePromotion(cartId)` - removes applied promotions
  - `getAppliedPromotion(cartId)` - retrieves current promotion
  - `calculateCartTotal(cartId)` - calculates total with discounts
  - Domain event publishing for CART_UPDATED, PROMOTION_APPLIED

#### OrderService
- **Location**: `services/OrderService.java`
- **Features**:
  - `createOrderFromCart()` - converts cart to order with line item snapshots
  - `markOrderPaid()` - transitions to PAID status after payment
  - `updateOrderStatus()` - manages state machine transitions
  - `cancelOrder()` - cancellation with reason tracking
  - Domain event publishing for ORDER_INITIATED, ORDER_PAID, ORDER_CANCELLED, ORDER_STATUS_CHANGED
  - Order number generation: ORD-YYYYMMDD-NNNN format

### 3. Domain Events

All services publish immutable domain events to the `domain_events` table:

| Event Type | Aggregate | Trigger | Payload Fields |
|------------|-----------|---------|----------------|
| CartUpdated | CART | Item added/removed/updated | cartId, userId, sessionId, itemCount |
| PromotionApplied | CART | Promotion code applied | cartId, promotionCode, subtotal, discountAmount |
| OrderInitiated | ORDER | Order created from cart | orderId, orderNumber, status, totals |
| OrderPaid | ORDER | Payment successful | orderId, paymentIntentId, totals |
| OrderStatusChanged | ORDER | Status transition | orderId, oldStatus, newStatus |
| OrderCancelled | ORDER | Order cancelled | orderId, reason |

## Checkout Saga Implementation

### CheckoutSaga Service

- **Location**: `services/CheckoutSaga.java`
- **Highlights**:
  - Enforces the `checkout.order-creation.enabled` kill switch before any mutations.
  - Acquires idempotency keys (60-minute TTL) and replays cached results for retried requests.
  - Validates cart ownership, snapshots items into an `Order` via `OrderService.createOrderFromCart`, and clears the cart after success.
  - Delegates payments to `PaymentService` (Stripe provider) and marks orders `PAID` once the PaymentIntent succeeds.
  - Persists metrics (`checkout.saga.duration`, `checkout.saga.completed{result}`) and structured error payloads for diagnostics.

```java
@Transactional
public CheckoutResult execute(CheckoutRequest request) {
    IdempotencyKey key = idempotencyService.acquire(request.idempotencyKey(), "checkout", 60);

    if (key.isSuccess() && key.result != null) {
        return objectMapper.readValue(key.result, CheckoutResult.class);
    }

    try {
        Cart cart = cartService.getCart(request.cartId()).orElseThrow(...);
        Order order = orderService.createOrderFromCart(cart, request.customerEmail(),
                request.shippingAddress(), request.billingAddress(),
                request.shippingAmount(), request.taxAmount(), request.currency());
        PaymentIntent pi = paymentService.createPaymentIntent(order.totalAmount, order.currency, order.id, true,
                request.idempotencyKey());
        orderService.markOrderPaid(order.id, pi.providerPaymentId);
        cartService.clearCart(cart.id);

        CheckoutResult result = new CheckoutResult(order.id, order.orderNumber, pi.providerPaymentId,
                order.status.name(), order.totalAmount, order.currency, order.paidAt);
        idempotencyService.markSuccess(request.idempotencyKey(), serializeResult(result), 200);
        return result;
    } catch (RuntimeException e) {
        idempotencyService.markFailed(request.idempotencyKey(), serializeError(e), determineStatusCode(e));
        throw e;
    }
}
```

### Stage Mapping (ADR-003 → Implementation)

| Stage | Implementation Detail |
|-------|-----------------------|
| Address Validation & Shipping | Upstream APIs populate normalized addresses and shipping/tax totals before invoking `CheckoutSaga`. |
| Inventory Reservation | Cart → Order snapshot occurs via `OrderService`; inventory hooks will be layered in via `InventoryService`. |
| Order Creation | `OrderService.createOrderFromCart` persists `Order` + `OrderLineItem` rows and emits `OrderInitiated`. |
| Payment Processing | `PaymentService.createPaymentIntent` (Stripe) uses the saga idempotency key to prevent duplicate charges; result stored in `CheckoutResult`. |
| Finalization | `OrderService.markOrderPaid` emits `OrderPaid`, and `CartService.clearCart` removes purchased items. |

### Compensation & Observability

- Failures cancel the partially created order and store a structured error payload on the idempotency row.
- Concurrent requests with the same key while the saga runs throw `IdempotencyConflictException` (HTTP 409).
- Metrics/logs are tagged with `tenant.id`, `order.id`, and `checkout.idempotency_key` for traceability.

## REST API Endpoints

### Cart API (`/api/carts`)

```
GET    /api/carts/current          - Get or create cart for session/user
POST   /api/carts/current/items    - Add item to cart
PATCH  /api/carts/current/items/{id} - Update item quantity
DELETE /api/carts/current/items/{id} - Remove item
POST   /api/carts/current/promotions - Apply promo code
DELETE /api/carts/current/promotions - Remove promo code
GET    /api/carts/current/totals   - Get cart totals
```

### Checkout API (`/api/checkout`)

```
POST /api/checkout/validate-address  - Validate shipping address (Stage 1)
POST /api/checkout/shipping-rates    - Get shipping options (Stages 1-2)
POST /api/checkout/complete          - Execute full checkout saga
  Headers:
    Idempotency-Key: {uuid}
  Body:
    cartId, paymentMethodId, shippingAddress, billingAddress, selectedShippingRate
```

## Testing Requirements

### Integration Tests

1. **Multi-tenant Cart Isolation**
   - Tenant A cannot access Tenant B's cart
   - Cart items scoped by tenant_id
   - Promotion codes tenant-specific

2. **Optimistic Locking**
   - Simulate concurrent cart updates
   - Verify retry logic with exponential backoff
   - Test version conflict detection

3. **Saga Compensation Flows**
   - Payment failure → inventory released, order cancelled
   - Timeout in Stage 2 → use fallback shipping rates
   - Inventory depletion → checkout fails before payment

4. **Idempotency Key Deduplication**
   - Same key returns same result (200 OK)
   - Concurrent requests with same key → 409 Conflict
   - Expired keys allow reuse

5. **Domain Event Persistence**
   - All cart/order events recorded
   - Events queryable by tenant + aggregate
   - Event payloads parseable as JSON

### Coverage Targets

- Line coverage: ≥80% (SonarCloud quality gate)
- Branch coverage: ≥80%
- Focus on error paths and compensation logic

## Observability

### OpenTelemetry Spans

Tag all saga stages with:
- `checkout.stage` - stage name (address_validation, payment, etc.)
- `checkout.idempotency_key` - client-provided key
- `order.id` - order UUID
- `tenant.id` - tenant UUID (auto-added by TenantResolutionFilter)

### Metrics

```
checkout.success_rate - counter (success/failure)
checkout.stage_duration{stage} - histogram
checkout.compensation_triggered{reason} - counter
cart.promotion.applied - counter
```

### Structured Logging

All log statements include:
- `tenantId` - current tenant UUID
- `userId` - authenticated user (if applicable)
- `orderId` - order UUID (in saga)
- `idempotencyKey` - client key (in saga)

## Database Migrations

All schema changes live in `modules/core-platform/src/main/resources/db/migrations/V20260116__checkout_domain.sql`. Key actions:

- Create `promotions` table (tenant scoped, unique per code, optimistic locking metadata).
- Create `idempotency_keys` table with 24-hour TTL, cached results/errors, and operation scoping indexes.
- Enrich `orders` + `order_line_items` tables with subtotal/discount/tax columns, promotion/payment metadata, and shipment timestamps.
- Align `payment_intents.order_id` with UUID primary keys to match the new `Order` aggregate.

Apply via Flyway/Maven: `./mvnw -pl modules/core-platform flyway:migrate`.

## Feature Flags

Add to `feature_flags` table:

```sql
INSERT INTO feature_flags (tenant_id, flag_key, enabled, description)
VALUES
  (NULL, 'checkout.order-creation.enabled', TRUE, 'Emergency kill switch for checkout flow'),
  (NULL, 'payments.stripe.enabled', TRUE, 'Emergency kill switch for Stripe payment processing'),
  (NULL, 'checkout.address-validation.enabled', TRUE, 'Enable USPS/Lob address validation'),
  (NULL, 'checkout.inventory-reservation.enabled', TRUE, 'Enable inventory reservation during checkout');
```

## Security Considerations

1. **Rate Limiting**: Max 5 checkout attempts per minute per session
2. **Price Validation**: Always recalculate totals server-side, never trust client
3. **PCI Compliance**: Never log payment method details or CVV
4. **Promotion Abuse**: Track usage limits, validate expiry dates
5. **Tenant Isolation**: All queries filtered by TenantContext.getCurrentTenantId()

## Deployment Checklist

- [ ] Run database migrations
- [ ] Insert feature flag records
- [ ] Configure Stripe API keys (test + production)
- [ ] Configure USPS/Lob API credentials
- [ ] Set up carrier API access (UPS/FedEx/USPS)
- [ ] Enable OpenTelemetry tracing
- [ ] Configure Prometheus metrics scraping
- [ ] Test feature flag kill switches
- [ ] Load test with concurrent checkout requests
- [ ] Verify idempotency behavior under race conditions
- [ ] Test saga compensation in staging environment

## References

- ADR-003: Checkout Saga Decision
- Architecture Overview: Multi-Tenancy & Domain Events
- Feature Flag Governance: docs/feature_flags/governance.md
- Java Project Standards: docs/java-project-standards.adoc
