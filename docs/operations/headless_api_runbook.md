# Headless API & OAuth Runbook

**Component:** Headless API & OAuth Client Management
**Owner:** Platform Engineering
**Last Updated:** 2026-01-10
**Related Docs:** [Architecture §3.20.2](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md), [Observability Framework](./observability.md)

---

## Overview

The Headless API module provides REST endpoints for external integrations via OAuth 2.0 authentication, supporting partner applications, mobile apps, and custom storefronts. This runbook provides operational guidance for OAuth token issuance, rate limiting, and API performance troubleshooting.

### Component KPIs

| KPI | Target | Alert Threshold | Criticality |
|-----|--------|----------------|-------------|
| OAuth Token Issuance Error Rate | <1% | >5% for 10min | Warning (Sev2) |
| API Request Latency (p95) | <500ms | >500ms for 10min | Warning |
| API Error Rate | <2% | >5% for 10min | Warning |
| Rate Limit Hit Rate | Varies | >10/sec per client | Info |
| Client Credential Issuance Errors | <0.1% | >0.01/sec for 5min | Warning (Sev2) |

### Service Dependencies

- **PostgreSQL**: OAuth client registry, token storage, rate limit counters
- **JWT Library**: Token signing and validation
- **Caffeine Cache**: Token validation cache, rate limit window tracking
- **Storefront API**: Backend services accessed by headless clients

---

## Alerts

### Alert: HeadlessOAuthTokenIssuanceErrorRateHigh (SEV2)

**Symptom:** OAuth client credential issuance errors >5% during partner onboarding week

**Severity:** Warning (Sev2 per Architecture §3.20.2)
**Component:** Headless API

#### Investigation Steps

1. **Check Token Issuance Error Rate:**
   ```promql
   sum(rate(headless_oauth_token_failed[5m])) by (tenant_id) /
   sum(rate(headless_oauth_token_issued[5m] + headless_oauth_token_failed[5m])) by (tenant_id)
   ```

2. **Identify Failure Reasons:**
   ```promql
   sum(rate(headless_oauth_token_failed[5m])) by (reason, grant_type)
   ```

3. **Check OAuth Client Status:**
   ```sql
   SELECT client_id, client_name, status, last_used_at
   FROM oauth_clients
   WHERE tenant_id = 'YOUR_TENANT_UUID'
     AND status = 'active'
   ORDER BY last_used_at DESC;
   ```

4. **Review Error Logs:**
   ```bash
   kubectl logs -l app=storefront --tail=200 | grep "headless.oauth.token.failed"
   ```

#### Resolution

1. **Invalid Credentials:**
   - Verify client secret hasn't expired:
     ```sql
     SELECT client_id, secret_expires_at
     FROM oauth_clients
     WHERE client_id = 'CLIENT_ID';
     ```
   - Rotate client secret if expired:
     ```bash
     curl -X POST https://admin.villagecompute.com/api/admin/oauth/clients/{clientId}/rotate-secret \
       -H "Authorization: Bearer $TOKEN"
     ```

2. **Invalid Grant Type:**
   - Ensure client allowed grant types:
     ```sql
     UPDATE oauth_clients
     SET allowed_grant_types = ARRAY['client_credentials', 'refresh_token']
     WHERE client_id = 'CLIENT_ID';
     ```

3. **Rate Limit Configuration:**
   - If false positives, adjust rate limits:
     ```properties
     headless.oauth.rate-limit.per-minute=100
     headless.oauth.rate-limit.burst=20
     ```

4. **Client Not Found:**
   - Verify client exists and is active:
     ```sql
     SELECT * FROM oauth_clients WHERE client_id = 'CLIENT_ID';
     ```
   - If revoked, explain reason to partner

---

### Alert: HeadlessApiRateLimitHitRateHigh

**Symptom:** High rate limit hit rate for OAuth client (>10/sec)

**Severity:** Info (tune limits per Architecture §3.20.2)
**Component:** Headless API

