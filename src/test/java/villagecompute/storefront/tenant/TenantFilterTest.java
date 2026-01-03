package villagecompute.storefront.tenant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

import villagecompute.storefront.data.models.CustomDomain;
import villagecompute.storefront.data.models.Tenant;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Comprehensive tests for tenant resolution filter. Covers subdomain resolution, custom domain resolution, edge cases,
 * and context propagation.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 2: Tenant Resolution Flow)</li>
 * <li>Task I1.T5: Tenant Access Gateway Prototype</li>
 * </ul>
 *
 * <p>
 * <strong>Run with:</strong> {@code mvn test -Dtest=TenantFilterTest}
 */
@QuarkusTest
public class TenantFilterTest {

    @Inject
    EntityManager entityManager;

    private UUID activeTenantId;
    private UUID suspendedTenantId;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clear ThreadLocal before each test
        TenantContext.clear();

        // Clean up any existing test data
        entityManager.createQuery("DELETE FROM ReportJob").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryAgingAggregate").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentPayoutAggregate").executeUpdate();
        entityManager.createQuery("DELETE FROM SalesByPeriodAggregate").executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLocation").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM CustomDomain").executeUpdate();
        entityManager.createQuery("DELETE FROM PaymentTender").executeUpdate();
        entityManager.createQuery("DELETE FROM StoreCreditTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM StoreCreditAccount").executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCardTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCard").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        entityManager.flush();

        // Create active tenant with subdomain
        Tenant activeTenant = new Tenant();
        activeTenant.subdomain = "teststore";
        activeTenant.name = "Test Store";
        activeTenant.status = "active";
        activeTenant.settings = "{}";
        activeTenant.createdAt = OffsetDateTime.now();
        activeTenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(activeTenant);
        entityManager.flush();
        activeTenantId = activeTenant.id;

        // Create suspended tenant
        Tenant suspendedTenant = new Tenant();
        suspendedTenant.subdomain = "suspended";
        suspendedTenant.name = "Suspended Store";
        suspendedTenant.status = "suspended";
        suspendedTenant.settings = "{}";
        suspendedTenant.createdAt = OffsetDateTime.now();
        suspendedTenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(suspendedTenant);
        entityManager.flush();
        suspendedTenantId = suspendedTenant.id;

        // Create verified custom domain for active tenant
        CustomDomain customDomain = new CustomDomain();
        customDomain.tenant = activeTenant;
        customDomain.domain = "shop.example.com";
        customDomain.verified = true;
        customDomain.verificationToken = "test-token";
        customDomain.createdAt = OffsetDateTime.now();
        customDomain.updatedAt = OffsetDateTime.now();
        entityManager.persist(customDomain);

        // Create unverified custom domain (should not resolve)
        CustomDomain unverifiedDomain = new CustomDomain();
        unverifiedDomain.tenant = activeTenant;
        unverifiedDomain.domain = "unverified.example.com";
        unverifiedDomain.verified = false;
        unverifiedDomain.verificationToken = "unverified-token";
        unverifiedDomain.createdAt = OffsetDateTime.now();
        unverifiedDomain.updatedAt = OffsetDateTime.now();
        entityManager.persist(unverifiedDomain);

        entityManager.flush();
    }

    @AfterEach
    public void cleanup() {
        // Ensure ThreadLocal is cleared after each test
        TenantContext.clear();
    }

    /**
     * Test subdomain resolution for active tenant. Verifies that subdomain.villagecompute.com resolves to correct
     * tenant.
     */
    @Test
    public void testSubdomainResolution_Active() {
        given().header("Host", "teststore.villagecompute.com").when().get("/api/v1/health").then()
                .statusCode(Response.Status.OK.getStatusCode());
    }

    /**
     * Test custom domain resolution for verified domain. Verifies that verified custom domain resolves to correct
     * tenant.
     */
    @Test
    public void testCustomDomainResolution_Verified() {
        given().header("Host", "shop.example.com").when().get("/api/v1/health").then()
                .statusCode(Response.Status.OK.getStatusCode());
    }

    /**
     * Test that unverified custom domain does NOT resolve.
     */
    @Test
    public void testCustomDomainResolution_Unverified() {
        given().header("Host", "unverified.example.com").when().get("/api/v1/health").then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode()).body(containsString("Store not found"));
    }

    /**
     * Test suspended tenant returns 403 Forbidden.
     */
    @Test
    public void testSuspendedTenant_ReturnsForbidden() {
        given().header("Host", "suspended.villagecompute.com").when().get("/api/v1/health").then()
                .statusCode(Response.Status.FORBIDDEN.getStatusCode())
                .body(containsString("Store temporarily unavailable"));
    }

    /**
     * Test unknown subdomain returns 404.
     */
    @Test
    public void testUnknownSubdomain_ReturnsNotFound() {
        given().header("Host", "nonexistent.villagecompute.com").when().get("/api/v1/health").then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode()).body(containsString("Store not found"));
    }

    /**
     * Test missing Host header returns 400.
     */
    @Test
    public void testMissingHostHeader_ReturnsBadRequest() {
        given().header("Host", " ").when().get("/api/v1/health").then()
                .statusCode(Response.Status.BAD_REQUEST.getStatusCode()).body(containsString("Missing Host header"));
    }

    /**
     * Test host with port number strips port correctly.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {"teststore.villagecompute.com:8080", "teststore.villagecompute.com:443",
                    "shop.example.com:3000"})
    public void testHostWithPort_StripsProperly(String hostWithPort) {
        given().header("Host", hostWithPort).when().get("/api/v1/health").then()
                .statusCode(Response.Status.OK.getStatusCode());
    }

    /**
     * Test case insensitivity in hostname resolution.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {"TESTSTORE.villagecompute.com", "TestStore.VillageCompute.COM", "SHOP.EXAMPLE.COM"})
    public void testHostnameCaseInsensitive(String hostVariant) {
        given().header("Host", hostVariant).when().get("/api/v1/health").then()
                .statusCode(Response.Status.OK.getStatusCode());
    }

    /**
     * Test invalid domain formats return 404.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {"invalid", "localhost", "192.168.1.1", "example.com", "subdomain.wrongdomain.com"})
    public void testInvalidDomainFormats_ReturnsNotFound(String invalidHost) {
        given().header("Host", invalidHost).when().get("/api/v1/health").then()
                .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    /**
     * Test TenantContext ThreadLocal behavior.
     */
    @Test
    public void testTenantContext_ThreadLocalBehavior() {
        // Initially no context
        assertFalse(TenantContext.hasContext());

        // Throws when accessing without context
        assertThrows(IllegalStateException.class, () -> TenantContext.getCurrentTenantId());

        // Set context
        TenantInfo testInfo = new TenantInfo(UUID.randomUUID(), "test", "Test Store", "active");
        TenantContext.setCurrentTenant(testInfo);

        // Now has context
        assertTrue(TenantContext.hasContext());
        assertNotNull(TenantContext.getCurrentTenantId());
        assertEquals(testInfo.tenantId(), TenantContext.getCurrentTenantId());

        // Clear context
        TenantContext.clear();
        assertFalse(TenantContext.hasContext());
    }

    /**
     * Test TenantInfo record validation.
     */
    @Test
    public void testTenantInfo_Validation() {
        UUID id = UUID.randomUUID();

        // Valid creation
        TenantInfo valid = new TenantInfo(id, "subdomain", "Name", "active");
        assertEquals(id, valid.tenantId());
        assertTrue(valid.isActive());
        assertFalse(valid.isSuspended());

        // Null tenantId
        assertThrows(IllegalArgumentException.class, () -> new TenantInfo(null, "sub", "name", "active"));

        // Null/blank subdomain
        assertThrows(IllegalArgumentException.class, () -> new TenantInfo(id, null, "name", "active"));
        assertThrows(IllegalArgumentException.class, () -> new TenantInfo(id, "", "name", "active"));

        // Null/blank name
        assertThrows(IllegalArgumentException.class, () -> new TenantInfo(id, "sub", null, "active"));
        assertThrows(IllegalArgumentException.class, () -> new TenantInfo(id, "sub", "", "active"));

        // Null/blank status
        assertThrows(IllegalArgumentException.class, () -> new TenantInfo(id, "sub", "name", null));
        assertThrows(IllegalArgumentException.class, () -> new TenantInfo(id, "sub", "name", ""));
    }

    /**
     * Test TenantInfo status helpers.
     */
    @Test
    public void testTenantInfo_StatusHelpers() {
        UUID id = UUID.randomUUID();

        TenantInfo active = new TenantInfo(id, "sub", "name", "active");
        assertTrue(active.isActive());
        assertFalse(active.isSuspended());

        TenantInfo suspended = new TenantInfo(id, "sub", "name", "suspended");
        assertFalse(suspended.isActive());
        assertTrue(suspended.isSuspended());

        TenantInfo deleted = new TenantInfo(id, "sub", "name", "deleted");
        assertFalse(deleted.isActive());
        assertFalse(deleted.isSuspended());
    }

    /**
     * Test CDI event validation.
     */
    @Test
    public void testCDIEvents_Validation() {
        TenantInfo info = new TenantInfo(UUID.randomUUID(), "sub", "name", "active");

        // Valid TenantResolved
        TenantResolved validResolved = new TenantResolved(info, "example.com");
        assertEquals(info, validResolved.tenantInfo());
        assertEquals("example.com", validResolved.hostname());

        // Null tenantInfo
        assertThrows(IllegalArgumentException.class, () -> new TenantResolved(null, "example.com"));

        // Null/blank hostname
        assertThrows(IllegalArgumentException.class, () -> new TenantResolved(info, null));
        assertThrows(IllegalArgumentException.class, () -> new TenantResolved(info, ""));

        // Valid TenantMissing
        TenantMissing validMissing = new TenantMissing("unknown.com", "not_found");
        assertEquals("unknown.com", validMissing.hostname());
        assertEquals("not_found", validMissing.reason());

        // Null/blank hostname
        assertThrows(IllegalArgumentException.class, () -> new TenantMissing(null, "reason"));
        assertThrows(IllegalArgumentException.class, () -> new TenantMissing("", "reason"));

        // Null/blank reason
        assertThrows(IllegalArgumentException.class, () -> new TenantMissing("host", null));
        assertThrows(IllegalArgumentException.class, () -> new TenantMissing("host", ""));
    }

    /**
     * Provide test cases for parameterized subdomain tests.
     */
    static Stream<Arguments> provideSubdomainTestCases() {
        return Stream.of(Arguments.of("teststore.villagecompute.com", true, "Valid subdomain"),
                Arguments.of("a.villagecompute.com", true, "Single char subdomain"),
                Arguments.of("my-store-123.villagecompute.com", true, "Subdomain with hyphens and numbers"),
                Arguments.of("-invalid.villagecompute.com", false, "Subdomain starting with hyphen"),
                Arguments.of("invalid-.villagecompute.com", false, "Subdomain ending with hyphen"),
                Arguments.of("test..villagecompute.com", false, "Double dot"),
                Arguments.of(".villagecompute.com", false, "Empty subdomain"));
    }
}
