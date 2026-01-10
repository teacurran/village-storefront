# Hypercare Plan: First 30 Days Post-Launch

**Version:** 1.0
**Launch Date:** [TBD - Set by Product Team]
**Hypercare Period:** Launch + 30 days
**Owner:** Platform Engineering Team
**On-Call Lead:** [Assigned at launch]

---

## Overview

This document defines the enhanced monitoring, on-call coverage, and incident response procedures for the first 30 days following the Village Storefront production launch.

**Hypercare Objectives:**
1. **Proactive Monitoring:** Detect and resolve issues before customer impact
2. **Rapid Response:** <15 minute response time for P1 incidents
3. **Knowledge Capture:** Document all incidents and operational learnings
4. **Smooth Handoff:** Transition to BAU (Business As Usual) operations after 30 days

---

## Enhanced Monitoring (First 30 Days)

### Daily Health Checks (Automated + Manual)

**Time:** 9 AM EST daily
**Owner:** On-call engineer
**Duration:** 30 minutes

**Checklist:**
- [ ] Review overnight alerts (any P1/P2 incidents?)
- [ ] Check platform availability (storefront, admin, API endpoints)
- [ ] Verify background job queue health (no backlog >1 hour)
- [ ] Review error rate metrics (within SLO thresholds?)
- [ ] Check database performance (query latency, connection pool)
- [ ] Verify backup completion (daily base backup successful?)
- [ ] Review tenant activity (new signups, active stores, checkout completion rate)
- [ ] Check integration health (Stripe, carriers, R2 storage)

**Reporting:** Post summary to #platform-ops Slack channel

### Real-Time Dashboard Monitoring

