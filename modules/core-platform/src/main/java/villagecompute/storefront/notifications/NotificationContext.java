package villagecompute.storefront.notifications;

import java.util.Map;
import java.util.UUID;

/**
 * Value object containing context and data for rendering email notifications.
 *
 * <p>
 * Carries tenant, recipient (consignor/customer/user), locale, and template-specific data payload. All notifications
 * must be tenant-scoped for isolation and audit logging.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Notification service and email templates (consignment)</li>
 * <li>Task I4.T5: Order email templates and localization</li>
 * <li>ADR-001: Tenant-scoped services and data isolation</li>
 * </ul>
 */
public class NotificationContext {

    private final UUID tenantId;
    private final UUID consignorId; // For consignment emails
    private final String consignorName; // For consignment emails
    private final String consignorEmail; // For consignment emails
    private final UUID customerId; // For order emails
    private final String customerName; // For order emails
    private final String customerEmail; // For order emails
    private final String locale;
    private final Map<String, Object> templateData;

    private NotificationContext(Builder builder) {
        this.tenantId = builder.tenantId;
        this.consignorId = builder.consignorId;
        this.consignorName = builder.consignorName;
        this.consignorEmail = builder.consignorEmail;
        this.customerId = builder.customerId;
        this.customerName = builder.customerName;
        this.customerEmail = builder.customerEmail;
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

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public String getLocale() {
        return locale;
    }

    public Map<String, Object> getTemplateData() {
        return templateData;
    }

    /**
     * Get the recipient email address (consignor or customer).
     *
     * @return email address of the notification recipient
     */
    public String getRecipientEmail() {
        return consignorEmail != null ? consignorEmail : customerEmail;
    }

    /**
     * Get the recipient name (consignor or customer).
     *
     * @return name of the notification recipient
     */
    public String getRecipientName() {
        return consignorName != null ? consignorName : customerName;
    }

    /**
     * Get the recipient ID (consignor or customer).
     *
     * @return UUID of the notification recipient
     */
    public UUID getRecipientId() {
        return consignorId != null ? consignorId : customerId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private UUID tenantId;
        private UUID consignorId;
        private String consignorName;
        private String consignorEmail;
        private UUID customerId;
        private String customerName;
        private String customerEmail;
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

        public Builder customerId(UUID customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder customerName(String customerName) {
            this.customerName = customerName;
            return this;
        }

        public Builder customerEmail(String customerEmail) {
            this.customerEmail = customerEmail;
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

            // Either consignor or customer fields must be provided
            boolean hasConsignor = consignorId != null && consignorEmail != null;
            boolean hasCustomer = customerId != null && customerEmail != null;

            if (!hasConsignor && !hasCustomer) {
                throw new IllegalStateException("Either consignor (id + email) or customer (id + email) is required");
            }

            if (templateData == null) {
                throw new IllegalStateException("templateData is required");
            }
            return new NotificationContext(this);
        }
    }
}
