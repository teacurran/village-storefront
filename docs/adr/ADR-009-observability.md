# ADR-009: Observability Framework & Telemetry Strategy

**Status:** Accepted
**Date:** 2026-01-18
**Decision Makers:** Architecture Team, Platform Operations Team
**Related ADRs:** [ADR-001 (Multi-Tenancy)](ADR-001-tenancy.md)

---

## Context

Multi-tenant SaaS platform requires comprehensive observability for incident response, performance debugging, and SLA monitoring. Must support tenant-scoped metrics, distributed tracing, and structured logging.

---

## Decision

We implement **OpenTelemetry + Prometheus + Grafana + Jaeger** stack with tenant context propagation:

### 1. Telemetry Stack

| Component | Purpose | Deployment |
|-----------|---------|------------|
| **OpenTelemetry** | Instrumentation SDK | Embedded in application |
| **Prometheus** | Metrics collection & storage | k3s cluster |
| **Grafana** | Visualization & dashboards | k3s cluster (observability.villagecompute.com) |
| **Jaeger** | Distributed tracing | k3s cluster |
| **Loki** | Log aggregation | k3s cluster |

### 2. Metrics Strategy

**Custom Metrics Format**: `{component}.{action}.{status}{tenant_id,priority}`

**Examples**:
- `media_queue_depth{tenant=X,priority=DEFAULT}` - Media job queue backlog
- `checkout_order_created{tenant=X,payment_method=stripe}` - Orders created counter
- `api_request_duration{endpoint=/products,tenant=X}` - API latency histogram

**Tenant Isolation**: All metrics include `tenant_id` label for per-tenant dashboards

### 3. Distributed Tracing

- **Trace ID Propagation**: W3C Trace Context headers (`traceparent`, `tracestate`)
- **Tenant Context Injection**: Every span includes `tenant.id` attribute
- **Sampling Strategy**: 100% for errors, 10% for successful requests
- **Span Attributes**: `http.method`, `http.url`, `db.statement`, `tenant.id`

### 4. Structured Logging

**Log Format** (JSON):
```json
{
  "timestamp": "2026-01-18T10:30:00Z",
  "level": "ERROR",
  "logger": "villagecompute.storefront.services.CheckoutService",
  "message": "Payment authorization failed",
  "tenant_id": "550e8400-e29b-41d4-a716-446655440000",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "00f067aa0ba902b7",
  "error": "PaymentDeclinedException: Insufficient funds"
}
```

**Required Fields**: `timestamp`, `level`, `logger`, `message`, `tenant_id` (when available), `trace_id`

### 5. Dashboards

**Platform Health Dashboard**:
- API request rate & latency (p50/p95/p99)
- Background job queue depth & processing latency
- Database connection pool usage
- Error rate by component

**Tenant Health Dashboard**:
- Filterable by `tenant_id`
- Order conversion funnel
- Media processing throughput
- Payment success rate

---

## Rationale

**Why OpenTelemetry (vs. Proprietary APM like DataDog)?**
- ✅ Vendor-neutral standard (portable across backends)
- ✅ Open-source (zero licensing cost)
- ✅ Single SDK for metrics, traces, logs

**Why Self-Hosted (vs. Cloud APM)?**
- ✅ Cost: $0 vs. $15-100/host/month for DataDog/NewRelic
- ✅ Data sovereignty (sensitive tenant data stays in-cluster)
- ✅ No external dependency (APM outage doesn't blind operations)

**Why Tenant ID in All Telemetry (vs. Aggregated Only)?**
- ✅ Per-tenant SLA monitoring
- ✅ Troubleshoot specific tenant issues
- ✅ Identify noisy neighbors consuming resources

---

## Consequences

### Positive
- Comprehensive visibility into platform health and tenant-specific issues
- Distributed tracing correlates logs/metrics/traces for incident investigation
- Self-hosted eliminates APM vendor costs (~$5k-10k/year at scale)

### Negative & Mitigations
- **Storage Growth**: Metrics/traces consume disk → Retention policy: 30 days metrics, 7 days traces
- **Query Performance**: High-cardinality `tenant_id` label → Use Prometheus federation for long-term storage
- **Operational Burden**: Self-manage Prometheus/Grafana/Jaeger → Automated backup, monitoring of monitoring stack

---

## References

- [Observability Runbook](../operations/observability.md)
- [Observability Dashboard Catalog](../architecture/ops/observability-dashboard.md)
- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)
