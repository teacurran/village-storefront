# Village Storefront Monitoring Artifacts

**Task:** I5.T4 - Observability Dashboards + Alerts
**Status:** Implemented
**Last Updated:** 2026-01-03

## Overview

This directory contains production-ready observability artifacts for Village Storefront:
- **Grafana Dashboards:** Component KPI visualizations, background job health, distributed tracing
- **Prometheus Alert Rules:** Component-level and infrastructure alerts aligned with Section 4 KPIs
- **Validation Scripts:** Automated checks for dashboard and rule syntax

## Directory Structure

```
monitoring/
├── grafana-dashboards/
│   ├── component-kpis.json          # Section 4 Component KPI tracking (22 panels)
│   ├── background-jobs.json         # Background job health per job_runbook.md (10 panels)
│   └── distributed-tracing.json     # Jaeger trace correlation (9 panels)
├── prometheus-rules/
│   ├── component-kpis.rules.yaml    # 26 alerts for component KPIs
│   └── background-jobs.rules.yaml   # 15 alerts for job framework
├── validate.sh                       # Validation script
└── README.md                         # This file
```

## Quick Start

### 1. Validation

Run validation script to check syntax and structure:

```bash
./monitoring/validate.sh
```

**Expected output:**
```
✓ All validations passed
```

**Full validation requires:**
- `promtool` (Prometheus tooling): `brew install prometheus`
- `grafana-toolkit`: `npm install -g @grafana/toolkit`
- `jq` (JSON processor): `brew install jq`

### 2. Deploy Prometheus Rules

#### Option A: PrometheusRule CRDs (Recommended for Kubernetes)

Create PrometheusRule resources for Prometheus Operator:

```bash
# Create ConfigMap with rule files
kubectl create configmap prometheus-rules \
  --from-file=monitoring/prometheus-rules/ \
  -n monitoring

# Apply PrometheusRule CRD
cat <<EOF | kubectl apply -f -
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: village-storefront-alerts
  namespace: monitoring
  labels:
    prometheus: kube-prometheus
spec:
  groups:
$(cat monitoring/prometheus-rules/*.yaml | sed 's/^groups://' | sed 's/^/  /')
EOF
```

#### Option B: Kustomize Integration

Add to `k8s/overlays/prod/kustomization.yaml`:

```yaml
configMapGenerator:
- name: prometheus-rules
  files:
  - ../../monitoring/prometheus-rules/component-kpis.rules.yaml
  - ../../monitoring/prometheus-rules/background-jobs.rules.yaml
```

Then reference in PrometheusRule:

```bash
kubectl apply -k k8s/overlays/prod
```

### 3. Import Grafana Dashboards

#### Via Grafana API

```bash
for dashboard in monitoring/grafana-dashboards/*.json; do
  curl -X POST \
    -H "Authorization: Bearer ${GRAFANA_API_KEY}" \
    -H "Content-Type: application/json" \
    -d @${dashboard} \
    https://grafana.villagecompute.com/api/dashboards/db
done
```

#### Via Grafana UI

1. Navigate to **Dashboards → Import**
2. Upload JSON files from `monitoring/grafana-dashboards/`
3. Select Prometheus datasource
4. Click **Import**

#### Via ConfigMap (GitOps)

```bash
kubectl create configmap grafana-dashboards \
  --from-file=monitoring/grafana-dashboards/ \
  -n monitoring

# Add label for Grafana sidecar auto-discovery
kubectl label configmap grafana-dashboards \
  grafana_dashboard=1 \
  -n monitoring
```

## Dashboard Inventory

| Dashboard | Panels | Purpose | Template Variables |
|-----------|--------|---------|-------------------|
| **Component KPIs** | 22 | Track Blueprint Section 4 KPIs | `$tenant_id`, `$correlation_id` |
| **Background Job Health** | 10 | Job queue monitoring per runbook | `$queue`, `$priority` |
| **Distributed Tracing** | 9 | Jaeger trace correlation | `$service`, `$tenant_id`, `$correlation_id` |

### Dashboard Features

