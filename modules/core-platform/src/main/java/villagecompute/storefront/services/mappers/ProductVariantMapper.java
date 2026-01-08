package villagecompute.storefront.services.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import villagecompute.storefront.api.types.ProductVariantDto;
import villagecompute.storefront.data.models.ProductVariant;

/**
 * MapStruct mapper for converting ProductVariant entities to DTOs.
 *
 * <p>
 * Maps variant pricing, SKU, and attributes. Stock information must be enriched separately via inventory service.
 *
 * <p>
 * References:
 * <ul>
 * <li>Entity: {@link ProductVariant}</li>
 * <li>DTO: {@link ProductVariantDto}</li>
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
    @Mapping(
            target = "price",
            expression = "java(new villagecompute.storefront.api.types.Money(variant.price, \"USD\"))")
    @Mapping(
            target = "stock",
            ignore = true) // Set separately in service via inventory lookup
    @Mapping(
            target = "options",
            ignore = true) // Set separately by parsing attributes JSON
    ProductVariantDto toDto(ProductVariant variant);
}
