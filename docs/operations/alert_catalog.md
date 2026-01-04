# Village Storefront Alert Catalog

**Task:** I5.T4
**Status:** Implemented
**Last Updated:** 2026-01-03

## Overview

This catalog documents all production alerts for Village Storefront, including alert definitions, severity levels, escalation paths, and remediation runbooks. All alerts align with Prometheus rules in `monitoring/prometheus-rules/` and Grafana dashboards in `monitoring/grafana-dashboards/`.

**References:**
- Observability Framework: `docs/operations/observability.md`
- Job Runbook: `docs/operations/job_runbook.md`
- Media Pipeline: `docs/media/pipeline.md`
- Checkout Saga: `docs/adr/ADR-003-checkout-saga.md`
- Architecture: `docs/architecture/04_Operational_Architecture.md` §3.7

---

## Table of Contents

1. [Severity Levels](#severity-levels)
2. [Escalation Matrix](#escalation-matrix)
3. [Component KPI Alerts](#component-kpi-alerts)
4. [Background Job Alerts](#background-job-alerts)
5. [Infrastructure Alerts](#infrastructure-alerts)
6. [Alert Routing Configuration](#alert-routing-configuration)

---

## Severity Levels

| Severity | SLA Response Time | On-Call Notification | Description |
|----------|-------------------|----------------------|-------------|
| **Critical** | 15 minutes (P1) | PagerDuty | Service degradation affecting users; revenue impact |
| **Warning** | 2 hours (P2) | Slack only | Performance degradation; potential future outage |
| **Info** | Next business day | Slack only | Informational; no immediate action required |

### Critical Alert Criteria

An alert is classified as **Critical** if any of the following apply:
- User-facing feature unavailable (checkout, login, storefront rendering)
- Data integrity risk (payment reconciliation failures, order state inconsistencies)
- Security incident (impersonation without audit trail, authentication bypass)
- Capacity exhaustion (queue overflow, database connections depleted)

### Warning Alert Criteria

An alert is classified as **Warning** if:
- Performance degradation but within degraded SLA (e.g., latency 2x target but <5x)
- Resource saturation approaching critical threshold (>80% but <95%)
- Error rate elevated but not service-breaking (<10%)

---

## Escalation Matrix

### On-Call Rotation

| Role | Primary Contact | Backup | Slack Handle |
|------|-----------------|--------|--------------|
| **On-Call Engineer** | PagerDuty rotation | Engineering Manager | `@oncall-eng` |
| **Database Admin** | DBA team | Infrastructure Lead | `@dba-team` |
| **External Services Liaison** | Vendor support | Technical Account Manager | `@vendor-support` |
| **Security Incident Response** | Security team | VP Engineering | `@security-team` |

### Escalation Path

```
P1 (Critical) Alert Fires
  ↓
PagerDuty notifies On-Call Engineer
  ↓
[15 minutes] No acknowledgment
  ↓
Escalate to Engineering Manager
  ↓
[30 minutes] Unresolved
  ↓
Escalate to VP Engineering + Incident Commander
  ↓
Page additional teams (DBA, Security, Vendor Support) as needed
```

### Incident Communication Channels

- **Slack:** `#incidents-storefront` (auto-created by PagerDuty)
- **Status Page:** `https://status.villagecompute.com` (updated by Incident Commander)
- **Customer Notifications:** Managed via Platform console incident banner

---

## Component KPI Alerts

### Tenant Access Gateway

#### TenantResolutionLatencyHigh

**Alert Definition:**
```yaml
expr: histogram_quantile(0.50, sum(rate(tenant_resolution_duration_bucket[5m])) by (le)) > 0.010
for: 5m
severity: warning
```

**Description:** Median tenant resolution latency exceeds 10ms SLA target (Section 4 KPI).

**Impact:** Slower request routing; all requests delayed by 10ms+ at gateway layer.

**Runbook:**
1. Check Prometheus metric: `tenant_resolution_duration_bucket`
2. Identify slow resolution paths via logs: `grep "tenant resolution" | jq '.duration_ms'`
3. Common causes:
   - Database query slow (check `tenant_domains` index health)
   - High cardinality in subdomain parsing
   - Network latency to tenant config service
4. Mitigation: Clear tenant resolution cache, restart gateway pods

**Dashboard:** [Component KPIs Panel 1](https://grafana.villagecompute.com/d/component-kpis?panelId=1)

**Escalation:** On-call engineer → Platform Team lead

---

#### TenantMisclassificationRateHigh

**Alert Definition:**
```yaml
expr: rate(tenant_resolution_misclassified_total[5m]) / rate(tenant_resolution_total[5m]) > 0.001
for: 10m
severity: critical
```

**Description:** Tenant misclassification rate exceeds 0.1% threshold.

**Impact:** Requests routed to wrong tenant; potential data leakage or 404 errors.

**Runbook:**
1. Query misclassified requests: `kubectl logs -l app=gateway | grep "misclassified"`
2. Identify pattern: custom domain vs. subdomain mismatches
3. Check recent DNS changes or domain verification failures
4. Immediate action: Disable affected custom domains, fallback to subdomain routing
5. Root cause: Investigate `tenant_domains` table integrity

**Dashboard:** [Component KPIs Panel 2](https://grafana.villagecompute.com/d/component-kpis?panelId=2)

**Escalation:** **CRITICAL** → Immediate page to On-call + Platform Team lead

---

### Identity & Session Service

#### TokenIssuanceLatencyHigh

**Alert Definition:**
```yaml
expr: histogram_quantile(0.50, sum(rate(token_issuance_duration_bucket[5m])) by (le)) > 0.050
for: 5m
severity: warning
```

**Description:** Token issuance median latency exceeds 50ms target.

**Impact:** Slower login/signup flows; user experience degradation.

**Runbook:**
1. Check JWT signing key cache (should be cached, not fetched per request)
2. Verify database connection pool health: `hikaricp_connections_active`
3. Profile JWT signing overhead (RSA vs. HMAC)
4. Mitigation: Increase signing key cache TTL, optimize database queries

**Dashboard:** [Component KPIs Panel 3](https://grafana.villagecompute.com/d/component-kpis?panelId=3)

**Escalation:** On-call engineer → Identity Team lead

---

#### TokenRefreshSuccessRateLow

**Alert Definition:**
```yaml
expr: rate(token_refresh_success_total[5m]) / rate(token_refresh_total[5m]) < 0.99
for: 10m
severity: warning
```

**Description:** Token refresh success ratio below 99% SLA.

**Impact:** Users forced to re-login; session continuity broken.

**Runbook:**
1. Check for expired refresh tokens in database (cleanup job running?)
2. Verify JWT signature validation (clock skew issues?)
3. Inspect error logs: `grep "token refresh failed" | jq '.error_code'`
4. Common errors: `invalid_token`, `token_expired`, `signature_mismatch`
5. Mitigation: Adjust refresh token TTL, fix clock sync on pods

**Dashboard:** [Component KPIs Panel 4](https://grafana.villagecompute.com/d/component-kpis?panelId=4)

**Escalation:** On-call engineer → Identity Team lead

---

### Storefront Rendering

#### StorefrontColdRenderSlow

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(storefront_render_duration_bucket{cold="true"}[5m])) by (le)) > 0.150
for: 10m
severity: warning
```

**Description:** Cold render p95 exceeds 150ms target (Section 4 KPI).

**Impact:** SEO performance degradation; slower initial page loads.

**Runbook:**
1. Check Qute template cache hit rate
2. Verify database query performance for product listings
3. Profile template rendering: `storefront_render_duration_bucket` breakdown
4. Mitigation: Pre-warm cache for popular tenants, optimize N+1 queries

**Dashboard:** [Component KPIs Panel 5](https://grafana.villagecompute.com/d/component-kpis?panelId=5)

**Escalation:** On-call engineer → Storefront Team lead

---

### Admin SPA Delivery

#### AdminBundleSizeTooLarge

**Alert Definition:**
```yaml
expr: avg(admin_spa_bundle_size_bytes{type="initial"}) > 2097152
for: 15m
severity: warning
```

**Description:** Admin SPA initial bundle exceeds 2 MB gzipped ceiling (Section 4 KPI).

**Impact:** Slower cold starts for platform admins; offline shell cache invalidations take longer.

**Runbook:**
1. Confirm latest deployment artifact size: `npm run build -- --stats-json` in `admin-spa/`
2. Inspect webpack bundle analyzer for large dependencies (charts, date libraries) and lazy-load offenders
3. Verify CDN compression headers; ensure Brotli is enabled for `.js`
4. Mitigation: split rarely used routes (`/analytics`, `/governance`) into async chunks, purge CDN cache

**Dashboard:** [Component KPIs Panel 19](https://grafana.villagecompute.com/d/component-kpis?panelId=19)

**Escalation:** On-call engineer → Admin SPA lead

---

#### AdminNavigationLatencyHigh

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(admin_spa_navigation_latency_bucket[5m])) by (le)) > 0.200
for: 5m
severity: warning
```

**Description:** Admin SPA navigation p95 exceeds 200 ms target despite cached API usage.

**Impact:** Delayed route transitions in console; degraded support workflows.

**Runbook:**
1. Break down latency by route label to isolate slow sections (catalog, governance, etc.)
2. Check API cache hit rate (`admin_spa_api_cache_hit_ratio`) and backing API latency via Jaeger
3. Verify Service Worker has current shell version (`/sw.js` served with new hash)
4. Mitigation: prefetch data for popular routes, roll back regressions, or temporarily disable heavy experiments via feature flag

**Dashboard:** [Component KPIs Panel 20](https://grafana.villagecompute.com/d/component-kpis?panelId=20)

**Escalation:** On-call engineer → Platform Console team lead

---

### Catalog & Inventory Module

#### CatalogBulkImportThroughputLow

**Alert Definition:**
```yaml
expr: rate(catalog_bulk_import_products_total[5m]) * 60 < 5000
for: 10m
severity: warning
```

**Description:** Bulk import throughput dropped below 5k products/minute goal.

**Impact:** Merchant onboarding/import scripts stalled; inventory not available for sale.

**Runbook:**
1. Inspect import job metrics grouped by tenant to find noisy neighbor
2. Check worker pod CPU/memory (`kubectl top pod -l app=catalog-workers`)
3. Review PostgreSQL locks on `catalog_product` / `catalog_variant` tables
4. Mitigation: throttle offending tenant, increase worker replicas, or split import files

**Dashboard:** [Component KPIs Panel 6](https://grafana.villagecompute.com/d/component-kpis?panelId=6)

**Escalation:** On-call engineer → Catalog/Inventory team lead

---

#### VariantUpsertLatencyHigh

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(variant_upsert_duration_bucket[5m])) by (le)) > 0.200
for: 5m
severity: warning
```

**Description:** Variant upsert p95 latency exceeds 200 ms; batching is failing.

**Impact:** Editors experience sluggish variant updates; API timeouts risk stale stock.

**Runbook:**
1. Inspect `variant_upsert_duration_bucket` by `batch_size` label to verify batching
2. Check database indexes (`\d inventory_variant`) and slow query log for row-by-row writes
3. Ensure Kafka → worker pipeline healthy; replays may serialize operations
4. Mitigation: temporarily reduce feature flag `variant-upsert.concurrent-writes`, run ANALYZE on inventory tables

**Dashboard:** [Component KPIs Panel 6](https://grafana.villagecompute.com/d/component-kpis?panelId=6)

**Escalation:** On-call engineer → Catalog/Inventory team lead

---

### Consignment Module

#### ConsignmentIntakeLatencyHigh

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(consignment_intake_duration_bucket[5m])) by (le)) > 0.500
for: 10m
severity: warning
```

**Description:** Intake batch p95 exceeds 500 ms target (100 items) from Section 4.

**Impact:** Consignor onboarding forms lag; intake associates cannot process drop-offs quickly.

**Runbook:**
1. Break down metrics by `consignor_id` to isolate tenants with huge payloads
2. Inspect `ConsignmentService` logs for validation or commission calculation spikes
3. Verify DB indexes on `consignment_item(consignor_id,status)` are healthy (ANALYZE)
4. Mitigation: enable batching flag `consignment.intake.batch-size`, offload expensive enrichments to async job

**Dashboard:** [Component KPIs Panel 21](https://grafana.villagecompute.com/d/component-kpis?panelId=21)

**Escalation:** On-call engineer → Consignment team lead

**Reference:** `docs/adr/ADR-004-consignment-payouts.md`

---

#### ConsignmentPayoutClosureSlow

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(consignment_payout_close_duration_bucket[5m])) by (le)) > 300
for: 15m
severity: warning
```

**Description:** Consignment payout batch closing exceeds 5-minute SLA.

**Impact:** Payouts delayed; finance cannot settle consignor balances on time.

**Runbook:**
1. Review payout batches stuck in `closing` status via `ConsignmentPayoutRepository`
2. Inspect Stripe Connect transfer logs for failures or rate limiting
3. Run reporting projection refresh (`./mvnw quarkus:dev -Dprojection=consignment`) to rebuild aggregates
4. Mitigation: Retry failed batches, coordinate with finance, and document incident for compliance (ADR-004)

**Dashboard:** [Component KPIs Panel 22](https://grafana.villagecompute.com/d/component-kpis?panelId=22)

**Escalation:** On-call engineer → Consignment team lead → Finance liaison

---

### Checkout & Order Orchestrator

#### CheckoutLatencyWarning

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(checkout_saga_duration_bucket{stage="complete"}[5m])) by (le)) > 0.300
for: 5m
severity: warning
```

**Description:** Checkout p95 >300ms triggers warnings per Architecture §3.7.

**Impact:** Slower checkout experience; potential cart abandonment.

**Runbook:**
1. Identify slow saga stage: `checkout_saga_duration_bucket{stage=~"address_validation|shipping_rate|payment"}`
2. Check external API latency:
   - Shipping carriers: `ShippingRateAdapter.quote` span duration
   - Stripe: `StripeIntentService.commit` span duration
   - Address validation: `AddressValidationAdapter.validate` span duration
3. Check cache hit ratio: `checkout_rate_cache_hit_ratio` (target: >70%)
4. Mitigation: Increase cache TTL, enable fallback rates, contact vendor support

**Dashboard:** [Component KPIs Panel 7](https://grafana.villagecompute.com/d/component-kpis?panelId=7)

**Escalation:** On-call engineer → Checkout Team lead

**Reference:** `docs/adr/ADR-003-checkout-saga.md`

---

#### CheckoutLatencyCritical

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(checkout_saga_duration_bucket{stage="complete"}[5m])) by (le)) > 0.800
for: 2m
severity: critical
```

**Description:** Checkout p95 exceeds 800ms SLA target (Section 4 KPI).

**Impact:** Severe checkout degradation; revenue impact.

**Runbook:**
1. **Immediate action:** Check if external services (Stripe, shipping carriers) are down
2. Enable fallback mode:
   - Use cached shipping rates
   - Defer address validation to post-checkout
3. Investigate stage-specific failures: `checkout_saga_failed_total{stage,error_type}`
4. Correlation ID search: Find failing requests and trace through Jaeger
5. Escalate to vendor support if external API issue

**Dashboard:** [Component KPIs Panel 7](https://grafana.villagecompute.com/d/component-kpis?panelId=7)

**Escalation:** **CRITICAL** → Immediate page to On-call + Checkout Team lead + Vendor Support

---

#### CheckoutFailureRateHigh

**Alert Definition:**
```yaml
expr: sum(rate(checkout_saga_failed_total[5m])) / sum(rate(checkout_saga_total[5m])) > 0.05
for: 10m
severity: warning
```

**Description:** Checkout failure rate exceeds 5%.

**Impact:** Users unable to complete purchases; revenue loss.

**Runbook:**
1. Break down failures by stage and error type: `checkout_saga_failed_total{stage,error_type}`
2. Common error types:
   - `payment_declined`: Stripe decline codes (check fraud rules)
   - `out_of_stock`: Inventory reservation failures
   - `shipping_error`: Carrier API failures
   - `address_invalid`: Address validation rejections
3. Tenant-specific analysis: Filter by `tenant_id` to identify if issue is global or tenant-scoped
4. Mitigation: Disable problematic integrations, enable fallback behavior

**Dashboard:** [Component KPIs Panel 8](https://grafana.villagecompute.com/d/component-kpis?panelId=8)

**Escalation:** On-call engineer → Checkout Team lead → Payment/Shipping Teams

---

### Media Pipeline

#### MediaQueueDepthHigh

**Alert Definition:**
```yaml
expr: media_queue_depth{priority=~"default|low"} > 100
for: 5m
severity: warning
```

**Description:** Media queue depth >100 for 5 minutes triggers scaling (Architecture §3.7).

**Impact:** Delayed image/video processing; users see "processing" status longer.

**Runbook:**
1. Check worker pod count: `kubectl get pods -l component=media-workers`
2. Verify HPA scaling: `kubectl get hpa media-workers`
3. If HPA not scaling: Manually scale `kubectl scale deployment/media-workers --replicas=10`
4. Check for stuck jobs: Query DLQ for media processing failures
5. Investigate FFmpeg/Thumbnailator errors in logs

**Dashboard:** [Component KPIs Panel 10](https://grafana.villagecompute.com/d/component-kpis?panelId=10)

**Escalation:** On-call engineer → Media Team lead

**Reference:** `docs/media/pipeline.md`

---

#### MediaSuccessRateLow

**Alert Definition:**
```yaml
expr: rate(media_job_success_total[5m]) / rate(media_job_total[5m]) < 0.995
for: 15m
severity: warning
```

**Description:** Media job success rate below 99.5% target (docs/media/pipeline.md).

**Impact:** Failed image/video uploads; user frustration.

**Runbook:**
1. Query failure breakdown: `media_job_failed_total{type="image|video"}`
2. Common failure types:
   - FFmpeg process crash (corrupt video files)
   - R2 upload timeout (network issues)
   - Quota exceeded during processing
   - Invalid media file format
3. Check DLQ for manual retry opportunities
4. Mitigation: Increase retry limits, notify users of unsupported formats

**Dashboard:** [Component KPIs Panel 9](https://grafana.villagecompute.com/d/component-kpis?panelId=9)

**Escalation:** On-call engineer → Media Team lead

---

### Payment Layer

#### StripeWebhookIdempotencyViolation

**Alert Definition:**
```yaml
expr: rate(stripe_webhook_duplicate_total[5m]) / rate(stripe_webhook_received_total[5m]) > 0.5
for: 10m
severity: warning
```

**Description:** High duplicate webhook rate indicates idempotency issue.

**Impact:** Potential duplicate payment processing; incorrect order states.

**Runbook:**
1. Check webhook event storage: `webhook_events` table for duplicate `event_id`
2. Verify idempotency logic: Should return 200 OK without re-processing
3. Investigate if database writes are failing (transaction rollback issues)
4. Mitigation: Fix idempotency store logic, re-process failed webhooks

**Dashboard:** [Component KPIs Panel 13](https://grafana.villagecompute.com/d/component-kpis?panelId=13)

**Escalation:** On-call engineer → Payment Team lead → Stripe support

---

#### PayoutReconciliationDiscrepancy

**Alert Definition:**
```yaml
expr: payout_reconciliation_unresolved_total > 0
for: 1h
severity: critical
```

**Description:** Unresolved payout discrepancies detected after daily reconciliation cycle.

**Impact:** Financial reporting inaccuracy; potential audit findings.

**Runbook:**
1. Query unresolved discrepancies: `SELECT * FROM payout_reconciliation WHERE status='unresolved'`
2. Common causes:
   - Stripe payout webhook lost
   - Manual payout outside system
   - Currency conversion rounding errors
3. **Action:** Contact finance team immediately for manual review
4. Document discrepancy in audit log before resolving

**Dashboard:** N/A (Finance dashboard)

**Escalation:** **CRITICAL** → Immediate page to On-call + Finance Team + Payment Team lead

---

### Loyalty & Rewards

#### LoyaltyPointsAccrualOverhead

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(loyalty_points_accrual_duration_bucket[5m])) by (le)) > 0.020
for: 10m
severity: warning
```

**Description:** Points accrual p95 overhead exceeds 20ms target (Section 4 KPI).

**Impact:** Slower checkout; transaction overhead.

**Runbook:**
1. Profile accrual logic: `loyalty_points_accrual_duration_bucket` breakdown
2. Check for N+1 query issues (fetching tier configuration per request)
3. Verify loyalty program cache hit rate
4. Mitigation: Cache tier rules, batch point writes

**Dashboard:** [Component KPIs Panel 14](https://grafana.villagecompute.com/d/component-kpis?panelId=14)

**Escalation:** On-call engineer → Loyalty Team lead

---

### POS & Offline Processor

#### POSOfflineFlushSlow

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(pos_offline_flush_duration_bucket[5m])) by (le)) > 60
for: 5m
severity: warning
```

**Description:** Offline queue flush p95 exceeds 60-second target (Section 4 KPI).

**Impact:** Delayed transaction sync; inventory reconciliation lag.

**Runbook:**
1. Check batch size: Are offline queues flushing large batches (>1000 transactions)?
2. Verify network latency between POS devices and server
3. Investigate transaction validation failures (failed flushes)
4. Mitigation: Reduce batch size, prioritize CRITICAL transactions

**Dashboard:** [Component KPIs Panel 15](https://grafana.villagecompute.com/d/component-kpis?panelId=15)

**Escalation:** On-call engineer → POS Team lead

---

### Reporting Service

#### ReportGenerationThroughputLow

**Alert Definition:**
```yaml
expr: rate(reporting_rows_generated_total[5m]) * 60 < 50000
for: 10m
severity: warning
```

**Description:** Report generation throughput below 50k rows/min target (Section 4 KPI).

**Impact:** Slow report generation; admin dashboard delays.

**Runbook:**
1. Check database query performance: `reporting_rows_generated_total` by report type
2. Verify reporting job worker pool size
3. Identify slow reports: Profile database queries with `EXPLAIN ANALYZE`
4. Mitigation: Add indexes, cache aggregations, scale worker pool

**Dashboard:** [Component KPIs Panel 16](https://grafana.villagecompute.com/d/component-kpis?panelId=16)

**Escalation:** On-call engineer → Reporting Team lead → DBA

---

### Platform Admin Backend

#### PlatformAdminStoreListSlow

**Alert Definition:**
```yaml
expr: histogram_quantile(0.95, sum(rate(platform_admin_store_list_duration_bucket[5m])) by (le)) > 0.300
for: 10m
severity: warning
```

**Description:** Global store list p95 exceeds 300 ms despite pagination guarantees.

**Impact:** Platform console views stall; ops cannot triage tenants quickly.

**Runbook:**
1. Filter metrics by `tenant_scope` label to see if issue isolated to large tenants
2. Inspect PostgreSQL query plan for `/admin/platform/stores` endpoint (`EXPLAIN ANALYZE`)
3. Verify read replicas healthy; check connection pool usage on platform backend pods
4. Mitigation: add covering indexes for filters (plan, status), temporarily shrink page size via feature flag

**Dashboard:** [Component KPIs Panel 17](https://grafana.villagecompute.com/d/component-kpis?panelId=17)

**Escalation:** On-call engineer → Platform ops lead

---

### Integration Adapter Layer

#### IntegrationAdapterFailureRateHigh

**Alert Definition:**
```yaml
expr: sum(rate(integration_adapter_failed_total[5m])) by (adapter, failure_type) > 0.05
for: 10m
severity: warning
```

**Description:** External adapter failures exceed 0.05 req/s threshold.

**Impact:** Third-party sync jobs fail (shipping, tax, ERP); tenant automations degrade.

**Runbook:**
1. Review failure types (`dependency_outage`, `local_misconfig`) to determine blast radius
2. Check downstream provider status pages; confirm credentials not expired
3. Toggle adapter-specific feature flags to graceful-degrade if vendor outage confirmed
4. Mitigation: back off retries (max 3) per Section 4, engage vendor support if outage persists

**Dashboard:** [Component KPIs Panel 18](https://grafana.villagecompute.com/d/component-kpis?panelId=18)

**Escalation:** On-call engineer → Integration team lead → Vendor liaison

---

## Background Job Alerts

### Critical Job Alerts

#### CriticalQueueDepthHigh

**Alert Definition:**
```yaml
expr: reporting_refresh_queue_depth{priority="critical"} > 100
for: 2m
severity: critical
```

**Description:** CRITICAL queue depth >100 for 2 minutes → P1 alert (job_runbook.md).

**Impact:** Payment webhooks, order confirmations delayed; revenue impact.

**Runbook:** See `docs/operations/job_runbook.md#scenario-1-critical-queue-backing-up`

**Dashboard:** [Background Jobs Panel 1](https://grafana.villagecompute.com/d/background-jobs?panelId=1)

**Escalation:** **CRITICAL** → Immediate page to On-call

---

#### DeadLetterQueueDepthHigh

**Alert Definition:**
```yaml
expr: reporting_dlq_depth > 10
for: 5m
severity: warning
```

**Description:** Dead-letter queue depth >10 requires investigation (job_runbook.md).

**Impact:** Failed jobs accumulating; manual intervention needed.

**Runbook:** See `docs/operations/job_runbook.md#scenario-2-dead-letter-queue-accumulating`

**Dashboard:** [Background Jobs Panel 3](https://grafana.villagecompute.com/d/background-jobs?panelId=3)

**Escalation:** On-call engineer → Backend Team lead

---

#### QueueOverflowEvents

**Alert Definition:**
```yaml
expr: increase(reporting_queue_overflow_total[5m]) > 0
for: 1m
severity: critical
```

**Description:** Queue overflow events detected → P1 alert (capacity exceeded).

**Impact:** Jobs rejected; user-facing feature degradation.

**Runbook:** See `docs/operations/job_runbook.md#scenario-3-queue-overflow-events`

**Dashboard:** [Background Jobs Panel 6](https://grafana.villagecompute.com/d/background-jobs?panelId=6)

**Escalation:** **CRITICAL** → Immediate page to On-call + Infrastructure Lead

---

#### JobsNotProcessing

**Alert Definition:**
```yaml
expr: rate(reporting_job_started_total[5m]) == 0 and reporting_refresh_queue_depth > 0
for: 5m
severity: critical
```

**Description:** Jobs stuck in queue (not processing) → P1 alert.

**Impact:** All background processing halted; cascading failures.

**Runbook:** See `docs/operations/job_runbook.md#scenario-4-jobs-stuck-in-queue`

**Action:** Restart worker pods immediately: `kubectl rollout restart deployment/village-storefront-workers`

**Dashboard:** [Background Jobs Panel 8](https://grafana.villagecompute.com/d/background-jobs?panelId=8)

**Escalation:** **CRITICAL** → Immediate page to On-call + Backend Team lead

---

## Infrastructure Alerts

### WorkerPodRestartRate

**Alert Definition:**
```yaml
expr: rate(kube_pod_container_status_restarts_total{namespace="village-storefront",container="worker"}[15m]) > 0.1
for: 5m
severity: warning
```

**Description:** Worker pod restart rate is high.

**Impact:** Job processing interruptions; potential memory leak or crash loop.

**Runbook:**
1. Check pod logs: `kubectl logs -l component=workers --tail=200`
2. Investigate OOM kills: `kubectl describe pod <pod> | grep OOMKilled`
3. Check resource limits: `kubectl get pod <pod> -o yaml | grep -A 5 resources`
4. Mitigation: Increase memory limits, fix memory leak

**Escalation:** On-call engineer → Infrastructure Lead

---

### DatabaseConnectionPoolExhausted

**Alert Definition:**
```yaml
expr: (hikaricp_connections_active / hikaricp_connections_max) > 0.9
for: 5m
severity: warning
```

**Description:** Database connection pool near capacity (>90%).

**Impact:** Database query failures; transaction timeouts.

**Runbook:**
1. Check for connection leaks: `hikaricp_connections_pending` metric
2. Verify connection pool configuration: `quarkus.datasource.jdbc.max-size`
3. Investigate long-running transactions: `SELECT * FROM pg_stat_activity WHERE state = 'active'`
4. Mitigation: Increase pool size, fix connection leaks

**Escalation:** On-call engineer → DBA team

---

## Alert Routing Configuration

### AlertManager Configuration

```yaml
# alertmanager.yml
route:
  receiver: 'default-slack'
  group_by: ['alertname', 'component']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
  # Critical alerts go to PagerDuty
  - match:
      severity: critical
    receiver: 'pagerduty-oncall'
    continue: true

  # Component-specific routing
  - match:
      component: checkout
    receiver: 'slack-team-payments'

  - match:
      component: media
    receiver: 'slack-team-media'

  - match:
      component: background-jobs
    receiver: 'slack-team-backend'

receivers:
- name: 'default-slack'
  slack_configs:
  - api_url: 'https://hooks.slack.com/services/...'
    channel: '#alerts-storefront'
    title: '{{ .GroupLabels.alertname }}'
    text: '{{ range .Alerts }}{{ .Annotations.summary }}{{ end }}'

- name: 'pagerduty-oncall'
  pagerduty_configs:
  - service_key: '<pagerduty-integration-key>'
    severity: '{{ .GroupLabels.severity }}'

- name: 'slack-team-payments'
  slack_configs:
  - api_url: 'https://hooks.slack.com/services/...'
    channel: '#team-payments'

inhibit_rules:
# WorkerPodRestartRate inhibits CriticalQueueDepthHigh
- source_match:
    alertname: 'WorkerPodRestartRate'
  target_match:
    alertname: 'CriticalQueueDepthHigh'
  equal: ['namespace']
```

---

## Alert Testing

### Simulating Alerts

```bash
# Inject test metric to trigger CriticalQueueDepthHigh
curl -X POST http://prometheus-pushgateway:9091/metrics/job/test \
  -d 'reporting_refresh_queue_depth{priority="critical"} 150'

# Verify alert fires
curl http://alertmanager:9093/api/v2/alerts | \
  jq '.[] | select(.labels.alertname == "CriticalQueueDepthHigh")'

# Expected output:
# {
#   "labels": {
#     "alertname": "CriticalQueueDepthHigh",
#     "severity": "critical",
#     "component": "background-jobs"
#   },
#   "state": "firing",
#   "activeAt": "2026-01-03T12:00:00Z"
# }
```

---

## Document Maintenance

### Update Triggers

This catalog should be updated when:
1. New alerts added to `monitoring/prometheus-rules/`
2. Severity levels or escalation paths change
3. Runbook links updated
4. Team ownership changes

### Validation Checklist

- [ ] All alerts listed have corresponding Prometheus rules
- [ ] All alerts have `runbook_url` and `action` annotations
- [ ] Escalation paths tested via PagerDuty drills
- [ ] AlertManager routing configuration matches catalog
- [ ] Dashboard links verified

---

**Document Version:** 1.0
**Author:** AI Code Implementation Agent
**Review Status:** Pending technical review
**Next Review:** Q2 2026
