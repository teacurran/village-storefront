package villagecompute.storefront.services.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Collection;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.ValidationException;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests for {@link CatalogValidator}.
 *
 * <p>
 * Tests cover status transition validation, slug uniqueness, and format validation.
 */
@QuarkusTest
class CatalogValidatorTest {

    @Inject
    CatalogValidator validator;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;

    @BeforeEach
    @Transactional
    void setUp() {
        purgeCatalogTables();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "validatortest";
        tenant.name = "Validator Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));
    }

    @AfterEach
    @Transactional
    void tearDown() {
        TenantContext.clear();
        purgeCatalogTables();
    }

    // ========================================
    // Status Transition Tests
    // ========================================

    @Test
    void testValidStatusTransition_DraftToActive() {
        assertDoesNotThrow(() -> validator.validateStatusTransition("draft", "active"));
    }

    @Test
    void testValidStatusTransition_ActiveToArchived() {
        assertDoesNotThrow(() -> validator.validateStatusTransition("active", "archived"));
    }

    @Test
    void testValidStatusTransition_ArchivedToDeleted() {
        assertDoesNotThrow(() -> validator.validateStatusTransition("archived", "deleted"));
    }

    @Test
    void testInvalidStatusTransition_ActiveToDraft() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateStatusTransition("active", "draft"));
        assertEquals("Cannot revert active entity to draft. Use archived status instead.", exception.getMessage());
    }

    @Test
    void testInvalidStatusTransition_ActiveToDeleted() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateStatusTransition("active", "deleted"));
        assertEquals("Can only delete archived entities. Archive first, then delete.", exception.getMessage());
    }

    @Test
    void testInvalidStatusTransition_DeletedEntity() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateStatusTransition("deleted", "active"));
        assertEquals("Cannot change status of deleted entity", exception.getMessage());
    }

    @Test
    void testInvalidStatus() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateStatusTransition("draft", "invalid_status"));
        assertTrue(exception.getMessage().contains("Invalid status"));
    }

    // ========================================
    // Slug Uniqueness Tests
    // ========================================

    @Test
    @Transactional
    void testProductSlugUniqueness_NewSlugIsUnique() {
        assertDoesNotThrow(() -> validator.validateProductSlugUniqueness("new-unique-slug", null));
    }

    @Test
    @Transactional
    void testProductSlugUniqueness_DuplicateSlug() {
        // Create product with slug
        Product product = new Product();
        product.tenant = entityManager.find(Tenant.class, tenantId);
        product.sku = "TEST-SKU-001";
        product.name = "Test Product";
        product.slug = "existing-product-slug";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);
        entityManager.flush();

        // Try to create another product with same slug
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateProductSlugUniqueness("existing-product-slug", null));
        assertEquals("Product slug 'existing-product-slug' is already in use", exception.getMessage());
    }

    @Test
    @Transactional
    void testProductSlugUniqueness_SameEntityUpdate() {
        // Create product with slug
        Product product = new Product();
        product.tenant = entityManager.find(Tenant.class, tenantId);
        product.sku = "TEST-SKU-002";
        product.name = "Test Product 2";
        product.slug = "product-to-update";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);
        entityManager.flush();

        // Updating same entity with its own slug should be allowed
        assertDoesNotThrow(() -> validator.validateProductSlugUniqueness("product-to-update", product.id));
    }

    @Test
    @Transactional
    void testCategorySlugUniqueness_DuplicateSlug() {
        // Create category with slug
        Category category = new Category();
        category.tenant = entityManager.find(Tenant.class, tenantId);
        category.code = "TEST-CAT-001";
        category.name = "Test Category";
        category.slug = "existing-category-slug";
        category.status = "active";
        category.createdAt = OffsetDateTime.now();
        category.updatedAt = OffsetDateTime.now();
        entityManager.persist(category);
        entityManager.flush();

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateCategorySlugUniqueness("existing-category-slug", null));
        assertEquals("Category slug 'existing-category-slug' is already in use", exception.getMessage());
    }

    @Test
    @Transactional
    void testCollectionSlugUniqueness_DuplicateSlug() {
        // Create collection with slug
        Collection collection = new Collection();
        collection.tenant = entityManager.find(Tenant.class, tenantId);
        collection.code = "TEST-COLL-001";
        collection.name = "Test Collection";
        collection.slug = "existing-collection-slug";
        collection.status = "draft";
        collection.createdAt = OffsetDateTime.now();
        collection.updatedAt = OffsetDateTime.now();
        entityManager.persist(collection);
        entityManager.flush();

        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateCollectionSlugUniqueness("existing-collection-slug", null));
        assertEquals("Collection slug 'existing-collection-slug' is already in use", exception.getMessage());
    }

    // ========================================
    // Slug Format Tests
    // ========================================

    @Test
    void testSlugFormat_Valid() {
        assertDoesNotThrow(() -> validator.validateSlugFormat("valid-slug-123"));
        assertDoesNotThrow(() -> validator.validateSlugFormat("simple"));
        assertDoesNotThrow(() -> validator.validateSlugFormat("multi-word-slug"));
        assertDoesNotThrow(() -> validator.validateSlugFormat("slug-with-123-numbers"));
    }

    @Test
    void testSlugFormat_InvalidCharacters() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlugFormat("Invalid_Slug"));
        assertEquals("Slug must contain only lowercase letters, numbers, and hyphens", exception.getMessage());

        assertThrows(ValidationException.class, () -> validator.validateSlugFormat("UPPERCASE"));
        assertThrows(ValidationException.class, () -> validator.validateSlugFormat("slug with spaces"));
        assertThrows(ValidationException.class, () -> validator.validateSlugFormat("slug@special"));
    }

    @Test
    void testSlugFormat_StartsOrEndsWithHyphen() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlugFormat("-starts-with-hyphen"));
        assertEquals("Slug cannot start or end with a hyphen", exception.getMessage());

        exception = assertThrows(ValidationException.class, () -> validator.validateSlugFormat("ends-with-hyphen-"));
        assertEquals("Slug cannot start or end with a hyphen", exception.getMessage());
    }

    @Test
    void testSlugFormat_ConsecutiveHyphens() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateSlugFormat("double--hyphen"));
        assertEquals("Slug cannot contain consecutive hyphens", exception.getMessage());
    }

    @Test
    void testSlugFormat_NullOrEmpty() {
        // Null and empty slugs should be allowed (slugs are optional)
        assertDoesNotThrow(() -> validator.validateSlugFormat(null));
        assertDoesNotThrow(() -> validator.validateSlugFormat(""));
        assertDoesNotThrow(() -> validator.validateSlugFormat("   "));
    }

    // ========================================
    // Type Validation Tests
    // ========================================

    @Test
    void testProductType_Valid() {
        assertDoesNotThrow(() -> validator.validateProductType("physical"));
        assertDoesNotThrow(() -> validator.validateProductType("digital"));
        assertDoesNotThrow(() -> validator.validateProductType("service"));
    }

    @Test
    void testProductType_Invalid() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateProductType("invalid_type"));
        assertTrue(exception.getMessage().contains("Invalid product type"));
    }

    @Test
    void testCollectionType_Valid() {
        assertDoesNotThrow(() -> validator.validateCollectionType("manual"));
        assertDoesNotThrow(() -> validator.validateCollectionType("automatic"));
    }

    @Test
    void testCollectionType_Invalid() {
        ValidationException exception = assertThrows(ValidationException.class,
                () -> validator.validateCollectionType("invalid_type"));
        assertTrue(exception.getMessage().contains("Invalid collection type"));
    }

    private void purgeCatalogTables() {
        boolean integrityDisabled = false;
        try {
            if (!isPostgres()) {
                entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
                integrityDisabled = true;
            }
            entityManager.createQuery("DELETE FROM ProductCollection").executeUpdate();
            entityManager.createQuery("DELETE FROM Collection").executeUpdate();
            entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
            entityManager.createQuery("DELETE FROM Cart").executeUpdate();
            entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
            entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
            entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
            entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
            entityManager.createQuery("DELETE FROM InventoryAdjustment").executeUpdate();
            entityManager.createQuery("DELETE FROM InventoryTransferLine").executeUpdate();
            entityManager.createQuery("DELETE FROM InventoryTransfer").executeUpdate();
            entityManager.createQuery("DELETE FROM InventoryAgingAggregate").executeUpdate();
            entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
            entityManager.createQuery("DELETE FROM InventoryLocation").executeUpdate();
            entityManager.createQuery("DELETE FROM ProductImage").executeUpdate();
            entityManager.createQuery("DELETE FROM ProductCategory").executeUpdate();
            entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
            entityManager.createQuery("DELETE FROM Product").executeUpdate();
            entityManager.createQuery("DELETE FROM Category").executeUpdate();
            entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
            entityManager.createQuery("DELETE FROM IdempotencyKey").executeUpdate();
            entityManager.createQuery("DELETE FROM PaymentIntent").executeUpdate();
            entityManager.createQuery("DELETE FROM User").executeUpdate();
            entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        } finally {
            if (integrityDisabled) {
                entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
            }
        }
    }

    private boolean isPostgres() {
        return ConfigProvider.getConfig()
                .getOptionalValue("quarkus.datasource.db-kind", String.class)
                .map(value -> "postgresql".equalsIgnoreCase(value))
                .orElse(false);
    }
}
