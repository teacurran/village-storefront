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
 * Service layer for consignment and order lifecycle notifications.
 *
 * <p>
 * Orchestrates email notification dispatch for consignor and customer events. All notifications are:
 * <ul>
 * <li>Tenant-scoped for isolation</li>
 * <li>Gated by feature flags</li>
 * <li>Localized (EN/ES)</li>
 * <li>Domain-filtered in staging (prevents sending to real customers)</li>
 * <li>Instrumented with metrics and structured logs</li>
 * </ul>
 *
 * <p>
 * Usage example (consignment):
 *
 * <pre>{@code
 * NotificationContext ctx = NotificationContext.builder().tenantId(tenantId).consignorId(consignorId)
 *         .consignorName("Jane Doe").consignorEmail("jane@example.com").locale("en")
 *         .templateData(Map.of("itemCount", 5, "batchId", batchId)).build();
 * notificationService.sendIntakeConfirmation(ctx);
 * }</pre>
 *
 * <p>
 * Usage example (order):
 *
 * <pre>{@code
 * NotificationContext ctx = NotificationContext.builder().tenantId(tenantId).customerId(customerId)
 *         .customerName("John Smith").customerEmail("john@example.com").locale("en")
 *         .templateData(Map.of("orderNumber", "ORD-12345", "orderDate", "2026-01-10")).build();
 * notificationService.sendOrderConfirmation(ctx);
 * }</pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Notification service and email templates (consignment)</li>
 * <li>Task I4.T5: Order email templates and domain filtering</li>
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
    DomainFilterService domainFilterService;

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

    @Inject
    @Location("email/order/order-confirmation.html")
    Template orderConfirmationTemplate;

    @Inject
    @Location("email/order/order-shipped.html")
    Template orderShippedTemplate;

    @Inject
    @Location("email/order/order-delivered.html")
    Template orderDeliveredTemplate;

    @Inject
    @Location("email/order/order-cancelled.html")
    Template orderCancelledTemplate;

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
     * Send order confirmation notification to customer after successful payment.
     *
     * <p>
     * Expected template data keys:
     * <ul>
     * <li>{@code orderNumber}: order number (e.g., "ORD-12345")</li>
     * <li>{@code orderDate}: order placement date</li>
     * <li>{@code lineItems}: list of order line items (productName, quantity, unitPrice, lineTotal)</li>
     * <li>{@code subtotal}: order subtotal (formatted Money)</li>
     * <li>{@code shipping}: shipping cost (formatted Money)</li>
     * <li>{@code tax}: tax amount (formatted Money)</li>
     * <li>{@code total}: order total (formatted Money)</li>
     * <li>{@code shippingAddress}: map with name, line1, line2, city, state, postalCode, country</li>
     * </ul>
     *
     * @param context
     *            notification context with customer and template data
     */
    public void sendOrderConfirmation(NotificationContext context) {
        enqueueNotification(EmailTemplateType.ORDER_CONFIRMATION, context);
    }

    /**
     * Send order shipped notification to customer.
     *
     * <p>
     * Expected template data keys:
     * <ul>
     * <li>{@code orderNumber}: order number</li>
     * <li>{@code shippedDate}: shipment date</li>
     * <li>{@code trackingNumber}: carrier tracking number (optional)</li>
     * <li>{@code carrier}: carrier name (optional)</li>
     * <li>{@code trackingUrl}: tracking URL (optional)</li>
     * <li>{@code estimatedDelivery}: estimated delivery date (optional)</li>
     * <li>{@code shippingAddress}: map with name, line1, line2, city, state, postalCode, country</li>
     * </ul>
     *
     * @param context
     *            notification context with customer and template data
     */
    public void sendOrderShipped(NotificationContext context) {
        enqueueNotification(EmailTemplateType.ORDER_SHIPPED, context);
    }

    /**
     * Send order delivered notification to customer.
     *
     * <p>
     * Expected template data keys:
     * <ul>
     * <li>{@code orderNumber}: order number</li>
     * <li>{@code deliveredDate}: delivery date</li>
     * <li>{@code deliveryLocation}: delivery location description (optional, e.g., "Front Porch")</li>
     * <li>{@code shippingAddress}: map with name, line1, line2, city, state, postalCode, country</li>
     * </ul>
     *
     * @param context
     *            notification context with customer and template data
     */
    public void sendOrderDelivered(NotificationContext context) {
        enqueueNotification(EmailTemplateType.ORDER_DELIVERED, context);
    }

    /**
     * Send order cancelled notification to customer.
     *
     * <p>
     * Expected template data keys:
     * <ul>
     * <li>{@code orderNumber}: order number</li>
     * <li>{@code cancelledDate}: cancellation date</li>
     * <li>{@code cancellationReason}: reason for cancellation (optional)</li>
     * <li>{@code refundAmount}: refund amount (formatted Money, optional)</li>
     * </ul>
     *
     * @param context
     *            notification context with customer and template data
     */
    public void sendOrderCancelled(NotificationContext context) {
        enqueueNotification(EmailTemplateType.ORDER_CANCELLED, context);
    }

    /**
     * Queue notification job if feature flag is enabled. Actual email rendering/sending occurs asynchronously via
     * {@link NotificationJobProcessor}.
     */
    private void enqueueNotification(EmailTemplateType type, NotificationContext context) {
        UUID tenantId = context.getTenantId();
        UUID recipientId = context.getRecipientId();
        String recipientEmail = context.getRecipientEmail();
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
                    "Notification skipped (feature flag disabled) - tenantId=%s, recipientId=%s, notification=%s, flag=%s",
                    tenantId, recipientId, type, featureFlagKey);
            meterRegistry.counter("notifications.skipped", "tenant_id", tenantId.toString(), "type", type.name(),
                    "reason", "feature_flag_disabled").increment();
            return;
        }

        NotificationJobPayload payload = NotificationJobPayload.create(type, context);
        notificationJobQueue.enqueue(payload);
        LOG.infof("Notification enqueued - jobId=%s, tenantId=%s, recipientId=%s, email=%s, notification=%s",
                payload.getJobId(), tenantId, recipientId, recipientEmail, type);

        meterRegistry.counter("notifications.enqueued", "tenant_id", tenantId.toString(), "recipient_id",
                recipientId.toString(), "type", type.name(), "locale", context.getLocale()).increment();
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
        String recipientEmail = payload.getRecipientEmail();

        LOG.infof("Sending notification - jobId=%s, tenantId=%s, recipientId=%s, email=%s, notification=%s, locale=%s",
                payload.getJobId(), tenantId, payload.getRecipientId(), recipientEmail, type, payload.getLocale());

        try {
            // Check domain filter (staging environment protection)
            if (!domainFilterService.isAllowed(recipientEmail)) {
                LOG.warnf(
                        "Notification blocked by domain filter - jobId=%s, tenantId=%s, recipientId=%s, email=%s, notification=%s",
                        payload.getJobId(), tenantId, payload.getRecipientId(), recipientEmail, type);
                meterRegistry.counter("notifications.blocked", "tenant_id", tenantId.toString(), "type", type.name(),
                        "reason", "domain_filter").increment();
                return; // Silently skip sending
            }

            Map<String, String> messages = localizationService.loadMessages(payload.getLocale());

            Map<String, Object> data = new HashMap<>(payload.getTemplateData());
            // Add recipient name (for both consignor and customer templates)
            data.put("consignorName", payload.getRecipientName());
            data.put("customerName", payload.getRecipientName());
            data.put("msg", messages);
            data.put("locale", payload.getLocale());

            Template template = templateFor(type);
            String htmlBody = template.data(data).render();

            // Determine subject key based on template type
            String subjectKey = getSubjectKey(type);
            String subject = messages.getOrDefault(subjectKey, "Notification");

            mailer.send(Mail.withHtml(recipientEmail, subject, htmlBody));

            LOG.infof("Notification sent successfully - jobId=%s, tenantId=%s, recipientId=%s, notification=%s",
                    payload.getJobId(), tenantId, payload.getRecipientId(), type);
            meterRegistry
                    .counter("notifications.sent", "tenant_id", tenantId.toString(), "recipient_id",
                            payload.getRecipientId().toString(), "type", type.name(), "locale", payload.getLocale())
                    .increment();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to send notification - jobId=%s, tenantId=%s, recipientId=%s, notification=%s",
                    payload.getJobId(), tenantId, payload.getRecipientId(), type);
            meterRegistry.counter("notifications.failed", "tenant_id", tenantId.toString(), "type", type.name(),
                    "error", e.getClass().getSimpleName()).increment();
            throw new RuntimeException("Failed to send notification: " + type, e);
        }
    }

    private String getSubjectKey(EmailTemplateType type) {
        switch (type) {
            case INTAKE_CONFIRMATION :
            case SALE_NOTIFICATION :
            case PAYOUT_SUMMARY :
            case EXPIRATION_ALERT :
                return "email.consignment." + type.name().toLowerCase() + ".subject";
            case ORDER_CONFIRMATION :
            case ORDER_SHIPPED :
            case ORDER_DELIVERED :
            case ORDER_CANCELLED :
                return "email.order." + type.name().toLowerCase() + ".subject";
            default :
                return "email.notification.subject";
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
            case ORDER_CONFIRMATION :
                return orderConfirmationTemplate;
            case ORDER_SHIPPED :
                return orderShippedTemplate;
            case ORDER_DELIVERED :
                return orderDeliveredTemplate;
            case ORDER_CANCELLED :
                return orderCancelledTemplate;
            default :
                throw new IllegalArgumentException("Unknown notification type: " + type);
        }
    }
}
