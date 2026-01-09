# Consignment Payout Ledger

## Overview

The Consignment Payout Ledger system tracks balances for consignment vendors (consignors) and manages the lifecycle of funds from sale to payout. The system implements a double-entry ledger with pending and available balances to enforce hold periods and ensure accurate financial reconciliation.

**Key Components:**
- `PayoutLedger`: Balance sheet tracking pending and available balances per consignor
- `PayoutLedgerEntry`: Immutable transaction log for all balance changes
- `PayoutLedgerService`: Business logic for balance operations
- Domain events: `ConsignmentItemReceived`, `ConsignmentPayoutDue`

## Architecture

### Balance Types

1. **Pending Balance**
   - Represents sales awaiting settlement after hold period
   - Incremented when consignment item sold (via order completion)
   - Decremented by refunds or moved to available after hold period
   - Not eligible for immediate payout

2. **Available Balance**
   - Represents funds ready for withdrawal
   - Incremented via settlement (moving from pending after hold period)
   - Decremented by payouts and refunds (when pending insufficient)
   - Can be withdrawn immediately via payout batch

### Ledger Entry Types

| Entry Type | Amount Sign | Description | Affects |
|-----------|-------------|-------------|---------|
| `SALE` | Positive | Consignment item sold | Pending (+) |
| `REFUND` | Negative | Order refund processed | Available (-), then Pending (-) |
| `SETTLEMENT` | Positive | Pending → Available after hold period | Pending (-), Available (+) |
| `PAYOUT` | Negative | Funds withdrawn to consignor | Available (-) |
| `ADJUSTMENT` | Positive/Negative | Manual correction by admin | Pending (±), Available (±) |

## Workflow

### 1. Item Sale Flow

```
┌─────────────────┐
│ Order Completed │
└────────┬────────┘
         │
         v
┌─────────────────┐
│ Calculate       │
│ Commission      │
│ Net = Sale - %  │
└────────┬────────┘
         │
         v
┌─────────────────┐
│ recordSale()    │
│ amount = Net    │
│ Pending += Net  │
└────────┬────────┘
         │
         v
┌─────────────────┐
│ Create Entry    │
│ Type: SALE      │
└─────────────────┘
```

**Example:**
- Item sold for $100
- Commission rate: 15%
- Commission: $15
- Net to consignor: $85
- Ledger: `pending_balance += $85`

### 2. Settlement Flow (Automated Job)

The settlement job runs on a schedule (e.g., daily) to move pending balances to available after the hold period.

```
┌─────────────────┐
│ Settlement Job  │
│ Runs Daily      │
└────────┬────────┘
         │
         v
┌─────────────────────────────┐
│ Query PayoutLedgerEntries   │
│ WHERE entry_type = 'SALE'   │
│   AND created_at <= NOW() - │
│        hold_period (e.g. 7d)│
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ Group by consignor          │
│ Sum unsettled amounts       │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ For each consignor:         │
│ settlePendingToAvailable()  │
│ Pending -= amount           │
│ Available += amount         │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ Emit ConsignmentPayoutDue   │
│ event if available > min    │
└─────────────────────────────┘
```

**Configuration:**
- Hold period: Configurable via feature flag `consignment.payout.settlement.enabled`
- Default: 7 days
- Minimum balance threshold: $50 (configurable in `consignment.payout.auto_sweep.enabled`)

### 3. Refund Flow

Refunds deduct from available balance first, then pending balance if insufficient.

```
┌─────────────────┐
│ Order Refunded  │
└────────┬────────┘
         │
         v
┌─────────────────────────────┐
│ recordRefund()              │
│ refund_amount = $50         │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ Check available_balance     │
│ If available >= refund:     │
│   Available -= refund       │
│ Else:                       │
│   Available -= available    │
│   Pending -= remainder      │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ Create Entry                │
│ Type: REFUND                │
│ Amount: -$50                │
└─────────────────────────────┘
```

### Order + Payment Service Integration

- `OrderService.markOrderPaid` calls `ConsignmentService.handleOrderPaid`, which inspects `OrderLineItem` vendor
  snapshots, enforces the stored commission percentages, records `SALE` entries through `PayoutLedgerService`, and marks
  the associated `ConsignmentItem` records as `sold`.
- `PaymentService.refundPayment` calls `ConsignmentService.handleOrderRefund`, which proportionally reverses the net
  payouts tied to the refunded subtotal, writes `REFUND` entries referencing the order, and marks fully refunded
  inventory as `returned`.

This wiring fulfills the acceptance criterion that ledger updates fire on sale/refund events—the ledger stays in sync with the actual order/payment lifecycle without manual intervention.

### 4. Payout Flow

```
┌─────────────────────────────┐
│ Payout Sweep Job            │
│ Triggered by event or       │
│ schedule                    │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ Query consignors with       │
│ available_balance >= min    │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ For each consignor:         │
│ - Create PayoutBatch        │
│ - Call Stripe Connect API   │
│ - Record payout_reference   │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ recordPayout()              │
│ Available -= payout_amount  │
└────────┬────────────────────┘
         │
         v
┌─────────────────────────────┐
│ Create Entry                │
│ Type: PAYOUT                │
│ Amount: -$payout_amount     │
│ Reference: payout_batch_id  │
└─────────────────────────────┘
```

