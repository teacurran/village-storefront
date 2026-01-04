# ADR-004: Consignment Payout Automation & Compliance Guard Rails

**Status:** Accepted
**Date:** 2026-01-03
**Deciders:** Architecture Team, Finance Lead, Compliance Officer
**Consulted:** Payment Integration Lead, Security Team
**Informed:** Engineering Team, Product Management, Support Team

---

## Context

The Village Storefront consignment module (implemented in Task I3.T1) enables merchants to sell products on behalf of third-party vendors (consignors). The platform must facilitate periodic payouts to consignors while:

1. **Maintaining Financial Accuracy:** Payout calculations must reconcile against sales aggregates with auditable precision
2. **Ensuring Compliance:** Payout approvals, transfers, and failures must be logged for financial audits and dispute resolution
3. **Preventing Fraud:** Guard rails must prevent unauthorized payouts, double-payments, and incorrect amounts
4. **Supporting Multi-Tenant Isolation:** Payout data and transfers must be strictly scoped to tenant boundaries (per ADR-001)
5. **Enabling Automation:** Reduce manual processing overhead while preserving admin oversight for high-value transfers

### Technical Context

From `docs/java-project-standards.adoc` and related ADRs:
- **Framework:** Quarkus 3.17+ (CDI, Panache ORM, Quarkus Scheduler)
- **Database:** PostgreSQL 17 (tenant-scoped data, optimistic locking via `@Version`)
- **Payment Provider:** Stripe Connect Express (separate accounts per consignor)
- **Reporting:** Event-driven projection aggregates (30-minute SLA, see `docs/reporting/projections.md`)
- **Observability:** Micrometer metrics, OpenTelemetry tracing
- **Audit Logging:** `platform_commands` table captures all admin actions with ticket numbers

### Business Requirements

From Section 3.2.6 (Consignment Module):
- **Payout Frequency:** Monthly batches (1st of month) or on-demand admin-triggered
- **Commission Model:** Configurable per consignor (default 15%, stored in `consignment_items.commission_rate`)
- **Approval Workflow:** Admin must approve batches >$500 or when consignor is new (< 3 months active)
- **Payout Timeframe:** Transfers execute within 24 hours of approval (SLA)
- **Failure Handling:** Failed transfers retry 3x with exponential backoff, then escalate to manual intervention
- **Notification:** Email consignor on payout completion with PDF statement attachment

### Competitive Landscape

From competitor research:
- **Shopify Collective:** Automated bi-weekly payouts via Stripe Connect, no manual approval
- **Etsy:** 3-5 business day payout window, automated with fraud detection gates
- **Faire:** Monthly payouts with net-60 terms, manual approval for new vendors

**Decision Driver:** Balance automation (reduce admin overhead) with compliance (audit trail) and fraud prevention (multi-stage validation).

---

## Decision

We will implement a **staged automation payout system** with the following design:

### 1. Payout Lifecycle State Machine

```
┌──────────┐
│ pending  │ ← Batch created (automated or manual)
└────┬─────┘
     │
     │ Admin approves (POST /payout-batches/{id}/approve)
     ↓
┌────────────────────┐
│ awaiting_approval  │ ← Queued for Stripe transfer
└────┬───────────────┘
     │
     │ Background job dequeues
     ↓
┌────────────┐
│ processing │ ← Stripe Connect transfer in flight
└────┬───────┘
     │
     ├──→ SUCCESS ──→ ┌───────────┐
     │                │ completed │ (payment_reference populated)
     │                └───────────┘
     │
     ├──→ FAILURE ──→ ┌────────┐
     │                │ failed │ (error_message populated, retryable)
     │                └────────┘
     │
     └──→ TIMEOUT ──→ ┌───────────┐
                      │ uncertain │ (awaiting webhook reconciliation)
                      └───────────┘
```

**State Transitions:**
- `pending → awaiting_approval`: Admin approval via API (requires ticket number)
- `awaiting_approval → processing`: Background job picks up batch
- `processing → completed`: Stripe transfer succeeds (payment_reference = Stripe transfer ID)
- `processing → failed`: Stripe transfer fails (retryable errors: balance_insufficient, rate_limit)
- `processing → uncertain`: Network timeout (webhook reconciliation within 24h)
- `failed → pending`: Admin retry action (POST /payout-batches/{id}/retry)

