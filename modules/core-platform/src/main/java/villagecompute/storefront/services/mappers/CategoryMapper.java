package villagecompute.storefront.services.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import villagecompute.storefront.api.types.CategoryDto;
import villagecompute.storefront.data.models.Category;

/**
 * MapStruct mapper for converting Category entities to/from DTOs.
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
        componentModel = "cdi")
public interface CategoryMapper {

    /**
     * Map Category entity to CategoryDto.
     *
     * @param category
     *            source entity
     * @return CategoryDto
     */
    @Mapping(
            target = "parentId",
            source = "parent.id")
    CategoryDto toDto(Category category);

    /**
     * Map CategoryDto to Category entity.
     *
     * @param dto
     *            source DTO
     * @return Category entity
     */
    @Mapping(
            target = "tenant",
            ignore = true) // Set by @PrePersist
    @Mapping(
            target = "parent",
            ignore = true) // Set separately in service
    @Mapping(
            target = "createdAt",
            ignore = true)
    @Mapping(
            target = "updatedAt",
            ignore = true)
    Category toEntity(CategoryDto dto);

    /**
     * Update existing Category entity from DTO (for PATCH/PUT operations).
     *
     * @param dto
     *            source DTO with updated fields
     * @param category
     *            target entity to update
     */
    @Mapping(
            target = "id",
            ignore = true)
    @Mapping(
            target = "tenant",
            ignore = true)
    @Mapping(
            target = "parent",
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
    void updateEntityFromDto(CategoryDto dto, @MappingTarget Category category);
}
