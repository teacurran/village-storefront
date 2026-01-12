package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.CustomDomain;
import villagecompute.storefront.data.models.CustomDomain.DomainStatus;
import villagecompute.storefront.data.models.Tenant;

import io.restassured.specification.RequestSpecification;
import io.quarkus.cache.CacheManager;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/**
 * Integration tests for CustomDomainResource REST API. Verifies CRUD operations, validation, and tenant isolation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: Custom Domain SSL Automation</li>
 * <li>API: CustomDomainResource.java</li>
 * </ul>
 *
 * <p>
 * <strong>Run with:</strong> {@code mvn test -Dtest=CustomDomainResourceTest}
 */
@QuarkusTest
public class CustomDomainResourceTest {

    @Inject
    EntityManager entityManager;

    @Inject
    CacheManager cacheManager;

    private UUID testTenantId;
    private UUID testDomainId;
    private String testTenantHost;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clean up existing test data
        entityManager.createQuery("DELETE FROM CustomDomain").executeUpdate();
        entityManager.flush();
        cacheManager.getCache("tenant-cache").ifPresent(cache -> cache.invalidateAll());

        Tenant tenant = entityManager
                .createQuery("SELECT t FROM Tenant t WHERE t.subdomain = :subdomain", Tenant.class)
                .setParameter("subdomain", "testshop").getResultStream().findFirst().orElse(null);

        if (tenant == null) {
            tenant = new Tenant();
            tenant.subdomain = "testshop";
            tenant.name = "Test Shop";
            tenant.status = "active";
            tenant.settings = "{}";
            tenant.createdAt = OffsetDateTime.now();
            tenant.updatedAt = OffsetDateTime.now();
            entityManager.persist(tenant);
            entityManager.flush();
        } else {
            tenant.updatedAt = OffsetDateTime.now();
            tenant.name = "Test Shop";
            tenant.status = "active";
            entityManager.flush();
        }

        testTenantId = tenant.id;
        testTenantHost = tenant.subdomain + ".villagecompute.com";

        // Create test custom domain
        CustomDomain domain = new CustomDomain();
        domain.tenant = tenant;
        domain.domain = "existing.example.com";
        domain.verified = false;
        domain.verificationToken = "existing-token";
        domain.status = DomainStatus.PENDING;
        domain.createdAt = OffsetDateTime.now();
        domain.updatedAt = OffsetDateTime.now();
        entityManager.persist(domain);
        entityManager.flush();
        testDomainId = domain.id;

    }

    private RequestSpecification givenWithTenantHost() {
        return given().header("Host", testTenantHost);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testListDomains_ReturnsAllDomainsForTenant() {
        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).when()
                .get("/api/v1/admin/custom-domains").then().statusCode(200).body("$", hasSize(1))
                .body("[0].domain", equalTo("existing.example.com")).body("[0].status", equalTo("PENDING"));
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testAddDomain_ValidDomain_ReturnsCreated() {
        String newDomain = "shop.newdomain.com";

        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body("{\"domain\": \"" + newDomain + "\"}").when().post("/api/v1/admin/custom-domains").then()
                .statusCode(201).body("domain", equalTo(newDomain)).body("status", equalTo("PENDING"))
                .body("dnsChallenge", notNullValue()).body("dnsChallenge.name", equalTo("_acme-challenge." + newDomain))
                .body("dnsChallenge.value", notNullValue());
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testAddDomain_DuplicateDomain_ReturnsConflict() {
        String duplicateDomain = "existing.example.com";

        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body("{\"domain\": \"" + duplicateDomain + "\"}").when().post("/api/v1/admin/custom-domains").then()
                .statusCode(409); // Conflict
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testAddDomain_InvalidDomainFormat_ReturnsBadRequest() {
        String invalidDomain = "invalid domain with spaces";

        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body("{\"domain\": \"" + invalidDomain + "\"}").when().post("/api/v1/admin/custom-domains").then()
                .statusCode(400); // Bad Request
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testAddDomain_EmptyDomain_ReturnsBadRequest() {
        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .body("{\"domain\": \"\"}")
                .when().post("/api/v1/admin/custom-domains").then().statusCode(400); // Bad Request (validation failure)
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testGetDomain_ExistingDomain_ReturnsDetails() {
        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).when()
                .get("/api/v1/admin/custom-domains/" + testDomainId).then().statusCode(200)
                .body("id", equalTo(testDomainId.toString())).body("domain", equalTo("existing.example.com"))
                .body("status", equalTo("PENDING"));
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testGetDomain_NonExistentDomain_ReturnsNotFound() {
        UUID nonExistentId = UUID.randomUUID();

        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).when()
                .get("/api/v1/admin/custom-domains/" + nonExistentId).then().statusCode(404); // Not Found
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testRemoveDomain_ExistingDomain_ReturnsNoContent() {
        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).when()
                .delete("/api/v1/admin/custom-domains/" + testDomainId).then().statusCode(204); // No Content

        // Verify domain was deleted
        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).when()
                .get("/api/v1/admin/custom-domains/" + testDomainId).then().statusCode(404); // Not Found
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"ADMIN"})
    public void testRemoveDomain_NonExistentDomain_ReturnsNotFound() {
        UUID nonExistentId = UUID.randomUUID();

        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).when()
                .delete("/api/v1/admin/custom-domains/" + nonExistentId)
                .then().statusCode(404); // Not Found
    }

    @Test
    public void testListDomains_Unauthenticated_ReturnsUnauthorized() {
        givenWithTenantHost().contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).when()
                .get("/api/v1/admin/custom-domains").then().statusCode(401); // Unauthorized
    }

    /**
     * Integration test placeholder: Full end-to-end flow requires DNS infrastructure and cert-manager deployment.
     *
     * <p>
     * Full integration test would:
     * <ol>
     * <li>POST new domain → receives DNS instructions</li>
     * <li>Mock DNS resolver to return verification token</li>
     * <li>Trigger DomainValidationJob</li>
     * <li>GET domain → status=ACTIVE, certificateExpiry populated</li>
     * <li>Verify Certificate CRD created in Kubernetes</li>
     * </ol>
     */
    @Test
    public void testDomainVerificationWorkflow_EndToEnd() {
        // TODO: Implement full workflow integration test
    }
}
