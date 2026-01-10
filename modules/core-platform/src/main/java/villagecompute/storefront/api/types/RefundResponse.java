package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for refund operations.
 *
 * <p>
 * Returned by {@code POST /admin/orders/{orderId}/refund} after refund is initiated. Status tracks refund processing
 * through Stripe.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Order admin API DTOs</li>
 * <li>OpenAPI: RefundResponse schema</li>
 * </ul>
 */
public class RefundResponse {

    @JsonProperty("refundId")
    private UUID refundId;

    @JsonProperty("amount")
    private Money amount;

    @JsonProperty("status")
    private String status; // PENDING, SUCCEEDED, FAILED

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("processedAt")
    private OffsetDateTime processedAt;

    public RefundResponse() {
    }

    public RefundResponse(UUID refundId, Money amount, String status, String reason, OffsetDateTime createdAt,
            OffsetDateTime processedAt) {
        this.refundId = refundId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.createdAt = createdAt;
        this.processedAt = processedAt;
    }

    public UUID getRefundId() {
        return refundId;
    }

    public void setRefundId(UUID refundId) {
        this.refundId = refundId;
    }

    public Money getAmount() {
        return amount;
    }

    public void setAmount(Money amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(OffsetDateTime processedAt) {
        this.processedAt = processedAt;
    }
}
