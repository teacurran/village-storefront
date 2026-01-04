# Notifications Playbook

## Overview

The notification system provides automated email notifications for consignment lifecycle events. All notifications are tenant-scoped, feature-flagged, localized (EN/ES), and instrumented with metrics and structured logging.

**References:**
- Task I3.T5: Notification service implementation
- Architecture Overview: Notifications module boundaries

---

## Architecture

### Components

```
villagecompute.storefront.notifications/
├── EmailTemplateType.java         # Enum of notification types with template paths and feature flags
├── NotificationContext.java       # Value object containing tenant, consignor, locale, and template data
├── NotificationJobPayload.java    # Snapshot of NotificationContext used for async processing
├── NotificationJobQueue.java      # In-memory queue with Micrometer gauge + enqueued counter
├── NotificationJobProcessor.java  # Scheduled worker that drains the queue
└── NotificationService.java       # Public API that enqueues jobs + renders/sends during processing
```

### Template Structure

```
src/main/resources/templates/email/
├── base-email.html                # Base layout (currently unused, templates are standalone)
└── consignment/
    ├── intake-confirmation.html   # Intake batch received
    ├── sale-notification.html     # Item sold
    ├── payout-summary.html        # Payout processed
    └── expiration-alert.html      # Items approaching expiration
```

### Localization

All notification strings are defined in:
- `src/main/resources/messages/messages.properties` (English)
- `src/main/resources/messages/messages_es.properties` (Spanish)

The `LocalizationService` loads message bundles with automatic fallback to English for missing translations.

### Background Delivery Flow

1. API/Domain code calls `NotificationService#send*` which validates tenant context, applies feature flags, and enqueues a `NotificationJobPayload`.
2. `NotificationJobQueue` persists the payload in-memory and updates the `notifications.queue.depth` gauge so operations can observe backlog.
3. `NotificationJobProcessor` runs every 5 seconds (configurable via `notifications.queue.dispatch-interval`) or on-demand in tests, draining the queue and invoking `NotificationService.processJob(...)`.
4. During processing the service renders the Qute template, sends the email through Quarkus Mailer, and emits `notifications.sent`/`notifications.failed` counters (tagged with tenant + consignor IDs); enqueue skips emit `notifications.skipped`.

---

## Feature Flags

Each notification type is gated by a tenant-specific or global feature flag:

| Notification Type       | Feature Flag Key                  | Template Path                                  |
|-------------------------|-----------------------------------|------------------------------------------------|
| Intake Confirmation     | `notifications.consignor.intake`  | `email/consignment/intake-confirmation.html`   |
| Sale Notification       | `notifications.consignor.sale`    | `email/consignment/sale-notification.html`     |
| Payout Summary          | `notifications.consignor.payout`  | `email/consignment/payout-summary.html`        |
| Expiration Alert        | `notifications.consignor.expiration` | `email/consignment/expiration-alert.html`   |

### Enabling Notifications

To enable a notification type for all tenants (global):

```sql
INSERT INTO feature_flags (tenant_id, flag_key, enabled, created_at, updated_at)
VALUES (NULL, 'notifications.consignor.sale', true, NOW(), NOW());
```

To enable for a specific tenant:

```sql
INSERT INTO feature_flags (tenant_id, flag_key, enabled, created_at, updated_at)
VALUES ('tenant-uuid-here', 'notifications.consignor.intake', true, NOW(), NOW());
```

To disable notifications globally or per-tenant:

```sql
UPDATE feature_flags
SET enabled = false, updated_at = NOW()
WHERE flag_key = 'notifications.consignor.payout'
AND tenant_id IS NULL;  -- Remove WHERE clause for tenant-specific
```

After changing flags, invalidate the cache:

```java
featureToggle.invalidateAll();
```

---

## Usage

### Sending Notifications

```java
@Inject
NotificationService notificationService;

// Example: Send intake confirmation
NotificationContext context = NotificationContext.builder()
    .tenantId(tenantId)
    .consignorId(consignorId)
    .consignorName("Jane Doe")
    .consignorEmail("jane@example.com")
    .locale("en")  // or "es"
    .templateData(Map.of(
        "batchId", batchId.toString(),
        "itemCount", 5,
        "submittedAt", "2026-01-03 10:30 AM"
    ))
    .build();

notificationService.sendIntakeConfirmation(context);
```

