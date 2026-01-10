# Shipping Integration Operational Runbook

**Last Updated:** 2026-01-08
**Owner:** Platform Operations Team
**Related Tasks:** I3.T4 - Shipping carrier adapter integration
**Architecture Reference:** Blueprint Foundation §3.10 Operational Runbooks

## Overview

This runbook provides operational procedures for managing the shipping integration layer, which coordinates address validation, rate fetching, and label generation across multiple carrier APIs (USPS, UPS, FedEx).

The shipping service implements:
- **Rate caching** (15-minute TTL per Architecture §3.0 Rulebook)
- **Fallback table rates** when carriers are unavailable
- **Retry/backoff** logic using Resilience4j (3 retries with 500ms initial delay)
- **Correlation tracking** for distributed tracing

## Service Architecture

### Components

- **ShippingService**: Main orchestrator handling rate aggregation, caching, and fallback logic
- **CarrierRateAdapter**: Interface for carrier-specific implementations
  - **USPSAdapter**: USPS Web Tools API integration
  - **UPSAdapter**: UPS Rating API integration
  - **FedExAdapter**: FedEx Web Services API integration
- **Caffeine Cache**: 15-minute TTL for shipping rates (max 500 entries)
- **ShippingProfile**: Per-tenant carrier credentials and preferences
- **ShippingLabel**: Label tracking and metadata storage

### Key Metrics

Monitor these metrics in Prometheus/Grafana:

```promql
# Shipping rate fetch latency (target: <200ms p95)
histogram_quantile(0.95, shipping_adapter_fetch_rates_seconds_bucket)

# Cache hit rate (target: >70%)
rate(shipping_rate_cache_hit_total[5m]) /
  (rate(shipping_rate_cache_hit_total[5m]) + rate(shipping_rate_cache_miss_total[5m]))

# Fallback rate usage (alert if >5%)
rate(shipping_rate_fallback_used_total[5m])

# Carrier availability (alert if carrier down >15min)
shipping_adapter_requests_total{status="PROVIDER_DOWN"}

# Label creation errors (alert if >1% failure rate)
rate(shipping_adapter_requests_total{operation="create_label", status="ERROR"}[5m])
```

---

## Common Operational Procedures

### 1. Carrier API Outage Response

**Scenario:** One or more carrier APIs are down or returning errors.

**Detection:**
- Alert: `Carrier API Outage - [CARRIER] unavailable for >15 minutes`
- Metrics: `shipping_adapter_requests_total{status="PROVIDER_DOWN"}` spiking
- Logs: `ERROR [CarrierRateAdapter] Carrier [CARRIER] rate fetch failed after retries`

**Impact:**
- Shipping rates may fall back to table rates (degraded service)
- Label creation for affected carrier will fail
- Address validation may skip unavailable carriers

**Response Steps:**

1. **Verify Outage Scope**
   ```bash
   # Check carrier adapter health
   curl -H "Host: tenant.villagecompute.com" \
        http://localhost:8080/q/health

   # Check recent carrier errors
   kubectl logs -l app=village-storefront --tail=100 | \
     grep "Carrier.*unavailable"
   ```

2. **Enable Fallback Rates (if not auto-enabled)**
   ```bash
   # Check current feature flag status
   curl -H "Host: tenant.villagecompute.com" \
        http://localhost:8080/api/v1/admin/feature-flags

   # Enable fallback if needed
   kubectl set env deployment/village-storefront \
     SHIPPING_FALLBACK_ENABLED=true
   ```

3. **Notify Merchants via Status Page**
   - Post incident to status.villagecompute.com
   - Update message: "Shipping rates for [CARRIER] temporarily unavailable. Using fallback table rates."

4. **Monitor Fallback Usage**
   ```bash
   # Track fallback invocations
   kubectl logs -l app=village-storefront -f | \
     grep "fallback table rates"

   # Check Prometheus metric
   rate(shipping_rate_fallback_used_total[5m])
   ```

5. **Queue Re-Rating Jobs (after recovery)**
   - Identify orders created during outage with fallback rates
   - Enqueue re-rating jobs to reconcile actual carrier rates
   ```sql
   -- Find orders using fallback rates during outage window
   SELECT o.id, o.order_number, o.shipping_amount
   FROM orders o
   WHERE o.created_at BETWEEN '2026-01-08 10:00:00+00'
                          AND '2026-01-08 12:00:00+00'
     AND o.metadata->>'shipping_fallback_used' = 'true';
   ```

6. **Verify Recovery**
   ```bash
   # Test carrier API directly
   curl -X POST http://localhost:8080/api/v1/shipping/rates \
     -H "Content-Type: application/json" \
     -H "Host: tenant.villagecompute.com" \
     -H "X-Correlation-ID: $(uuidgen)" \
     -d @test_rate_request.json

   # Check for fallbackUsed=false in response
   ```

---

### 2. Rate Cache Invalidation

**Scenario:** Carrier rate tables updated or cache contains stale data.

**Procedure:**

