# ADR-006: Background Job Architecture & DelayedJob Pattern

**Status:** Accepted
**Date:** 2026-01-18
**Decision Makers:** Architecture Team, Platform Engineering Team
**Related ADRs:** [ADR-001 (Multi-Tenancy)](ADR-001-tenancy.md), [ADR-005 (Media Pipeline)](ADR-005-media-pipeline.md)

---

## Context

Village Storefront requires asynchronous processing for numerous operational workflows that cannot block HTTP request/response cycles:

- **Media Processing**: Image resizing, video transcoding (30s-5m latency tolerance)
- **Payout Processing**: Consignment payout calculations, ACH transfers (time-sensitive, <5s target)
- **Report Generation**: CSV/PDF exports for sales reports, inventory summaries (<30s target)
- **Transactional Emails**: Order confirmations, password resets (<1s target)
- **Stripe Webhooks**: Payment event processing (idempotency critical, <1s target)
- **Bulk Operations**: Data migrations, session cleanup (best-effort)

### Technical Constraints

1. **No Redis Constraint**: Platform architecture prohibits Redis to minimize infrastructure complexity and operational burden (no cache invalidation complexity, no cluster management, no separate backup/recovery procedures)
2. **Multi-Tenancy Requirements**: All jobs must be tenant-scoped for isolation and quota enforcement (see ADR-001)
3. **Existing PostgreSQL Infrastructure**: PostgreSQL 17 already deployed with high availability setup
4. **Transactional Integrity**: Job enqueue must be atomic with business operations (e.g., enqueue payout job + mark order paid in single transaction)
5. **Priority-Based Execution**: Critical jobs (payment webhooks) must preempt low-priority jobs (analytics aggregation)
6. **Kubernetes Deployment**: Worker pods must auto-scale based on queue depth

### Business Requirements

- **SLA Commitments**: Different job types have different latency targets (1s for critical webhooks, 5m for videos)
- **Failure Recovery**: Transient failures (network issues, rate limits) must retry automatically
- **Operational Visibility**: Operations team must monitor queue depth, failure rates, DLQ growth
- **Cost Efficiency**: Self-managed infrastructure preferred over cloud job services (AWS SQS, Google Cloud Tasks)

---

## Decision

We will implement a **database-backed background job framework** using the DelayedJob pattern with the following design:

### 1. Architecture Components

- **Job Queue Tables**: One PostgreSQL table per functional domain (e.g., `media_processing_jobs`, `payout_batch_jobs`, `email_jobs`)
- **Priority-Based Polling**: Workers poll queues using `SELECT FOR UPDATE SKIP LOCKED` with `ORDER BY priority ASC, run_at ASC`
- **Worker Pods**: Dedicated Kubernetes Deployment with `@Scheduled` polling (every 3 seconds)
- **Job Payload Versioning**: JSONB payloads with explicit version field for schema evolution
- **Retry Strategy**: Exponential backoff with configurable max attempts per priority level
- **Dead Letter Queue (DLQ)**: Failed jobs exceeding max attempts moved to separate table for manual investigation

### 2. Queue Table Schema

All job tables follow this template (example: `media_processing_jobs`):

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
CREATE INDEX idx_media_jobs_worker_claim
    ON media_processing_jobs(status, run_at)
    WHERE status = 'pending';

CREATE INDEX idx_media_jobs_lock_expiry
    ON media_processing_jobs(locked_until)
    WHERE status = 'locked' AND locked_until IS NOT NULL;

-- RLS policy (see ADR-001)
ALTER TABLE media_processing_jobs ENABLE ROW LEVEL SECURITY;
CREATE POLICY media_jobs_isolation_policy ON media_processing_jobs
    FOR ALL
    USING (tenant_id = get_current_tenant_id());
