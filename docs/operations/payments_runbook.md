# Payments & Stripe Integration Runbook

**Component:** Payment Processing & Stripe Webhooks
**Owner:** Platform Engineering
**Last Updated:** 2026-01-10
**Related Docs:** [Architecture §3.20.1](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md), [Observability Framework](./observability.md)

---

## Overview

The Payment Processing module handles all payment transactions including credit cards via Stripe, gift cards, store credit, and multi-tender payments. This runbook provides operational guidance for responding to payment alerts, troubleshooting Stripe webhook failures, and managing payment reconciliation.

### Component KPIs

| KPI | Target | Alert Threshold | Criticality |
|-----|--------|----------------|-------------|
| Stripe Webhook Failure Rate | <1% | >5% for 5min | Critical (Sev1) |
| Payment Transaction Latency (p95) | <300ms | >300ms for 5min | Warning |
| Stripe Connect Onboarding Rate | >95% | <95% over 24h | Warning (Sev2) |
| Refund Processing Success Rate | >99% | <99% for 10min | Warning |
| Multi-Tender Transaction Success | >98% | <98% for 10min | Warning |

### Service Dependencies

- **Stripe API**: Payment processing, Connect payouts, webhook events
- **PostgreSQL**: Transaction records, webhook event log, payment method vault
- **Checkout Orchestrator**: Integration with cart finalization and order creation
- **Consignment Module**: Stripe Connect payouts for vendor settlements

---

## Alerts

### Alert: StripeWebhookFailureRateHigh (SEV1)

**Symptom:** Stripe webhook failure rate >5% across all event types