1. **Invalidate All Shipping Rate Cache**
   ```bash
   # Via admin API (preferred)
   curl -X POST \
     http://localhost:8080/api/v1/admin/cache/invalidate/shipping-rate-cache \
     -H "Authorization: Bearer $ADMIN_TOKEN"

   # Or via JMX (if enabled)
   jconsole -> io.quarkus.cache:name=shipping-rate-cache -> invalidateAll()
   ```

2. **Invalidate Cache for Specific Tenant**
   ```bash
   # Call ShippingService.invalidateRateCache() via admin endpoint
   curl -X POST \
     http://localhost:8080/api/v1/admin/tenants/$TENANT_ID/cache/shipping/invalidate \
     -H "Authorization: Bearer $ADMIN_TOKEN"
   ```

3. **Verify Cache Rebuild**
   ```bash
   # Monitor cache miss rate (should spike temporarily)
   rate(shipping_rate_cache_miss_total[1m])

   # Check logs for cache loader execution
   kubectl logs -l app=village-storefront --tail=50 | \
     grep "Shipping rate cache miss"
   ```

---

### 3. Carrier Credential Rotation

**Scenario:** Carrier API credentials need to be rotated (expiring keys, security incident).

**Procedure:**

1. **Update Credentials in Secret Manager**
   ```bash
   # Update Kubernetes secrets
   kubectl create secret generic shipping-credentials \
     --from-literal=USPS_USER_ID=new_user_id \
     --from-literal=UPS_ACCESS_KEY=new_access_key \
     --from-literal=FEDEX_API_KEY=new_api_key \
     --dry-run=client -o yaml | kubectl apply -f -
   ```

2. **Trigger Rolling Restart**
   ```bash
   # Rolling restart to pick up new credentials
   kubectl rollout restart deployment/village-storefront

   # Monitor rollout status
   kubectl rollout status deployment/village-storefront
   ```

3. **Verify New Credentials**
   ```bash
   # Test each carrier adapter
   curl -X POST http://localhost:8080/api/v1/shipping/rates \
     -H "Content-Type: application/json" \
     -d '{
       "origin": {...},
       "destination": {...},
       "packageInfo": {"weightOz": 16},
       "serviceLevels": ["GROUND"]
     }'

   # Check for successful carrier responses (not PROVIDER_DOWN)
   ```

---

### 4. Rate Limit Handling

**Scenario:** Carrier API rate limits exceeded.

**Detection:**
- Logs: `RateStatus.RATE_LIMIT_EXCEEDED` in adapter responses
- Metrics: `shipping_adapter_requests_total{status="RATE_LIMIT_EXCEEDED"}`

**Response:**

1. **Identify Affected Tenant**
   ```bash
   # Check logs for tenant context
   kubectl logs -l app=village-storefront | \
     grep "RATE_LIMIT_EXCEEDED" | \
     jq '.tenant_id'
   ```

2. **Enable Rate Caching Aggressive Mode** (extend TTL temporarily)
   ```bash
   # Increase cache TTL to 30 minutes
   kubectl set env deployment/village-storefront \
     QUARKUS_CACHE_CAFFEINE_SHIPPING_RATE_CACHE_EXPIRE_AFTER_WRITE=PT30M
   ```

3. **Contact Carrier Support** (if persistent)
   - USPS: Call API support, request limit increase
   - UPS: Review contract terms, upgrade if needed
   - FedEx: Open support ticket via developer portal

4. **Monitor Recovery**
   ```bash
   # Track rate limit errors over time
   rate(shipping_adapter_requests_total{status="RATE_LIMIT_EXCEEDED"}[5m])
   ```

---

### 5. Address Validation Failures

**Scenario:** High rate of address validation failures or incorrect normalizations.

**Detection:**
- Logs: `AddressValidationResult.status=INVALID` spike
- Customer reports of incorrect addresses

**Procedure:**

1. **Review Validation Errors**
   ```bash
   # Tail validation results
   kubectl logs -l app=village-storefront -f | \
     grep "Address validation" | \
     jq 'select(.validation_status != "VALID")'
   ```

2. **Check Carrier Availability**
   ```bash
   # Verify all carriers responding
   curl -X POST http://localhost:8080/api/v1/shipping/validate-address \
     -H "Content-Type: application/json" \
     -d '{
       "street1": "123 Main St",
       "city": "San Francisco",
       "state": "CA",
       "postalCode": "94102",
       "country": "US"
     }'
   ```

3. **Review Adapter Logic**
   - Check for recent code changes to validation adapters
   - Verify carrier API version compatibility

4. **Enable Manual Override** (if needed)
   ```bash
   # Disable strict validation temporarily
   kubectl set env deployment/village-storefront \
     SHIPPING_VALIDATION_STRICT=false
   ```

---