### 2. Data Model

#### payout_batches Table

```sql
CREATE TABLE payout_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    consignor_id UUID NOT NULL REFERENCES consignors(id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    total_amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
        -- pending|awaiting_approval|processing|completed|failed|uncertain
    approved_at TIMESTAMPTZ,
    approved_by VARCHAR(255),  -- User ID or impersonator ID
    started_at TIMESTAMPTZ,    -- When Stripe transfer initiated
    processed_at TIMESTAMPTZ,  -- When transfer completed/failed
    payment_reference VARCHAR(255),  -- Stripe transfer ID (e.g., 'tr_xxx')
    error_message TEXT,        -- Failure reason (e.g., 'balance_insufficient')
    version BIGINT NOT NULL DEFAULT 0,  -- Optimistic locking
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_payout_batch_period UNIQUE (tenant_id, consignor_id, period_start, period_end)
);

CREATE INDEX idx_payout_batches_tenant ON payout_batches(tenant_id, status, created_at);
CREATE INDEX idx_payout_batches_consignor ON payout_batches(consignor_id, status);
CREATE INDEX idx_payout_batches_payment_ref ON payout_batches(payment_reference);
```

**Design Rationale:**
- **Unique Constraint:** Prevents duplicate batches for same consignor + period (idempotency)
- **Optimistic Locking:** `@Version` column prevents concurrent approval/retry conflicts
- **Audit Fields:** `approved_by`, `approved_at` capture admin action (linked to `platform_commands`)
- **Payment Reference:** Stores Stripe `transfer_id` for reconciliation and refunds

#### payout_line_items Table

```sql
CREATE TABLE payout_line_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_batch_id UUID NOT NULL REFERENCES payout_batches(id) ON DELETE CASCADE,
    consignment_item_id UUID NOT NULL REFERENCES consignment_items(id) ON DELETE CASCADE,
    sale_amount NUMERIC(19,4) NOT NULL,       -- Original sale price
    commission_rate NUMERIC(5,2) NOT NULL,    -- Commission % (e.g., 15.00)
    commission_amount NUMERIC(19,4) NOT NULL, -- Calculated commission
    payout_amount NUMERIC(19,4) NOT NULL,     -- Net to consignor (sale - commission)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payout_line_items_batch ON payout_line_items(payout_batch_id);
CREATE INDEX idx_payout_line_items_item ON payout_line_items(consignment_item_id);
```

**Calculation Formula:**
```
commission_amount = sale_amount * (commission_rate / 100)
payout_amount = sale_amount - commission_amount
```

### 3. Stripe Connect Integration

#### Consignor Onboarding Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  Consignor Portal (Vue.js SPA)                                  │
│  Path: /vendor/onboarding                                       │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       │ 1. Click "Connect Stripe Account"
                       ↓
┌─────────────────────────────────────────────────────────────────┐
│  POST /api/v1/vendor/stripe/connect-link                        │
│  Response: { url: "https://connect.stripe.com/oauth/..." }     │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       │ 2. Redirect to Stripe Connect Express
                       ↓
┌─────────────────────────────────────────────────────────────────┐
│  Stripe Connect Express Onboarding                              │
│  - Collect business info (name, address, tax ID)               │
│  - KYC verification (identity documents)                        │
│  - Bank account details (for payouts)                           │
└──────────────────────┬──────────────────────────────────────────┘
                       │
                       │ 3. OAuth redirect callback
                       ↓
