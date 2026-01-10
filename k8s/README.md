# Kubernetes Deployment for Village Storefront

## Overview

This directory contains Kubernetes manifests for deploying the Village Storefront platform with multi-tenant gateway, background job workers, media processing workers, and autoscaling.

**Related Documentation:**
- Deployment Workflow: `docs/architecture/ops/deployment-architecture.md`
- Operations Runbook: `docs/operations/job_runbook.md`
- Architecture: `docs/architecture/04_Operational_Architecture.md` (Section 3.9)
- Tasks: I3.T6 (Job Framework), I4.T4 (Media Pipeline), I4.T7 (Deployment)

---

## Directory Structure

```
k8s/
├── base/
│   ├── deployment-gateway.yaml           # Multi-tenant HTTP gateway (Qute + Vue SPA)
│   ├── deployment-workers.yaml           # General-purpose worker deployment
│   ├── deployment-media-workers.yaml     # Media processing workers (FFmpeg)
│   ├── deployment-workers-critical.yaml  # Dedicated CRITICAL/HIGH priority workers
│   ├── ingress.yaml                      # Wildcard ingress with cert-manager
│   └── kustomization.yaml                # Kustomize base configuration
├── overlays/
│   ├── dev/                              # Development environment (minimal resources)
│   ├── staging/                          # Staging environment
│   └── prod/                             # Production environment (HA configuration)
└── README.md                             # This file
```

---

## Deployment Architecture

### Component Deployments

1. **Gateway Pods** (`deployment-gateway.yaml`)
   - Serves HTTP traffic for multi-tenant storefronts and admin SPA
   - Handles tenant resolution via subdomain/custom domain
   - Qute templates for customer-facing storefront (`/`)
   - Vue.js admin dashboard served at `/admin/*`
   - Default: 3 replicas, scales 3-20 based on CPU/memory
   - Resource requests: 250m CPU, 512Mi memory

2. **General-Purpose Workers** (`deployment-workers.yaml`)
   - Processes all priority levels (CRITICAL → BULK)
   - Default: 3 replicas, scales 2-20 based on CPU/memory
   - Resource requests: 250m CPU, 512Mi memory
   - Use case: MVP deployment, cost-effective for mixed workloads

3. **Media Processing Workers** (`deployment-media-workers.yaml`)
   - Processes HIGH and CRITICAL priority media jobs
   - FFmpeg video transcoding (HLS 720p/480p/360p)
   - Thumbnailator image resizing (thumbnail/small/medium/large)
   - Default: 2 replicas, scales 2-10 based on CPU/queue depth
   - Resource requests: 1000m CPU, 1Gi memory (limits: 4000m CPU, 4Gi memory)
   - EmptyDir volume: `/tmp/media_processing` (10Gi limit)
   - Use case: Video transcoding, image processing, poster frame extraction

4. **Critical Workers** (`deployment-workers-critical.yaml`)
   - Processes only CRITICAL and HIGH priority jobs
   - Default: 5 replicas, scales 3-30 based on queue depth
   - Resource requests: 500m CPU, 1Gi memory
   - Use case: Production deployment to prevent priority starvation

### Deployment Strategies

**Option A: Single Worker Pool (MVP)**
- Deploy only `deployment-workers.yaml`
- Simpler operations, lower resource usage
- Risk: Low-priority jobs can starve high-priority jobs during high load

**Option B: Dedicated Priority Pools (Production)**
- Deploy both `deployment-workers.yaml` and `deployment-workers-critical.yaml`
- CRITICAL workers handle urgent jobs (payments, notifications)
- General workers handle reporting, analytics, bulk operations
- Higher resource usage but guaranteed SLA compliance

---

## Building the Native Container Image

Village Storefront uses Quarkus native compilation via GraalVM to produce optimized, lightweight containers (<150MB) with sub-second startup times.

### Prerequisites for Native Build

- Docker or Podman installed
- 8GB+ RAM available for build process
- Maven 3.9+
- Java 21+

### Build Native Container Image

