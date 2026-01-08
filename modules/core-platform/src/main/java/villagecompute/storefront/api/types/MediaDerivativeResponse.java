package villagecompute.storefront.api.types;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO describing a processed media derivative.
 */
public class MediaDerivativeResponse {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("derivativeType")
    private String derivativeType;

    @JsonProperty("storageKey")
    private String storageKey;

    @JsonProperty("mimeType")
    private String mimeType;

    @JsonProperty("fileSize")
    private Long fileSize;

    @JsonProperty("width")
    private Integer width;

    @JsonProperty("height")
    private Integer height;

    public MediaDerivativeResponse() {
    }

    public MediaDerivativeResponse(UUID id, String derivativeType, String storageKey, String mimeType, Long fileSize,
            Integer width, Integer height) {
        this.id = id;
        this.derivativeType = derivativeType;
        this.storageKey = storageKey;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.width = width;
        this.height = height;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getDerivativeType() {
        return derivativeType;
    }

    public void setDerivativeType(String derivativeType) {
        this.derivativeType = derivativeType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
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
}
