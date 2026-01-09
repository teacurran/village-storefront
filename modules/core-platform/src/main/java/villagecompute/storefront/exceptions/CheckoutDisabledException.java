package villagecompute.storefront.exceptions;

/**
 * Exception thrown when checkout operations are disabled via feature flags.
 *
 * <p>
 * Allows API resources to translate the kill switch into a 503 or 409 HTTP response without leaking internal details.
 * </p>
 */
public class CheckoutDisabledException extends RuntimeException {

    public CheckoutDisabledException(String message) {
        super(message);
    }
}
