package villagecompute.storefront.media;

/**
 * Exception thrown when requested media does not exist in storage.
 */
public class MediaNotFoundException extends MediaStorageException {

    public MediaNotFoundException(String message) {
        super(message);
    }

    public MediaNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
