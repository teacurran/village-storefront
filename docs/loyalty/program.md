# Loyalty & Rewards Program

## Overview

The Village Storefront loyalty module provides a flexible, tenant-aware rewards system that tracks points accrual, redemption, tier progression, and expiration. Each tenant can configure their own loyalty program with custom rules for earning and redeeming points.

**References:**
- Task I4.T4: Loyalty & Rewards Module Implementation
- Blueprint Section 4.0: Core Components & Boundaries
- Operational Section 3.19.9: Loyalty and Rewards Ops

---

## Architecture

### Components

The loyalty module consists of three core entities:

1. **LoyaltyProgram** - Program configuration per tenant
2. **LoyaltyMember** - User enrollment and balance tracking
3. **LoyaltyTransaction** - Immutable ledger of all point movements

### Component Diagram Reference

See `docs/diagrams/architecture.puml` Section 3.5 for the loyalty module boundaries within the Quarkus API Core. The loyalty module integrates with:
- **Checkout Orchestrator**: Points accrual during order completion
- **Reporting Coordinator**: Ledger snapshots for analytics
- **Feature Flag Service**: Gradual rollout controls

---

## Data Model

### LoyaltyProgram

```java
{
  "name": "VIP Rewards",
  "enabled": true,
  "pointsPerDollar": 1.0,
  "redemptionValuePerPoint": 0.01,
  "minRedemptionPoints": 100,
  "maxRedemptionPoints": 10000,
  "pointsExpirationDays": 365,
  "tierConfig": "[{\"name\":\"Bronze\",\"minPoints\":0},{\"name\":\"Silver\",\"minPoints\":1000}]"
}
```

### Admin Upsert Payload

`PUT /admin/loyalty/program` accepts an `UpsertLoyaltyProgramRequest` that mirrors the entity fields plus a typed `tiers` array:

```json
{
  "name": "VIP Rewards",
  "description": "Premium loyalty for high-value shoppers",
  "enabled": true,
  "pointsPerDollar": 1.25,
  "redemptionValuePerPoint": 0.01,
  "minRedemptionPoints": 100,
  "maxRedemptionPoints": 10000,
  "pointsExpirationDays": 365,
  "tiers": [
    {"name": "Bronze", "minPoints": 0, "multiplier": 1.0},
    {"name": "Silver", "minPoints": 1000, "multiplier": 1.25},
    {"name": "Gold", "minPoints": 5000, "multiplier": 1.5}
  ],
  "metadata": {
    "welcomeBonus": 500
  }
}
```

**Key Fields:**
- `pointsPerDollar`: Points earned per dollar spent (e.g., 1.0 = 1 point per $1)
- `redemptionValuePerPoint`: Dollar value per point when redeeming (e.g., 0.01 = 1 point = $0.01)
- `minRedemptionPoints`: Minimum points required to redeem
- `maxRedemptionPoints`: Maximum points allowed in single redemption (null = unlimited)
- `pointsExpirationDays`: Days until earned points expire (null = never expire)
- `tierConfig`: JSON array defining tier thresholds and multipliers

### LoyaltyMember

```java
{
  "pointsBalance": 1250,
  "lifetimePointsEarned": 5000,
  "lifetimePointsRedeemed": 3750,
  "currentTier": "Silver",
  "status": "active"
}
```

**Key Fields:**
- `pointsBalance`: Current available points (decreases with redemptions and expirations)
- `lifetimePointsEarned`: Total points earned (never decreases, used for tier calculations)
- `lifetimePointsRedeemed`: Total points redeemed (audit metric)
- `currentTier`: Current tier name (recalculated on each accrual)

### LoyaltyTransaction

```java
{
  "pointsDelta": 100,
  "balanceAfter": 1350,
  "transactionType": "earned",
  "reason": "Purchase #abc123",
  "source": "order",
  "expiresAt": "2025-01-03T00:00:00Z",
  "idempotencyKey": null
}
```

