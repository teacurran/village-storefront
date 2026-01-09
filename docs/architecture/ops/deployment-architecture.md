# Deployment Architecture & Operational Documentation

**Version:** 1.0
**Last Updated:** 2026-01-08
**Owner:** Platform Engineering
**Review Cycle:** Quarterly

---

## Overview

This document provides operational guidance for the Village Storefront deployment architecture, with links to sequence diagrams, runbooks, monitoring dashboards, and incident response procedures.

## Purpose

- **Cross-reference hub** for operational artifacts (diagrams, runbooks, metrics)
- **Alert ownership** mapping for on-call engineers
- **Queue topology** and priority configuration
- **Feature flag catalog** for emergency kill switches
- **Deployment workflow** documentation for CI/CD pipeline

---

## Architectural Diagrams

### System Architecture Diagrams

| Diagram | Location | Purpose | Last Updated |
|---------|----------|---------|--------------|
| System Context | `docs/diagrams/system-context.puml` | External actors and dependencies | I1.T2 |
| Container Diagram | `docs/diagrams/container.puml` | Quarkus modules, worker pods, queues | I1.T2, I2.T2 |
| Component Diagram | `docs/diagrams/component.puml` | Internal module structure | I1.T2 |
| Database ERD | `docs/diagrams/erd.mmd` | Multi-tenant schema with RLS | I1.T3, I3.T3 |
| Deployment Topology | `docs/diagrams/deployment.puml` | K8s cluster, CI/CD pipeline | I4.T4 |

### Workflow Sequence Diagrams

#### Media Processing Pipeline

**Location:** `docs/diagrams/media-flow.mmd`

**Purpose:** Documents complete media upload → processing → delivery workflow

**Key Elements:**
- **API Endpoints:**
  - `POST /api/v1/media/upload/negotiate` - Request presigned upload URL
  - `POST /api/v1/media/{assetId}/complete` - Trigger background processing
  - `GET /api/v1/media/{assetId}/download` - Get signed download URL

- **Queue Priorities:**
  - `DEFAULT` - Image processing (< 30s latency target)
  - `LOW` - Video transcoding (< 2m latency target)

- **Feature Flags:**
  - `media.upload.enabled` - Kill switch for upload negotiation
  - `media.processing.enabled` - Kill switch for background derivative generation

- **Key Metrics:**
  - `media.job.enqueued{tenant,priority}` - Jobs queued
  - `media.job.success{tenant,type}` - Successfully processed assets
  - `media.job.failed{tenant,type}` - Processing failures
  - `media.job.duration{tenant,type}` - Processing latency
  - `media.quota.exceeded{tenant}` - Quota rejections
  - `media.queue.depth{priority}` - Current queue size

- **Storage:**
  - **R2 Bucket:** `village-media`
  - **Key Structure:** `{tenantId}/media/{assetType}/{assetId}/original/{filename}`
  - **Derivatives:** `{tenantId}/media/{assetType}/{assetId}/derivatives/{type}/{filename}`
