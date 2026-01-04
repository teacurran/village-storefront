package villagecompute.storefront.data.models;

/**
 * Reason codes for inventory adjustments.
 *
 * <p>
 * Used to categorize manual inventory corrections, damages, returns, and other non-transfer quantity changes.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Observability: Adjustment reason code logging</li>
 * </ul>
 */
public enum AdjustmentReason {
    /**
     * Inventory count correction from physical audit.
     */
    CYCLE_COUNT,

    /**
     * Damaged or defective items removed from inventory.
     */
    DAMAGE,

    /**
     * Customer return added back to inventory.
     */
    RETURN,

    /**
     * Theft or shrinkage loss.
     */
    SHRINKAGE,

    /**
     * Items found during inventory reconciliation.
     */
    FOUND,

    /**
     * Manual correction for other reasons.
     */
    OTHER
}