**Transaction Types:**
- `earned`: Points awarded for purchases or promotions
- `redeemed`: Points spent for discounts
- `expired`: Points removed due to expiration
- `adjusted`: Manual admin adjustments (positive or negative)
- `reversed`: Reversal of previous transaction (e.g., order refund)

**Audit Fields:**
- `balanceAfter`: Snapshot of member balance after this transaction (reconciliation aid)
- `idempotencyKey`: Required for redemptions to prevent duplicate debits (ADR-003)

---

## Point Accrual

### Purchase-Based Accrual

Points are automatically awarded when orders are completed:

```java
Optional<LoyaltyTransaction> transaction = loyaltyService.awardPointsForPurchase(
    userId,
    orderTotal,
    orderId
);

transaction.ifPresent(tx -> {
    log.infof("Points earned: %d", tx.pointsDelta);
});
```

**Calculation:**
```
pointsEarned = floor(orderTotal * program.pointsPerDollar)
```

If the calculated `pointsEarned` rounds down to zero, the method returns `Optional.empty()` and no ledger entry is
created—checkout proceeds without raising an exception.

**Expiration:**
If `program.pointsExpirationDays` is set, earned points expire after that many days. Expiration is tracked per transaction, not per member.

### Manual Adjustments (Admin)

Admins can manually adjust points for customer service scenarios:

```java
loyaltyService.adjustPoints(userId, 500, "Birthday bonus");
loyaltyService.adjustPoints(userId, -200, "Refund reversal");
```

---

## Point Redemption

### Redemption Rules

- Minimum redemption enforced by `program.minRedemptionPoints`
- Maximum redemption enforced by `program.maxRedemptionPoints`
- Cannot redeem more than current `pointsBalance`
- Redemption operations are **idempotent** via `idempotencyKey`

### Redemption Flow

```java
RedeemPointsRequest request = new RedeemPointsRequest();
request.pointsToRedeem = 1000;
request.idempotencyKey = UUID.randomUUID().toString();

LoyaltyTransaction transaction = loyaltyService.redeemPoints(userId,
    request.pointsToRedeem,
    request.idempotencyKey);

BigDecimal discountValue = loyaltyService.calculateRedemptionValue(1000);
// Returns: $10.00 (assuming redemptionValuePerPoint = 0.01)
```

- Always send the `X-Idempotency-Key` header when calling the redemption endpoint. The request body's `idempotencyKey` field remains as a backward-compatible fallback for clients that cannot set headers.
- `RedeemPointsRequest` requires the number of points to redeem and (optionally) a fallback idempotency key.
- `LoyaltyService.redeemPoints()` enforces minimum/maximum redemption thresholds and prevents over-redemption.

### Idempotency Guarantee

Redemption requests include an `idempotencyKey` to prevent duplicate point debits if the client retries due to network issues. The service checks for existing transactions with the same key before processing.

**ADR-003 Reference:** Idempotent redemption operations prevent double-charging during checkout retry scenarios.

---

## Storefront Cart Summary Integration

`CartDto` responses include a `loyalty` object sourced from `LoyaltyService.calculateCartSummary()` so the storefront can show available points and projected earnings while the shopper reviews their cart.

```json
"loyalty": {
  "programEnabled": true,
  "programId": "0a1c2a24-4f50-4b4b-8d0b-d2955271cb2f",
  "memberPointsBalance": 1250,
  "estimatedPointsEarned": 120,
  "estimatedRewardValue": { "amount": "1.20", "currency": "USD" },
  "availableRedemptionValue": { "amount": "12.50", "currency": "USD" },
  "currentTier": "Silver",
  "dataFreshnessTimestamp": "2026-01-03T10:15:00Z"
}
```

