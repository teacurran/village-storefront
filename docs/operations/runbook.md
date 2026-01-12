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
   # Set environment and image tag
   export ENVIRONMENT="staging"  # or "production"
   export NEW_VERSION="v1.2.3"

   # Option A: Using GitHub Actions (recommended)
   # Trigger deployment workflow via GitHub UI or gh CLI:
   gh workflow run deploy.yaml \
     -f environment=$ENVIRONMENT \
     -f image_tag=$NEW_VERSION

   # Option B: Manual deployment with kustomize
   export GREEN_NAMESPACE="village-storefront-${ENVIRONMENT}-green"

   # Create green namespace
   kubectl create namespace $GREEN_NAMESPACE --dry-run=client -o yaml | kubectl apply -f -

   # Retarget overlay namespace + image tag without touching git
   TMP_DIR=$(mktemp -d)
   cp -R infra/kustomize "$TMP_DIR/infra"
   pushd "$TMP_DIR/infra/kustomize/overlays/$ENVIRONMENT"
   kustomize edit set namespace $GREEN_NAMESPACE
   kustomize edit set image ghcr.io/villagecompute/village-storefront=ghcr.io/villagecompute/village-storefront:$NEW_VERSION
   kustomize build . | kubectl apply --dry-run=server -f - >/dev/null
   kustomize build . | kubectl apply -f -
   popd
   ```

2. **Run Database Migrations:**

   ```bash
   # Migrations are forward-compatible (safe to run before blue cutover)
   cd migrations
   mvn migration:up -Dmigration.env=production

   # GitHub Actions workflow runs migrations automatically for production
   ```

3. **Health Check Green Environment:**

   ```bash
   # Wait for pods ready
   kubectl wait --for=condition=ready pod \
     -l app=village-storefront,component=gateway \
     -n village-storefront-${ENVIRONMENT}-green \
     --timeout=300s

   kubectl wait --for=condition=ready pod \
     -l app=village-storefront,component=workers \
     -n village-storefront-${ENVIRONMENT}-green \
     --timeout=300s

   kubectl wait --for=condition=ready pod \
     -l app=village-storefront,component=media-workers \
     -n village-storefront-${ENVIRONMENT}-green \
     --timeout=300s

   # Verify health endpoints
   kubectl port-forward -n village-storefront-${ENVIRONMENT}-green svc/village-storefront-gateway 8080:8080 &
   curl http://localhost:8080/q/health/live
   curl http://localhost:8080/q/health/ready

   # GitHub Actions workflow runs automated smoke tests
   npm run smoke-test -- --env=green
   ```

4. **Cut Over Traffic (Blue → Green):**

   ```bash
   # Update ingress to point to green service
   kubectl patch ingress village-storefront-ingress \
     -n village-storefront-${ENVIRONMENT} \
     --type='json' \
     -p='[{"op": "replace", "path": "/spec/rules/0/http/paths/0/backend/service/name", "value":"village-storefront-gateway"}]'

   # Verify traffic flowing to green
   kubectl logs -l app=village-storefront,component=gateway \
     -n village-storefront-${ENVIRONMENT}-green --tail=100 | grep "HTTP"

   # Note: GitHub Actions workflow includes manual approval gate before cutover
   ```

5. **Monitor Green Environment (15 minutes):**

   ```bash
   # Watch key metrics
   watch -n 10 'kubectl top pods -l app=village-storefront -n village-storefront-${ENVIRONMENT}-green'

   # Check error rates
   # Open Grafana Platform Overview dashboard
   # Alert on error rate > 1% or p95 latency spike

   # GitHub Actions workflow monitors for 15 minutes automatically
   ```

6. **Decommission Blue (After Successful Monitoring):**

   ```bash
   # Scale down blue environment
   kubectl scale deployment/village-storefront-gateway --replicas=0 -n village-storefront-${ENVIRONMENT}
   kubectl scale deployment/village-storefront-workers --replicas=0 -n village-storefront-${ENVIRONMENT}
   kubectl scale deployment/village-storefront-media-workers --replicas=0 -n village-storefront-${ENVIRONMENT}

   # Delete blue namespace after 24 hours (allows rollback window)
   kubectl delete namespace village-storefront-${ENVIRONMENT} --wait=false

   # GitHub Actions workflow scales down blue automatically after monitoring
   ```

**Rollback Procedure (if issues detected):**

```bash
# Immediate rollback (< 5 minutes)
export ENVIRONMENT="staging"  # or "production"

kubectl patch ingress village-storefront-ingress \
  -n village-storefront-${ENVIRONMENT} \
  --type='json' \
  -p='[{"op": "replace", "path": "/spec/rules/0/http/paths/0/backend/service/name", "value":"village-storefront-gateway"}]'

# Verify traffic back to blue
kubectl logs -l app=village-storefront,component=gateway \
  -n village-storefront-${ENVIRONMENT} --tail=100 | grep "HTTP"

# Scale down green
kubectl scale deployment/village-storefront-gateway --replicas=0 -n village-storefront-${ENVIRONMENT}-green
kubectl scale deployment/village-storefront-workers --replicas=0 -n village-storefront-${ENVIRONMENT}-green

# Document rollback reason in incident channel
# GitHub Actions workflow automatically rolls back on smoke test or monitoring failures
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

### Kustomize Overlay Quick Reference

