package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cart data transfer object for API responses.
 *
 * <p>
 * Represents a shopping cart with its items and calculated totals. Matches the OpenAPI Cart schema.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: Cart component schema</li>
 * <li>Task I2.T4: Cart API DTOs</li>
 * </ul>
 */
public class CartDto {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("userId")
    private UUID userId;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("items")
    private List<CartItemDto> items;

    @JsonProperty("subtotal")
    private Money subtotal;

    @JsonProperty("itemCount")
    private long itemCount;

    @JsonProperty("expiresAt")
    private OffsetDateTime expiresAt;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    @JsonProperty("loyalty")
    private CartLoyaltySummary loyalty;

    public CartDto() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<CartItemDto> getItems() {
        return items;
    }

    public void setItems(List<CartItemDto> items) {
        this.items = items;
    }

    public Money getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(Money subtotal) {
        this.subtotal = subtotal;
    }

    public long getItemCount() {
        return itemCount;
    }

    public void setItemCount(long itemCount) {
        this.itemCount = itemCount;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public CartLoyaltySummary getLoyalty() {
        return loyalty;
    }

    public void setLoyalty(CartLoyaltySummary loyalty) {
        this.loyalty = loyalty;
    }
}
