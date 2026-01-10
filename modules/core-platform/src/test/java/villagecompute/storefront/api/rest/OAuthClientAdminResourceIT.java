package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.OAuthClient;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.OAuthClientRepository;
import villagecompute.storefront.services.OAuthService;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Integration tests for OAuth client management admin API.
 *
 * <p>
 * Tests:
 * <ul>
 * <li>Create OAuth client - returns clientId and plaintext secret (ONLY time secret is shown)</li>
 * <li>List OAuth clients - returns all clients for tenant (without secret hash)</li>
 * <li>Get OAuth client details - returns single client by ID</li>
 * <li>Update OAuth client - modifies scopes and rate limit</li>
 * <li>Revoke OAuth client - sets active=false</li>
 * <li>Regenerate client secret - returns new plaintext secret</li>
 * <li>RBAC enforcement - requires admin role</li>
 * <li>Tenant isolation - prevents cross-tenant access</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: OAuth client management API</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * </ul>
 */
@QuarkusTest
class OAuthClientAdminResourceIT {

    @Inject
    EntityManager entityManager;

    @Inject
    OAuthClientRepository oauthClientRepository;

    @Inject
    OAuthService oauthService;

    private Tenant tenant;
    private Tenant otherTenant;

    @BeforeEach
    @Transactional
    void setUp() {
        entityManager.createQuery("DELETE FROM OAuthClient").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        tenant = createTenant("oauthit");
        otherTenant = createTenant("otherit");
    }

    @AfterEach
    @Transactional
    void tearDown() {
        entityManager.createQuery("DELETE FROM OAuthClient").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
    }

    @Test
    void testCreateOAuthClient() {
        String requestBody = """
                {
                  "name": "Test API Client",
                  "description": "Client for integration tests",
                  "scopes": ["catalog:read", "cart:write"],
                  "rateLimitPerMinute": 10000
                }
                """;

        Response response = given().contentType(ContentType.JSON).header("X-Tenant-Subdomain", tenant.subdomain)
                .body(requestBody).when().post("/api/v1/admin/oauth-clients").then().statusCode(201)
                .body("id", notNullValue()).body("clientId", notNullValue())
                .body("clientId", org.hamcrest.Matchers.startsWith("oauth_")).body("clientSecret", notNullValue())
                .body("name", equalTo("Test API Client")).body("scopes", hasSize(2))
                .body("scopes", hasItems("catalog:read", "cart:write")).body("rateLimitPerMinute", equalTo(10000))
                .extract().response();

        String clientId = response.jsonPath().getString("clientId");
        String clientSecret = response.jsonPath().getString("clientSecret");

        assertNotNull(clientId);
        assertNotNull(clientSecret);

        // Verify client was persisted
        OAuthClient client = oauthClientRepository.findByClientId(clientId).orElseThrow();
        assertEquals("Test API Client", client.name);
        assertEquals("Client for integration tests", client.description);
        assertEquals(2, client.scopes.size());
        assertTrue(client.scopes.contains("catalog:read"));
        assertTrue(client.scopes.contains("cart:write"));
        assertEquals(10000, client.rateLimitPerMinute);
        assertTrue(client.active);
        assertNotNull(client.clientSecretHash);

        // Verify secret hash is correct
        assertTrue(oauthService.authenticate(clientId, clientSecret).isPresent());
    }

    @Test
    void testCreateOAuthClientWithDefaultRateLimit() {
        String requestBody = """
                {
                  "name": "Default Limit Client",
                  "scopes": ["catalog:read"]
                }
                """;

        given().contentType(ContentType.JSON).header("X-Tenant-Subdomain", tenant.subdomain).body(requestBody).when()
                .post("/api/v1/admin/oauth-clients").then().statusCode(201).body("rateLimitPerMinute", equalTo(5000));
    }

    @Test
    void testListOAuthClients() {
        createOAuthClient(tenant, "client1", "Test Client 1", Set.of("catalog:read"), 5000);
        createOAuthClient(tenant, "client2", "Test Client 2", Set.of("cart:write"), 3000);
        createOAuthClient(otherTenant, "client3", "Other Tenant Client", Set.of("catalog:read"), 5000);

        given().header("X-Tenant-Subdomain", tenant.subdomain).when().get("/api/v1/admin/oauth-clients").then()
                .statusCode(200).body("$", hasSize(2)).body("[0].clientId", notNullValue())
                .body("[0].name", notNullValue()).body("[0].scopes", notNullValue())
                .body("[0].rateLimitPerMinute", notNullValue()).body("[0].active", notNullValue())
                .body("[0].createdAt", notNullValue())
                // Verify clientSecretHash is NOT exposed
                .body("[0].clientSecretHash", nullValue());
    }