```

### 3. Priority System

Five priority levels with distinct SLA targets and queue capacity limits:

| Priority  | Order | Target Latency | Max Queue Depth | Max Attempts | Use Cases |
|-----------|-------|----------------|-----------------|--------------|-----------|
| CRITICAL  | 0     | < 1s           | 100             | 5            | Payment webhooks, order receipts |
| HIGH      | 1     | < 5s           | 500             | 3            | Payout batches, low-stock alerts |
| DEFAULT   | 2     | < 30s          | 1000            | 3            | Image processing, report exports |
| LOW       | 3     | < 2m           | 5000            | 3            | Video transcoding, analytics |
| BULK      | 4     | Best-effort    | 20000           | 0            | Data migrations, archive exports |

**Priority Assignment Guidelines:**
- CRITICAL: User-facing operations requiring immediate feedback
- HIGH: Time-sensitive business logic affecting money or inventory
- DEFAULT: Standard async work with moderate latency tolerance
- LOW: Optimization tasks tolerating minutes of delay
- BULK: Batch operations tolerating hours of delay (no retries, fail to DLQ immediately)

### 4. Worker Polling & Execution

Workers use Quarkus `@Scheduled` annotations to poll queues:

```java
@ApplicationScoped
public class MediaProcessingWorker {

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
                TenantContext.clear(); // MUST clear to prevent context leak
            }
        }
    }
}
```

**Job Claim Query** (optimized with `SKIP LOCKED` to prevent worker contention):

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

### 5. Retry Strategy & Dead Letter Queue

**Exponential Backoff Formula:**
```
backoff_delay = initial_delay * (backoff_multiplier ^ (attempts - 1))
backoff_delay = MIN(backoff_delay, max_delay)
```

**Retry Policy by Priority:**

| Priority  | Initial Delay | Max Delay | Backoff Multiplier | Max Attempts |
|-----------|---------------|-----------|---------------------|--------------|
| CRITICAL  | 500ms         | 30s       | 1.5x                | 5            |
| HIGH      | 1s            | 5m        | 2.0x                | 3            |
| DEFAULT   | 1s            | 5m        | 2.0x                | 3            |
| LOW       | 1s            | 5m        | 2.0x                | 3            |
| BULK      | N/A           | N/A       | N/A                 | 0 (fail immediately) |

**Dead Letter Queue Schema:**

```sql
CREATE TABLE dead_letter_queue (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID REFERENCES tenants(id),

    -- Original job context
    queue_name        VARCHAR(100) NOT NULL,
    job_type          VARCHAR(100) NOT NULL,
    priority          VARCHAR(20) NOT NULL,
    original_job_id   UUID,

    -- Failure context
    payload           JSONB NOT NULL,
    attempts          INTEGER NOT NULL,
    last_error        TEXT,
    stack_trace       TEXT,

    -- Metadata
    owning_module     VARCHAR(100),  -- e.g., 'media.processing', 'payouts.batch'
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at       TIMESTAMPTZ,
    resolution_notes  TEXT
);

