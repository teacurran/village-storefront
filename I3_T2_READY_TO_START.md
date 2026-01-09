# Task I3.T2 - Ready to Start

**Date**: January 9, 2026
**Status**: ✅ **ALL DEPENDENCIES SATISFIED**
**Next Task**: I3.T2 - OpenAPI + REST Controllers for Cart/Checkout/Order

---

## Dependency Status

| Dependency | Status | Verified |
|------------|--------|----------|
| I3.T1 (Cart + Checkout Services) | ✅ COMPLETE | January 9, 2026 |
| I1.T4 (API Skeleton) | ✅ COMPLETE | (Pre-existing) |

**Conclusion**: All dependencies are satisfied. Task I3.T2 is ready to begin.

---

## Task I3.T2 Overview

**Description**: Extend OpenAPI + REST controllers for cart/checkout/order endpoints (storefront + admin), including ProblemDetails, rate limits, and webhook callback modeling.

**Deliverables**:
- Documented endpoints for cart operations
- Checkout step endpoints
- Order admin operations
- Success/error handling tests
- Security scope verification

**Acceptance Criteria**:
- ✅ Spec lint + contract tests pass
- ✅ Controllers use TenantContext & feature flags
- ✅ E2E stub uses RestAssured to run sample checkout

---

## Available Resources for I3.T2

### 1. Implemented Services (from I3.T1)

**Cart Operations**:
- `CartService.getOrCreateCartForUser(userId)` - Authenticated carts
- `CartService.getOrCreateCartForSession(sessionId)` - Guest carts
- `CartService.addItemToCart(cartId, variantId, quantity)` - Add/update items
- `CartService.updateCartItemQuantity(cartId, itemId, quantity)` - Update quantities
- `CartService.removeCartItem(cartId, itemId)` - Remove items
- `CartService.applyPromotion(cartId, promoCode)` - Apply discount codes
- `CartService.removePromotion(cartId)` - Remove discounts
- `CartService.calculateCartTotal(cartId)` - Calculate totals with discounts
- `CartService.clearCart(cartId)` - Clear all items

**Order Operations**:
- `OrderService.createOrderFromCart(...)` - Create order from cart
- `OrderService.markOrderPaid(orderId, paymentIntentId)` - Mark as paid
- `OrderService.updateOrderStatus(orderId, newStatus)` - State transitions
- `OrderService.cancelOrder(orderId, reason)` - Cancel with reason
- `OrderService.getOrderLineItems(orderId)` - Get order items

**Checkout Operations**:
- `CheckoutSaga.execute(CheckoutRequest)` - Full checkout flow with idempotency

**Idempotency Operations**:
- `IdempotencyService.acquire(key, operationType, ttlMinutes)` - Acquire key
- `IdempotencyService.markSuccess(key, result, responseCode)` - Mark success
- `IdempotencyService.markFailed(key, error, responseCode)` - Mark failure

### 2. Existing REST Resources (Baseline)

**Already Implemented** (from I3.T1):
- `CartResource.java` - 7 endpoints for cart operations
- `OrderResource.java` - 3 endpoints for order management

**Status**: These resources exist but may need OpenAPI documentation updates.

### 3. Domain Models

**Entities**:
- `Cart` - Shopping cart
- `CartItem` - Cart line items
- `Promotion` - Discount codes
- `Order` - Order aggregate
- `OrderLineItem` - Order items (immutable)
- `IdempotencyKey` - Request deduplication
- `PaymentIntent` - Payment tracking

**DTOs** (may need creation):
- `CartResponse` - Cart with items
- `CartTotalsResponse` - Subtotal, discount, total
- `CheckoutRequest` - Checkout payload
- `CheckoutResponse` - Order result
- `OrderResponse` - Order details
- `OrderListResponse` - Paginated orders

### 4. Feature Flags

**Required Flags** (to be checked in controllers):
- `checkout.order-creation.enabled` - Checkout kill switch
- `payments.stripe.enabled` - Payment kill switch
- `checkout.address-validation.enabled` - Address validation toggle
- `checkout.inventory-reservation.enabled` - Inventory toggle

### 5. Documentation References

**Implementation Guide**: `docs/checkout-implementation-guide.md` (270 lines)
- Section 3: CheckoutSaga Architecture
- Section 4: REST API Endpoints (specifications)
- Section 5: Integration Test Requirements

**ADR-003**: `docs/adr/ADR-003-checkout-saga.md`
- Idempotency requirements
- Feature flag usage
- Compensation strategies

---

## OpenAPI Endpoints to Document/Implement

### Storefront Endpoints (Public)

**Cart Operations** (`/api/carts/...`):
- `GET /api/carts/current` - Get or create cart for session/user
- `POST /api/carts/current/items` - Add item to cart
- `PATCH /api/carts/current/items/{id}` - Update item quantity
- `DELETE /api/carts/current/items/{id}` - Remove item from cart
- `POST /api/carts/current/promotions` - Apply promotion code
- `DELETE /api/carts/current/promotions` - Remove promotion
- `GET /api/carts/current/totals` - Get cart totals (subtotal, discount, total)

**Checkout Operations** (`/api/checkout/...`):
- `POST /api/checkout/validate-address` - Validate shipping address (USPS/Lob)
- `POST /api/checkout/shipping-rates` - Get shipping rates (UPS/FedEx/USPS)
- `POST /api/checkout/complete` - Complete checkout (idempotency key required)