**Field Summary**
- `programEnabled`: Indicates whether the tenant's loyalty program is active.
- `memberPointsBalance`: Current point balance for the authenticated shopper.
- `estimatedPointsEarned`: Points that would be earned if the cart were purchased immediately (tier multipliers applied).
- `estimatedRewardValue`: Cash equivalent of `estimatedPointsEarned`.
- `availableRedemptionValue`: Cash value of the shopper's current balance.
- `currentTier`: Tier name derived from the tenant's tier config (defaults to the entry tier for guests).
- `dataFreshnessTimestamp`: Latest timestamp from the member record or program configuration for transparency.

This summary powers the cart sidebar module requested in Task I4.T4 and keeps storefront and checkout experiences consistent.

---

## Tier Calculations

### Tier Progression

Tiers are calculated based on `lifetimePointsEarned` (not current balance). This ensures tiers reflect customer loyalty history, not just current spending power.

**Default Tier Thresholds:**
- **Bronze**: 0+ lifetime points
- **Silver**: 1,000+ lifetime points
- **Gold**: 5,000+ lifetime points
- **Platinum**: 10,000+ lifetime points

### Custom Tier Configuration

The `tierConfig` JSON field allows tenants to define custom tiers:

```json
[
  {"name": "Bronze", "minPoints": 0, "multiplier": 1.0},
  {"name": "Silver", "minPoints": 1000, "multiplier": 1.5},
  {"name": "Gold", "minPoints": 5000, "multiplier": 2.0}
]
```

**Implementation Detail:** `LoyaltyService` now parses `tierConfig` JSON per tenant and caches the resulting `LoyaltyTierDefinition` set. Multipliers from the active tier are applied to accrual calculations, and tier progression uses the configured `minPoints` thresholds instead of hardcoded values.

### Tier Updates

Tiers are recalculated automatically after each accrual transaction. Tier downgrades do not occur (tiers are "lifetime" status based on total earned, not current balance).

---

## Expiration Handling

### Expiration Job

A scheduled job runs nightly to expire points past their `expiresAt` timestamp:

```java
int expiredCount = loyaltyService.expirePoints(OffsetDateTime.now());
```

**Process:**
1. Query transactions with `expiresAt <= now` and `transactionType = 'earned'`
2. For each expired transaction:
   - Create new transaction with `transactionType = 'expired'`
   - Debit member balance by `pointsDelta`
   - Update `balanceAfter` snapshot

### Expiration Notifications

Future enhancement: Send email notifications to members 7 days before points expire (referenced in Operational Section 3.19.9).

---

## API Endpoints

### Storefront Endpoints

**GET** `/api/v1/loyalty/program`
- Returns active loyalty program configuration
- Public endpoint (no authentication required)

**GET** `/api/v1/loyalty/member/{userId}`
- Returns member balance, tier, and stats
- Requires authentication

**POST** `/api/v1/loyalty/enroll/{userId}`
- Enrolls user in loyalty program
- Idempotent (returns existing member if already enrolled)

**POST** `/api/v1/loyalty/redeem/{userId}`
- Redeems points for discount
- Requires `RedeemPointsRequest` with `pointsToRedeem` and the `X-Idempotency-Key` header (body key is an optional fallback)

**GET** `/api/v1/loyalty/transactions/{userId}?page=0&size=20`
- Returns paginated transaction history
- Ordered by `createdAt DESC`

### Admin Endpoints

**GET** `/admin/loyalty/program`
- Returns the tenant's loyalty configuration whether or not it is currently enabled
- Secured with `@RolesAllowed("admin")`

**PUT** `/admin/loyalty/program`
- Creates or updates the loyalty configuration for the current tenant
- Requires `UpsertLoyaltyProgramRequest` (name, accrual/redemption rates, tier definitions, optional metadata)
- Response is `LoyaltyProgramDto` with 201 for creation and 200 for updates

**POST** `/admin/loyalty/adjust/{userId}`
- Manual points adjustment (admin only)
- Requires `AdjustPointsRequest` with `points` and `reason`
- Secured with `@RolesAllowed("admin")`

---

## Observability