**Directory Structure:**
```
infra/kustomize/
├── base/                          # Base manifests (gateway, workers, media-workers)
│   ├── deployment.yaml            # Gateway deployment + Service + HPA + PDB
│   ├── workers-deployment.yaml    # Job workers deployment + Service + HPA + PDB
│   ├── media-worker-deployment.yaml  # Media processing workers
│   └── kustomization.yaml         # Base kustomization config
├── overlays/
│   ├── dev/                       # Dev overlay (min resources)
│   │   ├── kustomization.yaml
│   │   ├── ingress.yaml
│   │   └── patches/
│   ├── staging/                   # Staging environment overlay
│   │   ├── kustomization.yaml     # Staging-specific patches
│   │   ├── ingress.yaml           # Staging ingress rules
│   │   ├── network-policy.yaml    # Network policies
│   │   ├── sealed-secrets/        # Placeholder sealed secrets (Stripe/SMTP)
│   │   └── patches/               # Strategic merge patches
│   └── production/                # Production overlay (full HA, sealed secrets)
│       ├── kustomization.yaml
│       ├── ingress.yaml
│       ├── network-policy.yaml
│       ├── priority-class.yaml
│       ├── service-monitor.yaml
│       ├── sealed-secrets/
│       └── patches/
```

**Common Commands:**

```bash
# Validate manifests without applying
kustomize build infra/kustomize/overlays/staging | kubectl apply --dry-run=client -f -

# Build and preview manifests
kustomize build infra/kustomize/overlays/staging > /tmp/manifests.yaml
cat /tmp/manifests.yaml

# Apply overlay to cluster
kubectl apply -k infra/kustomize/overlays/staging

# Diff against current cluster state
kustomize build infra/kustomize/overlays/staging | kubectl diff -f -

# Delete all resources from overlay
kubectl delete -k infra/kustomize/overlays/staging
```

**Secrets Management:**

Secrets are NOT stored in git. Use Sealed Secrets or External Secrets Operator. The `infra/kustomize/overlays/*/sealed-secrets/` directories contain placeholders for Stripe + SMTP—regenerate `encryptedData` with `kubeseal` before applying.

```bash
# Create sealed secret for database credentials
kubectl create secret generic village-storefront-db \
  --namespace village-storefront-staging \
  --from-literal=jdbc-url='jdbc:postgresql://postgres-staging.internal:5432/storefront_staging' \
  --from-literal=username='storefront_staging' \
  --from-literal=password='<STAGING_DB_PASSWORD>' \
  --dry-run=client -o yaml | \
  kubeseal -o yaml > infra/kustomize/overlays/staging/sealed-secrets/db.yaml

# Cloudflare R2 access
kubectl create secret generic village-storefront-r2 \
  --namespace village-storefront-staging \
  --from-literal=access-key-id='<R2_ACCESS_KEY>' \
  --from-literal=secret-access-key='<R2_SECRET_KEY>' \
  --dry-run=client -o yaml | \
  kubeseal -o yaml > infra/kustomize/overlays/staging/sealed-secrets/r2.yaml

# Stripe (test mode) API keys
kubectl create secret generic village-storefront-stripe \
  --namespace village-storefront-staging \
  --from-literal=api-key='sk_test_...' \
  --from-literal=webhook-secret='whsec_test_...' \
  --dry-run=client -o yaml | \
  kubeseal -o yaml > infra/kustomize/overlays/staging/sealed-secrets/stripe.yaml

# SMTP credentials (SendGrid token etc.)
kubectl create secret generic village-storefront-mailer \
  --namespace village-storefront-staging \
  --from-literal=username='apikey' \
  --from-literal=password='<STAGING_SMTP_TOKEN>' \
  --dry-run=client -o yaml | \
  kubeseal -o yaml > infra/kustomize/overlays/staging/sealed-secrets/smtp.yaml

# Apply sealed secrets
kubectl apply -f infra/kustomize/overlays/staging/sealed-secrets/db.yaml
kubectl apply -f infra/kustomize/overlays/staging/sealed-secrets/r2.yaml
kubectl apply -f infra/kustomize/overlays/staging/sealed-secrets/stripe.yaml
kubectl apply -f infra/kustomize/overlays/staging/sealed-secrets/smtp.yaml

# Verify secrets created
kubectl get secrets -n village-storefront-staging | grep village-storefront
```

**Updating Overlays:**

```bash
# Edit base manifests (affects all environments)
vim infra/kustomize/base/deployment.yaml

# Edit environment-specific patches
vim infra/kustomize/overlays/staging/patches/gateway-staging.yaml

# Update image tags in kustomization.yaml
vim infra/kustomize/overlays/staging/kustomization.yaml
# Change: newTag: staging-abc123

# Apply changes
kubectl apply -k infra/kustomize/overlays/staging
```

**Troubleshooting:**

