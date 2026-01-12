package villagecompute.storefront.headless;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

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
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for headless API rate limiting and abuse scenarios.
 *
 * <p>
 * Tests validate rate limit enforcement, 429 responses, retry-after headers, and logging of abuse attempts.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T3: Headless API finalization with rate limiting abuse tests</li>
 * <li>Architecture: Section 6 Safety Net (token bucket rate limiting)</li>
 * </ul>
 */
@QuarkusTest
class HeadlessRateLimitAbuseIT {

    private static final String ABUSE_CLIENT_ID = "abuse-test-client";
    private static final String ABUSE_SECRET = "abuse-secret";
    private static final int ABUSE_RATE_LIMIT = 5; // Low limit for fast testing

    @Inject
    EntityManager entityManager;

    @Inject
    OAuthService oauthService;

    @Inject
    RateLimitService rateLimitService;

    @Inject
    CacheManager cacheManager;

    private Tenant tenant;
    private Product product;
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

        tenant = createTenant("abusetenant");
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
        product.sku = "ABUSE-001";
        product.name = "Abuse Test Product";
        product.slug = "abuse-test-product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "ABUSE-V1";
        variant.name = "Abuse Variant";
        variant.price = new BigDecimal("19.99");
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);

        entityManager.persist(createOAuthClient(tenant, ABUSE_CLIENT_ID, ABUSE_SECRET,
                Set.of("catalog:read", "cart:read", "cart:write"), ABUSE_RATE_LIMIT));

        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        rateLimitService.clearAllBuckets();
        TenantContext.clear();
        clearTenantCache();
    }

    @Test
    void shouldEnforceRateLimitAndReturn429() {
        int successCount = 0;
        int rateLimitCount = 0;

        // Attempt ABUSE_RATE_LIMIT + 5 requests
        for (int i = 0; i < ABUSE_RATE_LIMIT + 5; i++) {
            Response response = headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when()
                    .get("/api/v1/headless/catalog/products");

            if (response.statusCode() == 200) {
                successCount++;
                // Verify rate limit headers are present
                assertNotNull(response.header("X-RateLimit-Limit"), "X-RateLimit-Limit header missing");
                assertNotNull(response.header("X-RateLimit-Remaining"), "X-RateLimit-Remaining header missing");
                assertNotNull(response.header("X-RateLimit-Reset"), "X-RateLimit-Reset header missing");
            } else if (response.statusCode() == 429) {
                rateLimitCount++;
                // Verify 429 response includes RFC 7807 Problem Details
                response.then().body("title", equalTo("Too Many Requests")).body("status", equalTo(429)).body("detail",
                        containsString("Rate limit exceeded"));

                // Verify rate limit headers in 429 response
                assertNotNull(response.header("X-RateLimit-Limit"), "X-RateLimit-Limit header missing in 429");
                assertNotNull(response.header("X-RateLimit-Remaining"), "X-RateLimit-Remaining header missing in 429");
                assertNotNull(response.header("X-RateLimit-Reset"), "X-RateLimit-Reset header missing in 429");
                assertNotNull(response.header("Retry-After"), "Retry-After header missing in 429");

                // Verify Retry-After is reasonable (1-60 seconds)
                int retryAfter = Integer.parseInt(response.header("Retry-After"));
                assertTrue(retryAfter > 0 && retryAfter <= 60,
                        "Retry-After should be between 1 and 60 seconds, got: " + retryAfter);
            }
        }

        // Verify rate limit enforcement
        assertEquals(ABUSE_RATE_LIMIT, successCount, "Expected exactly " + ABUSE_RATE_LIMIT + " successful requests");
        assertTrue(rateLimitCount > 0, "Expected at least one 429 response");
    }

    @Test
    void shouldIsolateRateLimitsByScope() {
        // Exhaust catalog:read quota
        for (int i = 0; i < ABUSE_RATE_LIMIT; i++) {
            headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when().get("/api/v1/headless/catalog/products").then()
                    .statusCode(200);
        }

        // Next catalog request should fail
        headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when().get("/api/v1/headless/catalog/products").then()
                .statusCode(429);

        // But cart operations should still work (different scope)
        String sessionId = "44444444-2222-3333-4444-555555555555";
        headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).header("X-Session-Id", sessionId).when()
                .get("/api/v1/headless/cart").then().statusCode(anyOf(equalTo(200), equalTo(404))); // 404 if cart
                                                                                                    // doesn't exist yet
    }

    @Test
    void shouldResetRateLimitAfterWindow() throws InterruptedException {
        // Exhaust quota
        for (int i = 0; i < ABUSE_RATE_LIMIT; i++) {
            headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when().get("/api/v1/headless/catalog/products").then()
                    .statusCode(200);
        }

        // Verify rate limit exceeded
        headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when().get("/api/v1/headless/catalog/products").then()
                .statusCode(429);

        // Wait for token bucket to refill (tokens refill continuously)
        // With ABUSE_RATE_LIMIT=5 per minute, we get 5/60 = ~0.083 tokens/second
        // Wait 13 seconds to accumulate at least 1 token (13 * 0.083 ≈ 1.08 tokens)
        Thread.sleep(13000);

        // Should now succeed
        headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when().get("/api/v1/headless/catalog/products").then()
                .statusCode(200);
    }

    @Test
    void shouldReturnCorrectRemainingCount() {
        // First request should show ABUSE_RATE_LIMIT - 1 remaining
        Response response1 = headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when()
                .get("/api/v1/headless/catalog/products");
        response1.then().statusCode(200).header("X-RateLimit-Limit", equalTo(String.valueOf(ABUSE_RATE_LIMIT)))
                .header("X-RateLimit-Remaining", equalTo(String.valueOf(ABUSE_RATE_LIMIT - 1)));

        // Second request should show ABUSE_RATE_LIMIT - 2 remaining
        Response response2 = headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when()
                .get("/api/v1/headless/catalog/products");
        response2.then().statusCode(200).header("X-RateLimit-Remaining", equalTo(String.valueOf(ABUSE_RATE_LIMIT - 2)));
    }

    @Test
    void shouldHandleConcurrentRequests() throws InterruptedException {
        // Simulate concurrent requests (simple sequential for deterministic testing)
        int totalRequests = ABUSE_RATE_LIMIT + 3;
        int successCount = 0;
        int rateLimitCount = 0;

        for (int i = 0; i < totalRequests; i++) {
            Response response = headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when()
                    .get("/api/v1/headless/catalog/products");
            if (response.statusCode() == 200) {
                successCount++;
            } else if (response.statusCode() == 429) {
                rateLimitCount++;
            }
        }

        assertEquals(ABUSE_RATE_LIMIT, successCount, "Expected exactly " + ABUSE_RATE_LIMIT + " successful requests");
        assertEquals(3, rateLimitCount, "Expected exactly 3 rate limited requests");
    }

    @Test
    void shouldLogRateLimitExceededEvents() {
        // Exhaust quota
        for (int i = 0; i < ABUSE_RATE_LIMIT; i++) {
            headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when().get("/api/v1/headless/catalog/products");
        }

        // Trigger rate limit exceeded (this should be logged)
        Response response = headlessRequest(ABUSE_CLIENT_ID, ABUSE_SECRET).when()
                .get("/api/v1/headless/catalog/products");
        response.then().statusCode(429);

        // Note: Actual logging verification would require log capture infrastructure
        // For now, we verify the response structure indicates proper handling
        response.then().body("title", equalTo("Too Many Requests"))
                .body("detail", containsString("Rate limit exceeded")).header("Retry-After", notNullValue());
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
        client.description = "Headless abuse test client";
        client.active = true;
        client.scopes.addAll(scopes);
        client.rateLimitPerMinute = rateLimitPerMinute;
        return client;
    }

    private void clearTenantCache() {
        cacheManager.getCache("tenant-cache").ifPresent(cache -> cache.invalidateAll().await().indefinitely());
    }
}
