# ADR-003: Checkout Saga Pattern & Adapter Contracts

**Status:** Accepted
**Date:** 2026-01-03
**Deciders:** Architecture Team, Tech Lead
**Consulted:** Payment Integration Lead, Shipping Domain Expert
**Informed:** Engineering Team, Product Management

---

## Context

The Village Storefront checkout flow is a critical business transaction that must coordinate multiple external systems and internal services:

1. **Address Validation:** Verify and normalize shipping addresses using USPS/Lob.com APIs
2. **Shipping Rate Calculation:** Fetch real-time rates from carrier APIs (UPS, FedEx, USPS)
3. **Inventory Management:** Reserve stock to prevent overselling
4. **Payment Processing:** Charge customer via Stripe payment intents
5. **Order Persistence:** Create order records with line items, shipping, and payment details

### Technical Context

From `docs/java-project-standards.adoc` and `docs/architecture_overview.md`:
- **Framework:** Quarkus 3.17+ (CDI, Panache ORM, REST)
- **Database:** PostgreSQL 17 (tenant-scoped data per ADR-001)
- **Payment Provider:** Stripe (v29.5.0 Java SDK)
- **Caching:** Caffeine in-memory cache (no Redis dependency)
- **Observability:** OpenTelemetry tracing, Prometheus metrics

### Problem Statement

Checkout involves multiple distributed operations that can fail partially:
- **Network failures:** Carrier API timeout after payment charged
- **Inventory conflicts:** Stock depleted between preview and commit
- **Payment failures:** Declined card after inventory reserved
- **Data inconsistency:** Order created but payment webhook lost

Without proper orchestration and compensation logic, these failures create:
- **Orphaned reservations:** Inventory locked indefinitely
- **Duplicate charges:** Retries without idempotency
- **Data corruption:** Orders in inconsistent states (e.g., `PROCESSING` but payment failed)

### Business Requirements

From Section 7 (Requirements):
- **Idempotency:** Retries must not create duplicate orders (RFC 7231 compliance)
- **Inventory Accuracy:** No overselling (99.9% accuracy SLA)
- **Payment Security:** PCI DSS scope limited to Stripe (no card storage)
- **User Experience:** <500ms p95 latency for checkout preview, <2s for commit
- **Resilience:** Graceful degradation (e.g., fallback shipping rates on carrier API failure)

### Competitive Landscape

From competitor research:
- **Shopify Checkout:** Saga-style orchestration with inventory holds, Stripe idempotency
- **Medusa.js:** Event-driven compensation (Redis Pub/Sub)
- **Spree Commerce:** Manual rollback via ActiveRecord transactions (limited to DB scope)

**Decision Driver:** Shopify's proven saga pattern balances consistency, performance, and operational simplicity without requiring distributed transaction managers or event sourcing infrastructure.

---

## Decision

We will implement a **choreographed saga pattern** for checkout orchestration with:

1. **Explicit saga stages** with compensation points
2. **Idempotency keys** for duplicate request detection
3. **Integration adapters** abstracting external service contracts
4. **Caching strategies** for performance and resilience
5. **Observability hooks** for tracing and metrics

### 1. Saga Stage Breakdown

The checkout saga consists of six sequential stages:

| Stage | Operation | Compensation | Failure Mode | Metric/Span |
|-------|-----------|--------------|--------------|-------------|
| **1. Address Validation** | Normalize shipping address via USPS/Lob API | None (read-only) | Return validation errors to client | `CheckoutSaga.addressValidation` |
| **2. Shipping Rate Fetch** | Get carrier rates (UPS/FedEx/USPS) | None (cached, fallback on timeout) | Return fallback rate + warning | `ShippingRateAdapter.quote`, `checkout.rate_cache_hit_ratio` |
| **3. Address Re-validation** | Defensive check on commit (prevent client tampering) | None | Abort checkout, return 400 | `CheckoutSaga.addressRevalidation` |
| **4. Inventory Reservation** | Insert `inventory_reservations` (30-min expiry) | Delete reservation rows | Abort if out of stock | `CheckoutSaga.inventoryReservation` |
| **5. Order Creation** | Insert `orders` (status: `PENDING_PAYMENT`) | Update status to `CANCELLED` | Orphaned order cleaned by cron | `CheckoutSaga.orderCreation` |
| **6. Payment Processing** | Stripe PaymentIntent (capture=true) | Release inventory, cancel order | Return 402 Payment Required | `StripeIntentService.commit` |

