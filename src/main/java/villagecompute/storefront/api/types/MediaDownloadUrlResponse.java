package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response returned when issuing a signed download URL.
 */
public class MediaDownloadUrlResponse {

    @JsonProperty("url")
    private String url;

    @JsonProperty("expiresAt")
    private OffsetDateTime expiresAt;

    @JsonProperty("remainingAttempts")
    private int remainingAttempts;

    public MediaDownloadUrlResponse() {
    }

    public MediaDownloadUrlResponse(String url, OffsetDateTime expiresAt, int remainingAttempts) {
        this.url = url;
        this.expiresAt = expiresAt;
        this.remainingAttempts = remainingAttempts;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getRemainingAttempts() {
        return remainingAttempts;
    }

    public void setRemainingAttempts(int remainingAttempts) {
        this.remainingAttempts = remainingAttempts;
    }
}