### Metrics

The loyalty module emits the following Micrometer metrics:

- `loyalty.program.saved` - Counter for program configuration updates
- `loyalty.member.enrolled` - Counter for new member enrollments
- `loyalty.points.earned` - Counter for points accrued (tagged by `tenant_id`, `tier`)
- `loyalty.points.redeemed` - Counter for points redeemed (tagged by `tenant_id`, `tier`)
- `loyalty.points.adjusted` - Counter for admin adjustments (tagged by `tenant_id`)
- `loyalty.points.expired` - Counter for expired points (tagged by `tenant_id`)
- `loyalty.tier.updated` - Counter for tier upgrades (tagged by `tenant_id`, `new_tier`)
- `reporting.loyalty.ledger.events` - Counter emitted by `ReportingProjectionService.recordLoyaltyLedgerEvent()` to feed reporting hooks

### Logging

All loyalty operations log structured messages including:
- `tenantId` - Current tenant context
- `userId` / `memberId` - User/member identifiers
- `points` - Point deltas
- `balance` - Resulting balance after operation

**Example:**
```
INFO  [vil.sto.loy.LoyaltyService] Points awarded - tenantId=abc-123, userId=def-456, memberId=ghi-789, points=100, newBalance=1350
```

Reporting hooks are wired through `ReportingProjectionService.recordLoyaltyLedgerEvent()` so the analytics pipeline can consume ledger activity without polling the database.

### Operational Checklist (Section 3.19.9)

- ✅ Nightly tier recalculation job completes within SLA
- ✅ Points accrual performance monitored for high-volume tenants
- ✅ Redemption rules tested for edge cases (partial redemption, returns)
- ⚠️  Expiration notifications (pending implementation)
- ✅ Ledger audit trails maintain `balanceAfter` integrity
- ✅ API responses include `data_freshness_timestamp` (via entity `updatedAt`)

---

## Integration with Checkout

### Checkout Saga Integration

The loyalty module integrates with the checkout orchestrator (see `docs/checkout/saga.md`):

1. **Order Completion**: Checkout saga calls `loyaltyService.awardPointsForPurchase()`
2. **Discount Application**: Checkout applies redemption value to order total
3. **Rollback**: If order fails, reversal transactions restore points

**Example Checkout Flow:**
```java
// During checkout
if (cart.loyaltyRedemption != null) {
    BigDecimal discount = loyaltyService.calculateRedemptionValue(
        cart.loyaltyRedemption.pointsToRedeem
    );
    order.discount += discount;
}

// After order success
loyaltyService.awardPointsForPurchase(order.userId, order.total, order.id);
```

---

## Feature Flags

### Gradual Rollout

Use feature flags to enable loyalty for specific tenants:

```java
if (featureFlagService.isEnabled("loyalty.enabled")) {
    loyaltyService.awardPointsForPurchase(...);
}
```

**Kill Switch:** Platform admins can disable loyalty program-wide during incidents via:
```
featureFlagService.overrideFlag("loyalty.enabled", false);
```

---

## Future Enhancements

1. **Tier Multipliers**: Parse `tierConfig` JSON to apply tier-specific earning rates
2. **Bonus Campaigns**: Temporary multipliers for specific product categories
3. **Referral Rewards**: Points for referring new customers
4. **CRM Integration**: Sync loyalty data with marketing automation platforms
5. **Expiration Notifications**: Email reminders before points expire
6. **Gift Points**: Transfer points between members
7. **Partner Integrations**: Earn/redeem points across merchant networks

---

## Database Schema

### Migration Scripts

Loyalty tables are managed via MyBatis migrations:

