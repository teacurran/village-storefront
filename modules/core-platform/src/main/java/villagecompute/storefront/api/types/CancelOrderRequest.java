package villagecompute.storefront.api.types;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for cancelling an order.
 *
 * <p>
 * Used by {@code POST /admin/orders/{orderId}/cancel} endpoint. Includes reason for cancellation and optional refund
 * flag.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Order admin API DTOs</li>
 * <li>OpenAPI: CancelOrderRequest schema</li>
 * </ul>
 */
public class CancelOrderRequest {

    @JsonProperty("reason")
    @NotBlank(
            message = "Cancellation reason is required")
    @Size(
            max = 500,
            message = "Reason must not exceed 500 characters")
    private String reason;

    @JsonProperty("refund")
    private Boolean refund;

    public CancelOrderRequest() {
    }

    public CancelOrderRequest(String reason, Boolean refund) {
        this.reason = reason;
        this.refund = refund;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Boolean getRefund() {
        return refund;
    }

    public void setRefund(Boolean refund) {
        this.refund = refund;
    }
}