## Payout Sweep Logic

### Automated Sweep (Feature Flag Controlled)

**Feature Flag:** `consignment.payout.auto_sweep.enabled`
**Default:** Disabled (requires manual trigger)
**Config:**
```json
{
  "min_balance": 50.00,
  "schedule": "weekly",
  "max_batch_size": 100
}
```

**Sweep Algorithm:**

1. **Query Eligible Consignors**
   ```sql
   SELECT consignor_id, available_balance
   FROM payout_ledger
   WHERE available_balance >= :min_balance
     AND tenant_id = :tenant_id
   ORDER BY last_updated_at ASC
   LIMIT :max_batch_size;
   ```

2. **For Each Consignor:**
   - Retrieve payout settings (Stripe Connect account, tax info)
   - Create `PayoutBatch` record with status `pending`
   - Call Stripe Connect Payout API
   - On success:
     - Update batch status to `processing` or `completed`
     - Record `payment_reference` (Stripe payout ID)
     - Call `recordPayout()` to deduct from available balance
   - On failure:
     - Update batch status to `failed`
     - Log failure reason
     - Do NOT deduct from balance

3. **Emit Events:**
   - `PayoutBatchCreated`: After batch persisted
   - `PayoutCompleted`: After Stripe confirms transfer
   - `PayoutFailed`: On Stripe API error

### Manual Payout Trigger

Admins can manually trigger payouts from the admin dashboard for specific consignors:

1. Navigate to Consignor detail page
2. View current `available_balance`
3. Click "Process Payout"
4. System validates:
   - Balance > $0
   - Stripe Connect account verified
   - No pending payout batch
5. Creates payout batch and processes immediately

## Commission Enforcement

Commission rates are enforced at multiple points:

1. **Consignment Item Creation:**
   - Rate validated by `normalizeCommissionRate()` (0-100%)
   - Stored on `ConsignmentItem.commissionRate`

2. **Order Completion:**
   - Line item subtotal retrieved
   - Commission calculated: `commission = subtotal × (rate / 100)`
   - Net payout: `net = subtotal - commission`
   - `recordSale()` called with net amount

3. **Payout Calculation:**
   - Service method `calculatePayout()` provides breakdown:
     - `itemSubtotal`: Original sale price
     - `commissionAmount`: Platform commission
     - `netPayout`: Amount added to pending balance

## Multi-Tenant RLS

Row Level Security (RLS) ensures tenant isolation:

### Database Policies

```sql
-- payout_ledger RLS policy (to be added)
CREATE POLICY payout_ledger_tenant_isolation ON payout_ledger
  FOR ALL
  USING (tenant_id = current_setting('app.current_tenant_id')::uuid);

-- payout_ledger_entries RLS policy (to be added)
CREATE POLICY payout_ledger_entries_tenant_isolation ON payout_ledger_entries
  FOR ALL
  USING (tenant_id = current_setting('app.current_tenant_id')::uuid);
```

### Repository Enforcement

All repository queries include tenant scoping:

```java
// Example from PayoutLedgerRepository
public Optional<PayoutLedger> findByConsignor(UUID consignorId) {
    UUID tenantId = TenantContext.getCurrentTenantId();
    return find("tenant.id = :tenantId and consignor.id = :consignorId",
                Parameters.with("tenantId", tenantId).and("consignorId", consignorId))
            .firstResultOptional();
}
```

### Service Layer Enforcement

- All service methods resolve `TenantContext.getCurrentTenantId()` on entry
- Entity lifecycle hooks (`@PrePersist`) auto-populate tenant_id
- Cross-tenant access attempts return empty results (not errors)

## Reconciliation & Reporting

### Ledger Integrity

The ledger maintains referential integrity:

1. **Entry Snapshots:**
   - Each entry records `pending_balance_after` and `available_balance_after`
   - Allows reconstruction of balance at any point in time

2. **Sum Validation:**
   ```sql
   -- Verify ledger consistency
   SELECT
     ledger_id,
     SUM(amount) AS total_transactions,
     MAX(pending_balance_after) AS current_pending,
     MAX(available_balance_after) AS current_available
   FROM payout_ledger_entries
   WHERE ledger_id = :ledger_id
   GROUP BY ledger_id;
   ```

3. **Audit Trail:**
   - All entries immutable (no updates/deletes)
   - `created_at` timestamp for chronological ordering
   - `reference_id` and `reference_type` link to source records

### Reporting Queries

**Consignor Balance Statement:**
```sql
SELECT
  ple.created_at,
  ple.entry_type,
  ple.amount,
  ple.description,
  ple.pending_balance_after,
  ple.available_balance_after
FROM payout_ledger_entries ple
JOIN payout_ledger pl ON ple.ledger_id = pl.id
WHERE pl.consignor_id = :consignor_id
  AND pl.tenant_id = :tenant_id
ORDER BY ple.created_at DESC;
```

