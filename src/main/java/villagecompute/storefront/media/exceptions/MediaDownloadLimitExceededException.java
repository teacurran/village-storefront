package villagecompute.storefront.media.exceptions;

/**
 * Indicates download attempts exceeded configured limit.
 */
public class MediaDownloadLimitExceededException extends RuntimeException {

    private final int remainingAttempts;

    public MediaDownloadLimitExceededException(int remainingAttempts) {
        super("Download limit reached");
        this.remainingAttempts = remainingAttempts;
    }

    public int getRemainingAttempts() {
        return remainingAttempts;
    }
}