```sql
CREATE TABLE loyalty_programs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    points_per_dollar DECIMAL(19,4) NOT NULL,
    redemption_value_per_point DECIMAL(19,4) NOT NULL,
    min_redemption_points INTEGER NOT NULL,
    max_redemption_points INTEGER,
    points_expiration_days INTEGER,
    tier_config JSONB,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE loyalty_members (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id),
    program_id UUID NOT NULL REFERENCES loyalty_programs(id),
    points_balance INTEGER NOT NULL DEFAULT 0,
    lifetime_points_earned INTEGER NOT NULL DEFAULT 0,
    lifetime_points_redeemed INTEGER NOT NULL DEFAULT 0,
    current_tier VARCHAR(50),
    tier_updated_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    metadata JSONB,
    enrolled_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE(user_id, program_id)
);

CREATE TABLE loyalty_transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    member_id UUID NOT NULL REFERENCES loyalty_members(id),
    order_id UUID,
    points_delta INTEGER NOT NULL,
    balance_after INTEGER NOT NULL,
    transaction_type VARCHAR(50) NOT NULL,
    reason TEXT,
    source VARCHAR(100),
    expires_at TIMESTAMPTZ,
    idempotency_key VARCHAR(255),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_loyalty_transactions_member ON loyalty_transactions(member_id);
CREATE INDEX idx_loyalty_transactions_order ON loyalty_transactions(order_id);
CREATE INDEX idx_loyalty_transactions_expires ON loyalty_transactions(expires_at);
```

### Row-Level Security

All loyalty tables enforce tenant isolation via `tenant_id` column and RLS policies (inherited from platform-wide tenant security model).

---

## Testing

### Unit Tests

See `src/test/java/villagecompute/storefront/loyalty/LoyaltyServiceTest.java`:
- Enrollment flows
- Points accrual calculations
- Redemption idempotency
- Tier progression logic
- Error handling (insufficient balance, minimum redemption)

### Integration Tests

See `src/test/java/villagecompute/storefront/loyalty/LoyaltyLedgerIT.java`:
- Ledger audit trail consistency
- Concurrent redemptions
- Expiration job execution
- Transaction history queries
- Idempotency key lookups

### Fuzz Testing (Acceptance Criteria)

The acceptance criteria requires fuzz testing for accrual scenarios:
- Random purchase amounts ($0.01 to $10,000)
- Concurrent accrual/redemption operations
- Ledger rollback during order failures

---

## Performance Considerations

### Indexing

Indexes on `loyalty_transactions`:
- `member_id` - Fast transaction history queries
- `order_id` - Fast order-to-points lookups
- `expires_at` - Efficient expiration job queries

### Caching

- Program configuration cached in `LoyaltyService` (1-hour TTL)
- Member balances cached during checkout session
- Transaction history paginated to limit query size

### High-Volume Tenants

For tenants with >10,000 members:
- Monitor points accrual latency (target <100ms p95)
- Consider read replicas for transaction history queries
- Pre-aggregate tier statistics for reporting dashboards

---

## Support & Troubleshooting

### Common Issues

**Issue:** Points not awarded after order completion
- Check feature flag: `loyalty.enabled`
- Verify program is `enabled = true`
- Review logs for exceptions during accrual

**Issue:** Redemption failed with "Insufficient balance"
- Confirm member balance via `/loyalty/member/{userId}`
- Check for expired points in transaction history
- Verify no concurrent redemptions depleted balance

**Issue:** Tier not updating after large purchase
- Tier calculations use `lifetimePointsEarned`, not `pointsBalance`
- Confirm transaction was persisted (check `/loyalty/transactions/{userId}`)
- Review tier thresholds in program configuration

### Audit & Reconciliation

To reconcile member balance:
```sql
SELECT
    member_id,
    SUM(points_delta) AS calculated_balance,
    (SELECT points_balance FROM loyalty_members WHERE id = member_id) AS current_balance
FROM loyalty_transactions
WHERE member_id = 'abc-123'
GROUP BY member_id;
```

If `calculated_balance != current_balance`, investigate for missing transactions or concurrent modification issues.

---

## Contact

For questions or enhancements, contact the platform engineering team or file an issue in the Village Storefront repository.
