package villagecompute.storefront.api.types;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for refunding an order.
 *
 * <p>
 * Used by {@code POST /admin/orders/{orderId}/refund} endpoint. Supports full or partial refunds with optional
 * restocking.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Order admin API DTOs</li>
 * <li>OpenAPI: RefundOrderRequest schema</li>
 * </ul>
 */
public class RefundOrderRequest {

    @JsonProperty("amount")
    @NotNull(
            message = "Refund amount is required")
    @Valid
    private Money amount;

    @JsonProperty("reason")
    @NotBlank(
            message = "Refund reason is required")
    @Size(
            max = 500,
            message = "Reason must not exceed 500 characters")
    private String reason;

    @JsonProperty("restockItems")
    private Boolean restockItems;

    public RefundOrderRequest() {
    }

    public RefundOrderRequest(Money amount, String reason, Boolean restockItems) {
        this.amount = amount;
        this.reason = reason;
        this.restockItems = restockItems;
    }

    public Money getAmount() {
        return amount;
    }

    public void setAmount(Money amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Boolean getRestockItems() {
        return restockItems;
    }

    public void setRestockItems(Boolean restockItems) {
        this.restockItems = restockItems;
    }
}
