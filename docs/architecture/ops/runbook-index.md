# Runbook Index

**Version:** 1.0
**Last Updated:** 2026-01-10
**Owner:** Platform Engineering Team
**Review Cycle:** Monthly

---

## Overview

This index provides a central directory of all operational runbooks for the Village Storefront platform. Use this document as your starting point when responding to incidents, performing maintenance, or troubleshooting issues.

---

## Runbook Inventory

| Runbook | Owner | Purpose | Last Updated | Alert Severity | Key Metrics |
|---------|-------|---------|--------------|----------------|-------------|
| [Catalog & Products](../../operations/catalog_runbook.md) | Backend Team | Product catalog operations, search indexing | 2026-01-09 | P2-P3 | `catalog.products.created`, `catalog.search.errors` |
| [Inventory Management](../../operations/catalog_inventory_runbook.md) | Backend Team | Stock management, inventory adjustments, multi-location sync | 2026-01-09 | P2-P3 | `inventory.adjustments.applied`, `inventory.drift.detected` |
| [Payments](../../operations/payments_runbook.md) | Payments Team | Stripe integration, refunds, payout reconciliation | 2026-01-10 | P1-P2 | `payments.intent.created`, `payments.webhook.processed` |
| [Shipping](../../operations/shipping_runbook.md) | Integration Team | Carrier integration (USPS, UPS, FedEx), rate calculation | 2026-01-08 | P2 | `shipping.rate.calculated`, `shipping.label.created` |
| [Consignment](../../operations/consignment_runbook.md) | Consignment Team | Vendor payouts, commission calculations, ledger accuracy | 2026-01-10 | P2-P3 | `consignment.payout.completed`, `consignment.balance.updated` |
| [Loyalty](../../operations/loyalty_runbook.md) | Loyalty Team | Points accrual, redemption, tier upgrades | 2026-01-10 | P3 | `loyalty.points.accrued`, `loyalty.redemption.completed` |
| [Media Pipeline](../../operations/media_runbook.md) | Media Team | FFmpeg transcoding, R2 storage, thumbnail generation | 2026-01-09 | P2-P3 | `media.upload.completed`, `media.transcode.duration` |
| [Headless API](../../operations/headless_api_runbook.md) | API Team | OAuth clients, rate limiting, headless integrations | 2026-01-10 | P2 | `api.request.rate_limited`, `api.oauth.token_issued` |
| [Background Jobs](../../operations/job_runbook.md) | Backend Team | Job queue management, delayed job processing | 2026-01-09 | P2-P3 | `jobs.queue.depth`, `jobs.processing.duration` |
| [Archive & Retention](../../operations/archive_runbook.md) | Data Ops | Compliance exports, data archival, partition maintenance | 2026-01-07 | P3 | `archive.partition.created`, `archive.export.completed` |
| [Disaster Recovery](../../operations/dr_playbook.md) | Platform Ops | Backup/restore, PITR, catastrophic failure recovery | 2026-01-10 | P1 | `backup.base.completed`, `backup.wal.archived` |
| [DR Testing Guide](../../operations/dr_testing_guide.md) | Platform Ops | Disaster recovery testing procedures, tenant suspension | 2026-01-10 | P1 | `dr.test.completed`, `tenant.suspended` |
| [Observability](../../operations/observability.md) | Platform Ops | Prometheus, Grafana, Jaeger, structured logging | 2026-01-03 | N/A | All platform metrics |
| [Alert Catalog](../../operations/alert_catalog.md) | Platform Ops | Alert definitions, severity levels, escalation paths | 2026-01-07 | P1-P3 | Alert conditions and thresholds |
| [Data Retention](../../operations/data-retention.md) | Data Ops | Retention policies, archival schedules, compliance | 2026-01-07 | P3 | `retention.policy.enforced`, `data.archived` |
| [Deployment](../../operations/deployment.md) | DevOps | Kubernetes deployments, rolling updates, configuration | 2026-01-07 | P1 | `deployment.rollout.duration`, `deployment.replica.ready` |
| [Release Runbook](./release-runbook.md) | DevOps | Release process, feature flags, rollback procedures | 2026-01-10 | P1 | `release.duration`, `release.rollback.triggered` |
| [Deployment Architecture](./deployment-architecture.md) | Platform Ops | Kubernetes architecture, networking, infrastructure | 2026-01-10 | N/A | Infrastructure design reference |

