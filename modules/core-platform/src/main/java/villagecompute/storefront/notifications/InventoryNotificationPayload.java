package villagecompute.storefront.notifications;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload for inventory notifications. Captures minimal context for async fan-out.
 */
public final class InventoryNotificationPayload {

    private final UUID notificationId;
    private final UUID tenantId;
    private final InventoryNotificationType type;
    private final UUID transferId;
    private final UUID variantId;
    private final String sourceLocation;
    private final String destinationLocation;
    private final String location;
    private final int quantity;
    private final int threshold;
    private final int lineCount;
    private final OffsetDateTime createdAt;

    private InventoryNotificationPayload(UUID notificationId, UUID tenantId, InventoryNotificationType type,
            UUID transferId, UUID variantId, String sourceLocation, String destinationLocation, String location,
            int quantity, int threshold, int lineCount, OffsetDateTime createdAt) {
        this.notificationId = notificationId;
        this.tenantId = tenantId;
        this.type = type;
        this.transferId = transferId;
        this.variantId = variantId;
        this.sourceLocation = sourceLocation;
        this.destinationLocation = destinationLocation;
        this.location = location;
        this.quantity = quantity;
        this.threshold = threshold;
        this.lineCount = lineCount;
        this.createdAt = createdAt;
    }

    public static InventoryNotificationPayload transferPayload(UUID tenantId, UUID transferId,
            InventoryNotificationType type, String sourceLocation, String destinationLocation, int lineCount) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(type, "type");
        return new InventoryNotificationPayload(UUID.randomUUID(), tenantId, type, transferId, null, sourceLocation,
                destinationLocation, null, 0, 0, lineCount, OffsetDateTime.now());
    }

    public static InventoryNotificationPayload lowStockPayload(UUID tenantId, UUID variantId, String location,
            int quantity, int threshold) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(variantId, "variantId");
        Objects.requireNonNull(location, "location");
        return new InventoryNotificationPayload(UUID.randomUUID(), tenantId, InventoryNotificationType.LOW_STOCK_ALERT,
                null, variantId, null, null, location, quantity, threshold, 0, OffsetDateTime.now());
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public InventoryNotificationType getType() {
        return type;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getSourceLocation() {
        return sourceLocation;
    }

    public String getDestinationLocation() {
        return destinationLocation;
    }

    public String getLocation() {
        return location;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getThreshold() {
        return threshold;
    }

    public int getLineCount() {
        return lineCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
