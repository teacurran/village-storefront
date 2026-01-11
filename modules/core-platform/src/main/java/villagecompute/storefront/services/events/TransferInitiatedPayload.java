package villagecompute.storefront.services.events;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import villagecompute.storefront.data.models.TransferStatus;

/**
 * Payload for TRANSFER_INITIATED domain event.
 *
 * <p>
 * Captures transfer creation details including source/destination locations, line items, and metadata for tracking and
 * reporting.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Transfer initiation event payload</li>
 * <li>Architecture: Domain event payloads for inventory transfers</li>
 * </ul>
 *
 * @param transferId
 *            UUID of the InventoryTransfer
 * @param sourceLocationId
 *            UUID of source location
 * @param sourceLocation
 *            code of source location
 * @param destinationLocationId
 *            UUID of destination location
 * @param destinationLocation
 *            code of destination location
 * @param status
 *            initial status of the transfer
 * @param lineItems
 *            list of items being transferred
 * @param initiatedBy
 *            user who initiated the transfer
 * @param expectedArrivalDate
 *            expected arrival at destination
 * @param notes
 *            optional transfer notes
 */
public record TransferInitiatedPayload(UUID transferId, UUID sourceLocationId, String sourceLocation,
        UUID destinationLocationId, String destinationLocation, TransferStatus status,
        List<TransferLineItem> lineItems, String initiatedBy, OffsetDateTime expectedArrivalDate, String notes) {

    /**
     * Line item within a transfer.
     *
     * @param variantId
     *            UUID of the product variant
     * @param variantSku
     *            SKU of the variant
     * @param quantity
     *            quantity being transferred
     */
    public record TransferLineItem(UUID variantId, String variantSku, int quantity) {
    }
}