**Critical Failure Point:** Stage 6 (Payment) is the point of no return. If payment succeeds but response is lost, Stripe webhooks reconcile order status asynchronously.

### 2. Idempotency Mechanism

Per `api/v1/openapi.yaml#/components/parameters/IdempotencyKey`:

```java
// Table schema
CREATE TABLE idempotency_keys (
    key VARCHAR(36) PRIMARY KEY,  -- UUID v4
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    status VARCHAR(20) NOT NULL,  -- PENDING, SUCCESS, FAILED
    order_id UUID REFERENCES orders(id),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT NOW() + INTERVAL '24 hours'
);
CREATE INDEX idx_idempotency_tenant ON idempotency_keys(tenant_id, created_at);
```

**Workflow:**
1. Client sends `X-Idempotency-Key: {UUID}` header (generated client-side)
2. CheckoutOrchestrator queries `idempotency_keys` by key + tenant
   - If found with `SUCCESS`: Return HTTP 409 Conflict + `Location: /orders/{orderId}`
   - If found with `PENDING`: Continue processing (lost response retry)
   - If found with `FAILED`: Allow retry (return previous error details)
   - If not found: Insert row with `status=PENDING`
3. On saga completion: Update row with `status=SUCCESS|FAILED`
4. Cron job purges expired keys (>24 hours old)

**Stripe Idempotency:** Stripe API calls use `order_id` as idempotency key (separate from client-provided key) to ensure payment operations are idempotent even if order creation retries.

### 3. Integration Adapter Contracts

All external service integrations implemented via CDI interfaces:

#### AddressValidationAdapter

```java
package villagecompute.storefront.integration;

/**
 * Adapter for address verification and normalization services.
 * Implementations: USPSAdapter, LobAdapter.
 */
public interface AddressValidationAdapter {

    /**
     * Validate and normalize shipping address.
     * @param address Raw address from user input
     * @return Validated address with confidence score
     * @throws AddressValidationException if address cannot be verified
     */
    ValidatedAddress validate(Address address) throws AddressValidationException;

    /**
     * Suggested corrections for ambiguous addresses.
     * @param address Partial/ambiguous address
     * @return List of possible matches with confidence scores
     */
    List<AddressSuggestion> suggest(Address address);
}

public record ValidatedAddress(
    Address normalized,
    ConfidenceLevel confidence,  // HIGH, MEDIUM, LOW
    Map<String, String> metadata  // Carrier routing codes, etc.
) {}

public enum ConfidenceLevel { HIGH, MEDIUM, LOW }
```

**Error Handling:**
- Timeout: 3s, retry 1x with exponential backoff
- Fallback: If all attempts fail, proceed with original address + flag order for manual review
- Cache: No caching (addresses must be validated real-time for accuracy)

#### CarrierRateAdapter

```java
package villagecompute.storefront.integration;

/**
 * Adapter for fetching shipping rates from carrier APIs.
 * Implementations: UPSAdapter, FedExAdapter, USPSAdapter, EasyPostAdapter (aggregator).
 */
public interface CarrierRateAdapter {

    /**
     * Fetch shipping rates for packages to destination.
     * @param request Rate request (origin, destination, packages, service level)
     * @return Available rates sorted by cost (ascending)
     * @throws CarrierApiException if all carriers fail
     */
    List<ShippingRate> getRates(RateRequest request) throws CarrierApiException;

    /**
     * Get carrier service level details (name, ETA).
     */
    CarrierService getService(String carrierId, String serviceCode);
}

public record ShippingRate(
    String carrierId,       // "ups", "fedex", "usps"
    String serviceCode,     // "GROUND", "2DAY", "OVERNIGHT"
    Money cost,             // Shipping cost
    LocalDate estimatedDelivery,
    Map<String, String> metadata  // Tracking, insurance, etc.
) {}

public record RateRequest(
    Address origin,
    Address destination,
    List<Package> packages,
    String requestedServiceLevel  // Optional: "ground", "express", "overnight"
) {}
```