**Dashboards to Monitor:**
- **Platform Overview:** [Grafana - Platform Health](https://grafana.villagecompute.com/d/platform-overview)
  - Monitor: Availability, error rates, API latency
- **Checkout Funnel:** [Grafana - Checkout Analytics](https://grafana.villagecompute.com/d/checkout-funnel)
  - Monitor: Cart abandonment rate, payment success rate, order completion
- **Background Jobs:** [Grafana - Job Queue](https://grafana.villagecompute.com/d/job-queue)
  - Monitor: Queue depth, processing latency, failed job count
- **Database:** [Grafana - PostgreSQL](https://grafana.villagecompute.com/d/postgresql)
  - Monitor: Connection count, query latency, replication lag

**Alert Thresholds (Hypercare - More Aggressive):**
- Storefront availability <99.5% over 5 minutes → Page immediately
- API error rate >1% over 5 minutes → Page immediately
- Checkout success rate <95% over 10 minutes → Page immediately
- Background job queue depth >1000 jobs → Page after 15 minutes
- Database connection pool >80% → Page after 10 minutes

**Post-Hypercare:** Relax thresholds to standard SLA targets (see [observability.md](../../operations/observability.md))

---

## Key Performance Indicators (KPIs) - First 30 Days

### Platform Health KPIs (Daily)

| KPI | Target | Alert Threshold | Data Source |
|-----|--------|----------------|-------------|
| **Storefront Availability** | ≥99.5% | <99.0% over 1 hour | Prometheus: `up{service="storefront"}` |
| **Admin API Availability** | ≥99.0% | <98.0% over 1 hour | Prometheus: `up{service="admin-api"}` |
| **Checkout Success Rate** | ≥95% | <90% over 1 hour | Database: `orders.status='completed'` / `carts.created` |
| **Payment Success Rate** | ≥98% | <95% over 1 hour | Prometheus: `payments.intent.succeeded` / `payments.intent.created` |
| **Background Job Success** | ≥95% | <90% over 1 hour | Database: `background_jobs.status='completed'` / `total` |
| **Database Query Latency (P95)** | <200ms | >500ms over 15 min | Prometheus: `postgres.query.duration` |
| **Media Upload Success** | ≥98% | <95% over 1 hour | Prometheus: `media.upload.completed` / `media.upload.started` |

### Business KPIs (Daily)

| KPI | Target | Notes | Data Source |
|-----|--------|-------|-------------|
| **New Tenant Signups** | Track baseline | No target yet (early days) | Database: `tenants.created_at` |
| **Active Stores** | >10 | Stores with ≥1 product and ≥1 order in last 7 days | Reporting: `tenant_activity_summary` |
| **Orders per Day** | >50 | Total across all tenants | Database: `orders.created_at` |
| **Average Order Value (AOV)** | >$30 | Baseline for future optimization | Reporting: `AVG(order_items.subtotal)` |
| **Cart Abandonment Rate** | <70% | Industry average is 70%, track for optimization | Reporting: `carts` vs `orders` |
| **Checkout Funnel Drop-off** | Track by step | Identify friction points | Reporting: `checkout_funnel_events` |
| **Consignment Payout Volume** | >$1000/week | Validates consignment feature adoption | Database: `consignment_ledger` |
| **Loyalty Redemption Rate** | >5% | % of orders using loyalty points | Reporting: `loyalty_redemptions` |

### Technical Debt & Performance KPIs (Weekly)

| KPI | Target | Action If Missed |
|-----|--------|------------------|
| **Test Coverage** | ≥80% | Block PRs until coverage restored |
| **SonarCloud Quality Gate** | Pass | Block PRs until issues resolved |
| **API Response Time (P95)** | <500ms | Performance tuning sprint |
| **Database CPU Usage** | <70% | Add read replicas, optimize queries |
| **R2 Storage Growth** | <10GB/day | Review media retention policies |

---

## On-Call Coverage (Enhanced)

### Hypercare On-Call Rotation

**Coverage:** 24/7 for first 30 days
**Rotation:** 1-week shifts
**Team Size:** 2 engineers (primary + backup)

**Week 1:** [Engineer A] (primary), [Engineer B] (backup)
**Week 2:** [Engineer C] (primary), [Engineer D] (backup)
**Week 3:** [Engineer A] (primary), [Engineer E] (backup)
**Week 4:** [Engineer B] (primary), [Engineer C] (backup)

**Responsibilities:**
- **Primary on-call:** Respond to all P1/P2 alerts within SLA
- **Backup on-call:** Available for escalation if primary doesn't respond in 10 minutes
- **Handoff:** Daily sync at 9 AM EST to review overnight incidents

**Post-Hypercare:** Transition to standard on-call rotation (see [runbook-index.md](runbook-index.md))

### Incident Response SLA (Hypercare)

| Severity | Description | Response Time | Resolution Time | Escalation |
|----------|-------------|---------------|-----------------|------------|
| **P1** | Customer-facing outage, data loss | <15 min | <4 hours | Escalate to CTO after 2 hours |
| **P2** | Degraded performance, partial outage | <30 min | <24 hours | Escalate to VP Eng after 12 hours |
| **P3** | Non-critical issues, monitoring alerts | <2 hours | <5 days | No escalation |

**Post-Hypercare:** Relax to standard SLA (P1: <30 min, P2: <1 hour)

---

## Incident Tracking & Learning

### Incident Documentation (Required)

**For ALL incidents (P1, P2, P3):**
1. **Create incident ticket:** [Jira - Incident Template](https://villagecompute.atlassian.net/...)
2. **Document timeline:**
   - Detection time (alert timestamp)
   - Response time (when on-call started investigation)
   - Root cause identified time
   - Resolution time (when issue fully resolved)
3. **Capture runbook effectiveness:**
   - Which runbook was used? Was it helpful?
   - What was missing from the runbook?
   - What additional steps were needed?
4. **Post-incident review (PIR):** Required for P1/P2 within 72 hours

### Weekly Incident Review Meeting

**Time:** Fridays 2-3 PM EST
**Attendees:** On-call rotation, platform eng lead, product manager

**Agenda:**
- Review all incidents from past week (P1, P2, P3)
- Discuss root causes and trends
- Identify action items (runbook updates, monitoring improvements, code fixes)
- Celebrate wins (fast response, good collaboration, proactive fixes)

### Knowledge Base Updates

**After each incident:**
- Update relevant runbook with learnings
- Add troubleshooting tips to developer guide
- Update monitoring/alerting if detection was slow
- Create new runbook if gap identified

**Goal:** By end of 30 days, runbooks should be battle-tested and comprehensive.

---

## Feature Flag Rollout Strategy (Hypercare)

### Progressive Rollout Approach

**All new features MUST use feature flags during hypercare period.**

**Rollout Phases:**

1. **Internal Testing (Day 0-3):**
   - Enable for internal test tenant (`villagecompute-test`)
   - Run smoke tests, monitor metrics
   - Fix any critical bugs before wider rollout

2. **Beta Tenants (Day 4-7):**
   - Enable for 5-10 beta tenants (early adopters)
   - Monitor adoption, gather feedback
   - Watch for increased error rates or support tickets

3. **Gradual Rollout (Day 8-21):**
   - Enable for 25% of tenants (Day 8)
   - Enable for 50% of tenants (Day 14)
   - Enable for 75% of tenants (Day 21)
   - Monitor KPIs at each stage, halt rollout if issues

4. **Full Rollout (Day 22+):**
   - Enable for all tenants (100%)
   - Remove feature flag after 30 days of stability

**Emergency Kill Switches (Always Available):**
- `checkout.kill-switch` - Disable checkout if payment issues
- `media.upload.disabled` - Disable media uploads if R2 issues
- `consignment.payout.halt` - Halt automated payouts if ledger issues

**Feature Flag Monitoring:**
- Track adoption rate per tenant (`feature_flags.adoption_rate`)
- Track errors by feature flag status (enabled vs. disabled)
- Alert if feature flag causes error rate spike

---

## Communication Plan

### Daily Standup (First 14 Days)

**Time:** 9:30 AM EST daily
**Duration:** 15 minutes
**Attendees:** On-call rotation, platform eng lead, product manager

**Agenda:**
1. Review overnight incidents (any issues?)
2. Review daily KPIs (anything concerning?)
3. Highlight upcoming releases/feature flag changes
4. Discuss action items from previous day

**After Day 14:** Transition to weekly check-ins

### Stakeholder Updates

**Frequency:** Weekly
**Format:** Email summary + Slack post
**Recipients:** Engineering leadership, product team, customer support

**Template:**

```
Subject: Week 1 Hypercare Summary - Village Storefront

**Platform Health:**
- Availability: 99.8% (target: 99.5%) ✅
- Orders: 342 (target: 50/day) ✅
- Incidents: 2 P2, 0 P1 ✅

**Highlights:**
- Launched loyalty program to 25% of tenants (beta rollout)
- Improved checkout latency by 15% (database indexing)
- Fixed 3 bugs reported by beta tenants

**Incidents:**
- P2: Media upload timeout (resolved in 45 min)
- P2: Catalog sync lag (resolved in 2 hours)

**Action Items:**
- Add R2 upload retry logic (ETA: next week)
- Update catalog sync runbook with new troubleshooting steps

**Next Week Focus:**
- Roll out loyalty to 50% of tenants
- Implement automated alerting for catalog sync lag
- Onboard 3 new beta tenants
```

### Customer Support Readiness

**Support Team Prep (Before Launch):**
- [ ] Train on platform capabilities (storefront, admin, POS, consignment)
- [ ] Provide access to test tenant for hands-on experience
- [ ] Share runbook index for issue escalation
- [ ] Set up #support-escalation Slack channel for eng team escalation

**Escalation Path:**
- Support ticket → Support team → #support-escalation → On-call engineer

**Support Metrics to Track:**
- Ticket volume (baseline for future)
- Time to resolution (target: <24 hours for P2)
- Common issues (identify documentation gaps)

---

## Transition to BAU (Business As Usual)

### Day 30 Review Meeting

**Attendees:** Engineering leadership, platform eng lead, on-call rotation, product manager

**Agenda:**
1. **Review hypercare metrics:**
   - Were KPI targets met?
   - How many incidents occurred?
   - Were runbooks effective?
2. **Document lessons learned:**
   - What went well?
   - What could be improved?
   - What surprised us?
3. **Update operational procedures:**
   - Adjust SLA thresholds based on actual performance
   - Refine alert rules (reduce false positives)
   - Update runbooks with hypercare learnings
4. **Plan post-hypercare operations:**
   - Transition to standard on-call rotation
   - Reduce daily standup frequency to weekly
   - Set quarterly review cadence

### BAU Handoff Checklist

- [ ] All runbooks updated with hypercare learnings
- [ ] Monitoring dashboards finalized (no ad-hoc queries needed)
- [ ] Alert thresholds tuned (false positive rate <5%)
- [ ] Feature flags removed for stable features (>30 days no issues)
- [ ] Knowledge base articles created for common issues
- [ ] Support team trained and confident
- [ ] On-call rotation transitioned to standard schedule
- [ ] Incident response SLA relaxed to standard targets
- [ ] Hypercare retrospective completed and action items tracked

---

## Success Criteria

Hypercare is considered **successful** if:

✅ Platform availability ≥99.5% over 30 days
✅ No P1 incidents lasting >4 hours
✅ All P2 incidents resolved within 24 hours
✅ ≥10 active stores (stores with orders) by Day 30
✅ ≥500 total orders processed by Day 30
✅ All critical runbooks validated through actual incident response
✅ Support team feels confident handling common issues
✅ Smooth transition to BAU operations (no gaps in knowledge or procedures)

**If success criteria not met:** Extend hypercare period by 2 weeks and reassess.

---

## Appendix: Hypercare Metrics Dashboard

**Grafana Dashboard:** [Hypercare Metrics](https://grafana.villagecompute.com/d/hypercare-overview)

**Key Panels:**
- Platform availability trend (30-day view)
- Incident count by severity (P1/P2/P3 bar chart)
- Order volume trend (daily orders line chart)
- Checkout funnel conversion rate (daily %)
- Feature flag adoption rate (% tenants with feature X enabled)
- Alert volume trend (total alerts per day, split by severity)

**Daily Review:** On-call engineer reviews this dashboard every morning at 9 AM.

---

**Questions or Feedback:**
- Slack: #platform-ops
- Email: platform-ops@villagecompute.com
- On-Call Hotline: [Phone number TBD]
