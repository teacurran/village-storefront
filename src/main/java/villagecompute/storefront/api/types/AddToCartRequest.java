package villagecompute.storefront.api.types;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request object for adding an item to a cart.
 *
 * <p>
 * Matches the OpenAPI AddToCartRequest schema.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: AddToCartRequest component schema</li>
 * <li>Task I2.T4: Cart API request DTOs</li>
 * </ul>
 */
public class AddToCartRequest {

    @NotNull(
            message = "Variant ID is required")
    @JsonProperty("variantId")
    private UUID variantId;

    @NotNull(
            message = "Quantity is required")
    @Min(
            value = 1,
            message = "Quantity must be at least 1")
    @JsonProperty("quantity")
    private Integer quantity;

    public AddToCartRequest() {
    }

    public AddToCartRequest(UUID variantId, Integer quantity) {
        this.variantId = variantId;
        this.quantity = quantity;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public void setVariantId(UUID variantId) {
        this.variantId = variantId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
