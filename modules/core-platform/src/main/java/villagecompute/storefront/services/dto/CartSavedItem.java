package villagecompute.storefront.services.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import villagecompute.storefront.data.models.CartItem;

/**
 * Value object representing an item saved for later outside the active cart.
 *
 * <p>
 * Stored inside {@code Cart.metadata} so carts retain shopper intent even if the shopper signs out or switches devices.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CartSavedItem {

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
    private BigDecimal unitPrice;

    @JsonProperty("savedAt")
    private OffsetDateTime savedAt;

    public static CartSavedItem fromCartItem(CartItem item) {
        CartSavedItem saved = new CartSavedItem();
        saved.id = UUID.randomUUID();
        saved.variantId = item.variant.id;
        saved.productName = item.variant.product != null ? item.variant.product.title : null;
        saved.variantName = item.variant.sku;
        saved.sku = item.variant.sku;
        saved.quantity = item.quantity != null ? item.quantity : 1;
        saved.unitPrice = item.unitPrice;
        saved.savedAt = OffsetDateTime.now();
        return saved;
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

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public OffsetDateTime getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(OffsetDateTime savedAt) {
        this.savedAt = savedAt;
    }
}