---

## Quick Reference by Incident Type

Use this section to quickly navigate to the appropriate runbook based on the incident or alert you're responding to.

### Customer-Facing Issues

| Symptom | Check First | Runbook |
|---------|-------------|---------|
| "Checkout is broken / payment failing" | Stripe dashboard, `payments.intent.failed` metric | [Payments Runbook § 3.2](../../operations/payments_runbook.md) |
| "Product images not loading" | R2 bucket status, `media.download.failed` metric | [Media Runbook § 2.1](../../operations/media_runbook.md) |
| "Search not returning results" | Catalog index status, `catalog.search.errors` metric | [Catalog Runbook § 2.3](../../operations/catalog_runbook.md) |
| "Shipping rates not calculating" | Carrier API status, `shipping.rate.errors` metric | [Shipping Runbook § 2.1](../../operations/shipping_runbook.md) |
| "Loyalty points not applying" | Loyalty job status, `loyalty.accrual.failed` metric | [Loyalty Runbook § 2.2](../../operations/loyalty_runbook.md) |
| "Storefront showing 503 / maintenance mode" | Tenant status, feature flags, `tenant.suspended` metric | [DR Testing Guide](../../operations/dr_testing_guide.md) |
| "Consignment payout missing" | Consignment ledger, `consignment.payout.failed` metric | [Consignment Runbook § 3.2](../../operations/consignment_runbook.md) |
| "Product inventory incorrect" | Inventory sync status, `inventory.drift.detected` metric | [Inventory Runbook § 3.1](../../operations/catalog_inventory_runbook.md) |

### Infrastructure Issues

| Symptom | Check First | Runbook |
|---------|-------------|---------|
| "Database is down / read-only" | PostgreSQL primary status, replication lag | [DR Playbook § 4.1](../../operations/dr_playbook.md) |
| "High API latency / timeouts" | Database connection pool, query performance | [Observability § 3.1](../../operations/observability.md) |
| "Out of memory / pod restarts" | JVM heap metrics, pod resource limits | [Observability § 3.2](../../operations/observability.md) |
| "Background jobs backing up" | Job queue depth, worker pod status | [Job Runbook § 2.1](../../operations/job_runbook.md) |
| "Backups failing" | R2 bucket access, backup job logs | [DR Playbook § 2.1](../../operations/dr_playbook.md) |
| "SSL certificate expiring / invalid" | cert-manager status, Cloudflare DNS | [Deployment Runbook § 4.2](../../operations/deployment.md) |
| "Deployment rollout stuck" | Kubernetes pod status, readiness probes | [Release Runbook § 3.1](./release-runbook.md) |
| "Feature flag not taking effect" | Feature flag cache, tenant settings | [Release Runbook § 4.2](./release-runbook.md) |

### Data Issues

| Symptom | Check First | Runbook |
|---------|-------------|---------|
| "Inventory out of sync" | Inventory adjustment logs, `inventory.drift.detected` metric | [Inventory Runbook § 3.1](../../operations/catalog_inventory_runbook.md) |
| "Consignment payout incorrect" | Consignment ledger audit logs, `consignment.balance.mismatch` metric | [Consignment Runbook § 3.2](../../operations/consignment_runbook.md) |
| "Data corruption suspected" | Audit logs, last known good backup timestamp | [DR Playbook § 4.2](../../operations/dr_playbook.md) |
| "Retention policy violation" | Archive job status, partition age metrics | [Archive Runbook § 3.1](../../operations/archive_runbook.md) |
| "Webhook events missing" | Stripe webhook log, `payments.webhook.failed` metric | [Payments Runbook § 2.1](../../operations/payments_runbook.md) |

