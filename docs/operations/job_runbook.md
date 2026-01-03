# Background Job Operations Runbook

## Overview

This runbook provides operational procedures for managing the Village Storefront background job framework, including queue monitoring, failure investigation, dead-letter queue management, and scaling guidance.

**References:**
- Architecture: `docs/architecture/04_Operational_Architecture.md` (Section 3.6, 3.7)
- Task: I3.T6 - Background Job Framework Enhancements
- Code: `src/main/java/villagecompute/storefront/services/jobs/config/`

---

## Architecture Summary

### Job Priorities

Jobs are processed in priority order with distinct SLA targets:

| Priority  | Order | Target Latency | Use Cases                                  |
|-----------|-------|----------------|---------------------------------------------|
| CRITICAL  | 0     | < 1s           | Payment webhooks, order confirmations       |
| HIGH      | 1     | < 5s           | Notifications, inventory updates            |
| DEFAULT   | 2     | < 30s          | Report generation, email processing         |
| LOW       | 3     | < 2m           | Analytics aggregation, cache warming        |
| BULK      | 4     | Best-effort    | Archive operations, data migrations         |

### Retry Policies

| Priority  | Max Attempts | Initial Delay | Max Delay | Backoff Multiplier |
|-----------|--------------|---------------|-----------|---------------------|
| CRITICAL  | 5            | 500ms         | 30s       | 1.5x                |
| HIGH      | 3            | 1s            | 5m        | 2.0x                |
| DEFAULT   | 3            | 1s            | 5m        | 2.0x                |
| LOW       | 3            | 1s            | 5m        | 2.0x                |
| BULK      | 0            | -             | -         | -                   |

**Note:** BULK jobs fail immediately without retry and move to the dead-letter queue for manual inspection.

---

## Monitoring & Alerting

### Key Prometheus Metrics

All job queues expose the following metrics at `/q/metrics`:

#### Queue Depth (Gauge)
```promql
<queue_name>.queue.depth{priority="<priority>"}
```
- **Alerts:**
  - CRITICAL queue depth > 100 for 2 minutes → P1 alert
  - DEFAULT queue depth > 1000 for 5 minutes → P2 alert
  - Any queue depth > 10,000 → P1 alert (backpressure issue)

#### Job Lifecycle Counters
```promql
<queue_name>.job.enqueued{priority="<priority>"}
<queue_name>.job.started{priority="<priority>"}
<queue_name>.job.completed{priority="<priority>"}
<queue_name>.job.failed{priority="<priority>", attempt="<attempt>"}
<queue_name>.job.retried{priority="<priority>", attempt="<attempt>"}
<queue_name>.job.exhausted{priority="<priority>"}
```

#### Dead-Letter Queue
```promql
<queue_name>.dlq.depth
<queue_name>.dlq.added{priority="<priority>"}
<queue_name>.dlq.removed
```
- **Alert:** DLQ depth > 10 → P2 alert (requires investigation)

#### Job Timing (Histograms)
```promql
<queue_name>.job.duration{priority="<priority>", status="success|failed"}
<queue_name>.job.wait_time{priority="<priority>"}
```
- **Alerts:**
  - p95 wait time exceeds target latency by 2x → P2 alert
  - p99 duration > 60s for DEFAULT jobs → P2 alert

#### Queue Overflow
```promql
<queue_name>.queue.overflow{priority="<priority>"}
```
- **Alert:** Any overflow events → P1 alert (queue capacity exceeded)

### Example Queries

**Calculate failure rate:**
```promql
rate(<queue_name>.job.failed[5m]) / rate(<queue_name>.job.started[5m])
```

**Average retry attempts before success:**
```promql
sum(rate(<queue_name>.job.retry_success[5m])) by (attempt)
```

**Job backlog age (requires custom metric):**
```promql
<queue_name>.job.age{priority="<priority>"}
```

### Grafana Dashboards

