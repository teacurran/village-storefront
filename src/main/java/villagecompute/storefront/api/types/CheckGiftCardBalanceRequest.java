package villagecompute.storefront.api.types;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for POST /gift-cards/check-balance.
 */
public class CheckGiftCardBalanceRequest {

    @NotBlank(
            message = "Gift card code is required")
    public String code;
}
