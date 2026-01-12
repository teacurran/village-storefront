package villagecompute.storefront.services.returns;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable payload describing an inventory adjustment that should happen as part of a return workflow.
 */
public final class ReturnInventoryTask {

    private final UUID taskId;
    private final UUID tenantId;
    private final Long returnAuthorizationId;
    private final UUID orderId;
    private final UUID lineItemId;
    private final UUID variantId;
    private final int quantity;
    private final String reasonCode;
    private final UUID consignorId;
    private final String trigger;
    private final OffsetDateTime enqueuedAt;

    public ReturnInventoryTask(UUID taskId, UUID tenantId, Long returnAuthorizationId, UUID orderId, UUID lineItemId,
            UUID variantId, int quantity, String reasonCode, UUID consignorId, String trigger,
            OffsetDateTime enqueuedAt) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.returnAuthorizationId = returnAuthorizationId;
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.lineItemId = Objects.requireNonNull(lineItemId, "lineItemId");
        this.variantId = Objects.requireNonNull(variantId, "variantId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
        this.quantity = quantity;
        this.reasonCode = reasonCode;
        this.consignorId = consignorId;
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

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getLineItemId() {
        return lineItemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public UUID getConsignorId() {
        return consignorId;
    }

    public String getTrigger() {
        return trigger;
    }

    public OffsetDateTime getEnqueuedAt() {
        return enqueuedAt;
    }
}
