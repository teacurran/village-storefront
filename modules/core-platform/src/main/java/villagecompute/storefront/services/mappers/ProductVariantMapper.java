package villagecompute.storefront.services.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import villagecompute.storefront.api.types.ProductVariantDto;
import villagecompute.storefront.data.models.ProductVariant;

/**
 * MapStruct mapper for converting ProductVariant entities to/from DTOs.
 *
 * <p>
 * Maps variant SKU, pricing, option values, inventory policy, dimensions, and media references.
 *
 * <p>
 * References:
 * <ul>
 * <li>Entity: {@link ProductVariant}</li>
 * <li>DTO: {@link ProductVariantDto}</li>
 * <li>Task I2.T1: Catalog domain model DTO mapping</li>
 * </ul>
 */
@Mapper(
        componentModel = "cdi")
public interface ProductVariantMapper {

    /**
     * Map ProductVariant entity to ProductVariantDto.
     *
     * @param variant
     *            source entity
     * @return ProductVariantDto
     */
    ProductVariantDto toDto(ProductVariant variant);

    /**
     * Map ProductVariantDto to ProductVariant entity.
     *
     * @param dto
     *            source DTO
     * @return ProductVariant entity
     */
    @Mapping(
            target = "product",
            ignore = true) // Set separately in service
    @Mapping(
            target = "tenant",
            ignore = true) // Set by @PrePersist
    @Mapping(
            target = "createdAt",
            ignore = true)
    @Mapping(
            target = "updatedAt",
            ignore = true)
    @Mapping(
            target = "name",
            expression = "java(defaultVariantName(dto))")
    ProductVariant toEntity(ProductVariantDto dto);

    /**
     * Update existing ProductVariant entity from DTO (for PATCH/PUT operations).
     *
     * @param dto
     *            source DTO with updated fields
     * @param variant
     *            target entity to update
     */
    @Mapping(
            target = "id",
            ignore = true)
    @Mapping(
            target = "product",
            ignore = true)
    @Mapping(
            target = "tenant",
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
    @Mapping(
            target = "name",
            ignore = true)
    void updateEntityFromDto(ProductVariantDto dto, @MappingTarget ProductVariant variant);

    /**
     * Derive a variant name from the DTO-visible fields.
     */
    default String defaultVariantName(ProductVariantDto dto) {
        if (dto == null || dto.sku == null || dto.sku.isBlank()) {
            return "UNSPECIFIED-VARIANT";
        }
        return dto.sku.trim();
    }
}