CREATE INDEX idx_dlq_unresolved ON dead_letter_queue(created_at) WHERE resolved_at IS NULL;
```

### 6. Horizontal Pod Autoscaling (HPA)

Workers scale based on queue depth metrics exported to Prometheus:

**HPA Configuration:**
- **Min Replicas**: 2 (high availability)
- **Max Replicas**: 10 (burst capacity)
- **Target Metric**: `media_processing_queue_depth` (average 100 jobs per pod)
- **Scale-Up**: 50% increase every 60s (aggressive for queue drain)
- **Scale-Down**: 1 pod every 60s after 300s stabilization window (prevent thrashing)

**Metrics Exported:**
- `{queue_name}.queue.depth{priority}` - Current backlog per priority
- `{queue_name}.job.duration{priority,status}` - Histogram of processing time
- `{queue_name}.job.failed{priority,attempt}` - Counter of failures by attempt number
- `{queue_name}.dlq.depth` - Count of unresolved DLQ entries

---

## Rationale

### Why Database-Backed Jobs (vs. Redis Queue)?

**Rejected Alternative: Redis Queue (Sidekiq, Bull, etc.)**
- **Additional Infrastructure**: Requires Redis cluster with replication/persistence, backup strategy, monitoring
- **No Transaction Support**: Cannot atomically enqueue job + update database in single transaction (two-phase commit complexity)
- **Operational Burden**: Redis cluster management, memory sizing, eviction policies, failover procedures
- **Tenant Isolation Complexity**: Requires application-layer tenant filtering (no RLS equivalent)
- **Cost**: AWS ElastiCache (Redis) ~$50-200/month vs. PostgreSQL already deployed

**Rejected Alternative: AWS SQS / Google Cloud Tasks**
- **Vendor Lock-In**: Proprietary APIs, difficult migration path
- **Cost**: SQS charges per request ($0.40 per million = $400 for 1B requests/month)
- **Latency**: Network round-trip to cloud service adds 50-100ms minimum
- **Multi-Tenancy**: No native tenant isolation, requires application-layer filtering
- **Observability Gaps**: Cloud metrics separate from application metrics (Prometheus)

**Chosen Solution Benefits:**
- ✅ **No Additional Infrastructure**: PostgreSQL already deployed with HA setup, no new systems to manage
- ✅ **Transactional Enqueue**: Job insertion atomic with business logic updates (ACID guarantees)
- ✅ **Tenant Isolation**: PostgreSQL RLS policies enforce tenant scoping at database level (see ADR-001)
- ✅ **Operational Simplicity**: Single backup/recovery procedure, unified monitoring with application database
- ✅ **Cost Efficiency**: Zero marginal infrastructure cost (PostgreSQL handles workload)
- ✅ **Query Flexibility**: SQL queries for job status, DLQ inspection, historical analysis

### Why Separate Queue Tables Per Domain (vs. Single Jobs Table)?

**Rejected Alternative: Single `background_jobs` Table**
- **Head-of-Line Blocking**: Slow video transcoding jobs block fast payment webhooks
- **Index Contention**: All workers compete for same index (hot spot on `status, run_at` index)
- **Operational Complexity**: Cannot independently tune queue capacity, worker pools per job type
- **Monitoring Noise**: Prometheus metrics aggregate unrelated job types, obscuring domain-specific SLA breaches

**Chosen Solution Benefits:**
- ✅ **Queue Isolation**: Slow media jobs cannot block time-sensitive payout jobs
- ✅ **Independent Scaling**: Tune worker pools separately (5 media workers, 2 payout workers)
- ✅ **Index Partitioning**: Each table has dedicated indexes, reducing contention
- ✅ **Domain-Specific Metrics**: Prometheus metrics scoped to job type for precise SLA monitoring

### Why Polling (vs. LISTEN/NOTIFY Push)?

**Rejected Alternative: PostgreSQL LISTEN/NOTIFY**
- **Connection Management**: Requires persistent database connection per worker (connection pool exhaustion risk)
- **State Synchronization**: Workers must handle notification loss during restarts/network issues
- **Scaling Complexity**: NOTIFY broadcasts to all listeners (inefficient for large worker pools)
- **Kubernetes Challenges**: Pod restarts lose notification subscriptions, requires reconnection logic

**Chosen Solution Benefits:**
- ✅ **Stateless Workers**: No persistent connections, workers can restart without missing jobs
- ✅ **Horizontal Scaling**: Add workers without notification broadcast overhead
- ✅ **Simple Failure Model**: If worker crashes, next poll cycle reclaims stale locks
- ✅ **Batch Efficiency**: `LIMIT 10` batch claim amortizes query overhead over multiple jobs

### Why `SELECT FOR UPDATE SKIP LOCKED` (vs. Application-Level Locking)?

**Rejected Alternative: Application-Layer Locking with Version Numbers**
- Requires optimistic locking with retry logic in application code
- Race conditions possible if two workers claim same job simultaneously
- Complex failure recovery (what if worker crashes after claim but before update?)

**Chosen Solution Benefits:**
- ✅ **Database-Level Atomicity**: Row lock acquired atomically, no race conditions
- ✅ **Non-Blocking**: `SKIP LOCKED` prevents workers from waiting on locked rows
- ✅ **Automatic Deadlock Prevention**: PostgreSQL handles lock contention
- ✅ **Simpler Code**: No application-level locking logic required

---

## Consequences

### Positive Consequences

1. **Transactional Integrity**: Job enqueue atomic with business operations (no orphaned jobs or inconsistent state)
2. **Infrastructure Simplification**: No Redis cluster to manage, monitor, backup, or scale
3. **Tenant Isolation Guarantees**: PostgreSQL RLS enforces tenant scoping at database level (see ADR-001)
4. **Operational Observability**: Unified monitoring with application database, SQL queries for troubleshooting
5. **Cost Efficiency**: Zero marginal infrastructure cost vs. ~$50-200/month for Redis or $400/month for SQS at scale
6. **Horizontal Scalability**: Worker HPA automatically adjusts capacity based on queue depth metrics
7. **Failure Recovery**: Automatic retry with exponential backoff, DLQ captures exhausted jobs for investigation

### Negative Consequences & Mitigations

1. **Database Load Increase**
   - **Issue**: Polling queries add read load to PostgreSQL (workers poll every 3 seconds)
   - **Mitigation**:
     - Dedicated indexes for worker claim query (`idx_media_jobs_worker_claim` enables index-only scan)
     - Batch claim (`LIMIT 10`) amortizes query overhead over multiple jobs
     - Separate queue tables prevent cross-domain index contention
     - Monitor `pg_stat_user_tables` for sequential scan hotspots
   - **Measured Impact**: At 10 worker pods × 3s poll interval = ~200 queries/minute (negligible vs. 10,000+ application queries/minute)

2. **Polling Latency**
   - **Issue**: Jobs wait up to 3 seconds before worker polls (not true real-time)
   - **Mitigation**:
     - Poll interval tuned per priority (CRITICAL: 1s, DEFAULT: 3s, BULK: 10s)
     - For <1s latency requirements, consider in-process async (CompletableFuture) instead of jobs
     - SLA targets account for polling overhead (e.g., CRITICAL target is 1s including poll delay)
   - **Acceptable Trade-Off**: 3s delay acceptable for all identified use cases (media processing, payouts, reports)

3. **Lock Timeout Risk**
   - **Issue**: If worker crashes during job execution, row remains locked until `locked_until` expires (default 5 minutes)
   - **Mitigation**:
     - `locked_until` timestamp prevents indefinite locks (stale locks reclaimed on next poll)
     - Worker health checks kill unresponsive pods proactively (Kubernetes liveness probe)
     - Jobs idempotent to tolerate duplicate execution (e.g., media processing checks `media_assets.status` before processing)

4. **Queue Table Growth**
   - **Issue**: Completed jobs accumulate in queue tables, degrading index performance over time
   - **Mitigation**:
     - Scheduled cleanup job deletes completed jobs older than 30 days (retention policy)
     - Partition queue tables by `created_at` month (PostgreSQL 12+ declarative partitioning)
     - Monitor table size with `pg_total_relation_size()` alerts (warn at >10GB)

5. **DLQ Manual Intervention Requirement**
   - **Issue**: Jobs in DLQ require manual investigation and retry (no automatic recovery)
   - **Mitigation**:
     - Prometheus alert fires when `dlq.depth > 10` (P2 alert)
     - Admin UI provides DLQ browser with retry button (one-click manual retry)
     - Runbook documents common DLQ scenarios and resolutions (see job_runbook.md)

6. **Payload Versioning Overhead**
   - **Issue**: Schema evolution requires version checks in handlers, payload migration logic
   - **Mitigation**:
     - JSONB allows additive changes without version bump (new optional fields)
     - Version mismatch throws `PayloadVersionMismatchException` → job moves to DLQ (prevents silent failures)
     - DLQ inspection reveals schema migration issues immediately

---

## Implementation References

### Code Locations
- **Job Service**: `villagecompute.storefront.services.jobs.config.PriorityJobQueue`
- **Worker Scheduler**: `villagecompute.storefront.jobs.workers.MediaProcessingWorker`
- **Job Handlers**: `villagecompute.storefront.jobs.handlers.*` (e.g., `MediaProcessingJobHandler`, `PayoutBatchJobHandler`)
- **Tenant Context**: `villagecompute.storefront.util.TenantContext` (critical for tenant isolation)

### Runbooks
- [Background Job Operations Runbook](../operations/job_runbook.md) - Failure investigation, DLQ management, scaling guidance
- [Media Pipeline Runbook](../operations/media_runbook.md) - Media-specific job monitoring and failure scenarios

### Diagrams
- [Media Processing Flow](../diagrams/media_pipeline.mmd) - Complete job lifecycle from enqueue to completion
- [POS Offline Sync Flow](../diagrams/pos-offline.mmd) - Background sync job patterns

### Configuration
- **Worker Deployment**: `k8s/base/deployment-workers.yaml`
- **Application Properties**: `application.properties` (*.processing.dispatch-interval, *.worker.batch-size)
- **Database Migrations**: `migrations/V2.0__background_jobs_schema.sql`

### Monitoring
- **Grafana Dashboard**: "Village Storefront > Background Job Health"
- **Key Metrics**:
  - `{queue_name}.queue.depth{priority}` - Current backlog per priority
  - `histogram_quantile(0.95, {queue_name}.job.duration_bucket{priority})` - p95 processing latency
  - `rate({queue_name}.job.failed[5m])` - Failure rate
  - `{queue_name}.dlq.depth` - Unresolved DLQ entries
- **Alerts**:
  - `JobQueueBacklog` - Queue depth exceeds threshold (P2)
  - `JobFailureSpike` - Failure rate >5% for 10 minutes (P2)
  - `JobDLQGrowth` - DLQ depth >10 (P2, requires investigation)

---

## Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2026-01-18 | 1.0 | Initial ADR documenting implemented architecture | Architecture Team |

---

## References

- [ADR-001: Multi-Tenancy & Tenant Isolation Strategy](ADR-001-tenancy.md)
- [ADR-005: Media Pipeline Architecture & FFmpeg Worker Isolation](ADR-005-media-pipeline.md)
- [Background Jobs Architecture Documentation](../architecture/background_jobs.md)
- [PostgreSQL Row-Level Security Documentation](https://www.postgresql.org/docs/17/ddl-rowsecurity.html)
- [Quarkus Scheduler Guide](https://quarkus.io/guides/scheduler)
- [Delayed Job Pattern (Rails)](https://github.com/collectiveidea/delayed_job) - Inspiration for database-backed job pattern
