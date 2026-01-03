# Stripe Connect Integration Guide

This document describes the Stripe Connect integration for Village Storefront, including setup, onboarding workflows, platform fees, payouts, and webhook handling.

## Table of Contents

1. [Overview](#overview)
2. [Configuration](#configuration)
3. [Architecture](#architecture)
4. [Onboarding Workflow](#onboarding-workflow)
5. [Platform Fees](#platform-fees)
6. [Payout Management](#payout-management)
7. [Webhook Integration](#webhook-integration)
8. [Testing](#testing)
9. [Troubleshooting](#troubleshooting)

---

## Overview

Village Storefront uses **Stripe Connect** to enable marketplace functionality, allowing:

- **Platform revenue collection** via configurable platform fees
- **Consignor/vendor onboarding** with Express or Standard accounts
- **Split payments** routing funds to connected accounts
- **Automated payouts** to vendors based on consignment sales
- **Dispute management** and chargeback handling

### Key Features

- ✅ Multi-tenant platform fee configuration
- ✅ Webhook-driven event processing with idempotency
- ✅ Payment provider abstraction for future extensibility
- ✅ Comprehensive audit logging and metrics
- ✅ Test mode support for development

---

## Configuration

### Environment Variables

Configure Stripe via environment variables or application properties:

```bash
# Stripe API Keys
STRIPE_SECRET_KEY=sk_test_...                    # or sk_live_...
STRIPE_PUBLISHABLE_KEY=pk_test_...              # or pk_live_...
STRIPE_WEBHOOK_SECRET=whsec_...                 # Webhook signing secret

# Optional Configuration
STRIPE_TEST_MODE=true                            # Enable test mode (default: true)
```

### Application Properties

Configuration is defined in `src/main/resources/application.properties`:

```properties
# Stripe Payment Provider Configuration
stripe.api.secret-key=${STRIPE_SECRET_KEY:sk_test_replace_with_your_key}
stripe.api.publishable-key=${STRIPE_PUBLISHABLE_KEY:pk_test_replace_with_your_key}
stripe.webhook.signing-secret=${STRIPE_WEBHOOK_SECRET:whsec_replace_with_your_secret}
stripe.api.version=2023-10-16
stripe.connect.enabled=true
stripe.api.max-retries=3
stripe.api.timeout-ms=30000
stripe.test-mode=${STRIPE_TEST_MODE:true}
```

### Kubernetes Secrets

For production deployments, store credentials in Kubernetes Secrets:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: stripe-credentials
type: Opaque
stringData:
  STRIPE_SECRET_KEY: sk_live_...
  STRIPE_PUBLISHABLE_KEY: pk_live_...
  STRIPE_WEBHOOK_SECRET: whsec_...
```

Mount secrets as environment variables in the deployment:

```yaml
spec:
  containers:
    - name: storefront
      env:
        - name: STRIPE_SECRET_KEY
          valueFrom:
            secretKeyRef:
              name: stripe-credentials
              key: STRIPE_SECRET_KEY
```

---

## Architecture

### Payment Provider Abstraction

Village Storefront implements a provider-agnostic payment interface:

```
┌─────────────────────────────────────────────┐
│         Business Logic Layer                │
│  (CartService, CheckoutOrchestrator, etc.)  │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│       Payment Provider Interfaces           │
│  - PaymentProvider                          │
│  - PaymentMethodProvider                    │
│  - MarketplaceProvider                      │
│  - WebhookHandler                           │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│         Stripe Implementation               │
│  - StripePaymentProvider                    │
│  - StripePaymentMethodProvider              │
│  - StripeMarketplaceProvider                │
│  - StripeWebhookHandler                     │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
              Stripe API
```

### Data Models

#### PaymentIntent

Tracks payment lifecycle with provider-agnostic status:

```java
PaymentIntent {
    id: Long
    tenantId: String
    provider: String                    // "stripe"
    providerPaymentId: String           // Stripe payment intent ID
    amount: BigDecimal
    status: PaymentStatus               // PENDING, AUTHORIZED, CAPTURED, etc.
    captureMethod: CaptureMethod        // AUTOMATIC, MANUAL
    amountCaptured: BigDecimal
    amountRefunded: BigDecimal
}
```

#### WebhookEvent

Ensures webhook idempotency and provides audit trail:

```java
WebhookEvent {
    id: Long
    tenantId: String
    provider: String
    providerEventId: String             // Unique, indexed
    eventType: String                   // "payment_intent.succeeded"
    payload: String                     // Raw JSON
    processed: boolean
    processingError: String
    receivedAt: Instant
    processedAt: Instant
}
```

### Payment Method Management

`StripePaymentMethodProvider` wraps Stripe's PaymentMethod APIs to attach customer-approved `pm_` tokens, apply billing metadata, list saved payment methods, and promote a default card/bank account per tenant. Backend orchestration only ever handles ephemeral tokens, so PCI scope remains on Stripe elements. Helper metrics `payments.methods.*` track creation/deletion/default changes for observability.

#### PlatformFeeConfig

Tenant-specific fee configuration:

```java
PlatformFeeConfig {
    id: Long
    tenantId: String
    feePercentage: BigDecimal           // e.g., 0.0500 for 5%
    fixedFeeAmount: BigDecimal          // e.g., 0.30
    minimumFee: BigDecimal
    maximumFee: BigDecimal
    effectiveFrom: Instant
    effectiveTo: Instant
}
```

---

## Onboarding Workflow

### Express Account Onboarding

Stripe Express provides a simplified onboarding flow for connected accounts:

#### Step 1: Initiate Onboarding

```java
@Inject
StripeMarketplaceProvider marketplaceProvider;

// Create onboarding request
MarketplaceProvider.OnboardingRequest request = new OnboardingRequest(
    "vendor@example.com",
    "Vendor Business Name",
    "US",
    OnboardingType.EXPRESS,
    "https://yoursite.com/onboarding/complete",  // Return URL
    "https://yoursite.com/onboarding/refresh",   // Refresh URL
    Map.of("consignor_id", "12345")
);

// Begin onboarding
MarketplaceProvider.OnboardingResult result = marketplaceProvider.beginOnboarding(request);

// Store connected account ID
ConnectAccount connectAccount = new ConnectAccount();
connectAccount.tenantId = tenantContext.getCurrentTenantId();
connectAccount.provider = "stripe";
connectAccount.providerAccountId = result.connectedAccountId();
connectAccount.consignorId = consignorId;
connectAccount.onboardingStatus = result.status();
connectAccount.onboardingUrl = result.onboardingUrl();
connectAccount.persist();

// Redirect user to onboarding URL
return Response.seeOther(URI.create(result.onboardingUrl())).build();
```

#### Step 2: Handle Return Flow

After the vendor completes onboarding, Stripe redirects to your `returnUrl`:

```java
@GET
@Path("/onboarding/complete")
public Response handleOnboardingComplete(@QueryParam("account_id") String accountId) {
    ConnectAccount account = ConnectAccount.findByProviderAccountId(accountId);

    // Check onboarding status
    OnboardingStatus status = marketplaceProvider.getOnboardingStatus(accountId);
    account.onboardingStatus = status;

    if (status == OnboardingStatus.COMPLETED) {
        account.payoutsEnabled = true;
        account.chargesEnabled = true;
        // Notify consignor that onboarding is complete
    }

    return Response.ok("Onboarding complete!").build();
}
```

#### Step 3: Monitor via Webhooks

Stripe sends `account.updated` webhooks when account capabilities change:

```java
private void handleAccountUpdated(String payload, String tenantId) {
    // Parse account from webhook payload
    Account account = parseStripeAccount(payload);

    ConnectAccount connectAccount = ConnectAccount.findByProviderAccountId(account.getId());
    if (connectAccount != null) {
        connectAccount.onboardingStatus = mapOnboardingStatus(account);
        connectAccount.payoutsEnabled = account.getPayoutsEnabled();
        connectAccount.chargesEnabled = account.getChargesEnabled();
    }
}
```

---

## Platform Fees

### Configuration

Platform fees are configured per tenant and can include:

- **Percentage fee** (e.g., 5% of transaction)
- **Fixed fee** (e.g., $0.30 per transaction)
- **Minimum fee** (e.g., $0.50)
- **Maximum fee** (e.g., $50.00)

### Fee Calculation

```java
@Inject
PaymentService paymentService;

// Calculate fee for a $100 transaction
BigDecimal transactionAmount = new BigDecimal("100.00");
MarketplaceProvider.PlatformFeeCalculation calc =
    paymentService.calculatePlatformFee(transactionAmount);

// Example with 5% + $0.30 fee:
// calc.platformFeeAmount()    => $5.30
// calc.netAmount()            => $94.70
```

### Fee Formula

```
Platform Fee = max(
    min(
        (transactionAmount × feePercentage) + fixedFeeAmount,
        maximumFee
    ),
    minimumFee
)

Net Amount = transactionAmount - Platform Fee
```

### Updating Fee Configuration

```java
// Update platform fee to 7.5% + $0.50
PlatformFeeConfig config = paymentService.updatePlatformFeeConfig(
    new BigDecimal("0.0750"),  // 7.5%
    new BigDecimal("0.50"),    // Fixed fee
    new BigDecimal("1.00"),    // Minimum
    new BigDecimal("50.00")    // Maximum
);
```

---

## Payout Management

### Creating Payouts

Payouts are created when consignment items are sold and payout batches are approved:

```java
@Inject
StripeMarketplaceProvider marketplaceProvider;

// Create payout to connected account
MarketplaceProvider.PayoutRequest request = new PayoutRequest(
    connectedAccountId,         // Stripe Connect account ID
    new BigDecimal("94.70"),    // Net amount after fees
    "USD",
    "Payout for batch #123",
    Map.of("batch_id", "123"),
    "payout-batch-123-" + timestamp  // Idempotency key
);

MarketplaceProvider.PayoutResult result = marketplaceProvider.createPayout(request);

// Update PayoutBatch with payout ID
payoutBatch.paymentReference = result.payoutId();
payoutBatch.status = "processing";
payoutBatch.estimatedArrival = result.estimatedArrival();
```

### Payout Lifecycle

1. **Created** → Payout initiated via API
2. **Pending** → Payout queued by Stripe
3. **In Transit** → Funds being transferred to bank
4. **Paid** → Funds arrived in recipient's bank account
5. **Failed** → Transfer failed (insufficient funds, invalid account)

### Webhook Updates

Handle `payout.paid` and `payout.failed` webhooks to update batch status:

```java
private void handlePayoutPaid(String payload, String tenantId) {
    Payout payout = parseStripePayout(payload);

    PayoutBatch batch = PayoutBatch.find("paymentReference", payout.getId()).firstResult();
    if (batch != null) {
        batch.status = "completed";
        batch.completedAt = Instant.now();
    }
}
```

---

## Webhook Integration

### Webhook Setup

1. **Configure endpoint in Stripe Dashboard:**
   - URL: `https://yoursite.com/api/webhooks/payments/stripe`
   - Events to send: Select all payment and Connect events

2. **Copy webhook signing secret** from Stripe Dashboard

3. **Set environment variable:**
   ```bash
   STRIPE_WEBHOOK_SECRET=whsec_...
   ```

### Webhook Events Handled

| Event Type | Description | Handler Action |
|------------|-------------|----------------|
| `payment_intent.succeeded` | Payment completed | Update PaymentIntent, trigger order completion |
| `payment_intent.payment_failed` | Payment failed | Mark PaymentIntent as failed, notify customer |
| `payment_intent.canceled` | Payment canceled | Release inventory, update status |
| `charge.refunded` | Refund processed | Update refund amount, trigger refund workflow |
| `charge.dispute.created` | Dispute opened | Create dispute record, notify merchant |
| `payout.paid` | Payout completed | Mark PayoutBatch as completed |
| `payout.failed` | Payout failed | Mark PayoutBatch as failed, notify admin |
| `account.updated` | Connected account changed | Update ConnectAccount capabilities |

### Idempotency

All webhook events are deduplicated using `providerEventId`:

```java
WebhookEvent existingEvent = WebhookEvent.findByProviderEventId(eventId);
if (existingEvent != null) {
    // Already processed, return success without re-processing
    return new WebhookProcessingResult(true, true, existingEvent.id.toString(), null);
}
```

### Signature Verification

Webhooks are verified using Stripe's signature:

```java
boolean valid = webhookHandler.verifySignature(
    payload,
    stripeSignatureHeader,
    webhookSigningSecret
);

if (!valid) {
    return Response.status(400).entity("Invalid signature").build();
}
```

---

## Testing

### Test Mode

Enable test mode to use Stripe's sandbox:

```properties
stripe.test-mode=true
stripe.api.secret-key=sk_test_...
```

### Test Cards

Use Stripe's test cards for payment testing:

- **Successful payment:** `4242 4242 4242 4242`
- **Declined payment:** `4000 0000 0000 0002`
- **Authentication required:** `4000 0025 0000 3155`

### Stripe CLI for Webhooks

Install and use Stripe CLI to test webhooks locally:

```bash
# Install Stripe CLI
brew install stripe/stripe-cli/stripe

# Forward webhooks to local dev server
stripe listen --forward-to localhost:8080/api/webhooks/payments/stripe

# Trigger test events
stripe trigger payment_intent.succeeded
stripe trigger payout.paid
```

### Running Integration Tests

```bash
# Set test credentials
export STRIPE_TEST_MODE=true
export STRIPE_SECRET_KEY=sk_test_...
export STRIPE_WEBHOOK_SECRET=whsec_...

# Run payment provider tests
./mvnw test -Dtest=StripeProviderTest

# Run webhook integration tests
./mvnw test -Dtest=StripeWebhookIT
```

---

## Troubleshooting

### Common Issues

#### 1. Webhook Signature Verification Failed

**Cause:** Incorrect webhook secret or payload modification

**Solution:**
- Verify `STRIPE_WEBHOOK_SECRET` matches Stripe Dashboard
- Ensure payload is raw body (not parsed JSON)
- Check for proxy/middleware modifying request

#### 2. Payment Intent Creation Fails

**Cause:** Invalid API key, insufficient permissions, or network error

**Solution:**
- Verify `STRIPE_SECRET_KEY` is correct
- Check API key has necessary permissions
- Review Stripe API logs in Dashboard
- Check retry configuration and network connectivity

#### 3. Payout Fails with "Insufficient Funds"

**Cause:** Platform account balance too low to cover payout

**Solution:**
- Ensure platform Stripe balance has sufficient funds
- Consider adjusting payout schedule
- Review platform fee collection

#### 4. Onboarding Link Expired

**Cause:** Account link expires after 24 hours

**Solution:**
- Generate new account link using `refreshUrl`
- Implement auto-refresh logic for long onboarding processes

### Monitoring & Observability

#### Metrics

Monitor these key metrics:

```
payments.intent.created{tenant,provider,currency}        # Payment intents created
payments.intent.failed{tenant,provider,error}            # Payment failures
payments.captured{tenant,provider}                       # Successful captures
payments.refunded{tenant,provider,reason}                # Refunds processed
webhooks.processed{tenant,provider,event_type}           # Webhooks processed
webhooks.processing.failed{tenant,provider,event_type}   # Webhook failures
marketplace.onboarding.started{tenant,type}              # Onboardings initiated
marketplace.payout.created{tenant,provider}              # Payouts created
```

#### Logs

All payment operations emit structured logs with tenant and payment IDs:

```
[Tenant: tenant-123] Created Stripe payment intent: pi_abc123, amount: 100.00 USD
[Tenant: tenant-123] Captured payment: id=456, amount=100.00
[Tenant: tenant-123] Processing payout.paid webhook
```

#### Alerts

Configure alerts for:

- High payment failure rate (> 5%)
- Webhook processing failures
- Payout failures
- Dispute creation
- API rate limit warnings

---

## Additional Resources

- [Stripe Connect Documentation](https://stripe.com/docs/connect)
- [Stripe API Reference](https://stripe.com/docs/api)
- [Stripe Webhooks Guide](https://stripe.com/docs/webhooks)
- [ADR-004: Consignment Payouts](../adr/ADR-004-consignment-payouts.md)

---

**Document Version:** 1.0
**Last Updated:** 2026-01-03
**Maintained By:** Village Storefront Team
