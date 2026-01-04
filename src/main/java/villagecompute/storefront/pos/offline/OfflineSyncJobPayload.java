package villagecompute.storefront.pos.offline;

import java.util.UUID;

/**
 * Job payload for processing POS offline queue entries.
 *
 * <p>
 * Background jobs use this payload to identify which queue entry to decrypt and replay. The tenant ID enables context
 * restoration per JobProcessor contract.
 *
 * <p>
 * References:
 * <ul>
 * <li>Architecture: §3.6 Background Processing (job payload patterns)</li>
 * <li>Code: JobProcessor requires tenant extraction</li>
 * <li>Task I4.T7: Offline sync job implementation</li>
 * </ul>
 */
public record OfflineSyncJobPayload(int version, // Payload schema version
        UUID tenantId, // For tenant context restoration
        Long queueEntryId, // POSOfflineQueue.id to process
        Long deviceId, // For logging and metrics
        String idempotencyKey // Prevent duplicate processing
) {
    public static final int CURRENT_VERSION = 1;

    /**
     * Create payload for a queue entry.
     */
    public static OfflineSyncJobPayload fromQueueEntry(POSOfflineQueue entry) {
        return new OfflineSyncJobPayload(CURRENT_VERSION, entry.tenant.id, entry.id, entry.device.id,
                entry.idempotencyKey);
    }
}
