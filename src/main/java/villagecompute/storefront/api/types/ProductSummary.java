package villagecompute.storefront.api.types;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ProductSummary DTO for catalog listing API responses.
 *
 * <p>
 * Lightweight product representation for list views, containing essential information without full details.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: ProductSummary component schema</li>
 * <li>Endpoint: GET /catalog/products</li>
 * </ul>
 */
public class ProductSummary {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("sku")
    private String sku;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("price")
    private Money price;

    @JsonProperty("status")
    private String status;

    @JsonProperty("imageUrl")
    private String imageUrl;

    @JsonProperty("inStock")
    private Boolean inStock;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Money getPrice() {
        return price;
    }

    public void setPrice(Money price) {
        this.price = price;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Boolean getInStock() {
        return inStock;
    }

    public void setInStock(Boolean inStock) {
        this.inStock = inStock;
    }
}
