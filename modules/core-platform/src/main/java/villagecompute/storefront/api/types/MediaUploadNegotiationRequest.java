package villagecompute.storefront.api.types;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for media upload negotiation.
 */
public class MediaUploadNegotiationRequest {

    @NotBlank
    @JsonProperty("filename")
    private String filename;

    @NotBlank
    @JsonProperty("contentType")
    private String contentType;

    @NotNull @Min(1)
    @JsonProperty("fileSize")
    private Long fileSize;

    @NotBlank
    @JsonProperty("assetType")
    private String assetType;

    @JsonProperty("maxDownloadAttempts")
    @Min(1)
    private Integer maxDownloadAttempts;

    public MediaUploadNegotiationRequest() {
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public Integer getMaxDownloadAttempts() {
        return maxDownloadAttempts;
    }

    public void setMaxDownloadAttempts(Integer maxDownloadAttempts) {
        this.maxDownloadAttempts = maxDownloadAttempts;
    }
}
