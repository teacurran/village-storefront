package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;

/**
 * Request DTO for updating gift card lifecycle state (admin operation).
 */
public class UpdateGiftCardRequest {
    public String status;
    public OffsetDateTime expiresAt;
    public String reason;
}
