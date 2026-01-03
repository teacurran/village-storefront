package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO representing media asset metadata.
 */
public class MediaAssetResponse {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("assetType")
    private String assetType;

    @JsonProperty("originalFilename")
    private String originalFilename;

    @JsonProperty("mimeType")
    private String mimeType;

    @JsonProperty("fileSize")
    private Long fileSize;

    @JsonProperty("status")
    private String status;

    @JsonProperty("width")
    private Integer width;

    @JsonProperty("height")
    private Integer height;

    @JsonProperty("durationSeconds")
    private Integer durationSeconds;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    @JsonProperty("downloadAttempts")
    private Integer downloadAttempts;

    @JsonProperty("maxDownloadAttempts")
    private Integer maxDownloadAttempts;

    @JsonProperty("derivatives")
    private List<MediaDerivativeResponse> derivatives = new ArrayList<>();

    public MediaAssetResponse() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<MediaDerivativeResponse> getDerivatives() {
        return derivatives;
    }

    public void setDerivatives(List<MediaDerivativeResponse> derivatives) {
        this.derivatives = derivatives != null ? derivatives : new ArrayList<>();
    }

    public Integer getDownloadAttempts() {
        return downloadAttempts;
    }

    public void setDownloadAttempts(Integer downloadAttempts) {
        this.downloadAttempts = downloadAttempts;
    }

    public Integer getMaxDownloadAttempts() {
        return maxDownloadAttempts;
    }

    public void setMaxDownloadAttempts(Integer maxDownloadAttempts) {
        this.maxDownloadAttempts = maxDownloadAttempts;
    }
}
