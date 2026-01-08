package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for {@link CartResource}.
 *
 * <p>
 * Tests cover HTTP contract compliance, error handling, and cart API functionality.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Cart endpoint integration tests with HTTP contract verification</li>
 * <li>OpenAPI: /cart endpoint specifications</li>
 * </ul>
 */
@QuarkusTest
class CartResourceTest {

    private static final String SESSION_HEADER = "X-Session-Id";
    private static final String SESSION_COOKIE_NAME = "vs_session";
    private static final String SESSION_ID = "11111111-2222-3333-4444-555555555555";

    @Inject
    EntityManager entityManager;

    private String tenantSubdomain;
    private String variantId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "cartapitest";
        tenant.name = "Cart API Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantSubdomain = tenant.subdomain;

        // Set tenant context for setup
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

        // Create test product and variant
        Product product = new Product();
        product.tenant = tenant;
        product.sku = "API-TEST-PRODUCT";
        product.name = "API Test Product";
        product.slug = "api-test-product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "API-TEST-VARIANT";
        variant.name = "API Test Variant";
        variant.price = new BigDecimal("29.99");
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);
        entityManager.flush();
        variantId = variant.id.toString();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RequestSpecification request() {
        return given().header("Host", tenantSubdomain + ".villagecompute.com").header(SESSION_HEADER, SESSION_ID)
                .contentType(ContentType.JSON);
    }

    private RequestSpecification anonymousRequest() {
        return given().header("Host", tenantSubdomain + ".villagecompute.com").contentType(ContentType.JSON);
    }

    // ========================================
    // GET /cart Tests
    // ========================================

    @Test
    void getCart_shouldReturn404WhenNoCart() {
        request().when().get("/api/v1/cart").then().statusCode(404);
    }

    @Test
    void getCart_shouldReturnCartWithItems() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":2}", variantId);
        request().body(addRequest).when().post("/api/v1/cart/items").then().statusCode(201);

        request().when().get("/api/v1/cart").then().statusCode(200).body("id", notNullValue())
                .body("items.size()", equalTo(1)).body("subtotal.amount", equalTo("59.98"))
                .body("subtotal.currency", equalTo("USD")).body("itemCount", equalTo(1));
    }

    @Test
    void getCart_shouldNotSetCookieWhenSessionHeaderProvided() {
        var response = request().when().get("/api/v1/cart").then().statusCode(404).extract().response();

        assertNull(response.getHeader("Set-Cookie"));
    }

    // ========================================
    // POST /cart/items Tests
    // ========================================

    @Test
    void addToCart_shouldCreateCartItem() {
        String requestBody = String.format("{\"variantId\":\"%s\",\"quantity\":2}", variantId);

        request().body(requestBody).when().post("/api/v1/cart/items").then().statusCode(201).body("id", notNullValue())
                .body("variantId", equalTo(variantId)).body("quantity", equalTo(2))
                .body("unitPrice.amount", equalTo("29.99")).body("unitPrice.currency", equalTo("USD"))
                .body("lineTotal.amount", equalTo("59.98"));
    }

    @Test
    void addToCart_shouldReturn400ForInvalidRequest() {
        String requestBody = "{\"variantId\":\"invalid-uuid\",\"quantity\":2}";

        request().body(requestBody).when().post("/api/v1/cart/items").then().statusCode(400);
    }

    @Test
    void addToCart_shouldReturn400ForZeroQuantity() {
        String requestBody = String.format("{\"variantId\":\"%s\",\"quantity\":0}", variantId);

        request().body(requestBody).when().post("/api/v1/cart/items").then().statusCode(400);
    }

    @Test
    void addToCart_shouldReturn404ForUnknownVariant() {
        String requestBody = String.format("{\"variantId\":\"%s\",\"quantity\":1}", UUID.randomUUID());

        request().body(requestBody).when().post("/api/v1/cart/items").then().statusCode(404).body("title",
                equalTo("Not Found"));
    }

    // ========================================
    // PATCH /cart/items/{itemId} Tests
    // ========================================

    @Test
    void updateCartItem_shouldUpdateQuantity() {
        // First add an item
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":2}", variantId);
        String itemId = request().body(addRequest).post("/api/v1/cart/items").then().statusCode(201).extract()
                .path("id");

        // Then update its quantity
        String updateRequest = "{\"quantity\":5}";

        request().body(updateRequest).when().patch("/api/v1/cart/items/" + itemId).then().statusCode(200)
                .body("id", equalTo(itemId)).body("quantity", equalTo(5)).body("lineTotal.amount", equalTo("149.95"));
    }

    @Test
    void updateCartItem_shouldReturn404ForNonExistentItem() {
        String nonExistentId = "00000000-0000-0000-0000-000000000000";
        String updateRequest = "{\"quantity\":5}";

        request().body(updateRequest).when().patch("/api/v1/cart/items/" + nonExistentId).then().statusCode(404);
    }

    // ========================================
    // DELETE /cart/items/{itemId} Tests
    // ========================================

    @Test
    void removeCartItem_shouldDeleteItem() {
        // First add an item
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":2}", variantId);
        String itemId = request().body(addRequest).post("/api/v1/cart/items").then().statusCode(201).extract()
                .path("id");

        // Then remove it
        request().when().delete("/api/v1/cart/items/" + itemId).then().statusCode(204);

        // Verify cart is empty
        request().when().get("/api/v1/cart").then().statusCode(200).body("itemCount", equalTo(0));
    }

    @Test
    void removeCartItem_shouldReturn404ForNonExistentItem() {
        String nonExistentId = "00000000-0000-0000-0000-000000000000";

        request().when().delete("/api/v1/cart/items/" + nonExistentId).then().statusCode(404);
    }

    @Test
    void removeCartItem_shouldReturn404WhenCartMismatch() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":2}", variantId);
        String itemId = request().body(addRequest).post("/api/v1/cart/items").then().statusCode(201).extract()
                .path("id");

        given().header("Host", tenantSubdomain + ".villagecompute.com")
                .header(SESSION_HEADER, "22222222-3333-4444-5555-666666666666").when()
                .delete("/api/v1/cart/items/" + itemId).then().statusCode(404);
    }

    // ========================================
    // DELETE /cart Tests
    // ========================================

    @Test
    void clearCart_shouldRemoveAllItems() {
        // Add items first
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":2}", variantId);
        request().body(addRequest).post("/api/v1/cart/items").then().statusCode(201);

        // Clear cart
        request().when().delete("/api/v1/cart").then().statusCode(204);

        // Verify cart is empty
        request().when().get("/api/v1/cart").then().statusCode(200).body("itemCount", equalTo(0));
    }

    @Test
    void clearCart_shouldReturn404WhenNoCart() {
        request().when().delete("/api/v1/cart").then().statusCode(404);
    }

    // ========================================
    // Session Handling Tests
    // ========================================

    @Test
    void sessionCookieIssuedWhenHeaderMissing() {
        var response = anonymousRequest().when().get("/api/v1/cart").then().statusCode(404).extract().response();

        String setCookie = response.getHeader("Set-Cookie");
        String sessionHeader = response.getHeader(SESSION_HEADER);

        assertNotNull(setCookie);
        assertTrue(setCookie.contains(SESSION_COOKIE_NAME));
        assertNotNull(sessionHeader);
    }

    @Test
    void sessionCookieAllowsCartReuse() {
        String cookieValue = anonymousRequest().when().get("/api/v1/cart").then().statusCode(404).extract().response()
                .getCookie(SESSION_COOKIE_NAME);
        assertNotNull(cookieValue);

        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":1}", variantId);

        given().header("Host", tenantSubdomain + ".villagecompute.com").cookie(SESSION_COOKIE_NAME, cookieValue)
                .contentType(ContentType.JSON).body(addRequest).post("/api/v1/cart/items").then().statusCode(201);

        given().header("Host", tenantSubdomain + ".villagecompute.com").cookie(SESSION_COOKIE_NAME, cookieValue).when()
                .get("/api/v1/cart").then().statusCode(200).body("itemCount", equalTo(1));
    }

    @Test
    void invalidSessionIdHeaderShouldBeIgnored() {
        var response = given().header("Host", tenantSubdomain + ".villagecompute.com").header(SESSION_HEADER, "invalid")
                .when().get("/api/v1/cart").then().statusCode(404).extract().response();

        String sessionHeader = response.getHeader(SESSION_HEADER);
        assertNotNull(sessionHeader);
        assertNotEquals("invalid", sessionHeader);
        assertNotNull(response.getCookie(SESSION_COOKIE_NAME));
    }
}