---

## On-Call Escalation & Ownership

### Primary On-Call Rotation

**Current:** View PagerDuty schedule → [Village Storefront On-Call](https://villagecompute.pagerduty.com/schedules)

**Responsibilities:**
- Respond to P1/P2 alerts within SLA (P1: 15 minutes, P2: 60 minutes)
- Use this runbook index to route to appropriate runbook
- Escalate to domain experts if needed (see table below)
- Document incident in incident management system

### Domain Expert Escalation

| Domain | Team | Contact | Escalation Threshold |
|--------|------|---------|----------------------|
| **Payments** | Payments Team | #team-payments | Payment intent failures >10% over 5 minutes |
| **Media** | Media Team | #team-media | Media processing failures >20% over 10 minutes |
| **Catalog** | Backend Team | #team-backend | Catalog sync failures, index corruption |
| **Consignment** | Consignment Team | #team-consignment | Payout discrepancies, ledger audit failures |
| **Infrastructure** | Platform Ops | #team-platform-ops | Database failures, cluster-wide issues |
| **Security** | Security Team | #team-security | Auth bypass, data leakage, vulnerabilities |

---

## SLA/SLO Reference

All runbooks reference the following SLAs defined in [observability.md](../../operations/observability.md):

| Service | Availability SLA | Latency SLO (P95) | Error Rate SLO |
|---------|------------------|-------------------|----------------|
| Storefront | 99.5% | <500ms | <1% |
| Admin API | 99.0% | <200ms | <2% |
| Checkout | 99.9% | <800ms | <0.5% |
| Payments | 99.9% | <1000ms | <0.1% |
| Background Jobs | N/A | <5 min (processing) | <5% |

**Alert Severity Mapping:**
- **P1 (Critical):** SLA breach, customer-facing outage, data loss risk → Page immediately
- **P2 (High):** Degraded performance, approaching SLA breach, non-critical failures → Page during business hours or after 30 min
- **P3 (Medium):** Performance degradation, non-customer-impacting → Slack alert, investigate next business day

For detailed alert definitions, see [Alert Catalog](../../operations/alert_catalog.md).

---

## Dashboard Access

**Grafana:** https://grafana.villagecompute.com/
**Prometheus:** https://prometheus.villagecompute.com/
**Jaeger:** https://jaeger.villagecompute.com/

**Key Dashboards:**
- **Platform Overview:** [Grafana - Platform Health](https://grafana.villagecompute.com/d/platform-overview)
- **API Performance:** [Grafana - API Latency](https://grafana.villagecompute.com/d/api-performance)
- **Database Metrics:** [Grafana - PostgreSQL](https://grafana.villagecompute.com/d/postgresql)
- **Job Queue:** [Grafana - Background Jobs](https://grafana.villagecompute.com/d/job-queue)
- **Component KPIs:** [Grafana - Component KPIs](https://grafana.villagecompute.com/d/component-kpis)

For dashboard usage guide, see [Observability Dashboard Guide](./observability-dashboard.md).

---

## Review & Maintenance

**Review Cycle:** Monthly
**Owner:** Platform Engineering Team

**Monthly Review Checklist:**
- [ ] Verify all runbooks are up-to-date (last updated within 3 months)
- [ ] Update ownership assignments (team changes, rotations)
- [ ] Review incident response times (were runbooks effective?)
- [ ] Update "Quick Reference" based on recent incidents
- [ ] Add new runbooks for recently launched features
- [ ] Archive/deprecate outdated runbooks

**Last Review:** 2026-01-10
**Next Review:** 2026-02-10

---

**For Runbook Authors:**
- Follow the runbook template structure (Overview, Alerts, Common Issues, Recovery Procedures)
- Include sections: Component KPIs, Service Dependencies, Monitoring Links
- Link to relevant ADRs, architecture docs, and code locations
- Test procedures in staging before documenting
- Keep runbooks concise (aim for <30 pages - longer runbooks should split into multiple docs)
