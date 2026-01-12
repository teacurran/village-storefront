# Village Storefront Operations Runbook

<!-- anchor: operations-runbook -->

**Status:** Authoritative
**Last Updated:** 2026-01-12
**Owner:** Platform Operations Team

## Document Purpose

This runbook provides operational procedures, deployment guidance, incident response playbooks, and monitoring instructions for Village Storefront platform operations. It consolidates deployment workflows, rollback procedures, background job management, payment/media/POS/consignment incident response, and SLA dashboard mappings.

**Intended Audience:** On-call engineers responding to production incidents, DevOps engineers managing deployments, SREs scaling infrastructure, operations teams monitoring platform health.

---

## Table of Contents

1. [Quick Reference](#quick-reference)
2. [Deployment Procedures](#deployment-procedures)
3. [Incident Response Playbooks](#incident-response-playbooks)
4. [Background Job Management](#background-job-management)
5. [Monitoring & Alerting](#monitoring--alerting)
6. [Escalation Matrix](#escalation-matrix)
7. [Routine Operations](#routine-operations)
8. [References & Resources](#references--resources)

---

<!-- anchor: quick-reference -->

## 1. Quick Reference

### Emergency Contacts

| Role | Responsibility | Primary Contact | Backup Contact | Escalation |
|------|---------------|----------------|----------------|------------|
| On-Call Engineer | First responder, execute playbooks | PagerDuty rotation | N/A | Eng Manager after 30m |
| Engineering Manager | Coordinate multi-team response | Slack @eng-manager | Slack @tech-lead | CTO after 1h (SEV-1) |
| Database Admin | PostgreSQL issues, schema changes | Slack @dba-team | On-call pager | Infra Lead |
| Infrastructure Lead | k3s cluster, networking, nodes | Slack @infra-lead | Slack @devops-team | CTO (SEV-1) |
| Finance Team | Payout discrepancies, refund issues | finance@villagecompute.com | Slack @finance | CFO |
| Security Team | Auth breaches, audit violations | security@villagecompute.com | Slack @security | CISO (immediate) |

**Platform Admin Access:**
- Production Admin UI: `https://platform.villagecompute.com/admin` (requires MFA)
- Kubernetes access: `kubectl config use-context production-k3s`
- Database access: Via bastion host (credentials in 1Password vault `production-db`)

### Feature Flag Registry

Critical feature flags for incident response:

| Flag Name | Default | Purpose | Toggle Impact | Verification |
|-----------|---------|---------|---------------|--------------|
| `checkout.kill-switch` | `true` | Emergency checkout disable | Blocks all new checkouts, shows maintenance page | Monitor `checkout_initiated_total` → 0 |
| `media.processing.enabled` | `true` | Media job processing toggle | Stops FFmpeg spawning, jobs remain queued | Check worker logs for "Kill switch activated" |
| `stripe.webhook.processing.enabled` | `true` | Stripe webhook circuit breaker | Webhooks queued but not processed | Verify `webhooks.stripe` queue depth stable |
| `impersonation.disable` | `false` | Disable admin impersonation | Blocks all impersonation attempts, audit logged | Test impersonation returns 403 |
| `shipping.fallback.enabled` | `true` | Enable flat-rate shipping fallback | Uses table rates if carrier APIs down | Check `shipping.rate.fallback_used` metric |
| `pos.offline.sync.enabled` | `true` | POS offline sync processing | Disables batch upload processing | Monitor `pos_offline_batch_jobs` queue |
| `consignment.payout.enabled` | `true` | Consignment payout generation | Blocks new payout batch creation | Verify no new `payouts.batch` jobs enqueued |
| `loyalty.points.enabled` | `true` | Loyalty program active | Disables point accrual/redemption | Orders process without points |

**How to Toggle Flags:**

```bash
# Via Admin API (preferred)
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"flag": "checkout.kill-switch", "enabled": false, "reason": "Emergency stop - incident #123"}' \
  https://api.villagecompute.com/admin/feature-flags

# Via kubectl (emergency fallback)
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "UPDATE feature_flags SET enabled = false WHERE flag_name = 'checkout.kill-switch';"
```

### Dashboard Links

| Dashboard | URL | Purpose | Critical Panels |
|-----------|-----|---------|-----------------|
| Background Job Health | `https://grafana.villagecompute.com/d/background-jobs` | Queue depth, throughput, DLQ | Queue depth by priority, failure rate, worker resource usage |
| Media Pipeline | `https://grafana.villagecompute.com/d/media-pipeline` | Media processing metrics | Upload → Ready funnel, FFmpeg processes, derivative success rate |
| Checkout & Payments | `https://grafana.villagecompute.com/d/checkout` | Order flow, payment success | Checkout conversion, Stripe webhook latency, payment decline rate |
| POS Offline Sync | `https://grafana.villagecompute.com/d/pos-offline` | Offline batch processing | Batch queue depth, replay errors, discrepancy count |
| Consignment Payouts | `https://grafana.villagecompute.com/d/consignment` | Payout batch jobs | Payout calculation latency, ACH transfer status, payout holds |
| Platform Overview | `https://grafana.villagecompute.com/d/platform` | System-wide metrics | API pod health, database connections, R2 bandwidth |

**Prometheus:**
- Query UI: `https://prometheus.villagecompute.com`
- Alert Manager: `https://prometheus.villagecompute.com/alertmanager`

**PagerDuty:**
- Incidents: `https://villagecompute.pagerduty.com/incidents`
- Escalation Policies: `https://villagecompute.pagerduty.com/escalation_policies`

---

<!-- anchor: deployment-procedures -->

## 2. Deployment Procedures

### Blue/Green Deployment Strategy

Village Storefront uses blue/green deployments to minimize downtime and enable instant rollback.

**Pre-Deployment Checklist:**

- [ ] All tests passing in CI/CD pipeline (GitHub Actions)
- [ ] SonarCloud quality gate passed (80% coverage, 0 bugs/vulnerabilities)
- [ ] Database migrations tested in staging environment
- [ ] Rollback plan documented and tested
- [ ] On-call engineer notified and available
- [ ] Deployment window scheduled (prefer low-traffic hours: 02:00-06:00 UTC)

**Deployment Steps:**

1. **Deploy Green Environment:**

   ```bash
   # Tag new image
   export NEW_VERSION="v1.2.3"
   docker build -t villagecompute/storefront:$NEW_VERSION .
   docker push villagecompute/storefront:$NEW_VERSION

   # Deploy to green namespace
   kubectl create namespace storefront-green --dry-run=client -o yaml | kubectl apply -f -
   kubectl apply -f k8s/base/ -n storefront-green
   kubectl set image deployment/village-storefront \
     app=villagecompute/storefront:$NEW_VERSION -n storefront-green
   ```

2. **Run Database Migrations:**

   ```bash
   # Migrations are forward-compatible (safe to run before blue cutover)
   cd migrations
   mvn migration:up -Dmigration.env=production
   ```

3. **Health Check Green Environment:**

   ```bash
   # Wait for pods ready
   kubectl wait --for=condition=ready pod \
     -l app=village-storefront -n storefront-green \
     --timeout=300s

   # Verify health endpoints
   kubectl port-forward -n storefront-green svc/village-storefront 8080:8080 &
   curl http://localhost:8080/q/health/live
   curl http://localhost:8080/q/health/ready

   # Run smoke tests
   npm run smoke-test -- --env=green
   ```

4. **Cut Over Traffic (Blue → Green):**

   ```bash
   # Update ingress to point to green service
   kubectl patch ingress village-storefront \
     -p '{"spec":{"rules":[{"host":"*.villagecompute.com","http":{"paths":[{"backend":{"service":{"name":"village-storefront-green"}}}]}}]}}' \
     -n storefront

   # Verify traffic flowing to green
   kubectl logs -l app=village-storefront -n storefront-green --tail=100 | grep "HTTP"
   ```

5. **Monitor Green Environment (15 minutes):**

   ```bash
   # Watch key metrics
   watch -n 10 'kubectl top pods -l app=village-storefront -n storefront-green'

   # Check error rates
   # Open Grafana Platform Overview dashboard
   # Alert on error rate > 1% or p95 latency spike
   ```

6. **Decommission Blue (After Successful Monitoring):**

   ```bash
   # Scale down blue environment
   kubectl scale deployment/village-storefront --replicas=0 -n storefront

   # Delete blue namespace after 24 hours (allows rollback window)
   kubectl delete namespace storefront --wait=false
   ```

**Rollback Procedure (if issues detected):**

```bash
# Immediate rollback (< 5 minutes)
kubectl patch ingress village-storefront \
  -p '{"spec":{"rules":[{"host":"*.villagecompute.com","http":{"paths":[{"backend":{"service":{"name":"village-storefront"}}}]}}]}}' \
  -n storefront

# Verify traffic back to blue
kubectl logs -l app=village-storefront -n storefront --tail=100 | grep "HTTP"

# Scale down green
kubectl scale deployment/village-storefront --replicas=0 -n storefront-green

# Document rollback reason in incident channel
```

### Rolling Update (Minor Patches)

For low-risk changes (hotfixes, config updates), use rolling updates:

```bash
# Update deployment image
kubectl set image deployment/village-storefront \
  app=villagecompute/storefront:$NEW_VERSION -n storefront

# Monitor rollout
kubectl rollout status deployment/village-storefront -n storefront

# Rollback if issues
kubectl rollout undo deployment/village-storefront -n storefront
```

### Worker Deployment

Workers deploy independently from API pods:

```bash
# Update worker image
kubectl set image deployment/village-storefront-workers \
  worker=villagecompute/storefront:$NEW_VERSION -n storefront

# Workers use graceful shutdown (60s drain period)
# Monitor queue depth during rollout
watch -n 5 'kubectl logs -l component=worker --tail=20 -n storefront | grep "Shutdown"'
```

### Verification Script

Post-deployment smoke test checklist:

```bash
#!/bin/bash
# smoke-test.sh

set -e

echo "Running post-deployment smoke tests..."

# Test 1: Health endpoints
echo "✓ Testing health endpoints..."
curl -f http://api.villagecompute.com/q/health/live || exit 1
curl -f http://api.villagecompute.com/q/health/ready || exit 1

# Test 2: Tenant routing
echo "✓ Testing tenant routing..."
curl -f -H "Host: demo.villagecompute.com" \
  http://api.villagecompute.com/api/catalog/products?limit=1 || exit 1

# Test 3: Admin API authentication
echo "✓ Testing admin API..."
curl -f -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://api.villagecompute.com/admin/tenants || exit 1

# Test 4: Background job processing
echo "✓ Testing background jobs..."
QUEUE_DEPTH=$(curl -s http://prometheus:9090/api/v1/query \
  --data-urlencode 'query=media_processing_queue_depth{priority="critical"}' | jq -r '.data.result[0].value[1]')
if [[ "$QUEUE_DEPTH" -gt 100 ]]; then
  echo "ERROR: Critical queue backlog detected: $QUEUE_DEPTH"
  exit 1
fi

# Test 5: Database connectivity
echo "✓ Testing database..."
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c "SELECT 1;" > /dev/null || exit 1

echo "All smoke tests passed!"
```

---

<!-- anchor: incident-response-playbooks -->

## 3. Incident Response Playbooks

### Incident Classification

| Severity | Impact | Response Time | Escalation | Examples |
|----------|--------|---------------|------------|----------|
| **SEV-1 (P1)** | Platform-wide outage, data loss, security breach | < 15 minutes | Immediate page, escalate to CTO after 30m | Checkout down, tenant data leak, all payments failing |
| **SEV-2 (P2)** | Single tenant affected, degraded performance | < 1 hour | Slack notification, escalate after 2h | DLQ accumulation, slow API responses, single store offline |
| **SEV-3 (P3)** | Minor degradation, cosmetic issues | Next business day | Email digest | Non-critical metric drift, admin UI bug, slow dashboard |

### Playbook: Checkout Failure (SEV-1)

**Alert Name:** `CheckoutServiceDown`
**Trigger:** `checkout_initiated_total == 0` for 5 minutes OR error rate > 50%
**Related Diagrams:** [Checkout Sequence Diagram](../diagrams/sequence_checkout_payment.mmd)

**Immediate Actions:**

1. **Verify Alert Legitimacy:**

   ```bash
   # Check recent checkout attempts
   kubectl logs -l app=village-storefront --tail=200 -n storefront | grep "checkout.initiated"

   # Check error distribution
   kubectl logs -l app=village-storefront --tail=500 -n storefront | grep "ERROR" | grep -i checkout
   ```

2. **Check Upstream Dependencies:**

   ```bash
   # Stripe API status
   curl https://status.stripe.com/api/v2/status.json | jq '.status.description'

   # Database connectivity
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -c "SELECT 1;"

   # Redis cache (if applicable)
   kubectl exec -it redis-pod -n storefront -- redis-cli PING
   ```

3. **Enable Kill Switch if Systemic Issue:**

   ```bash
   # Block new checkouts, show maintenance page
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "checkout.kill-switch", "enabled": false, "reason": "Incident #456 - checkout errors"}' \
     https://api.villagecompute.com/admin/feature-flags

   # Verify kill switch active
   curl -f https://demo.villagecompute.com/checkout 2>&1 | grep -q "maintenance" && echo "Kill switch active"
   ```

4. **Rollback if Recent Deployment:**

   ```bash
   # Check last deployment time
   kubectl rollout history deployment/village-storefront -n storefront

   # Rollback to previous version
   kubectl rollout undo deployment/village-storefront -n storefront

   # Monitor recovery
   watch -n 10 'kubectl logs -l app=village-storefront --tail=50 -n storefront | grep checkout.initiated | tail -5'
   ```

5. **Post-Incident Recovery:**

   ```bash
   # Re-enable checkout after fix
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "checkout.kill-switch", "enabled": true}' \
     https://api.villagecompute.com/admin/feature-flags

   # Monitor checkout recovery
   # Expect checkout_initiated_total to resume normal rate
   ```

**Common Root Causes:**

- **Payment Gateway Failure:** Stripe API outage → enable circuit breaker, show "payment temporarily unavailable"
- **Inventory Lock Contention:** Database deadlocks → review `pg_stat_activity` for long-running transactions
- **Session Expiry:** JWT validation failures → check token signing key rotation
- **Shipping Rate API Failure:** All carriers down → fallback rates should activate automatically

### Playbook: Payment Processing Incident (SEV-1)

**Alert Name:** `StripeWebhookBacklog`
**Trigger:** `webhooks.stripe` queue depth > 100 OR webhook processing failure rate > 5%

**Response Steps:**

1. **Check Stripe Integration Health:**

   ```bash
   # Verify Stripe API key valid
   kubectl get secret stripe-api-key -n storefront -o jsonpath='{.data.secret_key}' | base64 -d | \
     xargs -I {} curl -u {}:  https://api.stripe.com/v1/payment_intents?limit=1

   # Check webhook signature validation
   kubectl logs -l app=village-storefront --tail=200 -n storefront | grep "webhook.signature.invalid"
   ```

2. **Inspect Webhook Queue:**

   ```bash
   # Check CRITICAL priority queue depth
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT COUNT(*), priority, status FROM stripe_webhook_jobs GROUP BY priority, status;"

   # Sample failed webhook payloads
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT id, payload, last_error FROM stripe_webhook_jobs WHERE status = 'failed' LIMIT 5;"
   ```

3. **Enable Circuit Breaker if Stripe Down:**

   ```bash
   # Disable webhook processing (queue jobs for later)
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "stripe.webhook.processing.enabled", "enabled": false, "reason": "Stripe API degraded"}' \
     https://api.villagecompute.com/admin/feature-flags

   # Verify workers stop processing
   kubectl logs -l component=worker --tail=100 -n storefront | grep "Stripe webhook processing disabled"
   ```

4. **Manual Webhook Replay (After Recovery):**

   ```bash
   # Re-enable processing
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "stripe.webhook.processing.enabled", "enabled": true}' \
     https://api.villagecompute.com/admin/feature-flags

   # Monitor queue drain rate
   watch -n 10 'kubectl exec -it postgres-pod -n storefront -- psql -U storefront -c \
     "SELECT COUNT(*) FROM stripe_webhook_jobs WHERE status = '\''pending'\'';"'
   ```

5. **Verify Payment Reconciliation:**

   ```bash
   # Check for stuck orders
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT COUNT(*) FROM orders WHERE status = 'PENDING_PAYMENT' AND created_at < NOW() - INTERVAL '1 hour';"

   # Manually reconcile via Stripe dashboard if needed
   # https://dashboard.stripe.com/payments
   ```

**Compensation Verification (Checkout Saga):**

```bash
# Verify inventory holds released for failed payments
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "SELECT o.id, o.status, COUNT(ir.id) AS active_reservations \
   FROM orders o \
   LEFT JOIN inventory_reservations ir ON ir.order_id = o.id AND ir.released_at IS NULL \
   WHERE o.status = 'CANCELLED' \
   GROUP BY o.id, o.status \
   HAVING COUNT(ir.id) > 0;"

# Verify loyalty points restored
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "SELECT c.id, c.email, SUM(lh.points) AS unreleased_holds \
   FROM customers c \
   JOIN loyalty_holds lh ON lh.customer_id = c.id \
   WHERE lh.status = 'active' AND lh.expires_at < NOW() \
   GROUP BY c.id, c.email;"
   ```

**Kill Switch / Rollback Checklist (Payments):**

- **Stripe Webhook Kill Switch:** Disable processing via `stripe.webhook.processing.enabled` to stop workers from acknowledging Stripe payloads until the incident stabilizes.

  ```bash
  curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"flag": "stripe.webhook.processing.enabled", "enabled": false, "reason": "Payment incident"}' \
    https://api.villagecompute.com/admin/feature-flags
  ```

- **Worker Rollback:** If the regression was deployed with the latest worker release, revert to the previous version to restore stable payment handlers.

  ```bash
  kubectl rollout undo deployment/village-storefront-workers -n storefront
  kubectl rollout status deployment/village-storefront-workers -n storefront
  ```

- **Resume Processing:** Once verified, re-enable the flag and monitor `webhooks.stripe` queue depth returning to baseline.

  ```bash
  curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"flag": "stripe.webhook.processing.enabled", "enabled": true}' \
    https://api.villagecompute.com/admin/feature-flags
  ```

### Playbook: Media Processing Backlog (SEV-2)

**Alert Name:** `MediaQueueBacklog`
**Trigger:** `media_processing_queue_depth{priority="default"} > 1000` for 10 minutes
**Related Diagrams:** [Media Pipeline Flow](../diagrams/media_pipeline.mmd)

**Response Steps:**

1. **Scale Worker Pods:**

   ```bash
   # Immediate scale-up
   kubectl scale deployment/village-storefront-workers --replicas=10 -n storefront

   # Verify workers starting
   kubectl get pods -l component=worker -n storefront -w
   ```

2. **Check FFmpeg Resource Usage:**

   ```bash
   # Monitor FFmpeg processes per worker
   kubectl exec -it <worker-pod> -n storefront -- ps aux | grep ffmpeg

   # Check worker memory usage
   kubectl top pods -l component=worker -n storefront

   # If OOMKilled pods detected
   kubectl describe pod <worker-pod> -n storefront | grep -A 10 "Last State"
   ```

3. **Inspect Failed Media Jobs:**

   ```bash
   # Check DLQ for media jobs
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT id, job_type, last_error, attempts FROM dead_letter_queue \
      WHERE queue_name = 'media.processing' AND resolved_at IS NULL \
      ORDER BY created_at DESC LIMIT 10;"
   ```

4. **Enable Kill Switch if Runaway Jobs:**

   ```bash
   # Stop FFmpeg spawning
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "media.processing.enabled", "enabled": false, "reason": "FFmpeg causing OOM"}' \
     https://api.villagecompute.com/admin/feature-flags

   # Verify workers skip jobs
   kubectl logs -l component=worker --tail=100 -n storefront | grep "Kill switch activated"
   ```

5. **Optimize Media Jobs (Long-Term):**

   ```bash
   # Identify heavy video jobs
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT AVG(EXTRACT(EPOCH FROM (completed_at - started_at))) AS avg_duration_seconds \
      FROM media_processing_jobs \
      WHERE job_type = 'video_transcoding' AND completed_at IS NOT NULL;"

   # Consider downgrading video jobs to LOW priority
   # Update enqueue logic in MediaService.java
   ```

**Media Rollback Playbook:**

- **Worker Deployment Rollback:** If the backlog started immediately after a deploy, revert the worker deployment to the previous ReplicaSet while keeping the API layer untouched.

  ```bash
  kubectl rollout undo deployment/village-storefront-workers -n storefront
  kubectl rollout status deployment/village-storefront-workers -n storefront
  ```

- **Kill Switch Reminder:** Keep `media.processing.enabled` disabled until FFmpeg pods are healthy and DLQ counts fall below SLA thresholds; re-enable gradually and monitor `media_processing_queue_depth`.

**Resource Limit Tuning (if frequent OOM):**

```yaml
# Update k8s/base/deployment-workers.yaml
resources:
  limits:
    cpu: "3000m"      # Increase from 2000m
    memory: "6Gi"     # Increase from 4Gi
```

### Playbook: POS Offline Sync Discrepancy (SEV-2)

**Alert Name:** `POSOfflineDiscrepancy`
**Trigger:** `pos_offline_batch_validation_failures > 5` in 1 hour
**Related Diagrams:** [POS Offline Sync](../diagrams/pos-offline.mmd)

**Response Steps:**

1. **Identify Discrepancy Source:**

   ```bash
   # Query batch validation errors
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT batch_id, tenant_id, validation_error, created_at \
      FROM pos_offline_batches \
      WHERE validation_status = 'FAILED' \
      ORDER BY created_at DESC LIMIT 10;"
   ```

2. **Dry-Run Replay:**

   ```bash
   # Replay batch in dry-run mode
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"batchId": "<batch-uuid>", "dryRun": true}' \
     https://api.villagecompute.com/admin/pos/offline/replay

   # Review dry-run results
   kubectl logs -l app=village-storefront --tail=200 -n storefront | grep "pos.replay.dryrun"
   ```

3. **Common Discrepancy Patterns:**

   **Cash Drawer Mismatch:**
   ```bash
   # Compare expected vs actual cash totals
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT batch_id, expected_cash_total, actual_cash_total, \
             (actual_cash_total - expected_cash_total) AS difference \
      FROM pos_offline_batches \
      WHERE ABS(actual_cash_total - expected_cash_total) > 5.00;"
   ```

   **Inventory Sync Conflict:**
   ```bash
   # Check for concurrent inventory updates
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT variant_id, COUNT(*) AS concurrent_updates \
      FROM pos_offline_transactions \
      WHERE batch_id = '<batch-uuid>' \
      GROUP BY variant_id HAVING COUNT(*) > 1;"
   ```

4. **Escalate to Finance (if > $50 discrepancy):**

   ```bash
   # Generate discrepancy report
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "COPY (SELECT * FROM pos_offline_batches WHERE validation_status = 'FAILED') \
      TO STDOUT WITH CSV HEADER" > pos_discrepancy_$(date +%Y%m%d).csv

   # Email to finance team
   mail -s "POS Discrepancy Report $(date +%Y-%m-%d)" -a pos_discrepancy_*.csv \
     finance@villagecompute.com < /dev/null
   ```

5. **Audit Log Review:**

   ```bash
   # Retrieve audit trail for batch
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT timestamp, event_type, actor_type, actor_id, metadata \
      FROM audit_log \
      WHERE metadata->>'batch_id' = '<batch-uuid>' \
      ORDER BY timestamp;"
   ```

**Prevention (Configuration Review):**

```bash
# Check POS device sync intervals
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "SELECT device_id, last_sync_at, sync_interval_minutes \
   FROM pos_devices \
   WHERE last_sync_at < NOW() - INTERVAL '12 hours';"

# Alert on stale devices
# Recommend 4-hour sync intervals for high-volume stores
```

**POS Offline Kill Switch & Rollback:**

- **Pause Offline Sync Processing:** Toggle the `pos.offline.sync.enabled` flag to stop ingesting new offline batches while you triage discrepancies.

  ```bash
  curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"flag": "pos.offline.sync.enabled", "enabled": false, "reason": "POS discrepancy triage"}' \
    https://api.villagecompute.com/admin/feature-flags
  ```

- **Rollback Offline Sync Service (if deploy related):** Revert the POS sync worker shard (either the dedicated `village-storefront-workers-pos` deployment or the shared `village-storefront-workers`) to the previous release.

  ```bash
  kubectl rollout undo deployment/village-storefront-workers-pos -n storefront || \
    kubectl rollout undo deployment/village-storefront-workers -n storefront

  kubectl rollout status deployment/village-storefront-workers-pos -n storefront || \
    kubectl rollout status deployment/village-storefront-workers -n storefront
  ```

- **Resume Sync:** Re-enable the flag and monitor `pos_offline_batch_jobs` plus replay success rate dashboards once discrepancy totals stabilize.

  ```bash
  curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"flag": "pos.offline.sync.enabled", "enabled": true}' \
    https://api.villagecompute.com/admin/feature-flags
  ```

### Playbook: Consignment Payout Failure (SEV-2)

**Alert Name:** `ConsignmentPayoutJobsFailed`
**Trigger:** `payouts.batch` queue failure rate > 10% OR dead letter queue accumulation
**Related Diagrams:** [Consignment Payout Sequence](../diagrams/sequence_consignment_payout.mmd)

**Response Steps:**

1. **Check Payout Job Failures:**

   ```bash
   # Query failed payout jobs
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT id, consignor_id, period_start, period_end, last_error, attempts \
      FROM payout_batch_jobs \
      WHERE status = 'failed' \
      ORDER BY created_at DESC LIMIT 10;"
   ```

2. **Common Payout Failures:**

   **ACH Provider Error (Stripe Connect / Dwolla):**
   ```bash
   # Check provider API status
   curl https://status.stripe.com/api/v2/status.json | jq

   # Review provider error codes
   kubectl logs -l component=worker --tail=500 -n storefront | grep "payout.ach.error"
   ```

   **Consignor Account Not Verified:**
   ```bash
   # Query unverified consignor accounts
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT c.id, c.business_name, ca.verification_status \
      FROM consignors c \
      JOIN consignor_accounts ca ON ca.consignor_id = c.id \
      WHERE ca.verification_status != 'VERIFIED';"
   ```

   **Payout Calculation Error:**
   ```bash
   # Manually recalculate payout totals
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT consignor_id, SUM(line_item_total * consignment_rate) AS expected_payout \
      FROM order_line_items \
      WHERE consignor_id = '<consignor-uuid>' \
        AND created_at BETWEEN '<period_start>' AND '<period_end>' \
      GROUP BY consignor_id;"
   ```

3. **Manual Payout Replay:**

   ```bash
   # After fixing underlying issue, replay failed jobs
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"batchId": "<batch-uuid>"}' \
     https://api.villagecompute.com/admin/consignment/payouts/replay

   # Verify job completion
   kubectl logs -l component=worker --tail=100 -n storefront | grep "payout.batch.completed"
   ```

4. **Hold Payout if Finance Escalation Required:**

   ```bash
   # Mark batch as ON_HOLD
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "UPDATE payout_batches \
      SET status = 'ON_HOLD', hold_reason = 'Pending finance review' \
      WHERE id = '<batch-uuid>';"

   # Notify finance team via Slack
   curl -X POST -H "Content-Type: application/json" \
     -d '{"text": "Payout batch <batch-uuid> on hold - requires review"}' \
     $SLACK_FINANCE_WEBHOOK_URL
   ```

5. **Post-Resolution Verification:**

   ```bash
   # Verify payout transfers initiated
   kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
     "SELECT pb.id, pb.consignor_id, pb.amount, pt.transfer_status \
      FROM payout_batches pb \
      JOIN payout_transfers pt ON pt.batch_id = pb.id \
      WHERE pb.id = '<batch-uuid>';"
   ```

**Consignment Payout Kill Switch & Rollback:**

- **Pause New Payout Generation:** Disable the `consignment.payout.enabled` flag to prevent new payout batches while existing ones are reconciled.

  ```bash
  curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"flag": "consignment.payout.enabled", "enabled": false, "reason": "Payout incident"}' \
    https://api.villagecompute.com/admin/feature-flags
  ```

- **Rollback Payout Worker Release:** Revert the worker deployment serving `payouts.batch` if the regression correlates with a deployment.

  ```bash
  kubectl rollout undo deployment/village-storefront-workers -n storefront
  kubectl rollout status deployment/village-storefront-workers -n storefront
  ```

- **Resume Payouts:** Re-enable the flag and monitor the `payouts.batch` queue latency plus ACH transfer status panels to ensure stability.

  ```bash
  curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
    -d '{"flag": "consignment.payout.enabled", "enabled": true}' \
    https://api.villagecompute.com/admin/feature-flags
  ```

---

<!-- anchor: background-job-management -->

## 4. Background Job Management

### Queue Monitoring

**Key Metrics:**

| Metric | Query | Alert Threshold | Action |
|--------|-------|-----------------|--------|
| Queue depth (CRITICAL) | `media_processing_queue_depth{priority="critical"}` | > 100 | Scale workers, investigate spike |
| Queue depth (DEFAULT) | `media_processing_queue_depth{priority="default"}` | > 1000 | Scale workers, review job efficiency |
| Job failure rate | `rate(media_processing_job_failed_total[5m]) / rate(media_processing_job_started_total[5m])` | > 5% | Check DLQ, review errors |
| DLQ accumulation | `rate(dead_letter_queue_added_total[5m])` | > 0.1 jobs/sec | Investigate failure pattern |
| Job duration (p95) | `histogram_quantile(0.95, media_processing_job_duration_seconds)` | > 2x SLA target | Optimize handler, check resource limits |

**Dashboard Panel Reference:** See [Background Job Health Dashboard](https://grafana.villagecompute.com/d/background-jobs)

### Worker Scaling

**Manual Scaling:**

```bash
# Scale up during traffic spike
kubectl scale deployment/village-storefront-workers --replicas=8 -n storefront

# Scale down during maintenance
kubectl scale deployment/village-storefront-workers --replicas=0 -n storefront

# Verify replica count
kubectl get deployment village-storefront-workers -n storefront
```

**HPA Tuning:**

```yaml
# k8s/base/hpa-workers.yaml
spec:
  minReplicas: 2       # Baseline capacity
  maxReplicas: 10      # Hard limit (cluster capacity)
  metrics:
  - type: Pods
    pods:
      metric:
        name: media_processing_queue_depth
      target:
        type: AverageValue
        averageValue: "100"  # Scale at 100 jobs per pod
```

**Capacity Planning Formula:**

```
required_replicas = ceil(queue_depth / (jobs_per_minute * target_latency_minutes))

Example:
- Queue depth: 2000 jobs
- Worker throughput: 10 jobs/minute (image processing)
- Target latency: 30 seconds (0.5 minutes)
- Required: ceil(2000 / (10 * 0.5)) = 400 replicas (exceeds max, queue overloaded!)
```

### Dead Letter Queue Management

**Inspect DLQ:**

```bash
# Group by error type
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "SELECT substring(last_error, 1, 100) AS error_prefix, COUNT(*) \
   FROM dead_letter_queue \
   WHERE resolved_at IS NULL \
   GROUP BY error_prefix \
   ORDER BY COUNT(*) DESC LIMIT 10;"

# Export unresolved jobs
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "COPY (SELECT * FROM dead_letter_queue WHERE resolved_at IS NULL) \
   TO STDOUT WITH CSV HEADER" > dlq_export_$(date +%Y%m%d).csv
```

**Replay Jobs After Fix:**

```bash
# Re-enqueue via admin API (after code fix deployed)
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"queueName": "media.processing", "maxJobs": 100}' \
  https://api.villagecompute.com/admin/jobs/dlq/replay

# Monitor replay success rate
watch -n 10 'kubectl exec -it postgres-pod -n storefront -- psql -U storefront -c \
  "SELECT COUNT(*) FROM dead_letter_queue WHERE resolved_at IS NULL;"'
```

**Bulk Resolve (After Manual Intervention):**

```bash
# Mark jobs as resolved
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "UPDATE dead_letter_queue \
   SET resolved_at = NOW(), resolution_notes = 'Manually resolved after incident #789' \
   WHERE resolved_at IS NULL AND queue_name = 'media.processing';"
```

### Queue Tuning Parameters

**Application Properties:** `application.properties`

```properties
# Queue capacity limits
jobs.queue.capacity.critical=100
jobs.queue.capacity.high=500
jobs.queue.capacity.default=1000
jobs.queue.capacity.low=5000
jobs.queue.capacity.bulk=20000

# Worker polling intervals
media.processing.dispatch-interval=3s       # How often workers poll
media.processing.worker.batch-size=10       # Jobs claimed per poll
media.processing.worker.timeout=300s        # Max job execution time

# FFmpeg resource limits
media.ffmpeg.max-concurrent=2               # Max FFmpeg processes per worker
media.ffmpeg.cpu-limit=2000m
media.ffmpeg.memory-limit=4Gi
media.ffmpeg.timeout=300                    # Seconds

# Retry policies
jobs.retry.critical.max-attempts=5
jobs.retry.high.max-attempts=3
jobs.retry.default.max-attempts=3
jobs.retry.low.max-attempts=3
jobs.retry.bulk.max-attempts=0
```

**When to Tune:**

- **Increase `batch-size`**: Worker CPU underutilized, queue depth growing slowly
- **Decrease `dispatch-interval`**: Job latency exceeds SLA, workers idle
- **Increase `ffmpeg.max-concurrent`**: Video jobs slow, worker CPU < 70%
- **Decrease `ffmpeg.max-concurrent`**: Workers hitting memory limits, OOMKilled events

---

<!-- anchor: monitoring-alerting -->

## 5. Monitoring & Alerting

### SLA Mappings

**Priority-Based SLAs (from [background_jobs.md](../architecture/background_jobs.md#priority-system-sla)):**

| Queue Type | Priority | P50 Target | P95 Target | P99 Target | Failure Rate | Alert P1 | Alert P2 |
|------------|----------|------------|------------|------------|--------------|----------|----------|
| `emails.transactional` | CRITICAL | 200ms | 800ms | 1.5s | < 0.1% | p99 > 4.5s | p95 > 1.6s |
| `webhooks.stripe` | CRITICAL | 150ms | 600ms | 1.2s | < 0.05% | p99 > 3.6s | p95 > 1.2s |
| `payouts.batch` | HIGH | 1.2s | 4.5s | 8s | < 0.5% | p99 > 24s | p95 > 9s |
| `media.processing` (images) | DEFAULT | 8s | 25s | 45s | < 1% | p99 > 135s | p95 > 50s |
| `media.processing` (video) | LOW | 90s | 4m | 8m | < 2% | p99 > 24m | p95 > 8m |
| `reports.export` | DEFAULT | 12s | 28s | 50s | < 1% | p99 > 150s | p95 > 56s |

**Alert Threshold Logic:**
- P1 (SEV-1): P99 latency > 3x target **OR** failure rate > 2x threshold
- P2 (SEV-2): P95 latency > 2x target **OR** failure rate > threshold

### Critical Metrics Catalog

**Queue Health Metrics:**

```promql
# Queue depth by priority
media_processing_queue_depth{priority="critical"}
media_processing_queue_depth{priority="default"}

# Job lifecycle counters
rate(media_processing_job_enqueued_total[5m])
rate(media_processing_job_completed_total[5m])
rate(media_processing_job_failed_total[5m])

# Job duration histogram
histogram_quantile(0.95, rate(media_processing_job_duration_seconds_bucket[5m]))

# DLQ accumulation
dead_letter_queue_depth{queue="media.processing"}
rate(dead_letter_queue_added_total[5m])
```

**Worker Resource Metrics:**

```promql
# CPU usage per worker
sum(rate(container_cpu_usage_seconds_total{pod=~"village-storefront-workers.*"}[5m])) by (pod)

# Memory usage per worker
container_memory_working_set_bytes{pod=~"village-storefront-workers.*"}

# FFmpeg active processes (custom metric - future implementation)
media_processing_ffmpeg_active_processes
```

**Checkout & Payment Metrics:**

```promql
# Checkout conversion funnel
rate(checkout_initiated_total[5m])
rate(checkout_payment_attempted_total[5m])
rate(checkout_completed_total[5m])

# Payment success rate
rate(payment_succeeded_total[5m]) / rate(payment_attempted_total[5m])

# Stripe webhook latency
histogram_quantile(0.95, rate(stripe_webhook_processing_duration_seconds_bucket[5m]))

# Compensation events (saga rollback)
rate(checkout_compensation_triggered_total[5m])
```

**Media Pipeline Metrics:**

```promql
# Upload → Ready funnel
rate(media_upload_negotiated_total[5m])
rate(media_processing_job_started_total[5m])
rate(media_asset_ready_total[5m])

# Derivative generation success
sum(rate(media_derivative_created_total[5m])) by (derivative_type)

# R2 bandwidth (custom metric - future)
rate(media_storage_upload_bytes_total[5m]) + rate(media_storage_download_bytes_total[5m])
```

**POS Offline Metrics:**

```promql
# Batch queue depth
pos_offline_batch_queue_depth

# Validation failures
rate(pos_offline_batch_validation_failures[1h])

# Cash discrepancies
sum(pos_offline_cash_discrepancy_total)
```

**Consignment Payout Metrics:**

```promql
# Payout job duration
histogram_quantile(0.95, rate(payout_batch_job_duration_seconds_bucket[5m]))

# ACH transfer failures
rate(payout_ach_transfer_failed_total[5m])

# Payout holds (manual review required)
payout_batches_on_hold
```

### Dashboard Navigation Guide

**Primary Dashboards (see [Quick Reference](#quick-reference) for URLs):**

1. **Platform Overview:**
   - System-wide health: API pods, workers, database connections
   - Traffic metrics: Requests/sec, error rate, p95 latency
   - Resource usage: CPU, memory, disk I/O

2. **Background Job Health:**
   - Queue depth by priority (stacked area chart)
   - Job throughput (success/failed rate, 5m window)
   - DLQ depth over time
   - Job duration percentiles (p50/p95/p99)
   - Worker pod resource usage

3. **Checkout & Payments:**
   - Checkout funnel conversion
   - Payment success/decline rates
   - Stripe webhook processing latency
   - Compensation event frequency

4. **Media Pipeline:**
   - Upload → Ready conversion funnel
   - Processing time by content type (image vs video)
   - FFmpeg active processes gauge
   - Derivative generation success rate

5. **POS Offline:**
   - Batch queue depth
   - Validation failure rate
   - Cash discrepancy tracking
   - Sync interval distribution

6. **Consignment Payouts:**
   - Payout job latency
   - ACH transfer status
   - Payout holds requiring review

### Alert Configuration

**Prometheus Alert Rules:** `k8s/base/prometheus-rules.yaml`

```yaml
groups:
- name: background_jobs_critical
  interval: 30s
  rules:
  - alert: CriticalQueueBacklog
    expr: media_processing_queue_depth{priority="critical"} > 100
    for: 2m
    labels:
      severity: critical
      team: platform
    annotations:
      summary: "CRITICAL priority queue backlog"
      description: "Media processing CRITICAL queue depth {{ $value }} exceeds threshold"
      runbook: "https://docs.villagecompute.com/operations/runbook.md#media-processing-backlog"

  - alert: DLQAccumulating
    expr: rate(dead_letter_queue_added_total[5m]) > 0.1
    for: 5m
    labels:
      severity: critical
      team: platform
    annotations:
      summary: "Dead letter queue accumulating failures"
      runbook: "https://docs.villagecompute.com/operations/runbook.md#dead-letter-queue-management"

- name: background_jobs_warning
  interval: 60s
  rules:
  - alert: HighJobFailureRate
    expr: |
      rate(media_processing_job_failed_total[5m])
      /
      rate(media_processing_job_started_total[5m]) > 0.05
    for: 10m
    labels:
      severity: warning
      team: platform
    annotations:
      summary: "Job failure rate exceeds 5%"
      runbook: "https://docs.villagecompute.com/operations/runbook.md#background-job-management"
```

**PagerDuty Integration:**

- **Critical Alerts (P1):** Page on-call engineer immediately
- **Warning Alerts (P2):** Slack notification to #platform-alerts
- **Info Alerts (P3):** Email digest (daily summary)

---

<!-- anchor: escalation-matrix -->

## 6. Escalation Matrix

### Incident Escalation Paths

| Incident Type | L1 (On-Call) | L2 (Team Lead) | L3 (CTO/CISO) | External Vendor |
|---------------|--------------|----------------|---------------|-----------------|
| **Checkout Down** | Immediate response, execute playbook | Escalate after 30m if unresolved | Escalate after 1h (SEV-1) | Stripe support (if payment gateway issue) |
| **Payment Failure Spike** | Check Stripe status, enable circuit breaker | Coordinate with finance team | N/A | Stripe support line |
| **Media Queue Backlog** | Scale workers, check FFmpeg | Review worker efficiency | N/A | Cloudflare R2 support |
| **POS Discrepancy** | Dry-run replay, audit logs | Escalate if > $50 discrepancy | N/A | Finance approval required |
| **Consignment Payout Failure** | Retry jobs, check ACH provider | Finance coordination | CFO (if fraud suspected) | Stripe Connect / Dwolla support |
| **Database Outage** | Check managed service status | DBA team, initiate failover | CTO (SEV-1) | AWS RDS / PostgreSQL support |
| **Tenant Isolation Breach** | Enable kill switches, capture audit IDs | Security team investigation | CISO (immediate) | Legal counsel |
| **Worker OOMKilled** | Increase memory limits, restart pods | Review heap dumps, code fix | N/A | N/A |

### On-Call Rotation

**Primary On-Call Engineer:**
- **Schedule:** PagerDuty rotation (7-day shifts)
- **Responsibilities:** First responder for P1/P2 alerts, execute runbook procedures
- **Escalation:** Notify team lead if incident unresolved after 30 minutes

**Secondary On-Call (Backup):**
- **Schedule:** PagerDuty backup rotation
- **Responsibilities:** Cover during primary unavailability, assist with multi-engineer incidents

**Escalation Triggers:**

| Time in Incident | Escalation Action | Notification Channel |
|------------------|-------------------|---------------------|
| 0 minutes | On-call engineer paged | PagerDuty |
| 15 minutes | Update Slack #incidents channel | Slack |
| 30 minutes | Escalate to engineering manager | PagerDuty + Slack mention |
| 1 hour (SEV-1) | Escalate to CTO | Phone call + PagerDuty |
| 2 hours (SEV-2) | Escalate to team lead | Slack mention |

### Contact Registry

**Platform Team:**
- On-Call Engineer: PagerDuty `@platform-oncall`
- Engineering Manager: Slack `@eng-manager`, email `eng-manager@villagecompute.com`
- Tech Lead: Slack `@tech-lead`, email `tech-lead@villagecompute.com`

**Infrastructure:**
- DBA Team: Slack `@dba-team`, PagerDuty `@dba-oncall`
- Infra Lead: Slack `@infra-lead`, email `infra@villagecompute.com`
- DevOps Team: Slack `#devops`, email `devops@villagecompute.com`

**Security:**
- Security Team: Slack `@security`, email `security@villagecompute.com`
- CISO: Emergency phone (1Password `CISO Contact`)

**Business:**
- Finance Team: Slack `@finance`, email `finance@villagecompute.com`
- CFO: Email `cfo@villagecompute.com` (payout/financial escalations)
- CTO: Emergency phone (1Password `CTO Contact`), Slack `@cto`

**External Vendors:**
- Stripe Support: `https://support.stripe.com` (Priority support line in 1Password)
- Cloudflare R2 Support: `https://www.cloudflare.com/support`
- AWS Support: Critical ticket via AWS Console (Premium Support plan)

---

<!-- anchor: routine-operations -->

## 7. Routine Operations

### Tenant Isolation Verification

**Periodic RLS Policy Audit (Monthly):**

```bash
# Verify RLS policies enabled on all tenant-scoped tables
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "SELECT schemaname, tablename, rowsecurity \
   FROM pg_tables \
   WHERE schemaname = 'public' \
   AND tablename IN ('orders', 'products', 'customers', 'inventory', 'media_assets') \
   ORDER BY tablename;"

# Expected output: rowsecurity = true for all tenant tables

# Test RLS enforcement with test tenant
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "SET app.tenant_id = '<test-tenant-uuid>'; \
   SELECT COUNT(*) FROM orders;"
```

**Cross-Tenant Data Leak Detection:**

```bash
# Audit log analysis for suspicious cross-tenant queries
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "SELECT event_type, actor_id, metadata->>'tenant_id' AS accessed_tenant, COUNT(*) \
   FROM audit_log \
   WHERE event_type LIKE 'data.access.%' \
   AND timestamp > NOW() - INTERVAL '7 days' \
   GROUP BY event_type, actor_id, metadata->>'tenant_id' \
   HAVING COUNT(*) > 1000 \
   ORDER BY COUNT(*) DESC;"

# Flag accounts accessing multiple tenants (potential impersonation abuse)
```

### Custom Domain SSL Certificate Management

**Automated Workflow (Task I5.T4):**

Village Storefront automates custom domain SSL provisioning using cert-manager + Let's Encrypt. The workflow is:

1. **Admin adds domain** via Platform Admin UI → Domain Settings (`/admin/platform/domains`)
2. **DNS verification** runs hourly via `DomainValidationJob` (queries TXT record at `_acme-challenge.<domain>`)
3. **Certificate provisioning** triggers automatically when verification succeeds (fires `CustomDomainVerified` CDI event)
4. **cert-manager** creates Let's Encrypt certificate via ACME DNS-01 challenge
5. **Certificate renewal** managed by cert-manager (auto-renews 30 days before expiry)

**Domain State Machine:**

- **PENDING** → Awaiting DNS verification (admin must add TXT record)
- **ACTIVE** → Verified and certificate issued
- **FAILED** → Verification failed (see error message in UI for resolution steps)

**Admin Interface:**

```bash
# Access domain management UI
open https://platform.villagecompute.com/admin/platform/domains

# View domain status via API
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://api.villagecompute.com/api/v1/admin/custom-domains

# Check verification job logs
kubectl logs -l app=village-storefront,component=domain-validation -n storefront --tail=100
```

**Manual Certificate Operations:**

```bash
# Check certificate status for all domains
kubectl get certificate -n storefront -o custom-columns=NAME:.metadata.name,READY:.status.conditions[?(@.type==\"Ready\")].status,SECRET:.spec.secretName,EXPIRY:.status.notAfter

# Check specific domain certificate
kubectl describe certificate shop-example-com -n storefront

# Force certificate renewal (if stuck or expiring)
kubectl delete certificate shop-example-com -n storefront
# cert-manager will automatically recreate from Certificate CRD

# Verify certificate secret created
kubectl get secret shop-example-com-tls -n storefront -o yaml
```

**Troubleshooting DNS Verification Failures:**

```bash
# 1. Check domain status in database
kubectl exec -it postgres-pod -n storefront -- psql -U storefront -d storefront -c \
  "SELECT domain, status, verification_error_message, verification_retry_count, last_verification_attempt
   FROM custom_domains
   WHERE domain = 'shop.example.com';"

# 2. Verify DNS TXT record exists and matches verification token
dig _acme-challenge.shop.example.com TXT +short
# Should return: "verification-token-from-database"

# 3. Check domain verification job logs for specific error
kubectl logs -l app=village-storefront,job=domain-validation -n storefront --tail=200 | grep "shop.example.com"

# 4. Manually trigger verification job (instead of waiting for hourly schedule)
kubectl exec -it village-storefront-pod -n storefront -- \
  curl -X POST http://localhost:8080/q/scheduler/trigger/verify-custom-domains

# 5. Common DNS issues:
# - TXT record not propagated (wait up to 24h)
# - Wrong record name (must be _acme-challenge.<domain>)
# - Wrong record value (copy from Platform Admin UI)
# - DNS provider doesn't support TXT records (use different provider)

# 6. Verify Ingress points to correct load balancer IP
kubectl get ingress village-storefront -n storefront -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
# Admin must point A/CNAME record to this IP
```

**cert-manager Troubleshooting:**

```bash
# Check cert-manager pod logs
kubectl logs -n cert-manager deploy/cert-manager --tail=100

# Check ACME Order status (shows challenge progression)
kubectl get orders -n storefront
kubectl describe order shop-example-com-1234567890 -n storefront

# Check Challenge status (DNS-01 validation details)
kubectl get challenges -n storefront
kubectl describe challenge shop-example-com-1234567890-challenge -n storefront

# Verify ClusterIssuer configuration
kubectl get clusterissuer letsencrypt-prod -o yaml

# Check rate limits (Let's Encrypt: 50 certs/week per domain)
# If rate limited, use staging issuer for testing:
kubectl patch certificate shop-example-com -n storefront \
  --type='json' -p='[{"op": "replace", "path": "/spec/issuerRef/name", "value":"letsencrypt-staging"}]'
```

**Retry Backoff Schedule:**

DomainValidationJob implements exponential backoff for failed verifications:

- **Retry 0:** Immediate (at domain creation)
- **Retry 1:** 1 hour after first failure
- **Retry 2:** 4 hours after retry 1
- **Retry 3:** 12 hours after retry 2
- **Retry 4+:** 24 hours between attempts (indefinite)

**Related Files:**

- Backend: `CustomDomainResource.java`, `DomainValidationJob.java`, `CertificateEventHandler.java`
- Frontend: `DomainSettingsView.vue` (Platform Admin UI)
- Infra: `infra/kustomize/base/cert-manager/issuer.yaml`
- Migration: `V20260132__custom_domain_ssl_automation.sql`

### Background Job Maintenance Windows

**Schedule Maintenance (Disable Job Processing):**

```bash
# Scale workers to 0
kubectl scale deployment/village-storefront-workers --replicas=0 -n storefront

# Verify no jobs processing
kubectl logs -l component=worker -n storefront --tail=50 | grep "Job started" | tail -10
# Should show no new jobs

# Perform maintenance
# - Database schema migrations
# - Job handler code updates
# - Worker configuration changes

# Resume workers
kubectl scale deployment/village-storefront-workers --replicas=3 -n storefront

# Monitor queue drain
watch -n 10 'kubectl exec -it postgres-pod -n storefront -- psql -U storefront -c \
  "SELECT queue_name, COUNT(*) FROM ( \
     SELECT '\''media.processing'\'' AS queue_name, COUNT(*) FROM media_processing_jobs WHERE status = '\''pending'\'' \
     UNION ALL \
     SELECT '\''payouts.batch'\'', COUNT(*) FROM payout_batch_jobs WHERE status = '\''pending'\'' \
   ) AS queues GROUP BY queue_name;"'
```

### Database Backup & Restore Testing

**Automated Backups (AWS RDS):**

- Full backups: Daily at 03:00 UTC (7-day retention)
- Point-in-time recovery: Continuous WAL archiving (up to 7 days back)

**Test Restore Procedure (Quarterly):**

```bash
# Restore to staging environment
aws rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier storefront-production \
  --target-db-instance-identifier storefront-staging-test-$(date +%Y%m%d) \
  --restore-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ)

# Wait for restore completion
aws rds wait db-instance-available \
  --db-instance-identifier storefront-staging-test-$(date +%Y%m%d)

# Run data integrity checks
kubectl exec -it postgres-staging-test -- psql -U storefront -d storefront -c \
  "SELECT COUNT(*) FROM orders; \
   SELECT COUNT(*) FROM products; \
   SELECT COUNT(*) FROM customers;"

# Cleanup test instance
aws rds delete-db-instance \
  --db-instance-identifier storefront-staging-test-$(date +%Y%m%d) \
  --skip-final-snapshot
```

### Log Retention & Archival

**Structured Logs (Elasticsearch/Loki):**

- Retention: 30 days hot storage, 90 days cold storage (S3)
- Tenant-scoped indexes for GDPR compliance

**Archive Logs for Compliance:**

```bash
# Export logs for specific tenant (GDPR data export request)
curl -X POST "https://elasticsearch:9200/logs-storefront-*/_search?scroll=5m" \
  -H "Content-Type: application/json" \
  -d '{
    "query": {
      "term": { "tenant_id": "<tenant-uuid>" }
    },
    "size": 1000
  }' > tenant_logs_export.json

# Archive to S3 compliance bucket
aws s3 cp tenant_logs_export.json \
  s3://villagecompute-compliance/logs/<tenant-uuid>/$(date +%Y%m%d)/
```

### Capacity Planning Reviews

**Monthly Review Checklist:**

- [ ] Review queue depth trends (identify growth patterns)
- [ ] Check worker HPA scale events (frequency of max replicas hit)
- [ ] Database connection pool usage (adjust max connections if > 80%)
- [ ] R2 storage growth rate (alert if > 500 GB/month increase)
- [ ] Review P95/P99 latency trends (identify degradation)
- [ ] Audit new jobs added (ensure proper priority assignment)
- [ ] Review DLQ patterns (identify systemic issues)

**Generate Capacity Report:**

```bash
# Query Prometheus for 30-day trends
curl -s "http://prometheus:9090/api/v1/query_range" \
  --data-urlencode 'query=max_over_time(media_processing_queue_depth[30d])' \
  --data-urlencode 'start='$(date -d '30 days ago' +%s) \
  --data-urlencode 'end='$(date +%s) \
  --data-urlencode 'step=1h' | jq

# Export to CSV for review
# (Use Grafana dashboard export feature for formatted reports)
```

---

<!-- anchor: references-resources -->

## 8. References & Resources

### Architecture Documentation

- **[Background Jobs Architecture](../architecture/background_jobs.md)** - Authoritative spec for DelayedJob framework, queue schemas, SLA commitments
- **[Platform Operations](../architecture/platform_ops.md)** - Worker deployments, HPA configurations, alert playbooks
- **[Tenant Isolation](../architecture/tenant_isolation.md)** - TenantContext management, RLS policies
- **[Operational Architecture](../architecture/04_Operational_Architecture.md)** - Section 3.10: Operational Runbooks and Incident Response

### Sequence Diagrams

- **[Checkout & Payment Saga](../diagrams/sequence_checkout_payment.mmd)** - Checkout flow with compensation hooks
- **[Consignment Payout Flow](../diagrams/sequence_consignment_payout.mmd)** - Payout calculation and ACH transfer
- **[Media Pipeline](../diagrams/media_pipeline.mmd)** - Media upload, processing, derivative generation
- **[POS Offline Sync](../diagrams/pos-offline.mmd)** - Offline transaction replay and reconciliation

### Monitoring & Alerting

- **Grafana Dashboards:** See [Dashboard Links](#dashboard-links)
- **Prometheus Alert Rules:** `k8s/base/prometheus-rules.yaml`
- **PagerDuty Integration:** `https://villagecompute.pagerduty.com`

### Kubernetes Manifests

- **API Deployment:** `k8s/base/deployment-api.yaml`
- **Worker Deployment:** `k8s/base/deployment-workers.yaml`
- **HPA Configuration:** `k8s/base/hpa-workers.yaml`
- **Ingress Rules:** `k8s/base/ingress.yaml`

### External Service Status Pages

- **Stripe:** `https://status.stripe.com`
- **Cloudflare R2:** `https://www.cloudflarestatus.com`
- **AWS RDS:** `https://status.aws.amazon.com`

### Standards & Compliance

- **[Java Project Standards](../java-project-standards.adoc)** - Section 8: Background Job Conventions
- **GDPR Compliance:** Tenant-scoped logging, data deletion procedures
- **PCI-DSS:** Payment data handling (Stripe tokenization, no card storage)

### Training & Onboarding

- **On-Call Training:** Internal wiki `https://wiki.villagecompute.com/oncall`
- **Runbook Exercises:** Quarterly game days (simulate incidents in staging)

---

## Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2026-01-12 | 1.0 | Initial runbook creation covering deployment, incidents, job management, monitoring | Platform Ops Team |

---

## Future Automation Tasks

**Logged for Future Implementation:**

1. **Automated DLQ Replay:** Admin API endpoint for bulk job re-enqueue after fixes
2. **Circuit Breaker Dashboard:** Real-time breaker state visualization in Grafana
3. **Tenant-Specific Scaling:** Auto-scale workers based on per-tenant queue depth
4. **Predictive Scaling:** ML-based worker scaling for anticipated traffic patterns
5. **Self-Healing Compensation:** Automated saga rollback verification and correction
6. **POS Offline Auto-Reconciliation:** Automated discrepancy resolution for < $5 differences
7. **Cost Optimization Alerts:** R2 egress, Stripe API call volume monitoring

**Review Cadence:** After each SEV-1/SEV-2 incident or monthly (whichever is sooner)

---

**Document Owner:** Platform Operations Team
**Next Review:** 2026-02-12
**Feedback:** Submit improvements via GitHub Issues or #platform-ops Slack channel
