package villagecompute.storefront.platformops;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.platformops.data.models.ImpersonationSession;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.data.models.PlatformCommand;
import villagecompute.storefront.platformops.data.repositories.ImpersonationSessionRepository;
import villagecompute.storefront.platformops.data.repositories.PlatformCommandRepository;
import villagecompute.storefront.platformops.services.ImpersonationService;
import villagecompute.storefront.platformops.services.PlatformAdminService;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * Integration tests for Platform Admin Console.
 *
 * <p>
 * Tests cover:
 * <ul>
 * <li>Store directory listing and filtering</li>
 * <li>Impersonation session lifecycle (start, current, end)</li>
 * <li>Audit log querying with filters</li>
 * <li>RBAC enforcement (TODO: requires auth context)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console</li>
 * </ul>
 */
@QuarkusTest
public class PlatformAdminIT {

    @Inject
    PlatformAdminService platformAdminService;

    @Inject
    ImpersonationService impersonationService;

    @Inject
    PlatformCommandRepository platformCommandRepo;

    @Inject
    ImpersonationSessionRepository impersonationSessionRepo;

    private Tenant testTenant;
    private UUID platformAdminId;
    private String platformAdminEmail;
    private static final String PLATFORM_ADMIN_PERMISSIONS = "[\"view_stores\",\"suspend_tenant\",\"view_audit\",\"impersonate\",\"view_health\"]";
    private static final String PLATFORM_ADMIN_TEST_EMAIL = "admin@platform.test";

    @BeforeEach
    @Transactional
    public void setup() {
        // Create test tenant
        testTenant = new Tenant();
        testTenant.subdomain = "test-store-" + UUID.randomUUID().toString().substring(0, 8);
        testTenant.name = "Test Store";
        testTenant.status = "active";
        testTenant.createdAt = OffsetDateTime.now();
        testTenant.updatedAt = OffsetDateTime.now();
        testTenant.persist();

        // Mock platform admin identity
        platformAdminEmail = PLATFORM_ADMIN_TEST_EMAIL;
        PlatformAdminRole.delete("email = ?1", platformAdminEmail);
        PlatformAdminRole adminRole = new PlatformAdminRole();
        adminRole.email = platformAdminEmail;
        adminRole.role = PlatformAdminRole.ROLE_SUPER_ADMIN;
        adminRole.permissions = PLATFORM_ADMIN_PERMISSIONS;
        adminRole.mfaEnforced = true;
        adminRole.status = "active";
        adminRole.persist();

        platformAdminId = adminRole.id;
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testGetStoreDirectory() {
        given().when().get("/api/v1/platform/stores").then().statusCode(200).body("stores", notNullValue())
                .body("totalCount", notNullValue());
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testGetStoreDirectoryWithFilters() {
        given().queryParam("status", "active").queryParam("page", 0).queryParam("size", 10).when()
                .get("/api/v1/platform/stores").then().statusCode(200).body("page", equalTo(0))
                .body("size", equalTo(10));
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testGetStoreDetails() {
        given().pathParam("storeId", testTenant.id.toString()).when().get("/api/v1/platform/stores/{storeId}").then()
                .statusCode(200).body("id", equalTo(testTenant.id.toString()))
                .body("subdomain", equalTo(testTenant.subdomain));
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testSuspendStore() {
        Map<String, String> request = new HashMap<>();
        request.put("reason", "Violation of terms of service");

        given().contentType(ContentType.JSON).body(request).pathParam("storeId", testTenant.id.toString()).when()
                .post("/api/v1/platform/stores/{storeId}/suspend").then().statusCode(200)
                .body("message", notNullValue());

        // Verify store was suspended
        Tenant updated = reloadTenant(testTenant.id);
        assertEquals("suspended", updated.status);
    }

    @Test
    @Transactional
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testImpersonationLifecycle() {
        // Start impersonation
        Map<String, Object> startRequest = new HashMap<>();
        startRequest.put("targetTenantId", testTenant.id.toString());
        startRequest.put("targetUserId", null); // Tenant admin mode
        startRequest.put("reason", "Support ticket #12345 - investigating billing issue");
        startRequest.put("ticketNumber", "12345");

        String sessionId = given().contentType(ContentType.JSON).body(startRequest).when()
                .post("/api/v1/platform/impersonate").then().statusCode(200).body("sessionId", notNullValue())
                .body("targetTenantId", equalTo(testTenant.id.toString())).extract().path("sessionId");

        // Verify session created in database
        ImpersonationSession session = impersonationSessionRepo.findById(UUID.fromString(sessionId));
        assertNotNull(session);
        assertTrue(session.isActive());

        // Get current impersonation
        given().when().get("/api/v1/platform/impersonate/current").then().statusCode(200).body("sessionId",
                equalTo(sessionId));

        // End impersonation
        given().when().delete("/api/v1/platform/impersonate").then().statusCode(200);

        // Verify session ended
        session = impersonationSessionRepo.findById(UUID.fromString(sessionId));
        assertFalse(session.isActive());
        assertNotNull(session.endedAt);
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testImpersonationRequiresReason() {
        Map<String, Object> request = new HashMap<>();
        request.put("targetTenantId", testTenant.id.toString());
        request.put("reason", "short"); // Too short
        request.put("ticketNumber", "TICKET-1");

        given().contentType(ContentType.JSON).body(request).when().post("/api/v1/platform/impersonate").then()
                .statusCode(400).body("error", notNullValue());
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testImpersonationRequiresTicket() {
        Map<String, Object> request = new HashMap<>();
        request.put("targetTenantId", testTenant.id.toString());
        request.put("reason", "Valid support reason 12345");

        given().contentType(ContentType.JSON).body(request).when().post("/api/v1/platform/impersonate").then()
                .statusCode(400).body("error", notNullValue());
    }

    @Test
    @Transactional
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testAuditLogQuery() {
        // Create test audit entries
        PlatformCommand cmd = new PlatformCommand();
        cmd.actorType = "platform_admin";
        cmd.actorId = platformAdminId;
        cmd.actorEmail = platformAdminEmail;
        cmd.action = "suspend_store";
        cmd.targetType = "tenant";
        cmd.targetId = testTenant.id;
        cmd.reason = "Test suspension";
        cmd.persist();

        // Query audit log
        given().queryParam("action", "suspend_store").queryParam("page", 0).queryParam("size", 50).when()
                .get("/api/v1/platform/audit").then().statusCode(200).body("entries", notNullValue())
                .body("totalCount", notNullValue());
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testAuditLogPagination() {
        given().queryParam("page", 0).queryParam("size", 20).when().get("/api/v1/platform/audit").then().statusCode(200)
                .body("page", equalTo(0)).body("size", equalTo(20));
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testHealthMetrics() {
        given().when().get("/api/v1/platform/health").then().statusCode(200).body("timestamp", notNullValue())
                .body("tenantCount", notNullValue()).body("status", notNullValue());
    }

    @Test
    @TestSecurity(
            user = PLATFORM_ADMIN_TEST_EMAIL,
            roles = {"platform-admin"})
    public void testAuditLogStats() {
        given().queryParam("days", 7).when().get("/api/v1/platform/audit/stats").then().statusCode(200)
                .body("totalCommands", notNullValue()).body("lookbackDays", equalTo(7));
    }

    @Transactional
    Tenant reloadTenant(UUID tenantId) {
        return Tenant.findById(tenantId);
    }
}