```bash
# Validate kustomization syntax
kustomize build infra/kustomize/overlays/staging > /dev/null

# Check which resources will be created
kustomize build infra/kustomize/overlays/staging | kubectl apply --dry-run=server -f - -o json | \
  jq -r '.items[] | "\(.kind)/\(.metadata.name)"'

# Find mismatched labels/selectors
kustomize build infra/kustomize/overlays/staging | \
  grep -E "selector|matchLabels" -A 3

# Verify ConfigMap merging
kustomize build infra/kustomize/overlays/staging | \
  grep -A 20 "kind: ConfigMap"
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

**Prometheus Alert Rules:** `infra/kustomize/base/prometheus-rules.yaml`

**Alert Groups Deployed (Task I5.T5):**

1. **background_job_queue_health** - Queue depth alerts for media, email, webhooks, and DLQ
2. **job_latency_sla** - SLA breach detection for P95/P99 latency thresholds
3. **pos_offline_queue_health** - POS offline sync queue and validation monitoring
4. **checkout_payment_health** - Payment success rate and compensation event tracking
5. **worker_resource_health** - Worker pod CPU/memory saturation detection
6. **job_failure_rates** - Job failure rate threshold monitoring

**Key Alert Examples:**

```yaml
# MediaQueueDepthCritical (SEV-1)
- alert: MediaQueueDepthCritical
  expr: media_processing_queue_depth{priority="critical"} > 50
  for: 5m

# StripeWebhookLatencyP1 (SEV-1)
- alert: StripeWebhookLatencyP1
  expr: histogram_quantile(0.99, sum(rate(stripe_webhook_processing_duration_seconds_bucket[5m])) by (le)) > 3.6
  for: 5m

# POSOfflineQueueDepthCritical (SEV-1)
- alert: POSOfflineQueueDepthCritical
  expr: pos_offline_batch_queue_depth > 100
  for: 10m

# PaymentSuccessRateLow (SEV-1)
- alert: PaymentSuccessRateLow
  expr: (rate(payment_succeeded_total[5m]) / rate(payment_attempted_total[5m])) < 0.95
  for: 10m
```

**Alert Routing & Notification Policies**

#### PagerDuty Integration

**Service Configuration:**

- **Service Name:** `Village Storefront - Production`
- **Integration Key:** Configured in Prometheus Alertmanager
- **Escalation Policy:** L1 (5 min) → L2 (15 min) → L3 (30 min)

**Alert Severity Routing:**

- **SEV-1 (sev1):** Immediate PagerDuty page + Slack #incidents channel + StatusPage auto-update
- **SEV-2 (sev2):** Slack #platform-alerts notification + Email to on-call
- **SEV-3 (sev3):** Email digest (hourly rollup)

**Alertmanager Configuration Example:**

```yaml
route:
  receiver: 'default'
  group_by: ['alertname', 'component']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  routes:
  # SEV-1: Immediate escalation
  - match:
      severity: sev1
    receiver: 'pagerduty-critical'
    group_wait: 10s
    repeat_interval: 15m
    continue: true

  - match:
      severity: sev1
    receiver: 'slack-incidents'
    continue: true

  - match:
      severity: sev1
    receiver: 'statuspage-automation'

  # SEV-2: Slack + Email
  - match:
      severity: sev2
    receiver: 'slack-platform-alerts'
    continue: true

  - match:
      severity: sev2
    receiver: 'email-oncall'

receivers:
- name: 'pagerduty-critical'
  pagerduty_configs:
  - service_key: '${PAGERDUTY_INTEGRATION_KEY}'
    severity: 'critical'
    description: '{{ .GroupLabels.alertname }}: {{ .Annotations.summary }}'
    details:
      firing: '{{ template "pagerduty.default.instances" . }}'
      resolved: '{{ template "pagerduty.default.instances" . }}'
      runbook: '{{ .Annotations.runbook_url }}'
      dashboard: '{{ .Annotations.dashboard_url }}'

- name: 'slack-incidents'
  slack_configs:
  - api_url: '${SLACK_WEBHOOK_URL_INCIDENTS}'
    channel: '#incidents'
    title: 'SEV-1 Alert: {{ .GroupLabels.alertname }}'
    text: '{{ .Annotations.description }}'
    actions:
    - type: button
      text: 'View Runbook'
      url: '{{ .Annotations.runbook_url }}'
    - type: button
      text: 'View Dashboard'
      url: '{{ .Annotations.dashboard_url }}'

- name: 'slack-platform-alerts'
  slack_configs:
  - api_url: '${SLACK_WEBHOOK_URL_ALERTS}'
    channel: '#platform-alerts'
    title: '{{ .GroupLabels.alertname }}'
    text: '{{ .Annotations.summary }}'

- name: 'statuspage-automation'
  webhook_configs:
  - url: '${STATUSPAGE_WEBHOOK_URL}'
    send_resolved: true
