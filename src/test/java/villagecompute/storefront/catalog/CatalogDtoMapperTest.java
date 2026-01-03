package villagecompute.storefront.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import villagecompute.storefront.api.types.Money;
import villagecompute.storefront.api.types.PaginationMetadata;
import villagecompute.storefront.api.types.ProductDetail;
import villagecompute.storefront.api.types.ProductSummary;
import villagecompute.storefront.api.types.ProductVariantDto;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductCategory;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.services.mappers.ProductMapper;
import villagecompute.storefront.services.mappers.ProductVariantMapper;

/**
 * Unit tests covering DTO value objects and MapStruct mappers so that jacoco enforces the 80% coverage gate.
 *
 * <p>
 * These tests validate simple behaviors (getters/setters, derived values, mapper wiring) without requiring CDI.
 */
class CatalogDtoMapperTest {

    private final ProductMapper productMapper = Mappers.getMapper(ProductMapper.class);
    private final ProductVariantMapper productVariantMapper = Mappers.getMapper(ProductVariantMapper.class);

    @Test
    void moneyValueObjectSupportsDecimalRoundTrip() {
        Money money = new Money(new BigDecimal("12.34"), "USD");
        assertEquals("12.34", money.getAmount());
        assertEquals("USD", money.getCurrency());
        assertEquals(new BigDecimal("12.34"), money.getAmountAsDecimal());

        money.setAmount("99.99");
        money.setCurrency("CAD");

        assertEquals("99.99", money.getAmount());
        assertEquals("CAD", money.getCurrency());
        assertEquals(new BigDecimal("99.99"), money.getAmountAsDecimal());
    }

    @Test
    void paginationMetadataCalculatesTotalPages() {
        PaginationMetadata metadata = new PaginationMetadata(2, 25, 120L);
        assertEquals(5, metadata.getTotalPages());
        assertEquals(2, metadata.getPage());
        assertEquals(25, metadata.getPageSize());
        assertEquals(120L, metadata.getTotalItems());

        metadata.setPage(3);
        metadata.setPageSize(30);
        metadata.setTotalItems(180L);
        metadata.setTotalPages(6);

        assertEquals(3, metadata.getPage());
        assertEquals(30, metadata.getPageSize());
        assertEquals(180L, metadata.getTotalItems());
        assertEquals(6, metadata.getTotalPages());
    }

    @Test
    void productDtoAccessorsWorkAsExpected() {
        UUID variantId = UUID.randomUUID();
        ProductVariantDto variantDto = new ProductVariantDto();
        variantDto.setId(variantId);
        variantDto.setSku("VAR-001");
        variantDto.setPrice(new Money("45.00", "USD"));
        variantDto.setStock(12);
        variantDto.setOptions(Map.of("color", "red", "size", "M"));

        assertEquals(variantId, variantDto.getId());
        assertEquals("VAR-001", variantDto.getSku());
        assertEquals("45.00", variantDto.getPrice().getAmount());
        assertEquals(12, variantDto.getStock());
        assertEquals("red", variantDto.getOptions().get("color"));

        ProductDetail detail = new ProductDetail();
        detail.setId(UUID.randomUUID());
        detail.setSku("SKU-123");
        detail.setName("Demo Product");
        detail.setDescription("Short description");
        detail.setLongDescription("Long form description");
        detail.setStatus("active");
        detail.setPrice(new Money("59.00", "USD"));
        detail.setImageUrl("https://cdn/images/hero.jpg");
        detail.setImages(List.of("img-1", "img-2"));
        detail.setCategories(List.of("Electronics"));
        detail.setVariants(List.of(variantDto));
        detail.setInStock(true);

        assertEquals("Demo Product", detail.getName());
        assertEquals("Long form description", detail.getLongDescription());
        assertEquals("https://cdn/images/hero.jpg", detail.getImageUrl());
        assertEquals(1, detail.getVariants().size());
        assertTrue(detail.getInStock());
    }

    @Test
    void productMapperProducesSummaryAndDetailDtos() {
        Product product = new Product();
        product.id = UUID.randomUUID();
        product.sku = "SKU-MAP-001";
        product.name = "Mapper Product";
        product.description = "Mapper description";
        product.status = "active";

        ProductSummary summary = productMapper.toSummary(product);
        assertNotNull(summary);
        assertEquals(product.sku, summary.getSku());
        assertEquals(product.name, summary.getName());
        assertEquals("0.00", summary.getPrice().getAmount(), "Default price placeholder should be injected");

        ProductDetail detail = productMapper.toDetail(product);
        assertNotNull(detail);
        assertEquals(product.description, detail.getLongDescription());
        assertEquals(summary.getPrice().getAmount(), detail.getPrice().getAmount());
    }

    @Test
    void productVariantMapperMapsBasicFields() {
        ProductVariant variant = new ProductVariant();
        variant.id = UUID.randomUUID();
        variant.sku = "VAR-MAP-123";
        variant.price = new BigDecimal("19.95");

        ProductVariantDto dto = productVariantMapper.toDto(variant);
        assertNotNull(dto);
        assertEquals(variant.id, dto.getId());
        assertEquals("VAR-MAP-123", dto.getSku());
        assertEquals("19.95", dto.getPrice().getAmount());
    }

    @Test
    void productCategoryIdImplementsEqualityAndHashCode() {
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();

        ProductCategory.ProductCategoryId first = new ProductCategory.ProductCategoryId(tenantId, productId,
                categoryId);
        ProductCategory.ProductCategoryId second = new ProductCategory.ProductCategoryId(tenantId, productId,
                categoryId);
        ProductCategory.ProductCategoryId different = new ProductCategory.ProductCategoryId(tenantId, productId,
                UUID.randomUUID());

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotSame(first, second);
        assertNotNull(first.toString());
        assertTrue(first.equals(second));
        assertNotEquals(first, different);
    }
}
