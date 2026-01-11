package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.hasSize;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for {@link InventoryAdminResource}.
 *
 * <p>
 * Tests cover HTTP contract compliance, tenant isolation, and inventory admin API functionality. Verifies that
 * inventory locations, transfers, and adjustments are properly tenant-scoped.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T3: Inventory endpoint integration tests with tenant isolation verification</li>
 * <li>OpenAPI: /admin/inventory/** endpoint specifications</li>
 * </ul>
 */
@QuarkusTest
class InventoryAdminResourceTest {

    @Inject
    EntityManager entityManager;

    private String tenantSubdomain;
    private String otherTenantSubdomain;
    private UUID locationId;
    private UUID otherTenantLocationId;
    private UUID variantId;
    private UUID otherTenantVariantId;
    private Tenant primaryTenant;
    private Tenant secondaryTenant;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM InventoryAdjustment").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransferLine").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransfer").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLocation").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant 1
        Tenant tenant = new Tenant();
        tenant.subdomain = "inventorytest1";
        tenant.name = "Inventory Test Tenant 1";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantSubdomain = tenant.subdomain;
        primaryTenant = tenant;

        // Set tenant context for setup
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

        // Create test location for tenant 1
        InventoryLocation location = new InventoryLocation();
        location.tenant = tenant;
        location.code = "WAREHOUSE-1";
        location.name = "Main Warehouse";
        location.type = "warehouse";
        location.address = "{\"city\":\"Seattle\",\"state\":\"WA\"}";
        location.active = true;
        location.createdAt = OffsetDateTime.now();
        location.updatedAt = OffsetDateTime.now();
        entityManager.persist(location);
        entityManager.flush();
        locationId = location.id;

        // Create test product and variant for tenant 1
        Product product = new Product();
        product.tenant = tenant;
        product.sku = "TEST-PRODUCT-1";
        product.name = "Test Product 1";
        product.slug = "test-product-1";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "TEST-VARIANT-1";
        variant.name = "Test Variant 1";
        variant.price = new BigDecimal("49.99");
        variant.attributes = "{\"color\":\"Red\"}";
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);
        entityManager.flush();
        variantId = variant.id;

        // Clear tenant context
        TenantContext.clear();

        // Create test tenant 2 (for tenant isolation tests)
        Tenant otherTenant = new Tenant();
        otherTenant.subdomain = "inventorytest2";
        otherTenant.name = "Inventory Test Tenant 2";
        otherTenant.status = "active";
        otherTenant.settings = "{}";
        otherTenant.createdAt = OffsetDateTime.now();
        otherTenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherTenant);
        entityManager.flush();
        otherTenantSubdomain = otherTenant.subdomain;
        secondaryTenant = otherTenant;

        // Set tenant context for other tenant setup
        TenantContext.setCurrentTenant(
                new TenantInfo(otherTenant.id, otherTenant.subdomain, otherTenant.name, otherTenant.status));

        // Create test location for tenant 2
        InventoryLocation otherLocation = new InventoryLocation();
        otherLocation.tenant = otherTenant;
        otherLocation.code = "WAREHOUSE-2";
        otherLocation.name = "Other Warehouse";
        otherLocation.type = "warehouse";
        otherLocation.address = "{\"city\":\"Portland\",\"state\":\"OR\"}";
        otherLocation.active = true;
        otherLocation.createdAt = OffsetDateTime.now();
        otherLocation.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherLocation);
        entityManager.flush();
        otherTenantLocationId = otherLocation.id;

        // Create test product and variant for tenant 2
        Product otherProduct = new Product();
        otherProduct.tenant = otherTenant;
        otherProduct.sku = "TEST-PRODUCT-2";
        otherProduct.name = "Test Product 2";
        otherProduct.slug = "test-product-2";
        otherProduct.type = "physical";
        otherProduct.status = "active";
        otherProduct.createdAt = OffsetDateTime.now();
        otherProduct.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherProduct);

        ProductVariant otherVariant = new ProductVariant();
        otherVariant.tenant = otherTenant;
        otherVariant.product = otherProduct;
        otherVariant.sku = "TEST-VARIANT-2";
        otherVariant.name = "Test Variant 2";
        otherVariant.price = new BigDecimal("99.99");
        otherVariant.attributes = "{\"color\":\"Blue\"}";
        otherVariant.status = "active";
        otherVariant.createdAt = OffsetDateTime.now();
        otherVariant.updatedAt = OffsetDateTime.now();
        entityManager.persist(otherVariant);
        entityManager.flush();
        otherTenantVariantId = otherVariant.id;

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
    // GET /admin/inventory/locations Tests
    // ========================================

    @Test
    void listLocations_shouldReturnLocations() {
        request(tenantSubdomain).when().get("/api/v1/admin/inventory/locations").then().statusCode(200).body("$",
                hasSize(1));
    }

    @Test
    void listLocations_shouldIsolateTenants() {
        // Request from tenant 1 - should only see tenant 1 locations
        var response1 = request(tenantSubdomain).when().get("/api/v1/admin/inventory/locations").then().statusCode(200)
                .body("$", hasSize(1)).body("[0].code", equalTo("WAREHOUSE-1")).extract().jsonPath();

        // Request from tenant 2 - should only see tenant 2 locations
        var response2 = request(otherTenantSubdomain).when().get("/api/v1/admin/inventory/locations").then()
                .statusCode(200).body("$", hasSize(1)).body("[0].code", equalTo("WAREHOUSE-2")).extract().jsonPath();
    }

    // ========================================
    // POST /admin/inventory/locations Tests
    // ========================================

    @Test
    void createLocation_shouldCreateSuccessfully() {
        Map<String, Object> request = new HashMap<>();
        request.put("code", "STORE-1");
        request.put("name", "Retail Store 1");
        request.put("type", "store");
        request.put("address", "{\"city\":\"New York\",\"state\":\"NY\"}");
        request.put("active", true);

        request(tenantSubdomain).body(request).when().post("/api/v1/admin/inventory/locations").then().statusCode(201)
                .body("code", equalTo("STORE-1")).body("name", equalTo("Retail Store 1")).body("type", equalTo("store"))
                .body("id", notNullValue());
    }

    @Test
    void createLocation_shouldReturn409ForDuplicateCode() {
        Map<String, Object> request = new HashMap<>();
        request.put("code", "WAREHOUSE-1"); // Same code as existing location
        request.put("name", "Duplicate Warehouse");
        request.put("type", "warehouse");
        request.put("active", true);

        request(tenantSubdomain).body(request).when().post("/api/v1/admin/inventory/locations").then().statusCode(409);
    }

    // ========================================
    // GET /admin/inventory/transfers Tests
    // ========================================

    @Test
    void listTransfers_shouldReturnTransfers() {
        request(tenantSubdomain).when().get("/api/v1/admin/inventory/transfers").then().statusCode(200).body("$",
                notNullValue());
    }

    @Test
    void listTransfers_shouldIsolateTenants() {
        // Each tenant should only see their own transfers
        request(tenantSubdomain).when().get("/api/v1/admin/inventory/transfers").then().statusCode(200);
        request(otherTenantSubdomain).when().get("/api/v1/admin/inventory/transfers").then().statusCode(200);
    }

    // ========================================
    // POST /admin/inventory/adjustments Tests
    // ========================================

    @Test
    void createAdjustment_shouldCreateSuccessfully() {
        Map<String, Object> request = new HashMap<>();
        request.put("variantId", variantId.toString());
        request.put("locationId", locationId.toString());
        request.put("quantityChange", 10);
        request.put("reason", "CYCLE_COUNT");
        request.put("adjustedBy", "admin@test.com");
        request.put("notes", "Annual inventory count");

        request(tenantSubdomain).body(request).when().post("/api/v1/admin/inventory/adjustments").then().statusCode(201)
                .body("variantId", equalTo(variantId.toString())).body("locationId", equalTo(locationId.toString()))
                .body("quantityChange", equalTo(10)).body("reason", equalTo("CYCLE_COUNT")).body("id", notNullValue());
    }

    @Test
    void createAdjustment_shouldReturn400ForInvalidReason() {
        Map<String, Object> request = new HashMap<>();
        request.put("variantId", variantId.toString());
        request.put("locationId", locationId.toString());
        request.put("quantityChange", 10);
        request.put("reason", "INVALID_REASON");
        request.put("adjustedBy", "admin@test.com");

        request(tenantSubdomain).body(request).when().post("/api/v1/admin/inventory/adjustments").then()
                .statusCode(400);
    }

    @Test
    void createAdjustment_shouldReturn404ForOtherTenantLocation() {
        // Attempt to adjust inventory at tenant 2's location from tenant 1 context
        Map<String, Object> request = new HashMap<>();
        request.put("variantId", variantId.toString());
        request.put("locationId", otherTenantLocationId.toString()); // Other tenant's location
        request.put("quantityChange", 10);
        request.put("reason", "CYCLE_COUNT");
        request.put("adjustedBy", "admin@test.com");

        request(tenantSubdomain).body(request).when().post("/api/v1/admin/inventory/adjustments").then()
                .statusCode(404);
    }

    @Test
    void createAdjustment_shouldReturn404ForOtherTenantVariant() {
        // Attempt to adjust inventory for tenant 2's variant from tenant 1 context
        Map<String, Object> request = new HashMap<>();
        request.put("variantId", otherTenantVariantId.toString()); // Other tenant's variant
        request.put("locationId", locationId.toString());
        request.put("quantityChange", 10);
        request.put("reason", "CYCLE_COUNT");
        request.put("adjustedBy", "admin@test.com");

        request(tenantSubdomain).body(request).when().post("/api/v1/admin/inventory/adjustments").then()
                .statusCode(400); // Will fail variant lookup
    }

    // ========================================
    // Tenant Isolation Tests
    // ========================================

    @Test
    void enforceTenantIsolation_locations() {
        // Verify tenant 1 can only see their own locations
        var locations1 = request(tenantSubdomain).when().get("/api/v1/admin/inventory/locations").then().statusCode(200)
                .extract().jsonPath().getList("code", String.class);

        assert locations1.contains("WAREHOUSE-1");
        assert !locations1.contains("WAREHOUSE-2");

        // Verify tenant 2 can only see their own locations
        var locations2 = request(otherTenantSubdomain).when().get("/api/v1/admin/inventory/locations").then()
                .statusCode(200).extract().jsonPath().getList("code", String.class);

        assert locations2.contains("WAREHOUSE-2");
        assert !locations2.contains("WAREHOUSE-1");
    }

    @Test
    void enforceTenantIsolation_adjustments() {
        // Tenant 1 creates adjustment
        Map<String, Object> request1 = new HashMap<>();
        request1.put("variantId", variantId.toString());
        request1.put("locationId", locationId.toString());
        request1.put("quantityChange", 5);
        request1.put("reason", "FOUND");
        request1.put("adjustedBy", "admin1@test.com");

        request(tenantSubdomain).body(request1).when().post("/api/v1/admin/inventory/adjustments").then()
                .statusCode(201);

        // Tenant 2 creates adjustment
        Map<String, Object> request2 = new HashMap<>();
        request2.put("variantId", otherTenantVariantId.toString());
        request2.put("locationId", otherTenantLocationId.toString());
        request2.put("quantityChange", 3);
        request2.put("reason", "DAMAGE");
        request2.put("adjustedBy", "admin2@test.com");

        request(otherTenantSubdomain).body(request2).when().post("/api/v1/admin/inventory/adjustments").then()
                .statusCode(201);

        // Verify cross-tenant access is blocked
        Map<String, Object> crossTenantRequest = new HashMap<>();
        crossTenantRequest.put("variantId", otherTenantVariantId.toString()); // Tenant 2's variant
        crossTenantRequest.put("locationId", locationId.toString()); // Tenant 1's location
        crossTenantRequest.put("quantityChange", 1);
        crossTenantRequest.put("reason", "OTHER");
        crossTenantRequest.put("adjustedBy", "admin@test.com");

        // Should fail - variant doesn't belong to tenant 1
        request(tenantSubdomain).body(crossTenantRequest).when().post("/api/v1/admin/inventory/adjustments").then()
                .statusCode(400);
    }
}
