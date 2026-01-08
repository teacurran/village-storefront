package villagecompute.storefront.data.models;

/**
 * Status of inventory transfer between locations.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Architecture: Multi-location communication patterns</li>
 * </ul>
 */
public enum TransferStatus {
    /**
     * Transfer has been initiated but not yet shipped.
     */
    PENDING,

    /**
     * Items have been shipped from source location.
     */
    IN_TRANSIT,

    /**
     * Items have been received at destination location.
     */
    RECEIVED,

    /**
     * Transfer was cancelled before completion.
     */
    CANCELLED
}
