package villagecompute.storefront.api.types;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for converting remaining gift card balance into store credit.
 */
public class ConvertGiftCardRequest {

    @NotNull(
            message = "Gift card id is required")
    public Long giftCardId;

    @NotNull(
            message = "User id is required")
    public UUID userId;

    public String reason;
}
