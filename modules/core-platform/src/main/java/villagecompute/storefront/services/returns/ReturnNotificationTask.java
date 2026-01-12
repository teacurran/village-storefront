package villagecompute.storefront.services.returns;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable payload describing a notification that should be sent because of a return.
 */
public final class ReturnNotificationTask {

    private final UUID taskId;
    private final UUID tenantId;
    private final Long returnAuthorizationId;
    private final UUID consignorId;
    private final String consignorName;
    private final String consignorEmail;
    private final UUID orderId;
    private final String productName;
    private final int quantity;
    private final String reasonCode;
    private final String trigger;
    private final OffsetDateTime enqueuedAt;

    public ReturnNotificationTask(UUID taskId, UUID tenantId, Long returnAuthorizationId, UUID consignorId,
            String consignorName, String consignorEmail, UUID orderId, String productName, int quantity,
            String reasonCode, String trigger, OffsetDateTime enqueuedAt) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.returnAuthorizationId = returnAuthorizationId;
        this.consignorId = Objects.requireNonNull(consignorId, "consignorId");
        this.consignorName = consignorName;
        this.consignorEmail = consignorEmail;
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.productName = productName;
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
        this.quantity = quantity;
        this.reasonCode = reasonCode;
        this.trigger = trigger;
        this.enqueuedAt = Objects.requireNonNull(enqueuedAt, "enqueuedAt");
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Long getReturnAuthorizationId() {
        return returnAuthorizationId;
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

    public UUID getOrderId() {
        return orderId;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getTrigger() {
        return trigger;
    }

    public OffsetDateTime getEnqueuedAt() {
        return enqueuedAt;
    }
}