**Error Handling & Caching:**
- **Timeout:** 5s per carrier, parallel requests (max 3 carriers)
- **Retry:** 2 attempts with 500ms backoff
- **Fallback:** If all carriers timeout/fail:
  1. Check Caffeine cache for recent rates (same destination + weight bracket)
  2. Else: Return tenant's default fallback rate from `tenant_settings.fallback_shipping_rate`
  3. Flag order with `shipping_estimate_only=true` for manual review
- **Cache Key:** `Hash(origin ZIP, destination ZIP, total weight, service level)`
- **Cache TTL:** 15 minutes (balances freshness vs. API cost)
- **Cache Invalidation:** Manual purge if carrier announces rate changes

**Observability:**
- Metric: `checkout.rate_cache_hit_ratio` (target: >70%)
- Metric: `checkout.shipping_api_errors` (alert if >5% of requests fail)
- Span: `ShippingRateAdapter.quote` with attributes: `carrier.id`, `cache.hit`, `fallback.used`

### 4. Compensation Strategy

When stage N fails, compensate stages N-1 down to 1:

```
Stage 6 (Payment) FAILED:
  → Compensate Stage 5: UPDATE orders SET status='CANCELLED', cancellation_reason='payment_failed'
  → Compensate Stage 4: DELETE FROM inventory_reservations WHERE id IN (...)
  → Emit audit event: checkout.payment.failed
  → Return HTTP 402 Payment Required (ProblemDetails)

Stage 4 (Inventory) FAILED (out of stock):
  → Compensate Stage 3: None (read-only)
  → Return HTTP 400 Bad Request (ProblemDetails: out-of-stock)

Stage 2 (Shipping) FAILED (all carriers timeout):
  → Compensate Stage 1: None (read-only)
  → Fallback to default rate + warning flag
  → Continue checkout preview with estimated shipping
```

**Audit Trail:**
All compensation actions logged to `audit_events` table (immutable append-only):
```sql
INSERT INTO audit_events (
    tenant_id, event_type, entity_type, entity_id,
    user_id, metadata, created_at
) VALUES (
    ?, 'checkout.compensation.inventory_released', 'Order', ?,
    ?, '{"reservation_ids": [...], "reason": "payment_failed"}', NOW()
);
```

### 5. Stripe Integration Details

Per Section 5 (API Contracts) and Architecture Overview Section 3.2.7:

**Payment Intent Flow:**
1. CheckoutOrchestrator creates Stripe PaymentIntent with:
   - `amount`: Order total in cents
   - `currency`: Tenant default (USD, EUR, etc.)
   - `payment_method`: Client-provided payment method ID (from Stripe Elements)
   - `idempotency_key`: `order_{orderId}` (Stripe-side deduplication)
   - `metadata`: `{tenant_id, order_id, order_number}`
2. Stripe SDK captures payment immediately (`capture_method: automatic`)
3. On success: Store `payment_intent_id` in `orders.payment_intent_id`
4. On failure: Return `decline_code` + error message to client

**Webhook Reconciliation:**
Stripe webhooks (`payment_intent.succeeded`, `payment_intent.payment_failed`) reconcile orphaned orders:
- Find order by `metadata.order_id`
- If order status = `PENDING_PAYMENT` and webhook = `succeeded`:
  - Update order status to `PROCESSING`
  - Commit inventory reservations
  - Enqueue fulfillment jobs
- If webhook = `payment_failed`:
  - Trigger compensation saga (cancel order, release inventory)

**Webhook Idempotency:**
Stripe sends `Stripe-Signature` header (HMAC verification):
```java
@Path("/webhooks/stripe")
public class StripeWebhookResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response handleWebhook(
        @HeaderParam("Stripe-Signature") String signature,
        String payload
    ) {
        Event event = Webhook.constructEvent(payload, signature, webhookSecret);

        // Check if event already processed
        if (webhookEventRepository.existsByEventId(event.getId())) {
            return Response.ok().build();  // Idempotent: already handled
        }

        // Process event...
        webhookEventRepository.recordEvent(event.getId(), event.getType());
        return Response.ok().build();
    }
}
```

**Error Handling:**
- Stripe API timeout: 10s (longer than carrier APIs due to payment criticality)
- Retry: Automatic via Stripe SDK retry logic (exponential backoff, max 3 attempts)
- Fallback: No fallback (payment failures are terminal; client must retry)

### 6. Observability & Monitoring

Per `docs/architecture_overview.md` Section 5 (Observability):

