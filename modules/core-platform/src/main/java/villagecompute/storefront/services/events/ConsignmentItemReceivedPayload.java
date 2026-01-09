package villagecompute.storefront.services.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload for CONSIGNMENT_ITEM_RECEIVED domain event.
 *
 * <p>
 * Captures details of consignment item intake including product, consignor, commission details, and timestamps for
 * reporting and downstream processing (e.g., inventory updates, notifications).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Consignment domain event payloads</li>
 * <li>Architecture: Domain event payloads for reporting</li>
 * </ul>
 *
 * @param consignmentItemId
 *            UUID of the ConsignmentItem record
 * @param consignorId
 *            UUID of the consignor providing the item
 * @param consignorName
 *            name of the consignor (denormalized for reporting)
 * @param productId
 *            UUID of the product
 * @param productName
 *            name of the product (denormalized for reporting)
 * @param commissionRate
 *            commission rate percentage (e.g., 15.00 for 15%)
 * @param status
 *            initial status of the consignment item
 * @param receivedBy
 *            user who recorded the intake
 */
public record ConsignmentItemReceivedPayload(UUID consignmentItemId, UUID consignorId, String consignorName,
        UUID productId, String productName, BigDecimal commissionRate, String status, String receivedBy) {
}