### Template Data Requirements

Each notification type expects specific template data keys:

#### Intake Confirmation
- `batchId` (String/UUID): Intake batch identifier
- `itemCount` (int): Number of items submitted
- `submittedAt` (String): Submission timestamp

#### Sale Notification
- `productName` (String): Name of sold product
- `salePrice` (String): Formatted sale price (e.g., "$1,200.00")
- `commission` (String): Formatted commission amount
- `soldAt` (String): Sale timestamp

#### Payout Summary
- `payoutBatchId` (String/UUID): Payout batch identifier
- `totalAmount` (String): Formatted total payout amount
- `itemsSold` (int): Number of items included in payout
- `payoutDate` (String): Date payout was processed
- `paymentMethod` (String): Description of payment method

#### Expiration Alert
- `expiringItems` (List<Map<String, String>>): List of items with `name` and `expiryDate` keys
- `expirationDays` (int): Days remaining until expiration

---

## Throttling Strategy

Currently, notifications are sent synchronously. For production deployments, consider:

1. **Background Job Queue**: Enqueue notifications via DelayedJob/Quarkus Scheduler to prevent blocking request threads
2. **Rate Limiting**: Implement per-consignor rate limits (e.g., max 10 emails/hour) using Caffeine cache
3. **Batching**: Group sale notifications by consignor and send daily summaries instead of per-sale emails
4. **Bounce Tracking**: Integrate with Quarkus Mailer bounce handling to suppress notifications to invalid addresses

### Recommended Implementation

```java
// Background job approach (future enhancement)
@Scheduled(every = "5m")
void processNotificationQueue() {
    List<NotificationJob> jobs = notificationQueue.dequeueReady(100);
    for (NotificationJob job : jobs) {
        try {
            notificationService.send(job.type, job.context);
            job.markCompleted();
        } catch (Exception e) {
            job.markFailed();
            job.scheduleRetry();
        }
    }
}
```

---

## Environment Domain Filtering

Email sending behavior varies by environment:

### Development (`quarkus.profile=dev`)
- Emails sent to **Mailhog** on `localhost:1025`
- Access web UI at `http://localhost:8025`
- All emails captured for inspection, none delivered externally

### Staging (`quarkus.profile=staging`)
- Emails sent to configured SMTP server
- **Domain whitelist**: Only send to `@villagecompute.com` and `@example.com` domains
- Log warnings for filtered addresses
- Enable via config: `notifications.domain-filter.enabled=true`

### Production (`quarkus.profile=prod`)
- Emails sent to production SMTP server (AWS SES, SendGrid, etc.)
- No domain filtering
- Full bounce tracking and suppression list active

### Configuration

`application.properties`:

```properties
# Development - Mailhog
%dev.quarkus.mailer.host=localhost
%dev.quarkus.mailer.port=1025
%dev.quarkus.mailer.mock=false

# Staging - Domain filtering
%staging.quarkus.mailer.host=smtp.staging.example.com
%staging.quarkus.mailer.port=587
%staging.quarkus.mailer.username=notifications@villagecompute.com
%staging.quarkus.mailer.password=${MAILER_PASSWORD}
%staging.quarkus.mailer.tls=true
%staging.notifications.domain-filter.enabled=true
%staging.notifications.domain-filter.allowed=villagecompute.com,example.com

# Production - AWS SES
%prod.quarkus.mailer.host=email-smtp.us-east-1.amazonaws.com
%prod.quarkus.mailer.port=587
%prod.quarkus.mailer.username=${AWS_SES_USERNAME}
%prod.quarkus.mailer.password=${AWS_SES_PASSWORD}
%prod.quarkus.mailer.tls=true
%prod.notifications.domain-filter.enabled=false
```

---

## Testing

### Unit Tests

Run the test suite:

```bash
./mvnw test -Dtest=NotificationServiceTest
```

Coverage target: 80% (enforced by SonarCloud)

### Manual Testing with Mailhog

1. Start Mailhog (Docker):
   ```bash
   docker run -d -p 1025:1025 -p 8025:8025 mailhog/mailhog
   ```

2. Start Quarkus in dev mode:
   ```bash
   ./mvnw quarkus:dev
   ```