**Order Operations** (`/api/orders/...`):
- `GET /api/orders` - List user's orders (paginated)
- `GET /api/orders/{id}` - Get order details
- `POST /api/orders/{id}/cancel` - Cancel order

### Admin Endpoints (Authenticated)

**Cart Management** (`/admin/carts/...`):
- `GET /admin/carts` - List all tenant carts (paginated, filterable)
- `GET /admin/carts/{id}` - Get cart details
- `DELETE /admin/carts/{id}` - Delete cart

**Order Management** (`/admin/orders/...`):
- `GET /admin/orders` - List all tenant orders (paginated, filterable)
- `GET /admin/orders/{id}` - Get order details
- `PATCH /admin/orders/{id}/status` - Update order status
- `POST /admin/orders/{id}/refund` - Issue refund
- `GET /admin/orders/{id}/events` - Get order event history

**Promotion Management** (`/admin/promotions/...`):
- `GET /admin/promotions` - List promotions
- `POST /admin/promotions` - Create promotion
- `PATCH /admin/promotions/{id}` - Update promotion
- `DELETE /admin/promotions/{id}` - Delete promotion

### Webhook Endpoints (Stripe)

**Payment Webhooks** (`/webhooks/...`):
- `POST /webhooks/stripe` - Stripe webhook receiver (payment intents, refunds)

---

## Testing Requirements for I3.T2

### 1. Contract Tests (OpenAPI Validation)

**Tools**: OpenAPI spec linter, Schemathesis
**Coverage**:
- All request/response schemas valid
- Required fields enforced
- Enum values validated
- Error responses match ProblemDetails format

### 2. Controller Tests (RestAssured)

**Storefront Tests**:
- Cart CRUD operations with tenant isolation
- Promotion application success/failure scenarios
- Checkout flow end-to-end (address validation → shipping rates → complete)
- Idempotency key handling (duplicate requests return cached results)

**Admin Tests**:
- Order status transitions (valid/invalid state changes)
- Refund flow (full/partial refunds)
- Promotion CRUD with tenant scoping
- Pagination and filtering

### 3. Security Tests

**Tenant Isolation**:
- Tenant A cannot access Tenant B's carts/orders
- Admin endpoints require authentication
- Session-based carts accessible by session only

**Feature Flags**:
- Checkout disabled returns HTTP 503
- Payment disabled returns appropriate error

**Rate Limiting** (if implemented):
- Checkout endpoint limits enforced (e.g., 5 requests/min)

---

## Implementation Strategy for I3.T2

### Phase 1: OpenAPI Specification
1. Extend `openapi.yaml` with cart/checkout/order endpoints
2. Define request/response DTOs
3. Document error responses (ProblemDetails format)
4. Add webhook endpoint specifications

### Phase 2: REST Controller Implementation
1. Update `CartResource.java` with OpenAPI annotations
2. Create `CheckoutResource.java` for checkout flow
3. Update `OrderResource.java` with admin operations
4. Create admin controllers (`AdminCartResource.java`, `AdminOrderResource.java`, `AdminPromotionResource.java`)
5. Create `StripeWebhookResource.java` for webhook handling

### Phase 3: Error Handling
1. Implement ProblemDetails exception mapper
2. Add validation error responses (HTTP 400)
3. Add feature flag disabled responses (HTTP 503)
4. Add idempotency conflict responses (HTTP 409)

### Phase 4: Testing
1. Write contract tests (OpenAPI schema validation)
2. Write controller integration tests (RestAssured)
3. Write security tests (tenant isolation, auth)
4. Write E2E checkout test (address → rates → complete)

### Phase 5: Documentation
1. Update `docs/checkout-implementation-guide.md` with endpoint details
2. Add curl examples for each endpoint
3. Document webhook payload structures
4. Update README with API usage examples

---

## Success Criteria for I3.T2

- ✅ OpenAPI spec validates with spec linter
- ✅ All endpoints documented with request/response schemas
- ✅ Controllers use `TenantContext.getCurrentTenantId()` for isolation
- ✅ Controllers check feature flags before operations
- ✅ ProblemDetails format used for all error responses
- ✅ Idempotency-Key header supported on checkout endpoint
- ✅ Contract tests pass (schema validation)
- ✅ Integration tests pass (RestAssured E2E)
- ✅ Security tests pass (tenant isolation, auth)
- ✅ E2E checkout test demonstrates full flow

---

## Next Steps

1. **Review OpenAPI Skeleton** - Check existing `openapi.yaml` structure
2. **Plan Endpoint Specifications** - Define schemas for each endpoint
3. **Implement Controllers** - Build REST resources with OpenAPI annotations
4. **Write Tests** - Contract, integration, security, E2E
5. **Update Documentation** - Add endpoint usage examples

**Estimated Effort**: 3-5 days
**Complexity**: Medium (builds on complete I3.T1 foundation)
**Blockers**: None (all dependencies satisfied)

---

## References

- **Task Definition**: `.codemachine/artifacts/tasks/tasks_I3.json` (I3.T2)
- **Implementation Guide**: `docs/checkout-implementation-guide.md`
- **ADR-003**: `docs/adr/ADR-003-checkout-saga.md`
- **I3.T1 Completion Report**: `TASK_I3_T1_COMPLETE.md`
- **I3.T1 Verification Report**: `TASK_I3_T1_VERIFICATION_REPORT.md`

---

**Ready to Begin**: ✅ YES
**Recommended Start Date**: January 9, 2026 (immediately)
