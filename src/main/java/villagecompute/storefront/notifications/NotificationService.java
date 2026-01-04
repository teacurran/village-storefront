package villagecompute.storefront.notifications;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.services.FeatureToggle;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.util.LocalizationService;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;

/**
 * Service layer for consignment lifecycle notifications.
 *
 * <p>
 * Orchestrates email notification dispatch for consignor events: intake confirmation, sale notification, payout
 * summary, and expiration alerts. All notifications are:
 * <ul>
 * <li>Tenant-scoped for isolation</li>
 * <li>Gated by feature flags</li>
 * <li>Localized (EN/ES)</li>
 * <li>Instrumented with metrics and structured logs</li>
 * </ul>
 *
 * <p>
 * Usage example:
 *
 * <pre>{@code
 * NotificationContext ctx = NotificationContext.builder().tenantId(tenantId).consignorId(consignorId)
 *         .consignorName("Jane Doe").consignorEmail("jane@example.com").locale("en")
 *         .templateData(Map.of("itemCount", 5, "batchId", batchId)).build();
 * notificationService.sendIntakeConfirmation(ctx);
 * }</pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Notification service and email templates</li>
 * <li>Architecture Overview: Notifications module boundaries</li>
 * <li>ADR-001: Tenant-scoped services</li>
 * </ul>
 */
