package villagecompute.storefront.services.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Event payload for IntakeBatchCompleted domain event.
 *
 * <p>
 * Published when a consignment intake batch is completed, triggering downstream workflows such as inventory
 * reconciliation and notifications.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Batch intake events</li>
 * <li>Entity: {@link villagecompute.storefront.data.models.IntakeBatch}</li>
 * </ul>
 */
public record IntakeBatchCompletedPayload(UUID batchId, UUID consignorId, String batchName, Integer totalItems,
        Integer processedItems, OffsetDateTime completedAt, String triggeredBy) {
}