**Dashboard: Background Job Health**
- Panel 1: Queue depth by priority (stacked area chart)
- Panel 2: Job throughput by status (success/failed rate)
- Panel 3: DLQ depth over time
- Panel 4: Job duration p50/p95/p99 by priority
- Panel 5: Retry attempt distribution (heatmap)
- Panel 6: Queue overflow events (annotations)

**Link:** `https://grafana.villagecompute.com/d/background-jobs`

---

## Common Operations

### 1. Investigating Job Failures

#### Step 1: Identify Failed Jobs

**Query Prometheus for failure spike:**
```promql
topk(5, rate(<queue_name>.job.failed[5m]) by (priority))
```

**Check application logs for error context:**
```bash
kubectl logs -l app=village-storefront --tail=100 | grep "Job failed"
```

**Log format includes:**
- `executionId`: Unique job execution ID
- `priority`: Job priority level
- `attempt`: Current retry attempt number
- `tenantId`: Tenant context (if applicable)
- Stack trace with error message

#### Step 2: Inspect Dead-Letter Queue

Jobs that exceed retry limits are moved to the DLQ. Inspect via:

**Prometheus metric:**
```promql
<queue_name>.dlq.depth
```

**Application API (future enhancement):**
```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://api.villagecompute.com/admin/jobs/dlq?queue=reporting.refresh
```

**Log inspection:**
```bash
kubectl logs -l app=village-storefront | grep "Job moved to DLQ"
```

#### Step 3: Determine Root Cause

Common failure patterns:

1. **Transient errors** (network timeout, DB connection pool exhaustion)
   - Check `attempt` count in logs - should see retries
   - Verify metrics show retry success after backoff
   - **Action:** None required if retries succeed

2. **Data errors** (invalid payload, constraint violations)
   - Check error message for validation failures
   - Investigate tenant-specific data corruption
   - **Action:** Fix data issue, replay job manually

3. **External service failures** (Stripe API down, R2 unreachable)
   - Correlate with external service status pages
   - Check for widespread failures across multiple jobs
   - **Action:** Wait for service recovery, jobs will retry

4. **Code bugs** (null pointer, logic errors)
   - Check recent deployments via correlation IDs
   - Review feature flag states for affected cohorts
   - **Action:** Hotfix deployment or feature flag rollback

### 2. Manual Job Reprocessing

#### Replaying Jobs from DLQ

**Step 1: Export DLQ jobs (future API endpoint):**
```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://api.villagecompute.com/admin/jobs/dlq?queue=reporting.export&format=json \
     > dlq_export.json
```

**Step 2: Inspect job payloads:**
```bash
jq '.[] | {executionId, priority, attemptNumber, lastError, payload}' dlq_export.json
```

**Step 3: Re-enqueue valid jobs:**

For each job requiring replay:

```bash
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d @job_payload.json \
     https://api.villagecompute.com/admin/jobs/replay
```

**Manual code execution (interim solution):**

```java
// In Quarkus dev console or admin script
@Inject
ReportingJobService reportingJobService;

// Re-enqueue failed report export
UUID jobId = reportingJobService.enqueueExport(
    "sales_by_period",
    "csv",
    Map.of("start", "2025-01-01", "end", "2025-01-31"),
    "admin@villagecompute.com"
);
```

### 3. Pausing/Resuming Queue Processing

**Scenario:** Maintenance window, external service outage, runaway job investigation

#### Pausing Queue Processing

**Option 1: Scale worker deployment to 0 (recommended):**
```bash
kubectl scale deployment/village-storefront-workers --replicas=0
```

**Option 2: Disable scheduled job processing via feature flag:**
```bash
# Set environment variable
kubectl set env deployment/village-storefront \
  QUARKUS_SCHEDULER_ENABLED=false

# Or update ConfigMap
kubectl edit configmap village-storefront-config
```

**Verify queue processing stopped:**
```promql
rate(<queue_name>.job.started[1m]) == 0
```

#### Resuming Queue Processing