**Trace Spans:**
All saga stages wrapped in OpenTelemetry spans:
```java
@WithSpan("CheckoutSaga.addressValidation")
public ValidatedAddress validateAddress(Address address) {
    Span.current().setAttribute("address.country", address.country());
    Span.current().setAttribute("tenant.id", TenantContext.getCurrentTenantId().toString());
    // ... validation logic
}
```

**Span Hierarchy:**
```
StorefrontController.checkout (root span)
  ├─ CheckoutSaga.addressValidation
  ├─ ShippingRateAdapter.quote
  │   ├─ UPSAdapter.getRates
  │   ├─ FedExAdapter.getRates
  │   └─ USPSAdapter.getRates
  ├─ CheckoutSaga.inventoryReservation
  └─ StripeIntentService.commit
      └─ StripeAPI.createPaymentIntent
```

**Metrics (Prometheus):**
- `checkout_address_validation_latency` (histogram, ms)
- `checkout_rate_cache_hit_ratio` (gauge, 0-1)
- `checkout_shipping_api_errors` (counter, by carrier)
- `checkout_payment_failed` (counter, by decline_code)
- `checkout_saga_duration` (histogram, ms, by stage)

**Logs:**
Structured JSON logs (SLF4J + Logback):
```json
{
  "timestamp": "2026-01-03T12:34:56.789Z",
  "level": "INFO",
  "logger": "CheckoutOrchestrator",
  "message": "Saga stage completed",
  "tenant_id": "uuid",
  "order_id": "uuid",
  "stage": "inventory_reservation",
  "duration_ms": 45,
  "trace_id": "abc123",
  "span_id": "def456"
}
```

### 7. Textual Saga Walkthrough

**Happy Path: Successful Checkout**
1. **Client submits checkout:** `POST /checkout/commit` with idempotency key, payment method, shipping address
2. **Idempotency check:** Query DB by key; if duplicate, return 409 Conflict with existing order
3. **Stage 1 - Address Validation:** Call USPS API to normalize address (e.g., "123 Main St" → "123 MAIN ST APT 2B")
   - Success: Store normalized address
   - Failure: Return 400 with suggestions
4. **Stage 2 - Shipping Rate Fetch:** Check cache; if miss, call UPS/FedEx/USPS APIs in parallel
   - Success: Cache rates (15 min TTL), select cheapest
   - Timeout: Return fallback rate + warning flag
5. **Stage 3 - Address Re-validation:** Defensive check (prevent client-side tampering between preview and commit)
6. **Stage 4 - Inventory Reservation:** Begin DB transaction, lock `inventory_levels` rows, insert `inventory_reservations` (30-min expiry)
   - Success: Reservations created
   - Failure: Return 400 out-of-stock
7. **Stage 5 - Order Creation:** Insert `orders` (status: `PENDING_PAYMENT`), insert `order_items`
8. **Stage 6 - Payment Processing:** Call Stripe PaymentIntent API with order total + payment method
   - Success: Update order status to `PROCESSING`, commit reservations, enqueue jobs (email, shipping label)
   - Failure: **COMPENSATE** → cancel order, release reservations, return 402 Payment Required

**Compensation Path: Payment Declined**
1. Stages 1-5 succeed (address valid, inventory reserved, order created)
2. Stage 6 fails: Stripe returns `decline_code: insufficient_funds`
3. **Compensation sequence:**
   - Log audit event: `checkout.payment.failed` with decline code
   - Release inventory: `DELETE FROM inventory_reservations WHERE order_id = ?`
   - Cancel order: `UPDATE orders SET status='CANCELLED', cancellation_reason='payment_declined'`
   - Update idempotency key: `status=FAILED, error_message='Card declined: insufficient_funds'`
4. Return `HTTP 402 Payment Required` (ProblemDetails) to client

**Idempotency Retry: Lost Response**
1. Client submits checkout with key `K1`, payment succeeds, but network timeout (response lost)
2. Client retries with **same key** `K1`
3. CheckoutOrchestrator queries idempotency table:
   - If status = `PENDING`: Re-query Stripe PaymentIntent by `order_id` (Stripe returns cached response)
   - If status = `SUCCESS`: Return `HTTP 409 Conflict` with `Location: /orders/{orderId}` header
4. Client receives confirmation, redirects to order page

---

## Rationale

### Why Saga Pattern over Distributed Transactions?

