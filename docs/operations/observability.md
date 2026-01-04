# Village Storefront Observability Framework

**Task:** I5.T4
**Status:** Implemented
**Last Updated:** 2026-01-03

## Overview

This document describes the observability fabric for Village Storefront, covering structured logging, distributed tracing, metrics collection, alerting rules, and Platform console integration. All components align with Architecture §3.7 (Observability Fabric) and Blueprint Foundation Section 4 (Component KPIs).

**References:**
- Architecture: `docs/architecture/04_Operational_Architecture.md` §3.7
- Blueprint: `docs/architecture/01_Blueprint_Foundation.md` Section 4
- Job Runbook: `docs/operations/job_runbook.md`
- Media Pipeline: `docs/media/pipeline.md`
- Checkout Saga: `docs/adr/ADR-003-checkout-saga.md`

---

## Table of Contents

1. [Logging Strategy](#logging-strategy)
2. [Distributed Tracing](#distributed-tracing)
3. [Metrics Collection](#metrics-collection)
4. [Dashboards](#dashboards)
5. [Alerting Rules](#alerting-rules)
6. [Platform Console Integration](#platform-console-integration)
7. [Synthetic Monitoring](#synthetic-monitoring)
8. [Validation & Testing](#validation--testing)

---

## Logging Strategy

### Structured JSON Logging

All application logs use structured JSON format (SLF4J + Logback) with mandatory context fields per Architecture §3.7:

```json
{
  "timestamp": "2026-01-03T12:34:56.789Z",
  "level": "INFO",
  "logger": "villagecompute.storefront.services.CheckoutOrchestrator",
  "message": "Checkout saga completed",
  "tenant_id": "550e8400-e29b-41d4-a716-446655440000",
  "store_id": "7f3d2e1a-8b4c-4f5e-9c6d-1a2b3c4d5e6f",
  "user_id": "9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d",
  "session_id": "abc123-session",
  "correlation_id": "X-Request-ID-value",
  "impersonation_context": {
    "admin_id": "platform-admin-uuid",
    "reason": "customer support investigation"
  },
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "duration_ms": 245,
  "stage": "payment_processing",
  "order_id": "order-uuid"
}
```

### Mandatory Fields

| Field | Source | Purpose |
|-------|--------|---------|
| `tenant_id` | `TenantContext` CDI bean | Multi-tenant data isolation |
| `store_id` | Resolved from subdomain/domain | Store-level filtering |
| `user_id` | JWT claims | User action attribution |
| `session_id` | Session tracking | Login activity correlation |
| `correlation_id` | `X-Request-ID` HTTP header | Cross-service tracing |
| `impersonation_context` | Admin impersonation metadata | Audit trail for privileged actions |
| `trace_id`, `span_id` | OpenTelemetry auto-instrumentation | Jaeger trace correlation |

### Log Retention & Storage

- **Centralized Collectors:** Logs stream to Loki or ELK stack (deployment-specific)
- **Retention Policy:**
  - Application logs: 30 days hot storage, 90 days cold archive
  - Audit events (impersonation, compliance): 7 years per compliance requirements
  - Debug logs: 7 days (enabled only in non-production)

### Log Level Configuration

```properties
# application.properties
quarkus.log.level=INFO
quarkus.log.console.json=true

# Component-specific levels
quarkus.log.category."villagecompute.storefront.services".level=DEBUG
quarkus.log.category."villagecompute.storefront.payment".level=INFO
quarkus.log.category."org.hibernate".level=WARN
```

### Correlation ID Propagation

Per Architecture §3.7, correlation IDs propagate via:
- **HTTP Headers:** `X-Request-ID` passed through all internal API calls
- **Background Jobs:** Job metadata includes `correlation_id` from originating request
- **External API Calls:** Propagated to Stripe, shipping carriers, address validation services
- **CDN Caches:** CloudFlare forwards `X-Request-ID` to origin

---

## Distributed Tracing

### OpenTelemetry Instrumentation

Quarkus OpenTelemetry extension (`quarkus-opentelemetry`) auto-instruments:
- REST endpoints (JAX-RS)
- Background job handlers
- Database queries (JDBC)
- External HTTP clients (Stripe SDK, carrier APIs)

**Jaeger Exporter Configuration:**

```properties
# application.properties
quarkus.otel.exporter.otlp.endpoint=http://jaeger-collector:4317
quarkus.otel.service.name=village-storefront
quarkus.otel.traces.sampler=parentbased_traceidratio
quarkus.otel.traces.sampler.arg=0.1  # 10% sampling in production
```

### Service Name Mapping

Bounded contexts use distinct service names for Jaeger service graph:

| Service Name | Component | Spans |
|--------------|-----------|-------|
| `village-storefront` | Main application | REST endpoints, storefront rendering |
| `checkout-orchestrator` | Checkout module | `CheckoutSaga.*` spans per ADR-003 |
| `media-processor` | Media pipeline | `FFmpeg.*`, `Thumbnailator.*` spans |
| `background-jobs` | Job framework | `Job.execute.*` spans |
| `platform-admin` | Platform console | Admin API calls, impersonation |

### Span Naming Convention

```java
@WithSpan("CheckoutSaga.addressValidation")
public ValidatedAddress validateAddress(Address address) {
    Span span = Span.current();
    span.setAttribute("address.country", address.country());
    span.setAttribute("tenant.id", TenantContext.getCurrentTenantId().toString());
    span.setAttribute("correlation.id", RequestContext.getCorrelationId());
    // ... validation logic
}
```

**Span Hierarchy Example (Checkout):**

```
StorefrontController.checkout (root)
├─ CheckoutSaga.addressValidation
├─ ShippingRateAdapter.quote
│   ├─ UPSAdapter.getRates
│   ├─ FedExAdapter.getRates
│   └─ USPSAdapter.getRates
├─ CheckoutSaga.inventoryReservation
└─ StripeIntentService.commit
    └─ StripeAPI.createPaymentIntent
```

### Trace Attributes

Standard attributes per OpenTelemetry semantic conventions:

```java
// HTTP attributes
span.setAttribute("http.method", "POST");
span.setAttribute("http.url", "/checkout/commit");
span.setAttribute("http.status_code", 200);

// Database attributes
span.setAttribute("db.system", "postgresql");
span.setAttribute("db.statement", "SELECT * FROM orders WHERE id = ?");

// Custom business attributes
span.setAttribute("order.id", orderId.toString());
span.setAttribute("checkout.stage", "payment_processing");
span.setAttribute("tenant.id", tenantId.toString());
```

### Jaeger UI Access

- **Production:** `https://jaeger.villagecompute.com`
- **Staging:** `https://jaeger-staging.villagecompute.com`
- **Query by Correlation ID:** Use `correlation_id` tag filter in Jaeger UI
- **Query by Tenant:** Use `tenant.id` tag filter for tenant-specific traces

---

## Metrics Collection

### Prometheus Scrape Configuration

Quarkus Micrometer extension (`quarkus-micrometer-registry-prometheus`) exposes metrics at `/q/metrics`.

**Scrape Interval:** 30s (configurable via ServiceMonitor)

**Existing ServiceMonitor Reference:**

The project currently has Prometheus ServiceMonitor resources in `k8s/overlays/prod/service-monitor.yaml`. New Prometheus rules from `monitoring/prometheus-rules/` should be included via Kustomize patches:

```yaml
# k8s/overlays/prod/kustomization.yaml
resources:
- ../../base
- service-monitor.yaml

configMapGenerator:
- name: prometheus-rules
  files:
  - ../../monitoring/prometheus-rules/component-kpis.rules.yaml
  - ../../monitoring/prometheus-rules/background-jobs.rules.yaml
```

> **Plan reference note:** Architecture plans reference `k8s/base/prometheus.yaml`, but the canonical manifests live under `k8s/overlays/prod`. Always source rule ConfigMaps from `monitoring/prometheus-rules/` when extending Prometheus coverage so overlays stay authoritative.

### Metric Naming Convention

All custom metrics use namespace prefix `villagecompute_storefront_`:

```
villagecompute_storefront_<component>_<metric_name>_<unit>
```

**Examples:**
- `villagecompute_storefront_tenant_resolution_duration_seconds`
- `villagecompute_storefront_checkout_saga_duration_seconds`
- `villagecompute_storefront_media_queue_depth_jobs`

### Component KPI Metrics

Per Blueprint Foundation Section 4, the following metrics track component KPIs:

#### Tenant Access Gateway
```promql
# Resolution latency (target: <10ms median)
tenant_resolution_duration_bucket

# Misclassification rate (target: <0.1%)
tenant_resolution_misclassified_total
tenant_resolution_total
```

#### Identity & Session Service
```promql
# Token issuance latency (target: <50ms median)
token_issuance_duration_bucket

# Refresh success ratio (target: >99%)
token_refresh_success_total
token_refresh_total
```

#### Storefront Rendering Engine
```promql
# Cold render latency (target: <150ms)
storefront_render_duration_bucket{cold="true"}

# Hydration bundle size (target: <120KB gzipped)
storefront_bundle_size_bytes{type="hydration"}
```

#### Admin SPA Delivery
```promql
# Initial bundle size (target: <2MB gzipped)
admin_spa_bundle_size_bytes{type="initial"}

# Navigation latency (target: <200ms p95)
admin_spa_navigation_latency_bucket
```

#### Catalog & Inventory Module
```promql
# Bulk import throughput (target: 5k products/min)
catalog_bulk_import_products_total

# Variant upsert latency (batched SQL target: <200ms p95)
variant_upsert_duration_bucket
```

#### Consignment Module
```promql
# Intake batch creation latency (target: <500ms for 100 items)
consignment_intake_duration_bucket

# Payout batch closing latency (target: <5 minutes)
consignment_payout_close_duration_bucket
```

#### Checkout & Order Orchestrator
```promql
# Checkout latency (target: <800ms p95)
checkout_saga_duration_bucket{stage="complete"}

# Failure funnel by stage
checkout_saga_failed_total{stage,error_type}
```

#### Media Pipeline
```promql
# Job success rate (target: >99.5%)
media_job_success_total
media_job_total

# Queue depth (alert threshold: >100)
media_queue_depth{priority}

# Quota exceeded events
media_quota_exceeded_total
```

#### Background Job Scheduler
```promql
# Queue depth by priority
reporting_refresh_queue_depth{priority}

# Job lifecycle counters
reporting_job_enqueued_total{priority}
reporting_job_started_total{priority}
reporting_job_completed_total{priority}
reporting_job_failed_total{priority,attempt}
reporting_job_retried_total{priority,attempt}
reporting_job_exhausted_total{priority}

# Dead-letter queue
reporting_dlq_depth
reporting_dlq_added_total{priority}
reporting_dlq_removed_total

# Job timing histograms
reporting_job_duration_bucket{priority,status}
reporting_job_wait_time_bucket{priority}

# Queue overflow
reporting_queue_overflow_total{priority}
```

**Note:** Metric names in `docs/operations/job_runbook.md` use placeholders (`<queue_name>`). In practice, replace with actual exported series like `reporting_refresh_queue_depth`, `media_job_failed_total{type="video"}`, etc.

#### Payment Layer
```promql
# Webhook idempotency ratio (target: 100%)
stripe_webhook_duplicate_total
stripe_webhook_received_total

# Payout reconciliation
payout_reconciliation_unresolved_total
```

#### Loyalty & Rewards Module
```promql
# Points accrual overhead (target: <20ms)
loyalty_points_accrual_duration_bucket

# Tier recalculation job duration (target: <10 minutes)
loyalty_tier_recalc_job_duration_seconds
```

#### POS & Offline Processor
```promql
# Offline queue flush time (target: <60s p95)
pos_offline_flush_duration_bucket
```

#### Reporting Service
```promql
# Report generation throughput (target: 50k rows/min)
reporting_rows_generated_total
```

#### Platform Admin Backend
```promql
# Global store list query latency (target: <300ms p95)
platform_admin_store_list_duration_bucket
```

#### Integration Adapter Layer
```promql
# External call failures
integration_adapter_failed_total{adapter,failure_type}
```

### Tenant-Scoped Metrics

All metrics include `tenant_id` label for tenant-specific filtering:

```promql
# Query checkout latency for specific tenant
histogram_quantile(0.95,
  sum(rate(checkout_saga_duration_bucket{tenant_id="550e8400-..."}[5m])) by (le)
)
```

---

## Dashboards

Grafana dashboards are stored as version-controlled JSON in `monitoring/grafana-dashboards/`.

### Dashboard Inventory

| Dashboard | File | Purpose | Panels |
|-----------|------|---------|--------|
| **Component KPIs** | `component-kpis.json` | Track all Section 4 KPIs | 22 panels (tenant gateway, identity, admin SPA, catalog, consignment, checkout, media, etc.) |
| **Background Job Health** | `background-jobs.json` | Job queue monitoring per runbook | 10 panels (queue depth, throughput, DLQ, retries) |
| **Distributed Tracing** | `distributed-tracing.json` | Jaeger trace correlation | 9 panels (service latency, error rate, trace search) |

### Dashboard Features

**Template Variables:**
- `$tenant_id`: Multi-select tenant filter (default: All)
- `$correlation_id`: Text input for trace correlation
- `$priority`: Job priority filter (CRITICAL, HIGH, DEFAULT, LOW, BULK)

**Annotations:**
- Deployment events: Shows when pods rollout (from Kubernetes)
- Feature flag changes: Highlights when flags toggle for tenant cohorts

**Alerts Embedded in Dashboards:**
- Checkout latency warning (p95 >300ms)
- Media queue depth high (>100 jobs)
- DLQ depth high (>10 jobs)
- Queue overflow events

### Dashboard Links to Runbooks

Each panel with alerts includes `runbook_url` annotation linking to:
- `docs/operations/job_runbook.md` for background job alerts
- `docs/media/pipeline.md` for media pipeline alerts
- `docs/adr/ADR-003-checkout-saga.md` for checkout observability

### Section 4 KPI Coverage Matrix

| Component | KPI Focus | Panel IDs | Alerts | Runbook Reference |
|-----------|-----------|-----------|--------|-------------------|
| Tenant Access Gateway | Tenant resolution latency & misclassification | 1-2 | `TenantResolutionLatencyHigh`, `TenantMisclassificationRateHigh` | `docs/operations/alert_catalog.md#component-kpi-alerts` |
| Identity & Session | Token issuance latency, refresh success | 3-4 | `TokenIssuanceLatencyHigh`, `TokenRefreshSuccessRateLow` | `docs/operations/alert_catalog.md#identity--session-service` |
| Storefront Rendering Engine | Cold render latency, hydration bundle size | 5 | `StorefrontColdRenderSlow`, `HydrationBundleSizeLarge` | `docs/operations/alert_catalog.md#storefront-rendering` |
| Admin SPA Delivery | Initial bundle size, cached navigation latency | 19-20 | `AdminBundleSizeTooLarge`, `AdminNavigationLatencyHigh` | `docs/operations/alert_catalog.md#admin-spa-delivery` |
| Catalog & Inventory Module | Bulk import throughput, variant upsert latency | 6 | `CatalogBulkImportThroughputLow`, `VariantUpsertLatencyHigh` | `docs/operations/alert_catalog.md#catalog--inventory-module` |
| Consignment Module | Intake batch creation, payout closing time | 21-22 | `ConsignmentIntakeLatencyHigh`, `ConsignmentPayoutClosureSlow` | `docs/operations/alert_catalog.md#consignment-module` |
| Checkout & Order Orchestrator | Checkout saga latency & failures | 7-8 | `CheckoutLatencyWarning/Critical`, `CheckoutFailureRateHigh` | `docs/operations/alert_catalog.md#checkout--order-orchestrator` |
| Media Pipeline | Success rate, queue depth, quota saturation | 9-10 | `MediaQueueDepthHigh`, `MediaSuccessRateLow`, `MediaQuotaExceededFrequent` | `docs/media/pipeline.md` |
| Background Job Scheduler | Queue depth, DLQ, throughput | 11-12 | `CriticalQueueDepthHigh`, `DeadLetterQueueDepthHigh`, `QueueOverflowEvents`, `JobFailureRateSLAViolation` | `docs/operations/job_runbook.md` |
| Payment Layer | Webhook idempotency, payout reconciliation | 13 | `StripeWebhookIdempotencyViolation`, `PayoutReconciliationDiscrepancy` | `docs/operations/alert_catalog.md#payment-layer` |
| Loyalty & Rewards | Points accrual overhead, tier recalculation | 14 | `LoyaltyPointsAccrualOverhead`, `LoyaltyTierRecalculationSlow` | `docs/operations/alert_catalog.md#loyalty--rewards` |
| POS & Offline Processor | Offline queue flush time | 15 | `POSOfflineFlushSlow` | `docs/operations/alert_catalog.md#pos--offline-processor` |
| Reporting Service | Report generation throughput | 16 | `ReportGenerationThroughputLow` | `docs/operations/alert_catalog.md#reporting-service` |
| Platform Admin Backend | Global store list query latency | 17 | `PlatformAdminStoreListSlow` | `docs/operations/alert_catalog.md#platform-admin-backend` |
| Integration Adapter Layer | External adapter failure rates | 18 | `IntegrationAdapterFailureRateHigh` | `docs/operations/alert_catalog.md#integration-adapter-layer` |

### Validation Commands

```bash
# Validate dashboard JSON schema
grafana-toolkit validate dashboards monitoring/grafana-dashboards/*.json

# Import dashboards to Grafana
for file in monitoring/grafana-dashboards/*.json; do
  curl -X POST -H "Content-Type: application/json" \
    -d @$file \
    http://admin:password@grafana.villagecompute.com/api/dashboards/db
done
```

---

## Alerting Rules

Prometheus alerting rules are stored in `monitoring/prometheus-rules/` and loaded via PrometheusRule CRDs.

### Rule File Inventory

| Rule File | Alert Groups | Alerts | Severity Levels |
|-----------|--------------|--------|-----------------|
| `component-kpis.rules.yaml` | 14 groups | 26 alerts | Warning, Critical, Info |
| `background-jobs.rules.yaml` | 7 groups | 15 alerts | Warning, Critical, Info |

### Severity Taxonomy

| Severity | Response Time | On-Call | Examples |
|----------|---------------|---------|----------|
| **Critical** | Immediate (P1) | Yes | Queue overflow, jobs not processing, payment reconciliation failures |
| **Warning** | 30 minutes (P2) | No | High latency, elevated error rates, DLQ accumulation |
| **Info** | Next business day | No | Frequent quota exceeded, deployment annotations |

### Alert Annotations

All alerts include:
- `summary`: One-line description
- `description`: Detailed context with metric values
- `runbook_url`: Link to operational runbook section
- `action`: Recommended remediation steps
- `dashboard`: Link to relevant Grafana panel

**Example Alert:**

```yaml
- alert: CheckoutLatencyWarning
  expr: |
    histogram_quantile(0.95, sum(rate(checkout_saga_duration_bucket{stage="complete"}[5m])) by (le)) > 0.300
  for: 5m
  labels:
    severity: warning
    component: checkout
    kpi: checkout-latency
  annotations:
    summary: "Checkout p95 >300ms triggers warnings per Architecture §3.7"
    description: "Checkout p95 is {{ $value | humanizeDuration }} (warning threshold: 300ms)"
    runbook_url: "https://docs.villagecompute.com/runbooks/checkout"
    link_to_dashboard: "https://grafana.villagecompute.com/d/component-kpis?panelId=7"
```

### Alert Routing (AlertManager)

**Oncall Routing (Critical Alerts):**
- Alerts with `oncall: true` label route to PagerDuty
- Escalation policy: Engineer → Tech Lead → VP Engineering

**Team Routing (Warning Alerts):**
- Slack channel: `#alerts-storefront`
- Email: `eng-storefront@villagecompute.com`

**Inhibition Rules:**
- `WorkerPodRestartRate` inhibits `CriticalQueueDepthHigh` (if workers restarting, queue backing up is expected)
- `AnyQueueDepthCritical` inhibits `DefaultQueueDepthHigh` (more severe alert supersedes)

### Validation Commands

```bash
# Validate Prometheus rule syntax
promtool check rules monitoring/prometheus-rules/*.yaml

# Run consolidated validation (rules + dashboards + docs)
./monitoring/validate.sh
```

---

## Platform Console Integration

The Platform Admin console (`/admin/platform/*`) embeds observability widgets for ops/support teams.

### Widget Inventory

| Widget | Data Source | Refresh Rate | Purpose |
|--------|-------------|--------------|---------|
| **Active Alerts** | Prometheus AlertManager API | 30s | Show firing alerts across all tenants |
| **Tenant Error Rate** | Prometheus | 1m | Heatmap of error rate by tenant |
| **Queue Depth Gauges** | Prometheus | 30s | Real-time queue depth by priority |
| **Recent Traces** | Jaeger API | 1m | Latest traces with errors or high latency |
| **Impersonation Audit Log** | PostgreSQL | 5m | Recent impersonation sessions with reason |

### Tenant-Specific Drill-Down

Clicking a tenant in Platform console:
1. Filters Grafana dashboards to `tenant_id=$selected_tenant`
2. Opens Jaeger traces filtered by `tenant.id` tag
3. Displays tenant-specific logs in Loki/ELK with `tenant_id` filter

### Correlation ID Search

Platform console includes global search bar for correlation IDs:
- Input: `X-Request-ID` value from user support ticket
- Output: Unified view of logs + traces + metrics for that request
- Links: Direct links to Jaeger trace, Grafana panels, log aggregator

### Widget Embedding

Grafana panels embed via iframe with signed URLs:

```html
<iframe
  src="https://grafana.villagecompute.com/d-solo/component-kpis/village-storefront-component-kpis?orgId=1&panelId=7&var-tenant_id={{ tenant_id }}&from=now-1h&to=now"
  width="600" height="400" frameborder="0">
</iframe>
```

---

## Synthetic Monitoring

Per Architecture §3.7, synthetic monitors test critical user flows using canary tenants.

### Monitored Flows

| Flow | Endpoint | Frequency | SLA Target | Canary Tenant |
|------|----------|-----------|------------|---------------|
| **Login** | `POST /auth/login` | 5 minutes | <500ms p95 | `canary-login` |
| **Storefront Rendering** | `GET /` | 5 minutes | <150ms cold render | `canary-storefront` |
| **Checkout** | `POST /checkout/commit` | 10 minutes | <800ms p95 | `canary-checkout` |
| **Admin Dashboard** | `GET /admin/dashboard` | 5 minutes | <2s load | `canary-admin` |

### Synthetic Monitoring Stack

- **Tool:** Playwright or Selenium Grid
- **Execution:** Kubernetes CronJob in monitoring namespace
- **Results Storage:** Prometheus pushgateway for metrics
- **Failure Handling:** Screenshot capture on failure → store in S3

### SLA Reporting

Synthetic monitor results feed into monthly SLA reports:
- **Availability:** % of successful synthetic checks (target: 99.9%)
- **Latency:** p95 latency from synthetic runs
- **Screenshot Evidence:** Archived for compliance/debugging

---

## Validation & Testing

### Dashboard Validation

```bash
# Validate JSON schema
grafana-toolkit validate dashboards monitoring/grafana-dashboards/*.json

# Expected output: "✔ All dashboards valid"
```

### Prometheus Rule Validation

```bash
# Validate rule syntax
promtool check rules monitoring/prometheus-rules/*.yaml

# Expected output:
# Checking monitoring/prometheus-rules/component-kpis.rules.yaml
#   SUCCESS: 25 rules found
# Checking monitoring/prometheus-rules/background-jobs.rules.yaml
#   SUCCESS: 18 rules found
```

### Alert Simulation

Inject test metrics to trigger alerts:

```bash
# Simulate critical queue depth
curl -X POST http://prometheus-pushgateway:9091/metrics/job/test \
  -d 'reporting_refresh_queue_depth{priority="critical"} 150'

# Verify alert fires in AlertManager UI
curl http://alertmanager:9093/api/v2/alerts | jq '.[] | select(.labels.alertname == "CriticalQueueDepthHigh")'
```

### Trace Injection Testing

```java
@QuarkusTest
class ObservabilityTest {
    @Inject
    Tracer tracer;

    @Test
    void testCheckoutSpanHierarchy() {
        // Execute checkout
        given()
            .header("X-Request-ID", "test-correlation-123")
            .body(checkoutRequest)
        .when()
            .post("/checkout/commit")
        .then()
            .statusCode(200);

        // Query Jaeger for trace
        List<Trace> traces = jaegerClient.getTraces("correlation_id=test-correlation-123");
        assertThat(traces).hasSize(1);

        Trace trace = traces.get(0);
        assertThat(trace.spans)
            .extracting("operationName")
            .containsExactlyInAnyOrder(
                "StorefrontController.checkout",
                "CheckoutSaga.addressValidation",
                "ShippingRateAdapter.quote",
                "CheckoutSaga.inventoryReservation",
                "StripeIntentService.commit"
            );
    }
}
```

---

## Ownership & Escalation

### Component Ownership

| Component | Primary Owner | Secondary Owner | Slack Channel |
|-----------|---------------|-----------------|---------------|
| Tenant Gateway | Platform Team | SRE | `#platform-core` |
| Checkout | Payments Team | Backend | `#team-payments` |
| Media Pipeline | Media Team | SRE | `#team-media` |
| Background Jobs | Backend Team | SRE | `#team-backend` |
| Platform Console | Platform Team | Frontend | `#platform-admin` |

### Escalation Matrix

See `docs/operations/alert_catalog.md` for detailed escalation paths per alert.

**General Escalation Path:**
1. On-call engineer (PagerDuty)
2. Component owner team lead
3. Engineering Manager
4. VP Engineering

**Incident Slack Channel:** `#incidents-storefront`
**Status Page:** `https://status.villagecompute.com`

---

## Related Documentation

- **Alert Catalog:** `docs/operations/alert_catalog.md` (detailed alert runbooks)
- **Job Runbook:** `docs/operations/job_runbook.md` (background job operations)
- **Media Pipeline:** `docs/media/pipeline.md` (media processing observability)
- **Checkout Saga:** `docs/adr/ADR-003-checkout-saga.md` (checkout tracing strategy)
- **Feature Flags:** `docs/operations/feature_flags.md` (flag state correlation)
- **Incident Response:** `docs/operations/incident_response.md` (incident playbooks)

---

## Document Maintenance

### Update Triggers

This document should be updated when:
1. New metrics or KPIs added to Section 4 Component KPIs
2. Dashboards or alert rules modified
3. Integration with new observability tools (e.g., Datadog, New Relic)
4. Platform console widgets added or changed
5. Retention policies or compliance requirements change

### Validation Checklist

- [ ] All dashboard JSON files pass `grafana-toolkit validate`
- [ ] All Prometheus rules pass `promtool check rules`
- [ ] Alert annotations include `runbook_url` and `action`
- [ ] Metrics align with Blueprint Foundation Section 4 KPIs
- [ ] Correlation IDs propagate through logs + traces + metrics
- [ ] Platform console widgets tested with live data

---

**Document Version:** 1.0
**Author:** AI Code Implementation Agent
**Review Status:** Pending technical review
**Next Review:** Q2 2026