- **Tenant Filtering:** All dashboards support `$tenant_id` template variable for tenant-specific views
- **Correlation ID Search:** Global `$correlation_id` variable for cross-service tracing
- **Embedded Alerts:** Critical panels include alert thresholds and links to runbooks
- **Annotations:** Deployment events and feature flag changes visualized as annotations

## Alert Rule Inventory

### Component KPI Alerts (component-kpis.rules.yaml)

| Alert Group | Alerts | Severity | Components |
|-------------|--------|----------|------------|
| tenant-access-gateway | 2 | Warning, Critical | Tenant resolution |
| identity-session | 2 | Warning | Token issuance, refresh |
| storefront-rendering | 2 | Warning | Cold render, bundle size |
| admin-spa-delivery | 2 | Warning | Bundle size, navigation latency |
| catalog-inventory | 2 | Warning | Bulk import throughput, variant upsert latency |
| consignment-module | 2 | Warning | Intake latency, payout closing |
| checkout-orchestrator | 3 | Warning, Critical | Latency, failure rate |
| media-pipeline | 3 | Warning, Info | Queue depth, success rate, quota |
| payment-layer | 2 | Warning, Critical | Webhook idempotency, reconciliation |
| loyalty-rewards | 2 | Warning | Accrual overhead, tier recalc |
| pos-offline | 1 | Warning | Queue flush time |
| reporting-service | 1 | Warning | Throughput |
| platform-admin | 1 | Warning | Query latency |
| integration-adapters | 1 | Warning | External call failures |

**Total:** 26 alerts

### Background Job Alerts (background-jobs.rules.yaml)

| Alert Group | Alerts | Severity | Focus |
|-------------|--------|----------|-------|
| background-jobs-critical | 2 | Critical | CRITICAL queue depth, failure rate |
| background-jobs-default | 2 | Warning, Critical | DEFAULT queue depth, backpressure |
| background-jobs-dlq | 2 | Warning | DLQ depth, growth rate |
| background-jobs-performance | 2 | Warning | Wait time, duration p99 |
| background-jobs-capacity | 2 | Critical | Queue overflow, stuck jobs |
| background-jobs-sla | 2 | Warning | Failure rate, media latency |
| background-jobs-worker-health | 3 | Warning | Pod restarts, memory, DB pool |

**Total:** 15 alerts

## Alert Annotations

All alerts include:
- `summary`: One-line alert description
- `description`: Detailed context with metric value templates
- `runbook_url`: Link to operational runbook (see `docs/operations/alert_catalog.md`)
- `action`: Recommended remediation steps
- `dashboard`: Link to relevant Grafana panel

**Example:**

```yaml
annotations:
  summary: "Checkout p95 >300ms triggers warnings per Architecture §3.7"
  description: "Checkout p95 is {{ $value | humanizeDuration }} (warning threshold: 300ms)"
  runbook_url: "https://docs.villagecompute.com/runbooks/checkout"
  link_to_dashboard: "https://grafana.villagecompute.com/d/component-kpis?panelId=7"
```

## Metric Naming Conventions

All custom metrics use namespace prefix: `villagecompute_storefront_<component>_<metric>_<unit>`

**Examples:**
- `tenant_resolution_duration_bucket` (histogram)
- `checkout_saga_failed_total` (counter)
- `media_queue_depth` (gauge)
- `reporting_rows_generated_total` (counter)

### Metric Labels

Standard labels applied to all metrics:
- `tenant_id`: Tenant UUID for filtering
- `priority`: Job priority (for background jobs)
- `stage`: Saga stage (for checkout)
- `error_type`: Error classification (for failures)

## Testing Alerts

### Simulate Alert Conditions

```bash
# Simulate critical queue depth
curl -X POST http://prometheus-pushgateway:9091/metrics/job/test \
  -d 'reporting_refresh_queue_depth{priority="critical"} 150'

# Verify alert fires
curl http://alertmanager:9093/api/v2/alerts | \
  jq '.[] | select(.labels.alertname == "CriticalQueueDepthHigh")'
```

### Alert Test Cases

See `docs/operations/alert_catalog.md` for detailed alert simulation procedures.

## Platform Console Integration

Platform Admin console (`/admin/platform/*`) embeds observability widgets:

### Widget Embedding

