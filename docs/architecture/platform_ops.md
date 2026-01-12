# Platform Operations Architecture

<!-- anchor: platform-ops-blueprint -->

**Status:** Authoritative
**Last Updated:** 2026-01-11
**Owner:** Platform Operations Team

## Document Purpose

This document provides operational procedures, infrastructure configurations, and monitoring strategies for managing the Village Storefront platform in production. It covers background job worker scaling, queue tuning, incident response playbooks, and observability dashboards.

**Intended Audience:** DevOps engineers managing Kubernetes infrastructure, SREs responding to production incidents, platform operations team configuring monitoring and alerting.

---

## Table of Contents

1. [Overview & Operational Philosophy](#overview--operational-philosophy)
2. [Worker Pod Management](#worker-pod-management)
3. [Queue Tuning & Capacity Planning](#queue-tuning--capacity-planning)
4. [Monitoring Dashboards](#monitoring-dashboards)
5. [Alert Response Playbooks](#alert-response-playbooks)
6. [Manual Intervention Procedures](#manual-intervention-procedures)
7. [Incident Response Workflows](#incident-response-workflows)
8. [References & Related Documents](#references--related-documents)

---

<!-- anchor: overview-operational-philosophy -->

## 1. Overview & Operational Philosophy

### Operational Principles

Village Storefront's platform operations follow these core tenets:

1. **Observable by Default:** All critical paths emit structured metrics and logs
2. **Self-Healing Infrastructure:** HPAs, health checks, and circuit breakers minimize manual intervention
3. **Graceful Degradation:** Feature flags enable selective service disablement under load
4. **Tenant Isolation:** Operational changes scoped to single tenants when possible
5. **Runbook-Driven:** All alerts link to specific remediation procedures

### Infrastructure Components

```
┌─────────────────────────────────────────────────────────────────┐
│                        k3s Cluster                               │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────┐  │
│  │  API Pods        │  │  Worker Pods     │  │  Admin SPA   │  │
│  │  (Quarkus)       │  │  (Background)    │  │  (Vue/Vite)  │  │
│  │  Replicas: 3-10  │  │  Replicas: 2-10  │  │  Static      │  │
│  └──────────────────┘  └──────────────────┘  └──────────────┘  │
│            │                     │                     │         │
│            └─────────────────────┴─────────────────────┘         │
│                              ↓                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              PostgreSQL 17 (RDS/Managed)                  │  │
│  │  - Primary + read replica                                 │  │
│  │  - Partition tables: sessions, audit_log, pos_queue       │  │
│  │  - Connection pool: max 100 per pod                       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                              ↓                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              Cloudflare R2 (Object Storage)               │  │
│  │  - Media assets, derivatives, report exports              │  │
│  │  - Tenant-scoped prefixes for isolation                   │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    Observability Stack                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ Prometheus   │  │ Grafana      │  │ Elasticsearch/Loki   │  │
│  │ (Metrics)    │  │ (Dashboards) │  │ (Logs)               │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
│            │                 │                     │             │
│            └─────────────────┴─────────────────────┘             │
│                              ↓                                   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              PagerDuty (Incident Management)              │  │
│  │  - Alert routing, escalation policies, on-call rotation  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Deployment Topology

| Component | Deployment Type | Min Replicas | Max Replicas | HPA Metric |
|-----------|----------------|--------------|--------------|------------|
| API Pods | Deployment | 3 | 10 | CPU (70%), request rate |
| Worker Pods | Deployment | 2 | 10 | Queue depth (100 jobs/pod) |
| Admin SPA | Static (CDN) | N/A | N/A | N/A |
| PostgreSQL | Managed Service | 1 (primary) | 1 + replicas | Manual scaling |
| R2 Storage | Managed Service | N/A | N/A | Auto-scaling |

---

<!-- anchor: worker-pod-management -->

## 2. Worker Pod Management

### Worker Deployment Configuration

**Base Configuration:** `k8s/base/deployment-workers.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: village-storefront-workers
  namespace: storefront
  labels:
    app: village-storefront
    component: worker
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: village-storefront
      component: worker
  template:
    metadata:
      labels:
        app: village-storefront
        component: worker
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/q/metrics"
    spec:
      containers:
      - name: worker
        image: villagecompute/storefront:latest
        imagePullPolicy: Always

        env:
        - name: WORKER_MODE
          value: "true"
        - name: QUARKUS_SCHEDULER_ENABLED
          value: "true"
        - name: WORKER_POLL_INTERVAL_MS
          value: "3000"
        - name: JAVA_OPTS
          value: "-Xmx3g -Xms2g -XX:MaxRAMPercentage=75.0"

        resources:
          requests:
            cpu: "500m"
            memory: "1Gi"
          limits:
            cpu: "2000m"
            memory: "4Gi"

        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 3
          failureThreshold: 3

        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3

      restartPolicy: Always
      terminationGracePeriodSeconds: 60
```

### Horizontal Pod Autoscaler (HPA)

**HPA Configuration:** `k8s/base/hpa-workers.yaml`

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: village-storefront-workers
  namespace: storefront
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: village-storefront-workers

  minReplicas: 2
  maxReplicas: 10

  metrics:
  # Primary metric: Queue depth
  - type: Pods
    pods:
      metric:
        name: media_processing_queue_depth
      target:
        type: AverageValue
        averageValue: "100"

  # Secondary metric: CPU utilization
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70

  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
      - type: Pods
        value: 2
        periodSeconds: 60
      selectPolicy: Max

    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Pods
        value: 1
        periodSeconds: 60
      selectPolicy: Min
```

**HPA Tuning Guide:**

| Parameter | Default | Tuning Guidance |
|-----------|---------|-----------------|
| `minReplicas` | 2 | Set to minimum viable capacity for off-peak hours |
| `maxReplicas` | 10 | Set to cluster capacity limit minus API pod headroom |
| `averageValue` (queue depth) | 100 | Lower = more responsive, higher = better resource efficiency |
| `stabilizationWindowSeconds` (up) | 60s | Shorter = faster scale-up, longer = prevent thrashing |
| `stabilizationWindowSeconds` (down) | 300s | Longer = prevent premature scale-down during spikes |

### Worker Health Checks

**Liveness Probe:** Ensures worker pod is not deadlocked or crashed
- **Endpoint:** `/q/health/live`
- **Failure Action:** Restart pod
- **Use Case:** Detect JVM crashes, thread exhaustion

**Readiness Probe:** Ensures worker can accept jobs
- **Endpoint:** `/q/health/ready`
- **Failure Action:** Remove from service mesh (no new jobs routed)
- **Use Case:** Database connectivity issues, startup delays

**Custom Health Check (Future Implementation):**

```java
@ApplicationScoped
@Liveness
public class WorkerHealthCheck implements HealthCheck {

    @Inject
    EntityManager entityManager;

    @Override
    public HealthCheckResponse call() {
        try {
            // Verify database connectivity
            entityManager.createNativeQuery("SELECT 1").getSingleResult();

            // Verify no deadlock on job queue
            // (add specific checks as needed)

            return HealthCheckResponse.up("worker");
        } catch (Exception e) {
            return HealthCheckResponse.down("worker", Map.of("error", e.getMessage()));
        }
    }
}
```

### Graceful Shutdown

Workers finish in-flight jobs before terminating:

```java
@ApplicationScoped
public class WorkerLifecycleManager {

    @Inject
    @ConfigProperty(name = "worker.shutdown.timeout", defaultValue = "60")
    int shutdownTimeoutSeconds;

    void onStop(@Observes ShutdownEvent event) {
        LOGGER.info("Shutdown signal received, draining jobs...");

        // Stop accepting new jobs
        schedulerService.pauseAll();

        // Wait for in-flight jobs to complete (up to timeout)
        boolean completed = jobExecutor.awaitTermination(
            shutdownTimeoutSeconds,
            TimeUnit.SECONDS
        );

        if (!completed) {
            LOGGER.warning("Shutdown timeout exceeded, forcing termination");
            jobExecutor.shutdownNow();
        }

        LOGGER.info("Worker shutdown complete");
    }
}
```

**Kubernetes Configuration:**
- `terminationGracePeriodSeconds: 60` allows 60s for graceful shutdown
- SIGTERM sent to pod → worker stops polling → jobs finish → SIGKILL after 60s

---

<!-- anchor: queue-tuning-capacity-planning -->

## 3. Queue Tuning & Capacity Planning

### Queue Capacity Configuration

**Application Properties:** `application.properties`

```properties
# Queue capacity limits (per priority per queue)
jobs.queue.capacity.critical=100
jobs.queue.capacity.high=500
jobs.queue.capacity.default=1000
jobs.queue.capacity.low=5000
jobs.queue.capacity.bulk=20000

# Worker polling configuration
media.processing.dispatch-interval=3s
media.processing.worker.batch-size=10
media.processing.worker.timeout=300s

payouts.batch.dispatch-interval=5s
payouts.batch.worker.batch-size=5

reports.export.dispatch-interval=10s
reports.export.worker.batch-size=3

# FFmpeg isolation
media.ffmpeg.max-concurrent=2
media.ffmpeg.cpu-limit=2000m
media.ffmpeg.memory-limit=4Gi
media.ffmpeg.timeout=300

# Retry policies (see background_jobs.md §6)
jobs.retry.critical.max-attempts=5
jobs.retry.high.max-attempts=3
jobs.retry.default.max-attempts=3
jobs.retry.low.max-attempts=3
jobs.retry.bulk.max-attempts=0
```

### Capacity Planning Formulas

**Worker Replica Calculation:**

```
target_replicas = ceil(queue_depth / jobs_per_worker_per_minute / target_latency_minutes)
```

**Example (DEFAULT queue):**
- Queue depth: 3000 jobs
- Worker throughput: 10 jobs/minute (varies by job complexity)
- Target latency: 30s (0.5 minutes)
- **Calculation:** `ceil(3000 / 10 / 0.5) = 600 replicas`

**Interpretation:** If calculated replicas exceed `maxReplicas`, the queue is overloaded:
1. Investigate spike source (media upload burst, scheduled report generation)
2. Consider increasing worker capacity temporarily
3. Review job efficiency (optimize handlers, reduce processing time)

**Queue Depth Thresholds:**

| Priority | Healthy | Warning | Critical | Overflow |
|----------|---------|---------|----------|----------|
| CRITICAL | < 50 | 50-100 | > 100 (alert) | > 100 (reject) |
| HIGH | < 200 | 200-500 | > 500 (alert) | > 500 (reject) |
| DEFAULT | < 500 | 500-1000 | > 1000 (alert) | > 1000 (reject) |
| LOW | < 2000 | 2000-5000 | > 5000 (alert) | > 5000 (reject) |
| BULK | < 10000 | 10000-20000 | > 20000 (alert) | > 20000 (reject) |

**Overflow Behavior:** When queue depth exceeds capacity, new job enqueue requests receive HTTP 503 Service Unavailable.

### Performance Benchmarks

**Job Processing Throughput (per worker pod):**

| Queue Type | Job Type | Avg Duration | Jobs/Minute | Notes |
|------------|----------|--------------|-------------|-------|
| media.processing | Image resize | 2s | 30 | Lightweight (Thumbnailator) |
| media.processing | Video transcode | 90s | 0.67 | Heavy (FFmpeg, max 2 concurrent) |
| payouts.batch | Payout calculation | 5s | 12 | Database-intensive |
| reports.export | CSV export | 8s | 7.5 | I/O-heavy |
| emails.transactional | Send email | 0.5s | 120 | External API call |

**Database Connection Pool Tuning:**

```properties
# Connection pool per worker pod
quarkus.datasource.jdbc.max-size=20
quarkus.datasource.jdbc.min-size=5
quarkus.datasource.jdbc.acquisition-timeout=10s
```

**Rule of Thumb:** `total_connections = (API_pods * 20) + (worker_pods * 20) < PostgreSQL max_connections`

---

<!-- anchor: monitoring-dashboards -->

## 4. Monitoring Dashboards

### Grafana Dashboard: Background Job Health

**Dashboard ID:** `/d/background-jobs`
**Refresh Interval:** 30s
**Data Source:** Prometheus

#### Panel 1: Queue Depth by Priority (Stacked Area Chart)

**Query:**
```promql
sum by (priority) (media_processing_queue_depth)
```

**Visualization:** Stacked area chart
**Y-Axis:** Job count
**Thresholds:**
- Green: 0-500
- Yellow: 500-1000
- Red: > 1000

#### Panel 2: Job Throughput (Success/Failed Rate)

**Query:**
```promql
# Success rate
sum(rate(media_processing_job_completed_total[5m]))

# Failure rate
sum(rate(media_processing_job_failed_total[5m]))
```

**Visualization:** Time series graph
**Legend:** Success (green), Failures (red)

#### Panel 3: Dead Letter Queue Depth

**Query:**
```promql
sum by (queue) (dead_letter_queue_depth)
```

**Visualization:** Bar gauge
**Alert Threshold:** > 10

#### Panel 4: Job Duration Percentiles

**Query:**
```promql
histogram_quantile(0.50, sum(rate(media_processing_job_duration_seconds_bucket[5m])) by (le, priority))
histogram_quantile(0.95, sum(rate(media_processing_job_duration_seconds_bucket[5m])) by (le, priority))
histogram_quantile(0.99, sum(rate(media_processing_job_duration_seconds_bucket[5m])) by (le, priority))
```

**Visualization:** Multi-line time series
**Legend:** p50, p95, p99 by priority
**Reference Lines:** SLA targets from background_jobs.md §3

#### Panel 5: Retry Attempt Distribution (Heatmap)

**Query:**
```promql
sum by (attempt) (rate(media_processing_job_retried_total[5m]))
```

**Visualization:** Heatmap
**X-Axis:** Time
**Y-Axis:** Attempt number (1-5)
**Color Scale:** Job count (darker = more retries)

#### Panel 6: Worker Pod Resource Usage

**Query:**
```promql
# CPU usage
sum(rate(container_cpu_usage_seconds_total{pod=~"village-storefront-workers.*"}[5m])) by (pod)

# Memory usage
sum(container_memory_working_set_bytes{pod=~"village-storefront-workers.*"}) by (pod)
```

**Visualization:** Time series + stat panel
**Thresholds:** CPU > 1.5 cores (75% of 2 core limit), Memory > 3Gi (75% of 4Gi limit)

#### Panel 7: FFmpeg Active Processes (Future)

**Query:**
```promql
media_processing_ffmpeg_active_processes
```

**Visualization:** Gauge
**Max Value:** 2 (per worker)
**Alert:** If metric missing, FFmpeg monitoring not implemented

### Grafana Dashboard: Media Pipeline

**Dashboard ID:** `/d/media-pipeline`
**Refresh Interval:** 30s

#### Panel 1: Upload → Ready Funnel

**Queries:**
```promql
# Uploads started
sum(increase(media_upload_negotiated_total[5m]))

# Processing started
sum(increase(media_processing_job_started_total[5m]))

# Assets ready
sum(increase(media_asset_ready_total[5m]))
```

**Visualization:** Funnel chart
**Conversion Tracking:** Upload negotiation → Job enqueue → Processing → Ready

#### Panel 2: Processing Time by Content Type

**Query:**
```promql
histogram_quantile(0.95, sum(rate(media_processing_job_duration_seconds_bucket[5m])) by (le, content_type))
```

**Visualization:** Time series
**Legend:** Image (blue), Video (orange)

#### Panel 3: Derivative Generation Success Rate

**Query:**
```promql
sum(rate(media_derivative_created_total[5m])) by (derivative_type)
/
sum(rate(media_derivative_attempted_total[5m])) by (derivative_type)
* 100
```

**Visualization:** Stat panel
**Target:** > 99% success rate per derivative type

#### Panel 4: R2 Bandwidth

**Query (requires custom metric):**
```promql
sum(rate(media_storage_upload_bytes_total[5m])) + sum(rate(media_storage_download_bytes_total[5m]))
```

**Visualization:** Gauge
**Unit:** Mbps
**Alert:** > 500 Mbps sustained (R2 egress cost consideration)

### Grafana Dashboard: Checkout & Payments

_Runbook reference: `docs/operations/runbook.md` §5 "Dashboard Navigation Guide" (item 3)._

**Dashboard ID:** `/d/checkout-payments`
**Refresh Interval:** 30s
**Data Source:** Prometheus

#### Panel 1: Checkout Conversion Funnel

**Queries:**
```promql
# Checkout initiated
sum(rate(checkout_initiated_total[5m]))

# Payment attempted
sum(rate(checkout_payment_attempted_total[5m]))

# Checkout completed
sum(rate(checkout_completed_total[5m]))
```

**Visualization:** Funnel chart
**Conversion Thresholds:**
- Initiation → Payment: > 80%
- Payment → Completion: > 95%

#### Panel 2: Payment Success Rate

**Query:**
```promql
(
  rate(payment_succeeded_total[5m]) /
  rate(payment_attempted_total[5m])
) * 100
```

**Visualization:** Stat panel with sparkline
**Target:** > 95% success rate
**Thresholds:**
- Green: > 95%
- Yellow: 90-95%
- Red: < 90%

#### Panel 3: Stripe Webhook Processing Latency

**Query:**
```promql
# P50, P95, P99 latency
histogram_quantile(0.50, sum(rate(stripe_webhook_processing_duration_seconds_bucket[5m])) by (le))
histogram_quantile(0.95, sum(rate(stripe_webhook_processing_duration_seconds_bucket[5m])) by (le))
histogram_quantile(0.99, sum(rate(stripe_webhook_processing_duration_seconds_bucket[5m])) by (le))
```

**Visualization:** Time series graph
**Legend:** p50, p95, p99
**Reference Lines:** SLA targets (p50: 150ms, p95: 600ms, p99: 1.2s)
**Alert Lines:** P1 threshold (p99: 3.6s), P2 threshold (p95: 1.2s)

#### Panel 4: Compensation Event Rate (Saga Rollbacks)

**Query:**
```promql
rate(checkout_compensation_triggered_total[5m])
```

**Visualization:** Time series with alert annotations
**Target:** < 0.01 events/sec
**Context:** Track saga compensation triggers indicating payment failures, inventory conflicts, or timeout scenarios

#### Panel 5: Payment Gateway Response Times

**Query:**
```promql
histogram_quantile(0.95, sum(rate(http_client_request_duration_seconds_bucket{host=~".*stripe.com"}[5m])) by (le))
```

**Visualization:** Time series
**Unit:** seconds
**Alert:** > 2s sustained (indicates Stripe API degradation)

### Grafana Dashboard: POS Offline Sync

_Runbook reference: `docs/operations/runbook.md` §5 "Dashboard Navigation Guide" (item 5)._

**Dashboard ID:** `/d/pos-offline-sync`
**Refresh Interval:** 60s
**Data Source:** Prometheus

#### Panel 1: POS Offline Batch Queue Depth

**Query:**
```promql
pos_offline_batch_queue_depth
```

**Visualization:** Time series with threshold bands
**Y-Axis:** Batch count
**Thresholds:**
- Green: 0-50
- Yellow: 50-100
- Red: > 100 (triggers SEV-1 alert)

#### Panel 2: Batch Processing Rate

**Query:**
```promql
# Enqueued rate
rate(pos_offline_batch_enqueued_total[5m])

# Processed rate
rate(pos_offline_batch_processed_total[5m])

# Failed rate
rate(pos_offline_batch_failed_total[5m])
```

**Visualization:** Stacked area chart
**Legend:** Enqueued, Processed (green), Failed (red)

#### Panel 3: Validation Failure Rate

**Query:**
```promql
rate(pos_offline_batch_validation_failures[1h]) * 100
```

**Visualization:** Stat panel with trend indicator
**Unit:** Percent per hour
**Target:** < 5% per hour
**Alert Threshold:** > 5% for 15 minutes

#### Panel 4: Cash Discrepancy Tracker

**Query:**
```promql
sum(pos_offline_cash_discrepancy_total)
```

**Visualization:** Counter stat panel with history
**Alert:** Any non-zero value triggers notification to store managers
**Context:** Tracks cash register reconciliation discrepancies requiring investigation

#### Panel 5: Replay Error Analysis

**Query:**
```promql
sum(rate(pos_offline_batch_replay_errors[5m])) by (error_type)
```

**Visualization:** Bar chart
**Group By:** error_type (schema_mismatch, duplicate_key, referential_integrity, etc.)
**Context:** Identifies root causes of offline batch replay failures

#### Panel 6: Batch Size Distribution

**Query:**
```promql
histogram_quantile(0.95, sum(rate(pos_offline_batch_size_bucket[5m])) by (le))
```

**Visualization:** Heatmap
**X-Axis:** Time
**Y-Axis:** Transaction count per batch
**Color Scale:** Frequency
**Context:** Identifies stores with unusually large offline batches indicating potential network issues

### Grafana Dashboard: Platform Overview (SLO)

_Runbook reference: `docs/operations/runbook.md` §5 "Dashboard Navigation Guide" (item 1)._

**Dashboard ID:** `/d/platform-overview`
**Refresh Interval:** 15s
**Data Source:** Prometheus

#### Panel 1: Platform Health Score

**Query:**
```promql
# Composite health score (0-100)
100 * (
  (1 - clamp_max(rate(http_server_requests_total{status=~"5.."}[5m]) / rate(http_server_requests_total[5m]), 1)) * 0.4 +
  (1 - clamp_max(media_processing_queue_depth / 1000, 1)) * 0.3 +
  (rate(payment_succeeded_total[5m]) / rate(payment_attempted_total[5m])) * 0.3
)
```

**Visualization:** Large stat panel with gradient background
**Thresholds:**
- Green: > 95 (healthy)
- Yellow: 85-95 (degraded)
- Red: < 85 (unhealthy)

#### Panel 2: Request Rate & Error Rate

**Queries:**
```promql
# Request rate
sum(rate(http_server_requests_total[5m]))

# Error rate (4xx + 5xx)
sum(rate(http_server_requests_total{status=~"[45].."}[5m]))
```

**Visualization:** Dual-axis time series
**Left Y-Axis:** Requests/sec
**Right Y-Axis:** Errors/sec
**Alert:** Error rate > 1% of request rate

#### Panel 3: API Latency Percentiles (All Endpoints)

**Query:**
```promql
histogram_quantile(0.95, sum(rate(http_server_request_duration_seconds_bucket{uri!~"/q/.*"}[5m])) by (le, uri))
```

**Visualization:** Time series, top 10 slowest endpoints
**Y-Axis:** Latency (seconds)
**Target:** p95 < 300ms for API endpoints

#### Panel 4: Pod Resource Utilization

**Queries:**
```promql
# CPU usage by component
sum(rate(container_cpu_usage_seconds_total{pod=~"village-storefront-.*"}[5m])) by (component)

# Memory usage by component
sum(container_memory_working_set_bytes{pod=~"village-storefront-.*"}) by (component)
```

**Visualization:** Stacked bar chart
**Group By:** component (api, workers, media-workers)
**Units:** CPU cores, Memory GiB

#### Panel 5: Database Connection Pool Health

**Query:**
```promql
# Active connections
hikaricp_connections_active

# Idle connections
hikaricp_connections_idle

# Connection wait time
hikaricp_connections_pending
```

**Visualization:** Time series
**Target:** Pending connections < 5
**Alert:** Connection pool exhaustion (active + idle > 90% max)

#### Panel 6: Background Job SLA Compliance

**Query:**
```promql
# Percentage of jobs meeting SLA by priority
100 * (
  sum(rate(media_processing_job_completed_total{duration_bucket=~"le_sla"}[5m])) by (priority) /
  sum(rate(media_processing_job_completed_total[5m])) by (priority)
)
```

**Visualization:** Gauge per priority level
**Targets:**
- CRITICAL: > 99.9%
- HIGH: > 99%
- DEFAULT: > 95%
- LOW: > 90%

---

<!-- anchor: alert-response-playbooks -->

## 5. Alert Response Playbooks

### Alert Severity Levels

| Level | Response Time | Escalation | Examples |
|-------|--------------|------------|----------|
| P1 (Critical) | < 15 minutes | Immediate page on-call | Queue overflow, DLQ accumulation, service down |
| P2 (Warning) | < 1 hour | Slack notification | High failure rate, SLA breach, resource exhaustion |
| P3 (Info) | Next business day | Email digest | Non-critical metric drift, configuration warnings |

### Playbook: CRITICAL Queue Backlog (P1)

**Alert Name:** `CriticalQueueBacklog`
**Trigger:** `media_processing_queue_depth{priority="critical"} > 100` for 2 minutes

**Response Steps:**

1. **Verify Alert Legitimacy:**
   ```bash
   # Check current queue depth
   curl -s http://prometheus:9090/api/v1/query \
     --data-urlencode 'query=media_processing_queue_depth{priority="critical"}' | jq
   ```

2. **Check Worker Health:**
   ```bash
   kubectl get pods -l component=worker -n storefront
   kubectl top pods -l component=worker -n storefront
   ```

3. **Scale Workers Immediately:**
   ```bash
   kubectl scale deployment/village-storefront-workers --replicas=10 -n storefront
   ```

4. **Investigate Spike Source:**
   ```bash
   # Check job enqueue rate
   kubectl logs -l component=worker --tail=500 -n storefront | grep "Job enqueued"

   # Check for tenant-specific spike
   kubectl logs -l app=village-storefront --tail=500 -n storefront | \
     grep "tenantId" | jq -r '.tenantId' | sort | uniq -c | sort -rn
   ```

5. **If External Service Failure (e.g., Stripe API down):**
   ```bash
   # Enable circuit breaker via feature flag
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "stripe.webhook.processing.enabled", "enabled": false}' \
     https://api.villagecompute.com/admin/feature-flags
   ```

6. **Post-Incident:**
   - Document root cause in incident report
   - Update capacity planning if structural (not transient spike)
   - Review HPA configuration for faster scale-up

### Playbook: Dead Letter Queue Accumulation (P1)

**Alert Name:** `DLQAccumulating`
**Trigger:** `rate(dead_letter_queue_added_total[5m]) > 0.1` for 5 minutes

**Response Steps:**

1. **Identify Failure Pattern:**
   ```bash
   # Group DLQ entries by error type
   kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
     "SELECT substring(last_error, 1, 100) AS error_prefix, COUNT(*) \
      FROM dead_letter_queue \
      WHERE resolved_at IS NULL \
      GROUP BY error_prefix \
      ORDER BY COUNT(*) DESC \
      LIMIT 10;"
   ```

2. **Check for Tenant-Specific Issue:**
   ```bash
   kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
     "SELECT tenant_id, COUNT(*) \
      FROM dead_letter_queue \
      WHERE resolved_at IS NULL \
      GROUP BY tenant_id \
      ORDER BY COUNT(*) DESC \
      LIMIT 5;"
   ```

3. **Categorize Root Cause:**
   - **Data Error:** Fix tenant data, replay jobs manually
   - **Code Bug:** Deploy hotfix, replay jobs after fix
   - **External Service Outage:** Wait for recovery, enable circuit breaker
   - **Configuration Issue:** Update ConfigMap, restart workers

4. **Manual Job Replay (after fix):**
   ```bash
   # Export DLQ jobs to JSON
   kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
     "COPY (SELECT * FROM dead_letter_queue WHERE resolved_at IS NULL) \
      TO STDOUT WITH CSV HEADER" > dlq_export.csv

   # Re-enqueue via admin API (future implementation)
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d @dlq_export.json \
     https://api.villagecompute.com/admin/jobs/replay-batch
   ```

### Playbook: High Job Failure Rate (P2)

**Alert Name:** `HighJobFailureRate`
**Trigger:** Failure rate > 5% for 10 minutes

**Response Steps:**

1. **Calculate Failure Rate:**
   ```promql
   rate(media_processing_job_failed_total[5m]) / rate(media_processing_job_started_total[5m]) * 100
   ```

2. **Sample Failed Job Logs:**
   ```bash
   kubectl logs -l component=worker --tail=1000 -n storefront | grep "Job failed"
   ```

3. **Check Retry Success Rate:**
   ```promql
   sum(rate(media_processing_job_completed_total{retry="true"}[5m]))
   ```

4. **If Transient Errors (network timeouts, DB connection pool exhaustion):**
   - Monitor retry backoff (jobs should eventually succeed)
   - No immediate action required if retries recovering

5. **If Persistent Errors:**
   - Correlate with recent deployments (rollback if regression)
   - Check external service status pages (Stripe, R2)
   - Investigate code path in failed job type

### Playbook: Checkout Saga Failure Patterns (P2)

**Alert Name:** `CheckoutCompensationRateHigh`
**Trigger:** `rate(checkout_compensation_triggered_total[5m]) > 0.1` for 10 minutes
**Related Diagram:** [Checkout Sequence Diagram](../diagrams/checkout_sequence.puml)

**Response Steps:**

1. **Identify Saga Stage Failure Pattern:**
   ```bash
   # Query audit log for compensation events
   kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
     "SELECT event_type, COUNT(*) \
      FROM audit_log \
      WHERE event_type LIKE 'checkout.compensation.%' \
        AND timestamp > NOW() - INTERVAL '15 minutes' \
      GROUP BY event_type \
      ORDER BY COUNT(*) DESC \
      LIMIT 5;"
   ```

2. **Check Compensation Trigger Distribution:**
   ```promql
   sum by (stage) (rate(checkout_compensation_triggered_total[5m]))
   ```

**Common Failure Scenarios & Compensation Hooks:**

#### Scenario A: Payment Decline Surge

**Indicators:**
- High `checkout.payment.declined` metric
- Compensation events: `inventory_released`, `loyalty_released`, `order.cancelled`

**Root Causes:**
- Card processing issues (bank outages, fraud detection spikes)
- Incorrect payment method configuration
- Stripe API degradation

**Response:**
```bash
# Check Stripe API status
curl https://status.stripe.com/api/v2/status.json | jq

# Review decline codes distribution
kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
  "SELECT metadata->>'decline_code' AS decline_code, COUNT(*) \
   FROM audit_log \
   WHERE event_type = 'payment.declined' \
     AND timestamp > NOW() - INTERVAL '1 hour' \
   GROUP BY decline_code \
   ORDER BY COUNT(*) DESC;"
```

**Action:**
- If specific decline_code dominates (e.g., `card_declined`): Customer-side issue, no action
- If `api_error` or timeouts: Check Stripe status, enable circuit breaker if needed
- If fraud filters too aggressive: Review Stripe Radar rules

**Compensation Verification:**
- Inventory reservations released (no ghost holds)
- Loyalty points restored to customer accounts
- Orders marked CANCELLED, not stuck in PENDING_PAYMENT

#### Scenario B: Shipping Rate Adapter Failure

**Indicators:**
- High `checkout.shipping.fallback` metric
- Compensation: None (fallback strategy activates)

**Root Causes:**
- Carrier API outages (UPS/FedEx/USPS)
- Network connectivity issues
- Rate limiting by carrier APIs

**Response:**
```bash
# Check carrier adapter error distribution
kubectl logs -l app=village-storefront --tail=500 -n storefront | \
  grep "CarrierAdapter" | grep "error" | jq -r '.carrier' | sort | uniq -c

# Verify fallback rate usage
kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
  "SELECT COUNT(*) \
   FROM audit_log \
   WHERE event_type = 'checkout.shipping.fallback_used' \
     AND timestamp > NOW() - INTERVAL '1 hour';"
```

**Action:**
- Check carrier status pages: UPS, FedEx, USPS
- If single carrier down: Expected, system aggregates rates from available carriers
- If all carriers down: Fallback table rates activate automatically (if enabled)
- Monitor orders: Actual shipping cost reconciled at fulfillment

**Fallback Rate Configuration:**
- Enable/disable fallback: Set `shipping.fallback.enabled=true` in `application.properties`
- Disable carrier rates entirely: Set `shipping.rates.enabled=false` (emergency kill switch)
- Per-carrier control: Configure availability via API credentials (empty credentials = carrier disabled)
  - USPS: `shipping.usps.user-id`
  - UPS: `shipping.ups.access-key`
  - FedEx: `shipping.fedex.api-key`
- Table rate logic: Flat rates based on package weight (see `ShippingService.getFallbackTableRates`)
  - ≤16 oz: $5.99 ground, $11.98 priority
  - >16 oz: $9.99 ground, $19.98 priority
- Cache TTL: 15 minutes (`shipping-rate-cache` expire-after-write=PT15M)

**Carrier Adapter Resilience:**
- Retry policy: 3 attempts with 500ms exponential backoff (Resilience4j)
- Timeout: 10 seconds per carrier API call (configurable via `shipping.{carrier}.timeout-ms`)
- Circuit breaker: Managed by adapter `isAvailable()` health checks
- Partial failures: If 1+ carriers succeed, their rates are returned without fallback
- Total failure: All carriers down → fallback rates (if enabled), else empty rate list

**Monitoring:**
- `shipping.adapter.requests{carrier,operation,status}` - per-carrier API call metrics
- `shipping.rate.fallback_used{reason}` - fallback activation counter
- `shipping.rate.cache.hit` / `.miss` / `.invalidated` - cache effectiveness
- `shipping.rate.fetch{fallback=true/false}` - overall rate fetch latency

**No Compensation Required:**
- Fallback rate is estimate, no money collected yet
- Customer sees warning: "Shipping calculated at fulfillment"

#### Scenario C: Inventory Reservation Deadlock

**Indicators:**
- High `checkout.inventory.reservation_timeout` metric
- Compensation: Partial reservations released

**Root Causes:**
- Database lock contention (high-demand variants)
- Slow queries blocking SELECT FOR UPDATE
- Concurrent checkouts for same variant

**Response:**
```bash
# Check for long-running transactions
kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
  "SELECT pid, usename, state, query_start, state_change, query \
   FROM pg_stat_activity \
   WHERE state != 'idle' \
     AND query LIKE '%inventory_reservations%' \
   ORDER BY query_start ASC;"

# Check lock wait times
kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
  "SELECT locktype, relation::regclass, mode, granted \
   FROM pg_locks \
   WHERE NOT granted \
     AND relation::regclass::text LIKE '%inventory%';"
```

**Action:**
- If deadlock persists: Consider increasing `lock_timeout` config
- Review inventory query optimization (add indexes on hot variants)
- Implement optimistic locking for high-contention variants

**Compensation Verification:**
- Failed reservations cleaned up (no orphaned rows)
- Customer sees clear "Out of stock" or "Try again" message

#### Scenario D: Loyalty Hold Expiration

**Indicators:**
- Audit events: `checkout.loyalty.hold_expired`
- Customer complaints about points deducted but order failed

**Root Causes:**
- Checkout took > 15 minutes (hold TTL expired)
- Background job delay in releasing expired holds
- Race condition between hold expiry and payment processing

**Response:**
```bash
# Find expired holds not yet released
kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
  "SELECT id, customer_id, points, created_at, expires_at \
   FROM loyalty_holds \
   WHERE expires_at < NOW() \
     AND status = 'active' \
   LIMIT 10;"

# Manually release expired holds (emergency)
kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
  "DELETE FROM loyalty_holds \
   WHERE expires_at < NOW() - INTERVAL '1 hour' \
     AND status = 'active';"
```

**Action:**
- Verify loyalty hold cleanup job running (scheduled every 5 minutes)
- Review checkout duration metrics (p95 should be < 5 minutes)
- Increase hold TTL if legitimate slow checkouts common

**Compensation Verification:**
- Expired holds released automatically
- Customer loyalty balance accurate (no phantom deductions)

### Playbook: Address Validation API Failure (P3)

**Alert Name:** `AddressValidationUnavailable`
**Trigger:** `rate(checkout.address_validation_failures[5m]) > 0.5` for 15 minutes

**Response Steps:**

1. **Check Address Validation Provider:**
   ```bash
   # Test USPS/Lob.com API directly
   curl -X POST https://api.lob.com/v1/us_verifications \
     -u "$LOB_API_KEY:" \
     -d "primary_line=185 Berry St" \
     -d "city=San Francisco" \
     -d "state=CA" \
     -d "zip_code=94107"
   ```

2. **If Provider Down:**
   - Enable bypass mode (accept unvalidated addresses with warning)
   - Notify merchants: "Address validation temporarily disabled"
   - Mark orders for manual review before shipment

3. **Configuration Override (Emergency):**
   ```bash
   # Disable strict address validation
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "checkout.address_validation.required", "enabled": false}' \
     https://api.villagecompute.com/admin/feature-flags
   ```

4. **Recovery:**
   - Monitor provider status page
   - Re-enable strict validation after confirmed recovery
   - Review orders placed during bypass for shipping issues

### Playbook: Worker Pod OOMKilled (P2)

**Alert Name:** `WorkerPodOOMKilled`
**Trigger:** Pod restart with reason `OOMKilled`

**Response Steps:**

1. **Verify OOM Event:**
   ```bash
   kubectl describe pod <worker-pod-name> -n storefront | grep -A 10 "Last State"
   ```

2. **Check Memory Usage Trend:**
   ```promql
   max_over_time(container_memory_working_set_bytes{pod=~"village-storefront-workers.*"}[1h])
   ```

3. **Identify Memory Leak Candidate:**
   - FFmpeg subprocess not cleaned up (check `media.ffmpeg.max-concurrent`)
   - Temp file accumulation (check `/tmp` disk usage)
   - ThreadLocal leak (TenantContext not cleared)

4. **Immediate Mitigation:**
   ```bash
   # Increase memory limits temporarily
   kubectl set resources deployment/village-storefront-workers \
     --limits=memory=6Gi -n storefront
   ```

5. **Long-Term Fix:**
   - Profile heap dump (enable JMX, dump via `jmap`)
   - Review FFmpeg cleanup logic in `MediaProcessingJobHandler`
   - Audit `TenantContext.clear()` in all worker loops

---

<!-- anchor: manual-intervention-procedures -->

## 6. Manual Intervention Procedures

### Pausing Queue Processing

**Scenario:** Maintenance window, external service outage, runaway job investigation

**Procedure:**

1. **Scale Workers to 0:**
   ```bash
   kubectl scale deployment/village-storefront-workers --replicas=0 -n storefront
   ```

2. **Verify Queue Processing Stopped:**
   ```promql
   rate(media_processing_job_started_total[1m]) == 0
   ```

3. **Perform Maintenance:**
   - Database schema migration
   - External service cutover
   - Code hotfix deployment

4. **Resume Workers:**
   ```bash
   kubectl scale deployment/village-storefront-workers --replicas=3 -n storefront
   ```

5. **Monitor Queue Drain Rate:**
   ```promql
   rate(media_processing_job_completed_total[5m])
   ```

### Clearing Dead Letter Queue

**Scenario:** After investigation and manual fixes, clear DLQ of resolved jobs

**Procedure:**

1. **Backup DLQ Contents:**
   ```bash
   kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
     "COPY (SELECT * FROM dead_letter_queue WHERE resolved_at IS NULL) \
      TO STDOUT WITH CSV HEADER" > dlq_backup_$(date +%Y%m%d_%H%M%S).csv
   ```

2. **Mark Jobs as Resolved:**
   ```bash
   kubectl exec -it postgres-pod -- psql -U storefront -d storefront -c \
     "UPDATE dead_letter_queue \
      SET resolved_at = NOW(), resolution_notes = 'Bulk resolved after incident XYZ' \
      WHERE resolved_at IS NULL AND queue_name = 'media.processing';"
   ```

3. **Verify DLQ Cleared:**
   ```promql
   dead_letter_queue_depth{queue="media.processing"} == 0
   ```

### Emergency Kill Switch Activation

**Scenario:** Critical bug in job handler causing data corruption or cascading failures

**Procedure:**

1. **Disable Job Processing via Feature Flag:**
   ```bash
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"flag": "media.processing.enabled", "enabled": false, "reason": "Emergency stop - incident #123"}' \
     https://api.villagecompute.com/admin/feature-flags
   ```

2. **Verify Jobs Skipped (Not Failed):**
   ```bash
   kubectl logs -l component=worker --tail=200 -n storefront | grep "Kill switch activated"
   ```

3. **Jobs Remain in Queue (Not Moved to DLQ):**
   - Status stays `pending`, will retry when flag re-enabled

4. **Deploy Hotfix:**
   ```bash
   kubectl set image deployment/village-storefront-workers \
     worker=villagecompute/storefront:hotfix-v1.2.3 -n storefront
   ```

5. **Re-Enable Processing:**
   ```bash
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"flag": "media.processing.enabled", "enabled": true}' \
     https://api.villagecompute.com/admin/feature-flags
   ```

### Manual Job Re-Enqueue

**Scenario:** Jobs lost due to database corruption or operator error

**Procedure:**

1. **Export Job Payload from Backup:**
   ```bash
   # Restore from database backup to staging environment
   kubectl exec -it postgres-staging -- psql -U storefront -d storefront -c \
     "SELECT id, tenant_id, job_type, payload \
      FROM media_processing_jobs \
      WHERE created_at BETWEEN '2026-01-11 10:00:00' AND '2026-01-11 11:00:00';" \
     > jobs_to_replay.csv
   ```

2. **Re-Enqueue via Admin Script (Future API):**
   ```bash
   cat jobs_to_replay.csv | while IFS=, read id tenant_id job_type payload; do
     curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
       -H "Content-Type: application/json" \
       -d "{\"tenantId\": \"$tenant_id\", \"jobType\": \"$job_type\", \"payload\": $payload}" \
       https://api.villagecompute.com/admin/jobs/enqueue
   done
   ```

3. **Verify Re-Enqueued Jobs Processed:**
   ```promql
   increase(media_processing_job_completed_total[10m])
   ```

---

<!-- anchor: incident-response-workflows -->

## 7. Incident Response Workflows

### Incident Severity Classification

| Severity | Impact | Response Time | Example |
|----------|--------|---------------|---------|
| SEV-1 | Platform-wide outage or data loss | Immediate (page) | Queue overflow blocking all uploads |
| SEV-2 | Single tenant affected or degraded performance | < 1 hour | DLQ accumulation for specific tenant |
| SEV-3 | Minor degradation or cosmetic issue | Next business day | Non-critical metric drift |

### Incident Response Checklist

**During Incident:**

1. **Create Incident Channel:**
   ```
   Slack: #incident-2026-01-11-queue-overflow
   ```

2. **Acknowledge Alert in PagerDuty:**
   - Claim ownership
   - Update status to "Acknowledged"

3. **Execute Relevant Playbook:**
   - Follow steps in Section 5 based on alert type

4. **Communicate Status:**
   ```
   # Slack channel updates
   [10:05] Incident confirmed: CRITICAL queue backlog, scaling workers to 10
   [10:08] Workers scaled, queue draining at 200 jobs/minute
   [10:15] Queue depth below threshold, monitoring for 10 minutes
   [10:25] Incident resolved, root cause: scheduled report spike
   ```

5. **Update Status Page:**
   ```
   https://status.villagecompute.com
   - Mark affected components (Background Jobs)
   - Post updates every 15 minutes
   - Resolve when confirmed stable
   ```

**Post-Incident:**

1. **Document Root Cause:**
   ```markdown
   # Incident Report: Queue Overflow 2026-01-11

   ## Summary
   CRITICAL queue backlog triggered by scheduled report generation spike.

   ## Timeline
   - 10:00 - Alert triggered
   - 10:05 - On-call engineer acknowledged
   - 10:08 - Workers scaled from 3 to 10
   - 10:25 - Queue drained, incident resolved

   ## Root Cause
   Monthly report generation for 500 tenants scheduled simultaneously at 10:00 UTC.

   ## Resolution
   - Immediate: Manual worker scaling
   - Long-term: Stagger report generation across 1-hour window

   ## Action Items
   - [ ] Update report scheduling logic (JIRA-123)
   - [ ] Increase HPA maxReplicas to 15 (JIRA-124)
   - [ ] Add alert for scheduled task spikes (JIRA-125)
   ```

2. **Update Runbooks:**
   - Add learnings to relevant playbook sections
   - Document new failure modes discovered

3. **Review Monitoring Gaps:**
   - Were there missing metrics that would have helped?
   - Should alert thresholds be adjusted?

### Escalation Paths

| Role | Responsibility | Contact |
|------|---------------|---------|
| On-Call Engineer | First responder, execute playbooks | PagerDuty rotation |
| Engineering Manager | Coordinate multi-team response | Slack @eng-manager |
| Database Admin | Database-specific issues (schema, replication) | Slack @dba-team |
| Infrastructure Lead | Kubernetes cluster issues (node failures, networking) | Slack @infra-lead |
| CTO | SEV-1 incidents affecting revenue or data loss | Escalation after 30 minutes |

---
<!-- anchor: verification-release-readiness -->

## 8. Verification & Release Readiness Evidence

### 8.1 Automation Hooks

- **Full Plan Runner:** `scripts/qa/run_e2e.sh` accepts `RUN_PERF_TESTS`, `RUN_CHAOS_TESTS`, and `GENERATE_REPORT` flags. Chaos scripts respect `--auto-approve` for CI safety and write logs under `target/chaos-drills/`.
- **Load Testing:** k6 scripts live in `tests/load/k6/` (checkout, POS, media). Results exported to `target/load-tests/*.json` and summarized in `docs/quality/performance-test-report.md`.
- **Release Artifact:** `reports/release_readiness.md` aggregates coverage, regression matrix, load benchmarks, chaos outcomes, and governance sign-offs.

### 8.2 Coverage & Performance Snapshot (I5.T7 – 2026-01-12)

| Metric | Result |
|--------|--------|
| Unit Coverage | 86.1% line / 82.0% branch (Consignment branch 79.4% risk accepted – QA-218) |
| Integration Coverage | 614/616 REST-assured scenarios passed (Shipping fallback pending OPS-641) |
| E2E Coverage | 143/143 Playwright specs green across Chromium/Firefox/WebKit + mobile |
| Load | Checkout p95 preview 248 ms, commit 412 ms; 118 checkouts/min sustained |
| POS Offline | Batch validation 1.4 s, replay p95 92 ms, offline switch 420 ms |
| Lighthouse | Weighted score 93 (LCP <= 1.92 s on all key pages) |

### 8.3 Chaos & Operational Drills

- **Database Failover:** `scripts/qa/chaos/db_failover.sh --auto-approve` validated failover (47 s) + reconnection (23 s). Remediation: enable Hikari `initialFailFast`, document cache flush.
- **Worker Crash:** `scripts/qa/chaos/worker_crash.sh --auto-approve` forced pod deletions, recovered in 71 s with no DLQ pollution. Resulting actions: HPA minReplicas 3, `startupProbe` added.
- **Payment Outage:** Automation deferred to QA-219; manual fallback described in runbook §3.3 until Stripe sandbox supports TLS reset simulation.
- **Artifacts:** Logs archived under `target/chaos-drills/` and referenced by runbook §8; measurements summarized in `reports/release_readiness.md` §3.

---

<!-- anchor: references-related-documents -->

## 9. References & Related Documents

### Architecture Documents

- **[Background Jobs Architecture](./background_jobs.md)** (authoritative async processing specification)
- **[Operational Architecture](./04_Operational_Architecture.md)** (Section 3.6: Background Processing)
- **[Tenant Isolation](./tenant_isolation.md)** (Section 3: TenantContext Management)

### Diagrams

- **[Media Pipeline Flow](../diagrams/media_pipeline.mmd)** (comprehensive job lifecycle diagram)
- **[POS Offline Sync](../diagrams/pos-offline.mmd)** (offline queue replay workflow)

### Operations Runbooks

- **[Job Operations Runbook](../operations/job_runbook.md)** (detailed operational procedures)
- **[Incident Response](../operations/incident_response.md)** (generic incident management)

### Monitoring & Alerting

- **Grafana Dashboards:**
  - Background Job Health: `https://grafana.villagecompute.com/d/background-jobs`
  - Media Pipeline: `https://grafana.villagecompute.com/d/media-pipeline`
- **Prometheus Alert Rules:** `k8s/base/prometheus-rules.yaml`
- **PagerDuty Escalation Policies:** `https://villagecompute.pagerduty.com/escalation_policies`

### Kubernetes Manifests

- **Worker Deployment:** `k8s/base/deployment-workers.yaml`
- **HPA Configuration:** `k8s/base/hpa-workers.yaml`
- **ConfigMap:** `k8s/base/configmap-storefront.yaml`
- **Service:** `k8s/base/service-workers.yaml` (metrics endpoint)

### Standards & Compliance

- **[Java Project Standards](../java-project-standards.adoc)** (Section 8: Background Job Conventions)
- **GDPR Compliance:** Tenant-scoped log aggregation for data deletion support

---

**Document Maintainers:** Platform Operations Team
**Review Cadence:** Monthly or after each SEV-1/SEV-2 incident
**Next Review:** 2026-02-11
