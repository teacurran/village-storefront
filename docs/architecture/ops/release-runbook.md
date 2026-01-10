# Village Storefront Release Runbook

**Version:** 1.0
**Last Updated:** 2026-01-10
**Owner:** Platform Engineering Team

---

## Table of Contents

<!-- anchor: toc -->
1. [Overview](#overview)
2. [Pre-Release Checklist](#pre-release-checklist)
3. [Release Execution](#release-execution)
4. [Post-Release Verification](#post-release-verification)
5. [Feature Flag Rollout Strategy](#feature-flag-rollout-strategy)
6. [Rollback Procedures](#rollback-procedures)
7. [Incident Response](#incident-response)
8. [Release Schedule](#release-schedule)

---

<!-- anchor: overview -->
## Overview

This runbook documents the end-to-end process for releasing new versions of Village Storefront to production. Our release process uses:

- **Blue/Green Deployment**: Zero-downtime deployments with instant rollback capability
- **Feature Flags**: Progressive rollout with tenant-level control and emergency kill switches
- **Artifact Signing**: Cosign keyless signing with GitHub OIDC attestation
- **Automated Pipeline**: GitHub Actions workflow with quality gates and manual approval

### Release Principles

1. **Safety First**: All releases must pass quality gates before production deployment
2. **Reversibility**: Every release must have a tested rollback procedure
3. **Progressive Rollout**: Use feature flags to gradually expose new functionality
4. **Monitoring**: Continuous observation of error rates, latency, and resource usage
5. **Communication**: Notify stakeholders at each stage of the release process

---

<!-- anchor: pre-release-checklist -->
## Pre-Release Checklist

**Complete this checklist before creating a release tag.**

### 1. Quality Gates

Verify all CI quality gates have passed on the main branch:

```bash
# Check latest CI run status
gh run list --workflow=ci.yml --branch=main --limit=1

# View detailed status
gh run view --web
```

**Required Checks:**
- [ ] ✅ Spotless formatting passed
- [ ] ✅ OpenAPI spec validation passed
- [ ] ✅ OWASP dependency check passed (no CVSS ≥7 vulnerabilities)
- [ ] ✅ Trivy container scan passed (no CRITICAL/HIGH vulnerabilities)
- [ ] ✅ JVM tests passed with ≥80% coverage
- [ ] ✅ Native tests passed
- [ ] ✅ E2E visual regression tests passed (Percy)
- [ ] ✅ Lighthouse performance tests passed (LCP <2s)
- [ ] ✅ Admin SPA tests passed
- [ ] ✅ SonarCloud quality gate passed (≥80% coverage, 0 bugs, 0 vulnerabilities)
- [ ] ✅ Kubernetes manifests validated

### 2. Dependency Audit

Review dependency updates and security advisories:

```bash
# Check for outdated dependencies
./mvnw versions:display-dependency-updates

# Check for security vulnerabilities
./mvnw org.owasp:dependency-check-maven:check

# Review npm dependencies
npm outdated
npm audit
```

**Criteria:**
- [ ] No critical security vulnerabilities (CVSS ≥7)
- [ ] All direct dependencies reviewed and approved
- [ ] Breaking changes documented in CHANGELOG.md

### 3. Feature Flag Audit

Review all feature flags to determine rollout strategy:

```sql
-- Connect to production database
psql -U storefront -d villagestore

-- List all feature flags
SELECT
    flag_key,
    enabled,
    tenant_id,
    description,
    updated_at
FROM feature_flags
ORDER BY flag_key;

-- Check for active kill switches (should be FALSE before release)
SELECT flag_key, enabled
FROM feature_flags
WHERE flag_key LIKE '%.disable'
  AND enabled = true
  AND tenant_id IS NULL;
```

**Required State:**
- [ ] All emergency kill switches disabled (`checkout.disable`, `payment.disable`, `impersonation.disable`, `media.disable` = FALSE)
- [ ] New feature flags deployed with `enabled = false` (dark launch)
- [ ] Canary tenant list prepared for progressive rollout
- [ ] Rollback flags ready (previous version toggles documented)

### 4. Migration Review

Verify database migrations are safe and reversible:

```bash
cd migrations

# List pending migrations
mvn migration:pending -Dmigration.env=production

# Review migration SQL
cat src/main/migrations/scripts/<YYYYMMDDHHMMSS>_<description>.sql

# Verify down script exists
cat src/main/migrations/scripts/<YYYYMMDDHHMMSS>_<description>_down.sql
```

**Migration Safety Checklist:**
- [ ] Migration tested in staging environment
- [ ] Down script prepared and tested
- [ ] Migration does not lock tables for >5 seconds
- [ ] No destructive changes without backwards-compatible toggle
- [ ] Data transformation scripts validated with sample data

### 5. Monitoring & Alerting

Ensure observability is ready for release:

```bash
# Check Prometheus targets
curl -s http://prometheus.villagecompute.com/api/v1/targets | jq '.data.activeTargets[] | select(.labels.app=="village-storefront")'

# Verify Grafana dashboards accessible
curl -f https://grafana.villagecompute.com/api/dashboards/db/village-storefront-overview

# Check alert rules active
curl -s http://prometheus.villagecompute.com/api/v1/rules | jq '.data.groups[].rules[] | select(.name | contains("village"))'
```

**Monitoring Readiness:**
- [ ] Prometheus scraping all village-storefront pods
- [ ] Grafana dashboards updated with new metrics
- [ ] Alert rules configured for critical paths
- [ ] On-call rotation scheduled in PagerDuty
- [ ] Incident communication channels verified (Slack #incidents)

### 6. Documentation Review

Ensure release documentation is current:

- [ ] CHANGELOG.md updated with release notes
- [ ] API documentation updated (OpenAPI spec)
- [ ] Deployment architecture diagrams current
- [ ] Feature flag documentation updated
- [ ] Rollback procedures reviewed and tested

---

<!-- anchor: release-execution -->
## Release Execution

### Step 1: Create Release Tag

**Timing:** After all pre-release checks pass, typically during business hours (9 AM - 3 PM PT) on Tuesday-Thursday.

```bash
# Ensure on main branch with latest changes
git checkout main
git pull origin main

# Determine next version (semantic versioning)
CURRENT_VERSION=$(git describe --tags --abbrev=0)
echo "Current version: ${CURRENT_VERSION}"

# Increment version (patch, minor, or major)
NEW_VERSION="v1.2.3"  # Update based on semver rules

# Create annotated tag
git tag -a ${NEW_VERSION} -m "Release ${NEW_VERSION}

## Changes
- Feature: Multi-tender payment support (#123)
- Fix: Consignment payout calculation edge case (#124)
- Chore: Update dependencies to latest stable (#125)

## Database Migrations
- 20260110120000_add_payment_tenders.sql

## Feature Flags
- payment.multi_tender.enabled (default: false)

See CHANGELOG.md for full details."

# Push tag to trigger release workflow
git push origin ${NEW_VERSION}
```

**Verification:**
```bash
# Verify tag pushed
git ls-remote --tags origin | grep ${NEW_VERSION}

# Monitor workflow start
gh run watch --workflow=release.yml
```

### Step 2: Monitor Build Job

The release workflow automatically:
1. Builds native executable with GraalVM
2. Builds and pushes Docker image to GHCR
3. Signs image with cosign (keyless OIDC)
4. Generates SBOM (Software Bill of Materials)

**Monitoring:**
```bash
# Watch build job
gh run view --log --job=build

# Check build duration (should be <45 min)
gh run view --json jobs -q '.jobs[] | select(.name=="Build Native Image & Publish") | .conclusion, .steps[].completed_at'
```

**Expected Output:**
- ✅ Native executable built (~5-10 min)
- ✅ Docker image pushed to `ghcr.io/villagecompute/village-storefront:${VERSION}`
- ✅ Image signed with cosign
- ✅ SBOM uploaded as artifact

**On Build Failure:**
- Review logs: `gh run view --log --job=build`
- Common issues: GraalVM reflection config, dependency conflicts, Docker build errors
- Fix issue, delete tag, restart from Step 1

### Step 3: Deploy to Staging (Automatic)

The `deploy-staging` job runs automatically after build:
1. Verifies image signature with cosign
2. Checks feature flags (no active kill switches)
3. Updates Kubernetes manifests with new image tag
4. Runs database migrations
5. Deploys to staging namespace
6. Runs smoke tests

**Monitoring:**
```bash
# Watch staging deployment
gh run view --log --job=deploy-staging

# Check staging pods
kubectl get pods -n village-storefront-staging -l app=village-storefront

# Follow staging logs
kubectl logs -f deployment/village-storefront-workers -n village-storefront-staging --tail=100
```

**Smoke Test Verification:**
```bash
STAGING_URL="https://staging.villagecompute.com"

# Health checks
curl -f "${STAGING_URL}/q/health/ready"
curl -f "${STAGING_URL}/q/health/live"

# Metrics available
curl -f "${STAGING_URL}/q/metrics" | grep jvm_

# Version verification
curl -f "${STAGING_URL}/q/info" | jq '.version'
```

**On Staging Failure:**
- Review deployment logs: `kubectl describe pod -n village-storefront-staging`
- Check migration logs: `kubectl logs -n village-storefront-staging job/migration-${VERSION}`
- If issue is code-related: delete tag, fix, restart
- If issue is infrastructure-related: investigate staging environment, may proceed to prod if low-risk

### Step 4: Deploy to Production (Manual Approval)

The `deploy-production` job requires manual approval via GitHub Environments.

**Approval Process:**
1. Navigate to: `https://github.com/villagecompute/village-storefront/actions`
2. Click on the release workflow run
3. Review staging deployment summary
4. Click "Review deployments" button
5. Select "production" environment
6. Click "Approve and deploy"

**Approval Criteria:**
- [ ] Staging deployment successful
- [ ] Smoke tests passed in staging
- [ ] No active incidents or alerts
- [ ] On-call engineer available
- [ ] Feature flags configured for progressive rollout
- [ ] Release notes reviewed and approved

**Blue/Green Deployment Steps:**

The workflow automatically executes blue/green deployment:

1. **Verify Production Readiness**
   ```bash
   # Cosign signature verification
   cosign verify \
     --certificate-identity-regexp="https://github.com/villagecompute/village-storefront" \
     --certificate-oidc-issuer="https://token.actions.githubusercontent.com" \
     ghcr.io/villagecompute/village-storefront:${VERSION}

   # Feature flag verification (no active kill switches)
   kubectl exec -n village-storefront postgres-primary-0 -- \
     psql -U storefront -d villagestore -c \
     "SELECT flag_key FROM feature_flags WHERE flag_key LIKE '%.disable' AND enabled = true AND tenant_id IS NULL;"
   ```

2. **Backup Current State**
   ```bash
   # Deployment manifests saved as artifacts
   # Accessible via: gh run download ${RUN_ID} --name deployment-backup-${SHA}
   ```

3. **Run Migrations**
   ```bash
   # Automatic via Kubernetes Job
   kubectl logs -f job/migration-${VERSION} -n village-storefront
   ```

4. **Deploy Blue Environment**
   ```bash
   # Current deployment labeled "green"
   # New deployment labeled "blue"
   # Both running side-by-side
   kubectl get deployment -n village-storefront -L deployment-color
   ```

5. **Smoke Tests (Blue)**
   ```bash
   # Automated health checks against blue pods
   # Traffic still routed to green
   ```

6. **Switch Traffic (Green → Blue)**
   ```bash
   # Service selector updated to route to blue
   kubectl patch service village-storefront-workers \
     -n village-storefront \
     -p '{"spec":{"selector":{"deployment-color":"blue"}}}'
   ```

7. **Monitor Post-Switch (2 minutes)**
   ```bash
   # Automated monitoring of error rates and latency
   ```

8. **Scale Down Green**
   ```bash
   # Green scaled to 1 replica (retained for rollback)
   ```

**Monitoring During Deployment:**
```bash
# Watch deployment progress
gh run view --log --job=deploy-production

# Check pod status
kubectl get pods -n village-storefront -L deployment-color

# Monitor error rates (should stay <1%)
kubectl logs -f deployment/village-storefront-workers -n village-storefront | grep ERROR

# Check Grafana dashboard
open https://grafana.villagecompute.com/d/village-storefront-overview
```

### Step 5: GitHub Release Creation

Automatically creates GitHub release with:
- Version tag and release notes
- SBOM artifact attachment
- Deployment summary (staging + production)
- Image signature verification command
- Feature flag status
- Comprehensive rollback procedures
- Monitoring checklist

**Access Release:**
```bash
# View release
gh release view ${VERSION}

# Download SBOM
gh release download ${VERSION} --pattern "*.json"
```

---

<!-- anchor: post-release-verification -->
## Post-Release Verification

**Complete within 15 minutes of production deployment.**

### 1. Health Checks

```bash
PROD_URL="https://villagecompute.com"

# Liveness probe
curl -f "${PROD_URL}/q/health/live" || echo "CRITICAL: Liveness check failed"

# Readiness probe
curl -f "${PROD_URL}/q/health/ready" || echo "CRITICAL: Readiness check failed"

# Startup probe
curl -f "${PROD_URL}/q/health/started" || echo "CRITICAL: Startup check failed"
```

**Expected Response:** HTTP 200 with `{"status":"UP"}`

### 2. Metrics Validation

```bash
# Prometheus metrics endpoint
curl -f "${PROD_URL}/q/metrics" | grep -E "jvm_|http_|db_"

# Verify version metric
curl -s "${PROD_URL}/q/metrics" | grep 'application_info{version="'${VERSION}'"}'
```

**Key Metrics:**
- `jvm_memory_used_bytes`: Should be stable, not climbing
- `http_server_requests_seconds_count`: Request rate normal
- `db_pool_active_connections`: Connection pool healthy

### 3. Error Rate Monitoring

Query Prometheus for error rates over last 5 minutes:

```promql
# Error rate (should be <1%)
rate(http_server_requests_seconds_count{status=~"5..", namespace="village-storefront"}[5m])

# Error percentage
sum(rate(http_server_requests_seconds_count{status=~"5..", namespace="village-storefront"}[5m]))
/
sum(rate(http_server_requests_seconds_count{namespace="village-storefront"}[5m])) * 100
```

**Thresholds:**
- **Warning**: Error rate >1%
- **Critical**: Error rate >5% (initiate rollback)

### 4. Latency Verification

```promql
# p95 latency (should be <500ms)
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{namespace="village-storefront"}[5m])) by (le))

# p99 latency (should be <1s)
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{namespace="village-storefront"}[5m])) by (le))
```

**Thresholds:**
- **Warning**: p95 >500ms or p99 >1s
- **Critical**: p95 >1s or p99 >2s (investigate, possible rollback)

### 5. Database Connection Pool

```bash
# Check active connections
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "SELECT count(*) as active_connections FROM pg_stat_activity WHERE datname = 'villagestore';"

# Check for lock waits
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "SELECT count(*) as lock_waits FROM pg_stat_activity WHERE wait_event_type = 'Lock';"
```

**Expected:**
- Active connections: 20-50 (based on pool size × replicas)
- Lock waits: 0 (or very low, <5)

### 6. Feature Flag Verification

```bash
# Verify new feature flags deployed
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "SELECT flag_key, enabled, tenant_id FROM feature_flags WHERE updated_at > NOW() - INTERVAL '1 hour';"

# Verify kill switches still disabled
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "SELECT flag_key, enabled FROM feature_flags WHERE flag_key LIKE '%.disable' AND tenant_id IS NULL;"
```

**Expected:**
- New feature flags present with `enabled = false` (dark launch)
- All kill switches `enabled = false`

### 7. End-to-End Smoke Tests

Run critical path smoke tests:

```bash
cd tests/e2e/smoke

# Run smoke test suite
npm run smoke:production

# Key flows tested:
# - Storefront homepage load
# - Product search and filtering
# - Add to cart
# - Checkout initiation
# - Payment processing (test mode)
# - Order confirmation
```

**Expected:** All smoke tests pass (HTTP 200, correct page titles, no JavaScript errors)

### 8. Log Sampling

Sample logs for errors or warnings:

```bash
# Last 100 lines from workers
kubectl logs -n village-storefront deployment/village-storefront-workers --tail=100 | grep -E "ERROR|WARN"

# Last 100 lines from gateway
kubectl logs -n village-storefront deployment/village-storefront-gateway --tail=100 | grep -E "ERROR|WARN"
```

**Red Flags:**
- Repeated exceptions (connection timeouts, null pointers, etc.)
- OOM errors
- Database deadlocks

### 9. Post-Release Checklist

- [ ] ✅ Health checks passing (live, ready, started)
- [ ] ✅ Metrics endpoint responding
- [ ] ✅ Error rate <1%
- [ ] ✅ p95 latency <500ms
- [ ] ✅ Database connections healthy
- [ ] ✅ Feature flags deployed correctly
- [ ] ✅ Smoke tests passed
- [ ] ✅ No critical errors in logs
- [ ] ✅ Grafana dashboard shows normal metrics
- [ ] ✅ No PagerDuty alerts fired

---

<!-- anchor: feature-flag-rollout-strategy -->
## Feature Flag Rollout Strategy

**Use feature flags for progressive rollout of new functionality.**

### 1. Dark Launch (Day 0)

Deploy with feature disabled globally:

```sql
-- Deploy with feature disabled
INSERT INTO feature_flags (flag_key, enabled, tenant_id, description)
VALUES ('payment.multi_tender.enabled', false, NULL, 'Multi-tender payment support');
```

**Outcome:** Code deployed but inactive. No user impact.

### 2. Canary Rollout (Day 1-2)

Enable for 10% of tenants (canary cohort):

```sql
-- Select canary tenants (10%)
WITH canary_tenants AS (
  SELECT id FROM tenants
  WHERE id % 10 = 0  -- 10% sample
  LIMIT 50  -- Cap at 50 tenants for initial rollout
)
INSERT INTO feature_flags (flag_key, enabled, tenant_id, description)
SELECT 'payment.multi_tender.enabled', true, id, 'Multi-tender payment support (canary)'
FROM canary_tenants;
```

**Monitoring:**
- Track error rates per tenant: `rate(http_server_requests_seconds_count{status=~"5..", tenant_id=~"canary_tenants"}[5m])`
- Monitor feature usage: `payment_multi_tender_usage_total`
- Review customer support tickets from canary tenants

**Success Criteria:**
- Error rate <1% for canary tenants
- No increase in support tickets
- Feature usage metrics show expected behavior

### 3. Progressive Rollout (Day 3-7)

Gradually increase rollout percentage:

| Day | Percentage | Tenant Count |
|-----|-----------|--------------|
| 3   | 25%       | ~125 tenants |
| 5   | 50%       | ~250 tenants |
| 7   | 100%      | All tenants  |

```sql
-- Day 3: Enable for 25% of tenants
WITH rollout_tenants AS (
  SELECT id FROM tenants
  WHERE id % 4 = 0  -- 25% sample
)
INSERT INTO feature_flags (flag_key, enabled, tenant_id, description)
SELECT 'payment.multi_tender.enabled', true, id, 'Multi-tender payment support (25% rollout)'
FROM rollout_tenants
ON CONFLICT (flag_key, COALESCE(tenant_id, 0))
DO UPDATE SET enabled = true;

-- Day 5: Enable for 50%
WITH rollout_tenants AS (
  SELECT id FROM tenants
  WHERE id % 2 = 0  -- 50% sample
)
INSERT INTO feature_flags (flag_key, enabled, tenant_id, description)
SELECT 'payment.multi_tender.enabled', true, id, 'Multi-tender payment support (50% rollout)'
FROM rollout_tenants
ON CONFLICT (flag_key, COALESCE(tenant_id, 0))
DO UPDATE SET enabled = true;

-- Day 7: Enable globally
UPDATE feature_flags
SET enabled = true
WHERE flag_key = 'payment.multi_tender.enabled'
  AND tenant_id IS NULL;

-- Clean up tenant-specific overrides
DELETE FROM feature_flags
WHERE flag_key = 'payment.multi_tender.enabled'
  AND tenant_id IS NOT NULL;
```

### 4. Emergency Kill Switch

If critical issue detected, immediately disable feature:

```sql
-- Activate kill switch (disables for all tenants)
UPDATE feature_flags
SET enabled = true
WHERE flag_key = 'payment.disable'
  AND tenant_id IS NULL;

-- Or disable specific feature
UPDATE feature_flags
SET enabled = false
WHERE flag_key = 'payment.multi_tender.enabled'
  AND tenant_id IS NULL;
```

**Verify kill switch active:**
```bash
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "SELECT flag_key, enabled FROM feature_flags WHERE flag_key LIKE '%.disable' AND tenant_id IS NULL;"
```

### 5. Feature Flag Lifecycle

```
[Dark Launch] → [Canary (10%)] → [Progressive (25% → 50% → 100%)] → [Feature Hardcoded] → [Flag Removal]
     Day 0           Day 1-2             Day 3-7                        Day 30+           Day 60+
```

**Flag Removal Criteria:**
- Feature enabled globally for 30+ days
- No rollback incidents
- Feature usage metrics stable
- Code refactored to remove flag checks (feature hardcoded)

---

<!-- anchor: rollback-procedures -->
## Rollback Procedures

**Decision matrix for when to rollback:**

| Condition | Severity | Action | Timeline |
|-----------|----------|--------|----------|
| Error rate >5% | Critical | Immediate traffic switch | <2 min |
| p95 latency >1s | Critical | Immediate traffic switch | <2 min |
| Memory leak detected | Critical | Traffic switch + restart | <5 min |
| Data corruption | Critical | STOP + restore backup | Immediate |
| Error rate 1-5% | Warning | Monitor + feature flag disable | 5 min |
| p95 latency 500ms-1s | Warning | Monitor + investigate | 10 min |

### Rollback Option 1: Immediate Traffic Switch (Blue → Green)

**Use Case:** Critical production issue, green deployment still available
**Downtime:** None (instant traffic switch)
**Timeline:** <2 minutes

```bash
# 1. Switch traffic back to green deployment
kubectl patch service village-storefront-workers \
  -n village-storefront \
  -p '{"spec":{"selector":{"deployment-color":"green"}}}'

kubectl patch service village-storefront-workers-critical \
  -n village-storefront \
  -p '{"spec":{"selector":{"deployment-color":"green"}}}'

kubectl patch service village-storefront-gateway \
  -n village-storefront \
  -p '{"spec":{"selector":{"deployment-color":"green"}}}'

# 2. Verify traffic switched
kubectl get svc -n village-storefront -o yaml | grep deployment-color

# 3. Scale up green deployment
kubectl scale deployment village-storefront-workers \
  -n village-storefront \
  -l deployment-color=green \
  --replicas=3

# 4. Monitor error rates (should drop immediately)
watch "curl -s https://villagecompute.com/q/metrics | grep http_server_requests_seconds_count"

# 5. Scale down blue deployment
kubectl scale deployment village-storefront-workers \
  -n village-storefront \
  -l deployment-color=blue \
  --replicas=0
```

**Post-Rollback:**
- Verify health checks: `curl https://villagecompute.com/q/health/ready`
- Check error rate dropped to <1%
- Investigate root cause in blue deployment logs
- Update incident channel with rollback status

### Rollback Option 2: Emergency Feature Flag Disable

**Use Case:** Specific feature causing issues, other functionality stable
**Downtime:** None (immediate flag toggle)
**Timeline:** <1 minute

```bash
# Identify problematic feature flag
# Example: Multi-tender payment causing issues

# Activate kill switch
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "UPDATE feature_flags SET enabled = true WHERE flag_key = 'payment.disable' AND tenant_id IS NULL;"

# Or disable specific feature
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "UPDATE feature_flags SET enabled = false WHERE flag_key = 'payment.multi_tender.enabled' AND tenant_id IS NULL;"

# Verify flag updated
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "SELECT flag_key, enabled, updated_at FROM feature_flags WHERE flag_key IN ('payment.disable', 'payment.multi_tender.enabled') AND tenant_id IS NULL;"
```

**Feature Flag Cache Invalidation:**

Village Storefront uses Caffeine caching for feature flags (TTL: 60 seconds). Changes take effect within 1 minute naturally, or force invalidation:

```bash
# Force cache invalidation via management endpoint
curl -X POST https://villagecompute.com/q/cache/feature-flags/invalidate \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### Rollback Option 3: Database Migration Rollback

**Use Case:** Migration caused data integrity issues
**Downtime:** 5-10 minutes (depending on migration complexity)
**Timeline:** 10 minutes

```bash
# 1. Activate checkout kill switch (prevent new orders during rollback)
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "UPDATE feature_flags SET enabled = true WHERE flag_key = 'checkout.disable' AND tenant_id IS NULL;"

# 2. Run migration down script
cd migrations
mvn migration:down -Dmigration.env=production -Dmigration.count=1

# 3. Verify migration rolled back
mvn migration:status -Dmigration.env=production

# 4. Switch traffic to green (if needed)
kubectl patch service village-storefront-workers \
  -n village-storefront \
  -p '{"spec":{"selector":{"deployment-color":"green"}}}'

# 5. Re-enable checkout
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "UPDATE feature_flags SET enabled = false WHERE flag_key = 'checkout.disable' AND tenant_id IS NULL;"
```

**Critical:** Always test migration down scripts in staging before production use.

### Rollback Option 4: Complete Rollback (Kubernetes Rollout Undo)

**Use Case:** Full rollback to previous version required
**Downtime:** 5-10 minutes (rolling restart)
**Timeline:** 15 minutes

```bash
# 1. Activate checkout kill switch
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "UPDATE feature_flags SET enabled = true WHERE flag_key = 'checkout.disable' AND tenant_id IS NULL;"

# 2. Rollback all deployments
kubectl rollout undo deployment/village-storefront-workers -n village-storefront
kubectl rollout undo deployment/village-storefront-workers-critical -n village-storefront
kubectl rollout undo deployment/village-storefront-gateway -n village-storefront

# 3. Wait for rollout to complete
kubectl rollout status deployment/village-storefront-workers -n village-storefront --timeout=5m
kubectl rollout status deployment/village-storefront-workers-critical -n village-storefront --timeout=5m
kubectl rollout status deployment/village-storefront-gateway -n village-storefront --timeout=5m

# 4. Verify rollback version
kubectl get deployment village-storefront-workers -n village-storefront -o jsonpath='{.spec.template.spec.containers[0].image}'

# 5. Run migration rollback (if needed)
cd migrations
mvn migration:down -Dmigration.env=production

# 6. Re-enable checkout
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "UPDATE feature_flags SET enabled = false WHERE flag_key = 'checkout.disable' AND tenant_id IS NULL;"

# 7. Verify health checks
curl https://villagecompute.com/q/health/ready
```

### Rollback Option 5: Disaster Recovery (Database Restore)

**Use Case:** Critical data corruption or loss
**Downtime:** 30-60 minutes (database restore)
**Timeline:** 1 hour

**This is a CRITICAL operation. Coordinate with DBA and platform lead.**

```bash
# 1. STOP ALL TRAFFIC (activate global kill switch)
kubectl scale deployment village-storefront-workers -n village-storefront --replicas=0
kubectl scale deployment village-storefront-gateway -n village-storefront --replicas=0

# 2. Identify restore point
# List available backups
kubectl exec -n village-storefront postgres-backup-0 -- \
  pgbackrest info --stanza=village-storefront

# 3. Restore from backup (CRITICAL: This overwrites data)
kubectl exec -n village-storefront postgres-primary-0 -- \
  pgbackrest restore --stanza=village-storefront --type=time --target="2026-01-10 14:00:00"

# 4. Verify database integrity
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c "SELECT count(*) FROM orders WHERE created_at > NOW() - INTERVAL '1 hour';"

# 5. Restore application to previous version
kubectl rollout undo deployment/village-storefront-workers -n village-storefront
kubectl rollout undo deployment/village-storefront-gateway -n village-storefront

# 6. Scale up deployments
kubectl scale deployment village-storefront-workers -n village-storefront --replicas=3
kubectl scale deployment village-storefront-gateway -n village-storefront --replicas=3

# 7. Verify health checks
kubectl rollout status deployment/village-storefront-workers -n village-storefront --timeout=10m
curl https://villagecompute.com/q/health/ready

# 8. Re-enable traffic gradually (monitor closely)
# Keep checkout disabled initially
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "UPDATE feature_flags SET enabled = true WHERE flag_key = 'checkout.disable' AND tenant_id IS NULL;"

# After verification, re-enable checkout
kubectl exec -n village-storefront postgres-primary-0 -- \
  psql -U storefront -d villagestore -c \
  "UPDATE feature_flags SET enabled = false WHERE flag_key = 'checkout.disable' AND tenant_id IS NULL;"
```

---

<!-- anchor: incident-response -->
## Incident Response

### Rollback Decision Matrix

Use this matrix to determine appropriate rollback action:

| Metric | Threshold | Severity | Action | Timeline |
|--------|-----------|----------|--------|----------|
| Error rate | >5% | P0 | Traffic switch to green | <2 min |
| Error rate | 1-5% | P1 | Feature flag disable | <5 min |
| p95 latency | >1s | P0 | Traffic switch to green | <2 min |
| p95 latency | 500ms-1s | P1 | Monitor + investigate | 10 min |
| p99 latency | >2s | P1 | Feature flag disable | <5 min |
| Memory leak | Heap >90% | P0 | Traffic switch + restart | <5 min |
| Memory leak | Heap 75-90% | P1 | Monitor + scale up | 10 min |
| Data corruption | Any | P0 | STOP + database restore | Immediate |
| Database locks | >100 | P1 | Investigate + possible rollback | 10 min |
| Pod crash loop | >3 restarts | P0 | Traffic switch to green | <2 min |

### Communication Template

**Slack #incidents Channel:**

```
🚨 INCIDENT: Production release rollback initiated

**Version:** v1.2.3
**Issue:** Error rate spiked to 7.2% (threshold: 5%)
**Action:** Immediate traffic switch to green deployment (v1.2.2)
**Status:** IN PROGRESS
**Timeline:**
  14:05 - Release deployed (v1.2.3)
  14:12 - Error rate spike detected (7.2%)
  14:13 - Rollback initiated
  14:15 - Traffic switched to green
  14:17 - Error rate stabilized (0.8%)
  14:20 - Rollback complete

**Next Steps:**
  - Root cause analysis (RCA) scheduled for tomorrow 10 AM
  - Blue deployment scaled to 0
  - v1.2.3 tag preserved for investigation

**On-call:** @platform-eng-oncall
```

### Post-Incident Review

**Required within 48 hours of rollback:**

1. **Timeline Documentation**
   - Detailed timeline of events (detection → resolution)
   - Screenshots of metrics/dashboards
   - Log excerpts showing errors

2. **Root Cause Analysis**
   - What caused the issue?
   - Why wasn't it caught in staging/testing?
   - What monitoring gaps exist?

3. **Action Items**
   - Preventive measures (code fixes, test improvements)
   - Detection improvements (alerts, dashboards)
   - Process improvements (pre-release checks, rollout strategy)

4. **Documentation Updates**
   - Update runbook with lessons learned
   - Add new pre-release checks
   - Improve rollback procedures if gaps found

---

<!-- anchor: release-schedule -->
## Release Schedule

### Standard Release Cadence

- **Frequency:** Bi-weekly (every 2 weeks)
- **Day:** Tuesday or Wednesday
- **Time:** 9 AM - 3 PM Pacific Time
- **Duration:** 2-4 hours (including verification)

### Release Windows

| Week | Date | Type | Notes |
|------|------|------|-------|
| 1 | 2026-01-14 | Standard | Feature release |
| 2 | 2026-01-28 | Standard | Feature release |
| 3 | 2026-02-11 | Standard | Feature release |
| 4 | 2026-02-25 | Standard | Feature release |

**Blackout Periods:**
- Major holidays (Thanksgiving, Christmas, New Year)
- Black Friday / Cyber Monday (week before + week of)
- Company-wide events

### Hotfix Releases

**Criteria for hotfix (out-of-band release):**
- Critical security vulnerability (CVSS ≥7)
- Data corruption bug
- Payment processing outage
- Service outage affecting >10% of tenants

**Hotfix Process:**
1. Create hotfix branch from main: `git checkout -b hotfix/v1.2.4 main`
2. Apply minimal fix (no feature additions)
3. Test in staging (expedited testing, focus on affected area)
4. Tag hotfix version: `git tag -a v1.2.4 -m "Hotfix: Fix payment processing timeout"`
5. Deploy to production (same workflow, expedited approval)
6. Merge hotfix back to main: `git checkout main && git merge hotfix/v1.2.4`

**Hotfix Timeline:** 2-4 hours (detection → production)

---

## Appendix

### Useful Commands Cheatsheet

```bash
# Release workflow monitoring
gh run list --workflow=release.yml --limit=5
gh run view --log --job=deploy-production
gh run watch

# Kubernetes pod inspection
kubectl get pods -n village-storefront -L deployment-color
kubectl describe pod <pod-name> -n village-storefront
kubectl logs -f deployment/village-storefront-workers -n village-storefront --tail=100

# Database queries
kubectl exec -n village-storefront postgres-primary-0 -- psql -U storefront -d villagestore -c "SELECT version();"
kubectl exec -n village-storefront postgres-primary-0 -- psql -U storefront -d villagestore -c "SELECT count(*) FROM orders WHERE created_at > NOW() - INTERVAL '1 hour';"

# Metrics queries (Prometheus)
curl -s "http://prometheus.villagecompute.com/api/v1/query?query=rate(http_server_requests_seconds_count{status=~\"5..\"}[5m])"

# Feature flag management
kubectl exec -n village-storefront postgres-primary-0 -- psql -U storefront -d villagestore -c "SELECT flag_key, enabled FROM feature_flags WHERE tenant_id IS NULL ORDER BY flag_key;"

# Image signature verification
cosign verify --certificate-identity-regexp="github" ghcr.io/villagecompute/village-storefront:v1.2.3
```

### Contact Information

- **Platform Engineering Lead:** @platform-lead
- **On-Call Rotation:** PagerDuty schedule "Village Storefront"
- **Slack Channels:**
  - `#platform-releases` - Release announcements
  - `#incidents` - Production incidents
  - `#platform-engineering` - Team channel
- **Grafana:** https://grafana.villagecompute.com
- **Prometheus:** http://prometheus.villagecompute.com
- **GitHub Actions:** https://github.com/villagecompute/village-storefront/actions

---

**Runbook Version:** 1.0
**Last Updated:** 2026-01-10
**Next Review:** 2026-04-10 (quarterly review)
