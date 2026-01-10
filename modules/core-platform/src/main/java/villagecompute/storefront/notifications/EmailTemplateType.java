package villagecompute.storefront.notifications;

/**
 * Enumeration of email notification template types for consignment lifecycle and order notifications.
 *
 * <p>
 * Each type corresponds to a Qute template under {@code src/main/resources/templates/email/} and is gated by a feature
 * flag.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Notification service and email templates (consignment)</li>
 * <li>Task I4.T5: Order email templates and localization</li>
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
    EXPIRATION_ALERT("email/consignment/expiration-alert.html", "notifications.consignor.expiration"),

    /**
     * Sent when an order is successfully placed and payment confirmed.
     *
     * <p>
     * Template: {@code email/order/order-confirmation.html}
     * <p>
     * Feature flag: {@code notifications.order.confirmation}
     */
    ORDER_CONFIRMATION("email/order/order-confirmation.html", "notifications.order.confirmation"),

    /**
     * Sent when an order has been shipped.
     *
     * <p>
     * Template: {@code email/order/order-shipped.html}
     * <p>
     * Feature flag: {@code notifications.order.shipped}
     */
    ORDER_SHIPPED("email/order/order-shipped.html", "notifications.order.shipped"),

    /**
     * Sent when an order has been delivered.
     *
     * <p>
     * Template: {@code email/order/order-delivered.html}
     * <p>
     * Feature flag: {@code notifications.order.delivered}
     */
    ORDER_DELIVERED("email/order/order-delivered.html", "notifications.order.delivered"),

    /**
     * Sent when an order is cancelled.
     *
     * <p>
     * Template: {@code email/order/order-cancelled.html}
     * <p>
     * Feature flag: {@code notifications.order.cancelled}
     */
    ORDER_CANCELLED("email/order/order-cancelled.html", "notifications.order.cancelled");

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
