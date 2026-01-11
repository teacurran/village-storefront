# Background Jobs Architecture

<!-- anchor: background-jobs-blueprint -->

**Status:** Authoritative
**Last Updated:** 2026-01-11
**Owner:** Architecture Team

## Document Purpose

This document provides the comprehensive technical specification for Village Storefront's database-backed asynchronous job processing framework. It covers the DelayedJob pattern implementation, queue architecture, worker lifecycle, job payload contracts, retry strategies, and operational monitoring.

**Intended Audience:** Backend engineers implementing async features, DevOps engineers scaling worker pools, QA engineers testing background workflows, operations teams monitoring production health.

---

## Table of Contents

1. [Overview & Architecture](#overview--architecture)
2. [Queue Schema & Job Lifecycle](#queue-schema--job-lifecycle)
3. [Priority System & SLA Commitments](#priority-system--sla-commitments)
4. [Worker Architecture](#worker-architecture)
5. [Job Payload Contracts](#job-payload-contracts)
6. [Retry & Error Handling](#retry--error-handling)
7. [Tenant Isolation](#tenant-isolation)
8. [Media Pipeline Integration](#media-pipeline-integration)
9. [Monitoring & Metrics](#monitoring--metrics)
10. [Circuit Breaker Strategy](#circuit-breaker-strategy)
11. [References & Related Documents](#references--related-documents)

---

<!-- anchor: overview-architecture -->

## 1. Overview & Architecture

### Design Philosophy

Village Storefront implements database-backed background processing using the **DelayedJob** pattern. This architectural choice aligns with the platform's "No Redis" constraint while providing:

- **Prioritized queue execution** (CRITICAL → BULK)
- **Transactional job enqueuing** (atomicity with business operations)
- **Horizontal worker scaling** via Kubernetes HPAs
- **Operational observability** through Prometheus metrics and structured logging
- **Tenant isolation** enforced at queue level via PostgreSQL RLS

### Core Components

```
┌─────────────────────────────────────────────────────────────────┐
│                    Application Services                          │
│  MediaService, PayoutService, ReportingService, EmailService    │
└─────────────────────────────────────────────────────────────────┘
                            ↓ enqueue()
┌─────────────────────────────────────────────────────────────────┐
│                  JobService (Queue Facade)                       │
│  Priority routing, payload serialization, version tagging       │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│            PriorityJobQueue (Database Table)                     │
│  Row-level locking, tenant scoping, status tracking             │
└─────────────────────────────────────────────────────────────────┘
                            ↓ poll()
┌─────────────────────────────────────────────────────────────────┐
│              Background Worker Pods                              │
│  @Scheduled polling, TenantContext seeding, job execution       │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│                  Job Handlers                                    │
│  MediaProcessingHandler, PayoutBatchHandler, etc.               │
└─────────────────────────────────────────────────────────────────┘
```

### Queue Types

Background jobs are categorized by functional area, each with dedicated tables for operational isolation:

| Queue Name | Purpose | Priority Levels | Target Latency |
|------------|---------|-----------------|----------------|
| `media.processing` | Image/video transcoding, derivative generation | DEFAULT, LOW | < 30s (images), < 5m (video) |
| `payouts.batch` | Consignment payout calculation and ACH transfer | HIGH, DEFAULT | < 5s |
| `reports.export` | Report generation (CSV, PDF exports) | DEFAULT, LOW | < 30s |
| `emails.transactional` | Order confirmations, password resets | CRITICAL, HIGH | < 1s |
| `webhooks.stripe` | Payment webhook processing | CRITICAL | < 1s |
| `cleanup.sessions` | Session expiry, temp file cleanup | BULK | Best-effort |

**Rationale:** Separate tables prevent queue head-of-line blocking and allow independent worker pool tuning.

---

<!-- anchor: queue-schema-job-lifecycle -->

## 2. Queue Schema & Job Lifecycle

### Queue Table Schema

All queue tables follow this template (example: `media_processing_jobs`):

```sql
CREATE TABLE media_processing_jobs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    -- Job identification
    job_type          VARCHAR(100) NOT NULL,  -- e.g., 'image_processing', 'video_transcoding'
    priority          VARCHAR(20) NOT NULL,   -- CRITICAL, HIGH, DEFAULT, LOW, BULK

    -- Scheduling
    status            VARCHAR(20) NOT NULL DEFAULT 'pending',
    run_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_until      TIMESTAMPTZ,
    locked_by         VARCHAR(255),  -- Worker pod name

    -- Payload & versioning
    payload           JSONB NOT NULL,
    payload_version   INTEGER NOT NULL DEFAULT 1,

    -- Retry tracking
    attempts          INTEGER NOT NULL DEFAULT 0,
    max_attempts      INTEGER NOT NULL,
    last_error        TEXT,

    -- Timing
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,

    CONSTRAINT valid_status CHECK (status IN ('pending', 'locked', 'processing', 'completed', 'failed', 'dead'))
);

-- Critical indexes for worker polling
CREATE INDEX idx_media_jobs_queue_poll
    ON media_processing_jobs(tenant_id, priority, status, run_at)
    WHERE status = 'pending';

CREATE INDEX idx_media_jobs_worker_claim
    ON media_processing_jobs(status, run_at)
    WHERE status = 'pending';

CREATE INDEX idx_media_jobs_lock_expiry
    ON media_processing_jobs(locked_until)
    WHERE status = 'locked' AND locked_until IS NOT NULL;

-- RLS policy (see Section 7)
ALTER TABLE media_processing_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE media_processing_jobs FORCE ROW LEVEL SECURITY;

CREATE POLICY media_jobs_isolation_policy ON media_processing_jobs
    FOR ALL
    USING (tenant_id = get_current_tenant_id());
```

### Job Lifecycle States

```
┌──────────┐
│ pending  │ ← Initial state (run_at in future = scheduled)
└──────────┘
     ↓ Worker claims via SELECT FOR UPDATE SKIP LOCKED
┌──────────┐
│  locked  │ ← Row-level lock acquired, locked_until set
└──────────┘
     ↓ Handler execution begins
┌──────────┐
│processing│ ← Status updated before handler invocation
└──────────┘
     ↓
   ┌─────────────────┐
   │                 │
   ↓                 ↓
┌──────────┐    ┌──────────┐
│completed │    │  failed  │
└──────────┘    └──────────┘
                     ↓ (if attempts < max_attempts)
                Retry with backoff (return to pending)
                     ↓ (if attempts >= max_attempts)
                ┌──────────┐
                │   dead   │ ← Moved to dead_letter_queue
                └──────────┘
```

**State Transitions:**

1. **pending → locked:** Worker claims job via `SELECT FOR UPDATE SKIP LOCKED`
2. **locked → processing:** Worker updates status before calling handler
3. **processing → completed:** Handler succeeds, `completed_at` timestamp set
4. **processing → failed:** Handler throws exception, retry scheduled if `attempts < max_attempts`
5. **failed → pending:** Retry scheduled with `run_at = NOW() + backoff_delay`
6. **failed → dead:** Max attempts exceeded, job moved to `dead_letter_queue` table

### Worker Job Claim Query

Workers poll for jobs using this optimized query:

```sql
SELECT id, tenant_id, job_type, payload, payload_version, attempts
FROM media_processing_jobs
WHERE status = 'pending'
  AND run_at <= NOW()
  AND (locked_until IS NULL OR locked_until < NOW())
ORDER BY priority ASC, run_at ASC
LIMIT 10
FOR UPDATE SKIP LOCKED;
```

**Query Explanation:**
- `status = 'pending'`: Only unclaimed jobs
- `run_at <= NOW()`: Respect scheduled execution time
- `locked_until < NOW()`: Reclaim stale locks (worker crashed scenario)
- `ORDER BY priority ASC, run_at ASC`: Priority-first, then FIFO within priority
- `LIMIT 10`: Batch claim to reduce query overhead
- `FOR UPDATE SKIP LOCKED`: Skip rows locked by other workers (no blocking)

**Index Usage:** `idx_media_jobs_worker_claim` enables index-only scan.

---

<!-- anchor: priority-system-sla -->

## 3. Priority System & SLA Commitments

### Priority Levels

Priority determines queue position and resource allocation:

| Priority  | Order | Target Latency | Max Queue Depth | Worker Pool Size |
|-----------|-------|----------------|-----------------|------------------|
| CRITICAL  | 0     | < 1s           | 100             | 5-10 pods        |
| HIGH      | 1     | < 5s           | 500             | 3-6 pods         |
| DEFAULT   | 2     | < 30s          | 1000            | 2-5 pods         |
| LOW       | 3     | < 2m           | 5000            | 1-3 pods         |
| BULK      | 4     | Best-effort    | 20000           | 1 pod            |

**Priority Assignment Guidelines:**

- **CRITICAL:** User-facing operations with immediate feedback (payment confirmations, order receipts)
- **HIGH:** Time-sensitive business logic (low-stock alerts, payout batches)
- **DEFAULT:** Standard async work (report exports, media processing)
- **LOW:** Optimization tasks (analytics aggregation, cache warming)
- **BULK:** Batch operations tolerating hours of delay (data migrations, archive exports)

### SLA Table

| Queue Type | Priority | P50 Latency | P95 Latency | P99 Latency | Failure Rate |
|------------|----------|-------------|-------------|-------------|--------------|
| emails.transactional | CRITICAL | 200ms | 800ms | 1.5s | < 0.1% |
| webhooks.stripe | CRITICAL | 150ms | 600ms | 1.2s | < 0.05% |
| payouts.batch | HIGH | 1.2s | 4.5s | 8s | < 0.5% |
| media.processing (images) | DEFAULT | 8s | 25s | 45s | < 1% |
| media.processing (video) | LOW | 90s | 4m | 8m | < 2% |
| reports.export | DEFAULT | 12s | 28s | 50s | < 1% |
| cleanup.sessions | BULK | N/A | N/A | N/A | < 5% |

**Latency Measurement:** Time from `run_at` to `completed_at` (excludes scheduling delay).

**SLA Breach Alerts:**
- P95 latency exceeds target by 2x → P2 alert
- P99 latency exceeds target by 3x → P1 alert
- Failure rate exceeds threshold → P1 alert

---

<!-- anchor: worker-architecture -->

## 4. Worker Architecture

### Worker Pod Deployment

Workers execute as separate Kubernetes Deployments scaled independently from the main API pods:

```yaml
# k8s/base/deployment-workers.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: village-storefront-workers
spec:
  replicas: 3
  selector:
    matchLabels:
      app: village-storefront
      component: worker
  template:
    spec:
      containers:
      - name: worker
        image: villagecompute/storefront:latest
        env:
        - name: WORKER_MODE
          value: "true"
        - name: QUARKUS_SCHEDULER_ENABLED
          value: "true"
        - name: WORKER_POLL_INTERVAL_MS
          value: "3000"
        resources:
          requests:
            cpu: "500m"
            memory: "1Gi"
          limits:
            cpu: "2000m"
            memory: "4Gi"
```

### Scheduled Polling

Workers use Quarkus `@Scheduled` annotations to poll queues:

```java
@ApplicationScoped
public class MediaProcessingWorker {

    @Inject
    MediaProcessingJobService jobService;

    @Inject
    MediaProcessingJobHandler handler;

    @Scheduled(every = "{media.processing.dispatch-interval}",
               concurrentExecution = ConcurrentExecution.SKIP)
    void processJobs() {
        List<MediaProcessingJob> jobs = jobService.claimJobs(10);

        for (MediaProcessingJob job : jobs) {
            try {
                // CRITICAL: Seed tenant context before handler invocation
                TenantContext.setCurrentTenantId(job.tenantId);

                jobService.markProcessing(job.id);
                handler.execute(job.payload);
                jobService.markCompleted(job.id);

            } catch (Exception e) {
                jobService.handleFailure(job.id, e);
            } finally {
                TenantContext.clear(); // MUST clear to prevent bleed
            }
        }
    }
}
```

**Configuration Properties:**

```properties
# application.properties
media.processing.dispatch-interval=3s
media.processing.worker.batch-size=10
media.processing.worker.timeout=300s

# FFmpeg resource isolation
media.ffmpeg.cpu-limit=2000m
media.ffmpeg.memory-limit=4Gi
media.ffmpeg.max-concurrent=2
```

### FFmpeg Worker Isolation

Video processing jobs spawn FFmpeg subprocesses with strict resource limits to prevent worker node exhaustion:

```java
@ApplicationScoped
public class FFmpegProcessor {

    public ProcessingResult transcode(Path input, Path output) {
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg",
            "-i", input.toString(),
            "-c:v", "libx264",
            "-preset", "medium",
            "-crf", "23",
            "-c:a", "aac",
            "-b:a", "128k",
            output.toString()
        );

        // Enforce CPU/memory limits via cgroups (Kubernetes container limits)
        // Kill switch via feature flag check before spawning
        if (!featureFlags.isEnabled("media.processing.enabled")) {
            throw new ProcessingDisabledException("FFmpeg processing disabled");
        }

        Process process = pb.start();

        // Timeout enforcement
        if (!process.waitFor(mediaConfig.ffmpegTimeout(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new TimeoutException("FFmpeg exceeded timeout");
        }

        return parseOutput(process);
    }
}
```

**Resource Guardrails:**
- Container-level CPU/memory limits enforced by Kubernetes
- Process timeout kills runaway FFmpeg jobs
- Max concurrent FFmpeg processes per worker (default: 2)
- Feature flag kill switch (`media.processing.enabled`)

### Horizontal Pod Autoscaling (HPA)

Workers scale based on queue depth metrics exported to Prometheus:

```yaml
# k8s/base/hpa-workers-media.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: village-storefront-workers-media
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: village-storefront-workers
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Pods
    pods:
      metric:
        name: media_processing_queue_depth
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

**HPA Trigger Logic:**
- Scale up when `queue_depth / replicas > 100` (average)
- Scale up by 50% every 60s (max)
- Scale down by 1 pod every 60s (gradual)
- 5-minute stabilization window before scale-down (prevent thrashing)

---

<!-- anchor: job-payload-contracts -->

## 5. Job Payload Contracts

### Payload Versioning Strategy

All job payloads use JSONB with explicit version fields to support schema evolution:

```json
{
  "version": 1,
  "data": {
    "assetId": "550e8400-e29b-41d4-a716-446655440000",
    "assetType": "product_image",
    "contentType": "image/jpeg",
    "storageKey": "tenant-123/media/products/asset-456/original/photo.jpg",
    "derivativeTypes": ["thumbnail", "small", "medium", "large"]
  }
}
```

**Version Compatibility Check:**

```java
@ApplicationScoped
public class MediaProcessingJobHandler {

    private static final int SUPPORTED_VERSION = 1;

    public void execute(JsonNode payload) {
        int version = payload.get("version").asInt();

        if (version != SUPPORTED_VERSION) {
            LOGGER.log(Level.SEVERE, "Payload version mismatch", Map.of(
                "expected", SUPPORTED_VERSION,
                "actual", version,
                "severity", "SEV-1"
            ));
            throw new PayloadVersionMismatchException(
                "Unsupported payload version: " + version
            );
        }

        MediaProcessingPayload data = objectMapper.convertValue(
            payload.get("data"),
            MediaProcessingPayload.class
        );

        processMedia(data);
    }
}
```

**Version Migration Example (v1 → v2):**

```java
// V2 adds optional watermark configuration
{
  "version": 2,
  "data": {
    "assetId": "...",
    "assetType": "product_image",
    "contentType": "image/jpeg",
    "storageKey": "...",
    "derivativeTypes": ["thumbnail", "small", "medium", "large"],
    "watermark": {  // NEW in v2
      "enabled": true,
      "position": "bottom-right",
      "opacity": 0.5
    }
  }
}

// Handler supports both versions:
if (version == 1) {
    // Use default watermark settings
    data.watermark = WatermarkConfig.defaultConfig();
} else if (version == 2) {
    // Use explicit watermark config
}
```

### Common Payload Types

**MediaProcessingPayload (v1):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `assetId` | UUID | Yes | Media asset primary key |
| `assetType` | String | Yes | `product_image`, `digital_product`, `consignment_photo` |
| `contentType` | String | Yes | MIME type (`image/jpeg`, `video/mp4`) |
| `storageKey` | String | Yes | R2 object key for original file |
| `derivativeTypes` | String[] | Yes | List of variants to generate |

**PayoutBatchPayload (v1):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `batchId` | UUID | Yes | Payout batch primary key |
| `consignorId` | UUID | Yes | Consignor receiving payout |
| `periodStart` | ISO 8601 | Yes | Payout period start date |
| `periodEnd` | ISO 8601 | Yes | Payout period end date |
| `lineItemIds` | UUID[] | Yes | Order line items included in batch |

**ReportExportPayload (v1):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `reportType` | String | Yes | `sales_by_period`, `inventory_snapshot` |
| `format` | String | Yes | `csv`, `pdf`, `xlsx` |
| `parameters` | Object | Yes | Report-specific filters (date ranges, etc.) |
| `requestorEmail` | String | Yes | Email to send download link |

---

<!-- anchor: retry-error-handling -->

## 6. Retry & Error Handling

### Exponential Backoff Strategy

Failed jobs retry with exponential backoff based on priority:

```java
public class RetryPolicy {

    public static Duration calculateBackoff(JobPriority priority, int attemptNumber) {
        return switch (priority) {
            case CRITICAL -> Duration.ofMillis(500 * (long) Math.pow(1.5, attemptNumber - 1))
                                .min(Duration.ofSeconds(30));
            case HIGH, DEFAULT, LOW -> Duration.ofSeconds(1 * (long) Math.pow(2, attemptNumber - 1))
                                        .min(Duration.ofMinutes(5));
            case BULK -> Duration.ZERO; // No retry
        };
    }

    public static int maxAttempts(JobPriority priority) {
        return switch (priority) {
            case CRITICAL -> 5;
            case HIGH, DEFAULT, LOW -> 3;
            case BULK -> 0;
        };
    }
}
```

**Backoff Examples:**

| Priority | Attempt 1 | Attempt 2 | Attempt 3 | Attempt 4 | Attempt 5 |
|----------|-----------|-----------|-----------|-----------|-----------|
| CRITICAL | 500ms | 750ms | 1.125s | 1.687s | 2.53s |
| DEFAULT  | 1s | 2s | 4s | - | - |
| BULK     | No retry | - | - | - | - |

### Circuit Breaker Pattern

Prevent cascading failures when external services degrade:

```java
@ApplicationScoped
public class StripeWebhookHandler {

    @Inject
    CircuitBreaker circuitBreaker;

    public void processPaymentIntent(JsonNode payload) {
        circuitBreaker.executeSupplier(() -> {
            // Call Stripe API to verify webhook signature
            return stripeClient.constructEvent(payload);
        });
    }
}

// Circuit breaker configuration
@ApplicationScoped
public class CircuitBreakerConfig {

    @Produces
    @Named("stripe")
    public CircuitBreaker stripeCircuitBreaker() {
        return CircuitBreaker.of("stripe", CircuitBreakerConfig.custom()
            .failureRateThreshold(50) // Open at 50% failure rate
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .build());
    }
}
```

**Circuit Breaker States:**
- **CLOSED:** Normal operation, requests pass through
- **OPEN:** Failure threshold exceeded, fail-fast without calling service
- **HALF_OPEN:** Test request allowed after wait duration, re-close if successful

### Dead Letter Queue (DLQ)

Jobs exceeding `max_attempts` move to the `dead_letter_queue` table for manual inspection:

```sql
CREATE TABLE dead_letter_queue (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID REFERENCES tenants(id),

    -- Original job context
    queue_name        VARCHAR(100) NOT NULL,
    job_type          VARCHAR(100) NOT NULL,
    priority          VARCHAR(20) NOT NULL,

    -- Failure details
    original_job_id   UUID NOT NULL,
    payload           JSONB NOT NULL,
    payload_version   INTEGER NOT NULL,

    attempts          INTEGER NOT NULL,
    last_error        TEXT NOT NULL,
    stack_trace       TEXT,

    -- Ownership
    owning_module     VARCHAR(100),  -- e.g., 'media.processing'
    assignee          VARCHAR(255),  -- Engineer investigating issue

    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at       TIMESTAMPTZ,
    resolution_notes  TEXT
);

CREATE INDEX idx_dlq_queue_created ON dead_letter_queue(queue_name, created_at DESC);
CREATE INDEX idx_dlq_unresolved ON dead_letter_queue(resolved_at) WHERE resolved_at IS NULL;
```

**DLQ Inspection Runbook:**

1. **Identify DLQ buildup:**
   ```promql
   rate(dead_letter_queue_added[5m]) > 0.1
   ```

2. **Group by error type:**
   ```sql
   SELECT substring(last_error, 1, 100) AS error_prefix, COUNT(*)
   FROM dead_letter_queue
   WHERE resolved_at IS NULL
   GROUP BY error_prefix
   ORDER BY COUNT(*) DESC;
   ```

3. **Manual replay after fix:**
   ```bash
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
        -d '{"dlqId": "uuid"}' \
        https://api.villagecompute.com/admin/jobs/replay
   ```

---

<!-- anchor: tenant-isolation -->

## 7. Tenant Isolation

### TenantContext Seeding in Workers

**CRITICAL REQUIREMENT:** All background workers MUST seed `TenantContext` before processing jobs and clear it afterward to comply with the tenant isolation strategy documented in `docs/architecture/tenant_isolation.md`.

```java
@Scheduled(every = "{media.processing.dispatch-interval}")
void processJobs() {
    List<MediaProcessingJob> jobs = jobService.claimJobs(10);

    for (MediaProcessingJob job : jobs) {
        try {
            // STEP 1: Seed tenant context from job payload
            TenantContext.setCurrentTenantId(job.tenantId);

            // STEP 2: Set PostgreSQL RLS session variable
            if (rlsEnabled) {
                entityManager.createNativeQuery(
                    "SELECT set_current_tenant_id(:tenantId)"
                ).setParameter("tenantId", job.tenantId)
                 .getSingleResult();
            }

            // STEP 3: Execute handler (all queries auto-scoped to tenant)
            jobService.markProcessing(job.id);
            handler.execute(job.payload);
            jobService.markCompleted(job.id);

        } catch (Exception e) {
            jobService.handleFailure(job.id, e);
        } finally {
            // STEP 4: Clear context to prevent bleed to next job
            if (rlsEnabled) {
                entityManager.createNativeQuery(
                    "SELECT set_config('app.tenant_id', '', FALSE)"
                ).getSingleResult();
            }
            TenantContext.clear();
        }
    }
}
```

### Cross-Tenant Job Prevention

Queue tables enforce tenant isolation via RLS policies (see Section 2). Additionally, job handlers validate tenant context:

```java
public void processMedia(MediaProcessingPayload payload) {
    UUID currentTenantId = TenantContext.getCurrentTenantId();

    MediaAsset asset = MediaAsset.findById(payload.assetId);
    if (asset == null) {
        throw new NotFoundException("Asset not found");
    }

    // Defensive check: Ensure RLS didn't fail
    if (!asset.tenant.id.equals(currentTenantId)) {
        LOGGER.log(Level.SEVERE, "SECURITY: Cross-tenant access attempt", Map.of(
            "currentTenant", currentTenantId,
            "assetTenant", asset.tenant.id,
            "assetId", payload.assetId
        ));
        throw new SecurityException("Cross-tenant access denied");
    }

    // Proceed with processing
}
```

### Audit Logging

All background job execution logged with tenant context for compliance auditing:

```java
LOGGER.log(Level.INFO, "Job started", Map.of(
    "jobId", job.id,
    "tenantId", job.tenantId,
    "jobType", job.jobType,
    "priority", job.priority,
    "attempt", job.attempts
));
```

**Log Aggregation:** Structured JSON logs ingested into Elasticsearch with tenant-scoped indexes for GDPR compliance (tenant data deletion support).

---

<!-- anchor: media-pipeline-integration -->

## 8. Media Pipeline Integration

### Media Processing Job Flow

The media pipeline integrates with background jobs as documented in `docs/diagrams/media_pipeline.mmd`. Key integration points:

**Phase 1: Job Enqueue (from Media API):**

```java
@POST
@Path("/media/{assetId}/complete")
public Response completeUpload(@PathParam("assetId") UUID assetId) {
    MediaAsset asset = MediaAsset.findById(assetId);
    asset.status = AssetStatus.PENDING;
    asset.persist();

    // Enqueue processing job
    MediaProcessingPayload payload = new MediaProcessingPayload(
        assetId,
        asset.assetType,
        asset.contentType,
        asset.storageKey,
        determineDerivatives(asset)
    );

    JobPriority priority = asset.contentType.startsWith("video/")
        ? JobPriority.LOW
        : JobPriority.DEFAULT;

    mediaJobService.enqueue(payload, priority, TenantContext.getCurrentTenantId());

    return Response.accepted().build();
}
```

**Phase 2: Background Processing:**

```java
@ApplicationScoped
public class MediaProcessingJobHandler {

    public void execute(MediaProcessingPayload payload) {
        // Download original from R2
        Path originalFile = mediaStorage.download(payload.storageKey);

        try {
            if (payload.contentType.startsWith("image/")) {
                processImage(originalFile, payload);
            } else if (payload.contentType.startsWith("video/")) {
                processVideo(originalFile, payload);
            }

            // Update asset status
            MediaAsset asset = MediaAsset.findById(payload.assetId);
            asset.status = AssetStatus.READY;
            asset.processedAt = Instant.now();
            asset.persist();

        } finally {
            Files.deleteIfExists(originalFile); // Cleanup temp file
        }
    }

    private void processImage(Path source, MediaProcessingPayload payload) {
        for (String derivativeType : payload.derivativeTypes) {
            Dimension dimension = DERIVATIVE_SIZES.get(derivativeType);
            Path outputFile = resizeImage(source, dimension);

            String derivativeKey = buildDerivativeKey(payload, derivativeType);
            mediaStorage.upload(derivativeKey, outputFile);

            MediaDerivative derivative = new MediaDerivative();
            derivative.asset = MediaAsset.findById(payload.assetId);
            derivative.derivativeType = derivativeType;
            derivative.storageKey = derivativeKey;
            derivative.width = dimension.width;
            derivative.height = dimension.height;
            derivative.persist();
        }
    }
}
```

**Phase 3: Signed URL Generation (on-demand):**

Signed URLs generated lazily when client requests asset download (not during background processing). See `docs/diagrams/media_pipeline.mmd` Phase 7 for complete flow.

### FFmpeg Resource Isolation

Video transcoding jobs execute FFmpeg as subprocesses with strict resource controls:

```java
// Enforce max concurrent FFmpeg processes per worker
private final Semaphore ffmpegSemaphore = new Semaphore(
    config.ffmpegMaxConcurrent()
);

public void processVideo(Path source, MediaProcessingPayload payload) {
    try {
        ffmpegSemaphore.acquire();

        // Spawn FFmpeg subprocess with timeout
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg", "-i", source.toString(),
            "-c:v", "libx264", "-preset", "medium",
            "-hls_time", "6", "-hls_playlist_type", "vod",
            "output.m3u8"
        );

        Process process = pb.start();

        if (!process.waitFor(config.ffmpegTimeout(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new TimeoutException("FFmpeg timeout");
        }

    } finally {
        ffmpegSemaphore.release();
    }
}
```

**Container Resource Limits (Kubernetes):**

```yaml
containers:
- name: worker
  resources:
    limits:
      cpu: "2000m"      # Limits FFmpeg CPU usage
      memory: "4Gi"     # Prevents OOM during video encoding
```

**Kill Switch via Feature Flags:**

```java
if (!featureFlags.isEnabled("media.processing.enabled", tenantId)) {
    LOGGER.log(Level.WARNING, "Media processing disabled by feature flag");
    throw new ProcessingDisabledException("Kill switch activated");
}
```

---

<!-- anchor: monitoring-metrics -->

## 9. Monitoring & Metrics

### Prometheus Metric Catalog

All queue operations expose metrics at `/q/metrics` endpoint. Metric naming follows the pattern `<queue_name>.<metric_type>.<detail>`.

#### Queue Depth (Gauge)

```promql
media_processing_queue_depth{priority="default",tenant_id="tenant-123"}
```

**Cardinality:** `queue_name × priority × tenant_id`

**Alert Rules:**
- `media_processing_queue_depth{priority="critical"} > 100` for 2m → P1
- `media_processing_queue_depth{priority="default"} > 1000` for 5m → P2

#### Job Lifecycle (Counters)

```promql
media_processing_job_enqueued_total{priority="default",tenant_id="tenant-123"}
media_processing_job_started_total{priority="default",tenant_id="tenant-123"}
media_processing_job_completed_total{priority="default",tenant_id="tenant-123"}
media_processing_job_failed_total{priority="default",tenant_id="tenant-123",attempt="1"}
media_processing_job_exhausted_total{priority="default",tenant_id="tenant-123"}
```

**Usage Example (Failure Rate):**

```promql
rate(media_processing_job_failed_total[5m])
  /
rate(media_processing_job_started_total[5m])
```

#### Timing Histograms

```promql
media_processing_job_duration_seconds{priority="default",status="success"}
media_processing_job_wait_time_seconds{priority="default"}
```

**Buckets:** `[0.1, 0.5, 1, 2, 5, 10, 30, 60, 120, 300]` seconds

**Alert Rules:**
- `histogram_quantile(0.95, media_processing_job_duration_seconds) > 60` → P2

#### Dead Letter Queue

```promql
dead_letter_queue_depth{queue="media.processing"}
dead_letter_queue_added_total{queue="media.processing",priority="default"}
```

**Alert Rules:**
- `dead_letter_queue_depth > 10` → P2 (requires investigation)

### Metrics Backlog

Future metrics to implement (tracked in operations backlog):

| Metric | Type | Purpose | Priority |
|--------|------|---------|----------|
| `media_processing_ffmpeg_active_processes` | Gauge | Current FFmpeg subprocess count | HIGH |
| `media_processing_temp_file_size_bytes` | Gauge | Worker temp file disk usage | MEDIUM |
| `media_processing_derivative_count` | Counter | Derivatives generated by type | LOW |
| `payouts_batch_ach_transfer_amount` | Histogram | Payout amount distribution | MEDIUM |
| `reports_export_file_size_bytes` | Histogram | Report file size distribution | LOW |

### Grafana Dashboards

**Dashboard: Background Job Health** (`/d/background-jobs`)

- **Panel 1:** Queue depth by priority (stacked area chart)
- **Panel 2:** Job throughput (success/failed rate, 5m window)
- **Panel 3:** DLQ depth over time
- **Panel 4:** Job duration p50/p95/p99 by priority
- **Panel 5:** Retry attempt heatmap
- **Panel 6:** Worker pod CPU/memory usage
- **Panel 7:** FFmpeg active processes (when metric implemented)

**Dashboard: Media Pipeline** (`/d/media-pipeline`)

- **Panel 1:** Upload → processing → ready funnel
- **Panel 2:** Processing time by content type (image vs video)
- **Panel 3:** Derivative generation success rate
- **Panel 4:** R2 upload/download bandwidth
- **Panel 5:** Feature flag kill switch status

### Alert Definitions

**Critical Alerts (P1):**

```yaml
# Prometheus alerting rules
groups:
- name: background_jobs_critical
  interval: 30s
  rules:
  - alert: CriticalQueueBacklog
    expr: media_processing_queue_depth{priority="critical"} > 100
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "CRITICAL priority queue backlog"

  - alert: DLQAccumulating
    expr: rate(dead_letter_queue_added_total[5m]) > 0.1
    for: 5m
    labels:
      severity: critical
    annotations:
      summary: "Dead letter queue accumulating failures"
```

**Warning Alerts (P2):**

```yaml
- alert: HighJobFailureRate
  expr: |
    rate(media_processing_job_failed_total[5m])
    /
    rate(media_processing_job_started_total[5m]) > 0.05
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Job failure rate exceeds 5%"
```

---

<!-- anchor: circuit-breaker-strategy -->

## 10. Circuit Breaker Strategy

### Objectives

Circuit breakers prevent cascading failures when background jobs depend on unstable downstream systems (Cloudflare R2, SMTP/SES, Stripe, reporting storage). The goals are to:

- **Fail fast** once an external dependency is degraded instead of saturating worker threads with timeouts.
- **Protect queue SLAs** by requeueing affected jobs with controlled backoff while unaffected queues continue processing.
- **Surface actionable telemetry** (breaker state, tripped resource, tenant impact) to the operations dashboards/runbooks for rapid remediation.

### Implementation Pattern

All outbound adapters inside job handlers use Quarkus SmallRye Fault Tolerance (`@CircuitBreaker` + `@Timeout`). Each breaker is tuned per integration and publishes metrics via MicroProfile to Prometheus (`ft.<breaker>.circuitbreaker.open.total`).

```java
@ApplicationScoped
public class MediaStorageClient {

    @Inject
    StorageConfig config;

    @CircuitBreaker(
        requestVolumeThreshold = 20,  // minimum calls before evaluation
        failureRatio = 0.5,           // 50% failures trip the breaker
        delay = 30000,                // OPEN state wait (30s)
        successThreshold = 3          // HALF_OPEN → CLOSED after 3 successes
    )
    @Timeout(value = 5, unit = ChronoUnit.SECONDS)
    public URI generateSignedUrl(String key, Duration expiry) {
        return r2Client.createSignedUrl(key, expiry, config.region());
    }
}
```

**Key behaviors:**

- **Failure signals:** network timeouts, HTTP ≥500, bucket-level throttling, payload validation errors.
- **OPEN state handling:** The worker throws a retryable `CircuitBreakerOpenException`; `handleFailure` schedules retry respecting queue-specific exponential backoff.
- **HALF_OPEN probing:** Jobs allowed through during HALF_OPEN include extra structured logs (`circuitBreakerState=half_open`) for traceability.
- **Fallbacks:** For non-critical workloads (LOW/BULK queues) a fallback handler writes a `pending_external_dependency` status so ops can manually resume once the dependency recovers.

### Breaker Catalog

| Component | Protected Dependency | Trip Condition | Retry Strategy | Notes |
|-----------|---------------------|----------------|----------------|-------|
| `MediaStorageClient` | Cloudflare R2 signed URL + PUT/GET | ≥50% failures across last 20 calls or 5 consecutive timeouts | Retry same tenant job with exponential backoff capped at 5m | Kill switch (`media.processing.enabled`) also disables FFmpeg spawn to avoid wasted CPU |
| `ReportExportPublisher` | Object storage (R2) + email dispatch | Average latency > 8s or 40% 5xx responses within 60s | Jobs downgraded to LOW priority on first trip to preserve API throughput | Generates audit event `report_export.delayed_due_to_breaker` |
| `StripeWebhookProcessor` | Stripe API for charge lookup | 3 HTTP 429/5xx responses in 30s sliding window | Retries respect Stripe-recommended exponential schedule (1s → 32s) | Breaker state mirrored to `/ops/stripe` dashboard panel |
| `PayoutBatchWriter` | ACH provider (Dwolla/Stripe) | Connection failures >30% over 10 requests | Retries stop after 3 attempts → job moved to DLQ + PagerDuty notification | Manual remediation requires finance approval before replay |

### Failure Handling Flow

1. Worker detects breaker OPEN → `handleFailure` records `last_error='circuit_open:<dependency>'`.
2. Job remains in same queue but `run_at` delayed according to retry policy; metrics emit `background_job_circuit_open_total{queue,dependency}`.
3. If breaker remains OPEN past configurable SLA (e.g., 15 minutes), Ops escalates to manual remediation (platform_ops §5) and may pause queue.
4. After dependency recovers (breaker CLOSED), workers automatically drain backlog; resumed jobs log `circuitBreakerRecovered=true`.

### Monitoring Hooks & Alerts

- Prometheus scrapes `ft_*` metrics plus custom gauge `queue_circuit_breaker_state{queue,dependency}` (0=CLOSED, 1=OPEN, 0.5=HALF_OPEN).
- Grafana `Background Job Health` dashboard adds panel visualizing breaker states and correlating queue depth spikes.
- Alert rule:

```yaml
- alert: CircuitBreakerOpenTooLong
  expr: queue_circuit_breaker_state == 1
        and on(queue, dependency)
        max_over_time(queue_circuit_breaker_state[10m]) == 1
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Circuit breaker stuck OPEN for {{ $labels.queue }} ({{ $labels.dependency }})"
    runbook: "docs/architecture/platform_ops.md#manual-intervention-procedures"
```

Ops engineers use the existing manual intervention procedures to either scale workers down, flip feature flags, or reroute traffic once the alert fires.

---

<!-- anchor: references-related-documents -->

## 11. References & Related Documents

### Architecture Documents

- **[Operational Architecture](./04_Operational_Architecture.md)** (Section 3.6: Background Processing)
- **[Blueprint Foundation](./01_Blueprint_Foundation.md)** (Section 4.0: Media & Job Components)
- **[System Structure](./02_System_Structure_and_Data.md)** (Container Diagram: Worker Pods)
- **[Tenant Isolation](./tenant_isolation.md)** (Section 3: TenantContext Management for background jobs)

### Diagrams

- **[Media Pipeline Flow](../diagrams/media_pipeline.mmd)** (comprehensive sequence diagram)
- **[POS Offline Sync](../diagrams/pos-offline.mmd)** (offline queue replay workflow)

### Operations Runbooks

- **[Job Operations Runbook](../operations/job_runbook.md)** (monitoring, scaling, troubleshooting)
- **[Platform Operations](./platform_ops.md)** (HPA configurations, runbook procedures)

### Implementation References

- **MediaProcessingJobService:** `src/main/java/villagecompute/storefront/services/jobs/MediaProcessingJobService.java`
- **MediaProcessingWorker:** `src/main/java/villagecompute/storefront/jobs/MediaProcessingWorker.java`
- **Queue Schema Migration:** `migrations/src/main/resources/db/V20260115__create_background_job_tables.sql`

### Standards & Clarifications

- **[Java Project Standards](../java-project-standards.adoc)** (Section 8: Background Job Conventions)
- **Clarification 2:** DelayedJob pattern over Redis-backed queues
- **Clarification 3:** FFmpeg subprocess isolation requirements
- **Clarification 4:** Job payload versioning for schema evolution

### Risk Register

- **RISK-003:** Background job worker starvation during media upload spikes (mitigated by HPA + priority queues)
- **RISK-004:** Cross-tenant job processing due to TenantContext leak (mitigated by mandatory context seeding + RLS)

---

**Document Maintainers:** Architecture Team
**Review Cadence:** After each ADR affecting async processing or job framework
**Next Review:** Q2 2026 (post-media pipeline GA evaluation)
