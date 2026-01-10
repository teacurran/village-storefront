package villagecompute.storefront.services.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

import villagecompute.storefront.api.types.Money;
import villagecompute.storefront.api.types.ProductDetail;
import villagecompute.storefront.api.types.ProductDto;
import villagecompute.storefront.api.types.ProductSummary;
import villagecompute.storefront.data.models.Product;

/**
 * MapStruct mapper for converting Product entities to/from DTOs.
 *
 * <p>
 * Uses CDI component model for dependency injection. Automatically generates implementation at compile time.
 *
 * <p>
 * References:
 * <ul>
 * <li>MapStruct: https://mapstruct.org/</li>
 * <li>Task I2.T1: DTO mapping requirements</li>
 * </ul>
 */
@Mapper(
        componentModel = "cdi",
        uses = {ProductVariantMapper.class})
public interface ProductMapper {

    /**
     * Map Product entity to ProductSummary DTO (legacy API).
     *
     * @param product
     *            source entity
     * @return ProductSummary DTO
     */
    @Mapping(
            target = "price",
            source = "product",
            qualifiedByName = "getProductPrice")
    @Mapping(
            target = "imageUrl",
            ignore = true) // Set separately in service
    @Mapping(
            target = "inStock",
            ignore = true) // Set separately in service
    ProductSummary toSummary(Product product);

    /**
     * Map Product entity to ProductDetail DTO (legacy API).
     *
     * @param product
     *            source entity
     * @return ProductDetail DTO
     */
    @Mapping(
            target = "price",
            source = "product",
            qualifiedByName = "getProductPrice")
    @Mapping(
            target = "longDescription",
            source = "description")
    @Mapping(
            target = "imageUrl",
            ignore = true) // Set separately in service
    @Mapping(
            target = "inStock",
            ignore = true) // Set separately in service
    @Mapping(
            target = "images",
            ignore = true) // Set separately in service
    @Mapping(
            target = "variants",
            ignore = true) // Set separately in service
    @Mapping(
            target = "categories",
            ignore = true) // Set separately in service
    ProductDetail toDetail(Product product);

    /**
     * Map Product entity to ProductDto.
     *
     * @param product
     *            source entity
     * @return ProductDto
     */
    ProductDto toDto(Product product);

    /**
     * Map ProductDto to Product entity.
     *
     * @param dto
     *            source DTO
     * @return Product entity
     */
    @Mapping(
            target = "tenant",
            ignore = true) // Set by @PrePersist
    @Mapping(
            target = "variants",
            ignore = true) // Managed separately
    @Mapping(
            target = "createdAt",
            ignore = true)
    @Mapping(
            target = "updatedAt",
            ignore = true)
    Product toEntity(ProductDto dto);

    /**
     * Update existing Product entity from DTO (for PATCH/PUT operations).
     *
     * @param dto
     *            source DTO with updated fields
     * @param product
     *            target entity to update
     */
    @Mapping(
            target = "id",
            ignore = true)
    @Mapping(
            target = "tenant",
            ignore = true)
    @Mapping(
            target = "variants",
            ignore = true)
    @Mapping(
            target = "createdAt",
            ignore = true)
    @Mapping(
            target = "updatedAt",
            ignore = true)
    @Mapping(
            target = "version",
            ignore = true)
    void updateEntityFromDto(ProductDto dto, @MappingTarget Product product);

    /**
     * Get product base price from the first active variant (legacy helper).
     *
     * <p>
     * Note: This is a simplified approach. Real implementation should have a pricing service or store base price on
     * Product entity.
     *
     * @param product
     *            source product
     * @return Money object with price from first variant or zero
     */
    @Named("getProductPrice")
    default Money getProductPrice(Product product) {
        // Simplified: return USD 0.00 as placeholder
        // Real implementation would fetch from variants or pricing service
        return new Money("0.00", "USD");
    }
}
