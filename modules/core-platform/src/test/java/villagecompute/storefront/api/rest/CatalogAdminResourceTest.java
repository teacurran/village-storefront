package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Collection;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for {@link CatalogAdminResource}.
 *
 * <p>
 * Tests cover HTTP contract compliance, tenant isolation, and catalog admin API functionality.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T3: Catalog endpoint integration tests with tenant isolation verification</li>
 * <li>OpenAPI: /admin/catalog/** endpoint specifications</li>
 * </ul>
 */
@QuarkusTest
class CatalogAdminResourceTest {

    @Inject
    EntityManager entityManager;

    private String tenantSubdomain;
    private String otherTenantSubdomain;
    private UUID tenantId;
    private UUID categoryId;
    private UUID otherTenantCategoryId;
    private UUID collectionId;
    private UUID otherTenantCollectionId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM ProductCategory").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductCollection").executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Collection").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant 1
        Tenant tenant = new Tenant();
        tenant.subdomain = "catalogadmintest1";
        tenant.name = "Catalog Admin Test Tenant 1";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantSubdomain = tenant.subdomain;
        tenantId = tenant.id;

        // Set tenant context for setup
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

        // Create test category for tenant 1
        Category category = new Category();
        category.tenant = tenant;
        category.code = "TEST-CAT-1";
        category.name = "Test Category 1";
        category.slug = "test-category-1";
        category.status = "active";
        category.displayOrder = 1;
        category.createdAt = OffsetDateTime.now();
        category.updatedAt = OffsetDateTime.now();
        entityManager.persist(category);
        entityManager.flush();
        categoryId = category.id;

        // Create test collection for tenant 1
        Collection collection = new Collection();
        collection.tenant = tenant;
        collection.code = "TEST-COL-1";
        collection.name = "Test Collection 1";
        collection.slug = "test-collection-1";
        collection.status = "active";
        collection.collectionType = "manual";
        collection.published = true;
        collection.displayOrder = 1;
        collection.createdAt = OffsetDateTime.now();
        collection.updatedAt = OffsetDateTime.now();
        entityManager.persist(collection);
        entityManager.flush();
        collectionId = collection.id;

        // Clear tenant context
        TenantContext.clear();

        // Create test tenant 2 (for tenant isolation tests)
        Tenant otherTenant = new Tenant();
        otherTenant.subdomain = "catalogadmintest2";
        otherTenant.name = "Catalog Admin Test Tenant 2";
        otherTenant.status = "active";
        otherTenant.settings = "{}";
        otherTenant.createdAt = OffsetDateTime.now();
        otherTenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherTenant);
        entityManager.flush();
        otherTenantSubdomain = otherTenant.subdomain;

        // Set tenant context for other tenant setup
        TenantContext.setCurrentTenant(
                new TenantInfo(otherTenant.id, otherTenant.subdomain, otherTenant.name, otherTenant.status));

        // Create test category for tenant 2
        Category otherCategory = new Category();
        otherCategory.tenant = otherTenant;
        otherCategory.code = "TEST-CAT-2";
        otherCategory.name = "Test Category 2";
        otherCategory.slug = "test-category-2";
        otherCategory.status = "active";
        otherCategory.displayOrder = 1;
        otherCategory.createdAt = OffsetDateTime.now();
        otherCategory.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherCategory);
        entityManager.flush();
        otherTenantCategoryId = otherCategory.id;

        // Create test collection for tenant 2
        Collection otherCollection = new Collection();
        otherCollection.tenant = otherTenant;
        otherCollection.code = "TEST-COL-2";
        otherCollection.name = "Test Collection 2";
        otherCollection.slug = "test-collection-2";
        otherCollection.status = "active";
        otherCollection.collectionType = "manual";
        otherCollection.published = true;
        otherCollection.displayOrder = 1;
        otherCollection.createdAt = OffsetDateTime.now();
        otherCollection.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherCollection);
        entityManager.flush();
        otherTenantCollectionId = otherCollection.id;

        // Clear tenant context after setup
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RequestSpecification request(String subdomain) {
        return given().header("Host", subdomain + ".villagecompute.com").contentType(ContentType.JSON);
    }

    // ========================================
    // GET /admin/catalog/categories Tests
    // ========================================

    @Test
    void listCategories_shouldReturnCategories() {
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/categories").then().statusCode(200).body("$",
                notNullValue());
    }

    @Test
    void listCategories_shouldIsolateTenants() {
        // Request from tenant 1 - should only see tenant 1 categories
        var response1 = request(tenantSubdomain).when().get("/api/v1/admin/catalog/categories").then().statusCode(200)
                .extract().jsonPath();
        var categories1 = response1.getList("$");

        // Request from tenant 2 - should only see tenant 2 categories
        var response2 = request(otherTenantSubdomain).when().get("/api/v1/admin/catalog/categories").then()
                .statusCode(200).extract().jsonPath();
        var categories2 = response2.getList("$");

        // Each tenant should have their own isolated category list
    }

    @Test
    void getCategory_shouldReturnCategoryDetails() {
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/categories/" + categoryId).then().statusCode(200)
                .body("id", equalTo(categoryId.toString())).body("name", equalTo("Test Category 1"))
                .body("code", equalTo("TEST-CAT-1"));
    }

    @Test
    void getCategory_shouldReturn404ForNonExistentCategory() {
        UUID nonExistentId = UUID.randomUUID();
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/categories/" + nonExistentId).then().statusCode(404)
                .body("title", equalTo("Category Not Found")).body("status", equalTo(404));
    }

    @Test
    void getCategory_shouldReturn404ForOtherTenantCategory() {
        // Attempt to access tenant 2's category from tenant 1 context
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/categories/" + otherTenantCategoryId).then()
                .statusCode(404).body("title", equalTo("Category Not Found"));
    }

    @Test
    void createCategory_shouldCreateSuccessfully() {
        String newCategory = """
                {
                  "code": "NEW-CAT",
                  "name": "New Category",
                  "slug": "new-category",
                  "status": "active",
                  "displayOrder": 2
                }
                """;

        request(tenantSubdomain).body(newCategory).when().post("/api/v1/admin/catalog/categories").then()
                .statusCode(201).body("code", equalTo("NEW-CAT")).body("name", equalTo("New Category"));
    }

    @Test
    void createCategory_shouldReturn409ForDuplicateCode() {
        String duplicateCategory = """
                {
                  "code": "TEST-CAT-1",
                  "name": "Duplicate Category",
                  "slug": "duplicate-category",
                  "status": "active"
                }
                """;

        request(tenantSubdomain).body(duplicateCategory).when().post("/api/v1/admin/catalog/categories").then()
                .statusCode(409).body("title", equalTo("Duplicate Category Code"));
    }

    // ========================================
    // GET /admin/catalog/collections Tests
    // ========================================

    @Test
    void listCollections_shouldReturnCollections() {
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/collections").then().statusCode(200)
                .body("data", notNullValue()).body("pagination", notNullValue()).body("links.self", notNullValue());
    }

    @Test
    void listCollections_shouldIsolateTenants() {
        // Request from tenant 1
        var response1 = request(tenantSubdomain).when().get("/api/v1/admin/catalog/collections").then().statusCode(200)
                .extract().jsonPath();
        var collections1 = response1.getList("data");

        // Request from tenant 2
        var response2 = request(otherTenantSubdomain).when().get("/api/v1/admin/catalog/collections").then()
                .statusCode(200).extract().jsonPath();
        var collections2 = response2.getList("data");

        // Each tenant should have their own isolated collection list
    }

    @Test
    void getCollection_shouldReturnCollectionDetails() {
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/collections/" + collectionId).then().statusCode(200)
                .body("id", equalTo(collectionId.toString())).body("name", equalTo("Test Collection 1"))
                .body("code", equalTo("TEST-COL-1"));
    }

    @Test
    void getCollection_shouldReturn404ForNonExistentCollection() {
        UUID nonExistentId = UUID.randomUUID();
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/collections/" + nonExistentId).then().statusCode(404)
                .body("title", equalTo("Collection Not Found")).body("status", equalTo(404))
                .body("message", equalTo("Collection not found: " + nonExistentId))
                .body("tenantId", equalTo(tenantId.toString())).body("traceId", notNullValue());
    }

    @Test
    void getCollection_shouldReturn404ForOtherTenantCollection() {
        // Attempt to access tenant 2's collection from tenant 1 context
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/collections/" + otherTenantCollectionId).then()
                .statusCode(404).body("title", equalTo("Collection Not Found"));
    }

    @Test
    void createCollection_shouldCreateSuccessfully() {
        String newCollection = """
                {
                  "code": "NEW-COL",
                  "name": "New Collection",
                  "slug": "new-collection",
                  "status": "active",
                  "collectionType": "manual",
                  "published": true,
                  "displayOrder": 2
                }
                """;

        request(tenantSubdomain).body(newCollection).when().post("/api/v1/admin/catalog/collections").then()
                .statusCode(201).body("code", equalTo("NEW-COL")).body("name", equalTo("New Collection"));
    }

    @Test
    void createCollection_shouldReturn409ForDuplicateCode() {
        String duplicateCollection = """
                {
                  "code": "TEST-COL-1",
                  "name": "Duplicate Collection",
                  "slug": "duplicate-collection",
                  "status": "active",
                  "collectionType": "manual"
                }
                """;

        request(tenantSubdomain).body(duplicateCollection).when().post("/api/v1/admin/catalog/collections").then()
                .statusCode(409).body("title", equalTo("Duplicate Collection Code"));
    }

    @Test
    void enforceTenantIsolation_categories() {
        // Verify tenant 1 can access their own category
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/categories/" + categoryId).then().statusCode(200)
                .body("id", equalTo(categoryId.toString()));

        // Verify tenant 2 can access their own category
        request(otherTenantSubdomain).when().get("/api/v1/admin/catalog/categories/" + otherTenantCategoryId).then()
                .statusCode(200).body("id", equalTo(otherTenantCategoryId.toString()));

        // Verify tenant 1 cannot access tenant 2's category
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/categories/" + otherTenantCategoryId).then()
                .statusCode(404);

        // Verify tenant 2 cannot access tenant 1's category
        request(otherTenantSubdomain).when().get("/api/v1/admin/catalog/categories/" + categoryId).then()
                .statusCode(404);
    }

    @Test
    void enforceTenantIsolation_collections() {
        // Verify tenant 1 can access their own collection
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/collections/" + collectionId).then().statusCode(200)
                .body("id", equalTo(collectionId.toString()));

        // Verify tenant 2 can access their own collection
        request(otherTenantSubdomain).when().get("/api/v1/admin/catalog/collections/" + otherTenantCollectionId).then()
                .statusCode(200).body("id", equalTo(otherTenantCollectionId.toString()));

        // Verify tenant 1 cannot access tenant 2's collection
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/collections/" + otherTenantCollectionId).then()
                .statusCode(404);

        // Verify tenant 2 cannot access tenant 1's collection
        request(otherTenantSubdomain).when().get("/api/v1/admin/catalog/collections/" + collectionId).then()
                .statusCode(404);
    }

    // ========================================
    // Product CRUD Tests
    // ========================================

    @Test
    void createProduct_success() {
        String productJson = """
                {
                  "title": "Test Product",
                  "slug": "test-product-unique-slug-1",
                  "description": "A test product description",
                  "status": "draft"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).body("id", notNullValue())
                .body("title", equalTo("Test Product")).body("slug", equalTo("test-product-unique-slug-1"))
                .body("status", equalTo("draft"));
    }

    @Test
    void createProduct_withScheduledStatus_success() {
        String productJson = """
                {
                  "title": "Scheduled Product",
                  "slug": "scheduled-product-slug",
                  "visibilityWindow": "{\\"start_date\\":\\"2030-01-01T00:00:00Z\\",\\"end_date\\":\\"2030-01-31T00:00:00Z\\"}",
                  "status": "scheduled"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).body("status", equalTo("scheduled"))
                .body("visibilityWindow", notNullValue());
    }

    @Test
    void createProduct_duplicateSlug_returns409() {
        String productJson = """
                {
                  "title": "Test Product 1",
                  "slug": "duplicate-slug-test",
                  "description": "First product",
                  "status": "draft"
                }
                """;

        // Create first product
        request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201);

        // Attempt to create second product with same slug
        String productJson2 = """
                {
                  "title": "Test Product 2",
                  "slug": "duplicate-slug-test",
                  "description": "Second product",
                  "status": "draft"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(productJson2).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(409)
                .body("title", equalTo("Duplicate Product Slug"))
                .body("message", equalTo("Product with slug 'duplicate-slug-test' already exists"))
                .body("tenantId", equalTo(tenantId.toString())).body("traceId", notNullValue());
    }

    @Test
    void updateProduct_invalidTransition_returns400() {
        String productJson = """
                {
                  "title": "Live Product",
                  "slug": "live-product-slug",
                  "status": "active"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        String updateJson = """
                {
                  "title": "Live Product",
                  "slug": "live-product-slug",
                  "status": "draft"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(updateJson).when()
                .put("/api/v1/admin/catalog/products/" + productId).then().statusCode(400)
                .body("title", equalTo("Invalid Product Data"))
                .body("message", equalTo("Cannot transition from active to draft for catalog entities."))
                .body("tenantId", equalTo(tenantId.toString()));
    }

    @Test
    void listProducts_withPagination() {
        // Create multiple products
        for (int i = 1; i <= 5; i++) {
            String productJson = String.format("""
                    {
                      "title": "Product %d",
                      "slug": "product-%d-slug",
                      "description": "Product %d description",
                      "status": "active"
                    }
                    """, i, i, i);

            request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                    .post("/api/v1/admin/catalog/products").then().statusCode(201);
        }

        // List products with pagination
        request(tenantSubdomain).queryParam("page", 1).queryParam("pageSize", 3).when()
                .get("/api/v1/admin/catalog/products").then().statusCode(200).body("pagination.page", equalTo(1))
                .body("pagination.pageSize", equalTo(3)).body("pagination.totalItems", equalTo(5))
                .body("data.size()", equalTo(3)).body("links.self", notNullValue());
    }

    @Test
    void listProducts_filterByStatus() {
        // Create products with different statuses
        String draftProduct = """
                {
                  "title": "Draft Product",
                  "slug": "draft-product-slug",
                  "status": "draft"
                }
                """;

        String activeProduct = """
                {
                  "title": "Active Product",
                  "slug": "active-product-slug",
                  "status": "active"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(draftProduct).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201);

        request(tenantSubdomain).contentType(ContentType.JSON).body(activeProduct).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201);

        // Filter by status
        request(tenantSubdomain).queryParam("status", "draft").when().get("/api/v1/admin/catalog/products").then()
                .statusCode(200).body("data.size()", equalTo(1)).body("data[0].status", equalTo("draft"));

        request(tenantSubdomain).queryParam("status", "active").when().get("/api/v1/admin/catalog/products").then()
                .statusCode(200).body("data.size()", equalTo(1)).body("data[0].status", equalTo("active"));
    }

    @Test
    void getProduct_success() {
        // Create a product
        String productJson = """
                {
                  "title": "Get Test Product",
                  "slug": "get-test-product-slug",
                  "description": "Product for get test",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Get the product
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/products/" + productId).then().statusCode(200)
                .body("id", equalTo(productId)).body("title", equalTo("Get Test Product"))
                .body("slug", equalTo("get-test-product-slug"));
    }

    @Test
    void getProduct_notFound_returns404() {
        UUID nonExistentId = UUID.randomUUID();
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/products/" + nonExistentId).then().statusCode(404)
                .body("title", equalTo("Product Not Found"))
                .body("message", equalTo("Product not found: " + nonExistentId))
                .body("tenantId", equalTo(tenantId.toString())).body("traceId", notNullValue());
    }

    @Test
    void updateProduct_success() {
        // Create a product
        String productJson = """
                {
                  "title": "Original Title",
                  "slug": "original-slug-update-test",
                  "description": "Original description",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Update the product
        String updateJson = """
                {
                  "title": "Updated Title",
                  "slug": "updated-slug-test",
                  "description": "Updated description",
                  "status": "active"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(updateJson).when()
                .put("/api/v1/admin/catalog/products/" + productId).then().statusCode(200)
                .body("title", equalTo("Updated Title")).body("slug", equalTo("updated-slug-test"))
                .body("status", equalTo("active"));
    }

    @Test
    void deleteProduct_success() {
        // Create a product
        String productJson = """
                {
                  "title": "Product to Delete",
                  "slug": "delete-test-product-slug",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Delete the product
        request(tenantSubdomain).when().delete("/api/v1/admin/catalog/products/" + productId).then().statusCode(204);

        // Verify product is marked as deleted (should not appear in list)
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/products/" + productId).then().statusCode(200)
                .body("status", equalTo("deleted"));
    }

    @Test
    void enforceTenantIsolation_products() {
        // Create product for tenant 1
        String productJson = """
                {
                  "title": "Tenant 1 Product",
                  "slug": "tenant-1-product-slug-isolation",
                  "status": "active"
                }
                """;

        String tenant1ProductId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Verify tenant 1 can access their product
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/products/" + tenant1ProductId).then().statusCode(200)
                .body("id", equalTo(tenant1ProductId));

        // Verify tenant 2 CANNOT access tenant 1's product
        request(otherTenantSubdomain).when().get("/api/v1/admin/catalog/products/" + tenant1ProductId).then()
                .statusCode(404);

        // Verify tenant 2 CANNOT update tenant 1's product
        String updateJson = """
                {
                  "title": "Malicious Update",
                  "slug": "malicious-slug",
                  "status": "active"
                }
                """;

        request(otherTenantSubdomain).contentType(ContentType.JSON).body(updateJson).when()
                .put("/api/v1/admin/catalog/products/" + tenant1ProductId).then().statusCode(404);

        // Verify tenant 2 CANNOT delete tenant 1's product
        request(otherTenantSubdomain).when().delete("/api/v1/admin/catalog/products/" + tenant1ProductId).then()
                .statusCode(404);
    }

    // ========================================
    // Variant CRUD Tests
    // ========================================

    @Test
    void createVariant_success() {
        // Create a product first
        String productJson = """
                {
                  "title": "Product for Variant Test",
                  "slug": "product-for-variant-slug",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Create a variant
        String variantJson = """
                {
                  "sku": "TEST-VAR-001",
                  "barcode": "1234567890123",
                  "price": 99.99,
                  "inventoryPolicy": "continue"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .post("/api/v1/admin/catalog/products/" + productId + "/variants").then().statusCode(201)
                .body("id", notNullValue()).body("sku", equalTo("TEST-VAR-001"))
                .body("barcode", equalTo("1234567890123")).body("price", equalTo(99.99f))
                .body("inventoryPolicy", equalTo("continue"));
    }

    @Test
    void createVariant_duplicateSku_returns409() {
        // Create a product
        String productJson = """
                {
                  "title": "Product for Duplicate SKU Test",
                  "slug": "product-duplicate-sku-slug",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Create first variant
        String variantJson = """
                {
                  "sku": "DUPLICATE-SKU-001",
                  "price": 50.00,
                  "inventoryPolicy": "continue"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .post("/api/v1/admin/catalog/products/" + productId + "/variants").then().statusCode(201);

        // Attempt to create second variant with same SKU
        request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .post("/api/v1/admin/catalog/products/" + productId + "/variants").then().statusCode(409)
                .body("title", equalTo("Duplicate Variant SKU"));
    }

    @Test
    void listVariants_success() {
        // Create a product
        String productJson = """
                {
                  "title": "Product with Multiple Variants",
                  "slug": "product-with-variants-slug",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Create multiple variants
        for (int i = 1; i <= 3; i++) {
            String variantJson = String.format("""
                    {
                      "sku": "VAR-%03d",
                      "price": %d.99,
                      "inventoryPolicy": "continue"
                    }
                    """, i, i * 10);

            request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                    .post("/api/v1/admin/catalog/products/" + productId + "/variants").then().statusCode(201);
        }

        // List variants
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/products/" + productId + "/variants").then()
                .statusCode(200).body("size()", equalTo(3));
    }

    @Test
    void getVariant_success() {
        // Create a product
        String productJson = """
                {
                  "title": "Product for Get Variant Test",
                  "slug": "product-get-variant-slug",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Create a variant
        String variantJson = """
                {
                  "sku": "GET-VAR-001",
                  "price": 49.99,
                  "inventoryPolicy": "deny"
                }
                """;

        String variantId = request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .post("/api/v1/admin/catalog/products/" + productId + "/variants").then().statusCode(201).extract()
                .path("id");

        // Get the variant
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/products/" + productId + "/variants/" + variantId)
                .then().statusCode(200).body("id", equalTo(variantId)).body("sku", equalTo("GET-VAR-001"))
                .body("price", equalTo(49.99f));
    }

    @Test
    void updateVariant_success() {
        // Create a product
        String productJson = """
                {
                  "title": "Product for Update Variant Test",
                  "slug": "product-update-variant-slug",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Create a variant
        String variantJson = """
                {
                  "sku": "UPDATE-VAR-001",
                  "price": 29.99,
                  "inventoryPolicy": "continue"
                }
                """;

        String variantId = request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .post("/api/v1/admin/catalog/products/" + productId + "/variants").then().statusCode(201).extract()
                .path("id");

        // Update the variant
        String updateJson = """
                {
                  "sku": "UPDATE-VAR-001-MODIFIED",
                  "price": 39.99,
                  "inventoryPolicy": "deny"
                }
                """;

        request(tenantSubdomain).contentType(ContentType.JSON).body(updateJson).when()
                .put("/api/v1/admin/catalog/products/" + productId + "/variants/" + variantId).then().statusCode(200)
                .body("sku", equalTo("UPDATE-VAR-001-MODIFIED")).body("price", equalTo(39.99f))
                .body("inventoryPolicy", equalTo("deny"));
    }

    @Test
    void deleteVariant_success() {
        // Create a product
        String productJson = """
                {
                  "title": "Product for Delete Variant Test",
                  "slug": "product-delete-variant-slug",
                  "status": "draft"
                }
                """;

        String productId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Create a variant
        String variantJson = """
                {
                  "sku": "DELETE-VAR-001",
                  "price": 19.99,
                  "inventoryPolicy": "continue"
                }
                """;

        String variantId = request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .post("/api/v1/admin/catalog/products/" + productId + "/variants").then().statusCode(201).extract()
                .path("id");

        // Delete the variant
        request(tenantSubdomain).when().delete("/api/v1/admin/catalog/products/" + productId + "/variants/" + variantId)
                .then().statusCode(204);

        // Verify variant is marked as deleted
        request(tenantSubdomain).when().get("/api/v1/admin/catalog/products/" + productId + "/variants/" + variantId)
                .then().statusCode(200).body("status", equalTo("deleted"));
    }

    @Test
    void enforceTenantIsolation_variants() {
        // Create product for tenant 1
        String productJson = """
                {
                  "title": "Tenant 1 Product for Variant Isolation",
                  "slug": "tenant-1-product-variant-isolation-slug",
                  "status": "active"
                }
                """;

        String tenant1ProductId = request(tenantSubdomain).contentType(ContentType.JSON).body(productJson).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        // Create variant for tenant 1's product
        String variantJson = """
                {
                  "sku": "TENANT-1-VAR-001",
                  "price": 99.99,
                  "inventoryPolicy": "continue"
                }
                """;

        String tenant1VariantId = request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .post("/api/v1/admin/catalog/products/" + tenant1ProductId + "/variants").then().statusCode(201)
                .extract().path("id");

        // Verify tenant 1 can access their variant
        request(tenantSubdomain).when()
                .get("/api/v1/admin/catalog/products/" + tenant1ProductId + "/variants/" + tenant1VariantId).then()
                .statusCode(200).body("id", equalTo(tenant1VariantId));

        // Verify tenant 2 CANNOT access tenant 1's variant
        request(otherTenantSubdomain).when()
                .get("/api/v1/admin/catalog/products/" + tenant1ProductId + "/variants/" + tenant1VariantId).then()
                .statusCode(404);

        // Verify tenant 2 CANNOT update tenant 1's variant
        String updateJson = """
                {
                  "sku": "MALICIOUS-SKU",
                  "price": 1.00,
                  "inventoryPolicy": "continue"
                }
                """;

        request(otherTenantSubdomain).contentType(ContentType.JSON).body(updateJson).when()
                .put("/api/v1/admin/catalog/products/" + tenant1ProductId + "/variants/" + tenant1VariantId).then()
                .statusCode(404);

        // Verify tenant 2 CANNOT delete tenant 1's variant
        request(otherTenantSubdomain).when()
                .delete("/api/v1/admin/catalog/products/" + tenant1ProductId + "/variants/" + tenant1VariantId).then()
                .statusCode(404);
    }

    @Test
    void variantMustBelongToProductForUpdates() {
        String productOne = """
                {
                  "title": "Variant Parent One",
                  "slug": "variant-parent-one",
                  "status": "active"
                }
                """;

        String productTwo = """
                {
                  "title": "Variant Parent Two",
                  "slug": "variant-parent-two",
                  "status": "active"
                }
                """;

        String productOneId = request(tenantSubdomain).contentType(ContentType.JSON).body(productOne).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");
        String productTwoId = request(tenantSubdomain).contentType(ContentType.JSON).body(productTwo).when()
                .post("/api/v1/admin/catalog/products").then().statusCode(201).extract().path("id");

        String variantJson = """
                {
                  "sku": "WRONG-PRODUCT-SKU",
                  "price": 12.99,
                  "inventoryPolicy": "continue"
                }
                """;

        String variantId = request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .post("/api/v1/admin/catalog/products/" + productOneId + "/variants").then().statusCode(201).extract()
                .path("id");

        request(tenantSubdomain).contentType(ContentType.JSON).body(variantJson).when()
                .put("/api/v1/admin/catalog/products/" + productTwoId + "/variants/" + variantId).then()
                .statusCode(404);
    }
}
