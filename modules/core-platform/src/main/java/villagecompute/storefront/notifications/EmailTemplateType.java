package villagecompute.storefront.notifications;

/**
 * Enumeration of email notification template types for the consignment lifecycle.
 *
 * <p>
 * Each type corresponds to a Qute template under {@code src/main/resources/templates/email/consignment/} and is gated
 * by a feature flag.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Notification service and email templates</li>
 * <li>Architecture Overview: Notifications module boundaries</li>
 * </ul>
 */
public enum EmailTemplateType {

    /**
     * Sent when a consignor successfully submits items for intake.
     *
     * <p>
     * Template: {@code email/consignment/intake-confirmation.html}
     * <p>
     * Feature flag: {@code notifications.consignor.intake}
     */
    INTAKE_CONFIRMATION("email/consignment/intake-confirmation.html", "notifications.consignor.intake"),

    /**
     * Sent when a consignment item is sold.
     *
     * <p>
     * Template: {@code email/consignment/sale-notification.html}
     * <p>
     * Feature flag: {@code notifications.consignor.sale}
     */
    SALE_NOTIFICATION("email/consignment/sale-notification.html", "notifications.consignor.sale"),

    /**
     * Sent when a payout batch is processed for a consignor.
     *
     * <p>
     * Template: {@code email/consignment/payout-summary.html}
     * <p>
     * Feature flag: {@code notifications.consignor.payout}
     */
    PAYOUT_SUMMARY("email/consignment/payout-summary.html", "notifications.consignor.payout"),

    /**
     * Sent when consignment items are approaching expiration.
     *
     * <p>
     * Template: {@code email/consignment/expiration-alert.html}
     * <p>
     * Feature flag: {@code notifications.consignor.expiration}
     */
    EXPIRATION_ALERT("email/consignment/expiration-alert.html", "notifications.consignor.expiration");

    private final String templatePath;
    private final String featureFlagKey;

    EmailTemplateType(String templatePath, String featureFlagKey) {
        this.templatePath = templatePath;
        this.featureFlagKey = featureFlagKey;
    }

    /**
     * Get the Qute template path (relative to {@code src/main/resources/templates/}).
     *
     * @return template path
     */
    public String getTemplatePath() {
        return templatePath;
    }

    /**
     * Get the feature flag key that gates this notification type.
     *
     * @return feature flag key
     */
    public String getFeatureFlagKey() {
        return featureFlagKey;
    }
}
