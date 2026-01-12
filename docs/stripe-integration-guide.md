# Stripe Payment Integration Guide

## Overview

Village Storefront integrates with Stripe for payment processing, using Stripe Connect for marketplace features. This guide covers the payment flow, webhook handling, and local development setup.

## Architecture

### Components

1. **StripePaymentProvider** - Core payment provider implementation
   - Location: `src/main/java/villagecompute/storefront/payment/stripe/StripePaymentProvider.java`
   - Implements `PaymentProvider` interface
   - Handles payment intent creation, capture, refund, and cancellation
   - Emits metrics for observability
   - Supports stub mode for testing without API keys

2. **StripeWebhookHandler** - Webhook event processor
   - Location: `src/main/java/villagecompute/storefront/payment/stripe/StripeWebhookHandler.java`
   - Implements `WebhookHandler` interface
   - Verifies webhook signatures
   - Provides idempotency via event persistence
   - Updates local payment state from Stripe events

3. **PaymentIntentResource** - REST API endpoints
   - Location: `src/main/java/villagecompute/storefront/api/rest/PaymentIntentResource.java`
   - Exposes payment operations via REST
   - Handles idempotency via Idempotency-Key header
   - Returns RFC7807 Problem Details for errors

4. **PaymentWebhookResource** - Webhook ingestion endpoint
   - Location: `src/main/java/villagecompute/storefront/api/rest/PaymentWebhookResource.java`
   - Receives Stripe webhook events
   - Routes to StripeWebhookHandler for processing
   - Path: `/api/webhooks/payments/stripe`

5. **PaymentService** - Business logic orchestration
   - Location: `src/main/java/villagecompute/storefront/services/PaymentService.java`
   - Coordinates provider operations with local persistence
   - Manages payment lifecycle
   - Handles platform fee calculations

## Payment Flow

### 1. Create Payment Intent

```http
POST /api/v1/payments/intents
Content-Type: application/json
Idempotency-Key: unique-key-123

{
  "amount": 100.00,
  "currency": "USD",
  "orderId": "uuid-here",
  "captureImmediately": false,
  "idempotencyKey": "unique-key-123"
}
```

Response:
```json
{
  "id": 123,
  "providerPaymentId": "pi_abc123",
  "clientSecret": "pi_abc123_secret_xyz",
  "status": "PENDING",
  "amount": 100.00,
  "currency": "USD",
  "orderId": "uuid-here",
  "metadata": {
    "order_id": "uuid-here",
    "platform_fee_amount": "3.80",
    "platform_fee_percentage": "0.0300",
    "platform_net_amount": "96.20"
  }
}
```

Every payment intent now stores structured metadata so downstream systems can audit platform fees:

- `order_id` – The order UUID associated with the payment intent.
- `platform_fee_amount` – Total application fee taken by the platform (in major currency units).
- `platform_fee_percentage` – Percentage applied from the tenant’s `PlatformFeeConfig`.
- `platform_net_amount` – Amount that will be transferred to the connected account after fees.

These metadata fields are persisted in the `payment_intents` table and returned from the REST API for observability.

### 2. Client-Side Confirmation

Use the `clientSecret` with Stripe.js or mobile SDKs:

```javascript
const stripe = Stripe('pk_test_...');
const {error, paymentIntent} = await stripe.confirmCardPayment(clientSecret, {
  payment_method: {
    card: cardElement,
    billing_details: {name: 'Customer Name'}
  }
});
```

### 3. Webhook Notification

Stripe sends webhook events to `/api/webhooks/payments/stripe`:

- `payment_intent.succeeded` - Payment completed successfully
- `payment_intent.payment_failed` - Payment failed
- `charge.refunded` - Refund processed
- `charge.dispute.created` - Dispute filed

The webhook handler:
1. Verifies signature
2. Checks for duplicate events (idempotency)
3. Persists event to `webhook_events` table
4. Updates local `payment_intents` entity and synchronizes `orders`:
   - `payment_intent.succeeded` triggers `OrderService.markOrderPaid`, moving the order into the `PAID` state and triggering consignment payouts.
   - `payment_intent.payment_failed` / `payment_intent.canceled` set the order back to `PENDING_PAYMENT` so checkout can be retried safely.
   - `charge.refunded` updates the payment intent’s refund totals and marks the order `REFUNDED` once the full captured amount has been reversed.
5. Returns 200 OK

### 4. Capture (Manual Capture Only)

For two-step flows (authorize then capture):

