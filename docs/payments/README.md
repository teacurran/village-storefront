# Payment Provider Framework

This directory contains documentation for the Village Storefront payment provider framework and integrations.

## Overview

The payment provider framework provides a provider-agnostic abstraction for payment processing, enabling:

- Multiple payment provider support (Stripe, PayPal, etc.)
- Marketplace/platform payment flows with split payments
- Webhook-driven asynchronous event processing
- Multi-tenant fee configuration
- Comprehensive audit trails and metrics

## Architecture

### Provider Interfaces

Located in `src/main/java/villagecompute/storefront/payment/`:

- **`PaymentProvider`** - Core payment operations (create, capture, refund)
- **`PaymentMethodProvider`** - Payment method management
- **`MarketplaceProvider`** - Platform fees, onboarding, payouts
- **`WebhookHandler`** - Asynchronous event processing

### Stripe Implementation

Located in `src/main/java/villagecompute/storefront/payment/stripe/`:

- **`StripePaymentProvider`** - Stripe payment intent operations
- **`StripeMarketplaceProvider`** - Stripe Connect implementation
- **`StripeWebhookHandler`** - Stripe webhook processing
- **`StripeConfig`** - Configuration binding

### Data Models

Located in `src/main/java/villagecompute/storefront/data/models/`:

- **`PaymentIntent`** - Payment lifecycle tracking
- **`WebhookEvent`** - Webhook idempotency and audit
- **`ConnectAccount`** - Marketplace connected accounts
- **`PlatformFeeConfig`** - Tenant fee configuration

## Quick Start

### 1. Configuration

Set environment variables:

```bash
export STRIPE_SECRET_KEY=sk_test_...
export STRIPE_PUBLISHABLE_KEY=pk_test_...
export STRIPE_WEBHOOK_SECRET=whsec_...
export STRIPE_TEST_MODE=true
```

### 2. Database Migration

Run migrations to create payment tables:

```bash
cd migrations
mvn migration:up -Dmigration.env=development
```

### 3. Create Payment Intent

```java
@Inject
PaymentService paymentService;

PaymentIntent intent = paymentService.createPaymentIntent(
    new BigDecimal("100.00"),
    "USD",
    orderId,
    true, // captureImmediately
    "idempotency-key-123"
);

// Return client secret to frontend for confirmation
String clientSecret = intent.clientSecret;
```

### 4. Configure Webhooks

1. In Stripe Dashboard, add webhook endpoint:
   - **URL:** `https://yoursite.com/api/webhooks/payments/stripe`
   - **Events:** Select all payment and Connect events

2. Copy webhook signing secret to environment:
   ```bash
   export STRIPE_WEBHOOK_SECRET=whsec_...
   ```

3. Test locally with Stripe CLI:
   ```bash
   stripe listen --forward-to localhost:8080/api/webhooks/payments/stripe
   ```

## Documentation

- **[Stripe Connect Integration Guide](stripe_connect.md)** - Complete Stripe setup guide
  - Configuration and credentials
  - Onboarding workflow
  - Platform fee calculation
  - Payout management
  - Webhook handling
  - Testing and troubleshooting

## Testing

### Unit Tests

```bash
./mvnw test -Dtest=StripeProviderTest
```

### Integration Tests

Requires Stripe test credentials:

```bash
export STRIPE_TEST_MODE=true
export STRIPE_SECRET_KEY=sk_test_...
./mvnw test -Dtest=StripeWebhookIT
```

### Test Coverage

Current coverage (as of 2026-01-03):

- Payment provider interfaces: 100% (interface definitions)
- Stripe implementations: ~85%
- Webhook handlers: ~90%
- Service layer: ~80%

Target: **80% line and branch coverage** (enforced by SonarCloud)

## API Endpoints

### Payment Operations

Handled via `PaymentService` (injected into checkout resources):

- Create payment intent
- Capture payment
- Refund payment
- Calculate platform fees

### Webhook Ingestion

```
POST /api/webhooks/payments/stripe
Headers:
  - Stripe-Signature: [signature]
  - Content-Type: text/plain
Body: [raw webhook JSON]
```

### Health Check

```
GET /api/webhooks/payments/health
Response: { "status": "healthy", "providers": ["stripe"] }
```

## Metrics & Observability

### Key Metrics

- `payments.intent.created{tenant,provider,currency}` - Payment intents created
- `payments.intent.failed{tenant,provider,error}` - Payment failures
- `payments.captured{tenant,provider}` - Successful captures
- `payments.refunded{tenant,provider,reason}` - Refunds
- `webhooks.processed{tenant,provider,event_type}` - Webhook processing
- `marketplace.payout.created{tenant,provider}` - Payouts

### Logs

All operations emit structured logs with tenant context:

```
[Tenant: tenant-123] Created Stripe payment intent: pi_abc, amount: 100.00 USD
[Tenant: tenant-123] Processing webhook: payment_intent.succeeded
```

### Dashboards

Monitor in Grafana/Prometheus:

- Payment success/failure rates
- Webhook processing latency
- Payout completion rates
- Fee calculation accuracy

## Security

### Secrets Management

- Store credentials in Kubernetes Secrets
- Rotate secrets quarterly
- Never commit secrets to version control
- Use environment variable injection

### Webhook Verification

All webhooks are signature-verified before processing:

```java
boolean valid = webhookHandler.verifySignature(payload, signature, secret);
if (!valid) {
    return 400 Bad Request;
}
```

### Idempotency

- Client-provided idempotency keys prevent duplicate payment creation
- Webhook events are deduplicated by `provider_event_id`
- All financial operations are idempotent

### Multi-Tenancy

- All data is tenant-scoped via RLS policies
- Tenant context enforced at service layer
- Payment intents validated against tenant ownership

## Future Enhancements

### Planned Features

- [ ] PayPal Commerce Platform integration
- [ ] Apple Pay and Google Pay support
- [ ] Subscription billing with recurring payments
- [ ] Dynamic currency conversion
- [ ] Advanced dispute management UI
- [ ] Payment analytics and reporting dashboards
- [ ] Fraud detection integration (Stripe Radar)

### Provider Extensibility

To add a new payment provider:

1. Create implementation classes:
   ```
   src/main/java/villagecompute/storefront/payment/paypal/
   ├── PayPalConfig.java
   ├── PayPalPaymentProvider.java
   ├── PayPalMarketplaceProvider.java
   └── PayPalWebhookHandler.java
   ```

2. Implement all four provider interfaces

3. Add configuration to `application.properties`

4. Register webhook endpoint in `PaymentWebhookResource`

5. Write integration tests

6. Update documentation

## Support

For questions or issues:

- Review [Stripe Connect Guide](stripe_connect.md)
- Check logs for tenant-scoped error messages
- Monitor metrics dashboards
- Review ADR-004 for payout architecture decisions

## References

- [ADR-004: Consignment Payouts](../adr/ADR-004-consignment-payouts.md)
- [Stripe API Documentation](https://stripe.com/docs/api)
- [Stripe Connect Guide](https://stripe.com/docs/connect)
- [OpenAPI Specification](../../api/v1/openapi.yaml)
- [Checkout Sequence Diagram](../diagrams/sequence_checkout_payment.mmd)

---

**Last Updated:** 2026-01-03
**Version:** 1.0
**Task:** I4.T1 - PaymentProvider + Stripe Integration
