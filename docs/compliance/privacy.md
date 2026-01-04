# Privacy Compliance Workflows

## Overview

The Village Storefront platform provides automated compliance workflows for GDPR, CCPA, and other privacy regulations. These workflows enable merchants to respond to data subject requests for export (right to access) and deletion (right to erasure) while maintaining comprehensive audit trails.

**References:**
- Task: I5.T6 - Compliance automation
- Architecture: 01_Blueprint_Foundation.md Section 5 (Data Governance)
- Architecture: 04_Operational_Architecture.md Section 3.15 (Compliance personas)

---

## Privacy Export Workflow

### Purpose

Generate comprehensive data exports for customers exercising their right to access personal data.

### Process Flow

1. **Request Submission**
   - Platform admin or support staff submits export request via `/api/v1/platform/compliance/privacy-requests/export`
   - Required fields: requester email, subject email, reason, ticket number
   - Request enters `PENDING_REVIEW` status
   - Request ID returned for tracking

2. **Manual Review**
   - Compliance team reviews request for legitimacy
   - Verifies identity of data subject
   - Checks for any legal holds or blocking conditions
   - Documents decision in approval notes

3. **Approval & Queueing**
   - Approver calls `/api/v1/platform/compliance/privacy-requests/{requestId}/approve`
   - System creates `PlatformCommand` audit entry linking approver, reason, and ticket
   - Export job enqueued with HIGH priority
   - Request transitions to `APPROVED` status

4. **Background Export Generation**
   - Job handler retrieves customer data across all tables:
     - Customer profile (name, email, addresses)
     - Order history
     - Marketing consent timeline
     - Payment methods (masked card details)
     - Support tickets and communications
   - Data exported in multiple formats:
     - **customer_data.jsonl**: Line-delimited JSON for structured data
     - **marketing_consents.csv**: Consent timeline with timestamps and sources
     - **export_summary.csv**: Metadata (tenant, dates, requester)
   - All files bundled into ZIP archive

5. **Storage & Signed URL Generation**
   - ZIP file uploaded to Cloudflare R2 via `ReportStorageClient`
   - Object key format: `{tenant_id}/privacy-exports/{sanitized_email}_{hash}.zip`
   - SHA-256 hash computed and included in filename for integrity verification
   - Signed download URL generated with 72-hour expiry
   - URL stored in `PrivacyRequest.resultUrl`

6. **Notification**
   - Request status updated to `COMPLETED`
   - Platform admin dashboard shows download link
   - Support staff notifies customer via secure channel

### Data Included in Exports

- **Customer Profile**: Name, email, phone, created date
- **Addresses**: All shipping/billing addresses
- **Orders**: Order history with items, amounts, dates
- **Payments**: Masked payment methods (last 4 digits only)
- **Marketing Consents**: Full consent timeline per channel (email, SMS, push, phone)
- **Support Tickets**: Ticket IDs and subject lines (not full content to protect other parties)
- **Loyalty Points**: Points balance and transaction history
- **Store Credits**: Balance and transaction history
- **Gift Cards**: Card numbers and balances (for cards owned by subject)

### Retention & Cleanup

- Exported ZIP files retained in R2 for **90 days**
- Signed URLs expire after **72 hours** (new URL can be regenerated if needed)
- After 90 days, files automatically purged via lifecycle policy
- `PrivacyRequest` records retained indefinitely for audit trail

---

## Privacy Delete Workflow

### Purpose

Execute data deletion requests (right to erasure) in compliance with GDPR Article 17 and CCPA deletion requirements.

### Process Flow

1. **Request Submission**
   - Similar to export workflow
   - Submitted via `/api/v1/platform/compliance/privacy-requests/delete`
   - Request enters `PENDING_REVIEW` status

2. **Manual Review**
   - **CRITICAL**: Deletion is irreversible after purge phase
   - Compliance team verifies:
     - Identity of data subject
     - No legal hold or regulatory retention requirements
     - No active orders or disputes requiring data retention
     - Documented reason satisfies regulatory basis (e.g., consent withdrawn, no legitimate interest)
   - Decision documented in approval notes with ticket reference

3. **Approval & Soft-Delete**
   - Approver calls `/api/v1/platform/compliance/privacy-requests/{requestId}/approve`
   - Soft-delete job enqueued (HIGH priority)
   - Job handler marks records with `deleted_at` timestamp:
     - Customer profile
     - Addresses
     - Payment methods
     - Marketing consents
   - Records remain in database but excluded from application queries
   - `PlatformCommand` audit entry created for traceability

