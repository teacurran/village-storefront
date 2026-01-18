# ADR-005: Media Pipeline Architecture & FFmpeg Worker Isolation

**Status:** Accepted
**Date:** 2026-01-18
**Decision Makers:** Architecture Team, Platform Operations Team
**Related ADRs:** [ADR-001 (Multi-Tenancy)](ADR-001-tenancy.md), [ADR-006 (Background Jobs)](ADR-006-background-jobs.md)

---

## Context

Village Storefront's multi-tenant ecommerce platform requires robust media processing capabilities to handle product images and videos across thousands of tenant stores. Each tenant needs:

- **Product Image Management**: Upload, resize to multiple dimensions (thumbnail, small, medium, large), and serve optimized derivatives
- **Product Video Processing**: Upload, transcode to multiple resolutions (720p, 480p, 360p), package for HLS adaptive streaming
- **Digital Product Delivery**: Secure time-limited download URLs with access audit trails
- **Tenant Isolation**: All media storage must be tenant-scoped to prevent cross-tenant access
- **Quota Enforcement**: Per-tenant storage limits (default 10GB) with proactive warnings at 80% usage

### Technical Constraints

1. **FFmpeg Security Risk**: FFmpeg runs arbitrary decoder operations on untrusted user-uploaded media files, making it a security risk if run in-process with the main application
2. **Memory Intensive**: Video transcoding can consume 2-4GB RAM per concurrent job
3. **No Redis Constraint**: Platform architecture prohibits Redis, requiring database-backed job queues (see ADR-006)
4. **Cost Sensitivity**: Cloud transcoding services (AWS MediaConvert, Cloudflare Stream) would cost ~$0.015/minute, which at scale (1000 tenants × 50 videos/month) = $750/month vs. self-hosted $0
5. **Tenant Data Isolation**: All storage paths must be prefixed with tenant_id to enforce isolation (see ADR-001)

### Business Requirements

- **Image Processing Latency**: p95 < 30 seconds (customers expect product images available immediately)
- **Video Processing Latency**: p95 < 120 seconds (acceptable delay for video product showcases)
- **Availability**: 99.5% uptime for upload/download endpoints (media processing can degrade gracefully)
- **Scalability**: Support burst uploads during sales events (10x baseline)

---

## Decision

We will implement a **separated worker pod architecture** for media processing with the following design:

### 1. Architecture Components

- **API Service Pods**: Handle upload negotiation and presigned URL generation (no media processing)
- **Background Worker Pods**: Dedicated pods with FFmpeg installed, poll job queue, execute media processing
- **Cloudflare R2 Storage**: S3-compatible object storage for all media assets (tenant-scoped paths)
- **Database-Backed Job Queue**: PostgreSQL table `media_processing_jobs` with priority-based polling (see ADR-006)
- **MediaStorageClient**: Abstraction layer for R2 operations (presigned URLs, upload/download with tenant path enforcement)

### 2. Processing Pipeline

**Upload Flow:**
1. Client requests presigned upload URL from API service (`POST /media/upload/negotiate`)
2. API generates presigned R2 URL with tenant-scoped path: `{tenantId}/media/{assetType}/{assetId}/original/{filename}`
3. Client uploads directly to R2 (bypasses API, reduces bandwidth costs)
4. Client calls completion callback (`POST /media/{assetId}/complete`)
5. API enqueues background job to `media_processing_jobs` table (priority: DEFAULT for images, LOW for videos)

**Processing Flow:**
1. Worker pod polls queue every 3 seconds via `SELECT FOR UPDATE SKIP LOCKED`
2. Worker claims job, sets `TenantContext` from job payload (critical for RLS enforcement)
3. Worker downloads original from R2 to temp file (`/tmp/media-{jobId}-original`)
4. **Image Processing**: Thumbnailator library generates 4 derivatives (thumbnail, small, medium, large) with bicubic interpolation
5. **Video Processing**: FFmpeg subprocess transcodes to H.264/AAC, packages HLS playlists (720p, 480p, 360p with 6s segments)
6. Worker uploads derivatives to R2 with tenant-scoped paths
7. Worker updates `media_assets.status='ready'` and `media_quotas.used_bytes` atomically
8. Worker deletes temp files and clears `TenantContext`

**Download Flow:**
1. Client requests download URL from API (`GET /media/{assetId}/download?derivative=medium`)
2. API queries `media_assets` + `media_derivatives` with tenant_id filter (RLS enforced)
3. API generates signed R2 URL with 24-hour expiry
4. API logs access to `media_access_logs` table (audit trail for digital products)
5. Client downloads directly from R2 using signed URL

### 3. FFmpeg Isolation Strategy

