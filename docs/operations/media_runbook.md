# Media Pipeline Operational Runbook

**Task:** I4.T5
**Last Updated:** 2026-01-03
**Owner:** Platform Operations / Media Domain Squad
**Related Docs:** [Media Pipeline Spec](../media/pipeline.md) | [Sequence Diagram](../diagrams/sequence_media_pipeline.mmd) | [Architecture §3.6](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#3-6-background-processing)

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture Components](#architecture-components)
3. [Normal Operations](#normal-operations)
4. [Scaling Procedures](#scaling-procedures)
5. [Failure Scenarios](#failure-scenarios)
6. [Verification Metrics (Section 6 Reference)](#verification-metrics-section-6-reference)
7. [Kill Switches](#kill-switches)
8. [Capacity Planning](#capacity-planning)
9. [Appendix: Configuration Reference](#appendix-configuration-reference)

---

## Overview

The Media Processing Pipeline handles upload negotiation, image resizing, video transcoding with HLS packaging, and presigned URL delivery for the Village Storefront platform.

### Pipeline Stages

1. **Upload Negotiation** → Client receives presigned R2 URL
2. **Client Upload** → Direct upload to Cloudflare R2 (bypasses API)
3. **Completion Callback** → API enqueues background processing job
4. **Worker Processing** → Download → Generate derivatives → Upload to R2
5. **Status Update** → Mark asset `ready`, update quota usage
6. **Download URL Issuance** → Generate time-limited signed URLs (24h expiry)

### Key Principles

- **Tenant Isolation**: All storage keys prefixed with `{tenantId}/media/...`
- **Async Processing**: Images target <30s, videos target <2m latency
- **Quota Enforcement**: Default 10GB per tenant, 80% warning threshold
- **Retry Policy**: 3 attempts with exponential backoff (1s, 2s, 4s)
- **Observability**: Prometheus metrics, structured JSON logs with tenant context

**Visual Reference:** See [sequence_media_pipeline.mmd](../diagrams/sequence_media_pipeline.mmd) for detailed flow.

---

## Architecture Components

### Code Modules

| Component | Location | Responsibility |
|-----------|----------|----------------|
| **MediaResource** | `villagecompute.storefront.api.rest.MediaResource` | REST API endpoints (negotiation, completion, download) |
| **MediaJobService** | `villagecompute.storefront.services.MediaJobService` | Job orchestration, queue management, scheduled draining |
| **MediaProcessor** | `villagecompute.storefront.media.MediaProcessor` | Image (Thumbnailator) + video (FFmpeg) derivative generation |
| **MediaStorageClient** | `villagecompute.storefront.media.MediaStorageClient` | Cloudflare R2 abstraction (presigned URLs, uploads, downloads) |
| **PriorityJobQueue** | `villagecompute.storefront.services.jobs.config.PriorityJobQueue` | Priority-based job queueing (CRITICAL, HIGH, DEFAULT, LOW) |
| **FeatureToggle** | `villagecompute.storefront.services.FeatureToggle` | Feature flag resolution (tenant + platform defaults) |

### Kubernetes Resources

| Resource | Namespace | Description |
|----------|-----------|-------------|
| **Deployment: `village-storefront-workers`** | `default` | Background worker pods processing media jobs |
| **HPA: `village-storefront-workers-hpa`** | `default` | Auto-scaler: 2-20 replicas based on CPU/memory/queue depth |
| **ConfigMap: `village-storefront-config`** | `default` | R2 bucket name, mailer host, FFmpeg path |
| **Secret: `village-storefront-r2`** | `default` | R2 access keys (`access-key-id`, `secret-access-key`) |
| **PodDisruptionBudget: `village-storefront-workers-pdb`** | `default` | Ensures min 1 replica during voluntary disruptions |

**Deployment file:** `k8s/base/deployment-workers.yaml`

### Database Tables

- `media_assets` – Asset metadata (status, tenant_id, upload/processing timestamps)
- `media_derivatives` – Derivative metadata (type, dimensions, storage_key)
- `media_quotas` – Tenant storage usage tracking
- `media_access_logs` – Download access audit trail
- `dead_letter_queue` – Failed jobs requiring manual intervention

---

## Normal Operations

### Health Indicators

Monitor these signals to confirm normal operation:

#### Key Metrics (Prometheus)

```promql
# Job throughput (jobs/sec)
rate(media_job_success_total{tenant="*",type="image|video"}[5m])

# Processing latency (p50, p95, p99)
histogram_quantile(0.95, media_job_duration_seconds_bucket{tenant="*",type="*"})

# Queue depth (current backlog)
media_queue_depth{priority="DEFAULT|LOW"}

# Failure rate
rate(media_job_failed_total{tenant="*",type="*"}[5m])

# Quota rejections
rate(media_quota_exceeded_total{tenant="*"}[5m])

# FFmpeg/Thumbnailator errors
media_processing_error_total{component="ffmpeg|thumbnailator"}
```

**Dashboard:** Grafana > Village Storefront > Media Pipeline Health

#### Expected Baselines (Foundation §6.0 KPIs)

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| Image processing latency (p95) | <30s | >60s for 5 min |
| Video processing latency (p95) | <120s | >300s for 5 min |
| Job success rate | >99.5% | <99% for 10 min |
| Queue depth (DEFAULT) | <50 | >100 for 5 min |
| Queue depth (LOW - videos) | <20 | >50 for 10 min |
| FFmpeg crash rate | <0.1% | >1% for 10 min |

#### Health Endpoints

```bash
# Worker pod liveness (FFmpeg availability, DB connectivity)
curl http://village-storefront-workers:8080/q/health/live

# Worker pod readiness (job queue initialized)
curl http://village-storefront-workers:8080/q/health/ready
```

**Expected responses:** Both should return HTTP 200 with `{"status":"UP"}`.

### Routine Monitoring Tasks

**Daily:**
- Review queue depth trends for growth patterns
- Check DLQ for new entries: `SELECT COUNT(*) FROM dead_letter_queue WHERE owning_module='media.processing'`
- Verify storage usage remains within growth projections (see Capacity Planning)

**Weekly:**
- Audit top 10 tenants by quota usage for abuse patterns
- Review FFmpeg error logs for repeated failures (codec issues, corrupt files)
- Validate HPA scaling events align with traffic patterns

**Monthly:**
- Test kill switch procedures in staging (see Kill Switches section)
- Rotate R2 access keys if security policy requires
- Review and prune old DLQ entries (>90 days)

---

## Scaling Procedures

### Detecting Scale Needs

**Trigger conditions:**

1. **Queue Backlog**: Queue depth >100 (DEFAULT) or >50 (LOW) sustained for >5 minutes
2. **High Latency**: p95 processing time exceeds targets (>60s images, >300s videos)
3. **CPU/Memory Pressure**: Worker pods consistently >70% CPU or >80% memory
4. **Anticipated Load**: Known event (sale, launch) expected to spike uploads

### Manual Scaling

#### Increase Worker Replicas

```bash
# Scale to 8 replicas immediately
kubectl scale deployment village-storefront-workers --replicas=8

# Verify new pods are ready
kubectl get pods -l component=workers -w

# Check logs for successful job processing
kubectl logs -l component=workers --tail=50 --follow | grep "media.job.success"
```

**Rollback:**
```bash
kubectl scale deployment village-storefront-workers --replicas=3
```

#### Tune HPA Thresholds

Edit `k8s/base/deployment-workers.yaml` to adjust HPA behavior:

```yaml
spec:
  minReplicas: 2   # Minimum pods (increase for baseline capacity)
  maxReplicas: 20  # Maximum pods (increase for burst headroom)
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70  # Lower to scale earlier (e.g., 60)
```

Apply changes:
```bash
kubectl apply -f k8s/base/deployment-workers.yaml

# Verify HPA updated
kubectl describe hpa village-storefront-workers-hpa
```

#### Adjust Queue Capacity

Increase queue capacity via environment variables in `deployment-workers.yaml`:

```yaml
env:
- name: JOBS_QUEUE_CAPACITY_DEFAULT
  value: "10000"  # Default: 10000 (images)
- name: JOBS_QUEUE_CAPACITY_LOW
  value: "500"    # Default: 250 (videos)
```

Redeploy workers:
```bash
kubectl rollout restart deployment village-storefront-workers
kubectl rollout status deployment village-storefront-workers
```

**Effect:** Allows more jobs to queue before rejecting, smoothing bursts.

### Auto-Scaling Verification

After HPA adjustments or manual scaling, monitor effectiveness:

```bash
# Watch HPA scaling decisions
kubectl get hpa village-storefront-workers-hpa -w

# Check current replica count vs. desired
kubectl get deployment village-storefront-workers

# Review scaling events
kubectl describe hpa village-storefront-workers-hpa | grep -A10 "Events:"
```

**Expected behavior:**
- Scale-up completes within 60s (stabilization window)
- Scale-down waits 300s (prevents flapping)
- Max scale-up rate: 50% or +2 pods per 60s

---

## Failure Scenarios

### Scenario 1: Queue Backlog Exceeds SLA

**Symptoms:**
- `media_queue_depth{priority="DEFAULT"}` >100 for >5 minutes
- Prometheus alert: `MediaQueueBacklog` firing
- Customer complaints about slow image availability

**Detection:**
```bash
# Check current queue depth
kubectl exec -it $(kubectl get pod -l component=workers -o name | head -1) -- \
  curl -s localhost:8080/q/metrics | grep media_queue_depth

# Review queue growth rate
# (Use Grafana dashboard: Media Pipeline > Queue Depth panel)
```

**Response Steps:**

1. **Scale workers immediately** (+3 replicas recommended):
   ```bash
   kubectl scale deployment village-storefront-workers --replicas=6
   ```

2. **Verify FFmpeg readiness** (ensure workers can process):
   ```bash
   kubectl exec -it $(kubectl get pod -l component=workers -o name | head -1) -- \
     /usr/bin/ffmpeg -version
   ```

3. **Inspect for poison pills** (jobs causing repeated failures):
   ```sql
   -- Check DLQ for recent spikes
   SELECT asset_id, tenant_id, error_message, attempt_count, created_at
   FROM dead_letter_queue
   WHERE owning_module = 'media.processing'
     AND created_at > NOW() - INTERVAL '1 hour'
   ORDER BY created_at DESC
   LIMIT 20;
   ```

4. **Pause uploads if backlog critical** (>500 jobs and growing):
   ```bash
   # Use kill switch (see Kill Switches section)
   # Platform Admin UI > Feature Flags > Set "media.upload.enabled" = false
   ```

5. **Monitor recovery**:
   ```bash
   watch -n 10 'kubectl exec -it $(kubectl get pod -l component=workers -o name | head -1) -- curl -s localhost:8080/q/metrics | grep media_queue_depth'
   ```

6. **Re-enable uploads** once queue depth <50:
   ```bash
   # Platform Admin UI > Feature Flags > Set "media.upload.enabled" = true
   ```

**Prevention:**
- Set up proactive HPA tuning (lower CPU threshold to 60%)
- Configure custom metric-based scaling on queue depth (requires Prometheus Adapter)
- Pre-scale workers before known high-traffic events

---

### Scenario 2: FFmpeg Repeated Failures

**Symptoms:**
- `media_processing_error_total{component="ffmpeg"}` spiking
- Multiple jobs for same tenant failing with similar errors
- Worker pods crashing (OOMKilled or FFmpeg SIGKILL)

**Detection:**
```bash
# Check FFmpeg error rates
kubectl exec -it $(kubectl get pod -l component=workers -o name | head -1) -- \
  curl -s localhost:8080/q/metrics | grep media_processing_error_total

# Review worker logs for FFmpeg stderr
kubectl logs -l component=workers --tail=100 | grep -i "ffmpeg\|transcode\|hls"

# Check for pod crashes
kubectl get pods -l component=workers | grep -i "OOMKilled\|CrashLoop"
```

**Response Steps:**

1. **Identify problematic assets**:
   ```sql
   SELECT ma.id, ma.tenant_id, ma.filename, ma.mime_type, ma.error_message
   FROM media_assets ma
   WHERE ma.status = 'failed'
     AND ma.updated_at > NOW() - INTERVAL '1 hour'
     AND ma.error_message ILIKE '%ffmpeg%'
   LIMIT 10;
   ```

2. **Download and inspect failing media**:
   ```bash
   # Use psql to get storage_key for failing asset
   # Then download via R2 CLI or presigned URL for forensic analysis
   aws s3 cp s3://village-media/{storage_key} /tmp/debug.mp4 --endpoint-url=https://...

   # Test FFmpeg locally
   ffmpeg -i /tmp/debug.mp4 -f null -
   ```

3. **Pause processing for affected tenant** (if poison pill confirmed):
   ```sql
   -- Mark jobs for specific tenant to skip processing
   -- (Use admin UI to pause tenant's media processing flag)
   ```

4. **Increase worker memory limits** (if OOMKilled):
   ```yaml
   # Edit k8s/base/deployment-workers.yaml
   resources:
     limits:
       memory: "3Gi"  # Increase from 2Gi
   ```
   Apply: `kubectl apply -f k8s/base/deployment-workers.yaml`

5. **Patch MediaProcessor** if codec/format issue identified:
   - Update FFmpeg command flags (e.g., add codec compatibility)
   - Deploy fix to staging, validate with failing asset
   - Promote to production, trigger manual retry for DLQ jobs

6. **Retry failed jobs** from DLQ:
   ```bash
   # Use admin API endpoint
   curl -X POST https://platform.domain.com/admin/api/media/jobs/dlq/{jobId}/retry \
     -H "Authorization: Bearer {admin-token}"
   ```

**Prevention:**
- Implement content-type validation stricter than just MIME type (check magic bytes)
- Add resource limits per FFmpeg invocation (timeout, max memory)
- Pre-validate video codecs during upload negotiation (probe metadata before accepting)

---

### Scenario 3: R2 Storage Outage or Credential Failure

**Symptoms:**
- Upload negotiation returning 500 errors
- Workers failing to download originals or upload derivatives
- Metrics show R2 client errors spiking

**Detection:**
```bash
# Check worker logs for R2 errors
kubectl logs -l component=workers --tail=100 | grep -i "r2\|s3\|cloudflare\|403\|401"

# Test R2 connectivity from worker pod
kubectl exec -it $(kubectl get pod -l component=workers -o name | head -1) -- \
  curl -I https://{account-id}.r2.cloudflarestorage.com
```

**Response Steps:**

1. **Verify R2 credentials validity**:
   ```bash
   # Check secret exists and contains keys
   kubectl get secret village-storefront-r2 -o yaml

   # Decode and test keys manually (use AWS CLI with R2 endpoint)
   export AWS_ACCESS_KEY_ID=$(kubectl get secret village-storefront-r2 -o jsonpath='{.data.access-key-id}' | base64 -d)
   export AWS_SECRET_ACCESS_KEY=$(kubectl get secret village-storefront-r2 -o jsonpath='{.data.secret-access-key}' | base64 -d)
   aws s3 ls s3://village-media --endpoint-url=https://...
   ```

2. **Rotate credentials if expired/revoked**:
   ```bash
   # Generate new R2 API tokens in Cloudflare dashboard
   # Update Kubernetes secret
   kubectl create secret generic village-storefront-r2 \
     --from-literal=access-key-id=NEW_KEY \
     --from-literal=secret-access-key=NEW_SECRET \
     --dry-run=client -o yaml | kubectl apply -f -

   # Restart workers to pick up new secret
   kubectl rollout restart deployment village-storefront-workers
   ```

3. **Check Cloudflare R2 status page**:
   - Visit https://www.cloudflarestatus.com/
   - If R2 degraded, monitor for resolution and avoid scaling (won't help)

4. **Enable kill switch to pause uploads** during outage:
   ```bash
   # Platform Admin UI > Feature Flags > Set "media.upload.enabled" = false
   ```

5. **Resume operations** after R2 recovery:
   - Re-enable upload flag
   - Monitor queue drain rate
   - Check for jobs stuck in DLQ due to R2 errors, trigger manual retry

**Prevention:**
- Set up Cloudflare R2 status monitoring with alerting
- Implement circuit breaker pattern for R2 client (fail fast, retry with backoff)
- Store R2 credentials in external secret manager (Vault) with auto-rotation

---

### Scenario 4: Tenant Quota Exhaustion Spike

**Symptoms:**
- `media_quota_exceeded_total{tenant="X"}` counter increasing
- Customer reports upload failures with HTTP 413
- Admin dashboard shows tenant at/near quota limit

**Detection:**
```sql
-- Identify tenants near quota limits
SELECT t.id, t.subdomain, mq.used_bytes, mq.quota_bytes,
       (mq.used_bytes::float / mq.quota_bytes * 100) AS usage_percent
FROM tenants t
JOIN media_quotas mq ON t.id = mq.tenant_id
WHERE mq.enforce_quota = true
  AND (mq.used_bytes::float / mq.quota_bytes) > 0.80
ORDER BY usage_percent DESC
LIMIT 20;
```

**Response Steps:**

1. **Verify legitimate usage vs. abuse**:
   ```sql
   -- Check upload frequency and asset sizes
   SELECT DATE_TRUNC('day', created_at) AS upload_date,
          COUNT(*) AS upload_count,
          SUM(file_size) AS total_bytes
   FROM media_assets
   WHERE tenant_id = '{tenant-uuid}'
     AND created_at > NOW() - INTERVAL '7 days'
   GROUP BY upload_date
   ORDER BY upload_date DESC;
   ```

2. **Temporary quota increase** (for legitimate spike):
   ```sql
   -- Double quota for tenant (requires approval)
   UPDATE media_quotas
   SET quota_bytes = quota_bytes * 2
   WHERE tenant_id = '{tenant-uuid}';
   ```

3. **Disable enforcement** for premium tenants:
   ```sql
   UPDATE media_quotas
   SET enforce_quota = false
   WHERE tenant_id = '{tenant-uuid}';
   ```

4. **Contact tenant** if abuse suspected:
   - Review assets for policy violations (spam, off-platform use)
   - Provide guidance on optimization (image compression, video bitrate reduction)

5. **Clean up orphaned derivatives** (may free space):
   ```bash
   # Trigger orphan cleanup job manually
   curl -X POST https://platform.domain.com/admin/api/media/cleanup/orphaned \
     -H "Authorization: Bearer {admin-token}" \
     -d '{"tenantId": "{tenant-uuid}"}'
   ```

**Prevention:**
- Implement proactive alerting at 80% quota usage
- Provide tenant-facing dashboard showing quota usage trends
- Offer paid quota increase options in admin UI

---

## Verification Metrics (Section 6 Reference)

Section 6 of the [Blueprint Foundation](../../.codemachine/artifacts/architecture/01_Blueprint_Foundation.md#section-6-risk-mitigations) mandates verification metrics for every background workload. Use the signals below to demonstrate recovery during incidents and to document compliance with the Section 6 verification metrics checklist.

| Section 6 verification metric | Media pipeline signal | Dashboard / alert |
|------------------------------|-----------------------|-------------------|
| Job throughput + success rate | `rate(media_job_success_total{type="image|video"}[5m])` | Grafana: *Media Pipeline Health → Throughput* panel, alert `MediaThroughputDrop` |
| Processing latency budgets | `histogram_quantile(0.95, media_job_duration_seconds_bucket{type="*"})` | Grafana: *Media Latency* panel, alert `MediaLatencySLO` |
| Queue depth / backlog pressure | `media_queue_depth{priority="DEFAULT|LOW"}` | Grafana: *Queue Depth* panel, alert `MediaQueueBacklog` |
| Failure + DLQ rate | `rate(media_job_failed_total{type="*"}[5m])`, `media_processing_error_total` | Grafana: *Failure Funnel*, alert `MediaFailureSpike`; DLQ report (`dead_letter_queue`) |
| Quota enforcement / rejection rate | `rate(media_quota_exceeded_total{tenant="*"}[5m])` | Grafana: *Quota Pressure* panel, alert `MediaQuotaExceeded` |
| Storage usage + capacity | `media_quotas.used_bytes`, `media_quotas.quota_bytes` (SQL view or Prom scrape) | Grafana: *Tenant Storage Usage*, alert `MediaStorageCapacity` |

**Verification workflow (link to detection/response steps):**

1. **Capture baseline:** Snapshot each Section 6 verification metric before executing remediation (e.g., prior to scaling workers or toggling kill switches).
2. **Apply response:** Follow the scenario-specific detection/response steps (Sections 4–5). Record trace IDs and timestamps in the incident doc when manipulating worker replicas, flags, or quotas.
3. **Confirm recovery:** Re-run the PromQL snippets above. Section 6 verification metrics require evidence that throughput, latency, and backlog returned to their target bands (see [Expected Baselines](#expected-baselines-foundation-60-kpis)).
4. **Record evidence:** Attach Grafana screenshots or CLI snippets to the incident ticket; Section 6 reviews block release promotion if verification artifacts are missing.
5. **Automate checks:** Extend CI dashboards (Grafana + alertmanager) to page on sustained violations (>2 consecutive scrapes) so that Section 6 verification reports stay automated.

By explicitly mapping operational dashboards to the Section 6 verification metrics, the media squad can prove every incident response or scaling change restored SLO compliance.

## Kill Switches

Emergency feature flags to disable media processing without code deployment.

### Available Kill Switches

| Feature Flag | Scope | Effect | Use Case |
|--------------|-------|--------|----------|
| `media.upload.enabled` | Platform or Tenant | Disables upload negotiation; returns HTTP 503 | Pause intake during backlog/outage |
| `media.processing.enabled` | Platform or Tenant | Stops workers from claiming jobs; jobs remain queued | Halt derivative generation (e.g., FFmpeg bug) |
| `media.download.enabled` | Platform or Tenant | Blocks signed URL generation; returns HTTP 503 | Prevent downloads (e.g., legal/security issue) |

### Activating Kill Switches

**Platform Admin UI:**

1. Navigate to: **Platform Admin > Configuration > Feature Flags**
2. Search for flag key (e.g., `media.upload.enabled`)
3. Toggle **Platform Default** to `false` (affects all tenants)
   - OR set **Tenant Override** to `false` for specific tenant
4. Click **Save** (changes take effect within ~10s due to Caffeine cache TTL)

**Via Database (emergency fallback):**

```sql
-- Disable uploads globally
INSERT INTO feature_flags (tenant_id, flag_key, enabled, updated_at)
VALUES (NULL, 'media.upload.enabled', false, NOW())
ON CONFLICT (tenant_id, flag_key)
DO UPDATE SET enabled = false, updated_at = NOW();

-- Disable for specific tenant
INSERT INTO feature_flags (tenant_id, flag_key, enabled, updated_at)
VALUES ('{tenant-uuid}', 'media.upload.enabled', false, NOW())
ON CONFLICT (tenant_id, flag_key)
DO UPDATE SET enabled = false, updated_at = NOW();
```

### Verifying Kill Switch Activation

```bash
# Test upload negotiation (should return 503)
curl -X POST https://{tenant}.platform.com/api/v1/media/upload/negotiate \
  -H "Content-Type: application/json" \
  -d '{"filename":"test.jpg","contentType":"image/jpeg","fileSize":1024,"assetType":"image"}' \
  -w "\nHTTP Status: %{http_code}\n"

# Check feature flag resolution in logs
kubectl logs -l component=workers --tail=50 | grep "FeatureToggle.*media"
```

### Re-enabling After Incident

1. **Confirm root cause resolved** (backlog cleared, FFmpeg patched, R2 restored)
2. **Re-enable flag** via Admin UI or database (reverse steps above)
3. **Monitor metrics** for 15 minutes to ensure normal operation resumes
4. **Communicate status** to affected tenants (if downtime was significant)

**Post-Incident:**
- Document trigger, response, and resolution in incident log
- Update runbook if new failure mode discovered
- Schedule blameless postmortem within 48 hours

---

## Capacity Planning

### Growth Projections

**Assumptions (baseline):**
- Average tenant uploads: 50 images + 5 videos per month
- Average image size: 2 MB (original + 4 derivatives ≈ 3 MB total)
- Average video size: 50 MB (original + HLS variants ≈ 75 MB total)

**Storage growth per tenant per month:**
```
(50 images × 3 MB) + (5 videos × 75 MB) = 150 MB + 375 MB = 525 MB/tenant/month
```

**Scaling thresholds:**

| Tenant Count | Monthly Storage Growth | Recommended R2 Bucket Size | Worker Replica Baseline |
|--------------|------------------------|----------------------------|-------------------------|
| 100 | 52 GB | 500 GB | 3 |
| 500 | 262 GB | 2 TB | 5 |
| 1,000 | 525 GB | 5 TB | 8 |
| 5,000 | 2.6 TB | 25 TB | 12 |

### Worker Capacity Formula

**Processing capacity per worker (assuming 1 CPU, 2GB RAM):**
- Images: ~120 per hour (30s each)
- Videos: ~30 per hour (2m each)

**Required workers for target latency:**

```
Workers_needed = (Jobs_per_hour / Jobs_per_worker_per_hour) × Safety_factor

Example (100 tenants, peak 200 images/hour, 20 videos/hour):
  Image workers: (200 / 120) × 1.5 = 2.5 ≈ 3
  Video workers: (20 / 30) × 1.5 = 1
  Total: 3-4 workers minimum
```

**Safety factor (1.5)** accounts for:
- Upload bursts (sale events, coordinated campaigns)
- Retry overhead for transient failures
- Pod rescheduling during deployments

### HPA Configuration Recommendations

Set HPA min/max based on tenant count tier:

| Tenant Tier | Min Replicas | Max Replicas | CPU Threshold | Memory Threshold |
|-------------|--------------|--------------|---------------|------------------|
| 1-100 | 2 | 8 | 70% | 80% |
| 101-500 | 3 | 12 | 65% | 75% |
| 501-1000 | 5 | 20 | 60% | 75% |
| 1000+ | 8 | 30 | 60% | 70% |

### Storage Budget Planning

**R2 costs (Cloudflare pricing as of 2026):**
- Storage: $0.015/GB/month
- Class A operations (PUT): $4.50 per million
- Class B operations (GET): $0.36 per million

**Monthly cost estimate (1,000 tenants):**
```
Storage: 5,000 GB × $0.015 = $75
PUT ops: 55,000 uploads × 5 derivatives × $4.50/M = $1.24
GET ops: 100,000 downloads × $0.36/M = $0.04
Total: ~$76/month
```

**Quota enforcement prevents runaway costs**; alert if actual costs exceed projections by >20%.

### Monitoring Trends for Proactive Scaling

**Weekly review:**
```sql
-- Upload volume trend (past 4 weeks)
SELECT DATE_TRUNC('week', created_at) AS week,
       COUNT(*) AS uploads,
       SUM(file_size) AS total_bytes
FROM media_assets
WHERE created_at > NOW() - INTERVAL '4 weeks'
GROUP BY week
ORDER BY week;

-- Tenant growth rate
SELECT DATE_TRUNC('month', created_at) AS month,
       COUNT(*) AS new_tenants
FROM tenants
WHERE created_at > NOW() - INTERVAL '6 months'
GROUP BY month
ORDER BY month;
```

**Trigger proactive scaling when:**
- Upload volume grows >30% week-over-week for 2 consecutive weeks
- Queue depth baselines increase (p50 >30 jobs sustained)
- New tenant onboarding accelerates (>20% MoM growth)

---

## Appendix: Configuration Reference

### Environment Variables (k8s/base/deployment-workers.yaml)

| Variable | Default | Description |
|----------|---------|-------------|
| `JOBS_QUEUE_CAPACITY_CRITICAL` | 1000 | Max queued jobs for CRITICAL priority |
| `JOBS_QUEUE_CAPACITY_HIGH` | 5000 | Max queued jobs for HIGH priority |
| `JOBS_QUEUE_CAPACITY_DEFAULT` | 10000 | Max queued jobs for DEFAULT (images) |
| `JOBS_QUEUE_CAPACITY_LOW` | 250 | Max queued jobs for LOW (videos) |
| `JOBS_RETRY_MAX_ATTEMPTS_CRITICAL` | 5 | Max retry attempts for CRITICAL |
| `JOBS_RETRY_MAX_ATTEMPTS_DEFAULT` | 3 | Max retry attempts for DEFAULT/LOW |
| `R2_BUCKET_NAME` | `village-media` | Cloudflare R2 bucket name |
| `R2_ACCESS_KEY_ID` | (secret) | R2 API access key |
| `R2_SECRET_ACCESS_KEY` | (secret) | R2 API secret key |

### Application Properties (application.properties)

```properties
# R2 Storage
media.storage.r2.endpoint=https://account-id.r2.cloudflarestorage.com
media.storage.r2.bucket=village-media

# Processing
media.processing.ffmpeg.path=/usr/bin/ffmpeg
media.processing.thumbnailator.quality=0.85
media.processing.video.hls.segment-duration=6
media.processing.image.sizes=thumbnail:150,small:400,medium:800,large:1600
media.processing.dispatch-interval=3s

# Upload URLs
media.upload-url.expiry-minutes=15

# Quotas
media.quota.default-bytes=10737418240  # 10 GB
media.quota.warn-threshold=0.8

# Signed Download URLs
media.signed-url.expiry-hours=24
media.signed-url.max-download-attempts=5
```

### Prometheus Metric Catalog

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `media_job_enqueued_total` | Counter | `tenant`, `priority` | Jobs enqueued |
| `media_job_success_total` | Counter | `tenant`, `type` | Successfully processed jobs |
| `media_job_failed_total` | Counter | `tenant`, `type` | Failed jobs |
| `media_job_duration_seconds` | Histogram | `tenant`, `type` | Processing duration |
| `media_quota_exceeded_total` | Counter | `tenant` | Quota rejection count |
| `media_queue_depth` | Gauge | `priority` | Current queue size |
| `media_processing_error_total` | Counter | `component` | FFmpeg/Thumbnailator errors |

### Kubernetes Resources Quick Reference

```bash
# View worker pod status
kubectl get pods -l component=workers

# Check HPA scaling status
kubectl get hpa village-storefront-workers-hpa

# View worker logs (last 100 lines)
kubectl logs -l component=workers --tail=100

# Describe deployment for resource limits
kubectl describe deployment village-storefront-workers

# Execute command in worker pod
kubectl exec -it $(kubectl get pod -l component=workers -o name | head -1) -- /bin/sh

# Port-forward to worker metrics endpoint
kubectl port-forward svc/village-storefront-workers 8080:8080
# Then: curl localhost:8080/q/metrics
```

### Database Queries for Troubleshooting

```sql
-- Check current queue depth per priority
SELECT priority, COUNT(*) AS queued_jobs
FROM delayed_jobs
WHERE status = 'pending'
  AND owning_module = 'media.processing'
GROUP BY priority;

-- Find stuck jobs (queued >1 hour)
SELECT id, asset_id, tenant_id, priority, created_at, attempts
FROM delayed_jobs
WHERE status = 'pending'
  AND owning_module = 'media.processing'
  AND created_at < NOW() - INTERVAL '1 hour'
ORDER BY created_at
LIMIT 20;

-- Review DLQ entries (failed jobs)
SELECT id, asset_id, tenant_id, error_message, attempt_count, created_at
FROM dead_letter_queue
WHERE owning_module = 'media.processing'
ORDER BY created_at DESC
LIMIT 20;

-- Tenant quota usage summary
SELECT t.subdomain, mq.used_bytes, mq.quota_bytes,
       ROUND((mq.used_bytes::float / mq.quota_bytes * 100)::numeric, 2) AS usage_percent
FROM tenants t
JOIN media_quotas mq ON t.id = mq.tenant_id
WHERE mq.enforce_quota = true
ORDER BY usage_percent DESC
LIMIT 20;

-- Recent upload volume (last 24 hours)
SELECT DATE_TRUNC('hour', created_at) AS hour,
       COUNT(*) AS uploads
FROM media_assets
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY hour
ORDER BY hour;
```

---

## Related Documentation

- **Architecture:** [04_Operational_Architecture.md §3.6](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#3-6-background-processing)
- **Pipeline Spec:** [Media Pipeline](../media/pipeline.md)
- **Sequence Diagram:** [sequence_media_pipeline.mmd](../diagrams/sequence_media_pipeline.mmd)
- **Foundation:** [01_Blueprint_Foundation.md §3.0 Rulebook](../../.codemachine/artifacts/architecture/01_Blueprint_Foundation.md#section-3-rulebook)
- **KPI Reference:** [04_Operational_Architecture.md §3.12](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#3-12-kpi-telemetry)
- **Job Framework:** `src/main/java/villagecompute/storefront/services/jobs/config/`

---

**Document Version:** 1.0
**Last Verified:** 2026-01-03
**Next Review:** 2026-02-03 (monthly cadence)
**Feedback:** Report issues or suggest improvements via platform ops Slack channel or GitHub issues