```

#### StatusPage Automation

**Automated Component Status Updates:**

Webhook payload format for StatusPage API integration:

```json
{
  "component_id": "{{ if eq .GroupLabels.component \"checkout\" }}chkout123{{ else if eq .GroupLabels.component \"media-processing\" }}media456{{ end }}",
  "status": "{{ if eq .Status \"firing\" }}major_outage{{ else }}operational{{ end }}",
  "message": "{{ .Annotations.summary }}",
  "incident_updates": [
    {
      "body": "{{ .Annotations.description }}",
      "status": "{{ if eq .Status \"firing\" }}investigating{{ else }}resolved{{ end }}"
    }
  ]
}
```

**Component Mappings:**

| Alert Component | StatusPage Component ID | Component Name |
|-----------------|------------------------|----------------|
| `checkout` | `chkout123` | Checkout & Payments |
| `media-processing` | `media456` | Media Upload & Processing |
| `pos-offline` | `pos789` | POS Offline Sync |
| `webhooks` | `webhook012` | Webhook Processing |
| `job-system` | `jobs345` | Background Jobs |

**Incident Status Mapping:**

- **SEV-1 Alert Firing** → StatusPage: `major_outage` or `partial_outage`
- **SEV-2 Alert Firing** → StatusPage: `degraded_performance`
- **Alert Resolved** → StatusPage: `operational`

### Log Shipping & Retention (per §3.7 Observability Fabric)

- **Pipeline Overview:** Pods emit structured JSON logs (fields: `tenant_id`, `store_id`, `user_id`, `session_id`, `correlation_id`, `impersonation_context`) to stdout. A `fluent-bit` DaemonSet tails `/var/log/containers/*` and forwards batches to the OpenTelemetry Collector (`component=otel-collector`) via OTLP/HTTP logs receiver.
- **Section 3 Alignment:** Collector processors enforce §3.7 and §3.13.2 policies by enriching entries with `cluster.name`/`environment` attributes and deleting sensitive fields (`payment_token`, `raw_card_number`, `pii_masked`) via the `attributes/log_redaction` processor before export.
- **Destinations:** Logs are streamed simultaneously to (1) **Elastic Cloud** (`vs-prod-hot` index) for 30-day search, (2) **GCS cold storage** bucket `gs://vc-storefront-logs` for 13-month retention, and (3) **PagerDuty Events API** for SEV-1 payload enrichment. Exporters live in `infra/kustomize/base/observability/otel-collector-configmap.yaml`.
- **Access Controls:** Elastic IAM role `logs.viewer` grants read-only access to on-call engineers. Requests for elevated access (bulk export, longer retention review) must follow the governance process outlined in §3.7 Observability Fabric and be approved by security.
- **Correlation & Searchability:** `X-Request-ID` headers propagate via Quarkus filters; Fluent Bit ensures the ID is present on every log entry. Use this field to pivot between logs and Jaeger traces, mirroring §3 correlation guidance.
- **Validation Steps:**

```bash
# Ensure DaemonSet running on every node
kubectl get daemonset fluent-bit -n observability

# Spot-check raw structured logs
kubectl logs -n village-storefront deploy/village-storefront-api --tail=5 | jq

# Confirm collector receives log payloads (HTTP 200 == success)
kubectl port-forward -n village-storefront svc/otel-collector 4318:4318 &
curl -X POST http://localhost:4318/v1/logs -d '{}' -H 'Content-Type: application/json'

# Verify Elastic hot index is rolling
curl -u "${ELASTIC_USER}:${ELASTIC_PASS}" \
  https://logs.villagecompute.com/_cat/indices/vs-prod-hot?v
```

### Observability Stack Verification Commands

**OpenTelemetry Collector Health Check:**

```bash
# Check collector pod status
kubectl get pods -n village-storefront -l component=otel-collector

# Verify collector endpoints
kubectl port-forward -n village-storefront svc/otel-collector 4317:4317 &
curl -v http://localhost:4317  # Should connect (gRPC endpoint)

# Check collector metrics
kubectl port-forward -n village-storefront svc/otel-collector 8888:8888 &
curl http://localhost:8888/metrics | grep otelcol

# View collector logs
kubectl logs -n village-storefront -l component=otel-collector --tail=100 -f

# Check trace export to Jaeger
kubectl port-forward -n observability svc/jaeger-query 16686:16686 &
open http://localhost:16686  # Verify traces appear for "village-storefront" service
```

**Prometheus Alert Rules Validation:**

```bash
# Verify PrometheusRule resource applied
kubectl get prometheusrules -n village-storefront

# Check rule evaluation status
kubectl port-forward -n observability svc/prometheus 9090:9090 &
curl http://localhost:9090/api/v1/rules | jq '.data.groups[] | select(.name | contains("village"))'

# Test specific alert query
curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query=media_processing_queue_depth{priority="critical"}' | jq

# View active alerts
curl http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | select(.labels.app == "village-storefront")'
```

**Grafana Dashboard Verification:**

```bash
# Port-forward to Grafana
kubectl port-forward -n observability svc/grafana 3000:3000 &

# Access dashboards
open http://localhost:3000/d/background-jobs      # Background Job Health
open http://localhost:3000/d/checkout-payments    # Checkout & Payments
open http://localhost:3000/d/media-pipeline       # Media Pipeline
open http://localhost:3000/d/pos-offline-sync     # POS Offline Sync
open http://localhost:3000/d/platform-overview    # Platform Overview

# Test dashboard via API
curl -H "Authorization: Bearer ${GRAFANA_API_KEY}" \
  http://localhost:3000/api/dashboards/uid/background-jobs | jq
```

**Metrics Export Verification:**

```bash
# Check Quarkus app emitting metrics
kubectl port-forward -n village-storefront svc/village-storefront-api 8080:8080 &
curl http://localhost:8080/q/metrics | grep media_processing_queue_depth

# Verify Prometheus scraping app metrics
curl http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.app == "village-storefront")'

# Check metric availability in Prometheus
curl -s http://localhost:9090/api/v1/query \
  --data-urlencode 'query={app="village-storefront"}' | jq '.data.result | length'
```

**Alert Firing Simulation (Testing Only):**

```bash
# Simulate queue depth alert by scaling down workers
kubectl scale deployment village-storefront-media-workers -n village-storefront --replicas=0

# Wait 5-10 minutes, then check alert status
curl http://localhost:9090/api/v1/alerts | jq '.data.alerts[] | select(.labels.alertname == "MediaQueueDepthCritical")'

# Verify PagerDuty incident created (check PagerDuty dashboard)

# Restore workers
kubectl scale deployment village-storefront-media-workers -n village-storefront --replicas=2
```

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

<!-- anchor: chaos-engineering-drills -->

## 8. Chaos Engineering Drills

### Overview

Chaos engineering drills validate platform resilience by simulating failure scenarios in controlled environments. These drills are executed as part of release verification (Task I5.T7) and should be run quarterly in staging to maintain operational readiness.

**Location:** `scripts/qa/chaos/`

**Execution via E2E Runner:** Set `RUN_CHAOS_TESTS=true` when running `scripts/qa/run_e2e.sh`

### Database Failover Drill

**Script:** `scripts/qa/chaos/db_failover.sh`

**Scenario:** Simulates PostgreSQL primary failure and validates automatic failover, application reconnection, RLS policy integrity, and smoke test recovery.

**Validation Steps:**
1. Trigger managed PostgreSQL failover event
2. Monitor application reconnection behavior (target: <30s)
3. Verify RLS policies remain intact (3/3 tables)
4. Validate read-side cache invalidation
5. Run smoke tests across storefront + admin flows

**Success Criteria:**
- Failover completes within 60 seconds
- Application reconnects within 30 seconds
- No data loss (WAL replication verified)
- All smoke tests pass post-failover (4/4)

**Usage:**
```bash
# Staging environment (safe for testing)
./scripts/qa/chaos/db_failover.sh --environment staging

# Production (requires explicit confirmation)
./scripts/qa/chaos/db_failover.sh --environment production
```

**Related Runbook Sections:** §3 Incident Response Playbooks (Database Failover procedure)

#### Latest Execution (2026-01-12 – Task I5.T7)

- **Environment:** Staging (`village-storefront-staging`). Output archived at `target/chaos-drills/db_failover.log`.
- **Metrics Observed:** Failover completed in 47s, application reconnect in 23s, RLS verification 3/3 tables, smoke suite 4/4.
- **Remediation:** Added `initialFailFast=true` and readiness probe `failureThreshold=3` to storefront API deployments; refreshed catalog caches immediately after failover (PR #742).
- **References:** Release readiness report §3.1 plus Grafana `/d/checkout-payments` screenshots linked in `reports/release_readiness.md`.

### Worker Pod Crash Drill

**Script:** `scripts/qa/chaos/worker_crash.sh`

**Scenario:** Force-kills all worker pods to simulate catastrophic failure and validates job recovery, queue integrity, and graceful degradation behavior.

**Validation Steps:**
1. Enqueue 100 test jobs across priorities (CRITICAL, HIGH, DEFAULT)
2. Force-kill all worker pods (no graceful shutdown)
3. Monitor worker auto-scaling and restart (target: <2min)
4. Verify in-flight jobs retry correctly
5. Confirm no jobs moved to DLQ incorrectly (target: 0 false positives)
6. Validate queue drains to baseline (<10min)

**Success Criteria:**
- Workers restart within 2 minutes (liveness probe + HPA)
- In-flight jobs retry automatically
- No jobs lost or moved to DLQ without legitimate failures
- Queue drains to baseline within 10 minutes

**Usage:**
```bash
# Staging environment
./scripts/qa/chaos/worker_crash.sh --environment staging

# Production (use with extreme caution)
./scripts/qa/chaos/worker_crash.sh --environment production
```

**Related Runbook Sections:** §4 Background Job Management, §5 Monitoring & Alerting (DLQ alerts)

#### Latest Execution (2026-01-12 – Task I5.T7)

- **Environment:** Staging. Execution log: `target/chaos-drills/worker_crash.log`.
- **Metrics Observed:** Worker fleet recovered in 71s, 100/100 seeded jobs retried, DLQ false positives 0, queue depth returned to baseline in 8 minutes.
- **Remediation:** Increased HPA minReplicas from 2 -> 3, added `startupProbe` to media workers, and tagged chaos jobs with `tenant_id='test-tenant-chaos'` to keep DLQ noise-free.
- **References:** Release readiness report §3.2 and Grafana `/d/background-jobs` panel captures included with the log bundle.

### Payment Gateway Outage Drill

**Script:** `scripts/qa/chaos/payment_outage.sh` (future implementation)

**Scenario:** Simulates Stripe API unavailability and validates circuit breaker activation, compensation hooks, and customer error messaging.

**Validation Steps:**
1. Configure test environment to reject Stripe API calls
2. Attempt 20 checkout flows
3. Verify circuit breaker activates after threshold failures
4. Validate customers see graceful error message
5. Confirm inventory holds released via compensation hooks
6. Re-enable Stripe API and verify recovery

**Success Criteria:**
- Circuit breaker opens after 5 consecutive failures
- Checkout shows graceful error (not 500)
- Inventory reservations compensated correctly
- Circuit breaker closes after 3 successes
- No orders stuck in PENDING_PAYMENT

**Status:** Planned for future implementation

**Related Runbook Sections:** §3.3 Playbook: Checkout Saga Failure Patterns

> **Note:** Automation tracked under QA-219. Until the script ships, follow §3.3 manual procedure (toggle `checkout.kill-switch`, enforce offline messaging, replay orders once Stripe recovers). Risk acceptance recorded in `reports/release_readiness.md` §7.

### Drill Execution Cadence

| Drill Type | Frequency | Environment | Approval Required |
|------------|-----------|-------------|-------------------|
| Database Failover | Quarterly | Staging | Platform Ops Lead |
| Worker Crash | Quarterly | Staging | Platform Ops Lead |
| Payment Outage | Quarterly (once implemented) | Staging | Engineering Manager |
| Full Drill Suite | Pre-Release (I5.T7) | Staging | QA Lead + CTO |

**Drill Logging:** All drill executions are logged to `target/chaos-drills/` with timestamped results. Failed drills must be documented in incident reports with remediation plans.

**Production Drills:** Production chaos drills require explicit CTO approval and should only be executed during low-traffic maintenance windows with full on-call team availability.

---

<!-- anchor: references-resources -->

## 9. References & Resources

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

## Security Operations

### Encryption Key Rotation

**Purpose:** Periodically rotate application-level encryption keys used for sensitive PII (consignor tax IDs, payment tokens, POS device secrets) to limit exposure window in case of compromise.

**Schedule:**
- **Standard rotation:** Annual (every 365 days)
- **Emergency rotation:** Immediately upon suspected compromise
- **Monitoring alert:** Warning if key age > 400 days, critical if > 450 days

**Key Types:**

| Key Purpose | Storage Location | Rotation Frequency |
|------------|------------------|-------------------|
| Application master key (AES-256) | K8s Secret `encryption-keys` | Annual |
| Consignor PII encryption | Derived from master key | Annual |
| POS device secrets | K8s Secret per tenant | Quarterly |
| JWT signing key (HMAC-SHA256) | K8s Secret `jwt-secret` | Biannual |

#### Automated Rotation Procedure

**Pre-Rotation Checklist:**
- [ ] Verify backup window completed successfully (last 24h)
- [ ] Confirm no active deployments or schema migrations
- [ ] Alert on-call team of scheduled rotation (1 hour notice)
- [ ] Run staging rotation first (validate procedure)

**Automated Rotation Script:**

```bash
#!/bin/bash
# File: scripts/rotate-encryption-keys.sh
# Description: Automated encryption key rotation with re-encryption of existing data

set -euo pipefail

NAMESPACE="storefront"
KEY_NAME="encryption-keys"
NEW_KEY=$(openssl rand -base64 32)
TIMESTAMP=$(date +%Y%m%d-%H%M%S)

echo "🔑 Starting encryption key rotation - $TIMESTAMP"

# Step 1: Generate new encryption key
echo "Generating new encryption key..."
kubectl create secret generic $KEY_NAME-new \
  --from-literal=master-key=$NEW_KEY \
  --from-literal=rotation-timestamp=$TIMESTAMP \
  --from-literal=previous-key=$(kubectl get secret $KEY_NAME -o jsonpath='{.data.master-key}' | base64 -d) \
  --namespace=$NAMESPACE \
  --dry-run=client -o yaml | kubectl apply -f -

# Step 2: Deploy application with dual-key support (reads old, writes new)
echo "Deploying application with dual-key support..."
kubectl set env deployment/village-storefront \
  ENCRYPTION_KEY_NEW="$NEW_KEY" \
  ENCRYPTION_KEY_PREVIOUS="$(kubectl get secret $KEY_NAME -o jsonpath='{.data.master-key}' | base64 -d)" \
  --namespace=$NAMESPACE

kubectl rollout status deployment/village-storefront --namespace=$NAMESPACE --timeout=5m

# Step 3: Trigger re-encryption background job
echo "Triggering re-encryption background job..."
kubectl exec -it deployment/village-storefront --namespace=$NAMESPACE -- \
  curl -X POST http://localhost:8080/api/internal/re-encrypt \
    -H "Authorization: Bearer $(kubectl get secret ops-api-token -o jsonpath='{.data.token}' | base64 -d)" \
    -H "Content-Type: application/json" \
    -d '{"keyRotationTimestamp": "'$TIMESTAMP'"}'

# Step 4: Monitor re-encryption progress
echo "Monitoring re-encryption progress (this may take 10-30 minutes)..."
while true; do
  PROGRESS=$(kubectl exec deployment/village-storefront --namespace=$NAMESPACE -- \
    curl -s http://localhost:8080/api/internal/re-encrypt/status | jq -r '.percentComplete')

  echo "Re-encryption progress: $PROGRESS%"

  if [ "$PROGRESS" = "100" ]; then
    echo "✅ Re-encryption complete"
    break
  fi

  sleep 30
done

# Step 5: Switch to new key only (remove dual-key support)
echo "Switching to new key only..."
kubectl create secret generic $KEY_NAME \
  --from-literal=master-key=$NEW_KEY \
  --from-literal=rotation-timestamp=$TIMESTAMP \
  --namespace=$NAMESPACE \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl set env deployment/village-storefront \
  ENCRYPTION_KEY_NEW- \
  ENCRYPTION_KEY_PREVIOUS- \
  --namespace=$NAMESPACE

kubectl rollout status deployment/village-storefront --namespace=$NAMESPACE --timeout=5m

# Step 6: Archive old key for 90-day retention (compliance requirement)
echo "Archiving old key to S3..."
kubectl get secret $KEY_NAME-new -o json | \
  jq '.data."previous-key"' | \
  aws s3 cp - s3://villagecompute-key-archive/rotation-$TIMESTAMP-previous-key.txt

# Step 7: Verify encryption with new key
echo "Verifying encryption with new key..."
kubectl exec deployment/village-storefront --namespace=$NAMESPACE -- \
  curl -s http://localhost:8080/api/internal/encryption/verify | jq

echo "🎉 Key rotation complete - $TIMESTAMP"
```

#### Manual Rotation Override

**Emergency Rotation (Suspected Compromise):**

```bash
# Immediate rotation without re-encryption (accept temporary dual-key period)
NEW_KEY=$(openssl rand -base64 32)

kubectl create secret generic encryption-keys \
  --from-literal=master-key=$NEW_KEY \
  --from-literal=rotation-timestamp=$(date +%Y%m%d-%H%M%S) \
  --from-literal=previous-key=$(kubectl get secret encryption-keys -o jsonpath='{.data.master-key}' | base64 -d) \
  --namespace=storefront \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl rollout restart deployment/village-storefront --namespace=storefront

# Schedule re-encryption for next maintenance window
# Add note to incident ticket for follow-up
```

#### Re-Encryption Job Implementation

**Background Job Handler:**

The re-encryption job queries all tables with encrypted columns and updates records in batches:

```sql
-- Example: Re-encrypt consignor SSNs
UPDATE consignors
SET ssn_encrypted = pgp_sym_encrypt(
    pgp_sym_decrypt(ssn_encrypted, <old_key>),
    <new_key>
)
WHERE tenant_id = :tenantId
  AND updated_at < :rotationTimestamp
LIMIT 1000;
```

**Job Priority:** HIGH (complete within 2 hours to minimize dual-key window)

**Metrics:**
- `encryption.rotation.progress` (gauge: 0-100)
- `encryption.rotation.records_reencrypted` (counter)
- `encryption.rotation.duration_seconds` (histogram)

#### Monitoring & Alerts

**Prometheus Alerts:**

```yaml
- alert: EncryptionKeyAgeTooHigh
  expr: time() - encryption_key_rotation_timestamp_seconds > (400 * 86400)
  for: 24h
  labels:
    severity: warning
  annotations:
    summary: "Encryption key age exceeds 400 days"
    description: "Key last rotated {{ $value | humanizeDuration }} ago. Schedule rotation."

- alert: EncryptionKeyRotationFailed
  expr: encryption_key_rotation_errors_total > 0
  for: 5m
  labels:
    severity: critical
  annotations:
    summary: "Encryption key rotation failed"
    description: "Re-encryption job failed {{ $value }} times. Investigate immediately."
```

**Grafana Dashboard:**
- Key age gauge (days since last rotation)
- Re-encryption progress bar (during active rotation)
- Historical rotation timeline (annotations for each rotation)

---

### Privacy Request Workflow (GDPR/CCPA)

**Purpose:** Handle data subject access requests (DSAR) and right-to-erasure requests in compliance with GDPR Article 15/17 and CCPA Section 1798.110.

**SLA:**
- Data export: 30 days (typically complete within 5 minutes)
- Account deletion: Immediate soft-delete, 90-day purge retention

#### Data Export Request Workflow

**Customer Self-Service Path:**

```
1. Customer clicks "Download My Data" in Account Settings
   → POST /api/v1/privacy/export

2. System creates PrivacyRequest (status: PENDING_REVIEW)
   → Auto-approved for self-service requests
   → Enqueued in HIGH priority job queue

3. Background job processes export (ComplianceService.handleExportJob)
   → Queries all tenant-scoped entities for customer data
   → Streams JSONL files (users, orders, sessions, consents, etc.)
   → Generates CSV summaries for human readability
   → Creates manifest.json with checksums

4. ZIP file uploaded to R2 storage
   → Storage key: tenant-{tenant_id}/exports/{request_id}.zip
   → Pre-signed download URL generated (72-hour expiry)

5. Customer receives email notification
   → "Your data export is ready"
   → Download link expires in 72 hours

6. Customer downloads ZIP file
   → Status: COMPLETED
```

**Platform Admin Path (Manual Review):**

```
1. Support receives GDPR request via email/ticket
   → Log into platform admin console

2. Admin submits export request on behalf of customer
   → POST /api/v1/platform/compliance/privacy-requests/export
   → Status: PENDING_REVIEW

3. Compliance officer reviews and approves
   → POST /api/v1/platform/compliance/privacy-requests/{id}/approve
   → Rationale documented in approval notes

4-6. Same as self-service path
```

#### Data Deletion Request Workflow

**Customer Self-Service Path:**

```
1. Customer clicks "Delete My Account" in Account Settings
   → Confirmation modal warns of irreversible action
   → Requires re-authentication (password confirmation)

2. System creates PrivacyRequest (type: DELETE, status: PENDING_REVIEW)
   → POST /api/v1/privacy/delete
   → Auto-approved for self-service requests

3. Background job executes Phase 1: Soft Delete
   → UPDATE users SET deleted_at = NOW() WHERE id = :userId
   → Cascades to addresses, payment_methods, carts, sessions
   → Order history preserved (merchant compliance requirement)
   → Status: AWAITING_PURGE

4. Customer account deactivated immediately
   → Cannot log in (authentication filter checks deleted_at)
   → Profile hidden from UI

5. After 90-day retention period, scheduled job executes Phase 2: Purge
   → DELETE FROM users WHERE deleted_at < NOW() - INTERVAL '90 days'
   → Cascades via foreign key ON DELETE CASCADE
   → R2 objects deleted: tenant-{id}/user-{id}/*
   → Audit logs anonymized (email → 'REDACTED')
   → Status: COMPLETED
```

**Emergency Deletion (Manual Escalation):**

```bash
# For legal hold, court order, or user safety concerns
# Requires dual approval (compliance officer + engineering lead)

kubectl exec deployment/village-storefront --namespace=storefront -- \
  psql -U storefront -d storefront -c \
    "SELECT compliance_emergency_delete(
       p_tenant_id := '<tenant-uuid>',
       p_user_email := 'user@example.com',
       p_reason := 'Court order - case #12345',
       p_approved_by := 'compliance.officer@villagecompute.com'
     );"

# Function logs to platform_commands table (audit trail)
# Bypasses 90-day retention (immediate purge)
```

#### Safeguards & Validations

**Deletion Blockers (Return 409 Conflict):**

```sql
-- Check for active subscriptions
SELECT COUNT(*) FROM subscription_memberships
WHERE user_id = :userId
  AND status = 'active'
  AND cancel_at_period_end = false;

-- Check for pending consignor payouts
SELECT COUNT(*) FROM payout_batches pb
JOIN consignors c ON c.id = pb.consignor_id
WHERE c.user_id = :userId
  AND pb.status IN ('pending', 'processing');

-- If any blockers exist, return error:
{
  "error": "cannot_delete_account",
  "reason": "Active subscription or pending payouts",
  "blockers": [
    {"type": "subscription", "id": "sub_123"},
    {"type": "payout", "id": "pay_456"}
  ]
}
```

**Rate Limiting:**

```java
// Privacy request rate limit: 1 per user per 24 hours
@RateLimit(limit = 1, window = Duration.ofHours(24), key = "user_email")
public UUID requestDataExport() { ... }
```

#### Monitoring Privacy Workflows

**Key Metrics:**

```promql
# Export job completion rate
rate(compliance_export_completed_total[5m])

# Export job failure rate (alert if > 1%)
rate(compliance_export_failed_total[5m]) / rate(compliance_export_requested_total[5m]) * 100

# Deletion job lag (alert if > 95 days, approaching legal deadline)
max(time() - privacy_request_created_at{type="delete",status="awaiting_purge"})

# Privacy request queue depth (alert if > 100)
sum(compliance_privacy_request_queue_depth) by (type)
```

**Grafana Dashboard:**

- Privacy request funnel (requested → approved → completed)
- Export job duration histogram (P50/P95/P99)
- Deletion pipeline stages (soft-delete → awaiting_purge → purged)
- SLA compliance gauge (% completed within 30 days)

#### Incident Response: Failed Privacy Request

**Scenario:** Export job fails after 3 retries, customer ticket escalated

**Investigation:**

```bash
# Check job status and error logs
kubectl exec deployment/village-storefront --namespace=storefront -- \
  psql -U storefront -c \
    "SELECT pr.id, pr.status, pr.error_message, pr.created_at, pr.updated_at
     FROM privacy_requests pr
     WHERE pr.id = '<request-id>';"

# Check background job attempts
kubectl logs -l component=worker --namespace=storefront | \
  grep "PrivacyExportJobHandler" | \
  grep "<request-id>"

# Common failure causes:
# - R2 upload timeout (large data export)
# - Database query timeout (customer with 1M+ orders)
# - Out of memory (export ZIP buffer too large)
```

**Resolution:**

```bash
# Manual retry with increased resources
kubectl scale deployment/village-storefront-workers --replicas=5 --namespace=storefront

# Re-enqueue job
curl -X POST http://localhost:8080/api/internal/privacy/retry/<request-id> \
  -H "Authorization: Bearer $(kubectl get secret ops-api-token -o jsonpath='{.data.token}' | base64 -d)"

# Monitor completion
watch -n 5 "kubectl exec deployment/village-storefront -- \
  psql -U storefront -c \"SELECT status FROM privacy_requests WHERE id = '<request-id>'\""
```

**Post-Incident:**
- Update runbook with failure pattern
- Add retry logic improvements to backlog
- Document escalation path for future incidents

#### Compliance Audit Trail

**Query All Privacy Operations:**

```sql
-- Platform admin query: All privacy requests for compliance audit
SELECT
  pr.id AS request_id,
  pr.request_type,
  pr.status,
  pr.requester_email,
  pr.subject_email,
  pr.reason,
  pr.ticket_number,
  pr.created_at,
  pr.approved_by_email,
  pr.approved_at,
  pr.completed_at,
  pc.action AS platform_command_action,
  pc.actor_email AS audit_actor,
  pc.reason AS audit_rationale
FROM privacy_requests pr
LEFT JOIN platform_commands pc ON pc.id = pr.platform_command_id
WHERE pr.tenant_id = '<tenant-uuid>'
  AND pr.created_at >= '2025-01-01'
ORDER BY pr.created_at DESC;
```

**Export Audit Log for External Review:**

```bash
# Generate compliance report for GDPR audit
kubectl exec deployment/village-storefront --namespace=storefront -- \
  psql -U storefront -d storefront -c \
    "COPY (
       SELECT * FROM privacy_requests
       WHERE created_at BETWEEN '2025-01-01' AND '2025-12-31'
       ORDER BY created_at
     ) TO STDOUT WITH CSV HEADER" > privacy_audit_2025.csv

# Upload to compliance archive
aws s3 cp privacy_audit_2025.csv \
  s3://villagecompute-compliance/audits/2025/privacy-requests.csv
```

---

## Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2026-01-12 | 1.1 | Added encryption key rotation and privacy workflow procedures | Platform Ops Team |
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