4. **Retention Period**
   - Soft-deleted records retained for **90 days** (configurable via `compliance.delete.retention_days`)
   - Allows recovery if deletion submitted in error
   - Supports regulatory retention requirements (e.g., tax/accounting holds)

5. **Purge Phase**
   - After retention period, purge job automatically scheduled
   - Purge job permanently deletes records:
     - `DELETE FROM customers WHERE id = ?`
     - Cascade deletes for addresses, consents, etc.
   - Second `PlatformCommand` audit entry created
   - Request marked `COMPLETED`

### Data Deletion Scope

**Deleted:**
- Customer profile
- Addresses
- Payment methods (tokenized references only; actual card data never stored)
- Marketing consents
- Loyalty points
- Store credits
- Gift cards issued to customer

**Retained (Legal/Regulatory Requirements):**
- Order records (anonymized): Required for tax/accounting (7 years minimum)
  - Customer name replaced with "Deleted User {hash}"
  - Email/phone removed
  - Shipping address retained (tax jurisdiction required)
- Payment transaction logs: Required for PCI compliance and fraud prevention
- Audit logs: Platform commands and impersonation records retained indefinitely

### Manual Override

For special cases (legal hold, dispute resolution), platform admins can:
- Reject deletion request with documented reason
- Extend retention period before purge
- Manually trigger purge early (requires secondary approval)

---

## Marketing Consent Management

### Purpose

Track and manage customer consent for marketing communications across channels, satisfying GDPR consent requirements and CAN-SPAM/TCPA compliance.

### Consent Channels

- **email**: Email marketing
- **sms**: SMS/text marketing
- **push**: Mobile push notifications
- **phone**: Telemarketing calls

### Recording Consent

**Endpoint:** `POST /api/v1/platform/compliance/marketing-consents`

**Required Fields:**
- `customerId`: Customer UUID
- `channel`: Channel name
- `consented`: Boolean (true = opt-in, false = opt-out)
- `consentSource`: Origin (web_form, api, import, pos, customer_service)
- `consentMethod`: Method (opt_in, opt_out, implied)

**Optional Fields:**
- `ipAddress`: IP address for audit trail
- `userAgent`: Browser/device info
- `notes`: Free-text context (e.g., "Customer called to unsubscribe")

### Consent Timeline

Each consent action creates a new record (immutable log):
- Initial subscription: `consented=true, source=web_form, method=opt_in`
- Later unsubscribe: `consented=false, source=customer_service, method=opt_out`
- Re-subscribe: `consented=true, source=api, method=opt_in`

To determine current status, query for latest record per channel:
```java
MarketingConsent latest = consentRepo.findLatestByCustomerAndChannel(customerId, "email");
boolean canSendEmail = latest != null && latest.consented;
```

### Consent in Privacy Exports

Full consent timeline included in `marketing_consents.csv` within export ZIP, showing:
- All consent changes with timestamps
- Source and method for each action
- IP addresses (if captured)
- Demonstrates compliance with "freely given, specific, informed" requirements

---

## Audit Trail & Compliance Reporting

### Platform Commands

Every compliance action recorded in `platform_commands` table:
- Export approval: `action=approve_privacy_export`
- Delete approval: `action=approve_privacy_delete`
- Soft-delete execution: `action=privacy_soft_delete`
- Purge execution: `action=privacy_purge`

Each record includes:
- Actor (email of platform admin or system)
- Target (privacy request ID, customer ID)
- Reason (approval notes, ticket number)
- Metadata (tenant ID, subject email hash)
- IP address and user agent
- Timestamp

### Audit Log Queries

**Via API:** `GET /api/v1/platform/audit`
- Filter by action: `?action=approve_privacy_export`
- Filter by date range: `?startDate=2025-01-01T00:00:00Z&endDate=2025-12-31T23:59:59Z`
- Filter by actor: `?actorId={uuid}`

**Direct SQL (for compliance reports):**
```sql
SELECT
  pc.action,
  pc.actor_email,
  pc.target_id,
  pc.reason,
  pc.occurred_at,
  pr.subject_email
FROM platform_commands pc
JOIN privacy_requests pr ON pc.target_id = pr.id
WHERE pc.action LIKE 'privacy_%'
  AND pc.occurred_at >= '2025-01-01'
ORDER BY pc.occurred_at DESC;
```

### Compliance Metrics

**Endpoint:** `GET /api/v1/platform/compliance/metrics`

Returns:
- `pendingExports`: Requests awaiting approval
- `pendingDeletes`: Deletion requests awaiting approval
- `inProgress`: Jobs currently executing
- `completed`: Successfully completed requests
- `failed`: Failed jobs requiring investigation
- `exportQueueDepth`: Jobs queued for export processing
- `deleteQueueDepth`: Jobs queued for deletion processing

