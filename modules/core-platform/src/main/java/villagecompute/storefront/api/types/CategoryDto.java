package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for Category.
 *
 * <p>
 * Represents a product category in the catalog hierarchy. Used in API requests and responses.
 *
 * <p>
 * References:
 * <ul>
 * <li>Entity: {@link villagecompute.storefront.data.models.Category}</li>
 * <li>OpenAPI: api/v1/openapi.yaml (catalog schemas)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CategoryDto {

    public UUID id;

    public UUID parentId;

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

    public Integer displayOrder;

    @NotBlank
    @Pattern(
            regexp = "^(draft|active|archived|deleted)$")
    public String status;

    public Long version;

    public OffsetDateTime createdAt;

    public OffsetDateTime updatedAt;

    // Constructor
    public CategoryDto() {
    }
}