**Restore worker replicas:**
```bash
kubectl scale deployment/village-storefront-workers --replicas=3
```

**Re-enable scheduler:**
```bash
kubectl set env deployment/village-storefront \
  QUARKUS_SCHEDULER_ENABLED=true
```

**Monitor queue drain rate:**
```promql
rate(<queue_name>.job.completed[5m])
```

### 4. Clearing Dead-Letter Queue

**Scenario:** After investigation and manual fixes, clear DLQ of resolved jobs

**Step 1: Backup DLQ contents:**
```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://api.villagecompute.com/admin/jobs/dlq?queue=all&format=json \
     > dlq_backup_$(date +%Y%m%d_%H%M%S).json
```

**Step 2: Clear DLQ programmatically (future API):**
```bash
curl -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://api.villagecompute.com/admin/jobs/dlq?queue=reporting.export
```

**Step 3: Verify DLQ cleared:**
```promql
<queue_name>.dlq.depth == 0
```

---

## Scaling Guidance

### Horizontal Pod Autoscaling (HPA)

Worker deployments scale based on queue depth metrics:

```yaml
# k8s/base/hpa-workers.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: village-storefront-workers
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: village-storefront-workers
  minReplicas: 2
  maxReplicas: 20
  metrics:
  - type: Pods
    pods:
      metric:
        name: reporting_refresh_queue_depth
      target:
        type: AverageValue
        averageValue: "100"
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Pods
        value: 1
        periodSeconds: 60
```

### Dedicated Worker Pools (Future Enhancement)

Separate worker deployments per priority to prevent starvation:

```yaml
# k8s/base/deployment-workers-critical.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: village-storefront-workers-critical
spec:
  replicas: 5
  template:
    spec:
      containers:
      - name: worker
        env:
        - name: WORKER_PRIORITY_FILTER
          value: "CRITICAL,HIGH"
        resources:
          requests:
            cpu: "500m"
            memory: "1Gi"
          limits:
            cpu: "2000m"
            memory: "4Gi"
```

### Capacity Planning

**Queue depth thresholds:**
- CRITICAL: 100 jobs → scale up immediately
- HIGH: 500 jobs → scale up
- DEFAULT: 1000 jobs → scale up
- LOW/BULK: 5000 jobs → scale up (best-effort)

**Worker sizing:**
- **CRITICAL/HIGH workers:** 500m-2000m CPU, 1-4Gi memory
- **DEFAULT workers:** 250m-1000m CPU, 512Mi-2Gi memory
- **BULK workers:** 100m-500m CPU, 256Mi-1Gi memory

**Scaling formula:**
```
target_replicas = ceil(queue_depth / jobs_per_worker_per_minute / target_latency_minutes)
```

Example for DEFAULT queue:
- Queue depth: 3000 jobs
- Worker throughput: 10 jobs/min
- Target latency: 30s (0.5 min)
- **Replicas needed:** `ceil(3000 / 10 / 0.5) = 600` (likely hit maxReplicas limit → investigate workload)

---

## Troubleshooting Scenarios

### Scenario 1: CRITICAL Queue Backing Up

**Symptoms:**
- CRITICAL queue depth increasing
- Payment confirmation delays reported by users
- Prometheus alert: `critical_queue_depth_high`

**Investigation:**
1. Check worker pod health:
   ```bash
   kubectl get pods -l app=village-storefront-workers
   kubectl top pods -l app=village-storefront-workers
   ```

2. Review recent job failure rate:
   ```promql
   rate(reporting.job.failed{priority="critical"}[5m])
   ```

3. Inspect worker logs for errors:
   ```bash
   kubectl logs -l app=village-storefront-workers --tail=200 | grep "priority=CRITICAL"
   ```

**Resolution:**
- If workers healthy but queue growing: Scale up replicas manually
  ```bash
  kubectl scale deployment/village-storefront-workers --replicas=10
  ```

- If workers crashing: Check resource limits (OOM, CPU throttling)
  ```bash
  kubectl describe pod <pod-name>
  ```

