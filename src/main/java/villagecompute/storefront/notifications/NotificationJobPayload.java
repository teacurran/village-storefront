package villagecompute.storefront.notifications;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable payload representing a queued consignment notification job.
 *
 * <p>
 * Captures the template type plus tenant/consignor metadata so the async worker can render the template and send the
 * email without reaching back into the request thread.
 */
public final class NotificationJobPayload {

    private final UUID jobId;
    private final EmailTemplateType templateType;
    private final UUID tenantId;
    private final UUID consignorId;
    private final String consignorName;
    private final String consignorEmail;
    private final String locale;
    private final Map<String, Object> templateData;
    private final OffsetDateTime enqueuedAt;

    private NotificationJobPayload(UUID jobId, EmailTemplateType templateType, UUID tenantId, UUID consignorId,
            String consignorName, String consignorEmail, String locale, Map<String, Object> templateData,
            OffsetDateTime enqueuedAt) {
        this.jobId = jobId;
        this.templateType = templateType;
        this.tenantId = tenantId;
        this.consignorId = consignorId;
        this.consignorName = consignorName;
        this.consignorEmail = consignorEmail;
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

        return new NotificationJobPayload(jobId, type, context.getTenantId(), context.getConsignorId(),
                context.getConsignorName(), context.getConsignorEmail(), context.getLocale(), context.getTemplateData(),
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

    public UUID getConsignorId() {
        return consignorId;
    }

    public String getConsignorName() {
        return consignorName;
    }

    public String getConsignorEmail() {
        return consignorEmail;
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