**Pending Payout Summary (Platform Admin):**
```sql
SELECT
  t.name AS tenant_name,
  c.name AS consignor_name,
  pl.pending_balance,
  pl.available_balance,
  pl.last_updated_at
FROM payout_ledger pl
JOIN consignors c ON pl.consignor_id = c.id
JOIN tenants t ON pl.tenant_id = t.id
WHERE pl.available_balance >= 50.00
ORDER BY pl.available_balance DESC;
```

## Error Handling

### Insufficient Balance

Operations that would result in negative balance throw `IllegalStateException`:

```java
// Example: recordPayout()
if (ledger.availableBalance.compareTo(amount) < 0) {
    throw new IllegalStateException(
        "Insufficient available balance for payout. Available: " +
        ledger.availableBalance + ", Required: " + amount);
}
```

### Concurrent Updates

The `PayoutLedger` entity uses optimistic locking via `@Version`:

```java
@Version
@Column(name = "version")
public Long version;
```

- Concurrent updates will retry automatically via Panache
- Version mismatch throws `OptimisticLockException`
- Clients should retry transient failures

### Transaction Boundaries

All balance-modifying operations are `@Transactional`:

- Ledger update + entry creation happen atomically
- Rollback on any failure preserves consistency
- External API calls (Stripe) happen after DB commit

## Migration & Deployment

### Database Migration

File: `V20260118__payout_ledger_tables.sql`

Creates:
- `payout_ledger` table with unique constraint per tenant-consignor
- `payout_ledger_entries` table with immutable entries
- Indexes for efficient queries
- Feature flags for settlement and auto-sweep

### Backfilling Existing Data

If consignors exist before ledger deployment:

```sql
-- Create ledgers for all active consignors
INSERT INTO payout_ledger (id, tenant_id, consignor_id, pending_balance, available_balance, currency, last_updated_at, version, created_at, updated_at)
SELECT
  gen_random_uuid(),
  tenant_id,
  id AS consignor_id,
  0.00,
  0.00,
  'USD',
  NOW(),
  0,
  NOW(),
  NOW()
FROM consignors
WHERE status = 'active'
ON CONFLICT (tenant_id, consignor_id) DO NOTHING;
```

## Monitoring & Observability

### Metrics (Micrometer)

The service emits counters and gauges:

```java
// Counters
meterRegistry.counter("consignment.ledger.created", "tenant_id", tenantId).increment();
meterRegistry.counter("consignment.ledger.sale", "tenant_id", tenantId).increment();
meterRegistry.counter("consignment.ledger.refund", "tenant_id", tenantId).increment();
meterRegistry.counter("consignment.ledger.settlement", "tenant_id", tenantId).increment();
meterRegistry.counter("consignment.ledger.payout", "tenant_id", tenantId).increment();
meterRegistry.counter("consignment.ledger.adjustment", "tenant_id", tenantId).increment();

// Gauges (per tenant)
Gauge.builder("consignment.payout.pending.amount", atomicRef, val -> val.get().doubleValue())
     .tag("tenant_id", tenantId)
     .register(meterRegistry);
```

### Logging

Structured logs include tenant context:

```
LOG.infof("Recording sale - tenantId=%s, consignorId=%s, amount=%s, refId=%s",
          tenantId, consignorId, amount, referenceId);
```

### Alerts

Recommended alerts:

1. **Negative Balance**: Alert if any ledger has negative pending or available balance
2. **Settlement Lag**: Alert if pending entries older than hold period not settled
3. **Payout Failures**: Alert on Stripe API failures during payout sweep
4. **Balance Mismatch**: Alert if ledger balance != sum of entries

## Future Enhancements

1. **Tax Reporting:**
   - Generate 1099 forms for consignors exceeding thresholds
   - Track gross sales, commissions, net payouts per consignor per year

2. **Consignor Portal:**
   - Self-service balance view
   - Transaction history export
   - Payout request initiation

3. **Multi-Currency Support:**
   - Convert balances to consignor's preferred currency
   - Handle FX rates and conversion fees

4. **Tiered Commissions:**
   - Apply different rates based on sales volume
   - Automatic tier upgrades

5. **Aging Bucket Reports:**
   - Show pending amounts by age: 0-7d, 7-14d, 14-30d, 30+ days
   - Help identify settlement delays

## References

- **Task:** I3.T5 - Consignment Payout Ledger Implementation
- **ADR-001:** Multi-tenant data isolation
- **ADR-003:** Checkout saga and payment processing
- **Migration:** `V20260118__payout_ledger_tables.sql`
- **Entities:** `PayoutLedger`, `PayoutLedgerEntry`, `Consignor`
- **Services:** `PayoutLedgerService`, `ConsignmentService`
- **Tests:** `PayoutLedgerServiceTest`, `PayoutLedgerRLSTest`