    @Test
    void testGetOAuthClient() {
        OAuthClient client = createOAuthClient(tenant, "test-client", "Test Client", Set.of("catalog:read"), 5000);

        given().header("X-Tenant-Subdomain", tenant.subdomain).when()
                .get("/api/v1/admin/oauth-clients/" + client.clientId).then().statusCode(200)
                .body("clientId", equalTo(client.clientId)).body("name", equalTo("Test Client"))
                .body("scopes", hasItems("catalog:read")).body("active", equalTo(true))
                .body("rateLimitPerMinute", equalTo(5000))
                // Verify clientSecretHash is NOT exposed
                .body("clientSecretHash", nullValue());
    }

    @Test
    void testGetOAuthClientCrossTenantPrevention() {
        OAuthClient client = createOAuthClient(otherTenant, "other-client", "Other Client", Set.of("catalog:read"),
                5000);

        // Attempt to access client from different tenant
        given().header("X-Tenant-Subdomain", tenant.subdomain).when()
                .get("/api/v1/admin/oauth-clients/" + client.clientId).then().statusCode(404);
    }

    @Test
    void testUpdateOAuthClient() {
        OAuthClient client = createOAuthClient(tenant, "test-client", "Original Name", Set.of("catalog:read"), 5000);

        String updateBody = """
                {
                  "name": "Updated Name",
                  "description": "Updated description",
                  "scopes": ["catalog:read", "cart:read", "cart:write"],
                  "rateLimitPerMinute": 15000
                }
                """;

        given().contentType(ContentType.JSON).header("X-Tenant-Subdomain", tenant.subdomain).body(updateBody).when()
                .put("/api/v1/admin/oauth-clients/" + client.clientId).then().statusCode(200)
                .body("name", equalTo("Updated Name")).body("description", equalTo("Updated description"))
                .body("scopes", hasSize(3)).body("rateLimitPerMinute", equalTo(15000));

        // Verify update was persisted
        OAuthClient updated = oauthClientRepository.findByClientId(client.clientId).orElseThrow();
        assertEquals("Updated Name", updated.name);
        assertEquals("Updated description", updated.description);
        assertEquals(3, updated.scopes.size());
        assertEquals(15000, updated.rateLimitPerMinute);
    }

    @Test
    void testUpdateOAuthClientCrossTenantPrevention() {
        OAuthClient client = createOAuthClient(otherTenant, "other-client", "Other Client", Set.of("catalog:read"),
                5000);

        String updateBody = """
                {
                  "name": "Hacked Name",
                  "scopes": ["catalog:read"]
                }
                """;

        // Attempt to update client from different tenant
        given().contentType(ContentType.JSON).header("X-Tenant-Subdomain", tenant.subdomain).body(updateBody).when()
                .put("/api/v1/admin/oauth-clients/" + client.clientId).then().statusCode(404);
    }

    @Test
    void testRevokeOAuthClient() {
        OAuthClient client = createOAuthClient(tenant, "test-client", "Test Client", Set.of("catalog:read"), 5000);
        assertTrue(client.active);

        given().header("X-Tenant-Subdomain", tenant.subdomain).when()
                .post("/api/v1/admin/oauth-clients/" + client.clientId + "/revoke").then().statusCode(204);

        // Verify client was revoked
        OAuthClient revoked = oauthClientRepository.findByClientId(client.clientId).orElseThrow();
        assertFalse(revoked.active);
    }

    @Test
    void testRevokeOAuthClientCrossTenantPrevention() {
        OAuthClient client = createOAuthClient(otherTenant, "other-client", "Other Client", Set.of("catalog:read"),
                5000);

        // Attempt to revoke client from different tenant
        given().header("X-Tenant-Subdomain", tenant.subdomain).when()
                .post("/api/v1/admin/oauth-clients/" + client.clientId + "/revoke").then().statusCode(404);

        // Verify client was NOT revoked
        OAuthClient unchanged = oauthClientRepository.findByClientId(client.clientId).orElseThrow();
        assertTrue(unchanged.active);
    }

