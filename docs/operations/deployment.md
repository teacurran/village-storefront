# Village Storefront Deployment Operations Guide

This document provides comprehensive procedures for deploying, promoting, and rolling back Village Storefront releases across environments.

**Version:** 1.0
**Last Updated:** 2026-01-03
**Related Documents:**
- [Architecture Overview](../architecture/04_Operational_Architecture.md)
- [Deployment Diagram](../diagrams/deployment_k8s.puml)
- [GitHub Actions Release Workflow](../../.github/workflows/release.yml)

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Environment Configuration](#environment-configuration)
4. [Deployment Procedures](#deployment-procedures)
5. [Blue/Green Deployment Strategy](#bluegreen-deployment-strategy)
6. [Rollback Procedures](#rollback-procedures)
7. [Verification & Smoke Tests](#verification--smoke-tests)
8. [Secrets Management](#secrets-management)
9. [Troubleshooting](#troubleshooting)
10. [Runbook Quick Reference](#runbook-quick-reference)

---

## Overview

Village Storefront uses a **blue/green deployment strategy** to ensure zero-downtime releases. Deployments are orchestrated via GitHub Actions and managed using Kustomize overlays for environment-specific configuration.

### Architecture

```
GitHub Actions Release Workflow
    │
    ├─► Build Native Image (GraalVM)
    │
    ├─► Deploy to Staging (Automated)
    │   └─► Smoke Tests
    │
    └─► Deploy to Production (Manual Approval)
        ├─► Blue Environment (New Version)
        ├─► Traffic Switch (Green → Blue)
        └─► Green Cleanup (Delayed)
```

### Key Principles

- **Immutable Infrastructure**: Container images are built once, tagged with version, never modified
- **Progressive Deployment**: Staging → Production with manual approval gate
- **Zero Downtime**: Blue/green strategy ensures service continuity
- **Rollback Ready**: Previous deployment retained for instant rollback
- **Automated Testing**: Smoke tests validate each deployment before traffic switch

---

## Prerequisites

### Required Tools

```bash
# Kubernetes CLI
kubectl version --client  # v1.28.0+

# Kustomize
kustomize version         # v5.0.0+

# Docker (for local builds)
docker version            # 24.0.0+

# Optional: PlantUML (for diagram regeneration)
plantuml -version         # 1.2023.0+
```

### Access Requirements

- **GitHub**: Write access to repository for release tagging
- **Container Registry**: Push access to `ghcr.io/villagecompute/village-storefront`
- **Kubernetes Clusters**:
  - Dev: `kubectl` access to `village-storefront-dev` namespace
  - Staging: `kubectl` access to `village-storefront-staging` namespace
  - Production: `kubectl` access to `village-storefront` namespace
- **Secrets Management**: Access to Sealed Secrets or External Secrets Operator

### Quality Gates

Before initiating a release, ensure:

- ✅ CI pipeline passed (Spotless, JaCoCo ≥80%, unit/integration tests)
- ✅ SonarCloud quality gate passed (0 bugs, 0 vulnerabilities)
- ✅ Native image build succeeded
- ✅ All PlantUML diagrams render without errors
- ✅ Admin SPA lint and tests passed

---

## Environment Configuration

### Environment Overlay Structure

```
k8s/
├── base/                           # Shared base manifests
│   ├── deployment-workers.yaml
│   ├── deployment-workers-critical.yaml
│   └── kustomization.yaml
└── overlays/
    ├── dev/                        # Development (local/minikube)
    │   ├── kustomization.yaml
    │   ├── patches/
    │   └── ingress-dev.yaml
    ├── staging/                    # Staging (prod-like)
    │   ├── kustomization.yaml
    │   ├── patches/
    │   ├── ingress-staging.yaml
    │   └── network-policy-staging.yaml
    └── prod/                       # Production (HA)
        ├── kustomization.yaml
        ├── patches/
        ├── ingress-prod.yaml
        ├── network-policy-prod.yaml
        ├── priority-class.yaml
        └── service-monitor.yaml
```

### Environment Differences

| Aspect | Dev | Staging | Production |
|--------|-----|---------|------------|
| **Replicas** | 1 | 2 | 3-5 |
| **HPA Max** | 3 | 10 | 20-30 |
| **Resources** | 100m/256Mi | 250m/512Mi | 500m/1Gi |
| **Probes** | Relaxed | Prod-like | Strict |
| **Logging** | DEBUG, plain text | INFO, JSON | INFO, JSON |
| **Tracing** | Always on | Always on | 10% sampling |
| **PDB** | None | minAvailable: 1 | minAvailable: 2 |
| **External Services** | Mocks | Sandbox | Production |

---

## Deployment Procedures

### Automated Deployment (via GitHub Actions)

**Trigger**: Push a semver tag to the repository

```bash
# Create and push a release tag
git tag -a v1.2.3 -m "Release version 1.2.3"
git push origin v1.2.3
```

**Workflow Stages**:

1. **Build** (Automated)
   - GraalVM native compilation
   - Container image build and push
   - SBOM generation

2. **Deploy to Staging** (Automated)
   - Database migrations
   - Blue deployment
   - Smoke tests
   - Traffic switch

3. **Deploy to Production** (Manual Approval Required)
   - Requires GitHub environment approval
   - Database migrations
   - Blue/green deployment
   - Smoke tests
   - Traffic switch
   - Green retention for rollback

**Approval Process**:

1. Navigate to Actions tab in GitHub
2. Select the release workflow run
3. Review staging deployment results
4. Click "Review deployments" for production
5. Approve or reject deployment

### Manual Deployment (Dev/Testing)

For local development or testing without CI:

```bash
# Build local image
./mvnw package -Pnative -DskipTests
docker build -f src/main/docker/Dockerfile.native -t village-storefront:dev .

# Deploy to dev environment
kubectl config use-context dev-cluster
cd k8s/overlays/dev
kustomize edit set image ghcr.io/villagecompute/village-storefront:dev
kustomize build . | kubectl apply -f -

# Verify deployment
kubectl rollout status deployment/village-storefront-workers -n village-storefront-dev
```

### Database Migrations

Migrations run automatically as Kubernetes Jobs before traffic switch. For manual execution:

```bash
# Create migration job from CronJob template
kubectl create job --from=cronjob/migrations migration-$(date +%s) -n village-storefront

# Monitor migration progress
kubectl logs -f job/migration-<timestamp> -n village-storefront

# Verify migration completion
kubectl wait --for=condition=complete --timeout=300s job/migration-<timestamp> -n village-storefront
```

**Migration Rollback**:

If migration fails or needs reversal:

```bash
cd migrations
mvn migration:down -Dmigration.env=production -Dmigration.version=<version>
```

---

## Blue/Green Deployment Strategy

### Overview

Blue/green deployment maintains two identical production environments:
- **Blue**: New version being deployed
- **Green**: Current production version

Traffic switches from green to blue after validation, with green retained for instant rollback.

### Deployment Flow

```
┌─────────────┐         ┌─────────────┐
│   Green     │◄────────┤   Traffic   │  (Current Production)
│  v1.2.2     │         │   Router    │
└─────────────┘         └─────────────┘
                              │
                              │ Deploy Blue
                              ▼
┌─────────────┐         ┌─────────────┐
│   Green     │         │    Blue     │
│  v1.2.2     │         │   v1.2.3    │  (New Version)
└─────────────┘         └─────────────┘
                              │
                              │ Validate Blue
                              │ Switch Traffic
                              ▼
┌─────────────┐         ┌─────────────┐
│   Green     │         │    Blue     │◄───┐
│  v1.2.2     │         │   v1.2.3    │    │ (Now Production)
│ (Retained)  │         └─────────────┘    │
└─────────────┘                             │
      │                                     │
      │ Cleanup after 24h                   │
      ▼                                     │
    ✗ Deleted                       Traffic Router
```

### Implementation Steps

#### 1. Label Current Deployment as Green

```bash
kubectl patch deployment village-storefront-workers \
  -n village-storefront \
  -p '{"metadata":{"labels":{"deployment-color":"green"}}}'
```

#### 2. Deploy Blue Environment

```bash
cd k8s/overlays/prod
kustomize edit set image ghcr.io/villagecompute/village-storefront:v1.2.3
kustomize build . | kubectl apply -f -

kubectl patch deployment village-storefront-workers \
  -n village-storefront \
  -p '{"metadata":{"labels":{"deployment-color":"blue"}}}'
```

#### 3. Wait for Blue Rollout

```bash
kubectl rollout status deployment/village-storefront-workers -n village-storefront --timeout=10m
kubectl rollout status deployment/village-storefront-workers-critical -n village-storefront --timeout=10m
```

#### 4. Validate Blue Environment

```bash
# Run smoke tests against blue pods directly
BLUE_POD=$(kubectl get pod -n village-storefront -l deployment-color=blue -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n village-storefront $BLUE_POD -- curl -f http://localhost:8080/q/health/ready

# Check metrics
kubectl exec -n village-storefront $BLUE_POD -- curl -s http://localhost:8080/q/metrics | grep jvm_
```

#### 5. Switch Traffic to Blue

```bash
# Update service selector to route traffic to blue
kubectl patch service village-storefront-workers \
  -n village-storefront \
  -p '{"spec":{"selector":{"deployment-color":"blue"}}}'

echo "Traffic switched to blue (v1.2.3)"
```

#### 6. Monitor Post-Switch Metrics

```bash
# Watch error rates, latency, throughput
kubectl logs -f deployment/village-storefront-workers -n village-storefront --tail=100

# Check Prometheus alerts
# Navigate to Grafana dashboard for real-time metrics
```

#### 7. Scale Down Green (Retain for Rollback)

```bash
kubectl scale deployment village-storefront-workers \
  --replicas=1 \
  -n village-storefront \
  -l deployment-color=green
```

#### 8. Cleanup Green (After Verification Period)

Wait 24-48 hours to ensure stability before removing green:

```bash
kubectl delete deployment -l deployment-color=green -n village-storefront
```

---

## Rollback Procedures

### Immediate Rollback (Switch Back to Green)

If critical issues detected within minutes of blue deployment:

```bash
# 1. Switch traffic back to green
kubectl patch service village-storefront-workers \
  -n village-storefront \
  -p '{"spec":{"selector":{"deployment-color":"green"}}}'

# 2. Scale green back up
kubectl scale deployment village-storefront-workers \
  --replicas=3 \
  -n village-storefront \
  -l deployment-color=green

# 3. Wait for green to be ready
kubectl rollout status deployment/village-storefront-workers -n village-storefront -l deployment-color=green

# 4. Delete blue deployment
kubectl delete deployment -l deployment-color=blue -n village-storefront

echo "Rollback complete: traffic restored to green"
```

### Rollback via kubectl rollout undo

If blue deployment is the current revision:

```bash
# Rollback to previous revision
kubectl rollout undo deployment/village-storefront-workers -n village-storefront

# Verify rollback
kubectl rollout status deployment/village-storefront-workers -n village-storefront

# Check version
kubectl get deployment village-storefront-workers -n village-storefront -o jsonpath='{.spec.template.spec.containers[0].image}'
```

### Database Migration Rollback

If migration causes issues:

```bash
# 1. Identify migration version to revert
cd migrations
mvn migration:status -Dmigration.env=production

# 2. Execute down migration
mvn migration:down -Dmigration.env=production -Dmigration.version=<version>

# 3. Verify database state
mvn migration:status -Dmigration.env=production
```

### Feature Flag Emergency Disable

For critical bugs in specific features:

```bash
# Connect to database
kubectl exec -it postgres-primary-0 -n village-storefront -- psql -U storefront

-- Disable feature globally
UPDATE feature_flags
SET enabled = false
WHERE flag_key = 'FEATURE_NAME';

-- Disable for specific tenant
UPDATE feature_flags
SET enabled = false
WHERE flag_key = 'FEATURE_NAME' AND tenant_id = '<tenant-uuid>';

-- Verify
SELECT flag_key, enabled, tenant_id FROM feature_flags WHERE flag_key = 'FEATURE_NAME';
```

### Rollback Decision Matrix

| Symptom | Time Since Deploy | Action |
|---------|-------------------|--------|
| High error rate (>5%) | <5 minutes | Immediate rollback to green |
| Memory leak detected | <1 hour | Rollback + restart green pods |
| Data corruption | Any time | **STOP**: Rollback + restore DB backup |
| Feature bug (non-critical) | Any time | Disable feature flag + patch next release |
| Performance degradation | <30 minutes | Rollback if p99 latency >2x baseline |

---

## Verification & Smoke Tests

### Automated Smoke Tests

The release workflow runs these tests automatically. For manual execution:

```bash
ENVIRONMENT="staging"  # or "production"
BASE_URL="https://${ENVIRONMENT}.villagecompute.com"

# Health checks
curl -f "${BASE_URL}/q/health/live" || exit 1
curl -f "${BASE_URL}/q/health/ready" || exit 1
curl -f "${BASE_URL}/q/health/started" || exit 1

# Metrics endpoint
curl -f "${BASE_URL}/q/metrics" | grep -q "jvm_memory_used_bytes" || exit 1

# Version verification
VERSION=$(curl -s "${BASE_URL}/q/info" | jq -r '.version')
echo "Deployed version: ${VERSION}"

# API endpoint test
curl -f "${BASE_URL}/api/v1/health" || exit 1

echo "✅ All smoke tests passed"
```

### Manual Verification Checklist

After deployment, verify:

- [ ] **Health Probes**: All pods report ready/live
- [ ] **Metrics**: Prometheus scraping successfully
- [ ] **Logs**: No ERROR/FATAL messages in last 5 minutes
- [ ] **Database**: Migrations applied successfully
- [ ] **Ingress**: TLS certificate valid, DNS resolves
- [ ] **Storefront**: Homepage loads with tenant resolution
- [ ] **Admin SPA**: Login page loads, assets served
- [ ] **API**: Sample API call succeeds with auth
- [ ] **Jobs**: Background workers processing queue
- [ ] **Monitoring**: Grafana dashboards show metrics
- [ ] **Alerts**: No firing critical alerts

### Performance Baseline Validation

Compare metrics before/after deployment:

```bash
# Query Prometheus for error rate
curl -s 'http://prometheus:9090/api/v1/query?query=sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))/sum(rate(http_server_requests_seconds_count[5m]))'

# Check p99 latency
curl -s 'http://prometheus:9090/api/v1/query?query=histogram_quantile(0.99,sum(rate(http_server_requests_seconds_bucket[5m]))by(le))'

# Verify acceptable thresholds:
# - Error rate: <1%
# - p99 latency: <2s (API), <1s (health checks)
# - Throughput: ±10% of baseline
```

---

## Secrets Management

### Required Secrets

Each environment requires these Kubernetes secrets:

| Secret Name | Keys | Description |
|-------------|------|-------------|
| `village-storefront-db` | `jdbc-url`, `username`, `password` | PostgreSQL credentials |
| `village-storefront-r2` | `access-key-id`, `secret-access-key` | Cloudflare R2 credentials |
| `village-storefront-stripe` | `api-key` | Stripe API key (test/live mode) |

### Creating Secrets (Development)

```bash
kubectl create secret generic village-storefront-db \
  --namespace village-storefront-dev \
  --from-literal=jdbc-url='jdbc:postgresql://postgres:5432/storefront_dev' \
  --from-literal=username='storefront' \
  --from-literal=password='dev_password_change_me'

kubectl create secret generic village-storefront-r2 \
  --namespace village-storefront-dev \
  --from-literal=access-key-id='dev_access_key' \
  --from-literal=secret-access-key='dev_secret_key'

kubectl create secret generic village-storefront-stripe \
  --namespace village-storefront-dev \
  --from-literal=api-key='sk_test_...'
```

### Sealed Secrets (Production)

**Never commit production secrets to Git.** Use Sealed Secrets or External Secrets Operator.

#### Using Sealed Secrets

```bash
# Install sealed-secrets controller (once per cluster)
kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.24.0/controller.yaml

# Create secret and seal it
kubectl create secret generic village-storefront-db \
  --namespace village-storefront \
  --from-literal=jdbc-url='jdbc:postgresql://prod-db:5432/storefront' \
  --from-literal=username='storefront_prod' \
  --from-literal=password='PRODUCTION_PASSWORD' \
  --dry-run=client -o yaml | \
  kubeseal -o yaml > k8s/overlays/prod/sealed-secret-db.yaml

# Commit sealed secret (safe to commit)
git add k8s/overlays/prod/sealed-secret-db.yaml
git commit -m "Add production database sealed secret"

# Apply sealed secret
kubectl apply -f k8s/overlays/prod/sealed-secret-db.yaml
```

#### Using External Secrets Operator

```yaml
# k8s/overlays/prod/external-secret-db.yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: village-storefront-db
  namespace: village-storefront
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: vault-backend
    kind: ClusterSecretStore
  target:
    name: village-storefront-db
  data:
  - secretKey: jdbc-url
    remoteRef:
      key: village-storefront/prod/db
      property: jdbc-url
  - secretKey: username
    remoteRef:
      key: village-storefront/prod/db
      property: username
  - secretKey: password
    remoteRef:
      key: village-storefront/prod/db
      property: password
```

### Secret Rotation

**Recommended rotation schedule:**
- Database passwords: Every 90 days
- API keys (Stripe, R2): Every 180 days
- TLS certificates: Automated via cert-manager

**Rotation procedure (zero-downtime):**

```bash
# 1. Create new secret version
kubectl create secret generic village-storefront-db-new \
  --namespace village-storefront \
  --from-literal=jdbc-url='...' \
  --from-literal=username='...' \
  --from-literal=password='NEW_PASSWORD'

# 2. Update deployment to reference new secret
kubectl set env deployment/village-storefront-workers \
  --from=secret/village-storefront-db-new \
  -n village-storefront

# 3. Rolling restart (Kubernetes handles gracefully)
kubectl rollout restart deployment/village-storefront-workers -n village-storefront

# 4. Verify new pods using new credentials
kubectl rollout status deployment/village-storefront-workers -n village-storefront

# 5. Delete old secret
kubectl delete secret village-storefront-db -n village-storefront

# 6. Rename new secret to canonical name
kubectl get secret village-storefront-db-new -n village-storefront -o yaml | \
  sed 's/village-storefront-db-new/village-storefront-db/' | \
  kubectl apply -f -
kubectl delete secret village-storefront-db-new -n village-storefront
```

---

## Troubleshooting

### Common Issues

#### 1. Pods Stuck in ImagePullBackOff

**Symptom**: Pods cannot pull container image

```bash
kubectl describe pod <pod-name> -n village-storefront
```

**Resolution**:
- Verify image tag exists: `docker manifest inspect ghcr.io/villagecompute/village-storefront:<tag>`
- Check registry credentials: `kubectl get secret regcred -n village-storefront`
- Ensure GHCR permissions: Repository settings → Manage access

#### 2. Database Connection Failures

**Symptom**: Pods crash with JDBC connection errors

```bash
kubectl logs <pod-name> -n village-storefront | grep -i "connection"
```

**Resolution**:
- Verify secret exists: `kubectl get secret village-storefront-db -n village-storefront`
- Test DB connectivity: `kubectl run psql-test --rm -it --image=postgres:17 -- psql <jdbc-url>`
- Check network policy allows egress to PostgreSQL port 5432

#### 3. Migration Job Fails

**Symptom**: Migration job shows failed/error status

```bash
kubectl logs job/migration-<timestamp> -n village-storefront
```

**Resolution**:
- Review migration logs for SQL errors
- Verify migration scripts syntax: `cd migrations && mvn migration:validate`
- Check database permissions: User must have DDL privileges
- Manually run down migration: `mvn migration:down -Dmigration.env=production`

#### 4. High Memory Usage / OOMKilled

**Symptom**: Pods restarted due to OOM

```bash
kubectl get events -n village-storefront --field-selector reason=OOMKilled
```

**Resolution**:
- Increase memory limits in overlay patches
- Check for memory leaks: Analyze heap dump from `/tmp/heapdump.hprof`
- Tune JVM: Adjust `-XX:MaxRAMPercentage` or `-Xmx` settings
- Review resource metrics: Grafana → JVM memory usage dashboard

#### 5. TLS Certificate Issues

**Symptom**: HTTPS endpoints return certificate errors

```bash
kubectl describe certificate village-storefront-prod-tls -n village-storefront
kubectl describe certificaterequest <request-name> -n village-storefront
```

**Resolution**:
- Verify cert-manager is running: `kubectl get pods -n cert-manager`
- Check ACME challenge: `kubectl get challenge -n village-storefront`
- Validate DNS: `dig villagecompute.com` should resolve correctly
- Renew certificate manually: `kubectl delete certificate village-storefront-prod-tls -n village-storefront`

#### 6. Prometheus Not Scraping Metrics

**Symptom**: Metrics missing from Grafana dashboards

```bash
kubectl get servicemonitor -n village-storefront
kubectl logs -n monitoring prometheus-<pod-name> | grep village-storefront
```

**Resolution**:
- Verify ServiceMonitor selectors match service labels
- Check Prometheus configuration: Port 8080, path `/q/metrics`
- Test metrics endpoint: `curl http://<pod-ip>:8080/q/metrics`
- Ensure network policy allows ingress from monitoring namespace

### Debug Commands

```bash
# Get all resources in namespace
kubectl get all -n village-storefront

# Describe deployment for events
kubectl describe deployment village-storefront-workers -n village-storefront

# Get pod logs (last hour)
kubectl logs --since=1h deployment/village-storefront-workers -n village-storefront

# Tail logs from all pods
kubectl logs -f -l app=village-storefront -n village-storefront --all-containers=true

# Execute command in pod
kubectl exec -it <pod-name> -n village-storefront -- /bin/sh

# Port-forward to local machine
kubectl port-forward service/village-storefront-workers 8080:8080 -n village-storefront

# Check HPA status
kubectl get hpa -n village-storefront
kubectl describe hpa village-storefront-workers-hpa -n village-storefront

# View PodDisruptionBudget
kubectl get pdb -n village-storefront

# Check resource usage
kubectl top pods -n village-storefront
kubectl top nodes
```

---

## Runbook Quick Reference

### Release New Version

```bash
# 1. Tag release
git tag -a v1.2.3 -m "Release 1.2.3"
git push origin v1.2.3

# 2. Monitor GitHub Actions
# Navigate to: https://github.com/villagecompute/village-storefront/actions

# 3. Review staging deployment
# URL: https://staging.villagecompute.com

# 4. Approve production deployment
# GitHub Actions → Review deployments → Approve

# 5. Verify production
curl -f https://villagecompute.com/q/health/ready
```

### Emergency Rollback

```bash
# Switch traffic back to green
kubectl patch service village-storefront-workers \
  -n village-storefront \
  -p '{"spec":{"selector":{"deployment-color":"green"}}}'

# Scale green up
kubectl scale deployment -l deployment-color=green --replicas=3 -n village-storefront

# Delete blue
kubectl delete deployment -l deployment-color=blue -n village-storefront
```

### Check Deployment Status

```bash
# Deployments
kubectl get deployments -n village-storefront

# Pods
kubectl get pods -n village-storefront -o wide

# Health
kubectl exec -it <pod-name> -n village-storefront -- curl localhost:8080/q/health

# Logs
kubectl logs -f deployment/village-storefront-workers -n village-storefront --tail=50
```

### Regenerate Deployment Diagram

```bash
# Using Docker
docker run --rm -v "$PWD":/work ghcr.io/plantuml/plantuml docs/diagrams/deployment_k8s.puml -tpng

# Using local PlantUML
plantuml docs/diagrams/deployment_k8s.puml

# Verify rendering
npm run diagrams:check
```

---

## Additional Resources

- **Kubernetes Documentation**: https://kubernetes.io/docs/
- **Kustomize Guide**: https://kustomize.io/
- **Quarkus Kubernetes Extension**: https://quarkus.io/guides/deploying-to-kubernetes
- **cert-manager**: https://cert-manager.io/docs/
- **Sealed Secrets**: https://github.com/bitnami-labs/sealed-secrets

---

**Document Maintenance**: This guide should be updated with each major architecture change or deployment process improvement. Review quarterly.
