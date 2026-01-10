package villagecompute.storefront.services;

import java.util.UUID;

/**
 * Exception thrown when referencing a non-existent or invalid inventory location.
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
public class InvalidLocationException extends RuntimeException {

    private final String locationIdentifier;

    public InvalidLocationException(String locationCode) {
        super(String.format("Invalid or non-existent location: %s", locationCode));
        this.locationIdentifier = locationCode;
    }

    public InvalidLocationException(UUID locationId) {
        super(String.format("Invalid or non-existent location ID: %s", locationId));
        this.locationIdentifier = locationId.toString();
    }

    public InvalidLocationException(String message, String locationIdentifier) {
        super(message);
        this.locationIdentifier = locationIdentifier;
    }

    public String getLocationIdentifier() {
        return locationIdentifier;
    }
}