#### Investigation Steps

1. **Check Rate Limit Hit Rate:**
   ```promql
   sum(rate(headless_api_rate_limit_hit[5m])) by (tenant_id, client_id, endpoint)
   ```

2. **Review Client Usage Pattern:**
   ```sql
   SELECT endpoint, COUNT(*) AS request_count
   FROM api_access_log
   WHERE client_id = 'CLIENT_ID'
     AND created_at > NOW() - INTERVAL '1 hour'
   GROUP BY endpoint
   ORDER BY request_count DESC;
   ```

3. **Check Client Tier:**
   ```sql
   SELECT client_id, rate_limit_tier, requests_per_minute
   FROM oauth_clients
   WHERE client_id = 'CLIENT_ID';
   ```

#### Resolution

1. **Legitimate High-Volume Client:**
   - Upgrade client tier:
     ```sql
     UPDATE oauth_clients
     SET rate_limit_tier = 'premium',
         requests_per_minute = 1000
     WHERE client_id = 'CLIENT_ID';
     ```

2. **Inefficient API Usage:**
   - Contact partner to optimize:
     - Use pagination instead of fetching all records
     - Implement client-side caching
     - Batch requests where possible
   - Provide optimization guidance documentation

3. **Temporary Rate Limit Exemption:**
   - For one-time data migration:
     ```bash
     Feature Flag: headless.api.rate-limit.exempt-clients=[CLIENT_ID]
     ```
   - Document exemption reason and expiry

4. **Suspected Abuse:**
   - Investigate request patterns for abuse
   - Temporarily suspend client if malicious:
     ```sql
     UPDATE oauth_clients
     SET status = 'suspended',
         suspension_reason = 'Rate limit abuse - ticket #12345'
     WHERE client_id = 'CLIENT_ID';
     ```

---

### Alert: HeadlessApiRequestLatencyHigh

**Symptom:** Headless API request p95 exceeds 500ms target

**Severity:** Warning
**Component:** Headless API

#### Investigation Steps

1. **Check Latency by Endpoint:**
   ```promql
   histogram_quantile(0.95, sum(rate(headless_api_request_duration_bucket[5m])) by (le, endpoint))
   ```

2. **Identify Slow Endpoints:**
   ```promql
   topk(10, avg(rate(headless_api_request_duration_sum[5m])) by (endpoint) /
            avg(rate(headless_api_request_duration_count[5m])) by (endpoint))
   ```

3. **Check Database Query Performance:**
   ```sql
   SELECT query, mean_exec_time, calls
   FROM pg_stat_statements
   WHERE query LIKE '%headless%'
   ORDER BY mean_exec_time DESC LIMIT 10;
   ```

#### Resolution

1. **Enable Response Caching:**
   ```properties
   headless.api.cache.enabled=true
   headless.api.cache.ttl=300
   headless.api.cache.endpoints=/api/products,/api/categories
   ```

2. **Optimize Database Queries:**
   - Add indexes for common API queries:
     ```sql
     CREATE INDEX CONCURRENTLY idx_products_headless_api
     ON products(tenant_id, published_at DESC) WHERE published = true;
     ```

3. **Enable API Result Pagination:**
   - Enforce max page size:
     ```properties
     headless.api.pagination.max-page-size=100
     headless.api.pagination.default-page-size=25
     ```

4. **Rate Limit Slow Endpoints:**
   - Protect expensive endpoints:
     ```properties
     headless.api.endpoint-limits./api/reports.per-minute=10
     ```

---

## Common Issues

### Issue: OAuth Client Unable to Access Specific Endpoint

**Diagnosis:**
1. Check client scopes:
   ```sql
   SELECT client_id, scopes
   FROM oauth_clients
   WHERE client_id = 'CLIENT_ID';
   ```

2. Verify endpoint permission requirements:
   ```bash
   kubectl logs -l app=storefront --tail=100 | grep "headless.api.permission.denied.*CLIENT_ID"
   ```

