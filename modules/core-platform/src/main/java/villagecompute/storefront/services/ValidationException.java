package villagecompute.storefront.services;

/**
 * Exception thrown when validation rules are violated.
 *
 * <p>
 * Used for business rule validation such as status transitions, slug uniqueness, and format constraints.
 *
 * <p>
 * Per project standards, extends RuntimeException (no throws declarations needed).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T1: Catalog validation requirements</li>
 * <li>Project Standards: All exceptions extend RuntimeException</li>
 * </ul>
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
