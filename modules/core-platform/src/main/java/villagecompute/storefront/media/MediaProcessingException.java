package villagecompute.storefront.media;

/**
 * Exception thrown when media processing operations fail.
 *
 * <p>
 * Covers image resizing failures, video transcoding errors, and metadata extraction issues.
 */
public class MediaProcessingException extends RuntimeException {

    public MediaProcessingException(String message) {
        super(message);
    }

    public MediaProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