- **Separate Worker Pods**: FFmpeg runs in dedicated Kubernetes pods isolated from API service
- **Resource Limits**: Workers configured with CPU: 2000m (2 cores), Memory: 4Gi limits
- **Concurrency Control**: Semaphore limits 2 concurrent FFmpeg processes per worker pod (prevents memory exhaustion)
- **Timeout Enforcement**: FFmpeg subprocess killed after 300 seconds (5 minutes)
- **Security Hardening**: Worker pods run as non-root user, read-only root filesystem (except /tmp), no privilege escalation

### 4. Horizontal Scaling with HPA

Kubernetes Horizontal Pod Autoscaler configured with:
- **Min Replicas**: 2 (high availability)
- **Max Replicas**: 20 (burst capacity)
- **CPU Target**: 70% average utilization across pods
- **Custom Metric**: `media_processing_queue_depth` (target 100 jobs per pod) - requires Prometheus Adapter
- **Scale-Up Rate**: 50% increase or +2 pods per 60 seconds (whichever is greater)
- **Scale-Down Rate**: 1 pod per 60 seconds after 300-second stabilization window

### 5. Retry & Failure Handling

- **Retry Policy**: 3 attempts with exponential backoff (1s, 2s, 4s)
- **Dead Letter Queue**: Jobs exceeding max attempts moved to `dead_letter_queue` table for manual investigation
- **FFmpeg Crash Detection**: Worker catches exit code ≠ 0 or timeout, retries up to max attempts
- **Poison Pill Prevention**: Jobs failing 3 consecutive times flagged for manual review (likely corrupt file)

---

## Rationale

### Why Separate Worker Pods (vs. In-Process FFmpeg)?

**Rejected Alternative: In-Process FFmpeg**
- **Security Risk**: FFmpeg executes arbitrary decoders on untrusted input, making it a prime target for exploits (CVE-2016-1897, CVE-2020-22046, etc.)
- **Resource Contention**: Video transcoding can spike CPU/memory, starving API request threads
- **Deployment Coupling**: FFmpeg binary size (~100MB) would increase API pod image size, slowing deployments
- **Blast Radius**: FFmpeg crash would crash entire API pod, affecting all tenants

**Chosen Solution Benefits:**
- ✅ **Blast Radius Containment**: FFmpeg crash only affects worker pod, API service remains available
- ✅ **Independent Scaling**: Worker pods scale based on queue depth, API pods scale based on request rate
- ✅ **Security Isolation**: Worker pods can run with stricter securityContext, drop unnecessary capabilities
- ✅ **Cost Optimization**: API pods can use smaller instance types (no FFmpeg overhead)

### Why Cloudflare R2 (vs. Local Disk or Alternative Cloud Storage)?

**Rejected Alternatives:**
- **Local Disk**: No persistence across pod restarts, no multi-AZ durability, expensive disk PVCs
- **AWS S3**: 3x cost of R2 ($0.023/GB vs. $0.015/GB), egress charges for downloads
- **MinIO (self-hosted)**: Additional infrastructure to manage, no CDN integration, scaling complexity

**Chosen Solution Benefits:**
- ✅ **S3 Compatibility**: Drop-in replacement for AWS SDK (no proprietary APIs)
- ✅ **Cost**: 35% cheaper than AWS S3 storage, zero egress fees
- ✅ **Durability**: 11 nines durability, geo-redundant by default
- ✅ **CDN Integration**: R2 custom domains integrate with Cloudflare CDN for image serving

### Why Database-Backed Jobs (vs. Redis Queue or Cloud Queue Service)?

See [ADR-006: Background Job Architecture](ADR-006-background-jobs.md) for comprehensive rationale.

**Summary:**
- ✅ **No Redis**: Aligns with "No Redis" platform constraint
- ✅ **Transactional Enqueue**: Job enqueue + asset metadata insert in single transaction
- ✅ **Tenant Isolation**: PostgreSQL RLS policies enforce tenant scoping
- ✅ **Operational Simplicity**: No additional infrastructure to manage

### Why Presigned URLs (vs. Proxy Through API)?

**Rejected Alternative: Proxy Through API**
- Would require API pods to stream multi-gigabyte video files, consuming bandwidth and memory
- No built-in CDN caching (Cloudflare R2 + custom domain provides free CDN)
- Single point of failure (API downtime = no downloads)

**Chosen Solution Benefits:**
- ✅ **Bandwidth Savings**: Client downloads directly from R2, bypassing API pods
- ✅ **CDN Acceleration**: R2 custom domains integrate with Cloudflare CDN edge caching
- ✅ **Security**: Signed URLs expire after 24 hours, cannot be shared indefinitely
- ✅ **Auditability**: API logs all signed URL generation to `media_access_logs` table before issuing URL

---

## Consequences

### Positive Consequences