## Configuration Reference

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SHIPPING_RATES_ENABLED` | `true` | Master kill switch for shipping rate functionality |
| `SHIPPING_FALLBACK_ENABLED` | `true` | Enable fallback table rates when carriers down |
| `USPS_USER_ID` | (required) | USPS Web Tools API user ID |
| `UPS_ACCESS_KEY` | (required) | UPS API access key |
| `FEDEX_API_KEY` | (required) | FedEx API key |
| `USPS_TIMEOUT_MS` | `10000` | USPS API timeout (milliseconds) |
| `UPS_TIMEOUT_MS` | `10000` | UPS API timeout |
| `FEDEX_TIMEOUT_MS` | `10000` | FedEx API timeout |
| `USPS_MAX_RETRIES` | `3` | USPS retry attempts |
| `UPS_MAX_RETRIES` | `3` | UPS retry attempts |
| `FEDEX_MAX_RETRIES` | `3` | FedEx retry attempts |

### Cache Configuration

```properties
# Shipping rate cache (15-minute TTL per Architecture §3.0)
quarkus.cache.caffeine."shipping-rate-cache".maximum-size=500
quarkus.cache.caffeine."shipping-rate-cache".expire-after-write=PT15M
```

---

## Alerting Rules

Add these Prometheus alerts to your alerting configuration:

```yaml
groups:
  - name: shipping_integration
    rules:
      - alert: CarrierAPIDown
        expr: rate(shipping_adapter_requests_total{status="PROVIDER_DOWN"}[5m]) > 0.1
        for: 15m
        labels:
          severity: warning
          component: shipping
        annotations:
          summary: "Carrier API {{ $labels.carrier }} unavailable"
          description: "Carrier {{ $labels.carrier }} returning PROVIDER_DOWN for >15 minutes"

      - alert: HighFallbackRateUsage
        expr: rate(shipping_rate_fallback_used_total[5m]) > 0.05
        for: 10m
        labels:
          severity: warning
          component: shipping
        annotations:
          summary: "High fallback rate usage (>5%)"
          description: "Shipping rates using fallback table rates instead of live carrier rates"

      - alert: ShippingCacheMissRateHigh
        expr: |
          rate(shipping_rate_cache_miss_total[5m]) /
          (rate(shipping_rate_cache_hit_total[5m]) + rate(shipping_rate_cache_miss_total[5m])) > 0.5
        for: 15m
        labels:
          severity: info
          component: shipping
        annotations:
          summary: "Shipping cache hit rate <50%"
          description: "Consider increasing cache size or TTL"

      - alert: LabelCreationFailureRate
        expr: |
          rate(shipping_adapter_requests_total{operation="create_label", status="ERROR"}[5m]) /
          rate(shipping_adapter_requests_total{operation="create_label"}[5m]) > 0.01
        for: 5m
        labels:
          severity: critical
          component: shipping
        annotations:
          summary: "Label creation failure rate >1%"
          description: "Shipping label creation failing for carrier {{ $labels.carrier }}"
```

---

## Troubleshooting

### Issue: All Carriers Returning PROVIDER_DOWN

**Symptoms:**
- All rate requests return fallback rates
- Logs show connectivity errors

**Diagnosis:**
```bash
# Check network connectivity
kubectl exec -it deployment/village-storefront -- curl -v https://secure.shippingapis.com
kubectl exec -it deployment/village-storefront -- curl -v https://onlinetools.ups.com
kubectl exec -it deployment/village-storefront -- curl -v https://apis.fedex.com

# Check DNS resolution
kubectl exec -it deployment/village-storefront -- nslookup secure.shippingapis.com
```

**Resolution:**
- Verify network policies allow egress to carrier domains
- Check firewall rules
- Verify DNS configuration

### Issue: Cache Not Expiring After 15 Minutes

**Symptoms:**
- Old rates returned beyond TTL
- Cache hit rate suspiciously high (>95%)

**Diagnosis:**
```bash
# Check cache config
kubectl exec -it deployment/village-storefront -- \
  env | grep QUARKUS_CACHE

# Check cache statistics via JMX
```

**Resolution:**
- Verify cache TTL configuration
- Manually invalidate cache and monitor rebuild

### Issue: Correlation IDs Not Propagating

**Symptoms:**
- Distributed traces incomplete
- Missing correlation in carrier adapter logs

**Diagnosis:**
```bash
# Check correlation ID in logs
kubectl logs -l app=village-storefront | \
  grep "correlationId" | head -20
```

**Resolution:**
- Verify OpenTelemetry configuration
- Check that `X-Correlation-ID` header is passed through adapter layers

---

## Related Documentation

- Architecture Blueprint: `docs/architecture/01_Blueprint_Foundation.md` §3.0 Rulebook
- Integration Adapter Layer: `docs/architecture/02_System_Structure_and_Data.md` §4
- Operational Architecture: `docs/architecture/04_Operational_Architecture.md` §3.10
- OpenAPI Specification: `api/v1/openapi.yaml` (Shipping endpoints)
- Source Code:
  - `modules/core-platform/src/main/java/villagecompute/storefront/services/ShippingService.java`
  - `modules/core-platform/src/main/java/villagecompute/storefront/integration/shipping/`

---

## Escalation Contacts

- **On-Call Engineer:** PagerDuty rotation
- **Platform Lead:** @platform-lead (Slack)
- **Carrier Support:**
  - USPS: 1-800-344-7779 (API Support)
  - UPS: developer.ups.com/support
  - FedEx: developer.fedex.com/contact

---

**End of Shipping Integration Runbook**
