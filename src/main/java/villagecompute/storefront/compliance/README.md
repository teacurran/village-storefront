# Compliance Module

## Overview

The compliance module automates GDPR, CCPA, and other privacy regulation workflows for the Village Storefront platform. It provides:

- **Privacy Export Workflow**: Generate comprehensive customer data exports (right to access)
- **Privacy Delete Workflow**: Two-phase soft-delete and purge process (right to erasure)
- **Consent Management**: Track marketing consent across channels with full audit trail
- **Audit Logging**: All compliance actions recorded in `PlatformCommand` for traceability

**References:**
- Task: I5.T6 - Compliance automation
- Documentation: `docs/compliance/privacy.md`
- Operations: `docs/operations/archive_runbook.md`

---

## Package Structure

```
villagecompute/storefront/compliance/
├── ComplianceService.java              # Main orchestration service
├── api/
│   ├── rest/
│   │   └── ComplianceResource.java     # REST endpoints for Platform console
│   └── types/
│       ├── PrivacyRequestDto.java
│       ├── SubmitPrivacyRequestRequest.java
│       ├── ApprovePrivacyRequestRequest.java
│       ├── MarketingConsentDto.java
│       └── RecordConsentRequest.java
├── data/
│   ├── models/
│   │   ├── PrivacyRequest.java         # Export/delete request entity
│   │   └── MarketingConsent.java       # Consent tracking entity
│   └── repositories/
│       ├── PrivacyRequestRepository.java
│       └── MarketingConsentRepository.java
└── jobs/
    ├── PrivacyExportJobPayload.java    # Background job for exports
    └── PrivacyDeleteJobPayload.java    # Background job for deletions
```

---

## Quick Start

### Submit Privacy Export Request

```java
@Inject
ComplianceService complianceService;

// Submit request (requires manual approval)
UUID requestId = complianceService.submitExportRequest(
    "admin@platform.com",           // requester
    "customer@example.com",          // data subject
    "GDPR Article 15 access request", // reason
    "TICKET-12345"                   // support ticket
);

// Approve request (enqueues background job)
UUID jobId = complianceService.approveExportRequest(
    requestId,
    "compliance-officer@platform.com",
    "Identity verified via ID.me"
);

// Job processes asynchronously
// Customer receives signed download URL via support ticket
```

### Submit Privacy Delete Request

```java
// Submit request
UUID requestId = complianceService.submitDeleteRequest(
    "admin@platform.com",
    "customer@example.com",
    "GDPR Article 17 right to erasure",
    "TICKET-67890"
);

// Approve request (triggers soft-delete)
UUID jobId = complianceService.approveDeleteRequest(
    requestId,
    "compliance-officer@platform.com",
    "Verified no legal holds"
);

// Soft-delete executes immediately
// Purge scheduled for 90 days later (configurable)
```

### Record Marketing Consent

```java
@Inject
MarketingConsentRepository consentRepo;

MarketingConsent consent = new MarketingConsent();
consent.tenant = tenant;
consent.customer = customer;
consent.channel = "email";              // email, sms, push, phone
consent.consented = true;               // opt-in
consent.consentSource = "web_form";     // web_form, api, import, pos
consent.consentMethod = "opt_in";       // opt_in, opt_out, implied
consent.ipAddress = "192.168.1.1";
consent.userAgent = "Mozilla/5.0...";
consent.notes = "Subscribed via homepage form";

consentRepo.persist(consent);
```

### Query Consent Status

```java
// Get latest consent for channel
MarketingConsent latest = consentRepo.findLatestByCustomerAndChannel(
    customerId,
    "email"
);

boolean canSendEmail = latest != null && latest.consented;

// Get full consent timeline
List<MarketingConsent> timeline = consentRepo.findByCustomer(customerId);
```

---

## REST API Endpoints

All endpoints under `/api/v1/platform/compliance/*` require Platform Admin RBAC.

### Privacy Requests

| Method | Path | Description |
|--------|------|-------------|
| POST | `/privacy-requests/export` | Submit export request |
| POST | `/privacy-requests/delete` | Submit delete request |
| POST | `/privacy-requests/{id}/approve` | Approve request |
| GET | `/privacy-requests` | List requests (filter by status/type) |
| GET | `/privacy-requests/{id}` | Get request details |

