package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Order summary DTO for list views.
 *
 * <p>
 * Lightweight order representation for paginated lists. Contains essential fields without full line item details.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Order API DTOs</li>
 * <li>OpenAPI: OrderSummary schema</li>
 * </ul>
 */
public class OrderSummary {

    @JsonProperty("orderId")
    private UUID orderId;

    @JsonProperty("orderNumber")
    private String orderNumber;

    @JsonProperty("status")
    private String status;

    @JsonProperty("customerEmail")
    private String customerEmail;

    @JsonProperty("total")
    private Money total;

    @JsonProperty("itemCount")
    private Integer itemCount;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("paidAt")
    private OffsetDateTime paidAt;

    @JsonProperty("shippedAt")
    private OffsetDateTime shippedAt;

    public OrderSummary() {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public Money getTotal() {
        return total;
    }

    public void setTotal(Money total) {
        this.total = total;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(OffsetDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public OffsetDateTime getShippedAt() {
        return shippedAt;
    }

    public void setShippedAt(OffsetDateTime shippedAt) {
        this.shippedAt = shippedAt;
    }
}
