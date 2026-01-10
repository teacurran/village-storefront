package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for completed checkout operation.
 *
 * <p>
 * Returned by {@code POST /checkout/commit} after successful order creation and payment processing. Provides order
 * confirmation details including payment intent ID for client-side tracking.
 *
 * <p>
 * Maps to {@link villagecompute.storefront.services.CheckoutSaga.CheckoutResult} record.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Checkout API DTOs</li>
 * <li>OpenAPI: CheckoutResponse schema</li>
 * <li>CheckoutSaga: CheckoutResult record</li>
 * </ul>
 */
public class CheckoutResponse {

    @JsonProperty("orderId")
    private UUID orderId;

    @JsonProperty("orderNumber")
    private String orderNumber;

    @JsonProperty("paymentIntentId")
    private String paymentIntentId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("paidAt")
    private OffsetDateTime paidAt;

    public CheckoutResponse() {
    }

    public CheckoutResponse(UUID orderId, String orderNumber, String paymentIntentId, String status,
            BigDecimal totalAmount, String currency, OffsetDateTime paidAt) {
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.paymentIntentId = paymentIntentId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.paidAt = paidAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getPaymentIntentId() {
        return paymentIntentId;
    }

    public void setPaymentIntentId(String paymentIntentId) {
        this.paymentIntentId = paymentIntentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(OffsetDateTime paidAt) {
        this.paidAt = paidAt;
    }
}
