package villagecompute.storefront.headless;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.OAuthClient;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.OAuthService;
import villagecompute.storefront.services.RateLimitService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.cache.CacheManager;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for headless API OAuth scope enforcement.
 *
 * <p>
 * Tests validate that endpoints correctly enforce required scopes and reject requests with insufficient permissions.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T3: Headless API scope enforcement validation</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * </ul>
 */
@QuarkusTest
class HeadlessScopeEnforcementIT {

    private static final String SESSION_HEADER = "X-Session-Id";
    private static final String SESSION_ID = "55555555-3333-4444-5555-666666666666";

    private static final String CATALOG_ONLY_CLIENT_ID = "catalog-only-client";
    private static final String CATALOG_ONLY_SECRET = "catalog-secret";

    private static final String CART_READ_ONLY_CLIENT_ID = "cart-read-client";
    private static final String CART_READ_ONLY_SECRET = "cart-read-secret";

    private static final String CART_WRITE_CLIENT_ID = "cart-write-client";
    private static final String CART_WRITE_SECRET = "cart-write-secret";

    private static final String NO_SCOPES_CLIENT_ID = "no-scopes-client";
    private static final String NO_SCOPES_SECRET = "no-scopes-secret";

    private static final String ORDERS_READ_CLIENT_ID = "orders-read-client";
    private static final String ORDERS_READ_SECRET = "orders-read-secret";
    private static final String ORDERS_WRITE_CLIENT_ID = "orders-write-client";
    private static final String ORDERS_WRITE_SECRET = "orders-write-secret";
    private static final String CUSTOMER_READ_CLIENT_ID = "customer-read-client";
    private static final String CUSTOMER_READ_SECRET = "customer-read-secret";
    private static final String CUSTOMER_WRITE_CLIENT_ID = "customer-write-client";
    private static final String CUSTOMER_WRITE_SECRET = "customer-write-secret";

    @Inject
    EntityManager entityManager;

    @Inject
    OAuthService oauthService;

    @Inject
    RateLimitService rateLimitService;

    @Inject
    CacheManager cacheManager;

    private Tenant tenant;
    private ProductVariant variant;
    private String tenantHost;

    @BeforeEach
    @Transactional
    void setUp() {
        clearTenantCache();
        rateLimitService.clearAllBuckets();

        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
        entityManager.createQuery("DELETE FROM OAuthClient").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        tenant = createTenant("scopetest");
        tenantHost = tenant.subdomain + ".villagecompute.com";

        FeatureFlag flag = new FeatureFlag();
        flag.tenant = tenant;
        flag.flagKey = "headless.api.enabled";
        flag.enabled = true;
        flag.createdAt = OffsetDateTime.now();
        flag.updatedAt = OffsetDateTime.now();
        flag.owner = "headless-tests@villagecompute.dev";
        flag.lastReviewedAt = OffsetDateTime.now();
        entityManager.persist(flag);

        Product product = new Product();
        product.tenant = tenant;
        product.sku = "SCOPE-001";
        product.name = "Scope Test Product";
        product.slug = "scope-test-product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "SCOPE-V1";
        variant.name = "Scope Variant";
        variant.price = new BigDecimal("29.99");
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);

