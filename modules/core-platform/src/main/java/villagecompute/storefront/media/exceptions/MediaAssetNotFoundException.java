package villagecompute.storefront.media.exceptions;

import java.util.UUID;

/**
 * Raised when a media asset is not found for the current tenant.
 */
public class MediaAssetNotFoundException extends RuntimeException {

    private final UUID assetId;

    public MediaAssetNotFoundException(UUID assetId) {
        super("Media asset not found: " + assetId);
        this.assetId = assetId;
    }

    public UUID getAssetId() {
        return assetId;
    }
}