```bash
# Build native executable inside Docker container
docker build -t ghcr.io/villagecompute/village-storefront:latest .

# Alternatively, use Quarkus Maven plugin (slower but more configurable)
./mvnw clean package -Pnative \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.tag=latest

# Verify image was created
docker images | grep village-storefront
```

### Build Configuration

The multi-stage Dockerfile:
1. **Stage 1**: Maven build with Node.js (Quinoa frontend build)
   - Compiles Quarkus native executable via GraalVM
   - Builds Vue.js admin SPA assets
   - Outputs to `target/*-runner` and `target/quinoa/dist/`

2. **Stage 2**: Minimal Alpine runtime
   - Installs FFmpeg for media processing
   - Copies native executable and frontend assets
   - Non-root user (uid 1000)
   - Health check via `/q/health/live`

### Verify Container Locally

```bash
# Run container
docker run -p 8080:8080 \
  -e QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:5432/storefront \
  -e QUARKUS_DATASOURCE_USERNAME=storefront \
  -e QUARKUS_DATASOURCE_PASSWORD=password \
  ghcr.io/villagecompute/village-storefront:latest

# Test health endpoint
curl http://localhost:8080/q/health

# Test FFmpeg availability
docker exec <container-id> ffmpeg -version

# Check frontend assets
curl http://localhost:8080/admin/
```

### Push to Registry (CI/CD)

```bash
# Tag with git commit SHA
export GIT_SHA=$(git rev-parse --short HEAD)
docker tag ghcr.io/villagecompute/village-storefront:latest \
  ghcr.io/villagecompute/village-storefront:${GIT_SHA}

# Login to GitHub Container Registry
echo $GITHUB_TOKEN | docker login ghcr.io -u $GITHUB_USERNAME --password-stdin

# Push images
docker push ghcr.io/villagecompute/village-storefront:latest
docker push ghcr.io/villagecompute/village-storefront:${GIT_SHA}
```

---

## Cert-Manager Setup

