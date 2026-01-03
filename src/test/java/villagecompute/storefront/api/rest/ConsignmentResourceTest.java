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

import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for {@link ConsignmentResource}.
 *
 * <p>
 * Tests cover HTTP contract compliance, error handling, and consignment API functionality.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Consignment endpoint integration tests</li>
 * <li>OpenAPI: /admin/consignors endpoint specifications</li>
 * </ul>
 */
@QuarkusTest
class ConsignmentResourceTest {

    @Inject
    EntityManager entityManager;

    private String tenantSubdomain;
    private UUID consignorId;
    private UUID productId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "consignapitest";
        tenant.name = "Consignment API Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantSubdomain = tenant.subdomain;

        // Set tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

        // Create test consignor
        Consignor consignor = new Consignor();
        consignor.tenant = tenant;
        consignor.name = "Test Vendor";
        consignor.contactInfo = "{\"email\":\"vendor@example.com\"}";
        consignor.payoutSettings = "{\"default_commission_rate\":15.0}";
        consignor.status = "active";
        consignor.createdAt = OffsetDateTime.now();
        consignor.updatedAt = OffsetDateTime.now();
        entityManager.persist(consignor);
        entityManager.flush();
        consignorId = consignor.id;

        // Create test product
        Product product = new Product();
        product.tenant = tenant;
        product.sku = "TEST-PRODUCT-001";
        product.name = "Test Product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);
        entityManager.flush();
        productId = product.id;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ========================================
    // Consignor Endpoint Tests
    // ========================================

    @Test
    void testCreateConsignor() {
        String requestBody = """
                {
                  "name": "New Vendor",
                  "contactInfo": "{\\"email\\":\\"new@example.com\\"}",
                  "payoutSettings": "{\\"default_commission_rate\\":20.0}"
                }
                """;

        adminRequest().contentType(ContentType.JSON).body(requestBody).when().post("/api/v1/admin/consignors").then()
                .statusCode(201).body("id", notNullValue()).body("name", equalTo("New Vendor"))
                .body("status", equalTo("active"));
    }

    @Test
    void testGetConsignor() {
        adminRequest().when().get("/api/v1/admin/consignors/{id}", consignorId).then().statusCode(200)
                .body("id", equalTo(consignorId.toString())).body("name", equalTo("Test Vendor")).body("status",
                        equalTo("active"));
    }

    @Test
    void testGetConsignorNotFound() {
        UUID nonExistentId = UUID.randomUUID();

        adminRequest().when().get("/api/v1/admin/consignors/{id}", nonExistentId).then().statusCode(404).body("title",
                equalTo("Not Found"));
    }

    @Test
    void testUpdateConsignor() {
        String requestBody = """
                {
                  "name": "Updated Vendor Name",
                  "contactInfo": "{\\"email\\":\\"updated@example.com\\"}",
                  "payoutSettings": "{\\"default_commission_rate\\":18.0}"
                }
                """;

        adminRequest().contentType(ContentType.JSON).body(requestBody).when()
                .put("/api/v1/admin/consignors/{id}", consignorId).then().statusCode(200).body("name",
                        equalTo("Updated Vendor Name"));
    }

    @Test
    void testDeleteConsignor() {
        adminRequest().when().delete("/api/v1/admin/consignors/{id}", consignorId).then().statusCode(204);
    }

    @Test
    void testListConsignors() {
        adminRequest().queryParam("page", 0).queryParam("size", 10).when().get("/api/v1/admin/consignors").then()
                .statusCode(200);
    }

    // ========================================
    // Consignment Item Endpoint Tests
    // ========================================

    @Test
    void testCreateConsignmentItem() {
        String requestBody = String.format("""
                {
                  "productId": "%s",
                  "consignorId": "%s",
                  "commissionRate": 15.00
                }
                """, productId, consignorId);

        adminRequest().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/admin/consignors/{consignorId}/items", consignorId).then().statusCode(201)
                .body("id", notNullValue()).body("productId", equalTo(productId.toString())).body("consignorId",
                        equalTo(consignorId.toString())).body("commissionRate", equalTo(15.00f)).body("status",
                                equalTo("active"));
    }

    @Test
    void testListConsignmentItems() {
        adminRequest().queryParam("page", 0).queryParam("size", 10).when()
                .get("/api/v1/admin/consignors/{consignorId}/items", consignorId).then().statusCode(200);
    }

    // ========================================
    // Payout Batch Endpoint Tests
    // ========================================

    @Test
    void testCreatePayoutBatch() {
        adminRequest().contentType(ContentType.JSON).queryParam("periodStart", "2026-01-01")
                .queryParam("periodEnd", "2026-01-31").when()
                .post("/api/v1/admin/consignors/{consignorId}/payouts", consignorId).then().statusCode(201)
                .body("id", notNullValue()).body("consignorId", equalTo(consignorId.toString())).body("status",
                        equalTo("pending"));
    }

    @Test
    void testListPayoutBatches() {
        adminRequest().queryParam("page", 0).queryParam("size", 10).when()
                .get("/api/v1/admin/consignors/{consignorId}/payouts", consignorId).then().statusCode(200);
    }

    private RequestSpecification adminRequest() {
        return given().header("Host", tenantSubdomain + ".villagecompute.com");
    }
}