| Criterion | Saga Pattern | 2PC/Distributed TX | Analysis |
|-----------|--------------|-------------------|----------|
| **Complexity** | ✅ Sequential stages with explicit compensations | ❌ Requires transaction coordinator (XA, JTA) | Saga simpler for 6-stage flow |
| **Performance** | ✅ No locks held across external APIs | ❌ Locks block until all participants vote | Saga avoids long-running locks |
| **Resilience** | ✅ Partial failure handled gracefully | ❌ Single participant failure aborts all | Saga allows fallback (e.g., cached rates) |
| **External APIs** | ✅ Works with non-transactional systems (Stripe, UPS) | ❌ Requires all participants support 2PC | Stripe does not support 2PC |
| **Observability** | ✅ Explicit span per stage | ⚠️ Black-box transaction manager | Saga tracing clearer |

**Decision:** Saga pattern is the only viable choice given external API constraints (Stripe, carrier APIs do not support distributed transactions).

### Why Choreographed Saga (Orchestrator) over Event-Driven?

**Choreographed (Chosen):**
- Single `CheckoutOrchestrator` class owns saga execution
- Sequential stage invocation (`validate → fetch rates → reserve → pay`)
- Compensation logic co-located with forward logic

**Event-Driven (Rejected):**
- Publish domain events (`AddressValidated`, `InventoryReserved`, etc.)
- Each service subscribes and triggers next stage
- Compensation via compensating events (`InventoryReservationCancelled`)

**Why Choreographed Wins:**
- **Simplicity:** Saga flow readable in single class (easier debugging)
- **No Event Infrastructure:** Avoids Redis/Kafka dependency (deferred to Phase 2)
- **Atomic Compensation:** Rollback logic in transaction scope (DB-level consistency)

**Trade-off Accepted:** Orchestrator becomes single point of failure (mitigated by idempotency + webhook reconciliation).

### Why Caffeine Cache over Redis for Shipping Rates?

From `docs/java-project-standards.adoc`:
- **No Redis Dependency:** Architecture constraint (simplifies deployment)
- **Rate Data Characteristics:** Small payload (~1KB per rate set), tenant-scoped, 15-min TTL
- **Cache Hit Ratio:** Expected >70% (same customer previews multiple times before commit)

**Caffeine Advantages:**
- In-process cache (no network latency)
- Automatic eviction (LRU policy)
- Native Quarkus integration (`quarkus-cache`)

**Redis Migration Path:** If cache hit ratio <50% or multi-instance deployment requires shared cache, migrate to Redis with zero code changes (Quarkus cache abstraction).

### Alternatives Considered

1. **Optimistic Locking for Inventory (No Reservations):**
   - Pro: Simpler (no reservation table)
   - Con: High contention for popular products (checkout fails frequently)
   - Rejected: Poor UX for high-demand items

2. **Synchronous Stripe Payment on Preview:**
   - Pro: No "pending payment" state
   - Con: Preview becomes non-idempotent (charges on every preview call)
   - Rejected: Violates idempotency requirements

3. **Event Sourcing for Order State:**
   - Pro: Complete audit trail, replay capability
   - Con: Operational complexity (event store, projections)
   - Rejected: Overkill for MVP; deferred to Phase 3 if needed

---

## Consequences

### Positive Consequences

1. **Idempotency Guarantee:** No duplicate orders even with network retries (24-hour idempotency window)
2. **Data Consistency:** Compensation logic ensures no orphaned reservations or inconsistent order states
3. **Resilience:** Fallback rates allow checkout to proceed during carrier API outages
4. **Observability:** Explicit spans + metrics enable SLA monitoring (p95 latency, error rates)
5. **Testability:** Each saga stage testable in isolation (mock adapters via CDI alternatives)

### Negative Consequences

1. **Orchestrator Complexity:** Single class coordinates 6 stages (risk of "God object" anti-pattern)
   - **Mitigation:** Extract stage logic into dedicated service classes (`AddressValidationService`, `ShippingRateService`, etc.)
2. **Eventual Consistency:** Webhook reconciliation means payment success → order fulfillment has lag (max 5 min)
   - **Mitigation:** Monitor webhook delivery latency, retry failed webhooks
3. **Idempotency Storage Overhead:** 1 row per checkout attempt (purged after 24 hours)
   - **Mitigation:** Partitioned table by `created_at` for efficient purging
