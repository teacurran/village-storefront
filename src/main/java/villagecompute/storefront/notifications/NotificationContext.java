package villagecompute.storefront.notifications;

import java.util.Map;
import java.util.UUID;

/**
 * Value object containing context and data for rendering email notifications.
 *
 * <p>
 * Carries tenant, consignor, locale, recipient details, and template-specific data payload. All notifications must be
 * tenant-scoped for isolation and audit logging.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Notification service and email templates</li>
 * <li>ADR-001: Tenant-scoped services and data isolation</li>
 * </ul>
 */
public class NotificationContext {

    private final UUID tenantId;
    private final UUID consignorId;
    private final String consignorName;
    private final String consignorEmail;
    private final String locale;
    private final Map<String, Object> templateData;

    private NotificationContext(Builder builder) {
        this.tenantId = builder.tenantId;
        this.consignorId = builder.consignorId;
        this.consignorName = builder.consignorName;
        this.consignorEmail = builder.consignorEmail;
        this.locale = builder.locale;
        this.templateData = builder.templateData;
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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private UUID tenantId;
        private UUID consignorId;
        private String consignorName;
        private String consignorEmail;
        private String locale = "en";
        private Map<String, Object> templateData;

        public Builder tenantId(UUID tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder consignorId(UUID consignorId) {
            this.consignorId = consignorId;
            return this;
        }

        public Builder consignorName(String consignorName) {
            this.consignorName = consignorName;
            return this;
        }

        public Builder consignorEmail(String consignorEmail) {
            this.consignorEmail = consignorEmail;
            return this;
        }

        public Builder locale(String locale) {
            this.locale = locale;
            return this;
        }

        public Builder templateData(Map<String, Object> templateData) {
            this.templateData = templateData;
            return this;
        }

        public NotificationContext build() {
            if (tenantId == null) {
                throw new IllegalStateException("tenantId is required");
            }
            if (consignorId == null) {
                throw new IllegalStateException("consignorId is required");
            }
            if (consignorEmail == null || consignorEmail.isBlank()) {
                throw new IllegalStateException("consignorEmail is required");
            }
            if (templateData == null) {
                throw new IllegalStateException("templateData is required");
            }
            return new NotificationContext(this);
        }
    }
}
