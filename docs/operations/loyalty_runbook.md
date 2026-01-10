# Loyalty Program Runbook

**Component:** Loyalty & Rewards Module
**Owner:** Platform Engineering
**Last Updated:** 2026-01-10
**Related Docs:** [Architecture §3.12](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md), [Observability Framework](./observability.md)

---

## Overview

The Loyalty & Rewards module manages customer loyalty programs including points accrual, redemption, tier management, and expiration processing for the Village Storefront platform. This runbook provides operational guidance for responding to alerts, troubleshooting common issues, and tuning performance.

### Component KPIs

| KPI | Target | Alert Threshold | Criticality |
|-----|--------|----------------|-------------|
| Points Accrual Latency (p95) | <100ms | >100ms for 10min | Warning |
| Tier Recalculation Duration | <15 minutes | >15min for 5min | Warning (Sev2) |
| Redemption Exceeds Accrual | N/A | >1.0 ratio for 3 days | Info (fraud indicator) |
| Queue Backlog Depth | <50 | >50 for 15min | Warning |
| Reservation Expiration Processing | <5 minutes | Delayed >10min | Warning |

### Service Dependencies

- **PostgreSQL**: Primary data store for points ledger and tier assignments
- **Background Job Scheduler**: Tier recalculation, expiration cleanup, reservation release
- **Caffeine Cache**: In-memory caching for tier configuration and customer eligibility
- **Checkout Orchestrator**: Integration for redemption during checkout

---

## Alerts

### Alert: LoyaltyPointsAccrualOverhead

**Symptom:** Points accrual p95 overhead exceeds 100ms target

