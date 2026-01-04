# Platform Admin Console

**Task Reference:** I5.T2 - Platform admin console
**Architecture References:**
- [Blueprint Foundation](../architecture/01_Blueprint_Foundation.md#section-4-blueprint) - Section 4.0
- [Operational Architecture](../architecture/04_Operational_Architecture.md#3-8-1-authentication-authorization) - Section 3.8.1
- [Rationale: Impersonation Controls](../architecture/05_Rationale_and_Future.md#4-3-7-impersonation-control) - Section 4.3.7

---

## Overview

The Platform Admin Console provides SaaS-level governance and support tooling for platform operators. It enables:

1. **Store Directory** - Browse, search, and manage all tenant stores
2. **Impersonation Control** - Securely impersonate users for support with full audit trail
3. **Audit Log Viewer** - Query and filter platform-level administrative actions
4. **System Health Dashboards** - Monitor platform-wide metrics and performance
5. **Support Tooling** - Store suspension/reactivation, user management

All console operations are RBAC-protected and require platform admin credentials with MFA verification.

---

## Access Control

### RBAC Roles

Platform admin roles are defined in the `platform_admin_roles` table:

| Role | Permissions | Description |
|------|-------------|-------------|
| `super_admin` | All permissions | Full platform access, can modify roles |
| `support` | `impersonate`, `view_audit`, `view_health` | Support staff with impersonation rights |
| `ops` | `view_health`, `suspend_tenant`, `manage_feature_flags` | Operations team, no impersonation |
| `read_only` | `view_audit`, `view_health` | Read-only access to dashboards and logs |

### Permission Matrix

| Permission | Grants Access To |
|------------|------------------|
| `impersonate` | Start/end impersonation sessions |
| `suspend_tenant` | Suspend or reactivate stores |
| `view_audit` | Query platform command audit logs |
| `manage_feature_flags` | Toggle feature flags for cohorts |
| `view_health` | Access system health dashboards |

### MFA Requirement

All platform admin roles enforce MFA by default (`mfa_enforced = true`). MFA challenges are required before:
- Starting an impersonation session
- Suspending a store
- Modifying platform configuration

---

## Store Directory

**API Endpoint:** `GET /api/v1/platform/stores`

Browse all tenant stores with pagination and filtering.

### Features

- **Search**: Filter by subdomain or store name
- **Status Filtering**: `active`, `suspended`, `trial`, `deleted`
- **Pagination**: Configurable page size (default: 20, max: 100)
- **Metadata Display**: User counts, plan tier, custom domain status

### Example Request

```bash
curl -H "Authorization: Bearer $PLATFORM_TOKEN" \
  "https://platform.villagecompute.com/api/v1/platform/stores?status=active&search=coffee&page=0&size=20"
```

### Example Response

```json
{
  "stores": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "subdomain": "coffee-roasters",
      "name": "Village Coffee Roasters",
      "status": "active",
      "userCount": 15,
      "activeUserCount": 12,
      "createdAt": "2026-01-15T10:30:00Z",
      "lastActivityAt": "2026-02-01T14:22:00Z",
      "plan": "pro",
      "customDomainConfigured": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalCount": 1
}
```

### Store Actions

#### Suspend Store

**API:** `POST /api/v1/platform/stores/{storeId}/suspend`

Requires `suspend_tenant` permission. All suspensions log to `platform_commands` with reason.

```bash
curl -X POST \
  -H "Authorization: Bearer $PLATFORM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Violation of terms of service - fraudulent transactions"}' \
  "https://platform.villagecompute.com/api/v1/platform/stores/{storeId}/suspend"
```

**Reason Requirements:**
- Minimum 10 characters
- Should reference policy violation or support ticket
- Stored immutably in audit log

#### Reactivate Store

**API:** `POST /api/v1/platform/stores/{storeId}/reactivate`

Restores store to `active` status. Creates audit log entry.

---

## Impersonation Control

**API Endpoints:**
- `POST /api/v1/platform/impersonate` - Start session
- `DELETE /api/v1/platform/impersonate` - End session
- `GET /api/v1/platform/impersonate/current` - Get active session

### Impersonation Workflow

1. **MFA Challenge** - Platform admin authenticates with MFA
2. **Reason Required** - Provide justification (min 10 chars) and optional ticket number
3. **Session Start** - Creates entry in `impersonation_sessions` table
4. **Visual Indicator** - Red banner displays across all pages (see screenshot below)
5. **Session End** - Platform admin or timeout ends session
6. **Audit Trail** - All actions logged with impersonation context

### Starting Impersonation

```bash
curl -X POST \
  -H "Authorization: Bearer $PLATFORM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "targetTenantId": "550e8400-e29b-41d4-a716-446655440000",
    "targetUserId": null,
    "reason": "Support ticket #12345 - investigating billing discrepancy",
    "ticketNumber": "12345"
  }' \
  "https://platform.villagecompute.com/api/v1/platform/impersonate"
```

**Response:**

```json
{
  "sessionId": "660e8400-e29b-41d4-a716-446655440001",
  "platformAdminId": "770e8400-e29b-41d4-a716-446655440002",
  "platformAdminEmail": "support@villagecompute.com",
  "targetTenantId": "550e8400-e29b-41d4-a716-446655440000",
  "targetTenantName": "Village Coffee Roasters",
  "targetUserId": null,
  "targetUserEmail": null,
  "reason": "Support ticket #12345 - investigating billing discrepancy",
  "ticketNumber": "12345",
  "startedAt": "2026-02-03T15:30:00Z"
}
```

### Impersonation Modes

- **Tenant Admin Mode** (`targetUserId = null`): Act as tenant owner with full permissions
- **Specific User Mode**: Impersonate a specific user within the tenant

### Ending Impersonation

```bash
curl -X DELETE \
  -H "Authorization: Bearer $PLATFORM_TOKEN" \
  "https://platform.villagecompute.com/api/v1/platform/impersonate"
```

### Impersonation Banner (UI)

The frontend displays a prominent red banner during impersonation:

```
┌──────────────────────────────────────────────────────────────────┐
│ 👤 Impersonating: Village Coffee Roasters - Support ticket      │
│     #12345 - investigating billing discrepancy                   │
│                                            [End Impersonation] ❌ │
└──────────────────────────────────────────────────────────────────┘
```

**Visual Requirements:**
- Sticky positioning (always visible)
- High contrast (red gradient background)
- Displays reason and ticket number
- One-click session termination

### Security Considerations

- **Immutable Audit Log**: Every impersonation action writes to `platform_commands`
- **Session Timeout**: Sessions expire after 2 hours of inactivity
- **Reason Validation**: Minimum 10 characters enforced at DB constraint level
- **IP Tracking**: Source IP logged for forensic analysis
- **Regular Audits**: Weekly reviews of impersonation logs (automated report)

---

## Audit Log Viewer

**API Endpoint:** `GET /api/v1/platform/audit`

Query platform command audit logs with filtering and pagination.

### Filter Options

| Parameter | Type | Description |
|-----------|------|-------------|
| `actorId` | UUID | Platform admin who performed action |
| `action` | String | Action type (e.g., `impersonate_start`) |
| `targetType` | String | Entity type (e.g., `tenant`, `user`) |
| `startDate` | ISO-8601 | Date range start |
| `endDate` | ISO-8601 | Date range end |
| `page` | Integer | Page number (0-indexed) |
| `size` | Integer | Results per page (max 500) |

### Example Query

```bash
curl -H "Authorization: Bearer $PLATFORM_TOKEN" \
  "https://platform.villagecompute.com/api/v1/platform/audit?action=impersonate_start&startDate=2026-02-01T00:00:00Z&page=0&size=50"
```

### Example Response

```json
{
  "entries": [
    {
      "id": "880e8400-e29b-41d4-a716-446655440003",
      "actorType": "platform_admin",
      "actorId": "770e8400-e29b-41d4-a716-446655440002",
      "actorEmail": "support@villagecompute.com",
      "action": "impersonate_start",
      "targetType": "tenant",
      "targetId": "550e8400-e29b-41d4-a716-446655440000",
      "reason": "Support ticket #12345 - investigating billing discrepancy",
      "ticketNumber": "12345",
      "occurredAt": "2026-02-03T15:30:00Z",
      "ipAddress": "203.0.113.42"
    }
  ],
  "page": 0,
  "size": 50,
  "totalCount": 1
}
```

### Common Action Types

| Action | Description |
|--------|-------------|
| `impersonate_start` | Impersonation session started |
| `impersonate_stop` | Impersonation session ended |
| `suspend_store` | Store suspended |
| `reactivate_store` | Store reactivated |
| `update_feature_flag` | Feature flag toggled |
| `create_platform_admin` | New platform admin role created |

### Audit Export

For compliance, audit logs can be exported to CSV:

```bash
curl -H "Authorization: Bearer $PLATFORM_TOKEN" \
  "https://platform.villagecompute.com/api/v1/platform/audit/export?startDate=2026-01-01&endDate=2026-01-31" \
  > audit_jan_2026.csv
```

---

## System Health Dashboards

**API Endpoint:** `GET /api/v1/platform/health`

Real-time system health metrics aggregated from Prometheus and database queries.
`HealthMetricsService` pulls histogram snapshots from the in-process Prometheus registry (e.g.
`http.server.requests` timers, filesystem gauges, Vert.x pool gauges) then combines them with tenant/user counts and
impersonation session totals for a single payload.

### Metrics Included

| Metric | Description | Source |
|--------|-------------|--------|
| `tenantCount` | Total stores | Database count |
| `activeTenantCount` | Active stores | Database count |
| `totalUsers` | All users across tenants | Database count |
| `activeSessions` | Current active sessions | JWT/session tracker |
| `jobQueueDepth` | Pending background jobs | Job system |
| `failedJobs24h` | Failed jobs (last 24h) | Job system |
| `avgResponseTimeMs` | Average HTTP response time | Prometheus |
| `p95ResponseTimeMs` | 95th percentile response time | Prometheus |
| `errorRatePercent` | HTTP 5xx error rate | Prometheus |
| `diskUsagePercent` | Disk usage percentage | System metrics |
| `dbConnectionCount` | Active DB connections | Database pool |

### Example Response

```json
{
  "timestamp": "2026-02-03T16:00:00Z",
  "tenantCount": 247,
  "activeTenantCount": 239,
  "totalUsers": 3420,
  "activeSessions": 187,
  "jobQueueDepth": 12,
  "failedJobs24h": 3,
  "avgResponseTimeMs": 42.5,
  "p95ResponseTimeMs": 125.3,
  "errorRatePercent": 0.05,
  "diskUsagePercent": 45.2,
  "dbConnectionCount": 18,
  "status": "healthy"
}
```

### Health Status Scoring

| Status | Criteria |
|--------|----------|
| `healthy` | Error rate < 1%, disk < 75%, p95 response < 500ms |
| `degraded` | Error rate 1-5%, disk 75-90%, p95 response 500-1000ms |
| `critical` | Error rate > 5%, disk > 90%, or p95 response > 1000ms |

### Historical Snapshots

Health metrics are captured every 5 minutes and stored in `system_health_snapshots` for trending analysis.

```sql
SELECT snapshot_at, tenant_count, error_rate_percent, status
FROM system_health_snapshots
WHERE snapshot_at >= NOW() - INTERVAL '24 hours'
ORDER BY snapshot_at DESC;
```

---

## Database Schema

### `platform_commands` Table

Immutable audit log for all platform-level actions.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Primary key |
| `actor_type` | VARCHAR(50) | `platform_admin`, `system`, `automation` |
| `actor_id` | UUID | Platform admin user ID |
| `actor_email` | VARCHAR(255) | Denormalized for audit stability |
| `action` | VARCHAR(100) | Action type (required) |
| `target_type` | VARCHAR(50) | Entity type acted upon |
| `target_id` | UUID | Entity ID |
| `reason` | TEXT | Justification (required for impersonation) |
| `ticket_number` | VARCHAR(100) | External ticket reference |
| `impersonation_context` | JSONB | Session metadata |
| `metadata` | JSONB | Action-specific context |
| `ip_address` | INET | Source IP |
| `user_agent` | TEXT | Client info |
| `occurred_at` | TIMESTAMPTZ | Timestamp (default: NOW()) |

**Indexes:**
- `idx_platform_commands_occurred_at` (DESC)
- `idx_platform_commands_action`
- `idx_platform_commands_target`

**Constraints:**
- `chk_impersonation_requires_reason`: Impersonation actions must have reason ≥ 10 chars

### `impersonation_sessions` Table

Tracks active and historical impersonation sessions.

| Column | Type | Description |
|--------|------|-------------|
| `id` | UUID | Session ID |
| `platform_admin_id` | UUID | Admin performing impersonation |
| `platform_admin_email` | VARCHAR(255) | Admin email |
| `target_tenant_id` | UUID | Tenant being impersonated (FK) |
| `target_user_id` | UUID | User being impersonated (nullable) |
| `reason` | TEXT | Required justification |
| `ticket_number` | VARCHAR(100) | Optional ticket reference |
| `started_at` | TIMESTAMPTZ | Session start |
| `ended_at` | TIMESTAMPTZ | Session end (NULL = active) |
| `start_command_id` | UUID | FK to platform_commands |
| `end_command_id` | UUID | FK to platform_commands |
| `ip_address` | INET | Source IP |

**Indexes:**
- `idx_impersonation_sessions_active` (WHERE `ended_at IS NULL`)
- `idx_impersonation_sessions_target_tenant`

---

## Frontend Module Structure

```
src/main/webui/src/modules/platform/
├── api.ts                        # API client helpers
├── store.ts                      # Pinia store with RBAC-aware actions
├── types.ts                      # TypeScript type definitions
├── routes.ts                     # Vue router configuration
├── components/
│   └── ImpersonationBanner.vue   # Sticky impersonation indicator
├── views/
│   ├── StoreDirectoryView.vue    # Store listing/search + suspension
│   ├── AuditLogView.vue          # Audit log viewer with filters + pagination
│   ├── HealthDashboardView.vue   # Prometheus-backed health tiles
│   └── ImpersonationControlView.vue # Reason + ticket enforcement UI
├── __tests__/
│   └── PlatformConsole.spec.ts   # Vitest tests for Pinia store
└── tests/admin/PlatformConsole.spec.ts # Cypress smoke tests
```

---

## Testing

### Backend Integration Tests

```bash
# Run platform admin tests
./mvnw test -Dtest=PlatformAdminIT

# Test coverage
./mvnw test jacoco:report
# Open: target/site/jacoco/index.html
```

### Frontend UI Tests

```bash
# Run Vitest tests
cd src/main/webui
npm run test

# Run specific test suite
npm run test platform/__tests__/PlatformConsole.spec.ts
```

### Manual Testing Checklist

- [ ] Start impersonation → verify banner appears
- [ ] Perform action while impersonating → verify audit log entry
- [ ] End impersonation → verify banner disappears
- [ ] Suspend store → verify status changes in directory
- [ ] Filter audit logs by date range → verify results
- [ ] Access platform console without permission → verify 403 error
- [ ] Test impersonation with reason < 10 chars → verify validation error

---

## Monitoring & Alerts

### Prometheus Metrics

Platform console actions emit custom metrics scraped by Prometheus:

```
# Active impersonation sessions
platform_impersonation_sessions_active{admin_email="support@example.com"} 1

# Impersonation session duration (histogram)
platform_impersonation_duration_seconds_bucket{le="3600"} 45

# Store suspensions (counter)
platform_store_suspensions_total{reason="fraud"} 3
```

### Grafana Dashboards

Import pre-built dashboards from `docs/grafana/platform-console.json`:
- Impersonation activity over time
- Store suspension trends
- Platform admin action breakdown
- System health scorecards

### Alerting Rules

```yaml
# Alert if impersonation session exceeds 2 hours
- alert: LongImpersonationSession
  expr: platform_impersonation_duration_seconds > 7200
  for: 5m
  annotations:
    summary: "Impersonation session {{ $labels.session_id }} exceeded 2 hours"
```

---

## Runbook: Investigating Suspicious Impersonation

1. **Query Audit Logs**
   ```bash
   curl "https://platform.example.com/api/v1/platform/audit?action=impersonate_start&startDate=2026-02-01"
   ```

2. **Identify Session**
   ```sql
   SELECT * FROM impersonation_sessions
   WHERE platform_admin_email = 'suspicious@example.com'
   ORDER BY started_at DESC LIMIT 10;
   ```

3. **Review Platform Commands**
   ```sql
   SELECT * FROM platform_commands
   WHERE actor_id = '<admin_id>'
     AND occurred_at BETWEEN '<session_start>' AND '<session_end>'
   ORDER BY occurred_at;
   ```

4. **Correlate with Application Logs**
   Search logs for `correlation_id` or `session_id` to see actions performed during impersonation.

5. **Export Evidence**
   ```bash
   psql -c "COPY (SELECT * FROM platform_commands WHERE actor_id='<admin_id>') TO STDOUT CSV HEADER" > evidence.csv
   ```

---

## FAQ

**Q: Can impersonation sessions be forcefully terminated?**
A: Yes, super admins can end any session via direct database update:
```sql
UPDATE impersonation_sessions SET ended_at = NOW() WHERE id = '<session_id>';
```

**Q: How long are audit logs retained?**
A: Audit logs (`platform_commands`) are retained indefinitely. For compliance, archive annually to cold storage.

**Q: Can platform admins impersonate other platform admins?**
A: No. Impersonation only targets tenant users, not other platform admins.

**Q: What happens if a store is suspended mid-transaction?**
A: Active sessions are invalidated immediately. In-flight API requests return 403 errors.

---

## Related Documentation

- [ADR-001: Tenancy Strategy](../adr/ADR-001-tenancy.md)
- [ADR-004: Consignment Payouts](../adr/ADR-004-consignment-payouts.md) (platform_commands pattern)
- [Architecture: Observability Fabric](../architecture/04_Operational_Architecture.md#3-7-observability)
- [Architecture: RBAC](../architecture/04_Operational_Architecture.md#3-8-1-authentication-authorization)

---

**Last Updated:** 2026-02-03
**Maintained By:** Platform Operations Team
**Escalation:** platform-ops@villagecompute.com
