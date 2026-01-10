package villagecompute.storefront.services.events;

import java.util.UUID;

import villagecompute.storefront.data.models.AdjustmentReason;

/**
 * Payload for INVENTORY_ADJUSTED domain event.
 *
 * <p>
 * Captures details of manual inventory adjustments including quantities, reason codes, and user context for reporting
 * and audit trails.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Inventory adjustment event payload</li>
 * <li>Architecture: Domain event payloads for reporting</li>
 * </ul>
 *
 * @param variantId
 *            UUID of the product variant adjusted
 * @param locationCode
 *            location code where adjustment occurred
 * @param quantityBefore
 *            quantity before adjustment
 * @param quantityAfter
 *            quantity after adjustment
 * @param quantityChange
 *            delta applied (positive or negative)
 * @param reason
 *            reason code for the adjustment
 * @param adjustedBy
 *            user who performed the adjustment
 * @param notes
 *            optional notes explaining the adjustment
 * @param adjustmentId
 *            UUID of the InventoryAdjustment record
 */
public record InventoryAdjustedPayload(UUID variantId, String locationCode, int quantityBefore, int quantityAfter,
        int quantityChange, AdjustmentReason reason, String adjustedBy, String notes, UUID adjustmentId) {
}
