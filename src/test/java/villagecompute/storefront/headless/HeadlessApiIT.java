package villagecompute.storefront.headless;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;

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
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.services.OAuthService;
import villagecompute.storefront.services.RateLimitService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

@QuarkusTest
class HeadlessApiIT {

    private static final String SESSION_HEADER = "X-Session-Id";
    private static final String SESSION_ID = "44444444-2222-3333-4444-555555555555";
    private static final String FULL_CLIENT_ID = "headless-full-client";
    private static final String FULL_CLIENT_SECRET = "full-secret";
    private static final String READ_ONLY_CLIENT_ID = "headless-read-only";
    private static final String READ_ONLY_SECRET = "read-only-secret";
    private static final String ROGUE_CLIENT_ID = "rogue-client";
    private static final String ROGUE_SECRET = "rogue-secret";

    @Inject
    EntityManager entityManager;

    @Inject
    OAuthService oauthService;

    @Inject
    CatalogService catalogService;

    @Inject
    RateLimitService rateLimitService;

    private Tenant tenant;
    private Tenant rogueTenant;
    private Product product;
    private ProductVariant variant;
    private String tenantHost;

    @BeforeEach
    @Transactional
    void setUp() {
        rateLimitService.clearAllBuckets();

        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
        entityManager.createQuery("DELETE FROM OAuthClient").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        tenant = createTenant("headlessit");
        rogueTenant = createTenant("rogueit");
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

        product = new Product();
        product.tenant = tenant;
        product.sku = "H-API-001";
        product.name = "Headless Test Product";
        product.slug = "headless-test-product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "H-API-V1";
        variant.name = "Headless Variant";
        variant.price = new BigDecimal("19.99");
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);

        entityManager.persist(createOAuthClient(tenant, FULL_CLIENT_ID, FULL_CLIENT_SECRET,
                Set.of("catalog:read", "cart:read", "cart:write"), 3));
        entityManager
                .persist(createOAuthClient(tenant, READ_ONLY_CLIENT_ID, READ_ONLY_SECRET, Set.of("catalog:read"), 3));
        entityManager.persist(
                createOAuthClient(rogueTenant, ROGUE_CLIENT_ID, ROGUE_SECRET, Set.of("catalog:read", "cart:write"), 3));

        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        rateLimitService.clearAllBuckets();
        TenantContext.clear();
    }

    @Test
    void shouldReturnCatalogResultsWithRateLimitHeaders() {
        headlessRequest(FULL_CLIENT_ID, FULL_CLIENT_SECRET).when().get("/api/v1/headless/catalog/products").then()
                .statusCode(200).contentType(ContentType.JSON).body("products.size()", greaterThanOrEqualTo(1))
                .body("pagination.page", equalTo(1)).header("X-RateLimit-Limit", equalTo("3"))
                .header("X-RateLimit-Remaining", equalTo("2")).header("X-RateLimit-Reset", matchesRegex("\\d+"));
    }

    @Test
    void shouldEnforceScopeForCartWrites() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":1}", variant.id);

        headlessRequest(READ_ONLY_CLIENT_ID, READ_ONLY_SECRET).header(SESSION_HEADER, SESSION_ID)
                .contentType(ContentType.JSON).body(addRequest).when().post("/api/v1/headless/cart/items").then()
                .statusCode(403).body("detail", containsString("scope"));
    }

    @Test
    void shouldAddItemToCart() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":2}", variant.id);

        headlessRequest(FULL_CLIENT_ID, FULL_CLIENT_SECRET).header(SESSION_HEADER, SESSION_ID)
                .contentType(ContentType.JSON).body(addRequest).when().post("/api/v1/headless/cart/items").then()
                .statusCode(201).body("variantId", equalTo(variant.id.toString())).body("quantity", equalTo(2));
    }

    @Test
    void shouldRateLimitAfterThreshold() {
        for (int i = 0; i < 4; i++) {
            var response = headlessRequest(FULL_CLIENT_ID, FULL_CLIENT_SECRET).when()
                    .get("/api/v1/headless/catalog/products");
            if (i < 3) {
                response.then().statusCode(200);
            } else {
                response.then().statusCode(429).body("title", equalTo("Too Many Requests")).header("Retry-After",
                        notNullValue());
            }
        }
    }

    @Test
    void shouldPreventCrossTenantAccess() {
        given().header("Authorization", buildBasicAuthHeader(ROGUE_CLIENT_ID, ROGUE_SECRET)).header("Host", tenantHost)
                .when().get("/api/v1/headless/catalog/products").then().statusCode(403)
                .body("detail", containsString("Client does not belong"));
    }

    @Test
    void shouldInvalidateSearchCacheAfterDelete() {
        String query = "headless";

        headlessRequest(FULL_CLIENT_ID, FULL_CLIENT_SECRET).queryParam("search", query)
                .get("/api/v1/headless/catalog/products").then().statusCode(200).body("products.size()", equalTo(1));

        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));
        catalogService.deleteProduct(product.id);
        TenantContext.clear();

        headlessRequest(FULL_CLIENT_ID, FULL_CLIENT_SECRET).queryParam("search", query)
                .get("/api/v1/headless/catalog/products").then().statusCode(200).body("products.size()", equalTo(0));
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
        client.description = "Headless test client";
        client.active = true;
        client.scopes.addAll(scopes);
        client.rateLimitPerMinute = rateLimitPerMinute;
        return client;
    }
}
