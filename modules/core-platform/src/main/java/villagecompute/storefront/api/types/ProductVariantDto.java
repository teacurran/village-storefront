package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * ProductVariant DTO for API requests and responses.
 *
 * <p>
 * Represents a product variant with SKU, pricing, inventory policy, and option values (e.g., color, size).
 *
 * <p>
 * References:
 * <ul>
 * <li>Entity: {@link villagecompute.storefront.data.models.ProductVariant}</li>
 * <li>Task I2.T1: Catalog domain model DTO mapping</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductVariantDto {

    public UUID id;

    @NotBlank
    @Size(
            max = 100)
    public String sku;

    @Size(
            max = 100)
    public String barcode;

    public String optionValues; // JSON string: {"color": "Red", "size": "Large"}

    @NotNull public BigDecimal price;

    public BigDecimal compareAtPrice;

    @Pattern(
            regexp = "^(continue|deny)$",
            message = "Inventory policy must be 'continue' or 'deny'")
    public String inventoryPolicy;

    public BigDecimal weight;

    public String dimensions; // JSON string: {length, width, height}

    public String mediaIds; // JSON array string of MediaAsset UUIDs

    public Long version;

    public OffsetDateTime createdAt;

    public OffsetDateTime updatedAt;

    // Constructor
    public ProductVariantDto() {
    }
}
