package villagecompute.storefront.services.events;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Payload for TRANSFER_RECEIVED domain event.
 *
 * <p>
 * Captures transfer completion details including received quantities and fulfillment timestamp for audit and reporting.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Transfer completion event payload</li>
 * <li>Architecture: Multi-location inventory workflow events</li>
 * </ul>
 *
 * @param transferId
 *            UUID of the InventoryTransfer
 * @param sourceLocation
 *            code of source location
 * @param destinationLocation
 *            code of destination location
 * @param lineItems
 *            list of received items with quantities
 * @param receivedAt
 *            timestamp when transfer was marked received
 */
public record TransferReceivedPayload(UUID transferId, String sourceLocation, String destinationLocation,
        List<ReceivedLineItem> lineItems, OffsetDateTime receivedAt) {

    /**
     * Received line item with quantities.
     *
     * @param variantId
     *            UUID of the product variant
     * @param quantityExpected
     *            quantity originally transferred
     * @param quantityReceived
     *            quantity actually received
     */
    public record ReceivedLineItem(UUID variantId, int quantityExpected, int quantityReceived) {
    }
}
