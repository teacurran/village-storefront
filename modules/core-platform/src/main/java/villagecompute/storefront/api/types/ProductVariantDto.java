package villagecompute.storefront.api.types;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ProductVariant DTO for API responses.
 *
 * <p>
 * Represents a product variant with pricing, stock availability, and variant-specific attributes.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: ProductVariant component schema</li>
 * <li>Entity: {@link villagecompute.storefront.data.models.ProductVariant}</li>
 * </ul>
 */
public class ProductVariantDto {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("sku")
    private String sku;

    @JsonProperty("price")
    private Money price;

    @JsonProperty("stock")
    private Integer stock;

    @JsonProperty("options")
    private Map<String, String> options;

    // Getters and setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public Money getPrice() {
        return price;
    }

    public void setPrice(Money price) {
        this.price = price;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public Map<String, String> getOptions() {
        return options;
    }

    public void setOptions(Map<String, String> options) {
        this.options = options;
    }
}
