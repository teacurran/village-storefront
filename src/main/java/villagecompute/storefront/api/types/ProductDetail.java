package villagecompute.storefront.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ProductDetail DTO for full product information API responses.
 *
 * <p>
 * Extends ProductSummary with additional details like full description, variant list, and multiple images.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: ProductDetail component schema (allOf ProductSummary)</li>
 * <li>Endpoint: GET /catalog/products/{productId}</li>
 * </ul>
 */
public class ProductDetail extends ProductSummary {

    @JsonProperty("longDescription")
    private String longDescription;

    @JsonProperty("images")
    private List<String> images;

    @JsonProperty("variants")
    private List<ProductVariantDto> variants;

    @JsonProperty("categories")
    private List<String> categories;

    // Getters and setters

    public String getLongDescription() {
        return longDescription;
    }

    public void setLongDescription(String longDescription) {
        this.longDescription = longDescription;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images;
    }

    public List<ProductVariantDto> getVariants() {
        return variants;
    }

    public void setVariants(List<ProductVariantDto> variants) {
        this.variants = variants;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }
}