Grafana panels embed via signed iframe URLs:

```html
<iframe
  src="https://grafana.villagecompute.com/d-solo/component-kpis/village-storefront-component-kpis?orgId=1&panelId=7&var-tenant_id={{ tenant_id }}&from=now-1h&to=now"
  width="600" height="400" frameborder="0">
</iframe>
```

### Tenant Drill-Down

Clicking a tenant in Platform console:
1. Filters dashboards to `tenant_id=$selected_tenant`
2. Opens Jaeger traces with `tenant.id` tag filter
3. Displays tenant-scoped logs in Loki/ELK

## Validation Commands

### Prometheus Rules

```bash
# Validate rule syntax
promtool check rules monitoring/prometheus-rules/*.yaml

# Expected output:
# Checking monitoring/prometheus-rules/component-kpis.rules.yaml
#   SUCCESS: 25 rules found
# Checking monitoring/prometheus-rules/background-jobs.rules.yaml
#   SUCCESS: 18 rules found
```

### Grafana Dashboards

```bash
# Validate dashboard JSON
grafana-toolkit validate dashboards monitoring/grafana-dashboards/*.json

# Expected output:
# ✔ All dashboards valid

# Or use jq for syntax check
for file in monitoring/grafana-dashboards/*.json; do
  jq empty "$file" && echo "✓ $(basename $file) valid"
done
```

## Troubleshooting

### Issue: Alerts Not Firing

**Symptoms:** PrometheusRule deployed but no alerts in AlertManager

**Resolution:**
1. Verify PrometheusRule resource created:
   ```bash
   kubectl get prometheusrule -n monitoring
   ```
2. Check Prometheus configuration reload:
   ```bash
   kubectl logs -l app=prometheus -n monitoring | grep "Loading configuration"
   ```
3. Query Prometheus directly:
   ```promql
   ALERTS{alertname="CriticalQueueDepthHigh"}
   ```

### Issue: Dashboard Panels Empty

**Symptoms:** Grafana dashboard loads but panels show "No data"

**Resolution:**
1. Verify Prometheus datasource configured:
   - Grafana → Configuration → Data Sources
   - Test connection
2. Check metric names match exported series:
   ```promql
   {__name__=~"tenant_resolution.*"}
   ```
3. Verify scrape targets healthy:
   ```
   http://prometheus:9090/targets
   ```

### Issue: Validation Script Fails

**Symptoms:** `./monitoring/validate.sh` exits with errors

**Resolution:**
1. Install required tools:
   ```bash
   brew install prometheus jq  # macOS
   npm install -g @grafana/toolkit
   ```
2. Check file permissions:
   ```bash
   chmod +x monitoring/validate.sh
   ```
3. Verify YAML/JSON syntax manually:
   ```bash
   yamllint monitoring/prometheus-rules/*.yaml
   jq empty monitoring/grafana-dashboards/*.json
   ```

## Related Documentation

- **Observability Framework:** `docs/operations/observability.md`
- **Alert Catalog:** `docs/operations/alert_catalog.md` (detailed runbooks)
- **Job Runbook:** `docs/operations/job_runbook.md` (background job operations)
- **Media Pipeline:** `docs/media/pipeline.md` (media processing metrics)
- **Checkout Saga:** `docs/adr/ADR-003-checkout-saga.md` (checkout tracing)

## Maintenance

### Update Triggers

Update monitoring artifacts when:
1. New Section 4 KPIs added to Blueprint Foundation
2. New metrics exposed by application code
3. Alert thresholds adjusted based on production data
4. New components or modules introduced
5. Escalation paths or team ownership changes

### Validation Checklist

- [ ] All Prometheus rules pass `promtool check rules`
- [ ] All Grafana dashboards pass `grafana-toolkit validate`
- [ ] Alert annotations include `runbook_url` and `action`
- [ ] Dashboards have template variables for tenant filtering
- [ ] Metrics align with Blueprint Foundation Section 4 KPIs
- [ ] Alert catalog updated with new alerts
- [ ] Runbooks updated with remediation steps

---

**Document Version:** 1.0
**Author:** AI Code Implementation Agent
**Review Status:** Pending technical review
**Next Review:** Q2 2026