┌─────────────────────────────────────────────────────────────────┐
│  GET /api/v1/vendor/stripe/callback?code={auth_code}            │
│  - Exchange code for Stripe account ID (acct_xxx)              │
│  - Store in consignor.payout_settings JSONB                     │
│  - Verify account status (charges_enabled, capabilities)       │
└─────────────────────────────────────────────────────────────────┘
```

**Stored Data (consignor.payout_settings JSONB):**
```json
{
  "stripe_account_id": "acct_1A2B3C4D5E6F",
  "stripe_onboarding_completed": true,
  "stripe_charges_enabled": true,
  "stripe_payouts_enabled": true,
  "stripe_capabilities": {
    "transfers": "active",
    "card_payments": "active"
  },
  "last_verified_at": "2026-01-03T10:00:00Z"
}
```

#### Transfer Execution

```java
// Stripe Connect Transfer API call (from ConsignmentService)
Transfer transfer = Transfer.create(
    TransferCreateParams.builder()
        .setAmount(batch.totalAmount.multiply(new BigDecimal("100")).longValue()) // Convert to cents
        .setCurrency("usd")
        .setDestination(consignor.stripeAccountId)
        .setDescription("Consignment payout for " + batch.periodStart + " to " + batch.periodEnd)
        .putMetadata("batch_id", batch.id.toString())
        .putMetadata("tenant_id", batch.tenant.id.toString())
        .putMetadata("consignor_id", batch.consignor.id.toString())
        .build(),
    RequestOptions.builder()
        .setIdempotencyKey(batch.id.toString()) // Prevent duplicate transfers
        .build()
);
```

**Idempotency Strategy:**
- **Key:** `batch.id` (UUID) guarantees unique transfer per batch
- **Retry Safety:** If network timeout occurs, retry with same key returns existing transfer (no duplicate)
- **Stripe Guarantee:** Idempotency keys valid for 24 hours

### 4. Automation & Approval Rules

#### Automated Batch Creation

**Scheduled Job (Quarkus Scheduler):**
```java
@Scheduled(cron = "0 0 1 * * ?")  // 1st of every month at midnight
public void createMonthlyPayoutBatches() {
    LocalDate lastMonth = LocalDate.now().minusMonths(1);
    LocalDate periodStart = lastMonth.withDayOfMonth(1);
    LocalDate periodEnd = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());

    // Iterate all tenants (multi-tenant aware)
    for (Tenant tenant : Tenant.listAll()) {
        TenantContext.setCurrentTenantId(tenant.id);
        try {
            consignmentService.createPayoutBatchesForPeriod(periodStart, periodEnd);
        } finally {
            TenantContext.clear();
        }
    }
}
```

**Batch Creation Logic (ConsignmentService):**
1. Query `consignment_payout_aggregates` for period (see `docs/reporting/projections.md`)
2. Validate aggregate freshness (`data_freshness_timestamp` < 30 min lag)
3. For each consignor with sales:
   - Check for existing batch (unique constraint prevents duplicates)
   - Create `payout_batches` row (status='pending')
   - Create `payout_line_items` rows from aggregate data
4. Emit metric: `consignment.payout.batch.created`

#### Approval Threshold Rules

**Auto-Approve Conditions:**
- Batch amount ≤ $500
- Consignor active for > 3 months
- No failed payouts in last 6 months
- Stripe account verified (`charges_enabled=true`)

**Require Manual Approval:**
- Batch amount > $500
- New consignor (< 3 months active)
- Prior failed payouts (within 6 months)
- Stripe account unverified or capabilities pending
- First payout to consignor (fraud prevention)

**Implementation:**
```java
public boolean requiresManualApproval(PayoutBatch batch) {
    BigDecimal threshold = new BigDecimal("500.00");
    if (batch.totalAmount.compareTo(threshold) > 0) return true;

    Consignor consignor = batch.consignor;
    if (consignor.createdAt.isAfter(OffsetDateTime.now().minusMonths(3))) return true;

    long failedPayouts = PayoutBatch.count(
        "consignor = ?1 AND status = 'failed' AND created_at > ?2",
        consignor, OffsetDateTime.now().minusMonths(6)
    );
    if (failedPayouts > 0) return true;

    // Check Stripe account status
    JsonNode payoutSettings = objectMapper.readTree(consignor.payoutSettings);
    if (!payoutSettings.path("stripe_charges_enabled").asBoolean(false)) return true;

    return false;
}
```

### 5. Compliance & Audit Logging

#### platform_commands Audit Trail

All admin actions logged to `platform_commands` table (per ADR-001):

```sql
CREATE TABLE platform_commands (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    action VARCHAR(100) NOT NULL,  -- approve_payout_batch, retry_payout_batch, etc.
    resource_type VARCHAR(50),      -- 'payout_batch'
    resource_id UUID,               -- payout_batch.id
    user_id VARCHAR(255) NOT NULL,  -- Admin user ID
    ticket_number VARCHAR(100),     -- Support ticket reference (required for approvals)
    metadata JSONB,                 -- Additional context (impersonator_id, approval_reason, etc.)
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_platform_commands_tenant ON platform_commands(tenant_id, timestamp);
CREATE INDEX idx_platform_commands_resource ON platform_commands(resource_type, resource_id);
```

**Example Audit Log Entries:**

```sql
-- Admin approves payout batch
INSERT INTO platform_commands (tenant_id, action, resource_type, resource_id, user_id, ticket_number, metadata)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'approve_payout_batch',
    'payout_batch',
    '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    'admin@example.com',
    'TICKET-12345',
    '{"approval_reason": "Verified sales data", "batch_amount": 1500.00}'::jsonb
);