Village Storefront uses cert-manager for automatic TLS certificate provisioning via ACME (Let's Encrypt).

### Install cert-manager

```bash
# Install cert-manager CRDs and controllers
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.14.0/cert-manager.yaml

# Verify installation
kubectl get pods -n cert-manager
```

### Create ClusterIssuer

```bash
kubectl apply -f - <<EOF
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    # Let's Encrypt production server
    server: https://acme-v02.api.letsencrypt.org/directory
    email: ops@villagecompute.com
    privateKeySecretRef:
      name: letsencrypt-prod-account-key
    solvers:
    # HTTP-01 challenge solver
    - http01:
        ingress:
          class: nginx
          ingressClassName: nginx
EOF
```

### Create Staging Issuer (for testing)

```bash
kubectl apply -f - <<EOF
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-staging
spec:
  acme:
    server: https://acme-staging-v02.api.letsencrypt.org/directory
    email: ops@villagecompute.com
    privateKeySecretRef:
      name: letsencrypt-staging-account-key
    solvers:
    - http01:
        ingress:
          class: nginx
EOF
```

### Verify Certificate Issuance

After deploying the Ingress manifest:

```bash
# Check certificate status
kubectl get certificate -n village-storefront
kubectl describe certificate village-storefront-tls -n village-storefront

# Check cert-manager logs
kubectl logs -n cert-manager deployment/cert-manager -f

# Verify TLS secret was created
kubectl get secret village-storefront-tls -n village-storefront
```

### Troubleshooting cert-manager

**Common Issues:**

1. **HTTP-01 challenge fails**
   - Ensure ingress-nginx is installed and running
   - Verify DNS points to ingress controller LoadBalancer IP
   - Check firewall allows HTTP (port 80) traffic

2. **Rate limiting**
   - Let's Encrypt has rate limits (50 certs/week per domain)
   - Use staging issuer for testing: change annotation to `letsencrypt-staging`

3. **Wildcard certificate issues**
   - HTTP-01 challenge doesn't support wildcards
   - Use DNS-01 challenge with Cloudflare/Route53 provider
   - See: https://cert-manager.io/docs/configuration/acme/dns01/

---

## Prerequisites

### 1. Namespace

```bash
kubectl create namespace village-storefront
```

### 2. Service Account

```bash
kubectl create serviceaccount village-storefront -n village-storefront
```

### 3. Secrets

Create required secrets for database, object storage, and external services:

```bash
# Database credentials
kubectl create secret generic village-storefront-db \
  --namespace=village-storefront \
  --from-literal=jdbc-url='jdbc:postgresql://postgres.default.svc.cluster.local:5432/storefront' \
  --from-literal=username='storefront' \
  --from-literal=password='<DB_PASSWORD>'

# Cloudflare R2 credentials
kubectl create secret generic village-storefront-r2 \
  --namespace=village-storefront \
  --from-literal=access-key-id='<R2_ACCESS_KEY>' \
  --from-literal=secret-access-key='<R2_SECRET_KEY>'

# Stripe API key
kubectl create secret generic village-storefront-stripe \
  --namespace=village-storefront \
  --from-literal=api-key='sk_live_...'
```

**Production:** Use [Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets) or [External Secrets Operator](https://external-secrets.io/) to manage secrets securely.

### 4. ConfigMap

ConfigMap is auto-generated by Kustomize. To customize:

```bash
kubectl edit configmap village-storefront-config -n village-storefront
```

---

## Deployment

### Using Kustomize (Recommended)

```bash
# Deploy base configuration (general-purpose workers)
kubectl apply -k k8s/base/

# Verify deployment
kubectl get deployments -n village-storefront
kubectl get pods -n village-storefront
kubectl get hpa -n village-storefront
```

### Using kubectl (Direct)

```bash
# Deploy general-purpose workers
kubectl apply -f k8s/base/deployment-workers.yaml -n village-storefront

# Optionally deploy critical workers
kubectl apply -f k8s/base/deployment-workers-critical.yaml -n village-storefront
```

### Verify Health

```bash
# Check pod status
kubectl get pods -l app=village-storefront -n village-storefront

# Check pod logs
kubectl logs -l component=workers -n village-storefront --tail=50

# Check health endpoint
kubectl port-forward -n village-storefront deployment/village-storefront-workers 8080:8080
curl http://localhost:8080/q/health

# Check Prometheus metrics
curl http://localhost:8080/q/metrics | grep queue
```

---

## Configuration

### Environment Variables

Key configuration options (set in deployment YAML or via ConfigMap):

| Variable | Default | Description |
|----------|---------|-------------|
| `QUARKUS_SCHEDULER_ENABLED` | `true` | Enable/disable background job processing |
| `JOBS_QUEUE_CAPACITY_CRITICAL` | `1000` | Max CRITICAL queue depth before overflow |
| `JOBS_QUEUE_CAPACITY_DEFAULT` | `10000` | Max DEFAULT queue depth |
| `JOBS_RETRY_MAX_ATTEMPTS_CRITICAL` | `5` | Max retry attempts for CRITICAL jobs |
| `JOBS_RETRY_MAX_ATTEMPTS_DEFAULT` | `3` | Max retry attempts for DEFAULT jobs |
| `WORKER_PRIORITY_FILTER` | (none) | Comma-separated priorities to process (e.g., `CRITICAL,HIGH`) |

### Resource Limits

**General Workers:**
```yaml
resources:
  requests:
    cpu: "250m"
    memory: "512Mi"
  limits:
    cpu: "1000m"
    memory: "2Gi"
```

**Critical Workers:**
```yaml
resources:
  requests:
    cpu: "500m"
    memory: "1Gi"
  limits:
    cpu: "2000m"
    memory: "4Gi"
```

**Media Workers:**
```yaml
resources:
  requests:
    cpu: "1000m"      # High CPU for FFmpeg transcoding
    memory: "1Gi"
  limits:
    cpu: "4000m"      # Allow burst to 4 cores for 720p transcoding
    memory: "4Gi"
```

Adjust based on workload profiling and cost constraints.

### Media Workers Configuration

Media workers are specialized for CPU-intensive FFmpeg video transcoding and image processing.

**FFmpeg Requirements:**
- Binary path: `/usr/bin/ffmpeg` (bundled in container)
- HLS video variants: 720p@2Mbps, 480p@1Mbps, 360p@500Kbps
- Segment duration: 6 seconds (VOD playlist)
- Codec: H.264 + AAC audio (128k)
- Poster frame extraction at 1 second

**Temporary Storage:**
- EmptyDir volume: `/tmp/media_processing`
- Size limit: 10Gi
- Cleared when pod terminates
- Used for intermediate transcoding files

**Queue Configuration:**
- Priority filter: `WORKER_PRIORITY_FILTER=HIGH,CRITICAL`
- Only processes HIGH and CRITICAL media jobs
- Prevents blocking on low-priority bulk operations

**Health Checks:**
- Liveness probe: 90s initial delay (allows time for long transcode jobs)
- Readiness probe: 60s initial delay
- Timeout: 10s (longer than gateway/workers due to processing load)

**Scaling:**
- Min replicas: 2 (production), 1 (dev)
- Max replicas: 10 (production), 2 (dev)
- Triggers: CPU > 70%, memory > 80%
- Future: Custom metric `media_queue_depth` (target: 50 jobs/pod)

### Horizontal Pod Autoscaling

**General Workers HPA:**
- Min replicas: 2
- Max replicas: 20
- Scale-up trigger: CPU > 70%, Memory > 80%
- Scale-up rate: 50% increase every 60s
- Scale-down rate: 1 pod every 60s after 5min stabilization

**Critical Workers HPA:**
- Min replicas: 3
- Max replicas: 30
- Scale-up trigger: CPU > 60%
- Scale-up rate: 100% increase every 30s (aggressive)
- Scale-down rate: 1 pod every 120s after 10min stabilization

**Custom Metrics (Future):**

When Prometheus Adapter is configured, enable queue-depth-based scaling:

```yaml
metrics:
- type: Pods
  pods:
    metric:
      name: reporting_refresh_queue_depth
    target:
      type: AverageValue
      averageValue: "100"
```

---

## Monitoring

### Prometheus Metrics

Workers expose metrics at `/q/metrics` endpoint. Key metrics:

```promql
# Queue depth per priority
reporting_refresh_queue_depth{priority="critical"}
reporting_export_queue_depth{priority="default"}

# Job throughput
rate(reporting_job_completed[5m])
rate(reporting_job_failed[5m])

# Dead-letter queue depth
reporting_refresh_dlq_depth
reporting_export_dlq_depth

# Job latency (p95)
histogram_quantile(0.95, rate(reporting_job_duration_bucket[5m]))
```

### Grafana Dashboards

Import dashboard: `Background Job Health` (ID: TBD)

Panels:
1. Queue depth by priority (stacked area)
2. Job completion rate vs. failure rate
3. DLQ depth over time
4. Job duration p50/p95/p99
5. Worker pod CPU/memory usage
6. HPA scaling events

### Alerts

Recommended alert rules (Prometheus):

```yaml
groups:
- name: background_jobs
  interval: 30s
  rules:
  - alert: CriticalQueueBacklog
    expr: reporting_refresh_queue_depth{priority="critical"} > 100
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "CRITICAL job queue backing up"
      description: "CRITICAL queue depth {{ $value }} exceeds threshold for 2 minutes"

  - alert: DeadLetterQueueGrowing
    expr: rate(reporting_refresh_dlq_depth[5m]) > 0
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Dead-letter queue accumulating jobs"
      description: "DLQ growing - investigate failed jobs"

  - alert: JobFailureRateHigh
    expr: rate(reporting_job_failed[5m]) / rate(reporting_job_started[5m]) > 0.1
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Job failure rate exceeds 10%"
      description: "{{ $value | humanizePercentage }} of jobs failing"
```

---

## Operations

### Scaling Workers Manually

```bash
# Scale general workers
kubectl scale deployment/village-storefront-workers --replicas=10 -n village-storefront

# Scale critical workers
kubectl scale deployment/village-storefront-workers-critical --replicas=15 -n village-storefront
```

### Pausing Job Processing

```bash
# Option 1: Scale to 0 replicas
kubectl scale deployment/village-storefront-workers --replicas=0 -n village-storefront

# Option 2: Disable scheduler via ConfigMap
kubectl set env deployment/village-storefront-workers \
  QUARKUS_SCHEDULER_ENABLED=false -n village-storefront
```

### Resuming Job Processing

```bash
# Restore replicas
kubectl scale deployment/village-storefront-workers --replicas=3 -n village-storefront

# Re-enable scheduler
kubectl set env deployment/village-storefront-workers \
  QUARKUS_SCHEDULER_ENABLED=true -n village-storefront
```

### Viewing Logs

```bash
# All worker logs
kubectl logs -l component=workers -n village-storefront --tail=100 -f

# Specific pod logs
kubectl logs village-storefront-workers-<pod-id> -n village-storefront

# Filter for job failures
kubectl logs -l component=workers -n village-storefront | grep "Job failed"
```

### Debugging

```bash
# Shell into worker pod
kubectl exec -it deployment/village-storefront-workers -n village-storefront -- /bin/sh

# Check health
curl http://localhost:8080/q/health

# View metrics
curl http://localhost:8080/q/metrics | grep queue

# Thread dump
jstack 1
```

---

## Rollout Strategy

### Rolling Update (Default)

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

- New pods start before old pods terminate
- Zero downtime for job processing
- Gradual queue handoff

### Blue-Green Deployment

For major framework changes:

```bash
# Deploy new version as separate deployment
kubectl apply -f deployment-workers-v2.yaml -n village-storefront

# Monitor new version processing jobs
kubectl logs -l version=v2 -n village-storefront

# Switch traffic by scaling old version to 0
kubectl scale deployment/village-storefront-workers --replicas=0 -n village-storefront
kubectl scale deployment/village-storefront-workers-v2 --replicas=5 -n village-storefront
```

---

## Troubleshooting

### Pods Not Starting

**Check events:**
```bash
kubectl describe pod <pod-name> -n village-storefront
```

**Common issues:**
- Missing secrets: Verify `village-storefront-db`, `village-storefront-r2`, `village-storefront-stripe` exist
- Image pull errors: Check `imagePullPolicy` and registry credentials
- Resource constraints: Verify node capacity with `kubectl describe node`

### Jobs Not Processing

**Check scheduler status:**
```bash
kubectl exec deployment/village-storefront-workers -n village-storefront -- \
  curl -s http://localhost:8080/q/health | jq '.checks[] | select(.name | contains("scheduler"))'
```

**Check environment variables:**
```bash
kubectl exec deployment/village-storefront-workers -n village-storefront -- env | grep SCHEDULER
```

**Check logs for errors:**
```bash
kubectl logs -l component=workers -n village-storefront | grep -E "(ERROR|WARN)"
```

### High Memory Usage

**Check current usage:**
```bash
kubectl top pods -l component=workers -n village-storefront
```

**Increase memory limits:**
```bash
kubectl set resources deployment/village-storefront-workers \
  --limits=memory=4Gi -n village-storefront
```

**Heap dump for analysis:**
```bash
kubectl exec deployment/village-storefront-workers -n village-storefront -- \
  jcmd 1 GC.heap_dump /tmp/heap.hprof
```

---

## Migration Guide

### From Existing Job System

1. **Deploy framework in shadow mode:**
   - Keep existing job system running
   - Deploy new workers with `WORKER_PRIORITY_FILTER=BULK`
   - Monitor metrics for 7 days

2. **Gradual traffic shift:**
   - Move non-critical jobs (BULK, LOW) to new framework
   - Monitor DLQ and failure rates
   - Iterate on retry policies

3. **Full cutover:**
   - Migrate all jobs to new framework
   - Decommission old job system
   - Update runbooks and dashboards

---

## Related Documentation

- **Operations Runbook:** `docs/operations/job_runbook.md`
- **Architecture:** `docs/architecture/04_Operational_Architecture.md`
- **Integration Tests:** `src/test/java/villagecompute/storefront/services/jobs/JobSchedulerTest.java`
- **Framework Code:** `src/main/java/villagecompute/storefront/services/jobs/config/`

---

## Support

For operational issues:
- **On-call:** PagerDuty rotation
- **Slack:** `#incidents-storefront`
- **Status Page:** https://status.villagecompute.com