```http
POST /api/v1/payments/intents/123/capture
Content-Type: application/json

{
  "amountToCapture": 100.00
}
```

### 5. Refund

```http
POST /api/v1/payments/intents/123/refund
Content-Type: application/json

{
  "amountToRefund": 50.00,
  "reason": "requested_by_customer"
}
```

## Local Development Setup

### Prerequisites

1. Install Stripe CLI:
   ```bash
   brew install stripe/stripe-cli/stripe
   ```

2. Authenticate:
   ```bash
   stripe login
   ```

3. Set environment variables in `.env`:
   ```bash
   STRIPE_API_KEY=sk_test_...
   STRIPE_WEBHOOK_SIGNING_SECRET=whsec_...
   ```

### Running Webhook Tunnel

Use the provided script to forward webhooks to your local server:

```bash
./scripts/dev/stripe_tunnel.sh
```

Options:
- `-p, --port PORT` - Local server port (default: 8080)
- `-h, --host HOST` - Local server host (default: localhost)
- `-e, --endpoint PATH` - Webhook endpoint path (default: /api/webhooks/payments/stripe)

The script will:
1. Start Stripe CLI listener
2. Forward events to your local server
3. Export webhook signing secret to `.env.stripe-tunnel`
4. Display events in real-time

### Testing Webhooks Locally

Trigger test events:

```bash
# Payment intent succeeded
stripe trigger payment_intent.succeeded

# Payment failed
stripe trigger payment_intent.payment_failed

# Charge refunded
stripe trigger charge.refunded
```

Or use the Stripe Dashboard to trigger events from test payments.

### Docker Compose Integration

The project includes a `stripe-cli` service in `docker-compose.yml`:

```bash
# Start Stripe CLI via Docker
docker compose --profile payments up stripe-cli

# View logs
docker compose logs -f stripe-cli
```

## Configuration

### Application Properties

```properties
# Stripe API Configuration
stripe.api-key=${STRIPE_API_KEY}
stripe.api-secret-key=${STRIPE_SECRET_KEY}
stripe.max-retries=3

# Webhook Configuration
stripe.webhook-signing-secret=${STRIPE_WEBHOOK_SIGNING_SECRET}
stripe.webhook.skip-verification=${STRIPE_WEBHOOK_SKIP_VERIFICATION:false}

# Feature Flags
payments.stripe.enabled=true
payments.stripe.connect.enabled=true
```

### Feature Flags

- `payments.stripe.enabled` - Global kill switch for Stripe payments
- `payments.stripe.connect.enabled` - Enable/disable Stripe Connect marketplace features
- `payments.alternative.providers.enabled` - Prepare for future payment providers (PayPal, Square, etc.)

## Platform Fees & Connect

The integration supports Stripe Connect for marketplace scenarios:

1. **Platform Fee Calculation** - `StripeMarketplaceProvider.calculatePlatformFee()`
2. **Connected Accounts** - Onboard consignors via Stripe Connect Express
3. **Payouts** - Automated payout batches to connected accounts

Example platform fee:
- 3% of transaction amount
- $0.30 fixed fee per transaction
- $0.50 minimum fee
- Configurable per tenant via `platform_fee_configs` table

## Error Handling

All endpoints return RFC7807 Problem Details on errors:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Payment intent not in authorized state: PENDING"
}
```

Common error codes:
- `400` - Invalid request (validation failure, invalid state)
- `404` - Payment intent not found
- `409` - Idempotency key conflict (same key, different payload)
- `500` - Stripe API error or internal server error

## Idempotency

### Request Idempotency

Use the `Idempotency-Key` header to safely retry payment intent creation:

```http
POST /api/v1/payments/intents
Idempotency-Key: order-12345-payment-1

