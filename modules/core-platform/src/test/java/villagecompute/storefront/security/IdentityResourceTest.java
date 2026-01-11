package villagecompute.storefront.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for IdentityResource placeholder endpoints. Verifies that endpoints return HTTP 501 and enforce
 * tenant context presence.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I1.T5: Identity skeleton with placeholder endpoints</li>
 * <li>Architecture Overview Section 4.0: Identity & Session Service</li>
 * </ul>
 *
 * <p>
 * <strong>Run with:</strong> {@code mvn test -Dtest=IdentityResourceTest}
 */
@QuarkusTest
public class IdentityResourceTest {

    @Inject
    EntityManager entityManager;

    private UUID testTenantId;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clear ThreadLocal before each test
        TenantContext.clear();

        boolean integrityDisabled = false;
        try {
            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
            integrityDisabled = true;
        } catch (Exception e) {
            // PostgreSQL doesn't support SET REFERENTIAL_INTEGRITY, which is fine
        }

        // Clean up existing test data
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        entityManager.flush();

        // Intentionally do not re-enable referential integrity during tests to avoid interfering with other suites

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "identitytest";
        tenant.name = "Identity Test Store";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        testTenantId = tenant.id;
    }

    /**
     * Test health endpoint returns 501 Not Implemented with tenant context.
     */
    @Test
    public void testHealthEndpoint_ReturnsNotImplemented() {
        given().header("Host", "identitytest.villagecompute.com").when().get("/api/v1/identity/health").then()
                .statusCode(Response.Status.NOT_IMPLEMENTED.getStatusCode()).body("status", equalTo("not_implemented"))
                .body("message", containsString("Identity service placeholder"))
                .body("message", containsString("tenant:"));
    }

    /**
     * Test login endpoint returns 501 Not Implemented with tenant context.
     */
    @Test
    public void testLoginEndpoint_ReturnsNotImplemented() {
        given().header("Host", "identitytest.villagecompute.com").when().get("/api/v1/identity/login").then()
                .statusCode(Response.Status.NOT_IMPLEMENTED.getStatusCode()).body("error", equalTo("not_implemented"))
                .body("message", containsString("Login endpoint not yet implemented"));
    }

    /**
     * Test refresh endpoint returns 501 Not Implemented with tenant context.
     */
    @Test
    public void testRefreshEndpoint_ReturnsNotImplemented() {
        given().header("Host", "identitytest.villagecompute.com").when().get("/api/v1/identity/refresh").then()
                .statusCode(Response.Status.NOT_IMPLEMENTED.getStatusCode()).body("error", equalTo("not_implemented"))
                .body("message", containsString("Token refresh endpoint not yet implemented"));
    }

    /**
     * Test sessions endpoint returns 501 Not Implemented with tenant context.
     */
    @Test
    public void testSessionsEndpoint_ReturnsNotImplemented() {
        given().header("Host", "identitytest.villagecompute.com").when().get("/api/v1/identity/sessions").then()
                .statusCode(Response.Status.NOT_IMPLEMENTED.getStatusCode()).body("error", equalTo("not_implemented"))
                .body("message", containsString("Session list endpoint not yet implemented"));
    }

    /**
     * Test that endpoints fail gracefully when tenant context is missing. This verifies that the filter integration is
     * correct and endpoints check for tenant context.
     */
    @Test
    public void testEndpoints_WithoutTenantContext_ReturnNotFound() {
        // When Host header points to unknown tenant, filter returns 404 before reaching the endpoint
        given().header("Host", "unknown.villagecompute.com").when().get("/api/v1/identity/health").then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }
}
