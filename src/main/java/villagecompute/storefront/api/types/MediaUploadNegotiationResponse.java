package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response returned after negotiating presigned upload URLs.
 */
public class MediaUploadNegotiationResponse {

    @JsonProperty("assetId")
    private UUID assetId;

    @JsonProperty("storageKey")
    private String storageKey;

    @JsonProperty("uploadUrl")
    private String uploadUrl;

    @JsonProperty("expiresAt")
    private OffsetDateTime expiresAt;

    @JsonProperty("remainingQuotaBytes")
    private long remainingQuotaBytes;

    public MediaUploadNegotiationResponse() {
    }

    public MediaUploadNegotiationResponse(UUID assetId, String storageKey, String uploadUrl, OffsetDateTime expiresAt,
            long remainingQuotaBytes) {
        this.assetId = assetId;
        this.storageKey = storageKey;
        this.uploadUrl = uploadUrl;
        this.expiresAt = expiresAt;
        this.remainingQuotaBytes = remainingQuotaBytes;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public void setAssetId(UUID assetId) {
        this.assetId = assetId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getUploadUrl() {
        return uploadUrl;
    }

    public void setUploadUrl(String uploadUrl) {
        this.uploadUrl = uploadUrl;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public long getRemainingQuotaBytes() {
        return remainingQuotaBytes;
    }

    public void setRemainingQuotaBytes(long remainingQuotaBytes) {
        this.remainingQuotaBytes = remainingQuotaBytes;
    }
}
