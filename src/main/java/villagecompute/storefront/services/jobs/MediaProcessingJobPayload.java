package villagecompute.storefront.services.jobs;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Job payload for media processing background jobs.
 *
 * <p>
 * Encapsulates media asset ID and tenant ID for derivative generation. Job processor uses this payload to fetch the
 * asset, process it (images or video), upload derivatives to R2, and update asset status.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T3 - Media Pipeline</li>
 * <li>Architecture §3.6: JSON payloads with version numbers to prevent schema drift</li>
 * </ul>
 */
public class MediaProcessingJobPayload {

    private final UUID mediaAssetId;
    private final UUID tenantId;
    private final int payloadVersion;

    @JsonCreator
    public MediaProcessingJobPayload(@JsonProperty("mediaAssetId") UUID mediaAssetId,
            @JsonProperty("tenantId") UUID tenantId, @JsonProperty("payloadVersion") int payloadVersion) {
        this.mediaAssetId = mediaAssetId;
        this.tenantId = tenantId;
        this.payloadVersion = payloadVersion;
    }

    public MediaProcessingJobPayload(UUID mediaAssetId, UUID tenantId) {
        this(mediaAssetId, tenantId, 1); // Current schema version
    }

    public UUID getMediaAssetId() {
        return mediaAssetId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public int getPayloadVersion() {
        return payloadVersion;
    }

    @Override
    public String toString() {
        return String.format("MediaProcessingJobPayload{mediaAssetId=%s, tenantId=%s, version=%d}", mediaAssetId,
                tenantId, payloadVersion);
    }
}