**Severity:** Critical (Sev1 per Architecture §3.20.1)
**Component:** Payment
**Dashboard:** [Payment KPIs Panel](https://grafana.villagecompute.com/d/component-kpis?var-component=payment)

#### Causes

1. **Signature validation failures**
   - Webhook secret key rotated in Stripe dashboard
   - Incorrect secret in environment variables
   - Clock skew causing timestamp validation failures

2. **Event handler exceptions**
   - Code bug in webhook event processing
   - Database constraint violations
   - Null pointer exceptions on unexpected payloads

3. **Database connection failures**
   - Connection pool exhausted
   - Database failover in progress
   - RLS policy blocking webhook event inserts

4. **Timeout issues**
   - Slow downstream processing
   - Synchronous external API calls in webhook handler
   - Large webhook payloads causing parsing delays

#### Investigation Steps

1. **Check Webhook Failure Rate:**
   ```promql
   sum(rate(payment_webhook_failed[5m])) / sum(rate(payment_webhook_received[5m]))
   ```

2. **Identify Failing Event Types:**
   ```promql
   sum(rate(payment_webhook_failed[5m])) by (event_type, reason)
   ```

3. **Check Stripe Dashboard:**
   - Login to Stripe Dashboard → Developers → Webhooks
   - Review recent webhook attempts and error responses
   - Verify endpoint URL and signature secret

4. **Review Webhook Event Log:**
   ```sql
   SELECT event_type, error_message, COUNT(*) AS failure_count
   FROM webhook_events
   WHERE provider = 'stripe'
     AND status = 'failed'
     AND created_at > NOW() - INTERVAL '1 hour'
   GROUP BY event_type, error_message
   ORDER BY failure_count DESC;
   ```

5. **Check Application Logs:**
   ```bash
   kubectl logs -l app=storefront --tail=200 | grep "payment.webhook.failed"
   ```

6. **Verify Webhook Signature:**
   ```bash
   # Test webhook locally with Stripe CLI
   stripe listen --forward-to localhost:8080/api/webhooks/stripe
   stripe trigger payment_intent.succeeded
   ```

#### Resolution

1. **Signature Validation Fix:**
   - If secret rotated in Stripe:
     ```bash
     kubectl create secret generic stripe-webhook-secret \
       --from-literal=secret=whsec_NEW_SECRET \
       --dry-run=client -o yaml | kubectl apply -f -

     kubectl rollout restart deployment/storefront
     ```
   - Verify correct secret in environment:
     ```bash
     kubectl get secret stripe-webhook-secret -o jsonpath='{.data.secret}' | base64 -d
     ```

2. **Handler Exception Fix:**
   - If specific event type failing, temporarily disable handling:
     ```bash
     Feature Flag: payment.webhook.handler.[event_type].enabled=false
     ```
   - Deploy hotfix for bug, re-enable handler
   - Manually replay failed events from webhook_events table

3. **Database Connection Fix:**
   - Increase connection pool:
     ```properties
     quarkus.datasource.jdbc.max-size=30
     ```
   - Verify RLS policies allow webhook inserts:
     ```sql
     -- Test as webhook service account
     SET ROLE webhook_service;
     INSERT INTO webhook_events (event_id, event_type, payload, tenant_id) VALUES (...);
     ```

4. **Timeout Fix:**
   - Enable async webhook processing:
     ```properties
     payment.webhook.async=true
     payment.webhook.queue-size=1000
     ```
   - Return 200 immediately, process in background job

5. **Emergency Rollback:**
   - If recent deployment caused regression:
     ```bash
     kubectl rollout undo deployment/storefront
     ```

6. **Stripe Retry Configuration:**
   - Update webhook retry settings in Stripe Dashboard:
     - Max retries: 3
     - Retry intervals: 1h, 3h, 6h
   - This reduces noise from transient failures

#### Post-Resolution

1. **Manually Replay Failed Events:**
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/webhooks/retry \
     -H "Authorization: Bearer $TOKEN" \
     -d '{
       "provider": "stripe",
       "event_ids": ["evt_xxx", "evt_yyy"],
       "force": true
     }'
   ```

2. **Verify Reconciliation:**
   ```sql
   SELECT COUNT(*) FROM webhook_events
   WHERE provider = 'stripe'
     AND status = 'failed'
     AND created_at > NOW() - INTERVAL '1 hour';
   ```
   - Should be zero after replay

3. **Monitor Recovery:**
   ```promql
   sum(rate(payment_webhook_processed[5m])) by (event_type)
   ```

---

### Alert: PaymentTransactionLatencyHigh

**Symptom:** Payment transaction p95 >300ms (impacts checkout KPI)

**Severity:** Warning
**Component:** Payment
**Dashboard:** [Payment KPIs Panel](https://grafana.villagecompute.com/d/component-kpis?var-component=payment)

#### Causes

1. **Slow Stripe API responses**
   - Stripe experiencing elevated latency
   - Network connectivity issues to Stripe endpoints
   - Large payment intent payloads

2. **Multi-tender payment complexity**
   - Sequential processing of multiple payment methods
   - Gift card balance checks with external systems
   - Store credit ledger queries

3. **Database query performance**
   - Missing indexes on payment_transactions table
   - Slow payment method vault decryption
   - Lock contention on customer payment methods

4. **Synchronous fraud checks**
   - Third-party fraud detection API calls
   - Address verification service (AVS) delays
   - 3D Secure authentication flows

#### Investigation Steps

1. **Check Current Transaction Latency:**
   ```promql
   histogram_quantile(0.95, sum(rate(payment_transaction_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le))
   ```

2. **Break Down by Payment Method:**
   ```promql
   histogram_quantile(0.95, sum(rate(payment_transaction_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le, payment_method))
   ```

3. **Check Stripe API Latency:**
   ```bash
   curl https://status.stripe.com/api/v2/status.json
   ```

4. **Review Slow Query Logs:**
   ```sql
   SELECT query, mean_exec_time, calls
   FROM pg_stat_statements
   WHERE query LIKE '%payment_transaction%'
   ORDER BY mean_exec_time DESC LIMIT 10;
   ```

5. **Check Multi-Tender Usage:**
   ```promql
   rate(payment_multi_tender_used{tenant_id="YOUR_TENANT"}[5m])
   ```

#### Resolution

1. **Optimize Stripe API Calls:**
   - Enable request idempotency keys (already implemented):
     ```java
     // Verify idempotency key generation
     paymentIntent.create(params, RequestOptions.builder().setIdempotencyKey(uuid).build());
     ```
   - Reduce payment intent metadata size (remove debug fields)

2. **Parallelize Multi-Tender Processing:**
   - Enable concurrent payment method processing:
     ```properties
     payment.multi-tender.parallel=true
     payment.multi-tender.max-parallelism=3
     ```

3. **Cache Gift Card Balances:**
   - Enable Redis caching for gift card lookups:
     ```properties
     payment.gift-card.cache-enabled=true
     payment.gift-card.cache-ttl=300
     ```

4. **Async Fraud Checks (Non-Blocking):**
   - Move fraud checks to post-authorization:
     ```properties
     payment.fraud-check.async=true
     payment.fraud-check.block-on-high-risk-only=true
     ```

5. **Database Optimization:**
   - Add composite index:
     ```sql
     CREATE INDEX CONCURRENTLY idx_payment_transactions_tenant_created
     ON payment_transactions(tenant_id, created_at DESC);
     ```

---

### Alert: StripeConnectOnboardingFailureRateHigh

**Symptom:** Stripe Connect onboarding completion rate drops below 95% over 24 hours

**Severity:** Warning (Sev2 per Architecture §3.20.2)
**Component:** Payment
**Dashboard:** [Payment KPIs Panel - Connect](https://grafana.villagecompute.com/d/component-kpis?var-component=payment)

#### Causes

1. **Incomplete onboarding flows**
   - Vendors abandoning Stripe Connect form
   - Missing required business information
   - Failed identity verification

2. **Stripe account creation errors**
   - API errors during account creation
   - Invalid business details (EIN, SSN formatting)
   - Restricted business categories

3. **Webhook delivery failures**
   - `account.updated` webhooks not processed
   - Onboarding status not synced to database
   - Race conditions in status updates

4. **Geographic restrictions**
   - Unsupported countries for Stripe Connect
   - Missing required documents for region

#### Investigation Steps

1. **Check Onboarding Success Rate:**
   ```promql
   sum(rate(payment_connect_onboarding_completed[24h])) /
   (sum(rate(payment_connect_onboarding_completed[24h])) + sum(rate(payment_connect_onboarding_failed[24h])))
   ```

2. **Identify Failure Reasons:**
   ```promql
   sum(rate(payment_connect_onboarding_failed[24h])) by (reason)
   ```

3. **Check Vendor Onboarding Status:**
   ```sql
   SELECT vendor_id, stripe_account_id, onboarding_status, failure_reason
   FROM consignment_vendors
   WHERE tenant_id = 'YOUR_TENANT_UUID'
     AND stripe_account_id IS NOT NULL
     AND onboarding_status != 'complete'
     AND created_at > NOW() - INTERVAL '7 days'
   ORDER BY created_at DESC;
   ```

4. **Review Stripe Dashboard:**
   - Login to Stripe Dashboard → Connect → Accounts
   - Filter by "Onboarding" status
   - Review account details for blocked vendors

#### Resolution

1. **Manual Onboarding Assistance:**
   - Contact vendors with incomplete onboarding:
     ```bash
     # Generate new onboarding link
     curl -X POST https://api.stripe.com/v1/account_links \
       -u sk_live_XXX: \
       -d "account=acct_XXX" \
       -d "refresh_url=https://merchant.example.com/connect/refresh" \
       -d "return_url=https://merchant.example.com/connect/return" \
       -d "type=account_onboarding"
     ```

2. **Fix API Integration Issues:**
   - If account creation failing, check required fields:
     ```java
     // Ensure all required fields present
     AccountCreateParams params = AccountCreateParams.builder()
       .setType(AccountCreateParams.Type.EXPRESS)
       .setCountry("US")
       .setEmail(vendor.getEmail())
       .setBusinessType(AccountCreateParams.BusinessType.INDIVIDUAL)
       .build();
     ```

3. **Webhook Sync Fix:**
   - Manually sync onboarding status from Stripe:
     ```bash
     curl -X POST https://admin.villagecompute.com/api/admin/vendors/{vendorId}/sync-connect-status \
       -H "Authorization: Bearer $TOKEN"
     ```

4. **Geographic Support:**
   - If unsupported countries, document limitation:
     ```bash
     Feature Flag: payment.connect.restricted-countries=[CN,RU,KP]
     ```
   - Show clear error message to vendors in restricted regions

---

## Common Issues

### Issue: Payment Succeeded but Order Not Created

**Symptoms:**
- Customer charged but no order confirmation
- Stripe shows successful payment intent
- Order not in database

**Diagnosis:**
1. Check payment intent status:
   ```bash
   stripe payment_intents retrieve pi_XXX
   ```

2. Find orphaned payment intent:
   ```sql
   SELECT * FROM payment_transactions
   WHERE stripe_payment_intent_id = 'pi_XXX'
     AND order_id IS NULL;
   ```

3. Check checkout saga logs:
   ```bash
   kubectl logs -l app=storefront --tail=500 | grep "checkout.saga.*pi_XXX"
   ```

**Resolution:**
1. Manually reconcile payment to order:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/payments/reconcile \
     -H "Authorization: Bearer $TOKEN" \
     -d '{
       "payment_intent_id": "pi_XXX",
       "cart_id": "cart_UUID",
       "action": "create_order"
     }'
   ```

2. If order creation failed mid-saga, retry saga:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/checkout/retry-saga \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"saga_id": "saga_UUID"}'
   ```

3. If unrecoverable, refund customer:
   ```bash
   stripe refunds create --payment-intent=pi_XXX --reason=requested_by_customer
   ```

---

### Issue: Duplicate Payment Charges

**Symptoms:**
- Customer charged multiple times for same order
- Multiple payment intent IDs for single cart
- Duplicate webhook events processed

**Diagnosis:**
1. Check for duplicate payment intents:
   ```sql
   SELECT cart_id, COUNT(*) AS payment_count
   FROM payment_transactions
   WHERE cart_id = 'cart_UUID'
   GROUP BY cart_id
   HAVING COUNT(*) > 1;
   ```

2. Verify idempotency key usage:
   ```sql
   SELECT idempotency_key, COUNT(*) AS usage_count
   FROM payment_transactions
   WHERE idempotency_key IS NOT NULL
   GROUP BY idempotency_key
   HAVING COUNT(*) > 1;
   ```

3. Check webhook duplicate detection:
   ```sql
   SELECT event_id, COUNT(*) AS process_count
   FROM webhook_events
   WHERE event_id = 'evt_XXX'
   GROUP BY event_id;
   ```

**Resolution:**
1. Refund duplicate charges:
   ```bash
   # Identify duplicates
   SELECT * FROM payment_transactions WHERE cart_id = 'cart_UUID';

   # Refund extra charges (keep first successful)
   stripe refunds create \
     --payment-intent=pi_DUPLICATE \
     --reason=duplicate \
     --metadata[cart_id]=cart_UUID
   ```

2. Fix idempotency key generation:
   - Verify cart_id used as idempotency seed
   - Ensure idempotency key persists across retries

3. Strengthen webhook deduplication:
   ```sql
   CREATE UNIQUE INDEX CONCURRENTLY idx_webhook_events_event_id_unique
   ON webhook_events(event_id);
   ```

---

### Issue: Refund Processing Failures

**Symptoms:**
- Refund API calls to Stripe fail
- Customer not credited for return
- Refund stuck in "pending" status

**Diagnosis:**
1. Check refund status in Stripe:
   ```bash
   stripe refunds retrieve re_XXX
   ```

2. Find failed refund attempts:
   ```sql
   SELECT * FROM payment_refunds
   WHERE status = 'failed'
     AND created_at > NOW() - INTERVAL '24 hours'
   ORDER BY created_at DESC;
   ```

3. Review refund error logs:
   ```bash
   kubectl logs -l app=storefront --tail=200 | grep "payment.refund.failed"
   ```

**Resolution:**
1. Retry failed refund:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/refunds/{refundId}/retry \
     -H "Authorization: Bearer $TOKEN"
   ```

2. If Stripe API error, manually create refund:
   ```bash
   stripe refunds create \
     --payment-intent=pi_XXX \
     --amount=1000 \
     --reason=requested_by_customer
   ```

3. Update refund status in database:
   ```sql
   UPDATE payment_refunds
   SET status = 'completed',
       stripe_refund_id = 're_XXX',
       completed_at = NOW()
   WHERE id = 'refund_UUID';
   ```

---

## Metrics Reference

### Key Metrics

| Metric | Description | Query |
|--------|-------------|-------|
| Webhook Success Rate | % webhooks processed successfully | `sum(rate(payment_webhook_processed[5m])) / sum(rate(payment_webhook_received[5m]))` |
| Transaction Latency (p95) | 95th percentile payment processing time | `histogram_quantile(0.95, payment_transaction_duration_bucket)` |
| Multi-Tender Usage | % checkouts using multiple payment methods | `rate(payment_multi_tender_used[5m]) / rate(payment_transaction_success[5m])` |
| Refund Rate | % transactions resulting in refund | `sum(rate(payment_refund_success[1d])) / sum(rate(payment_transaction_success[1d]))` |
| Connect Payout Success | % successful vendor payouts | `sum(rate(payment_connect_payout_created[5m])) / (sum(rate(payment_connect_payout_created[5m])) + sum(rate(payment_connect_payout_failed[5m])))` |

### Dashboard Links

- [Payment KPIs Dashboard](https://grafana.villagecompute.com/d/component-kpis?orgId=1&var-component=payment)
- [Stripe Webhook Monitoring](https://grafana.villagecompute.com/d/webhooks?var-provider=stripe)
- [Jaeger Traces - Payment](https://jaeger.villagecompute.com/search?service=storefront&tags=%7B%22component%22%3A%22payment%22%7D)
- [Stripe Dashboard](https://dashboard.stripe.com/dashboard)

---

## Feature Flags

### Emergency Kill Switches

| Flag | Purpose | Default |
|------|---------|---------|
| `payment.stripe.enabled` | Disable Stripe payment processing | `true` |
| `payment.webhook.enabled` | Disable webhook event processing | `true` |
| `payment.webhook.handler.[event_type].enabled` | Disable specific event handler | `true` |
| `payment.multi-tender.enabled` | Disable multi-tender payments | `true` |
| `payment.connect.enabled` | Disable Stripe Connect operations | `true` |

### Performance Tuning Flags

| Flag | Purpose | Default |
|------|---------|---------|
| `payment.webhook.async` | Process webhooks asynchronously | `false` |
| `payment.multi-tender.parallel` | Parallelize payment method processing | `false` |
| `payment.fraud-check.async` | Move fraud checks post-authorization | `false` |
| `payment.gift-card.cache-enabled` | Cache gift card balance lookups | `true` |

---

## Escalation

### When to Escalate

1. **Critical**: Webhook failure rate >5% for >10 minutes (auto-page via PagerDuty)
2. **High**: Payment reconciliation discrepancies >$10,000
3. **Medium**: Connect onboarding failures affecting >10 vendors/day
4. **Low**: Refund processing delays <24 hours old

### Escalation Contacts

- **On-Call Engineer**: PagerDuty rotation (auto-paged for Sev1)
- **Payment Product Owner**: @payments-team in Slack
- **Stripe Support**: support@stripe.com (include account ID)
- **Finance Team**: finance@villagecompute.com (for reconciliation issues)

---

## Related Documentation

- [Payment Architecture](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#payment)
- [Stripe Integration Guide](../integrations/stripe.md)
- [Multi-Tender Payment Design](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#multi-tender)
- [Observability Framework](./observability.md)
- [Platform Admin Console Guide](./platform-admin.md)