1. **Enhanced Security Posture**: FFmpeg isolation prevents exploit propagation to API service
2. **Cost Efficiency**: Self-hosted transcoding saves ~$750/month vs. cloud services at 1000-tenant scale
3. **Horizontal Scalability**: Worker HPA automatically adjusts capacity during traffic bursts
4. **Operational Visibility**: Prometheus metrics + Grafana dashboards track queue depth, processing latency, failure rates
5. **Tenant Isolation Guarantees**: R2 path prefixing + RLS policies prevent cross-tenant media access
6. **Graceful Degradation**: API remains available even if all worker pods crash (jobs queue until recovery)

### Negative Consequences & Mitigations

1. **FFmpeg Maintenance Burden**
   - **Issue**: Must track FFmpeg CVEs, update base images, test codec compatibility
   - **Mitigation**: Pin FFmpeg version in worker Dockerfile, subscribe to security mailing list, quarterly upgrade cadence

2. **Video Processing Latency Variance**
   - **Issue**: Large video files (>500MB) can exceed 5-minute timeout, causing job failures
   - **Mitigation**: Client-side validation rejects files >1GB, communicate video duration limits in UI (max 10 minutes at 1080p)

3. **R2 Vendor Lock-In**
   - **Issue**: Migrating to different storage provider requires code changes to MediaStorageClient
   - **Mitigation**: MediaStorageClient uses S3-compatible API (portable to AWS S3, MinIO, Wasabi), storage_key paths are provider-agnostic

4. **Database Queue Scaling Ceiling**
   - **Issue**: PostgreSQL queue polling may bottleneck at >10,000 jobs/second
   - **Mitigation**: Separate tables per queue type (media_processing_jobs, payout_jobs, etc.) prevent head-of-line blocking; monitor `pg_stat_user_tables` for sequential scan hotspots

5. **Worker Pod Startup Latency**
   - **Issue**: HPA scale-up takes 60-90 seconds (image pull + container start + readiness probe)
   - **Mitigation**: Pre-warm min replicas (2 baseline), proactive scaling before known events (sales, launches), image registry caching

---

## Implementation References

### Code Locations
- **MediaResource**: `villagecompute.storefront.api.rest.MediaResource` (API endpoints)
- **MediaJobService**: `villagecompute.storefront.services.MediaJobService` (job orchestration)
- **MediaProcessor**: `villagecompute.storefront.media.MediaProcessor` (image/video processing logic)
- **MediaStorageClient**: `villagecompute.storefront.media.MediaStorageClient` (R2 abstraction)

### Runbooks
- [Media Pipeline Operational Runbook](../operations/media_runbook.md) - Scaling procedures, failure scenarios, kill switches
- [Job Processing Runbook](../operations/job_runbook.md) - Queue monitoring, DLQ management, worker HPA tuning

### Diagrams
- [Media Flow Sequence Diagram](../diagrams/media_pipeline.mmd) - Complete upload → process → download flow
- [Component Architecture](../diagrams/component_overview.puml) - Worker pod topology

### Configuration
- **Worker Deployment**: `k8s/base/deployment-workers.yaml`
- **HPA Configuration**: `k8s/base/deployment-workers.yaml` (spec.autoscaling)
- **Application Properties**: `application.properties` (media.processing.*, media.storage.r2.*)

### Monitoring
- **Grafana Dashboard**: "Village Storefront > Media Pipeline Health"
- **Key Metrics**:
  - `media_queue_depth{priority}` - Current backlog per priority level
  - `histogram_quantile(0.95, media_job_duration_seconds_bucket{type})` - p95 processing latency
  - `rate(media_job_failed_total{type}[5m])` - Failure rate
  - `media_processing_error_total{component="ffmpeg"}` - FFmpeg crash rate
- **Alerts**:
  - `MediaQueueBacklog` - Queue depth >100 for 5 minutes (P2)
  - `MediaLatencySLO` - p95 latency exceeds targets (>60s images, >300s videos) for 5 minutes (P2)
  - `MediaFailureSpike` - Failure rate >5% for 10 minutes (P2)

---

## Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2026-01-18 | 1.0 | Initial ADR documenting implemented architecture | Architecture Team |

---

## References

- [ADR-001: Multi-Tenancy & Tenant Isolation Strategy](ADR-001-tenancy.md)
- [ADR-006: Background Job Architecture & DelayedJob Pattern](ADR-006-background-jobs.md)
- [Architecture Overview: Background Processing](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md#3-6-background-processing)
- [Blueprint Foundation: Section 6 Risk Mitigations](../../.codemachine/artifacts/architecture/01_Blueprint_Foundation.md#section-6-risk-mitigations)
- [FFmpeg Security Best Practices](https://ffmpeg.org/security.html)
- [Cloudflare R2 Documentation](https://developers.cloudflare.com/r2/)
