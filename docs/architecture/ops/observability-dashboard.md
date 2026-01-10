# Observability Dashboard Guide

**Version:** 1.0
**Last Updated:** 2026-01-10
**Owner:** Platform Ops Team
**Audience:** On-call Engineers, SREs, DevOps

---

## Overview

This guide provides practical instructions for using the observability infrastructure (Grafana dashboards, Prometheus queries, Jaeger traces) to monitor the Village Storefront platform and investigate incidents.

**Related Documentation:**
- **Implementation:** [Observability Framework](../../operations/observability.md) - How observability is implemented
- **Runbooks:** [Runbook Index](runbook-index.md) - Operational procedures for specific components
- **Hypercare:** [Hypercare Plan](hypercare-plan.md) - Enhanced monitoring during first 30 days

---

## Table of Contents

1. [Dashboard Quick Reference](#dashboard-quick-reference)
2. [Common Prometheus Queries](#common-prometheus-queries)
3. [Dashboard Walkthroughs](#dashboard-walkthroughs)
4. [Alert Investigation Workflows](#alert-investigation-workflows)
5. [Custom Queries](#custom-queries)

---

## 1. Dashboard Quick Reference

### Dashboard Access

**Grafana:** https://grafana.villagecompute.com/
**Prometheus:** https://prometheus.villagecompute.com/
**Jaeger:** https://jaeger.villagecompute.com/

**Credentials:** Use SSO (Google Workspace) or contact #platform-ops for API key.

### Primary Dashboards

| Dashboard | URL | Use Case | Key Metrics |
|-----------|-----|----------|-------------|
| **Platform Overview** | [/d/platform-overview](https://grafana.villagecompute.com/d/platform-overview) | Daily health check, overall system status | Availability, error rate, API latency |
| **API Performance** | [/d/api-performance](https://grafana.villagecompute.com/d/api-performance) | API latency spikes, endpoint analysis | Request rate, p95/p99 latency, error rate by endpoint |
| **Database Metrics** | [/d/postgresql](https://grafana.villagecompute.com/d/postgresql) | Database performance, slow queries | Connection pool, query latency, replication lag |
| **Background Jobs** | [/d/job-queue](https://grafana.villagecompute.com/d/job-queue) | Job queue backlog, processing failures | Queue depth, job duration, failure rate by job type |
| **Component KPIs** | [/d/component-kpis](https://grafana.villagecompute.com/d/component-kpis) | Component-specific SLA monitoring | Component availability, latency, error rate (filterable) |
| **Checkout Funnel** | [/d/checkout-funnel](https://grafana.villagecompute.com/d/checkout-funnel) | Conversion analysis, drop-off identification | Cart creation, checkout initiation, payment success, order completion |
| **Consignment Analytics** | [/d/consignment](https://grafana.villagecompute.com/d/consignment) | Payout volume, ledger accuracy | Payout count, total volume, ledger balance accuracy |
| **Media Pipeline** | [/d/media-pipeline](https://grafana.villagecompute.com/d/media-pipeline) | Upload/transcode performance | Upload success rate, transcode latency, R2 storage usage |
| **Tenant Activity** | [/d/tenant-activity](https://grafana.villagecompute.com/d/tenant-activity) | Per-tenant usage, multi-tenant health | Active tenants, requests per tenant, tenant error rates |

### Specialized Dashboards

| Dashboard | URL | Use Case |
|-----------|-----|----------|
| **JVM Metrics** | [/d/jvm](https://grafana.villagecompute.com/d/jvm) | Memory usage, GC pauses, thread count |
| **Kubernetes Pods** | [/d/k8s-pods](https://grafana.villagecompute.com/d/k8s-pods) | Pod restarts, resource limits, scaling events |
| **Network Traffic** | [/d/network](https://grafana.villagecompute.com/d/network) | Ingress traffic, egress to external APIs |
| **Security Events** | [/d/security](https://grafana.villagecompute.com/d/security) | Failed logins, rate limit breaches, impersonation activity |

---

## 2. Common Prometheus Queries

### Platform Health Queries

#### Overall Availability (Last 1 Hour)
```promql
# Success rate across all services
(sum(rate(http_server_requests_seconds_count{status=~"2.."}[1h])) /
 sum(rate(http_server_requests_seconds_count[1h]))) * 100
```

**Expected:** >99.5%
**Action if <99.0%:** Check [Platform Overview](https://grafana.villagecompute.com/d/platform-overview) dashboard for service breakdown

#### API Error Rate
```promql
# Error rate (4xx + 5xx) per endpoint
sum(rate(http_server_requests_seconds_count{status=~"[45].."}[5m])) by (uri, status)
```

**Expected:** <1% overall
**Action if >5%:** Investigate specific endpoint, check logs for error details

#### Database Connection Pool Usage
```promql
# Connection pool utilization
(hikaricp_connections_active / hikaricp_connections_max) * 100
```

**Expected:** <70%
**Action if >80%:** Risk of connection exhaustion, check for connection leaks or increase pool size

---

### API Performance Queries

#### API Latency (P95, P99)
```promql
# P95 latency per endpoint
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (uri, le))
```

**Expected:** <500ms for most endpoints
**Action if >1s:** Identify slow endpoint, check database query performance

#### Top 5 Slowest Endpoints
```promql
# Top 5 endpoints by P99 latency
topk(5, histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[1h])) by (uri, le)))
```

**Use Case:** Performance optimization targets

#### Request Rate by Endpoint
```promql
# Requests per second by endpoint
sum(rate(http_server_requests_seconds_count[5m])) by (uri)
```

**Use Case:** Identify high-traffic endpoints, plan capacity

---

### Background Jobs Queries

#### Job Queue Depth
```promql
# Number of pending jobs by type
sum(background_job_queue_depth) by (job_type)
```

**Expected:** <100 jobs per type
**Action if >1000:** Check job processing lag, investigate failures

#### Job Processing Duration (P95)
```promql
# P95 processing time per job type
histogram_quantile(0.95, sum(rate(background_job_duration_seconds_bucket[10m])) by (job_type, le))
```

**Expected:** <5 minutes for most jobs
**Action if >30 min:** Investigate slow job processing, check resource constraints

#### Job Failure Rate
```promql
# Failed jobs per hour
sum(increase(background_job_failures_total[1h])) by (job_type)
```

**Expected:** <5% failure rate
**Action if >10%:** Check job logs for error patterns

---

### Database Queries

#### Slow Query Count
```promql
# Queries taking >1s
sum(increase(postgres_slow_queries_total{threshold="1s"}[5m]))
```

**Expected:** <10 per hour
**Action if >100:** Run `pg_stat_statements` analysis, add indexes

#### Replication Lag
```promql
# Seconds behind primary
postgres_replication_lag_seconds
```

**Expected:** <5 seconds
**Action if >30s:** Check replica health, network latency

#### Deadlocks
```promql
# Deadlock count
increase(postgres_deadlocks_total[1h])
```

**Expected:** 0
**Action if >0:** Analyze transaction patterns, review locking strategy

---

### Checkout & Payments Queries

#### Checkout Success Rate
```promql
# Successful checkouts / total checkout attempts
(sum(rate(checkout_completed_total[10m])) /
 sum(rate(checkout_initiated_total[10m]))) * 100
```

**Expected:** >95%
**Action if <90%:** Check payment failures, inventory issues, timeouts

#### Payment Intent Failure Rate
```promql
# Stripe payment failures
sum(rate(stripe_payment_intent_failed_total[5m])) by (failure_reason)
```

**Expected:** <2%
**Action if >5%:** Check Stripe dashboard, validate card decline reasons

---

## 3. Dashboard Walkthroughs

### Platform Overview Dashboard

**Purpose:** Daily health check, overall system status

**Layout:**

```
┌─────────────────────────────────────────────────────────────────┐
│ Platform Overview                          [Last 1h] [Refresh]  │
├─────────────────────────────────────────────────────────────────┤
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐       │
│  │ Availability  │  │  Error Rate   │  │  API Latency  │       │
│  │    99.8%      │  │     0.3%      │  │   245ms (p95) │       │
│  │      ✅       │  │      ✅       │  │      ✅       │       │
│  └───────────────┘  └───────────────┘  └───────────────┘       │
├─────────────────────────────────────────────────────────────────┤
│  Request Rate (last 1h)                                         │
│  ▁▂▃▄▅▆▇█ [Graph: requests/sec over time]                      │
├─────────────────────────────────────────────────────────────────┤
│  Error Rate by Service                                          │
│  storefront:  0.1%  ▓░░░░░░░░░                                  │
│  admin-api:   0.5%  ▓▓░░░░░░░░                                  │
│  checkout:    0.2%  ▓░░░░░░░░░                                  │
├─────────────────────────────────────────────────────────────────┤
│  Top 5 Slowest Endpoints (p95 latency)                          │
│  1. POST /checkout/complete         1245ms  ⚠️                  │
│  2. GET  /catalog/search             487ms  ✅                  │
│  3. POST /consignment/payouts        423ms  ✅                  │
│  4. GET  /admin/reports/sales        398ms  ✅                  │
│  5. POST /media/upload               321ms  ✅                  │
└─────────────────────────────────────────────────────────────────┘
```

**Daily Review Checklist:**
- [ ] Availability >99.5% ✅
- [ ] Error rate <1% ✅
- [ ] API latency p95 <500ms ✅
- [ ] No sustained error spikes in graph
- [ ] No endpoints >1s latency (investigate if present)

**Investigation Triggers:**
- ⚠️ Availability <99.0% → Check service health, pod status
- ⚠️ Error rate >2% → Drill into Error Rate by Service, identify failing endpoint
- ⚠️ Endpoint >1s latency → Click endpoint link → View traces in Jaeger

---

### API Performance Dashboard

**Purpose:** Investigate API latency spikes, identify slow endpoints

**Key Panels:**

1. **Request Rate (requests/sec)** - Line graph
   - Use Case: Identify traffic spikes, correlate with latency
   - Filter: By endpoint, tenant, status code

2. **Latency Distribution (p50, p95, p99)** - Multi-line graph
   - Use Case: Understand latency spread, identify outliers
   - Expected: p95 <500ms, p99 <1s

3. **Error Rate by Endpoint** - Table
   - Use Case: Find endpoints with high error rates
   - Action: Click endpoint → View logs, traces

4. **Top 10 Endpoints by Request Count** - Bar chart
   - Use Case: Capacity planning, cache optimization targets

**Investigation Workflow:**

1. **Latency Spike Alert:**
   - Check "Latency Distribution" graph → Identify spike time
   - Filter "Request Rate" by time window → Identify correlated traffic spike
   - Check "Error Rate by Endpoint" → Any errors during spike?
   - Click slow endpoint → View Jaeger traces → Identify bottleneck

2. **High Error Rate Alert:**
   - Check "Error Rate by Endpoint" table → Sort by error %
   - Click endpoint → View recent logs (Loki/CloudWatch)
   - Filter logs by `status=~"5.."` → Identify error pattern
   - Check [Runbook Index](runbook-index.md) → Route to appropriate runbook

---

### Database Metrics Dashboard

**Purpose:** Database performance analysis, query optimization

**Key Panels:**

1. **Connection Pool Usage (%)** - Gauge + trend
   - Expected: <70%
   - Alert: >80% (connection exhaustion risk)

2. **Query Latency (p95)** - Line graph
   - Expected: <200ms
   - Alert: >500ms sustained

3. **Slow Queries (>1s)** - Counter
   - Expected: <10/hour
   - Alert: >100/hour

4. **Replication Lag** - Gauge
   - Expected: <5s
   - Alert: >30s

5. **Top 10 Slowest Queries** - Table (from pg_stat_statements)
   - Use Case: Optimization targets

**Investigation Workflow:**

1. **Connection Pool Exhaustion:**
   ```bash
   # Check active connections by state
   SELECT state, COUNT(*) FROM pg_stat_activity GROUP BY state;

   # Find long-running queries
   SELECT pid, now() - query_start AS duration, query
   FROM pg_stat_activity
   WHERE state = 'active' AND now() - query_start > interval '5 minutes';
   ```

2. **Slow Query Investigation:**
   ```bash
   # Query pg_stat_statements for slow queries
   SELECT query, mean_exec_time, calls
   FROM pg_stat_statements
   ORDER BY mean_exec_time DESC
   LIMIT 10;

   # Analyze query plan
   EXPLAIN ANALYZE <slow_query>;
   ```

---

## 4. Alert Investigation Workflows

### Workflow: "Storefront Down" Alert

**Alert:** `StorefrontAvailability` (P1)

**Steps:**

1. **Confirm Outage:**
   - Check [Platform Overview](https://grafana.villagecompute.com/d/platform-overview) → Availability panel
   - Verify: Is availability <99%?

2. **Check Pod Health:**
   ```bash
   kubectl get pods -n village-storefront
   # Look for pods in CrashLoopBackOff or Error state
   ```

3. **Check Recent Deployments:**
   - Grafana → [Deployment](https://grafana.villagecompute.com/d/k8s-pods) dashboard
   - Was there a recent rollout? Check deployment events.

4. **Check Logs:**
   ```bash
   kubectl logs -n village-storefront deployment/storefront --tail=100
   # Look for startup errors, exceptions
   ```

5. **Rollback if Recent Deployment:**
   ```bash
   kubectl rollout undo deployment/storefront -n village-storefront
   ```

6. **Escalate:**
   - If not resolved in 15 minutes → Escalate to Platform Ops lead
   - Reference: [DR Playbook § 4.1](../../operations/dr_playbook.md)

---

### Workflow: "High API Error Rate" Alert

**Alert:** `APIErrorRateHigh` (P2)

**Steps:**

1. **Identify Failing Endpoint:**
   - Grafana → [API Performance](https://grafana.villagecompute.com/d/api-performance)
   - Check "Error Rate by Endpoint" table → Sort by error %

2. **Check Error Distribution:**
   - Filter by endpoint, view error status codes (4xx vs 5xx)
   - 4xx → Likely client errors (bad requests, auth failures)
   - 5xx → Server errors (investigate backend)

3. **View Recent Logs:**
   ```bash
   # Prometheus query to get failing endpoint
   topk(5, rate(http_server_requests_seconds_count{status=~"5.."}[5m]))

   # View logs for specific endpoint
   kubectl logs -n village-storefront deployment/storefront --tail=500 | grep "POST /checkout/complete"
   ```

4. **Check Dependencies:**
   - Is Stripe API down? → Check [Stripe Status](https://status.stripe.com/)
   - Is database slow? → Check [Database Metrics](https://grafana.villagecompute.com/d/postgresql)

5. **Route to Runbook:**
   - Checkout errors → [Payments Runbook](../../operations/payments_runbook.md)
   - Catalog errors → [Catalog Runbook](../../operations/catalog_runbook.md)

---

### Workflow: "Background Jobs Backing Up" Alert

**Alert:** `JobQueueDepthHigh` (P2)

**Steps:**

1. **Identify Backlogged Job Type:**
   - Grafana → [Background Jobs](https://grafana.villagecompute.com/d/job-queue)
   - Check "Queue Depth by Job Type" → Which job type has >1000 pending?

2. **Check Job Failure Rate:**
   - View "Job Failure Rate by Type" panel
   - Are jobs failing or just slow?

3. **Check Worker Pod Status:**
   ```bash
   kubectl get pods -n village-storefront -l app=job-worker
   # Are workers running? Check CPU/memory limits
   ```

4. **Check Recent Failed Jobs:**
   ```bash
   # Query database for recent failures
   psql -h localhost -U appuser -d storefront_prod
   SELECT id, job_type, error_message, created_at
   FROM background_jobs
   WHERE status = 'failed' AND created_at > NOW() - INTERVAL '1 hour'
   ORDER BY created_at DESC
   LIMIT 10;
   ```

5. **Scale Workers (if needed):**
   ```bash
   kubectl scale deployment/job-worker --replicas=10 -n village-storefront
   ```

6. **Reference:**
   - [Job Runbook](../../operations/job_runbook.md) for specific job types

---

## 5. Custom Queries

### Ad-Hoc Investigation Queries

#### Find Tenants with High Error Rates
```promql
# Tenants with >5% error rate (last 1h)
(sum(rate(http_server_requests_seconds_count{status=~"5..", tenant_id!=""}[1h])) by (tenant_id) /
 sum(rate(http_server_requests_seconds_count{tenant_id!=""}[1h])) by (tenant_id)) > 0.05
```

**Use Case:** Identify problematic tenants, isolate tenant-specific issues

#### Media Pipeline Throughput
```promql
# Media uploads per minute
sum(rate(media_upload_completed_total[5m])) * 60
```

**Use Case:** Capacity planning, identify upload spikes

#### Checkout Funnel Drop-off Rate
```promql
# % of carts that don't complete checkout
(1 - (sum(rate(checkout_completed_total[1h])) / sum(rate(cart_created_total[1h])))) * 100
```

**Expected:** <70% (industry average cart abandonment)
**Use Case:** Conversion optimization, funnel analysis

---

### Creating Custom Dashboards

**Steps:**

1. **Grafana UI:**
   - Navigate to https://grafana.villagecompute.com/
   - Click "+" → "Dashboard" → "Add new panel"

2. **Add Prometheus Query:**
   - Select "Prometheus" as data source
   - Enter query (see examples above)
   - Adjust visualization (graph, gauge, table)

3. **Save Dashboard:**
   - Click "Save" → Enter name → Select folder ("Custom Dashboards")

4. **Share with Team:**
   - Click "Share" → Copy URL
   - Post in #platform-ops Slack channel

**Best Practices:**
- Use template variables for filtering (tenant_id, job_type, endpoint)
- Add panel descriptions explaining what metric means
- Set alert thresholds on critical panels
- Test queries in Prometheus UI first ([prometheus.villagecompute.com](https://prometheus.villagecompute.com/))

---

## Quick Reference

### Dashboard URLs (Bookmarks)

```
Platform Overview:    https://grafana.villagecompute.com/d/platform-overview
API Performance:      https://grafana.villagecompute.com/d/api-performance
Database Metrics:     https://grafana.villagecompute.com/d/postgresql
Background Jobs:      https://grafana.villagecompute.com/d/job-queue
Component KPIs:       https://grafana.villagecompute.com/d/component-kpis
```

### Common Prometheus Queries (Copy-Paste)

```promql
# API availability
(sum(rate(http_server_requests_seconds_count{status=~"2.."}[1h])) /
 sum(rate(http_server_requests_seconds_count[1h]))) * 100

# Database connection pool usage
(hikaricp_connections_active / hikaricp_connections_max) * 100

# Job queue depth
sum(background_job_queue_depth) by (job_type)

# Checkout success rate
(sum(rate(checkout_completed_total[10m])) /
 sum(rate(checkout_initiated_total[10m]))) * 100
```

### Escalation Path

1. **Check Dashboard** → Identify affected component
2. **Check Runbook Index** → Route to appropriate runbook
3. **Escalate if Needed** → See [On-Call Escalation](runbook-index.md#on-call-escalation--ownership)

---

**For questions or feedback:**
- Slack: #platform-ops
- Documentation: [Observability Framework](../../operations/observability.md)