4. **Cache Consistency Risk:** Cached shipping rates become stale if carriers change pricing
   - **Mitigation:** 15-min TTL balances freshness vs. API cost; manual purge endpoint for emergencies

### Technical Debt Accepted

- **No Distributed Tracing Across Stripe:** Stripe API calls create new trace context (no propagation)
  - Deferred: Stripe tracing integration (planned Q2 2026)
- **No Circuit Breaker for Carrier APIs:** Direct timeout/retry logic (no Resilience4j yet)
  - Deferred: Circuit breaker implementation after observing failure patterns (Q2 2026)

### Risks Introduced

See Architecture Overview Section 6 (Risk Register):
- **RISK-005:** Insufficient test coverage for payment flows (Medium likelihood, High impact)
  - **Mitigation:** Dedicated Stripe test mode webhooks, 80% JaCoCo threshold enforced

### Impact on Future Decisions

- **ADR-004 (Caching Strategy):** Shipping rate cache design informs broader caching patterns (product catalog, tenant metadata)
- **ADR-005 (Background Jobs):** Order fulfillment jobs (email, shipping label) must restore TenantContext from job payload
- **ADR-006 (Event-Driven Architecture):** Webhook reconciliation logic can transition to domain events when event bus introduced

---

## Implementation Checklist

**Core Saga Implementation:**
- [ ] Implement `CheckoutOrchestrator` service with 6 saga stages
- [ ] Implement `IdempotencyStore` repository + DB table
- [ ] Add `X-Idempotency-Key` header handling in `CheckoutResource`
- [ ] Implement compensation logic for stages 4-6
- [ ] Add `@WithSpan` annotations to all saga stages

**Adapter Implementations:**
- [ ] Implement `AddressValidationAdapter` interface + USPS adapter
- [ ] Implement `CarrierRateAdapter` interface + UPS/FedEx/USPS adapters
- [ ] Implement Stripe `PaymentAdapter` with idempotency key support
- [ ] Configure Caffeine cache for shipping rates (15-min TTL)

**Observability:**
- [ ] Define Prometheus metrics (`checkout_*` namespace)
- [ ] Configure structured logging for saga stages
- [ ] Add span attributes (tenant_id, order_id, stage_name)
- [ ] Create Grafana dashboard for checkout metrics

**Testing:**
- [ ] Integration tests: Happy path (all stages succeed)
- [ ] Integration tests: Compensation paths (payment failure, inventory OOS)
- [ ] Integration tests: Idempotency (duplicate key returns 409)
- [ ] Load tests: 100 concurrent checkouts with Stripe test mode
- [ ] Webhook tests: Stripe event reconciliation for orphaned orders

**Documentation:**
- [ ] Update OpenAPI spec with `CheckoutPreviewRequest`/`CheckoutCommitRequest` schemas
- [ ] Document adapter contracts in `ARCHITECTURE.md`
- [ ] Add runbook for checkout failure investigation (logs, traces, metrics)

---

## References

- **Architecture Overview:** `docs/architecture_overview.md` (Section 3.2.7: Checkout & Order Orchestrator, Section 4: Component Interaction Notes)
- **OpenAPI Spec:** `api/v1/openapi.yaml` (Endpoints: `/checkout/preview`, `/checkout/commit`, Parameters: `IdempotencyKey`)
- **Component Diagram:** `docs/diagrams/component_overview.puml` (Flow 1: Customer Purchase)
- **Sequence Diagram:** `docs/diagrams/sequence_checkout_payment.mmd` (This ADR's visual companion)
- **ADR-001 Tenancy:** `docs/adr/ADR-001-tenancy.md` (TenantContext propagation)
- **Standards:** `docs/java-project-standards.adoc` (Section 5: Code Quality, Section 7: Observability)
- **Stripe Idempotency:** [Stripe API Docs - Idempotent Requests](https://stripe.com/docs/api/idempotent_requests)
- **Saga Pattern:** [Microservices.io - Saga Pattern](https://microservices.io/patterns/data/saga.html)
- **RFC 7807 Problem Details:** [IETF RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807)

---

**Document Version:** 1.0
**Last Updated:** 2026-01-03
**Maintained By:** Architecture Team
**Next Review:** Q2 2026 (after Stripe integration production launch)
