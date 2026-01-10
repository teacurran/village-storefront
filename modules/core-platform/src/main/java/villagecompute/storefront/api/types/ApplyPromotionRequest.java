package villagecompute.storefront.api.types;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for applying a promotion code to a cart.
 *
 * <p>
 * Used by {@code POST /api/carts/current/promotions} endpoint to apply discount codes.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Cart API DTOs</li>
 * <li>OpenAPI: ApplyPromotionRequest schema</li>
 * </ul>
 */
public class ApplyPromotionRequest {

    @JsonProperty("promoCode")
    @NotBlank(
            message = "Promotion code is required")
    @Size(
            max = 50,
            message = "Promotion code must not exceed 50 characters")
    private String promoCode;

    public ApplyPromotionRequest() {
    }

    public ApplyPromotionRequest(String promoCode) {
        this.promoCode = promoCode;
    }

    public String getPromoCode() {
        return promoCode;
    }

    public void setPromoCode(String promoCode) {
        this.promoCode = promoCode;
    }
}