---

## Security & Access Control

### RBAC Requirements

**Privacy Request Submission:**
- Role: `platform_admin`
- Permission: `PERMISSION_MANAGE_STORES`

**Privacy Request Approval:**
- Role: `platform_admin`
- Permission: `PERMISSION_MANAGE_STORES`

**Audit Log Viewing:**
- Role: `platform_admin`
- Permission: `PERMISSION_VIEW_AUDIT`

**Consent Management:**
- Any authenticated store user can record consent for their tenant's customers

### Data Protection

- **Hashed Identifiers**: Job payloads store SHA-256 hash of customer email, never plaintext
- **Tenant Isolation**: All queries filtered by `TenantContext` to prevent cross-tenant data access
- **Signed URLs**: Export downloads require time-limited signed URL (72hr expiry)
- **Audit Logging**: All approvals/rejections logged with actor, reason, and ticket reference

---

## Error Handling & Manual Intervention

### Common Failures

**Export Job Fails:**
- Check logs for exception details
- Common causes: Missing customer data, storage upload failure, invalid email format
- Recovery: Re-approve request to retry export generation

**Delete Job Fails:**
- Check for foreign key constraints (orders, transactions)
- May require manual intervention to anonymize related records
- Recovery: Fix constraint issues, then re-approve request

**Queue Capacity Reached:**
- Increase `jobs.queue.capacity.high` in application.properties
- Scale job processors horizontally
- Monitor `compliance_export_queue_depth` and `compliance_delete_queue_depth` metrics

### Manual Approval Override

For urgent/special cases:
1. Direct database update: `UPDATE privacy_requests SET status = 'APPROVED' WHERE id = ?`
2. Manually enqueue job via service call in Quarkus dev console
3. Document override in `approval_notes` field

---

## Monitoring & Alerting

### Key Metrics (Prometheus)

- `compliance_export_requested`: Counter of export requests submitted
- `compliance_export_approved`: Counter of approved exports
- `compliance_export_started`: Counter of export jobs started
- `compliance_export_failed`: Counter of failed export jobs
- `compliance_export_duration`: Histogram of export job duration
- `compliance_export_queue_depth`: Gauge of pending export jobs
- `compliance_delete_requested`: Counter of delete requests submitted
- `compliance_delete_approved`: Counter of approved deletions
- `compliance_delete_started`: Counter of delete jobs started (soft_delete/purge phases)
- `compliance_delete_failed`: Counter of failed delete jobs
- `compliance_delete_duration`: Histogram of delete job duration
- `compliance_delete_queue_depth`: Gauge of pending delete jobs
- `compliance_delete_soft_deleted`: Counter of successful soft-deletes
- `compliance_delete_purged`: Counter of successful purges

### Recommended Alerts

**High Queue Depth:**
```promql
compliance_export_queue_depth > 100 OR compliance_delete_queue_depth > 50
```
- **Action:** Scale job processors, investigate slow exports

**Export Failures:**
```promql
rate(compliance_export_failed[5m]) > 0.1
```
- **Action:** Check logs, investigate storage/customer data issues

**Delete Failures:**
```promql
rate(compliance_delete_failed[5m]) > 0
```
- **Action:** CRITICAL - investigate immediately, may indicate data integrity issues

**Pending Approval Backlog:**
```sql
SELECT COUNT(*) FROM privacy_requests WHERE status = 'PENDING_REVIEW' AND created_at < NOW() - INTERVAL '7 days';
```
- **Action:** Notify compliance team, review SLA adherence

---

## SLA & Response Times

### Regulatory Requirements

- **GDPR**: Respond to access requests within **30 days** (extendable to 90 days with justification)
- **CCPA**: Respond to deletion requests within **45 days**

### Platform Targets

- **Export Request Approval:** Within **3 business days**
- **Export Generation:** Within **24 hours** of approval
- **Delete Request Approval:** Within **5 business days** (allows for thorough legal review)
- **Soft-Delete Execution:** Within **24 hours** of approval
- **Purge Execution:** **90 days** after soft-delete (configurable)

---

## Future Enhancements

- **Automated Identity Verification:** Integration with ID.me or similar services to reduce manual review burden
- **Partial Exports:** Allow customers to request specific data categories (e.g., only orders, not payment history)
- **Self-Service Portal:** Customer-facing UI for submitting export/delete requests without support intervention
- **Consent Preference Center:** Granular opt-in/opt-out UI for marketing channels embedded in storefront
- **Right to Portability:** Generate exports in standard machine-readable formats (JSON-LD, CSV) per GDPR Article 20
- **Data Minimization Reports:** Automated recommendations for purging stale customer data