@ApplicationScoped
public class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class);

    @Inject
    FeatureToggle featureToggle;

    @Inject
    LocalizationService localizationService;

    @Inject
    Mailer mailer;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    NotificationJobQueue notificationJobQueue;

    @Inject
    @Location("email/consignment/intake-confirmation.html")
    Template intakeConfirmationTemplate;

    @Inject
    @Location("email/consignment/sale-notification.html")
    Template saleNotificationTemplate;

    @Inject
    @Location("email/consignment/payout-summary.html")
    Template payoutSummaryTemplate;

    @Inject
    @Location("email/consignment/expiration-alert.html")
    Template expirationAlertTemplate;

    /**
     * Send intake confirmation notification to consignor.
     *
     * <p>
     * Expected template data keys:
     * <ul>
     * <li>{@code itemCount}: number of items in the intake batch</li>
     * <li>{@code batchId}: UUID of the intake batch</li>
     * <li>{@code submittedAt}: submission timestamp</li>
     * </ul>
     *
     * @param context
     *            notification context with consignor and template data
     */
    public void sendIntakeConfirmation(NotificationContext context) {
        enqueueNotification(EmailTemplateType.INTAKE_CONFIRMATION, context);
    }

    /**
     * Send sale notification to consignor when an item is sold.
     *
     * <p>
     * Expected template data keys:
     * <ul>
     * <li>{@code productName}: name of the sold product</li>
     * <li>{@code salePrice}: final sale price (formatted Money)</li>
     * <li>{@code commission}: consignor commission amount (formatted Money)</li>
     * <li>{@code soldAt}: sale timestamp</li>
     * </ul>
     *
     * @param context
     *            notification context with consignor and template data
     */
    public void sendSaleNotification(NotificationContext context) {
        enqueueNotification(EmailTemplateType.SALE_NOTIFICATION, context);
    }

    /**
     * Send payout summary notification to consignor.
     *
     * <p>
     * Expected template data keys:
     * <ul>
     * <li>{@code payoutBatchId}: UUID of the payout batch</li>
     * <li>{@code totalAmount}: total payout amount (formatted Money)</li>
     * <li>{@code itemsSold}: number of items included in payout</li>
     * <li>{@code payoutDate}: date payout was processed</li>
     * <li>{@code paymentMethod}: description of payment method</li>
     * </ul>
     *
     * @param context
     *            notification context with consignor and template data
     */
    public void sendPayoutSummary(NotificationContext context) {
        enqueueNotification(EmailTemplateType.PAYOUT_SUMMARY, context);
    }

    /**
     * Send expiration alert notification to consignor.
     *
     * <p>
     * Expected template data keys:
     * <ul>
     * <li>{@code expiringItems}: list of items approaching expiration (each with name, expiryDate)</li>
     * <li>{@code expirationDays}: number of days until expiration</li>
     * </ul>
     *
     * @param context
     *            notification context with consignor and template data
     */
    public void sendExpirationAlert(NotificationContext context) {
        enqueueNotification(EmailTemplateType.EXPIRATION_ALERT, context);
    }

    /**
     * Queue notification job if feature flag is enabled. Actual email rendering/sending occurs asynchronously via
     * {@link NotificationJobProcessor}.
     */
    private void enqueueNotification(EmailTemplateType type, NotificationContext context) {
        UUID tenantId = context.getTenantId();
        UUID consignorId = context.getConsignorId();
        String featureFlagKey = type.getFeatureFlagKey();

        // Verify tenant context matches
        UUID currentTenantId = TenantContext.getCurrentTenantId();
        if (!tenantId.equals(currentTenantId)) {
            LOG.errorf("Tenant mismatch - context.tenantId=%s, TenantContext.tenantId=%s, notification=%s", tenantId,
                    currentTenantId, type);
            throw new IllegalStateException("Tenant context mismatch");
        }

        // Check feature flag
        if (!featureToggle.isEnabled(tenantId, featureFlagKey)) {
            LOG.infof(
                    "Notification skipped (feature flag disabled) - tenantId=%s, consignorId=%s, notification=%s, flag=%s",
                    tenantId, consignorId, type, featureFlagKey);
            meterRegistry.counter("notifications.skipped", "tenant_id", tenantId.toString(), "type", type.name(),
                    "reason", "feature_flag_disabled").increment();
            return;
        }

        NotificationJobPayload payload = NotificationJobPayload.create(type, context);
        notificationJobQueue.enqueue(payload);
        LOG.infof("Notification enqueued - jobId=%s, tenantId=%s, consignorId=%s, email=%s, notification=%s",
                payload.getJobId(), tenantId, consignorId, context.getConsignorEmail(), type);

        meterRegistry.counter("notifications.enqueued", "tenant_id", tenantId.toString(), "consignor_id",
                consignorId.toString(), "type", type.name(), "locale", context.getLocale()).increment();
    }

    /**
     * Process a queued notification payload. Package-private so {@link NotificationJobProcessor} and tests can trigger
     * processing deterministically.
     *
     * @param payload
     *            queued notification payload
     */
    void processJob(NotificationJobPayload payload) {
        EmailTemplateType type = payload.getTemplateType();
        UUID tenantId = payload.getTenantId();

        LOG.infof("Sending notification - jobId=%s, tenantId=%s, consignorId=%s, email=%s, notification=%s, locale=%s",
                payload.getJobId(), tenantId, payload.getConsignorId(), payload.getConsignorEmail(), type,
                payload.getLocale());

        try {
            Map<String, String> messages = localizationService.loadMessages(payload.getLocale());

            Map<String, Object> data = new HashMap<>(payload.getTemplateData());
            data.put("consignorName", payload.getConsignorName());
            data.put("msg", messages);
            data.put("locale", payload.getLocale());

            Template template = templateFor(type);
            String htmlBody = template.data(data).render();

            String subjectKey = "email.consignment." + type.name().toLowerCase() + ".subject";
            String subject = messages.getOrDefault(subjectKey, "Consignment Notification");

            mailer.send(Mail.withHtml(payload.getConsignorEmail(), subject, htmlBody));

            LOG.infof("Notification sent successfully - jobId=%s, tenantId=%s, consignorId=%s, notification=%s",
                    payload.getJobId(), tenantId, payload.getConsignorId(), type);
            meterRegistry
                    .counter("notifications.sent", "tenant_id", tenantId.toString(), "consignor_id",
                            payload.getConsignorId().toString(), "type", type.name(), "locale", payload.getLocale())
                    .increment();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to send notification - jobId=%s, tenantId=%s, consignorId=%s, notification=%s",
                    payload.getJobId(), tenantId, payload.getConsignorId(), type);
            meterRegistry.counter("notifications.failed", "tenant_id", tenantId.toString(), "type", type.name(),
                    "error", e.getClass().getSimpleName()).increment();
            throw new RuntimeException("Failed to send notification: " + type, e);
        }
    }

    private Template templateFor(EmailTemplateType type) {
        switch (type) {
            case INTAKE_CONFIRMATION :
                return intakeConfirmationTemplate;
            case SALE_NOTIFICATION :
                return saleNotificationTemplate;
            case PAYOUT_SUMMARY :
                return payoutSummaryTemplate;
            case EXPIRATION_ALERT :
                return expirationAlertTemplate;
            default :
                throw new IllegalArgumentException("Unknown notification type: " + type);
        }
    }
}