        // Create OAuth clients with different scopes
        entityManager.persist(
                createOAuthClient(tenant, CATALOG_ONLY_CLIENT_ID, CATALOG_ONLY_SECRET, Set.of("catalog:read"), 1000));
        entityManager.persist(
                createOAuthClient(tenant, CART_READ_ONLY_CLIENT_ID, CART_READ_ONLY_SECRET, Set.of("cart:read"), 1000));
        entityManager.persist(createOAuthClient(tenant, CART_WRITE_CLIENT_ID, CART_WRITE_SECRET,
                Set.of("cart:read", "cart:write"), 1000));
        entityManager.persist(createOAuthClient(tenant, NO_SCOPES_CLIENT_ID, NO_SCOPES_SECRET, Set.of(), 1000));
        entityManager.persist(
                createOAuthClient(tenant, ORDERS_READ_CLIENT_ID, ORDERS_READ_SECRET, Set.of("orders:read"), 1000));
        entityManager.persist(
                createOAuthClient(tenant, ORDERS_WRITE_CLIENT_ID, ORDERS_WRITE_SECRET, Set.of("orders:write"), 1000));
        entityManager.persist(createOAuthClient(tenant, CUSTOMER_READ_CLIENT_ID, CUSTOMER_READ_SECRET,
                Set.of("customer:read"), 1000));
        entityManager.persist(createOAuthClient(tenant, CUSTOMER_WRITE_CLIENT_ID, CUSTOMER_WRITE_SECRET,
                Set.of("customer:write"), 1000));

        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        rateLimitService.clearAllBuckets();
        TenantContext.clear();
        clearTenantCache();
    }

    // ========== Catalog Scope Tests ==========

    @Test
    void catalogRead_shouldSucceedWithCatalogReadScope() {
        headlessRequest(CATALOG_ONLY_CLIENT_ID, CATALOG_ONLY_SECRET).when().get("/api/v1/headless/catalog/products")
                .then().statusCode(200);
    }

    @Test
    void catalogRead_shouldFailWithoutCatalogReadScope() {
        headlessRequest(CART_READ_ONLY_CLIENT_ID, CART_READ_ONLY_SECRET).when().get("/api/v1/headless/catalog/products")
                .then().statusCode(403).body("detail", containsString("scope"));
    }

    @Test
    void catalogRead_shouldFailWithNoScopes() {
        headlessRequest(NO_SCOPES_CLIENT_ID, NO_SCOPES_SECRET).when().get("/api/v1/headless/catalog/products").then()
                .statusCode(403).body("detail", containsString("scope"));
    }

    @Test
    void catalogGetProduct_shouldSucceedWithCatalogReadScope() {
        headlessRequest(CATALOG_ONLY_CLIENT_ID, CATALOG_ONLY_SECRET).when()
                .get("/api/v1/headless/catalog/products/" + variant.product.id).then().statusCode(200);
    }

    @Test
    void catalogGetProduct_shouldFailWithoutCatalogReadScope() {
        headlessRequest(CART_WRITE_CLIENT_ID, CART_WRITE_SECRET).when()
                .get("/api/v1/headless/catalog/products/" + variant.product.id).then().statusCode(403)
                .body("detail", containsString("scope"));
    }

    // ========== Cart Read Scope Tests ==========

    @Test
    void cartGet_shouldFailWithoutCartReadScope() {
        headlessRequest(CATALOG_ONLY_CLIENT_ID, CATALOG_ONLY_SECRET).header(SESSION_HEADER, SESSION_ID).when()
                .get("/api/v1/headless/cart").then().statusCode(403).body("detail", containsString("scope"));
    }

    @Test
    void cartGet_shouldSucceedWithCartReadScope() {
        // Cart might not exist, so 404 is acceptable
        int statusCode = headlessRequest(CART_READ_ONLY_CLIENT_ID, CART_READ_ONLY_SECRET)
                .header(SESSION_HEADER, SESSION_ID).when().get("/api/v1/headless/cart").statusCode();
        // Either 200 (cart exists) or 404 (cart doesn't exist) is acceptable - but not 403
        org.junit.jupiter.api.Assertions.assertTrue(statusCode == 200 || statusCode == 404,
                "Expected 200 or 404, got: " + statusCode);
    }

    // ========== Cart Write Scope Tests ==========

    @Test
    void cartAdd_shouldFailWithoutCartWriteScope() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":1}", variant.id);

        headlessRequest(CART_READ_ONLY_CLIENT_ID, CART_READ_ONLY_SECRET).header(SESSION_HEADER, SESSION_ID)
                .contentType(ContentType.JSON).body(addRequest).when().post("/api/v1/headless/cart/items").then()
                .statusCode(403).body("detail", containsString("scope"));
    }

    @Test
    void cartAdd_shouldSucceedWithCartWriteScope() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":1}", variant.id);

        headlessRequest(CART_WRITE_CLIENT_ID, CART_WRITE_SECRET).header(SESSION_HEADER, SESSION_ID)
                .contentType(ContentType.JSON).body(addRequest).when().post("/api/v1/headless/cart/items").then()
                .statusCode(201);
    }

    @Test
    void cartAdd_shouldFailWithCatalogScopeOnly() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":1}", variant.id);

        headlessRequest(CATALOG_ONLY_CLIENT_ID, CATALOG_ONLY_SECRET).header(SESSION_HEADER, SESSION_ID)
                .contentType(ContentType.JSON).body(addRequest).when().post("/api/v1/headless/cart/items").then()
                .statusCode(403).body("detail", containsString("scope"));
    }

    @Test
    void cartUpdate_shouldFailWithoutCartWriteScope() {
        String updateRequest = "{\"quantity\":2}";

        headlessRequest(CART_READ_ONLY_CLIENT_ID, CART_READ_ONLY_SECRET).header(SESSION_HEADER, SESSION_ID)
                .contentType(ContentType.JSON).body(updateRequest).when()
                .patch("/api/v1/headless/cart/items/00000000-0000-0000-0000-000000000000").then().statusCode(403)
                .body("detail", containsString("scope"));
    }

    @Test
    void cartRemove_shouldFailWithoutCartWriteScope() {
        headlessRequest(CART_READ_ONLY_CLIENT_ID, CART_READ_ONLY_SECRET).header(SESSION_HEADER, SESSION_ID).when()
                .delete("/api/v1/headless/cart/items/00000000-0000-0000-0000-000000000000").then().statusCode(403)
                .body("detail", containsString("scope"));
    }

    @Test
    void cartClear_shouldFailWithoutCartWriteScope() {
        headlessRequest(CART_READ_ONLY_CLIENT_ID, CART_READ_ONLY_SECRET).header(SESSION_HEADER, SESSION_ID).when()
                .delete("/api/v1/headless/cart").then().statusCode(403).body("detail", containsString("scope"));
    }

    // ========== Orders Scope Tests ==========

    @Test
    void ordersRead_shouldSucceedWithOrdersReadScope() {
        headlessRequest(ORDERS_READ_CLIENT_ID, ORDERS_READ_SECRET).when().get("/api/v1/headless/orders").then()
                .statusCode(200);
    }

    @Test
    void ordersRead_shouldFailWithoutOrdersScope() {
        headlessRequest(CATALOG_ONLY_CLIENT_ID, CATALOG_ONLY_SECRET).when().get("/api/v1/headless/orders").then()
                .statusCode(403).body("detail", containsString("scope"));
    }

    @Test
    void ordersWrite_shouldSucceedWithOrdersWriteScope() {
        headlessRequest(ORDERS_WRITE_CLIENT_ID, ORDERS_WRITE_SECRET).contentType(ContentType.JSON).body("{}").when()
                .post("/api/v1/headless/orders").then().statusCode(201);
    }

    @Test
    void ordersWrite_shouldFailWithReadOnlyScope() {
        headlessRequest(ORDERS_READ_CLIENT_ID, ORDERS_READ_SECRET).contentType(ContentType.JSON).body("{}").when()
                .post("/api/v1/headless/orders").then().statusCode(403).body("detail", containsString("scope"));
    }

    // ========== Customer Scope Tests ==========

    @Test
    void customerProfile_shouldSucceedWithCustomerReadScope() {
        headlessRequest(CUSTOMER_READ_CLIENT_ID, CUSTOMER_READ_SECRET).when().get("/api/v1/headless/customer/profile")
                .then().statusCode(200);
    }

    @Test
    void customerProfile_shouldFailWithoutCustomerReadScope() {
        headlessRequest(CATALOG_ONLY_CLIENT_ID, CATALOG_ONLY_SECRET).when().get("/api/v1/headless/customer/profile")
                .then().statusCode(403).body("detail", containsString("scope"));
    }

    @Test
    void customerUpdate_shouldSucceedWithCustomerWriteScope() {
        headlessRequest(CUSTOMER_WRITE_CLIENT_ID, CUSTOMER_WRITE_SECRET).contentType(ContentType.JSON)
                .body("{\"firstName\":\"Updated\"}").when().patch("/api/v1/headless/customer/profile").then()
                .statusCode(200);
    }

    @Test
    void customerUpdate_shouldFailWithReadScopeOnly() {
        headlessRequest(CUSTOMER_READ_CLIENT_ID, CUSTOMER_READ_SECRET).contentType(ContentType.JSON)
                .body("{\"firstName\":\"Updated\"}").when().patch("/api/v1/headless/customer/profile").then()
                .statusCode(403).body("detail", containsString("scope"));
    }

    private RequestSpecification headlessRequest(String clientId, String secret) {
        return given().header("Authorization", buildBasicAuthHeader(clientId, secret)).header("Host", tenantHost)
                .contentType(ContentType.JSON);
    }

    private String buildBasicAuthHeader(String clientId, String clientSecret) {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
    }

    private Tenant createTenant(String subdomain) {
        Tenant newTenant = new Tenant();
        newTenant.subdomain = subdomain;
        newTenant.name = subdomain + " Tenant";
        newTenant.status = "active";
        newTenant.settings = "{}";
        newTenant.createdAt = OffsetDateTime.now();
        newTenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(newTenant);
        return newTenant;
    }

    private OAuthClient createOAuthClient(Tenant owner, String clientId, String secret, Set<String> scopes,
            int rateLimitPerMinute) {
        OAuthClient client = new OAuthClient();
        client.tenant = owner;
        client.clientId = clientId;
        client.clientSecretHash = oauthService.hashSecret(secret);
        client.name = clientId;
        client.description = "Scope test client";
        client.active = true;
        client.scopes.addAll(scopes);
        client.rateLimitPerMinute = rateLimitPerMinute;
        return client;
    }

    private void clearTenantCache() {
        cacheManager.getCache("tenant-cache").ifPresent(cache -> cache.invalidateAll().await().indefinitely());
    }
}
