package villagecompute.storefront.exceptions;

/**
 * Exception thrown when an idempotency key conflict is detected (duplicate request still processing).
 *
 * <p>
 * Maps to HTTP 409 Conflict status code per ADR-003 idempotency semantics.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-003: Checkout saga decision (Section 3: Idempotency keys)</li>
 * <li>Task I3.T1: Idempotency conflict handling</li>
 * </ul>
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }

    public IdempotencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