-- Platform admin impersonates tenant admin to approve payout
INSERT INTO platform_commands (tenant_id, action, resource_type, resource_id, user_id, ticket_number, metadata)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'approve_payout_batch',
    'payout_batch',
    '8d7f5680-8536-51fe-a55c-f18ad2g01bf8',
    'tenant_owner@merchant.com',
    'TICKET-12346',
    '{"impersonator_id": "platform_admin@villagecompute.com", "impersonation_session_id": "sess_xyz"}'::jsonb
);

-- Payout transfer completed (logged by system)
INSERT INTO platform_commands (tenant_id, action, resource_type, resource_id, user_id, metadata)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'payout_completed',
    'payout_batch',
    '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    'system',
    '{"payment_reference": "tr_1A2B3C4D5E6F", "transfer_amount": 1500.00, "stripe_account_id": "acct_xxx"}'::jsonb
);

-- Payout transfer failed
INSERT INTO platform_commands (tenant_id, action, resource_type, resource_id, user_id, metadata)
VALUES (
    '550e8400-e29b-41d4-a716-446655440000',
    'payout_failed',
    'payout_batch',
    '9e8g6791-9647-62gf-b66d-g29be3h12cg9',
    'system',
    '{"error_code": "balance_insufficient", "error_message": "Insufficient funds in platform balance", "retry_count": 1}'::jsonb
);
```

#### Impersonation Logging Requirements

From ADR-001 (Multi-Tenancy):
- Platform admins can impersonate tenant admins for support
- All impersonation sessions must be logged
- Audit log captures both `user_id` (impersonated user) and `metadata.impersonator_id`

**Implementation Contract:**
```java
@POST
@Path("/payout-batches/{batchId}/approve")
public Response approvePayoutBatch(
    @PathParam("batchId") UUID batchId,
    @HeaderParam("X-Ticket-Number") String ticketNumber,
    @HeaderParam("X-User-Context") String userId,  // From JWT 'sub' claim
    @Context SecurityContext securityContext
) {
    // Check if request is impersonated
    String impersonatorId = securityContext.getUserPrincipal()
        .getClaim("impersonator_id"); // Present if impersonation active

    // Log audit trail
    PlatformCommand auditLog = new PlatformCommand();
    auditLog.action = "approve_payout_batch";
    auditLog.userId = userId;
    auditLog.ticketNumber = ticketNumber;

    if (impersonatorId != null) {
        auditLog.metadata = Json.createObjectBuilder()
            .add("impersonator_id", impersonatorId)
            .add("impersonation_session_id", securityContext.getUserPrincipal().getClaim("session_id"))
            .build().toString();
    }

    auditLog.persist();

    // Proceed with approval
    consignmentService.approvePayoutBatch(batchId, userId);

    return Response.accepted().build();
}
```

### 6. Reconciliation & Error Handling

#### Aggregate Validation Before Batch Creation

**Guard Rail:** Ensure reporting aggregates are fresh (<30 min SLA) before creating batches.

```java
public void createPayoutBatchesForPeriod(LocalDate periodStart, LocalDate periodEnd) {
    // Fetch aggregates
    List<ConsignmentPayoutAggregate> aggregates = reportingProjectionService
        .getConsignmentPayoutAggregates(periodStart, periodEnd);

    // Validate freshness
    OffsetDateTime freshnessThreshold = OffsetDateTime.now().minusMinutes(30);
    for (ConsignmentPayoutAggregate agg : aggregates) {
        if (agg.dataFreshnessTimestamp.isBefore(freshnessThreshold)) {
            LOG.warnf("Stale aggregate detected for consignor %s (lag: %d minutes). Refreshing...",
                agg.consignorId,
                Duration.between(agg.dataFreshnessTimestamp, OffsetDateTime.now()).toMinutes());

            // Trigger aggregate refresh
            reportingProjectionService.refreshConsignmentPayoutAggregates();

            // Emit warning metric
            meterRegistry.counter("consignment.payout.stale_data_detected").increment();

            // Re-fetch after refresh
            aggregates = reportingProjectionService.getConsignmentPayoutAggregates(periodStart, periodEnd);
            break;
        }
    }

    // Proceed with batch creation
    // ...
}
```

#### Stripe Transfer Failure Handling

**Failure Categories:**

| Error Code | Category | Retry Strategy | Resolution |
|------------|----------|----------------|------------|
| `balance_insufficient` | Temporary | 3x with exponential backoff (1h, 4h, 12h) | Admin funds platform Stripe balance |
| `account_invalid` | Permanent | No retry | Consignor re-completes Stripe onboarding |
| `rate_limit_exceeded` | Temporary | Retry after rate limit window (60s) | Wait for Stripe rate limit reset |
| `network_error` / timeout | Uncertain | Mark 'uncertain', await webhook | Webhook reconciles within 24h |
| `invalid_request` | Permanent | No retry, alert admin | Code bug or config issue |

**Retry Implementation (Quarkus Scheduler):**
```java
@Scheduled(every = "1h")
public void retryFailedPayouts() {
    List<PayoutBatch> failedBatches = PayoutBatch.find(
        "status = 'failed' AND error_message LIKE '%balance_insufficient%'"
    ).list();

    for (PayoutBatch batch : failedBatches) {
        int retryCount = extractRetryCount(batch.errorMessage);
        if (retryCount >= 3) {
            LOG.warnf("Batch %s exceeded retry limit. Escalating to manual intervention.", batch.id);
            notificationService.notifyAdminPayoutFailed(batch);
            continue;
        }

        // Retry transfer
        try {
            consignmentService.executeStripeTransfer(batch.id);
        } catch (StripeException e) {
            LOG.errorf(e, "Retry failed for batch %s (attempt %d)", batch.id, retryCount + 1);
        }
    }
}
```

#### Webhook Reconciliation (Uncertain Transfers)

**Scenario:** Stripe transfer succeeds but HTTP response lost due to network timeout.

**Reconciliation Flow:**
1. Batch marked `status='uncertain'` with error_message = 'stripe_timeout_awaiting_webhook'
2. Admin notified via email: "Payout batch {id} in uncertain state, awaiting confirmation"
3. Stripe webhook `transfer.paid` or `transfer.failed` event received within 24h
4. Webhook handler updates batch status to `completed` or `failed`
5. If no webhook received after 24h, alert admin for manual Stripe dashboard check

**Webhook Handler:**
```java
@POST
@Path("/webhooks/stripe")
public Response handleStripeWebhook(String payload, @HeaderParam("Stripe-Signature") String signature) {
    // Verify webhook signature (prevent replay attacks)
    Event event = Webhook.constructEvent(payload, signature, tenantStripeWebhookSecret);

    if ("transfer.paid".equals(event.getType())) {
        Transfer transfer = (Transfer) event.getDataObjectDeserializer().getObject().orElseThrow();
        String batchId = transfer.getMetadata().get("batch_id");

        PayoutBatch batch = PayoutBatch.findById(UUID.fromString(batchId));
        if (batch != null && "uncertain".equals(batch.status)) {
            batch.status = "completed";
            batch.processedAt = OffsetDateTime.now();
            batch.paymentReference = transfer.getId();
            batch.persist();

            // Log reconciliation
            platformCommandService.logAction(
                "payout_reconciled_via_webhook",
                "payout_batch",
                batch.id,
                "system",
                Map.of("transfer_id", transfer.getId())
            );

            // Send consignor notification
            notificationService.sendPayoutConfirmationEmail(batch);
        }
    }

    return Response.ok().build();
}
```

### 7. Observability & Monitoring

#### Metrics (Micrometer)

**Counters:**
```java
// Batch lifecycle
meterRegistry.counter("consignment.payout.batch.created", "tenant_id", tenantId).increment();
meterRegistry.counter("consignment.payout.batch.completed", "tenant_id", tenantId).increment();
meterRegistry.counter("consignment.payout.batch.failed", "tenant_id", tenantId, "error_code", errorCode).increment();

