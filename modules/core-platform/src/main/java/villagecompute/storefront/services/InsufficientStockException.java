package villagecompute.storefront.services;

import java.util.UUID;

/**
 * Exception thrown when attempting to reserve or transfer more inventory than available.
 *
 * <p>
 * Per project standards, extends RuntimeException (no throws declarations needed).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Transfer validation requirements</li>
 * <li>Project Standards: All exceptions extend RuntimeException</li>
 * </ul>
 */
public class InsufficientStockException extends RuntimeException {

    private final UUID variantId;
    private final String location;
    private final int available;
    private final int requested;

    public InsufficientStockException(UUID variantId, String location, int available, int requested) {
        super(String.format("Insufficient stock for variant %s at location %s - available: %d, requested: %d",
                variantId, location, available, requested));
        this.variantId = variantId;
        this.location = location;
        this.available = available;
        this.requested = requested;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public String getLocation() {
        return location;
    }

    public int getAvailable() {
        return available;
    }

    public int getRequested() {
        return requested;
    }
}
