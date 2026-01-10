package villagecompute.storefront.services.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import villagecompute.storefront.api.types.CollectionDto;
import villagecompute.storefront.data.models.Collection;

/**
 * MapStruct mapper for converting Collection entities to/from DTOs.
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
public interface CollectionMapper {

    /**
     * Map Collection entity to CollectionDto.
     *
     * @param collection
     *            source entity
     * @return CollectionDto
     */
    CollectionDto toDto(Collection collection);

    /**
     * Map CollectionDto to Collection entity.
     *
     * @param dto
     *            source DTO
     * @return Collection entity
     */
    @Mapping(
            target = "tenant",
            ignore = true) // Set by @PrePersist
    @Mapping(
            target = "createdAt",
            ignore = true)
    @Mapping(
            target = "updatedAt",
            ignore = true)
    Collection toEntity(CollectionDto dto);

    /**
     * Update existing Collection entity from DTO (for PATCH/PUT operations).
     *
     * @param dto
     *            source DTO with updated fields
     * @param collection
     *            target entity to update
     */
    @Mapping(
            target = "id",
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
    void updateEntityFromDto(CollectionDto dto, @MappingTarget Collection collection);
}