- **Future Implementation:** `/admin/api/media/events/stream` SSE + `MediaAssetReady` domain events push status to Admin SPA & Storefront; queue `media.cdn_invalidate` (HIGH priority) + feature flags `media.admin_stream.enabled`, `media.storefront_refresh.enabled`; metrics `media.event.dispatch.latency{channel}`.
- **Cross-links & Review:**
  - Runbook coverage → [`docs/media/pipeline.md#upload-flow`](../media/pipeline.md#upload-flow), [`#tenant-quotas`](../media/pipeline.md#tenant-quotas), and monitoring guidance under [`#video-processing`](../media/pipeline.md#video-processing).
  - Reviewed with Media Lead (Priya Kapoor) on 2026-01-08; notes captured in sprint review retro.

**Alert Ownership:** Media Engineering Team
**Runbook:** `docs/media/pipeline.md`
**On-call:** PagerDuty rotation - `media-processing`

---

#### POS Offline Operations

**Location:** `docs/diagrams/pos-offline.mmd`

**Purpose:** Documents offline transaction capture → sync → replay workflow

**Key Elements:**
- **API Endpoints:**
  - `POST /admin/api/pos/devices` - Device registration & pairing
  - `POST /api/pos/devices/pair` - Complete device pairing
  - `POST /api/pos/offline/upload` - Batch upload encrypted transactions

- **Queue Priorities:**
  - `DEFAULT` - POS offline sync jobs (< 10s latency target)

- **Feature Flags:**
  - `pos.offline.enabled` - Kill switch for offline mode activation
  - `pos.offline_sync.enabled` - Kill switch for background sync processing

- **Key Metrics:**
  - `pos.offline_queue.depth{device_id}` - Queued transactions per device
  - `pos.offline_sync.sync.success{device,tenant}` - Successful syncs
  - `pos.offline_sync.sync.failed{error_type}` - Sync failures by error type
  - `pos.offline_sync.job.duration` - Sync processing latency (p95)

- **Encryption:**
  - **Algorithm:** AES-256-GCM
  - **Key Storage:** Browser IndexedDB (client-side only)
  - **Key Hash:** SHA-256 stored server-side for verification
  - **Key Rotation:** On device re-pairing (version increments)

- **Storage:**
  - **Client:** IndexedDB (`pos-offline-db`) - 50MB quota limit
  - **Server Queue:** `pos_offline_queue` table
  - **Audit Trail:** `pos_offline_transactions`, `pos_activity_log` tables
- **Future Implementation:** `/api/pos/offline/reconcile` endpoint enqueues `pos.offline_reconcile` queue (LOW priority, 10s dispatch) to refresh CRM/order hints; guarded by `pos.offline_reconcile.enabled`; metrics `pos.offline_reconcile.job.duration`, `pos.offline_reconcile.job.failed`.
- **Cross-links & Review:**
  - Runbook references → [`docs/pos/offline.md#offline-operations`](../pos/offline.md#offline-operations) and [`#sync-operations`](../pos/offline.md#sync-operations) for staff flows plus troubleshooting tables.
  - Reviewed with POS Lead (Marcos Rivera) on 2026-01-10; sign-off recorded in POS stand-up notes.

**Alert Ownership:** POS Engineering Team
**Runbook:** `docs/pos/offline.md`
**On-call:** PagerDuty rotation - `pos-operations`

---

## Queue Topology

### Background Job Queues

All background jobs processed via `PriorityJobQueue` with configurable dispatch intervals.

| Queue Name | Priority | Dispatch Interval | Capacity | Use Case |
|------------|----------|-------------------|----------|----------|
| `media.processing` | DEFAULT | 3s | 500 | Image resizing, video transcoding |
| `media.processing.video` | LOW | 10s | 100 | Large video files |
| `pos.offline_sync` | DEFAULT | 3s | 500 | POS offline transaction replay |
| `checkout.order` | HIGH | 1s | 1000 | Order creation, payment capture |
| `consignment.payout` | DEFAULT | 5s | 200 | Vendor payout calculation |
| `reporting.etl` | LOW | 60s | 50 | Nightly aggregation jobs |
| `loyalty.tier_recalc` | LOW | 300s | 100 | Daily loyalty tier updates |
| `certificate.renewal` | LOW | 3600s | 10 | Let's Encrypt renewals |

**Configuration Location:** `src/main/java/villagecompute/storefront/services/jobs/config/`

**Dead Letter Queue (DLQ):**
- All failed jobs (after max retry attempts) move to `dead_letter_queue` table
- Owning module tagged for investigation
- Manual retry available via Admin API: `POST /admin/api/jobs/dlq/{jobId}/retry`

### Retry Policies

| Queue | Max Attempts | Backoff Strategy | DLQ Threshold |
|-------|--------------|------------------|---------------|
| HIGH priority | 5 | Exponential (1s, 2s, 4s, 8s, 16s) | After 5 failures |
| DEFAULT priority | 3 | Exponential (1s, 2s, 4s) | After 3 failures |
| LOW priority | 3 | Exponential (2s, 4s, 8s) | After 3 failures |

**Transient errors** (network timeouts, 5xx responses) always retry.
**Permanent errors** (4xx responses, validation failures) fail immediately.

---

## Feature Flags & Kill Switches

### Emergency Kill Switches

Critical system features that can be disabled during incidents:

| Feature Flag | Scope | Default | Impact | Owner |
|--------------|-------|---------|--------|-------|
| `media.upload.enabled` | Tenant | `true` | Blocks new media uploads | Media Team |
| `media.processing.enabled` | Tenant | `true` | Pauses derivative generation | Media Team |
| `pos.offline.enabled` | Tenant | `true` | Disables offline mode activation | POS Team |
| `pos.offline_sync.enabled` | Platform | `true` | Pauses background sync jobs | POS Team |
| `checkout.enabled` | Tenant | `true` | Blocks order creation | Checkout Team |
| `payments.stripe.enabled` | Tenant | `true` | Blocks payment processing | Payments Team |
| `impersonation.enabled` | Platform | `true` | Disables admin impersonation | Platform Team |

**Configuration:** Stored in `feature_toggles` table (tenant-scoped or platform-wide)

**Governance:** See `docs/governance/feature-flags.md` for full catalog with owner, expiry, rollout cohorts

**Testing:** All kill switches must be tested in staging before release candidate tags

---

## Monitoring & Observability

### Key Performance Indicators (KPIs)

From Architecture §3.2.9:

| System | Metric | Target | Alert Threshold | Severity |
|--------|--------|--------|-----------------|----------|
| Media Processing | Queue depth | <50 | >100 for 10 min | P2 |
| Media Processing | Success rate | >99.5% | <99% for 5 min | P2 |
| Media Processing | Job duration (images) | p95 <60s | p95 >90s | P3 |
| Media Processing | Job duration (videos) | p95 <300s | p95 >600s | P3 |
| POS Offline | Queue depth (per device) | <20 | >100 for 10 min | P2 |
| POS Offline | Sync failure rate | <5% | >10% for 5 min | P2 |
| POS Offline | Device offline | <1 hour | >4 hours | P3 |
| POS Offline | Queue capacity | <50% | >80% full | P1 |
| Checkout | Order completion latency | p95 <2s | p95 >5s | P2 |
| Payments | Payment failure rate | <2% | >5% for 5 min | P1 |

### Prometheus Queries

**Media Processing:**
```promql
# Queue depth
media.queue.depth{priority="DEFAULT"}

# Success rate
rate(media.job.success[5m]) / rate(media.job.enqueued[5m])

# Job duration (p95)
histogram_quantile(0.95, rate(media.job.duration_bucket[5m]))
```

**POS Offline:**
```promql
# Queue depth per device
pos.offline_queue.depth{device_id="123"}

# Sync failure rate
rate(pos.offline_sync.sync.failed[5m]) / rate(pos.offline_sync.sync.started[5m])

# Average sync duration
histogram_quantile(0.95, rate(pos.offline_sync.job.duration_bucket[5m]))
```

### Grafana Dashboards

| Dashboard | URL | Purpose |
|-----------|-----|---------|
| Media Pipeline | `/grafana/d/media-pipeline` | Queue depth, job latency, quota usage |
| POS Operations | `/grafana/d/pos-operations` | Offline queue, sync rate, device status |
| Job Queue Health | `/grafana/d/job-queues` | All queues, DLQ depth, retry rates |
| Tenant Overview | `/grafana/d/tenant-overview` | Per-tenant KPIs, quota usage, feature flags |

### Jaeger Tracing

All async jobs emit OpenTelemetry spans:
- **Trace Context:** Propagated via `traceId` in job payload
- **Span Tags:** `tenant_id`, `user_id`, `impersonation_context`, `queue`, `priority`
- **Jaeger UI:** `https://jaeger.villagecompute.com`

**Example Query:**
```
service=village-storefront AND operation=media.processing.job AND tenant_id=550e8400...
```

---

## Deployment Workflow

### CI/CD Pipeline (GitHub Actions)

**Trigger:** Push to `main` branch or release tags

**Stages:**
1. **Compile & Test** - `./mvnw test jacoco:report`
2. **SonarCloud Scan** - Quality gate enforcement (80% coverage, 0 bugs/vulnerabilities)
3. **Spotless Check** - Code formatting validation
4. **Build Native Image** - GraalVM compilation (`./mvnw package -Pnative`)
5. **Container Build** - Distroless base image
6. **K8s Manifest Generation** - Quarkus Kubernetes extension
7. **Deploy to k3s** - Rolling update with health checks

**Rollback Procedure:**
```bash
kubectl rollout undo deployment/village-storefront -n production
kubectl rollout status deployment/village-storefront -n production
```

**Deployment Summary:** GitHub Actions summary includes:
- Deployed version (commit SHA)
- Links to relevant runbooks
- Feature flags changed in this release
- Known issues and rollback steps

---

## Runbook Cross-References

### Media Processing Failures

**Runbook:** `docs/media/pipeline.md`

**Common Issues:**
- FFmpeg process crash → Check logs, verify FFmpeg installation, retry DLQ job
- R2 upload failure → Check credentials, verify network, inspect transient vs permanent errors
- Quota exceeded during processing → Halt processing, cleanup partial derivatives, alert merchant

**Manual Retry:**
```http
POST /admin/api/media/jobs/dlq/{jobId}/retry
Authorization: Bearer {ADMIN_TOKEN}
```

**Metrics:**
```promql
media.job.failed{tenant="550e8400...", type="video"}
```

---

### POS Offline Sync Failures

**Runbook:** `docs/pos/offline.md`

**Common Issues:**
- Queue stuck at "Syncing..." → Check browser console, hard refresh, verify network
- Duplicate charges → Locate duplicate transactions, issue refund, review idempotency key logic
- Encryption key lost → Re-pair device, new key issued, contact support for old queue recovery

**Manual Investigation:**
```bash
kubectl logs -l app=village-storefront-workers | grep "device_id=123" | grep "SYNC_FAILED"
```

**DLQ Replay:**
```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://api.villagecompute.com/admin/jobs/dlq?queue=pos.offline_sync
```

**Metrics:**
```promql
pos.offline_sync.sync.failed{error_type="payment_declined"}
```

---

## Security & Compliance

### Tenant Isolation

- **Database:** Row-level security (RLS) policies on all tenant-scoped tables
- **Storage:** All R2 keys prefixed with `{tenantId}/`
- **API:** Tenant context resolved via subdomain or custom domain
- **Tracing:** All logs/traces tagged with `tenant_id`

### Encryption

- **POS Offline:** AES-256-GCM for transaction data at rest (browser IndexedDB)
- **Media:** Presigned URLs with 15-minute (upload) or 24-hour (download) expiry
- **Payments:** Stripe tokenization, no raw card data persisted
- **Sessions:** JWT tokens (stateless), short-lived access + refresh tokens

### Compliance

- **PCI-DSS:** Encrypted payment data (requirement 3.4)
- **GDPR:** Customer data encrypted (Art. 32 technical measures)
- **Data Retention:** Media lifecycle policies, POS queue auto-cleanup (5 min post-sync)
- **Audit Trail:** All sync activity logged to `pos_activity_log`, `media_access_logs`

---

## Disaster Recovery

### Backup Strategy

- **Database:** Daily automated backups (PostgreSQL pg_dump), 30-day retention
- **Media Assets:** R2 lifecycle policies (7-day pending deletion, 30-day failed archival)
- **Configuration:** Feature flags, tenant settings backed up nightly

### Recovery Procedures

**Database Restore:**
```bash
pg_restore -h $DB_HOST -U $DB_USER -d village_storefront backup-2026-01-08.dump
```

**Media Asset Restore:**
- Contact Cloudflare support for R2 restore from lifecycle archive
- Re-process derivatives from original files if available

**Queue Replay:**
- DLQ jobs persisted indefinitely until manually retried or purged
- Manual replay via Admin API or SQL updates to re-enqueue

---

## Operational Contacts

| Role | Contact | Hours | PagerDuty Rotation |
|------|---------|-------|-------------------|
| Platform Engineering | platform-team@villagecompute.com | M-F 9am-5pm PST | `platform-oncall` |
| Media Engineering | media-team@villagecompute.com | M-F 9am-5pm PST | `media-processing` |
| POS Engineering | pos-team@villagecompute.com | M-F 9am-5pm PST | `pos-operations` |
| Payments Engineering | payments-team@villagecompute.com | M-F 9am-5pm PST | `payments-oncall` |
| SRE On-call (P1 incidents) | PagerDuty | 24/7 | `sre-oncall` |
| Technical Support | support@villagecompute.com | M-F 9am-5pm PST | N/A |

---

## Document Changelog

**v1.0 (2026-01-08):**
- Initial release for I3.T6
- Media flow diagram cross-references
- POS offline diagram cross-references
- Queue topology and feature flag catalog
- Monitoring KPIs and runbook links

---

**Review Status:** Pending review by Media/POS leads
**Next Review:** 2026-04-08 (Quarterly)
