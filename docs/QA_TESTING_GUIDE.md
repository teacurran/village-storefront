# QA Testing Guide for Checkout & Consignment Pipeline

**Iteration:** I3 - Checkout/Payment/Shipping/Consignment Integration
**Task:** I3.T7 - Development Environment & QA Support
**Last Updated:** 2025-01-09

This guide provides comprehensive instructions for testing the complete checkout pipeline including payments, shipping, consignment attribution, and supporting infrastructure.

---

## Table of Contents

- [Environment Setup](#environment-setup)
- [Mock Services](#mock-services)
- [Test Data Overview](#test-data-overview)
- [Test Scenarios](#test-scenarios)
- [Troubleshooting](#troubleshooting)
- [CI Testing](#ci-testing)

---

## Environment Setup

### Prerequisites

- Docker and docker-compose
- Node.js 20+ (for Stripe CLI)
- psql client (optional, for database inspection)
- curl or Postman (for API testing)

### Quick Start

```bash
# 1. Clone repository and navigate to project root
cd /path/to/village-storefront

# 2. Run bootstrap script (starts all services + loads sample data)
./scripts/dev/bootstrap.sh

# 3. Verify all services are running
docker compose ps

# Expected services:
# - village-postgres (Up, healthy)
# - village-minio (Up, healthy)
# - village-mailhog (Up)
# - village-usps-mock (Up, healthy)
# - village-ups-mock (Up, healthy)
# - village-fedex-mock (Up, healthy)

# 4. Start Quarkus in dev mode
./mvnw quarkus:dev
```

### Optional: Stripe CLI Webhook Forwarder

The Stripe CLI is required for testing webhook events locally (payment confirmations, payout notifications).

```bash
# One-time setup: Authenticate with Stripe
stripe login

# Start Stripe CLI webhook forwarder (requires --profile payments)
docker compose --profile payments up -d stripe-cli

# View webhook events in real-time
docker compose logs -f stripe-cli

# Test webhook forwarding
# 1. Create a payment in your application
# 2. Watch the Stripe CLI logs for the webhook event
# 3. Verify webhook is received at: http://localhost:8080/api/webhooks/stripe
```

---

## Mock Services

All mock services automatically start with `docker compose up`. They simulate carrier APIs for local development without requiring real credentials.

### USPS Mock (Port 9100)

**Health Check:**
```bash
curl http://localhost:9100/health
# Expected: {"status":"ok","service":"usps-mock"}
```

**Test Rate Calculation:**
```bash
curl -X POST http://localhost:9100/ShippingAPI.dll?API=RateV4 \
  -H "Content-Type: application/xml" \
  -d '<RateV4Request USERID="mock">
    <Package ID="1">
      <Service>PRIORITY</Service>
      <ZipOrigination>94103</ZipOrigination>
      <ZipDestination>10001</ZipDestination>
      <Pounds>2</Pounds>
      <Ounces>0</Ounces>
      <Container>VARIABLE</Container>
      <Size>REGULAR</Size>
    </Package>
  </RateV4Request>'
```

**Expected Response:**
```xml
<RateV4Response>
  <Package ID="1">
    <Postage>
      <MailService>Priority Mail 2-Day™</MailService>
      <Rate>7.50</Rate>
    </Postage>
  </Package>
</RateV4Response>
```

**Test Address Verification:**
```bash
curl -X POST http://localhost:9100/ShippingAPI.dll?API=Verify \
  -H "Content-Type: application/xml" \
  -d '<AddressValidateRequest USERID="mock">
    <Address>
      <Address1/>
      <Address2>123 Main St</Address2>
      <City>San Francisco</City>
      <State>CA</State>
      <Zip5>94102</Zip5>
      <Zip4/>
    </Address>
  </AddressValidateRequest>'
```

### UPS Mock (Port 9101)

**Health Check:**
```bash
curl http://localhost:9101/health
# Expected: {"status":"ok","service":"ups-mock"}
```

**Test Rate Calculation:**
```bash
curl -X POST http://localhost:9101/api/rating/v1/Rate \
  -H "Content-Type: application/json" \
  -d '{
    "RateRequest": {
      "Shipment": {
        "ShipFrom": {
          "Address": {
            "PostalCode": "94103"
          }
        },
        "ShipTo": {
          "Address": {
            "PostalCode": "10001"
          }
        },
        "Package": [{
          "PackagingType": {
            "Code": "02"
          },
          "PackageWeight": {
            "Weight": "5.0",
            "UnitOfMeasurement": {
              "Code": "LBS"
            }
          }
        }]
      }
    }
  }'
```

**Expected Response:**
```json
{
  "RateResponse": {
    "RatedShipment": [
      {
        "Service": {
          "Code": "03",
          "Description": "UPS Ground"
        },
        "TotalCharges": {
          "CurrencyCode": "USD",
          "MonetaryValue": "12.45"
        }
      }
    ]
  }
}
```

### FedEx Mock (Port 9102)

**Health Check:**
```bash
curl http://localhost:9102/health
# Expected: {"status":"ok","service":"fedex-mock"}
```

**Test Rate Calculation:**
```bash
curl -X POST http://localhost:9102/rate/v1/rates/quotes \
  -H "Content-Type: application/json" \
  -d '{
    "accountNumber": {
      "value": "mock"
    },
    "requestedShipment": {
      "shipper": {
        "address": {
          "postalCode": "94103",
          "countryCode": "US"
        }
      },
      "recipient": {
        "address": {
          "postalCode": "10001",
          "countryCode": "US"
        }
      },
      "requestedPackageLineItems": [{
        "weight": {
          "value": 5.0,
          "units": "LB"
        }
      }]
    }
  }'
```

---

## Test Data Overview

The bootstrap script loads comprehensive sample data across 2 tenants with realistic multi-tenant consignment scenarios.

### Tenants

| Tenant ID | Subdomain | Name | Stripe Account |
|-----------|-----------|------|----------------|
| (varies) | tech-gadgets | Tech Gadgets Store | acct_test_tech |
| (varies) | artisan-crafts | Artisan Crafts Shop | acct_test_artisan |

### Consignors

| Consignor | Tenant | Pending Balance | Available Balance | Commission Rate | Notes |
|-----------|--------|-----------------|-------------------|-----------------|-------|
| **Vintage Audio Collector** | Tech Gadgets | $0.00 | $0.00 | 20% | New consignor, no sales yet |
| **Mobile Accessories Hub** | Tech Gadgets | $125.50 | **$342.75** | 25% | **Ready for payout testing** |
| **Oak & Pine Woodworks** | Artisan Crafts | $0.00 | $0.00 | 30% | New consignor, no sales yet |

### Sample Products

#### Tech Gadgets Store (tech-gadgets.localhost:8080)

**Consignment Items:**

1. **ProSound Wireless Earbuds** - Consignor: Vintage Audio Collector
   - Variants: Black ($79.99), White ($79.99)
   - Commission: 20%
   - Stock: 50 units each

2. **Premium Phone Cases** - Consignor: Mobile Accessories Hub
   - Variants: iPhone 14 ($24.99), iPhone 14 Pro ($29.99), Galaxy S23 ($24.99)
   - Commission: 25%
   - Stock: 100 units each

**Store-Owned Items:**

3. **USB-C Charging Cable** - Store-owned (NOT consignment)
   - Variants: 3ft ($9.99), 6ft ($12.99), 10ft ($15.99)
   - Stock: 200 units each

#### Artisan Crafts Shop (artisan-crafts.localhost:8080)

Products to be added (currently empty catalog for testing import flows).

### Payout Ledger Historical Data

Mobile Accessories Hub has **12 historical transactions** demonstrating a complete payout lifecycle:

```sql
-- Sample transactions for Mobile Accessories Hub
INSERT INTO payout_ledger (tenant_id, consignor_id, entry_type, amount_cents, balance_cents, reference_type, reference_id, settled_at, notes)
VALUES
  -- Sales from 30 days ago
  ('tech-tenant-id', 'mobile-accessories-id', 'SALE', 2499, 2499, 'ORDER_ITEM', 'order-item-1', NULL, 'Phone Case - iPhone 14'),
  ('tech-tenant-id', 'mobile-accessories-id', 'SALE', 2999, 5498, 'ORDER_ITEM', 'order-item-2', NULL, 'Phone Case - iPhone 14 Pro'),

  -- First settlement (30 days ago)
  ('tech-tenant-id', 'mobile-accessories-id', 'SETTLEMENT', -5498, 0, 'PAYOUT_BATCH', 'batch-1', NOW() - INTERVAL '30 days', 'Weekly payout'),

  -- More sales in the past 2 weeks
  ('tech-tenant-id', 'mobile-accessories-id', 'SALE', 2499, 2499, 'ORDER_ITEM', 'order-item-3', NULL, 'Phone Case - Galaxy S23'),
  ('tech-tenant-id', 'mobile-accessories-id', 'SALE', 2499, 4998, 'ORDER_ITEM', 'order-item-4', NULL, 'Phone Case - iPhone 14'),

  -- Recent settlement (7 days ago) - moved to available balance
  ('tech-tenant-id', 'mobile-accessories-id', 'SETTLEMENT', -4998, 0, 'PAYOUT_BATCH', 'batch-2', NOW() - INTERVAL '7 days', 'Weekly payout');

-- Current state after ledger processing:
-- Pending: $125.50 (recent sales not yet settled)
-- Available: $342.75 (ready for payout)
```

---

## Test Scenarios

### Scenario 1: Place Order with Consignment Item

**Objective:** Verify consignment sale creates correct ledger entry.

**Steps:**

1. Navigate to Tech Gadgets Store:
   ```bash
   curl http://tech-gadgets.localhost:8080/
   ```

2. Add consignment item to cart:
   ```bash
   curl -X POST http://tech-gadgets.localhost:8080/api/cart/items \
     -H "Content-Type: application/json" \
     -d '{
       "productId": "prosound-earbuds-id",
       "variantId": "black-variant-id",
       "quantity": 1
     }'
   ```

3. Complete checkout with Stripe test card:
   ```bash
   # Card number: 4242 4242 4242 4242
   # Expiry: Any future date
   # CVC: Any 3 digits
   # ZIP: Any 5 digits
   ```

4. **Verify:** Check payout_ledger table for new SALE entry:
   ```bash
   psql -h localhost -U appuser -d storefront_dev -c "
     SELECT entry_type, amount_cents, balance_cents, reference_type, notes
     FROM payout_ledger
     WHERE consignor_id = 'vintage-audio-id'
     ORDER BY created_at DESC
     LIMIT 5;
   "
   ```

5. **Expected Result:**
   - New SALE entry with `amount_cents = 7999 * 0.20 = 1599.80` (20% commission)
   - `balance_cents` incremented by commission amount
   - `reference_type = 'ORDER_ITEM'`
   - `settled_at IS NULL` (pending settlement)

---

### Scenario 2: Test Shipping Rate Calculation

**Objective:** Verify shipping service aggregates rates from multiple carriers.

**Steps:**

1. Add item to cart (see Scenario 1)

2. Request shipping rates:
   ```bash
   curl -X POST http://tech-gadgets.localhost:8080/api/checkout/shipping-rates \
     -H "Content-Type: application/json" \
     -d '{
       "destination": {
         "street1": "123 Main St",
         "city": "New York",
         "state": "NY",
         "postalCode": "10001",
         "country": "US"
       }
     }'
   ```

3. **Verify Mock Service Logs:**
   ```bash
   # Check USPS mock was called
   docker compose logs usps-mock | grep "RateV4"

   # Check UPS mock was called
   docker compose logs ups-mock | grep "Rate"

   # Check FedEx mock was called
   docker compose logs fedex-mock | grep "quotes"
   ```

4. **Expected Response:**
   ```json
   {
     "rates": [
       {
         "carrierCode": "USPS",
         "serviceLevel": "PRIORITY",
         "serviceName": "Priority Mail 2-Day",
         "totalCharge": {
           "amount": "7.50",
           "currency": "USD"
         },
         "estimatedDelivery": "2025-01-12T00:00:00Z"
       },
       {
         "carrierCode": "UPS",
         "serviceLevel": "GROUND",
         "serviceName": "UPS Ground",
         "totalCharge": {
           "amount": "12.45",
           "currency": "USD"
         },
         "estimatedDelivery": "2025-01-14T00:00:00Z"
       }
     ],
     "fallbackUsed": false
   }
   ```

5. **Verify Caching:**
   ```bash
   # Make the same request twice
   # Second request should be faster (cache hit)
   time curl -X POST http://tech-gadgets.localhost:8080/api/checkout/shipping-rates ...
   ```

---

### Scenario 3: Simulate Payout Settlement

**Objective:** Transfer pending balance to available balance (ready for payout).

**Steps:**

1. Check current balances:
   ```bash
   psql -h localhost -U appuser -d storefront_dev -c "
     SELECT c.name, pl.pending_balance, pl.available_balance
     FROM payout_ledger pl
     JOIN consignors c ON pl.consignor_id = c.id
     WHERE c.name = 'Mobile Accessories Hub';
   "
   ```

2. Trigger settlement job (manually via admin API):
   ```bash
   curl -X POST http://tech-gadgets.localhost:8080/api/admin/jobs/trigger \
     -H "Authorization: Bearer <admin-jwt>" \
     -H "Content-Type: application/json" \
     -d '{
       "jobType": "CONSIGNMENT_SETTLEMENT",
       "priority": "HIGH"
     }'
   ```

3. **Verify:** Check for new SETTLEMENT entry:
   ```bash
   psql -h localhost -U appuser -d storefront_dev -c "
     SELECT entry_type, amount_cents, balance_cents, settled_at
     FROM payout_ledger
     WHERE consignor_id = 'mobile-accessories-id'
     AND entry_type = 'SETTLEMENT'
     ORDER BY created_at DESC
     LIMIT 1;
   "
   ```

4. **Expected Result:**
   - SETTLEMENT entry with negative `amount_cents` (transfer out of pending)
   - `pending_balance` reduced to 0
   - `available_balance` increased by settlement amount
   - `settled_at` populated with current timestamp

---

### Scenario 4: Process Stripe Payout

**Objective:** Initiate payout to consignor's Stripe Connected Account.

**Prerequisites:**
- Stripe CLI must be running (`docker compose --profile payments up -d stripe-cli`)
- Consignor must have available balance > $0 (Mobile Accessories Hub has $342.75)

**Steps:**

1. Check available balance:
   ```bash
   curl http://tech-gadgets.localhost:8080/api/admin/consignors/mobile-accessories-id/balance \
     -H "Authorization: Bearer <admin-jwt>"
   ```

2. Initiate payout:
   ```bash
   curl -X POST http://tech-gadgets.localhost:8080/api/admin/consignors/mobile-accessories-id/payout \
     -H "Authorization: Bearer <admin-jwt>" \
     -H "Content-Type: application/json" \
     -d '{
       "amountCents": 34275,
       "currency": "USD",
       "description": "Weekly payout - January 2025"
     }'
   ```

3. **Monitor Stripe CLI:**
   ```bash
   docker compose logs -f stripe-cli

   # Expected webhook events:
   # - payout.created
   # - payout.paid (after 1-2 days in production, instant in test mode)
   ```

4. **Verify Webhook Processing:**
   ```bash
   psql -h localhost -U appuser -d storefront_dev -c "
     SELECT event_type, payload->>'id' as payout_id, processed_at
     FROM stripe_webhook_events
     WHERE event_type LIKE 'payout.%'
     ORDER BY created_at DESC
     LIMIT 5;
   "
   ```

5. **Expected Result:**
   - Payout ID returned in response
   - Webhook events logged in `stripe_webhook_events` table
   - `available_balance` reduced by payout amount
   - PAYOUT entry in `payout_ledger` with `reference_type = 'STRIPE_PAYOUT'`

---

### Scenario 5: Test Refund of Consignment Item

**Objective:** Verify refund creates negative ledger entry for consignor.

**Prerequisites:**
- Completed order with consignment item (see Scenario 1)

**Steps:**

1. Identify order to refund:
   ```bash
   psql -h localhost -U appuser -d storefront_dev -c "
     SELECT id, order_number, total_cents, created_at
     FROM orders
     WHERE status = 'COMPLETED'
     ORDER BY created_at DESC
     LIMIT 5;
   "
   ```

2. Process refund via admin API:
   ```bash
   curl -X POST http://tech-gadgets.localhost:8080/api/admin/orders/<order-id>/refund \
     -H "Authorization: Bearer <admin-jwt>" \
     -H "Content-Type: application/json" \
     -d '{
       "reason": "Customer request",
       "items": [
         {
           "orderItemId": "<order-item-id>",
           "quantity": 1
         }
       ]
     }'
   ```

3. **Verify:** Check for REFUND entry in payout_ledger:
   ```bash
   psql -h localhost -U appuser -d storefront_dev -c "
     SELECT entry_type, amount_cents, balance_cents, reference_type
     FROM payout_ledger
     WHERE consignor_id = 'vintage-audio-id'
     AND entry_type = 'REFUND'
     ORDER BY created_at DESC
     LIMIT 1;
   "
   ```

4. **Expected Result:**
   - REFUND entry with **negative** `amount_cents` (commission clawed back)
   - `balance_cents` reduced by refund amount
   - `reference_type = 'ORDER_ITEM'`
   - If consignor's balance goes negative, flag for review

---

### Scenario 6: Test Address Validation

**Objective:** Verify USPS address normalization.

**Steps:**

1. Submit address with lowercase and missing apartment:
   ```bash
   curl -X POST http://tech-gadgets.localhost:8080/api/checkout/validate-address \
     -H "Content-Type: application/json" \
     -d '{
       "street1": "123 main st",
       "city": "san francisco",
       "state": "ca",
       "postalCode": "94102",
       "country": "US"
     }'
   ```

2. **Expected Response:**
   ```json
   {
     "status": "VALID",
     "normalizedAddress": {
       "street1": "123 MAIN ST",
       "city": "SAN FRANCISCO",
       "state": "CA",
       "postalCode": "94102-1234",
       "country": "US"
     },
     "suggestions": [],
     "carrierUsed": "USPS"
   }
   ```

3. **Verify Mock Called:**
   ```bash
   docker compose logs usps-mock | grep "Verify"
   ```

---

### Scenario 7: Test Fallback Rates (Carrier Offline)

**Objective:** Verify fallback table rates when carriers unavailable.

**Steps:**

1. Stop all carrier mocks:
   ```bash
   docker compose stop usps-mock ups-mock fedex-mock
   ```

2. Request shipping rates:
   ```bash
   curl -X POST http://tech-gadgets.localhost:8080/api/checkout/shipping-rates \
     -H "Content-Type: application/json" \
     -d '{
       "destination": {
         "street1": "123 Main St",
         "city": "New York",
         "state": "NY",
         "postalCode": "10001",
         "country": "US"
       }
     }'
   ```

3. **Expected Response:**
   ```json
   {
     "rates": [
       {
         "carrierCode": "FALLBACK",
         "serviceLevel": "GROUND",
         "serviceName": "Standard Shipping (3-5 days)",
         "totalCharge": {
           "amount": "9.99",
           "currency": "USD"
         }
       }
     ],
     "fallbackUsed": true,
     "fallbackReason": "All carrier services unavailable"
   }
   ```

4. Restart mocks for subsequent tests:
   ```bash
   docker compose up -d usps-mock ups-mock fedex-mock
   ```

---

## Troubleshooting

### Mock Services Not Starting

**Symptom:** `docker compose ps` shows mocks as "Exited" or "Restarting"

**Solution:**

```bash
# Check logs for error details
docker compose logs usps-mock
docker compose logs ups-mock
docker compose logs fedex-mock

# Common issues:
# 1. Port already in use
sudo lsof -i :9100  # Check what's using port 9100
sudo lsof -i :9101
sudo lsof -i :9102

# 2. Missing Node.js in container (unlikely, but verify Dockerfile)
docker compose build usps-mock ups-mock fedex-mock

# 3. Restart specific service
docker compose restart usps-mock
```

### Stripe CLI Not Forwarding Webhooks

**Symptom:** Payments succeed but webhook events not received in logs

**Solution:**

```bash
# 1. Verify Stripe CLI is authenticated
stripe login

# 2. Check Stripe CLI logs
docker compose logs stripe-cli

# Expected output: "Ready! Your webhook signing secret is whsec_..."

# 3. Verify forwarding URL is correct
# Should point to: http://host.docker.internal:8080/api/webhooks/stripe

# 4. Test webhook manually
stripe trigger payment_intent.succeeded
```

### Test Database Connection Issues

**Symptom:** Tests fail with "Connection refused" to PostgreSQL

**Solution:**

```bash
# 1. Check if PostgreSQL is running
docker compose ps postgres

# 2. Verify test profile uses H2 in-memory database (NOT PostgreSQL)
# Check modules/core-platform/src/test/resources/application.properties
# Should have: %test.quarkus.datasource.db-kind=h2

# 3. If tests need PostgreSQL, start it separately
docker compose up -d postgres

# 4. Wait for PostgreSQL to be healthy
docker compose ps postgres | grep "healthy"
```

### Shipping Rate Cache Not Working

**Symptom:** Every rate request takes full 5-10 seconds

**Solution:**

```bash
# 1. Verify Caffeine cache is configured
# Check application.properties for:
# quarkus.cache.caffeine."shipping-rate-cache".expire-after-write=PT15M

# 2. Check cache stats in dev mode
curl http://localhost:8080/q/cache-metrics

# 3. Clear cache and retry
curl -X POST http://localhost:8080/api/admin/cache/invalidate/shipping-rate-cache \
  -H "Authorization: Bearer <admin-jwt>"
```

### Consignment Sample Data Not Loading

**Symptom:** Consignors table is empty after bootstrap

**Solution:**

```bash
# 1. Check bootstrap script output
./scripts/dev/bootstrap.sh | grep "consignment"

# Expected: "Loading consignment sample data..."

# 2. Manually load SQL file
psql -h localhost -U appuser -d storefront_dev \
  -f tools/scripts/sample_consignment_loader.sql

# 3. Verify data loaded
psql -h localhost -U appuser -d storefront_dev -c "SELECT COUNT(*) FROM consignors;"
# Expected: 3
```

---

## CI Testing

### Running Tests with Mocks in CI

The CI workflow (`.github/workflows/ci.yml`) automatically starts mock services before running tests.

**Workflow Steps:**

1. **Start shipping mocks:**
   ```yaml
   - name: Start shipping mock services
     run: |
       cd docker
       docker compose up -d usps-mock ups-mock fedex-mock
   ```

2. **Wait for health checks:**
   ```yaml
   - name: Wait for shipping mocks to be healthy
     run: |
       timeout 30 bash -c 'until curl -f http://localhost:9100/health; do sleep 1; done'
       timeout 30 bash -c 'until curl -f http://localhost:9101/health; do sleep 1; done'
       timeout 30 bash -c 'until curl -f http://localhost:9102/health; do sleep 1; done'
   ```

3. **Run tests with mock URLs:**
   ```yaml
   - name: Run JVM tests
     env:
       USPS_MOCK_URL: http://localhost:9100/ShippingAPI.dll
       UPS_MOCK_URL: http://localhost:9101/api/rating/v1
       FEDEX_MOCK_URL: http://localhost:9102/rate/v1
     run: npm run test
   ```

4. **Cleanup:**
   ```yaml
   - name: Stop shipping mock services
     if: always()
     run: |
       cd docker
       docker compose down usps-mock ups-mock fedex-mock
   ```

### Test Profile Configuration

The test profile (`modules/core-platform/src/test/resources/application.properties`) automatically overrides shipping URLs:

```properties
# Shipping Carrier Mock Configuration (Task I3.T7)
%test.shipping.usps.api-url=${USPS_MOCK_URL:http://localhost:9100/ShippingAPI.dll}
%test.shipping.ups.api-url=${UPS_MOCK_URL:http://localhost:9101/api/rating/v1}
%test.shipping.fedex.api-url=${FEDEX_MOCK_URL:http://localhost:9102/rate/v1}

# Use mock credentials
%test.shipping.usps.user-id=mock-usps-user
%test.shipping.ups.access-key=mock-ups-access
%test.shipping.fedex.api-key=mock-fedex-key

# Reduce timeouts for faster test execution
%test.shipping.usps.timeout-ms=5000
%test.shipping.ups.timeout-ms=5000
%test.shipping.fedex.timeout-ms=5000
```

### Running Tests Locally with Mocks

```bash
# 1. Start mock services
docker compose up -d usps-mock ups-mock fedex-mock

# 2. Wait for health checks
./scripts/dev/wait-for-shipping-mocks.sh  # (create this helper script)

# 3. Run tests with test profile
./mvnw test -Dquarkus.test.profile=test

# 4. Run specific shipping tests
./mvnw test -Dtest=ShippingServiceTest
./mvnw test -Dtest=CheckoutE2ETest
```

---

## Additional Resources

- **Architecture Documentation:** `docs/architecture/`
- **OpenAPI Specification:** `api/v1/openapi.yaml`
- **Stripe API Docs:** https://stripe.com/docs/api
- **USPS Web Tools Docs:** https://www.usps.com/business/web-tools-apis/
- **Project Standards:** `docs/java-project-standards.adoc`

---

**Last Updated:** 2025-01-09
**Maintainer:** Development Team
**Questions?** Open an issue or see `README.md`