// Transfer operations
meterRegistry.counter("consignment.payout.transfer.started").increment();
meterRegistry.counter("consignment.payout.transfer.succeeded").increment();
meterRegistry.counter("consignment.payout.transfer.failed", "error_code", stripeErrorCode).increment();

// Reconciliation
meterRegistry.counter("consignment.payout.webhook.received", "event_type", eventType).increment();
meterRegistry.counter("consignment.payout.reconciled_via_webhook").increment();
```

**Gauges:**
```java
// Per-tenant pending payout amounts
Gauge.builder("consignment.payout.pending.amount", pendingAmountReference,
    ref -> ref.get().doubleValue())
    .description("Total pending consignment payout amount for tenant")
    .tag("tenant_id", tenantId.toString())
    .register(meterRegistry);

// Count of batches by status
Gauge.builder("consignment.payout.batches.count", () ->
    PayoutBatch.count("status = ?1 AND tenant_id = ?2", "pending", tenantId))
    .tag("tenant_id", tenantId.toString())
    .tag("status", "pending")
    .register(meterRegistry);
```

**Timers:**
```java
// Transfer API call latency
Timer.Sample sample = Timer.start(meterRegistry);
try {
    Transfer transfer = stripeConnectService.createTransfer(batch);
    sample.stop(meterRegistry.timer("consignment.payout.transfer.latency",
        "outcome", "success"));
} catch (StripeException e) {
    sample.stop(meterRegistry.timer("consignment.payout.transfer.latency",
        "outcome", "failure", "error_code", e.getCode()));
}
```

#### OpenTelemetry Tracing Spans

```java
@WithSpan(value = "ConsignmentPayout.createBatches")
public void createPayoutBatchesForPeriod(LocalDate periodStart, LocalDate periodEnd) {
    Span.current().setAttribute("period_start", periodStart.toString());
    Span.current().setAttribute("period_end", periodEnd.toString());
    Span.current().setAttribute("tenant_id", TenantContext.getCurrentTenantId().toString());

    // Stage 1: Aggregate validation
    try (Scope scope = tracer.spanBuilder("ConsignmentPayout.aggregateValidation").startSpan()) {
        // ... validation logic
    }

    // Stage 2: Batch creation
    try (Scope scope = tracer.spanBuilder("ConsignmentPayout.batchCreation").startSpan()) {
        // ... creation logic
    }
}

