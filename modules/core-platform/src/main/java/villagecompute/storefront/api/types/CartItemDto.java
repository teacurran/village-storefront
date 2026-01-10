package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cart item data transfer object for API responses.
 *
 * <p>
 * Represents a line item within a shopping cart. Matches the OpenAPI CartItem schema.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: CartItem component schema</li>
 * <li>Task I2.T4: Cart API DTOs</li>
 * </ul>
 */
public class CartItemDto {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("variantId")
    private UUID variantId;

    @JsonProperty("productName")
    private String productName;

    @JsonProperty("variantName")
    private String variantName;

    @JsonProperty("sku")
    private String sku;

    @JsonProperty("quantity")
    private int quantity;

    @JsonProperty("unitPrice")
    private Money unitPrice;

    @JsonProperty("lineTotal")
    private Money lineTotal;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    public CartItemDto() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public void setVariantId(UUID variantId) {
        this.variantId = variantId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getVariantName() {
        return variantName;
    }

    public void setVariantName(String variantName) {
        this.variantName = variantName;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Money getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(Money unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Money getLineTotal() {
        return lineTotal;
    }

    public void setLineTotal(Money lineTotal) {
        this.lineTotal = lineTotal;
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
}