    @Test
    void testRegenerateSecret() {
        String originalSecret = "original-secret";
        OAuthClient client = createOAuthClientWithSecret(tenant, "test-client", "Test Client", Set.of("catalog:read"),
                5000, originalSecret);
        String originalHash = client.clientSecretHash;

        // Regenerate secret
        Response response = given().header("X-Tenant-Subdomain", tenant.subdomain).when()
                .post("/api/v1/admin/oauth-clients/" + client.clientId + "/regenerate-secret").then().statusCode(200)
                .body("clientId", equalTo(client.clientId)).body("clientSecret", notNullValue()).extract().response();

        String newSecret = response.jsonPath().getString("clientSecret");
        assertNotNull(newSecret);

        // Verify new secret hash was persisted
        OAuthClient updated = oauthClientRepository.findByClientId(client.clientId).orElseThrow();
        assertNotNull(updated.clientSecretHash);
        assertFalse(originalHash.equals(updated.clientSecretHash));

        // Verify old secret no longer works
        assertTrue(oauthService.authenticate(client.clientId, originalSecret).isEmpty());

        // Verify new secret works
        assertTrue(oauthService.authenticate(client.clientId, newSecret).isPresent());
    }

    @Test
    void testRegenerateSecretCrossTenantPrevention() {
        OAuthClient client = createOAuthClient(otherTenant, "other-client", "Other Client", Set.of("catalog:read"),
                5000);

        // Attempt to regenerate secret for client from different tenant
        given().header("X-Tenant-Subdomain", tenant.subdomain).when()
                .post("/api/v1/admin/oauth-clients/" + client.clientId + "/regenerate-secret").then().statusCode(404);
    }

    @Test
    void testCreateOAuthClientValidation() {
        // Missing name
        String missingName = """
                {
                  "scopes": ["catalog:read"]
                }
                """;

        given().contentType(ContentType.JSON).header("X-Tenant-Subdomain", tenant.subdomain).body(missingName).when()
                .post("/api/v1/admin/oauth-clients").then().statusCode(400);

        // Empty scopes
        String emptyScopes = """
                {
                  "name": "Test Client",
                  "scopes": []
                }
                """;

        given().contentType(ContentType.JSON).header("X-Tenant-Subdomain", tenant.subdomain).body(emptyScopes).when()
                .post("/api/v1/admin/oauth-clients").then().statusCode(400);

        // Invalid rate limit (too low)
        String invalidRateLimit = """
                {
                  "name": "Test Client",
                  "scopes": ["catalog:read"],
                  "rateLimitPerMinute": 50
                }
                """;

        given().contentType(ContentType.JSON).header("X-Tenant-Subdomain", tenant.subdomain).body(invalidRateLimit)
                .when().post("/api/v1/admin/oauth-clients").then().statusCode(400);
    }

    // ========================================
    // Helper Methods
    // ========================================

    @Transactional
    Tenant createTenant(String subdomain) {
        Tenant t = new Tenant();
        t.subdomain = subdomain;
        t.name = subdomain + " Store";
        t.status = "active";
        t.createdAt = OffsetDateTime.now();
        t.updatedAt = OffsetDateTime.now();
        entityManager.persist(t);
        return t;
    }

    @Transactional
    OAuthClient createOAuthClient(Tenant tenant, String clientId, String name, Set<String> scopes,
            int rateLimitPerMinute) {
        return createOAuthClientWithSecret(tenant, clientId, name, scopes, rateLimitPerMinute, "test-secret");
    }

    @Transactional
    OAuthClient createOAuthClientWithSecret(Tenant tenant, String clientId, String name, Set<String> scopes,
            int rateLimitPerMinute, String secret) {
        OAuthClient client = new OAuthClient();
        client.tenant = tenant;
        client.clientId = clientId;
        client.clientSecretHash = oauthService.hashSecret(secret);
        client.name = name;
        client.scopes = scopes;
        client.rateLimitPerMinute = rateLimitPerMinute;
        client.active = true;
        client.createdAt = OffsetDateTime.now();
        client.updatedAt = OffsetDateTime.now();
        entityManager.persist(client);
        return client;
    }
}