### Marketing Consent

| Method | Path | Description |
|--------|------|-------------|
| POST | `/marketing-consents` | Record consent action |
| GET | `/marketing-consents/customer/{id}` | Get consent timeline |

### Monitoring

| Method | Path | Description |
|--------|------|-------------|
| GET | `/metrics` | Queue depths, request counts |

---

## Background Jobs

### Export Job Flow

1. Request submitted → `PENDING_REVIEW`
2. Admin approves → `APPROVED`, job enqueued (HIGH priority)
3. Job handler:
   - Queries customer data across all tables
   - Generates JSONL (structured) + CSV (summary)
   - Zips files with SHA-256 hash in filename
   - Uploads to R2 via `ReportStorageClient`
   - Generates 72-hour signed URL
4. Request updated → `COMPLETED`, URL stored

### Delete Job Flow

1. Request submitted → `PENDING_REVIEW`
2. Admin approves → `APPROVED`, soft-delete job enqueued
3. Soft-delete handler:
   - Marks records with `deleted_at` timestamp
   - Creates `PlatformCommand` audit entry
   - Schedules purge job for +90 days
4. Request updated → `COMPLETED`
5. Purge job (90 days later):
   - Permanently deletes records (`DELETE FROM ...`)
   - Creates second `PlatformCommand` audit entry

---

## Configuration

```properties
# application.properties

# Delete retention period (days between soft-delete and purge)
compliance.delete.retention_days=90

# Job queue capacities
jobs.queue.capacity.high=5000

# Retry policies
jobs.retry.max_attempts.high=3
```

---

## Testing

### Integration Tests

Run full compliance workflow tests:

```bash
./mvnw test -Dtest=ComplianceIT
```

**Test Coverage:**
- Export request submission and approval
- Delete request submission and approval
- Background job processing (export + delete)
- Consent recording and retrieval
- Queue metrics and monitoring
- Signed URL generation and integrity

### Manual Testing

1. Start dev server: `./mvnw quarkus:dev`
2. Submit request via cURL or Postman
3. Approve via Platform admin console (http://localhost:8080/admin)
4. Monitor job processing:
   ```bash
   curl http://localhost:8080/api/v1/platform/compliance/metrics
   ```
5. Download export via signed URL

---

## Monitoring & Observability

### Prometheus Metrics

- `compliance_export_requested` - Export requests submitted
- `compliance_export_approved` - Export requests approved
- `compliance_export_started` - Export jobs started
- `compliance_export_failed` - Export jobs failed
- `compliance_export_duration` - Export job duration (histogram)
- `compliance_export_queue_depth` - Jobs queued for export
- `compliance_delete_*` - Corresponding delete metrics
- `compliance_delete_soft_deleted` - Successful soft-deletes
- `compliance_delete_purged` - Successful purges

### Audit Trail

All compliance actions logged in `platform_commands` table:
- `approve_privacy_export`
- `approve_privacy_delete`
- `privacy_soft_delete`
- `privacy_purge`

Query via `/api/v1/platform/audit` or direct SQL.

---

## Security & Compliance

### Tenant Isolation

All queries filtered by `TenantContext` to prevent cross-tenant data leakage.

### Data Protection

- Job payloads store **SHA-256 hash** of customer email, never plaintext
- Exports stored in R2 with 72-hour signed URLs
- Audit logs retained indefinitely for regulatory compliance

### RBAC

- **Submit/Approve Requests**: `PERMISSION_MANAGE_STORES`
- **View Audit Logs**: `PERMISSION_VIEW_AUDIT`
- **Record Consent**: Any authenticated store user

---

## Future Enhancements

- [ ] Automated identity verification (ID.me integration)
- [ ] Self-service customer portal for requests
- [ ] Partial exports (select data categories)
- [ ] Consent preference center UI
- [ ] Right to portability (machine-readable formats)
- [ ] Data minimization recommendations

---

## Support

**Documentation:**
- Privacy workflows: `docs/compliance/privacy.md`
- Operational runbook: `docs/operations/archive_runbook.md`

**Incident Response:**
- PagerDuty: `compliance-critical` escalation policy
- Slack: `#compliance-ops` channel

**Code Owners:**
- Platform Operations Team
- Compliance Engineering