@WithSpan(value = "StripeConnectService.createTransfer")
public Transfer createTransfer(PayoutBatch batch) {
    Span.current().setAttribute("batch_id", batch.id.toString());
    Span.current().setAttribute("amount", batch.totalAmount.toString());
    Span.current().setAttribute("consignor_id", batch.consignor.id.toString());

    // ... Stripe API call
}
```

#### Alerting Thresholds

**Critical Alerts (PagerDuty):**
- Payout batch stuck in `processing` for >2 hours
- 3+ consecutive transfer failures for same batch
- Webhook reconciliation timeout (batch in `uncertain` for >24h)
- Aggregate freshness lag >60 minutes during batch creation

**Warning Alerts (Slack):**
- Manual approval backlog >10 batches
- Failed payout batch (first occurrence)
- Stale aggregate detected and refreshed
- Stripe API rate limit approached (>80% of limit)

---

## Rationale

### Why Staged Automation (Not Fully Automated)?

| Option | Pros | Cons | Analysis |
|--------|------|------|----------|
| **Fully Automated** | Zero admin overhead, fastest payouts | Higher fraud risk, no human oversight for anomalies | Rejected: Financial liability too high for MVP |
| **Fully Manual** | Maximum control, lowest fraud risk | Slow payouts, high admin time cost | Rejected: Not scalable beyond 50 consignors |
| **Staged Automation** | Balances risk/speed, auto-approve low-risk batches | Approval queue for high-value/new consignors | **Selected**: Best fit for MVP risk profile |

**Decision:** Staged automation reduces admin burden (80% of batches auto-approved) while preserving oversight for high-risk transfers (>$500, new consignors).

### Why Stripe Connect Express (Not Standard)?

- **Express:** Simplified onboarding UI (Stripe hosts forms), reduced liability (Stripe handles KYC)
- **Standard:** More control over onboarding flow, but increased compliance burden
- **Custom:** Full control, but must implement KYC/AML ourselves (regulatory minefield)

**Decision:** Express offers fastest time-to-market with lowest compliance risk.

### Why Webhook Reconciliation (Not Polling)?

- **Webhooks:** Real-time status updates (latency <1s), no polling overhead
- **Polling:** Simple implementation, but introduces lag (5-60s) and API rate limit risk

**Decision:** Webhooks are industry standard for payment reconciliation. Polling fallback available for webhook delivery failures.

### Why Optimistic Locking (`@Version`) on payout_batches?

**Scenario:** Two admins approve same batch concurrently (race condition).

**Without Locking:**
```
Admin A: Read batch (status=pending) → Approve → Update status=awaiting_approval
Admin B: Read batch (status=pending) → Approve → Update status=awaiting_approval
Result: Batch approved twice, potentially double-transferred
```

**With Optimistic Locking:**
```
Admin A: Read batch (version=1, status=pending) → Approve → UPDATE WHERE version=1 (succeeds, version→2)
Admin B: Read batch (version=1, status=pending) → Approve → UPDATE WHERE version=1 (fails, OptimisticLockException)
Result: Admin B receives error, batch approved once
```

**Decision:** `@Version` prevents double-approval without database-level pessimistic locks (better performance).

### Alternatives Considered

1. **Event Sourcing (Full Audit Log via Events):**
   - Pro: Complete state change history, replay capability
   - Con: Increased complexity (event store, projections), overkill for MVP
   - Rejected: `platform_commands` table provides sufficient audit trail

2. **Manual Reconciliation (No Webhooks):**
   - Pro: Simpler implementation (no webhook endpoint security)
   - Con: Requires daily manual Stripe dashboard checks, slower payout confirmations
   - Rejected: Webhooks are industry standard, security mitigated via signature verification

3. **Immediate Transfer (No Approval Stage):**
   - Pro: Fastest payout (< 1 hour after batch creation)
   - Con: No fraud review window, high risk for chargebacks
   - Rejected: Financial risk too high without approval gate

---

## Consequences

### Positive Consequences

1. **Reduced Admin Overhead:** 80% of batches auto-approved (low-risk consignors), freeing admin time
2. **Audit Compliance:** Full trail in `platform_commands` for financial audits, dispute resolution
3. **Fraud Prevention:** Multi-stage validation (aggregate reconciliation, Stripe account checks, approval gates)
4. **Scalability:** Automated batch creation scales to 1000+ consignors without manual intervention
5. **Idempotency Guarantees:** Duplicate transfers prevented via Stripe idempotency keys (batch.id)

### Negative Consequences

1. **Approval Latency:** High-value batches wait for admin approval (target <24h, but depends on admin availability)
   - **Mitigation:** Admin notification emails, approval queue dashboard widget
2. **Webhook Dependency:** Reconciliation relies on Stripe webhook delivery (99.9% SLA, but not 100%)
   - **Mitigation:** 24-hour timeout escalation, manual Stripe dashboard check fallback
3. **Aggregate Freshness Risk:** Batch creation fails if aggregates stale (>30 min lag)
   - **Mitigation:** Automatic aggregate refresh triggered on staleness detection
4. **Stripe Account Complexity:** Consignors must complete KYC onboarding (identity verification, bank account)
   - **Mitigation:** In-app onboarding wizard, support documentation, email reminders

### Technical Debt Accepted

- **No Partial Payout Support (MVP):** If one line item in batch disputed, entire batch must be manually adjusted
  - **Future:** Line-item-level payout adjustments with partial transfer reversal (planned Q2 2026)
- **No Multi-Currency Payouts:** All payouts in USD only
  - **Future:** Stripe Connect supports 135+ currencies, extensible via `currency` column (planned Q3 2026)
- **No ACH Direct Deposit Option:** Stripe Connect transfers only (consignor must have Stripe account)
  - **Future:** Plaid integration for direct ACH payouts to consignor bank accounts (planned Q4 2026)

### Risks Introduced

**RISK-005:** Stripe Connect account compromise (consignor credentials stolen)
- **Likelihood:** Low (Stripe handles auth, 2FA required)
- **Impact:** Critical (fraudulent payout redirection)
- **Mitigation:** Stripe monitors for suspicious account changes, email notifications on payout destination updates

**RISK-006:** Aggregate calculation drift (reporting aggregates diverge from actual sales)
- **Likelihood:** Medium (eventual consistency lag during high-volume periods)
- **Impact:** High (incorrect payout amounts, consignor disputes)
- **Mitigation:** Daily reconciliation job compares aggregates to raw sales data, alerts on >1% variance

**RISK-007:** Approval queue backlog (admin unavailable for >48h)
- **Likelihood:** Low (multiple admins per tenant)
- **Impact:** Medium (consignor payout delay, support complaints)
- **Mitigation:** Auto-escalate to platform admins after 48h, consignor notification of delay

### Impact on Future Decisions

- **ADR-005 (Notification System):** Must support transactional emails for payout confirmations, failures, admin alerts
- **ADR-006 (Multi-Currency Support):** Payout currency column already in schema, requires Stripe Connect currency capability checks
- **ADR-007 (Refunds & Chargebacks):** Payout reversals must deduct from future batches or create negative line items

---

## Implementation Checklist

- [x] Create `payout_batches` and `payout_line_items` tables (migration completed in I3.T1)
- [x] Implement `ConsignmentService` batch creation methods (see ConsignmentService.java:342-384)
- [x] Add Stripe Connect account ID storage in `consignor.payout_settings` JSONB (see Consignor.java:74-79)
- [ ] Implement Stripe Connect OAuth flow (vendor portal onboarding endpoint)
- [ ] Add approval API endpoint with ticket number validation (`POST /payout-batches/{id}/approve`)
- [ ] Implement background job for Stripe transfer execution (`ExecuteStripeTransferJob`)
- [ ] Add webhook endpoint for `transfer.paid` / `transfer.failed` reconciliation
- [ ] Implement retry logic for failed transfers (exponential backoff scheduler)
- [ ] Add `platform_commands` audit logging to all admin actions
- [ ] Implement aggregate freshness validation before batch creation
- [ ] Add impersonation logging support (capture `impersonator_id` in metadata JSONB)
- [ ] Create admin dashboard widget for approval queue (Vue.js component)
- [ ] Implement email notifications (consignor payout confirmation, admin failure alerts)
- [ ] Add Micrometer metrics for batch lifecycle events (created, completed, failed)
- [ ] Add OpenTelemetry tracing spans for transfer execution
- [ ] Write integration tests verifying:
  - [ ] Duplicate batch prevention (unique constraint test)
  - [ ] Optimistic locking on concurrent approval (version conflict test)
  - [ ] Idempotent Stripe transfers (same batch.id retry test)
  - [ ] Webhook reconciliation for uncertain batches
  - [ ] Tenant isolation (consignor A cannot see consignor B's batches)
- [ ] Document Stripe Connect onboarding flow in admin guide
- [ ] Create runbook for manual intervention (failed batch resolution steps)

---

## References

- **Standards:** `docs/java-project-standards.adoc` (Sections 5, 7, 12)
- **Architecture:** `docs/architecture_overview.md` (Section 3.2.6: Consignment Module)
- **Reporting Projections:** `docs/reporting/projections.md` (Section 75-102: Consignment Payout Aggregate)
- **ADR-001:** Multi-Tenant Tenant Isolation (TenantContext, audit logging, impersonation)
- **ADR-003:** Checkout Saga Pattern (idempotency keys, compensation logic)
- **Sequence Diagram:** `docs/diagrams/sequence_consignment_payout.mmd`
- **ConsignmentService:** `src/main/java/villagecompute/storefront/services/ConsignmentService.java`
- **PayoutBatch Entity:** `src/main/java/villagecompute/storefront/data/models/PayoutBatch.java`
- **Stripe Connect API:** [Stripe Connect Transfers](https://stripe.com/docs/connect/payouts)
- **Stripe Connect Express:** [Connect Express Onboarding](https://stripe.com/docs/connect/express-accounts)
- **Stripe Webhooks:** [Webhook Event Reference](https://stripe.com/docs/api/events/types)
- **Stripe Idempotency:** [Idempotent Requests](https://stripe.com/docs/api/idempotent_requests)
- **Prior Art:**
  - Shopify Collective Payout Flow: [Shopify Partner Docs](https://shopify.dev/docs/apps/fulfillment/collective)
  - Etsy Seller Payments: [Etsy Seller Handbook](https://www.etsy.com/seller-handbook/article/understanding-payment-account/22271474634)

---

**Document Version:** 1.0
**Last Updated:** 2026-01-03
**Maintained By:** Architecture Team, Finance Lead
**Next Review:** Q2 2026 (after first production payout cycle)