{
  "amount": 100.00,
  "currency": "USD"
}
```

Duplicate requests with the same key return the existing payment intent (not a new one).

### Webhook Idempotency

Webhook events are automatically deduplicated:
- Each event has a unique `provider_event_id` (e.g., `evt_abc123`)
- Events are stored in `webhook_events` table
- Duplicate deliveries return success without reprocessing

## Security

### Webhook Signature Verification

All webhook events are verified using the `Stripe-Signature` header:

```java
boolean verifySignature(String payload, String signature, String secret)
```

**Do not disable verification in production!** Only set `stripe.webhook.skip-verification=true` for local testing.

### API Key Security

- API keys stored in Kubernetes Secrets (mounted as files)
- Keys rotated quarterly per operational guardrails
- Never log full API keys or client secrets
- Stub mode activates automatically when keys are missing/invalid

## Metrics & Observability

The integration emits Micrometer metrics:

### Counters
- `payments.intent.created` - Payment intents created
- `payments.intent.failed` - Payment intent creation failures
- `payments.captured` - Successful captures
- `payments.refunded` - Successful refunds
- `webhooks.processed` - Webhooks processed successfully
- `webhooks.duplicate` - Duplicate webhook deliveries
- `webhooks.signature.invalid` - Signature verification failures

### Timers
- `payments.intent.create.duration` - Payment intent creation latency
- `payments.capture.duration` - Capture operation latency
- `payments.refund.duration` - Refund operation latency
- `webhooks.processing.duration` - Webhook processing latency

All metrics are tagged with:
- `tenant` - Tenant UUID
- `provider` - "stripe"
- `event_type` - Event type (for webhooks)

## Testing

### Unit Tests

```bash
# Run payment provider tests
./mvnw test -Dtest=StripePaymentProviderTest

# Run webhook handler tests
./mvnw test -Dtest=StripeWebhookIT
```

### Integration Tests

```bash
# Run payment intent API tests
./mvnw test -Dtest=PaymentIntentResourceIT

# Run with real Stripe API (requires test credentials)
STRIPE_TEST_MODE=true ./mvnw test -Dtest=StripeProviderTest
```

### Coverage Requirements

- **Target**: ≥80% line and branch coverage
- **Enforced by**: SonarCloud quality gate
- **Generate report**: `./mvnw test jacoco:report`
- **View report**: `open modules/core-platform/target/site/jacoco/index.html`

## Database Schema

### payment_intents

| Column | Type | Description |
|--------|------|-------------|
| id | BIGSERIAL | Primary key |
| tenant_id | UUID | Tenant identifier |
| provider | VARCHAR(50) | Payment provider ("stripe") |
| provider_payment_id | VARCHAR(255) | Stripe payment intent ID |
| order_id | UUID | Associated order (nullable) |
| amount | DECIMAL(19,4) | Payment amount |
| currency | VARCHAR(3) | Currency code (ISO 4217) |
| status | VARCHAR(50) | Payment status enum |
| capture_method | VARCHAR(20) | AUTOMATIC or MANUAL |
| amount_captured | DECIMAL(19,4) | Captured amount |
| amount_refunded | DECIMAL(19,4) | Refunded amount |
| client_secret | VARCHAR(500) | Client secret for confirmation |
| idempotency_key | VARCHAR(255) | Client idempotency key |
| metadata | TEXT | JSON metadata |

### webhook_events

| Column | Type | Description |
|--------|------|-------------|
| id | BIGSERIAL | Primary key |
| provider | VARCHAR(50) | Webhook provider ("stripe") |
| provider_event_id | VARCHAR(255) | Stripe event ID (unique) |
| event_type | VARCHAR(100) | Event type |
| payload | TEXT | Raw JSON payload |
| processed | BOOLEAN | Processing status |
| processing_error | TEXT | Error message (nullable) |
| received_at | TIMESTAMP | Event receipt timestamp |
| processed_at | TIMESTAMP | Processing completion timestamp |

## Troubleshooting

### Webhook Events Not Arriving

1. Check webhook endpoint is accessible:
   ```bash
   curl -X POST http://localhost:8080/api/webhooks/payments/health
   ```

2. Verify Stripe CLI is forwarding:
   ```bash
   stripe listen --print-secret
   ```

3. Check logs for signature verification errors:
   ```bash
   grep "webhook signature" logs/quarkus.log
   ```

### Payment Creation Fails

1. Verify API key is set:
   ```bash
   echo $STRIPE_SECRET_KEY
   ```

2. Check stub mode isn't active unintentionally:
   ```bash
   grep "stub mode" logs/quarkus.log
   ```

3. Review Stripe Dashboard for API errors

### Idempotency Key Conflicts

Ensure idempotency keys are unique per request intent. If you're retrying a failed payment, use a new idempotency key if the request parameters changed.

## References

- [Stripe API Documentation](https://stripe.com/docs/api)
- [Stripe Connect Documentation](https://stripe.com/docs/connect)
- [Stripe Webhooks Guide](https://stripe.com/docs/webhooks)
- [RFC7807: Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc7807)
- Architecture: `docs/architecture/04_Operational_Architecture.md` (Section 3.2.8)
- Background Jobs: `docs/architecture/background_jobs.md`
