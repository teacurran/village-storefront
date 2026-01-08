package villagecompute.storefront.media.exceptions;

/**
 * Thrown when a tenant exceeds their configured media quota.
 */
public class MediaQuotaExceededException extends RuntimeException {

    private final long remainingBytes;

    public MediaQuotaExceededException(String message, long remainingBytes) {
        super(message);
        this.remainingBytes = remainingBytes;
    }

    public long getRemainingBytes() {
        return remainingBytes;
    }
}