**Severity:** Warning
**Component:** Loyalty
**Dashboard:** [Component KPIs Panel - Loyalty](https://grafana.villagecompute.com/d/component-kpis)

#### Causes

1. **Database contention on points_ledger table**
   - High concurrency during sale events
   - Lock contention on customer balance rows

2. **Tier eligibility check overhead**
   - Complex tier qualification rules
   - Uncached tier configuration lookups

3. **Slow external tier benefit calculations**
   - API calls to external promotion engines
   - Webhook notifications for tier changes

4. **Missing database indexes**
   - customer_id + tenant_id composite index missing
   - created_at index for expiration queries

#### Investigation Steps

1. **Check Current Accrual Latency:**
   ```promql
   histogram_quantile(0.95, sum(rate(loyalty_accrual_duration_bucket{tenant_id="YOUR_TENANT"}[5m])) by (le))
   ```

2. **Check Database Lock Contention:**
   ```sql
   SELECT * FROM pg_stat_activity
   WHERE datname = 'storefront'
     AND wait_event_type = 'Lock'
     AND query LIKE '%loyalty_points%';
   ```

3. **Check Accrual Rate:**
   ```promql
   rate(loyalty_accrual_operations{tenant_id="YOUR_TENANT"}[5m])
   ```

4. **Review Service Logs:**
   ```bash
   kubectl logs -l app=storefront --tail=100 | grep "loyalty.accrual"
   ```

#### Resolution

1. **Optimize Database Access:**
   - Ensure indexes exist:
     ```sql
     CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_loyalty_points_customer_tenant
     ON loyalty_points(customer_id, tenant_id);

     CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_loyalty_points_expiration
     ON loyalty_points(expires_at) WHERE status = 'active';
     ```
   - Consider partitioning `loyalty_points` table by tenant_id for large installations

2. **Enable Tier Configuration Caching:**
   - Verify cache settings in `application.properties`:
     ```properties
     loyalty.tier.cache.ttl=3600
     loyalty.tier.cache.max-size=1000
     ```

3. **Batch Accrual Operations:**
   - For high-volume stores, enable batch accrual:
     ```properties
     loyalty.accrual.batch-enabled=true
     loyalty.accrual.batch-window-ms=100
     ```

4. **Feature Flag Override (Emergency):**
   - Temporarily disable synchronous tier checks during accrual:
     ```bash
     # Via Platform Admin Console
     Feature Flag: loyalty.tier.check.async=true
     ```

---

### Alert: LoyaltyTierRecalculationSlow

**Symptom:** Tier recalculation job exceeding 15 minutes runtime

**Severity:** Warning (Sev2 per Architecture §3.20.2)
**Component:** Loyalty
**Dashboard:** [Component KPIs Panel - Loyalty](https://grafana.villagecompute.com/d/component-kpis)

#### Causes

1. **Large customer base with complex tier rules**
   - Scanning all customers nightly
   - Multiple tier thresholds with historical aggregations

2. **Slow historical points aggregation**
   - Full table scan on loyalty_points for date ranges
   - Missing indexes on created_at, customer_id

3. **Database connection pool exhaustion**
   - Tier recalculation job consuming all connections
   - Blocking other operations

4. **Concurrent tier change webhooks**
   - Synchronous webhook delivery to external systems
   - Webhook timeouts causing retries

#### Investigation Steps

1. **Check Last Recalculation Duration:**
   ```promql
   max(loyalty_tier_recalculation_duration_bucket{tenant_id="YOUR_TENANT"})
   ```

2. **Check Customers Processed:**
   ```promql
   loyalty_tier_recalculation_customers{tenant_id="YOUR_TENANT"}
   ```

3. **Check Job Execution Logs:**
   ```bash
   kubectl logs -l app=storefront --tail=200 | grep "loyalty.tier.recalculation"
   ```

4. **Check Database Query Performance:**
   ```sql
   SELECT query, mean_exec_time, calls
   FROM pg_stat_statements
   WHERE query LIKE '%loyalty_tier%'
   ORDER BY mean_exec_time DESC LIMIT 10;
   ```

#### Resolution

1. **Optimize Tier Qualification Query:**
   - Use materialized views for historical aggregations:
     ```sql
     CREATE MATERIALIZED VIEW loyalty_customer_spend_rolling_12mo AS
     SELECT customer_id, tenant_id, SUM(points_accrued) AS total_points
     FROM loyalty_points
     WHERE created_at > NOW() - INTERVAL '12 months'
     GROUP BY customer_id, tenant_id;

     CREATE INDEX ON loyalty_customer_spend_rolling_12mo(tenant_id, customer_id);
     ```
   - Refresh materialized view before tier recalculation job

2. **Batch Processing with Pagination:**
   - Configure batch size in `application.properties`:
     ```properties
     loyalty.tier.recalculation.batch-size=500
     loyalty.tier.recalculation.commit-interval=100
     ```

3. **Async Webhook Delivery:**
   - Enable async webhook queue:
     ```properties
     loyalty.tier.webhook.async=true
     loyalty.tier.webhook.queue-size=1000
     ```

4. **Increase Job Timeout:**
   - For tenants with >100k customers:
     ```properties
     loyalty.tier.recalculation.timeout-minutes=30
     ```

5. **Feature Flag Override (Emergency):**
   - Skip tier recalculation for tonight:
     ```bash
     Feature Flag: loyalty.tier.recalculation.enabled=false
     ```
   - Investigate and re-enable next day

---

### Alert: LoyaltyRedemptionExceedsAccrual

**Symptom:** Loyalty redemption exceeding accrual for more than 3 consecutive days

**Severity:** Info (potential fraud indicator per Architecture §3.20.3)
**Component:** Loyalty
**Dashboard:** [Component KPIs Panel - Loyalty](https://grafana.villagecompute.com/d/component-kpis)

#### Causes

1. **Manual points adjustment by merchant**
   - Goodwill credits issued without corresponding purchases
   - Points imported from legacy system

2. **Fraudulent activity**
   - Multiple accounts redeeming same points
   - Points manipulation via API exploits

3. **Migrated historical points**
   - One-time bulk import of legacy points
   - Customers redeeming imported balances

4. **Promotional bonus points**
   - Signup bonuses, referral bonuses
   - Time-limited promotional multipliers

#### Investigation Steps

1. **Check Redemption/Accrual Ratio:**
   ```promql
   sum(increase(loyalty_points_redeemed{tenant_id="YOUR_TENANT"}[1d])) /
   sum(increase(loyalty_points_accrued{tenant_id="YOUR_TENANT"}[1d]))
   ```

2. **Identify Top Redeemers:**
   ```sql
   SELECT customer_id,
          SUM(CASE WHEN transaction_type = 'redemption' THEN -points ELSE 0 END) AS redeemed,
          SUM(CASE WHEN transaction_type = 'accrual' THEN points ELSE 0 END) AS accrued
   FROM loyalty_points
   WHERE tenant_id = 'YOUR_TENANT_UUID'
     AND created_at > NOW() - INTERVAL '7 days'
   GROUP BY customer_id
   HAVING SUM(CASE WHEN transaction_type = 'redemption' THEN -points ELSE 0 END) >
          SUM(CASE WHEN transaction_type = 'accrual' THEN points ELSE 0 END)
   ORDER BY redeemed DESC LIMIT 20;
   ```

3. **Check Manual Adjustments:**
   ```sql
   SELECT * FROM loyalty_points
   WHERE tenant_id = 'YOUR_TENANT_UUID'
     AND transaction_type = 'adjustment'
     AND created_at > NOW() - INTERVAL '7 days'
   ORDER BY created_at DESC;
   ```

4. **Review Audit Logs:**
   ```bash
   kubectl logs -l app=storefront --tail=500 | grep "loyalty.anomaly.redemption_exceeds_accrual"
   ```

#### Resolution

1. **Validate Expected Behavior:**
   - Confirm with merchant if bulk import or promotional campaign is active
   - Document expected redemption surge in incident notes

2. **Investigate Fraudulent Patterns:**
   - Check for duplicate customer accounts (same email, phone, address)
   - Review API access logs for unusual redemption patterns
   - Cross-reference with checkout logs for suspicious order patterns

3. **Temporary Limits (If Fraud Suspected):**
   - Enable redemption velocity limits:
     ```properties
     loyalty.redemption.max-per-day=1000
     loyalty.redemption.max-per-transaction=500
     ```

4. **Escalate to Fraud Team:**
   - If fraud indicators present, escalate to security team
   - Provide customer_ids and transaction IDs from investigation

5. **Feature Flag Override (Emergency):**
   - Temporarily disable redemptions for affected tenant:
     ```bash
     Feature Flag: loyalty.redemption.enabled=false (tenant-specific override)
     ```

---

### Alert: LoyaltyQueueBacklogHigh

**Symptom:** Loyalty processing queue backlog high (>50 jobs for 15min)

**Severity:** Warning
**Component:** Loyalty
**Dashboard:** [Component KPIs Panel - Loyalty](https://grafana.villagecompute.com/d/component-kpis)

#### Causes

1. **High checkout volume during sale events**
   - Black Friday, flash sales causing accrual spikes
   - Insufficient worker capacity

2. **Slow downstream webhook delivery**
   - External loyalty partners timing out
   - Webhook retries consuming worker capacity

3. **Job failure loop**
   - Poison pill message causing repeated failures
   - Job retry backoff not configured

4. **Database performance degradation**
   - Slow queries blocking job processing
   - Connection pool exhaustion

#### Investigation Steps

1. **Check Current Queue Depth:**
   ```promql
   loyalty_queue_depth{tenant_id="YOUR_TENANT"}
   ```

2. **Check Job Processing Rate:**
   ```promql
   rate(loyalty_job_executed{tenant_id="YOUR_TENANT", status="success"}[5m])
   ```

3. **Identify Failed Jobs:**
   ```bash
   kubectl logs -l app=storefront --tail=200 | grep "loyalty.job.executed.*failed"
   ```

4. **Check Dead Letter Queue:**
   ```sql
   SELECT * FROM background_jobs_dlq
   WHERE job_type LIKE 'loyalty%'
   ORDER BY failed_at DESC LIMIT 20;
   ```

#### Resolution

1. **Scale Worker Capacity:**
   - Increase worker replicas:
     ```bash
     kubectl scale deployment/storefront-worker --replicas=5
     ```
   - Monitor queue depth decrease

2. **Pause Problematic Job Types:**
   - If specific job type failing repeatedly:
     ```bash
     Feature Flag: loyalty.job.[job_type].enabled=false
     ```
   - Fix underlying issue, re-enable

3. **Increase Job Timeout:**
   - For slow webhook deliveries:
     ```properties
     loyalty.job.timeout-seconds=60
     loyalty.job.webhook.timeout-seconds=30
     ```

4. **Manually Drain Dead Letter Queue:**
   - After fix, replay failed jobs:
     ```bash
     curl -X POST https://admin.villagecompute.com/api/jobs/retry-dlq \
       -H "Authorization: Bearer $TOKEN" \
       -d '{"job_type":"loyalty.*", "tenant_id":"YOUR_TENANT_UUID"}'
     ```

---

## Common Issues

### Issue: Customer Points Balance Incorrect

**Symptoms:**
- Customer reports incorrect points balance
- Balance discrepancy between storefront and admin

**Diagnosis:**
1. Check ledger entries for customer:
   ```sql
   SELECT * FROM loyalty_points
   WHERE customer_id = 'CUSTOMER_UUID'
     AND tenant_id = 'TENANT_UUID'
   ORDER BY created_at DESC;
   ```

2. Calculate expected balance:
   ```sql
   SELECT SUM(CASE
     WHEN transaction_type = 'accrual' THEN points
     WHEN transaction_type = 'redemption' THEN -points
     WHEN transaction_type = 'expiration' THEN -points
     ELSE 0
   END) AS expected_balance
   FROM loyalty_points
   WHERE customer_id = 'CUSTOMER_UUID'
     AND tenant_id = 'TENANT_UUID'
     AND status = 'completed';
   ```

3. Compare with cached balance:
   ```java
   // Via Platform Admin API
   GET /api/admin/loyalty/customers/{customerId}/balance
   ```

**Resolution:**
1. If cache stale, clear customer cache:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/cache/invalidate \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"cache_type":"loyalty_customer", "customer_id":"CUSTOMER_UUID"}'
   ```

2. If ledger corrupted, run reconciliation:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/loyalty/reconcile \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"customer_id":"CUSTOMER_UUID", "tenant_id":"TENANT_UUID"}'
   ```

---

### Issue: Tier Upgrade Not Reflected

**Symptoms:**
- Customer qualifies for higher tier but not upgraded
- Tier benefits not applied at checkout

**Diagnosis:**
1. Check tier qualification:
   ```sql
   SELECT * FROM loyalty_tier_assignments
   WHERE customer_id = 'CUSTOMER_UUID'
     AND tenant_id = 'TENANT_UUID'
   ORDER BY assigned_at DESC LIMIT 5;
   ```

2. Check qualification criteria:
   ```sql
   SELECT SUM(points) AS total_points
   FROM loyalty_points
   WHERE customer_id = 'CUSTOMER_UUID'
     AND tenant_id = 'TENANT_UUID'
     AND created_at > NOW() - INTERVAL '12 months';
   ```

3. Check tier recalculation job status:
   ```promql
   loyalty_job_executed{job_type="tier_recalculation", status="success"}
   ```

**Resolution:**
1. Manually trigger tier recalculation for customer:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/loyalty/customers/{customerId}/recalculate-tier \
     -H "Authorization: Bearer $TOKEN"
   ```

2. If tier rules changed, run full tenant recalculation:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/loyalty/tenants/{tenantId}/recalculate-all-tiers \
     -H "Authorization: Bearer $TOKEN"
   ```

---

## Metrics Reference

### Key Metrics

| Metric | Description | Query |
|--------|-------------|-------|
| Accrual Rate | Points accrued per second | `rate(loyalty_points_accrued[5m])` |
| Redemption Rate | Points redeemed per second | `rate(loyalty_points_redeemed[5m])` |
| Accrual Latency (p95) | 95th percentile accrual duration | `histogram_quantile(0.95, loyalty_accrual_duration_bucket)` |
| Tier Distribution | Customers per tier | `loyalty_tier_customers` |
| Queue Depth | Pending loyalty jobs | `loyalty_queue_depth` |
| Reservation Expiration Rate | Expired reservations per minute | `rate(loyalty_reservation_expired[1m]) * 60` |

### Dashboard Links

- [Loyalty KPIs Dashboard](https://grafana.villagecompute.com/d/component-kpis?orgId=1&var-component=loyalty)
- [Loyalty Job Monitoring](https://grafana.villagecompute.com/d/background-jobs?var-job_type=loyalty)
- [Jaeger Traces - Loyalty](https://jaeger.villagecompute.com/search?service=storefront&tags=%7B%22component%22%3A%22loyalty%22%7D)

---

## Feature Flags

### Emergency Kill Switches

| Flag | Purpose | Default |
|------|---------|---------|
| `loyalty.accrual.enabled` | Disable all points accrual | `true` |
| `loyalty.redemption.enabled` | Disable all redemptions | `true` |
| `loyalty.tier.recalculation.enabled` | Skip nightly tier jobs | `true` |
| `loyalty.tier.check.async` | Make tier checks async | `false` |
| `loyalty.job.[job_type].enabled` | Disable specific job type | `true` |

### Performance Tuning Flags

| Flag | Purpose | Default |
|------|---------|---------|
| `loyalty.accrual.batch-enabled` | Batch accrual operations | `false` |
| `loyalty.tier.cache.ttl` | Tier config cache TTL (seconds) | `3600` |
| `loyalty.redemption.max-per-transaction` | Max points per checkout | `10000` |

---

## Escalation

### When to Escalate

1. **Critical**: Ledger balance discrepancies >$1000
2. **High**: Suspected fraud patterns affecting multiple customers
3. **Medium**: Tier recalculation job failing for >24 hours
4. **Low**: Queue backlog persisting after scaling workers

### Escalation Contacts

- **On-Call Engineer**: PagerDuty rotation
- **Loyalty Product Owner**: @loyalty-team in Slack
- **Fraud Team**: fraud@villagecompute.com (for suspected fraud)
- **Finance Team**: finance@villagecompute.com (for ledger discrepancies)

---

## Related Documentation

- [Loyalty Program Architecture](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#loyalty)
- [Observability Framework](./observability.md)
- [Platform Admin Console Guide](./platform-admin.md)
- [Feature Flag Management](./feature-flags.md)
