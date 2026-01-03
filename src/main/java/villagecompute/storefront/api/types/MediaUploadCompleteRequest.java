package villagecompute.storefront.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload when clients signal upload completion.
 */
public class MediaUploadCompleteRequest {

    @JsonProperty("checksumSha256")
    private String checksumSha256;

    public MediaUploadCompleteRequest() {
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }
}
