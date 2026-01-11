package villagecompute.storefront.notifications;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.models.InventoryTransfer;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service that enqueues inventory notification payloads for async delivery (email/webhook to be added later).
 */
@ApplicationScoped
public class InventoryNotificationService {

    private static final Logger LOG = Logger.getLogger(InventoryNotificationService.class);

    @Inject
    InventoryNotificationQueue queue;

    @Inject
    MeterRegistry meterRegistry;

    public UUID enqueueTransferInitiated(InventoryTransfer transfer) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        InventoryNotificationPayload payload = InventoryNotificationPayload.transferPayload(tenantId, transfer.id,
                InventoryNotificationType.TRANSFER_INITIATED, transfer.sourceLocation.code,
                transfer.destinationLocation.code, transfer.lines != null ? transfer.lines.size() : 0);
        queue.enqueue(payload);
        meterRegistry.counter("inventory.notifications.enqueued", "type", "transfer_initiated").increment();
        LOG.infof("Inventory transfer notification queued - tenantId=%s, transferId=%s, type=%s", tenantId, transfer.id,
                InventoryNotificationType.TRANSFER_INITIATED);
        return payload.getNotificationId();
    }

    public UUID enqueueTransferReceived(InventoryTransfer transfer) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        InventoryNotificationPayload payload = InventoryNotificationPayload.transferPayload(tenantId, transfer.id,
                InventoryNotificationType.TRANSFER_RECEIVED, transfer.sourceLocation.code,
                transfer.destinationLocation.code, transfer.lines != null ? transfer.lines.size() : 0);
        queue.enqueue(payload);
        meterRegistry.counter("inventory.notifications.enqueued", "type", "transfer_received").increment();
        LOG.infof("Inventory transfer notification queued - tenantId=%s, transferId=%s, type=%s", tenantId, transfer.id,
                InventoryNotificationType.TRANSFER_RECEIVED);
        return payload.getNotificationId();
    }

    public UUID enqueueLowStockAlert(InventoryLevel level) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        InventoryNotificationPayload payload = InventoryNotificationPayload.lowStockPayload(tenantId, level.variant.id,
                level.location, level.quantity, level.lowStockThreshold != null ? level.lowStockThreshold : 0);
        queue.enqueue(payload);
        meterRegistry.counter("inventory.notifications.enqueued", "type", "low_stock_alert").increment();
        LOG.infof("Low stock notification queued - tenantId=%s, variantId=%s, location=%s, quantity=%d, threshold=%d",
                tenantId, level.variant.id, level.location, level.quantity, level.lowStockThreshold);
        return payload.getNotificationId();
    }
}
