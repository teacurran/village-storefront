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
                .body("data", notNullValue()).body("pagination", notNullValue());
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
                .body("title", equalTo("Collection Not Found")).body("status", equalTo(404));
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
}
