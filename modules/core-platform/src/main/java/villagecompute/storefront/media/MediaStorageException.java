package villagecompute.storefront.media;

/**
 * Exception thrown when media storage operations fail.
 *
 * <p>
 * Wraps underlying S3/R2 exceptions with domain-specific context.
 */
public class MediaStorageException extends RuntimeException {

    public MediaStorageException(String message) {
        super(message);
    }

    public MediaStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