**Resolution:**
- Grant required scopes:
  ```sql
  UPDATE oauth_clients
  SET scopes = array_append(scopes, 'products:read')
  WHERE client_id = 'CLIENT_ID';
  ```
- Invalidate token cache to pick up new scopes:
  ```bash
  curl -X POST https://admin.villagecompute.com/api/admin/oauth/clients/{clientId}/invalidate-tokens \
    -H "Authorization: Bearer $TOKEN"
  ```

---

### Issue: Webhook Delivery Failures for Headless API Events

**Diagnosis:**
1. Check webhook delivery status:
   ```promql
   sum(rate(headless_api_webhook_delivery{status="failed"}[5m])) by (client_id, event_type)
   ```

2. Review webhook endpoint status:
   ```sql
   SELECT webhook_url, last_success_at, last_failure_at, failure_count
   FROM oauth_client_webhooks
   WHERE client_id = 'CLIENT_ID';
   ```

**Resolution:**
1. Retry failed webhooks:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/oauth/webhooks/retry \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"client_id": "CLIENT_ID", "event_ids": ["evt_xxx"]}'
   ```

2. Verify webhook endpoint health:
   ```bash
   curl -X POST https://partner.example.com/webhooks \
     -H "Content-Type: application/json" \
     -d '{"test": true}'
   ```

3. Disable webhook if consistently failing:
   ```sql
   UPDATE oauth_client_webhooks
   SET enabled = false,
       disabled_reason = 'Endpoint unreachable - ticket #12345'
   WHERE client_id = 'CLIENT_ID';
   ```

---

## Metrics Reference

| Metric | Query |
|--------|-------|
| Token Issuance Rate | `rate(headless_oauth_token_issued[5m])` |
| Token Issuance Error Rate | `sum(rate(headless_oauth_token_failed[5m])) / sum(rate(headless_oauth_token_issued[5m] + headless_oauth_token_failed[5m]))` |
| API Request Rate | `rate(headless_api_request[5m])` |
| API Error Rate | `sum(rate(headless_api_request_error[5m])) / sum(rate(headless_api_request[5m]))` |
| Rate Limit Hit Rate | `rate(headless_api_rate_limit_hit[5m])` |
| Active OAuth Clients | `headless_oauth_clients_active` |

### Dashboard Links

- [Headless API KPIs Dashboard](https://grafana.villagecompute.com/d/component-kpis?var-component=headless-api)
- [OAuth Client Monitoring](https://grafana.villagecompute.com/d/oauth-clients)
- [Jaeger Traces - Headless API](https://jaeger.villagecompute.com/search?service=storefront&tags=%7B%22component%22%3A%22headless-api%22%7D)

---

## Feature Flags

| Flag | Purpose | Default |
|------|---------|---------|
| `headless.api.enabled` | Disable all headless API endpoints | `true` |
| `headless.oauth.enabled` | Disable OAuth token issuance | `true` |
| `headless.api.rate-limit.enabled` | Disable rate limiting (testing only) | `true` |
| `headless.api.rate-limit.exempt-clients` | Clients exempt from rate limits | `[]` |
| `headless.api.cache.enabled` | Enable response caching | `true` |

---

## Escalation

### When to Escalate

1. **High**: OAuth issuance errors >5% during partner onboarding
2. **Medium**: API latency degradation affecting multiple clients
3. **Low**: Individual client rate limit tuning requests

### Contacts

- **On-Call Engineer**: PagerDuty rotation
- **Partnerships Team**: partnerships@villagecompute.com (for client issues)
- **Product Owner**: @headless-api-team in Slack

---

## Related Documentation

- [Headless API Architecture](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#headless-api)
- [OAuth 2.0 Integration Guide](../integrations/oauth.md)
- [API Rate Limiting Policy](./rate-limiting.md)
- [Observability Framework](./observability.md)