3. Trigger notification via REST endpoint or test class:
   ```bash
   curl -X POST http://localhost:8080/api/test/notifications/intake \
     -H "Content-Type: application/json" \
     -d '{
       "consignorId": "uuid-here",
       "consignorEmail": "test@example.com",
       "locale": "en",
       "batchId": "batch-uuid",
       "itemCount": 5
     }'
   ```

4. View email in Mailhog UI: `http://localhost:8025`

### Template Snapshot Tests

Template rendering is tested with sample data to ensure:
- All placeholders resolve correctly
- Locale switching works (EN/ES)
- HTML structure is valid
- No XSS vulnerabilities from unescaped data

---

## Observability

### Metrics

All notifications emit Micrometer metrics:

```
notifications.sent{tenant_id, consignor_id, type, locale}
notifications.failed{tenant_id, type, error}
notifications.skipped{tenant_id, type, reason}
notifications.enqueued{tenant_id, consignor_id, type, locale}
notifications.queue.depth (gauge)
```

Query examples (Prometheus):

```promql
# Notification volume by type
rate(notifications_sent_total[5m])

# Failure rate
rate(notifications_failed_total[5m]) / rate(notifications_sent_total[5m])

# Feature flag skip rate
notifications_skipped_total{reason="feature_flag_disabled"}

# Queue depth
notifications_queue_depth
```

### Structured Logging

All log entries include:
- `tenantId`: Tenant UUID
- `consignorId`: Consignor UUID
- `notification`: Notification type (INTAKE_CONFIRMATION, SALE_NOTIFICATION, etc.)
- `locale`: Email locale
- `email`: Recipient email (for sent confirmations)

Example log entry:

```
INFO  Sending notification - tenantId=abc123, consignorId=def456, email=jane@example.com, notification=SALE_NOTIFICATION, locale=en
```

---

## Security Considerations

1. **Tenant Isolation**: All notifications validate that `TenantContext.getCurrentTenantId()` matches `NotificationContext.tenantId`
2. **Email Validation**: Consignor email addresses are validated at consignor creation (not in notification layer)
3. **XSS Prevention**: Qute templates auto-escape all variables; use `{variable|raw}` only for pre-sanitized HTML
4. **PII Logging**: Avoid logging full email bodies; log only metadata (recipient, type, locale)
5. **Rate Limiting**: Implement per-consignor throttling to prevent abuse

---

## Troubleshooting

### Notification Not Sent

1. **Check feature flag**: Verify flag is enabled for tenant or globally
   ```bash
   psql -c "SELECT * FROM feature_flags WHERE flag_key LIKE 'notifications.%';"
   ```

2. **Check logs**: Search for `"Notification skipped"` messages
   ```bash
   grep "Notification skipped" logs/application.log
   ```

3. **Verify tenant context**: Ensure `TenantContext.getCurrentTenantId()` is set

4. **Check Mailhog/SMTP logs**: Verify Mailer is configured correctly

### Template Rendering Errors

1. **Missing i18n key**: Check `messages.properties` and `messages_es.properties` for required keys
2. **Missing template data**: Ensure all required keys are present in `templateData` map
3. **Qute syntax error**: Run `./mvnw quarkus:dev` and check startup logs for template compilation errors

### Locale Not Applied

1. **Verify locale string**: Use `"en"` or `"es"` (case-sensitive)
2. **Check fallback**: Missing ES translations fall back to EN automatically
3. **Clear cache**: Restart Quarkus to reload message bundles

---

## Future Enhancements

1. **SMS Notifications**: Integrate Twilio for critical alerts (payouts, expirations)
2. **Push Notifications**: Web push for vendor portal users
3. **Notification Preferences**: Allow consignors to opt out of specific notification types
4. **Template Editor**: Admin UI for customizing email templates per tenant
5. **A/B Testing**: Feature flag variants for testing subject lines and layouts
6. **Rich Analytics**: Track open rates, click-through rates, conversion metrics

---

## References

- **Task**: I3.T5 (Notification service and email templates)
- **Architecture**: `docs/architecture_overview.md` (Notifications module boundaries)
- **ADR**: ADR-001 (Tenant-scoped services and data isolation)
- **Quarkus Mailer**: https://quarkus.io/guides/mailer
- **Qute Templates**: https://quarkus.io/guides/qute
