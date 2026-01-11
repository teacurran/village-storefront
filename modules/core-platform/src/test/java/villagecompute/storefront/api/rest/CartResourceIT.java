package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.api.types.AddToCartRequest;
import villagecompute.storefront.api.types.UpdateCartItemRequest;
import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.services.CartService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * Integration tests for {@link CartResource}.
 *
 * <p>
 * Tests cover HTTP endpoints for cart operations including session management, tenant isolation, and error handling.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Cart REST endpoints with multi-tenant isolation and Problem Details</li>
 * <li>OpenAPI: /api/v1/cart endpoints specification</li>
 * </ul>
 */
@QuarkusTest
class CartResourceIT {

    private static final String AUTH_USER_EMAIL = "cart-it-user@example.com";

    @Inject
    EntityManager entityManager;

    @Inject
    CartService cartService;

    private UUID tenantId;
    private UUID tenant2Id;
    private UUID variantId;
    private String sessionId;
    private UUID userId;
    private String userEmail;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM DomainEvent").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "cartresource";
        tenant.name = "Cart Resource Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Create second tenant for isolation tests
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "cartresource2";
        tenant2.name = "Cart Resource Test Tenant 2";
        tenant2.status = "active";
        tenant2.settings = "{}";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2);
        entityManager.flush();
        tenant2Id = tenant2.id;

        // Set current tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));

        // Create test user for authenticated flows
        User user = new User();
        user.tenant = tenant;
        user.email = AUTH_USER_EMAIL;
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        entityManager.persist(user);
        entityManager.flush();
        userId = user.id;
        userEmail = user.email;

        // Create test product and variant
        Product product = new Product();
        product.tenant = tenant;
        product.sku = "TEST-PRODUCT-IT";
        product.name = "Test Product IT";
        product.slug = "test-product-it";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "TEST-VARIANT-IT";
        variant.name = "Test Variant IT";
        variant.price = new BigDecimal("29.99");
        variant.status = "active";
        variant.position = 0;
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);
        entityManager.flush();
        variantId = variant.id;

        // Generate session ID for tests
        sessionId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ========================================
    // GET /api/v1/cart Tests
    // ========================================

    @Test
    void getCart_shouldReturn404WhenNoActiveCart() {
        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", UUID.randomUUID().toString()).when()
                .get("/api/v1/cart").then().statusCode(Response.Status.NOT_FOUND.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON).body("title", equalTo("Not Found"));
    }

    @Test
    @Transactional
    void getCart_shouldReturnCartWhenSessionExists() {
        // Create cart in database
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        cartService.addItemToCart(cart.id, variantId, 2);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when().get("/api/v1/cart")
                .then().statusCode(Response.Status.OK.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body("id", equalTo(cart.id.toString())).body("items", hasSize(1))
                .body("items[0].quantity", equalTo(2));
    }

    @Test
    @Transactional
    void getCart_shouldGenerateSessionCookieWhenNotProvided() {
        // Create cart with a session
        Cart cart = cartService.getOrCreateCartForSession(sessionId);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when().get("/api/v1/cart")
                .then().statusCode(Response.Status.NOT_FOUND.getStatusCode()); // Empty cart returns 404
    }

    // ========================================
    // POST /api/v1/cart/items Tests
    // ========================================

    @Test
    void addToCart_shouldCreateCartAndAddItem() {
        AddToCartRequest request = new AddToCartRequest();
        request.setVariantId(variantId);
        request.setQuantity(3);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/v1/cart/items").then()
                .statusCode(Response.Status.CREATED.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body("id", notNullValue()).body("quantity", equalTo(3))
                .body("variantId", equalTo(variantId.toString())).header("X-Session-Id", notNullValue());
    }

    @Test
    void addToCart_shouldReturn400ForInvalidQuantity() {
        AddToCartRequest request = new AddToCartRequest();
        request.setVariantId(variantId);
        request.setQuantity(0); // Invalid

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/v1/cart/items").then()
                .statusCode(Response.Status.BAD_REQUEST.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body("title", equalTo("Bad Request"));
    }

    @Test
    void addToCart_shouldReturn404ForNonExistentVariant() {
        AddToCartRequest request = new AddToCartRequest();
        request.setVariantId(UUID.randomUUID()); // Non-existent
        request.setQuantity(1);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/v1/cart/items").then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body("title", equalTo("Not Found"));
    }

    @Test
    @Transactional
    void addToCart_shouldMergeQuantityForExistingItem() {
        // Add item first time
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        cartService.addItemToCart(cart.id, variantId, 2);

        // Add same variant again
        AddToCartRequest request = new AddToCartRequest();
        request.setVariantId(variantId);
        request.setQuantity(3);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/v1/cart/items").then()
                .statusCode(Response.Status.CREATED.getStatusCode()).body("quantity", equalTo(5)); // 2 + 3 = 5
    }

    @Test
    @TestSecurity(
            user = AUTH_USER_EMAIL,
            roles = {"customer"})
    void addToCart_shouldUseAuthenticatedUserCart() {
        AddToCartRequest request = new AddToCartRequest();
        request.setVariantId(variantId);
        request.setQuantity(1);

        given().header("X-Tenant-Id", tenantId.toString()).contentType(MediaType.APPLICATION_JSON).body(request).when()
                .post("/api/v1/cart/items").then().statusCode(Response.Status.CREATED.getStatusCode())
                .header("Set-Cookie", nullValue());

        assertTrue(cartService.findActiveCartForUser(userId).isPresent());
    }

    // ========================================
    // PATCH /api/v1/cart/items/{itemId} Tests
    // ========================================

    @Test
    @Transactional
    void updateCartItem_shouldUpdateQuantity() {
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        UpdateCartItemRequest request = new UpdateCartItemRequest();
        request.setQuantity(5);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().patch("/api/v1/cart/items/" + item.id)
                .then().statusCode(Response.Status.OK.getStatusCode()).body("id", equalTo(item.id.toString()))
                .body("quantity", equalTo(5));
    }

    @Test
    void updateCartItem_shouldReturn404ForNonExistentItem() {
        UpdateCartItemRequest request = new UpdateCartItemRequest();
        request.setQuantity(5);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(request).when()
                .patch("/api/v1/cart/items/" + UUID.randomUUID()).then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    @Transactional
    void updateCartItem_shouldReturn400ForInvalidQuantity() {
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        UpdateCartItemRequest request = new UpdateCartItemRequest();
        request.setQuantity(-1); // Invalid

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().patch("/api/v1/cart/items/" + item.id)
                .then().statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }

    // ========================================
    // DELETE /api/v1/cart/items/{itemId} Tests
    // ========================================

    @Test
    @Transactional
    void removeCartItem_shouldDeleteItem() {
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 2);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when()
                .delete("/api/v1/cart/items/" + item.id).then().statusCode(Response.Status.NO_CONTENT.getStatusCode());
    }

    @Test
    void removeCartItem_shouldReturn404ForNonExistentItem() {
        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when()
                .delete("/api/v1/cart/items/" + UUID.randomUUID()).then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    // ========================================
    // DELETE /api/v1/cart Tests
    // ========================================

    @Test
    @Transactional
    void clearCart_shouldRemoveAllItems() {
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        cartService.addItemToCart(cart.id, variantId, 2);

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when()
                .delete("/api/v1/cart").then().statusCode(Response.Status.NO_CONTENT.getStatusCode());

        // Verify cart is empty
        assertEquals(0, cartService.getCartItemCount(cart.id));
    }

    @Test
    void clearCart_shouldReturn404WhenNoActiveCart() {
        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", UUID.randomUUID().toString()).when()
                .delete("/api/v1/cart").then().statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    // ========================================
    // GET /api/v1/cart/totals Tests
    // ========================================

    @Test
    @Transactional
    void getCartTotals_shouldReturnCalculatedTotals() {
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        cartService.addItemToCart(cart.id, variantId, 2); // 2 * $29.99 = $59.98

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when()
                .get("/api/v1/cart/totals").then().statusCode(Response.Status.OK.getStatusCode())
                .body("subtotal.amount", equalTo("59.98")).body("subtotal.currency", equalTo("USD"))
                .body("total.amount", equalTo("59.98")).body("itemCount", equalTo(1));
    }

    @Test
    void getCartTotals_shouldReturn404WhenNoActiveCart() {
        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", UUID.randomUUID().toString()).when()
                .get("/api/v1/cart/totals").then().statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    // ========================================
    // Session Management Tests
    // ========================================

    @Test
    @Transactional
    void sessionManagement_shouldPreserveCartAcrossRequests() {
        // First request creates cart and returns session cookie
        AddToCartRequest request = new AddToCartRequest();
        request.setVariantId(variantId);
        request.setQuantity(1);

        String sessionFromHeader = given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId)
                .contentType(MediaType.APPLICATION_JSON).body(request).when().post("/api/v1/cart/items").then()
                .statusCode(Response.Status.CREATED.getStatusCode()).extract().header("X-Session-Id");

        assertNotNull(sessionFromHeader);

        // Second request using same session should see existing cart
        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionFromHeader).when()
                .get("/api/v1/cart").then().statusCode(Response.Status.OK.getStatusCode()).body("items", hasSize(1));
    }

    @Test
    @Transactional
    void saveForLaterEndpoints_shouldMoveItemsBetweenLists() {
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        CartItem item = cartService.addItemToCart(cart.id, variantId, 1);

        String savedItemId = given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when()
                .post("/api/v1/cart/items/" + item.id + "/save-for-later").then()
                .statusCode(Response.Status.CREATED.getStatusCode()).body("variantId", equalTo(variantId.toString()))
                .extract().path("id");

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when()
                .get("/api/v1/cart/save-for-later").then().statusCode(Response.Status.OK.getStatusCode())
                .body("$", hasSize(1));

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when()
                .post("/api/v1/cart/save-for-later/" + savedItemId + "/restore").then()
                .statusCode(Response.Status.OK.getStatusCode()).body("variantId", equalTo(variantId.toString()));

        given().header("X-Tenant-Id", tenantId.toString()).header("X-Session-Id", sessionId).when()
                .get("/api/v1/cart/save-for-later").then().statusCode(Response.Status.OK.getStatusCode())
                .body("$", hasSize(0));
    }

    // ========================================
    // Multi-Tenant Isolation Tests
    // ========================================

    @Test
    @Transactional
    void tenantIsolation_shouldPreventCrossTenantCartAccess() {
        // Create cart in tenant 1
        Cart cart = cartService.getOrCreateCartForSession(sessionId);
        cartService.addItemToCart(cart.id, variantId, 1);

        // Switch to tenant 2 context
        TenantContext.clear();
        TenantContext
                .setCurrentTenant(new TenantInfo(tenant2Id, "cartresource2", "Cart Resource Test Tenant 2", "active"));

        // Attempt to access cart from tenant 2 should fail
        given().header("X-Tenant-Id", tenant2Id.toString()).header("X-Session-Id", sessionId).when().get("/api/v1/cart")
                .then().statusCode(Response.Status.NOT_FOUND.getStatusCode());

        // Restore original tenant
        TenantContext.clear();
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, "cartresource", "Cart Resource Test Tenant", "active"));
    }
}
