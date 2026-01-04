package villagecompute.storefront.api.types;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request object for updating a cart item's quantity.
 *
 * <p>
 * Matches the OpenAPI UpdateCartItemRequest schema.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: UpdateCartItemRequest component schema</li>
 * <li>Task I2.T4: Cart API request DTOs</li>
 * </ul>
 */
public class UpdateCartItemRequest {

    @NotNull(
            message = "Quantity is required")
    @Min(
            value = 1,
            message = "Quantity must be at least 1")
    @JsonProperty("quantity")
    private Integer quantity;

    public UpdateCartItemRequest() {
    }

    public UpdateCartItemRequest(Integer quantity) {
        this.quantity = quantity;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