- If external service failing: Enable circuit breaker, notify stakeholders

### Scenario 2: Dead-Letter Queue Accumulating

**Symptoms:**
- DLQ depth increasing steadily
- Jobs exhausting retry attempts
- Prometheus alert: `dlq_depth_high`

**Investigation:**
1. Sample recent DLQ additions:
   ```bash
   kubectl logs -l app=village-storefront --tail=50 | grep "Job moved to DLQ"
   ```

2. Group errors by type:
   ```bash
   kubectl logs -l app=village-storefront | grep "Job moved to DLQ" | \
     jq -r '.lastError' | sort | uniq -c | sort -rn
   ```

3. Check for tenant-specific failures:
   ```bash
   kubectl logs -l app=village-storefront | grep "Job moved to DLQ" | \
     jq -r '.tenantId' | sort | uniq -c | sort -rn
   ```

**Resolution:**
- **Data errors:** Fix tenant data, replay jobs manually
- **Code bugs:** Deploy hotfix, replay jobs after fix
- **External service outage:** Clear DLQ after service recovery (jobs will auto-retry if re-enqueued)

### Scenario 3: Queue Overflow Events

**Symptoms:**
- Jobs rejected due to queue capacity
- Prometheus metric: `<queue_name>.queue.overflow` incrementing
- User-facing feature degradation (reports not generating)

**Investigation:**
1. Identify which priority queue is overflowing:
   ```promql
   sum(rate(<queue_name>.queue.overflow[5m])) by (priority)
   ```

2. Check current queue depth vs. capacity:
   ```promql
   <queue_name>.queue.depth{priority="<priority>"}
   ```

3. Review worker throughput:
   ```promql
   rate(<queue_name>.job.completed{priority="<priority>"}[5m])
   ```

**Resolution:**
- **Short-term:** Increase queue capacity via ConfigMap:
  ```yaml
  # application.properties or environment variable
  jobs.queue.capacity.default=20000
  ```

- **Long-term:** Scale worker pool or optimize job processing time
- **Investigate:** Why is enqueue rate exceeding processing rate?

### Scenario 4: Jobs Stuck in Queue (Not Processing)

**Symptoms:**
- Queue depth stable but jobs not completing
- No `job.started` metrics incrementing
- Worker pods running but idle

**Investigation:**
1. Check scheduler health:
   ```bash
   kubectl exec -it <worker-pod> -- curl localhost:8080/q/health
   ```

2. Verify scheduler enabled:
   ```bash
   kubectl exec -it <worker-pod> -- env | grep QUARKUS_SCHEDULER_ENABLED
   ```

3. Check for deadlocks or thread exhaustion:
   ```bash
   kubectl exec -it <worker-pod> -- jstack 1
   ```

**Resolution:**
- Restart worker pods:
  ```bash
  kubectl rollout restart deployment/village-storefront-workers
  ```

- If scheduler disabled, re-enable via ConfigMap update

---

## Runbook Maintenance

### Update Triggers

This runbook should be updated when:
1. New job priorities added or retry policies changed
2. Queue capacity limits adjusted
3. New metrics or alerts introduced
4. Worker deployment topology changes (dedicated pools)
5. Manual intervention procedures evolve

### Related Documentation

- **Architecture:** `docs/architecture/04_Operational_Architecture.md`
- **Monitoring:** Grafana dashboard `Background Job Health`
- **Incident Response:** `docs/operations/incident_response.md`
- **Feature Flags:** `docs/operations/feature_flags.md`

---

## Emergency Contacts

| Role                  | Contact           | Escalation Path       |
|-----------------------|-------------------|-----------------------|
| On-Call Engineer      | PagerDuty rotation | → Engineering Manager |
| Database Admin        | DBA team          | → Infrastructure Lead |
| External Services     | Vendor support    | → Technical Account Mgr |

**Incident Slack Channel:** `#incidents-storefront`

**Status Page:** `https://status.villagecompute.com`
