package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for Product.
 *
 * <p>
 * Represents a product in the catalog with full metadata including SEO, visibility windows, categories, and
 * collections. Used in API requests and responses.
 *
 * <p>
 * References:
 * <ul>
 * <li>Entity: {@link villagecompute.storefront.data.models.Product}</li>
 * <li>Task I2.T1: Catalog domain model DTO mapping</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductDto {

    public UUID id;

    @NotBlank
    @Size(
            max = 500)
    public String title;

    @NotBlank
    @Size(
            max = 255)
    @Pattern(
            regexp = "^[a-z0-9-]+$",
            message = "Slug must contain only lowercase letters, numbers, and hyphens")
    public String slug;

    @Size(
            max = 10000)
    public String description;

    @NotBlank
    @Pattern(
            regexp = "^(draft|active|archived|deleted)$")
    public String status;

    public String visibilityWindow; // JSON string: {start_date, end_date}

    public String seo; // JSON string: {title, description, keywords}

    public String categoryIds; // JSON array string of category UUIDs

    public String collectionIds; // JSON array string of collection UUIDs

    public String customAttributes; // JSON object for extensible metadata

    public Long version;

    public OffsetDateTime createdAt;

    public OffsetDateTime updatedAt;

    // Constructor
    public ProductDto() {
    }
}
