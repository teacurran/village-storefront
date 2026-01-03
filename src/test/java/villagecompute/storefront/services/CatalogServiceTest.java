package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link CatalogService}.
 *
 * <p>
 * Tests cover CRUD operations, variant management, tenant isolation, and search functionality.
 */
@QuarkusTest
class CatalogServiceTest {

    @Inject
    CatalogService catalogService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID tenant2Id;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "catalogtest";
        tenant.name = "Catalog Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Create second tenant for isolation tests
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "catalogtest2";
        tenant2.name = "Catalog Test Tenant 2";
        tenant2.status = "active";
        tenant2.settings = "{}";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2);
        entityManager.flush();
        tenant2Id = tenant2.id;

        // Set current tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ========================================
    // Product CRUD Tests
    // ========================================

    @Test
    @Transactional
    void createProduct_shouldPersistWithTenantContext() {
        Product product = createTestProduct("WIDGET-001", "Test Widget");

        Product created = catalogService.createProduct(product);

        assertNotNull(created.id);
        assertEquals("WIDGET-001", created.sku);
        assertEquals("Test Widget", created.name);
        assertEquals(tenantId, created.tenant.id);
        assertNotNull(created.createdAt);
        assertNotNull(created.updatedAt);
    }

    @Test
    @Transactional
    void updateProduct_shouldModifyFields() {
        Product product = createAndPersistProduct("WIDGET-002", "Original Name");

        product.name = "Updated Name";
        product.description = "Updated description";
        Product updated = catalogService.updateProduct(product.id, product);

        assertEquals("Updated Name", updated.name);
        assertEquals("Updated description", updated.description);
        assertTrue(updated.updatedAt.isAfter(updated.createdAt));
    }

    @Test
    @Transactional
    void updateProduct_shouldFailForNonExistentProduct() {
        Product product = createTestProduct("WIDGET-003", "Test");
        UUID nonExistentId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> catalogService.updateProduct(nonExistentId, product));
    }

    @Test
    @Transactional
    void updateProduct_shouldFailWhenTenantMismatch() {
        Product product = createAndPersistProduct("WIDGET-003A", "Tenant One Product");

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "catalogtest2", "Catalog Test Tenant 2", "active"));

        assertThrows(IllegalArgumentException.class, () -> catalogService.updateProduct(product.id, product));

        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "catalogtest", "Catalog Test Tenant", "active"));
    }

    @Test
    @Transactional
    void getProduct_shouldReturnProductById() {
        Product product = createAndPersistProduct("WIDGET-004", "Test Product");

        Optional<Product> retrieved = catalogService.getProduct(product.id);

        assertTrue(retrieved.isPresent());
        assertEquals("WIDGET-004", retrieved.get().sku);
    }

    @Test
    @Transactional
    void getProduct_shouldReturnEmptyForWrongTenant() {
        // Create product in tenant2
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenant2Id, "catalogtest2", "Tenant 2", "active"));
        Product product = createAndPersistProduct("WIDGET-005", "Tenant 2 Product");
        UUID productId = product.id;

        // Switch back to tenant1
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "catalogtest", "Tenant 1", "active"));

        // Should not find product from tenant2
        Optional<Product> retrieved = catalogService.getProduct(productId);
        assertFalse(retrieved.isPresent());
    }

    @Test
    @Transactional
    void getProductBySku_shouldReturnProduct() {
        createAndPersistProduct("UNIQUE-SKU-001", "Test");

        Optional<Product> retrieved = catalogService.getProductBySku("UNIQUE-SKU-001");

        assertTrue(retrieved.isPresent());
        assertEquals("UNIQUE-SKU-001", retrieved.get().sku);
    }

    @Test
    @Transactional
    void listActiveProducts_shouldReturnPaginatedResults() {
        // Create multiple products
        for (int i = 1; i <= 15; i++) {
            Product p = createTestProduct("PROD-" + i, "Product " + i);
            p.status = "active";
            catalogService.createProduct(p);
        }

        List<Product> page1 = catalogService.listActiveProducts(0, 10);
        List<Product> page2 = catalogService.listActiveProducts(1, 10);

        assertEquals(10, page1.size());
        assertEquals(5, page2.size());
    }

    @Test
    @Transactional
    void searchProducts_shouldMatchNameAndSku() {
        createAndPersistProduct("WIDGET-100", "Red Widget");
        createAndPersistProduct("WIDGET-200", "Blue Widget");
        createAndPersistProduct("GADGET-300", "Red Gadget");

        List<Product> results = catalogService.searchProducts("red", 0, 10);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(p -> p.sku.equals("WIDGET-100")));
        assertTrue(results.stream().anyMatch(p -> p.sku.equals("GADGET-300")));
    }

    @Test
    @Transactional
    void deleteProduct_shouldSoftDelete() {
        Product product = createAndPersistProduct("DELETE-001", "To Delete");

        catalogService.deleteProduct(product.id);

        Product deleted = entityManager.find(Product.class, product.id);
        assertEquals("deleted", deleted.status);
    }

    // ========================================
    // Category Tests
    // ========================================

    @Test
    @Transactional
    void createCategory_shouldPersistWithTenantContext() {
        Category category = createTestCategory("ELEC", "Electronics");

        Category created = catalogService.createCategory(category);

        assertNotNull(created.id);
        assertEquals("ELEC", created.code);
        assertEquals(tenantId, created.tenant.id);
    }

    @Test
    @Transactional
    void getRootCategories_shouldReturnOnlyRootCategories() {
        Category root1 = createAndPersistCategory("ROOT1", "Root 1", null);
        Category root2 = createAndPersistCategory("ROOT2", "Root 2", null);
        createAndPersistCategory("CHILD1", "Child 1", root1.id);

        List<Category> roots = catalogService.getRootCategories();

        assertEquals(2, roots.size());
        assertTrue(roots.stream().anyMatch(c -> c.code.equals("ROOT1")));
        assertTrue(roots.stream().anyMatch(c -> c.code.equals("ROOT2")));
    }

    @Test
    @Transactional
    void getChildCategories_shouldReturnOnlyChildrenOfParent() {
        Category parent = createAndPersistCategory("PARENT", "Parent", null);
        createAndPersistCategory("CHILD1", "Child 1", parent.id);
        createAndPersistCategory("CHILD2", "Child 2", parent.id);
        createAndPersistCategory("OTHER", "Other", null);

        List<Category> children = catalogService.getChildCategories(parent.id);

        assertEquals(2, children.size());
        assertTrue(children.stream().allMatch(c -> c.parent != null && c.parent.id.equals(parent.id)));
    }

    // ========================================
    // Variant Tests
    // ========================================

    @Test
    @Transactional
    void createVariant_shouldPersistWithTenantContext() {
        Product product = createAndPersistProduct("PROD-VAR-001", "Product with Variants");
        ProductVariant variant = createTestVariant(product, "VAR-001", "Red Variant", new BigDecimal("29.99"));

        ProductVariant created = catalogService.createVariant(variant);

        assertNotNull(created.id);
        assertEquals("VAR-001", created.sku);
        assertEquals(tenantId, created.tenant.id);
        assertEquals(0, created.price.compareTo(new BigDecimal("29.99")));
    }

    @Test
    @Transactional
    void getProductVariants_shouldReturnAllVariantsForProduct() {
        Product product = createAndPersistProduct("PROD-MULTI-VAR", "Multi Variant Product");
        ProductVariant v1 = createTestVariant(product, "VAR-RED", "Red", new BigDecimal("19.99"));
        ProductVariant v2 = createTestVariant(product, "VAR-BLUE", "Blue", new BigDecimal("19.99"));
        catalogService.createVariant(v1);
        catalogService.createVariant(v2);

        List<ProductVariant> variants = catalogService.getProductVariants(product.id);

        assertEquals(2, variants.size());
    }

    @Test
    @Transactional
    void getVariantBySku_shouldReturnVariant() {
        Product product = createAndPersistProduct("PROD-VAR-002", "Product");
        ProductVariant variant = createTestVariant(product, "UNIQUE-VAR-SKU", "Test Variant", new BigDecimal("9.99"));
        catalogService.createVariant(variant);

        Optional<ProductVariant> retrieved = catalogService.getVariantBySku("UNIQUE-VAR-SKU");

        assertTrue(retrieved.isPresent());
        assertEquals("UNIQUE-VAR-SKU", retrieved.get().sku);
    }

    // ========================================
    // Helper Methods
    // ========================================

    private Product createTestProduct(String sku, String name) {
        Product product = new Product();
        product.sku = sku;
        product.name = name;
        product.slug = name.toLowerCase().replace(" ", "-");
        product.description = "Test description for " + name;
        product.type = "physical";
        product.status = "active";
        product.metadata = "{}";
        return product;
    }

    @Transactional
    Product createAndPersistProduct(String sku, String name) {
        Product product = createTestProduct(sku, name);
        return catalogService.createProduct(product);
    }

    private Category createTestCategory(String code, String name) {
        Category category = new Category();
        category.code = code;
        category.name = name;
        category.slug = name.toLowerCase().replace(" ", "-");
        category.status = "active";
        category.displayOrder = 0;
        return category;
    }

    @Transactional
    Category createAndPersistCategory(String code, String name, UUID parentId) {
        Category category = createTestCategory(code, name);
        if (parentId != null) {
            category.parent = entityManager.getReference(Category.class, parentId);
        }
        return catalogService.createCategory(category);
    }

    private ProductVariant createTestVariant(Product product, String sku, String name, BigDecimal price) {
        ProductVariant variant = new ProductVariant();
        variant.product = product;
        variant.sku = sku;
        variant.name = name;
        variant.price = price;
        variant.requiresShipping = true;
        variant.taxable = true;
        variant.position = 0;
        variant.status = "active";
        variant.attributes = "{}";
        return variant;
    }
}
