package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for Collection.
 *
 * <p>
 * Represents a product collection for merchandising and promotional grouping. Used in API requests and responses.
 *
 * <p>
 * References:
 * <ul>
 * <li>Entity: {@link villagecompute.storefront.data.models.Collection}</li>
 * <li>OpenAPI: api/v1/openapi.yaml (catalog schemas)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CollectionDto {

    public UUID id;

    @NotBlank
    @Size(
            max = 50)
    public String code;

    @NotBlank
    @Size(
            max = 255)
    public String name;

    @Size(
            max = 255)
    @Pattern(
            regexp = "^[a-z0-9-]+$",
            message = "Slug must contain only lowercase letters, numbers, and hyphens")
    public String slug;

    @Size(
            max = 5000)
    public String description;

    @Size(
            max = 500)
    public String imageUrl;

    public Integer displayOrder;

    @NotBlank
    @Pattern(
            regexp = "^(manual|automatic)$")
    public String collectionType;

    public String selectionRules; // JSON string

    public Boolean published;

    public OffsetDateTime publishedAt;

    @NotBlank
    @Pattern(
            regexp = "^(draft|active|archived|deleted)$")
    public String status;

    @Size(
            max = 255)
    public String seoTitle;

    @Size(
            max = 1000)
    public String seoDescription;

    public Long version;

    public OffsetDateTime createdAt;

    public OffsetDateTime updatedAt;

    // Constructor
    public CollectionDto() {
    }
}
