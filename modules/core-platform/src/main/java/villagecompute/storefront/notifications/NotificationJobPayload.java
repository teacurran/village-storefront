package villagecompute.storefront.notifications;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable payload representing a queued notification job (consignment or order).
 *
 * <p>
 * Captures the template type plus tenant/recipient metadata so the async worker can render the template and send the
 * email without reaching back into the request thread.
 */
public final class NotificationJobPayload {

    private final UUID jobId;
    private final EmailTemplateType templateType;
    private final UUID tenantId;
    private final UUID recipientId; // consignorId or customerId
    private final String recipientName; // consignorName or customerName
    private final String recipientEmail; // consignorEmail or customerEmail
    private final String locale;
    private final Map<String, Object> templateData;
    private final OffsetDateTime enqueuedAt;

    private NotificationJobPayload(UUID jobId, EmailTemplateType templateType, UUID tenantId, UUID recipientId,
            String recipientName, String recipientEmail, String locale, Map<String, Object> templateData,
            OffsetDateTime enqueuedAt) {
        this.jobId = jobId;
        this.templateType = templateType;
        this.tenantId = tenantId;
        this.recipientId = recipientId;
        this.recipientName = recipientName;
        this.recipientEmail = recipientEmail;
        this.locale = locale;
        this.templateData = Collections.unmodifiableMap(new HashMap<>(templateData));
        this.enqueuedAt = enqueuedAt;
    }

    /**
     * Factory method that snapshots the provided {@link NotificationContext}.
     *
     * @param type
     *            template type
     * @param context
     *            notification context captured at enqueue time
     * @return payload ready for queueing
     */
    public static NotificationJobPayload create(EmailTemplateType type, NotificationContext context) {
        UUID jobId = UUID.randomUUID();
        OffsetDateTime enqueuedAt = OffsetDateTime.now();

        return new NotificationJobPayload(jobId, type, context.getTenantId(), context.getRecipientId(),
                context.getRecipientName(), context.getRecipientEmail(), context.getLocale(), context.getTemplateData(),
                enqueuedAt);
    }

    public UUID getJobId() {
        return jobId;
    }

    public EmailTemplateType getTemplateType() {
        return templateType;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    /** @deprecated Use {@link #getRecipientId()} instead */
    @Deprecated
    public UUID getConsignorId() {
        return recipientId;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    /** @deprecated Use {@link #getRecipientName()} instead */
    @Deprecated
    public String getConsignorName() {
        return recipientName;
    }

    public String getRecipientName() {
        return recipientName;
    }

    /** @deprecated Use {@link #getRecipientEmail()} instead */
    @Deprecated
    public String getConsignorEmail() {
        return recipientEmail;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getLocale() {
        return locale;
    }

    public Map<String, Object> getTemplateData() {
        return templateData;
    }

    public OffsetDateTime getEnqueuedAt() {
        return enqueuedAt;
    }
}
