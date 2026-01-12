package villagecompute.storefront.platformops.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.platformops.data.models.ImpersonationSession;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.data.models.PlatformCommand;
import villagecompute.storefront.platformops.data.repositories.ImpersonationSessionRepository;
import villagecompute.storefront.platformops.data.repositories.PlatformAdminRoleRepository;
import villagecompute.storefront.platformops.data.repositories.PlatformCommandRepository;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Integration tests for Platform Admin REST API.
 *
 * <p>
 * Tests:
 * <ul>
 * <li>Suspend/reactivate stores with audit logging</li>
 * <li>Plan management (get plan, change plan)</li>
 * <li>Impersonation lifecycle (start, stop, current)</li>
 * <li>Platform metrics aggregation</li>
 * <li>RBAC enforcement for platform admin permissions</li>
 * <li>Tenant isolation verification</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T1: Platform admin backend</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 4.0</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.19.13</li>
 * </ul>
 */
@QuarkusTest
class PlatformAdminResourceIT {

    @Inject
    EntityManager entityManager;

    @Inject
    PlatformAdminRoleRepository platformAdminRoleRepository;

    @Inject
    PlatformCommandRepository platformCommandRepository;

    @Inject
    ImpersonationSessionRepository impersonationSessionRepository;

    private Tenant tenant1;
    private Tenant tenant2;
    private User user1;
    private PlatformAdminRole adminRole;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up test data
        entityManager.createQuery("DELETE FROM ImpersonationSession").executeUpdate();
        entityManager.createQuery("DELETE FROM PlatformCommand").executeUpdate();
        entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        entityManager.createQuery("DELETE FROM PlatformAdminRole").executeUpdate();

        // Create test tenants
        tenant1 = createTenant("test-store-1", "Test Store 1");
        tenant2 = createTenant("test-store-2", "Test Store 2");

        // Create test user
        user1 = createUser(tenant1, "user1@test.com");

        // Create platform admin role
        adminRole = createPlatformAdmin("admin@platform.com");
    }

    @AfterEach
    @Transactional
    void tearDown() {
        entityManager.createQuery("DELETE FROM ImpersonationSession").executeUpdate();
        entityManager.createQuery("DELETE FROM PlatformCommand").executeUpdate();
        entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        entityManager.createQuery("DELETE FROM PlatformAdminRole").executeUpdate();
    }

    // --- Suspend/Reactivate Tests ---

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testSuspendStore() {
        String requestBody = String.format("""
                {
                  "reason": "Terms of service violation - ticket #12345",
                  "ticketNumber": "OPS-12345"
                }
                """);

        Response response = given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/platform/stores/" + tenant1.id + "/suspend").then().statusCode(200)
                .body("message", equalTo("Store suspended successfully"))
                .body("storeId", equalTo(tenant1.id.toString())).extract().response();

        // Verify tenant status updated
        Tenant updated = entityManager.find(Tenant.class, tenant1.id);
        assertEquals("suspended", updated.status);

        Map<String, Boolean> flagStates = getKillSwitchStates(tenant1.id);
        assertEquals(Boolean.TRUE, flagStates.get("checkout.kill-switch"));
        assertEquals(Boolean.TRUE, flagStates.get("admin.access.disabled"));
        assertEquals(Boolean.TRUE, flagStates.get("storefront.maintenance-mode"));

        // Verify audit command logged
        List<PlatformCommand> commands = platformCommandRepository.findByAction("suspend_store", 0, 10);
        assertEquals(1, commands.size());
        PlatformCommand command = commands.get(0);
        assertEquals("suspend_store", command.action);
        assertEquals(tenant1.id, command.targetId);
        assertNotNull(command.reason);
        assertTrue(command.reason.contains("Terms of service"));
        assertEquals("OPS-12345", command.ticketNumber);
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testReactivateStore() {
        // Suspend via API to ensure kill switches are enabled
        String suspendRequest = """
                {
                  "reason": "Investigation underway - ticket #88888",
                  "ticketNumber": "OPS-88888"
                }
                """;
        given().contentType(ContentType.JSON).body(suspendRequest)
                .post("/api/v1/platform/stores/" + tenant1.id + "/suspend").then().statusCode(200);

        String requestBody = """
                {
                  "reason": "Investigation resolved - ticket #54321",
                  "ticketNumber": "OPS-54321"
                }
                """;

        Response response = given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/platform/stores/" + tenant1.id + "/reactivate").then().statusCode(200)
                .body("message", equalTo("Store reactivated successfully"))
                .body("storeId", equalTo(tenant1.id.toString())).extract().response();

        // Verify tenant status updated
        Tenant updated = entityManager.find(Tenant.class, tenant1.id);
        assertEquals("active", updated.status);

        Map<String, Boolean> flagStates = getKillSwitchStates(tenant1.id);
        assertEquals(Boolean.FALSE, flagStates.get("checkout.kill-switch"));
        assertEquals(Boolean.FALSE, flagStates.get("admin.access.disabled"));
        assertEquals(Boolean.FALSE, flagStates.get("storefront.maintenance-mode"));

        // Verify audit command logged
        List<PlatformCommand> commands = platformCommandRepository.findByAction("reactivate_store", 0, 10);
        assertEquals(1, commands.size());
        assertEquals("OPS-54321", commands.get(0).ticketNumber);
    }

    // --- Plan Management Tests ---

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testGetStorePlan() {
        Response response = given().when().get("/api/v1/platform/stores/" + tenant1.id + "/plan").then().statusCode(200)
                .body("tenantId", equalTo(tenant1.id.toString())).body("subdomain", equalTo(tenant1.subdomain))
                .body("currentPlan", notNullValue()).extract().response();
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testChangeStorePlan() {
        String requestBody = """
                {
                  "newPlan": "enterprise",
                  "reason": "Customer requested upgrade due to increased traffic - ticket #99999",
                  "ticketNumber": "TICKET-99999"
                }
                """;

        Response response = given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/platform/stores/" + tenant1.id + "/plan").then().statusCode(200)
                .body("message", equalTo("Plan changed successfully")).body("newPlan", equalTo("enterprise")).extract()
                .response();

        // Verify tenant settings updated
        Tenant updated = entityManager.find(Tenant.class, tenant1.id);
        JsonObject settings = new JsonObject(updated.settings);
        assertEquals("enterprise", settings.getString("plan"));

        // Verify audit command logged
        List<PlatformCommand> commands = platformCommandRepository.findByAction("change_plan", 0, 10);
        assertEquals(1, commands.size());
        PlatformCommand command = commands.get(0);
        assertEquals(tenant1.id, command.targetId);
        assertEquals("TICKET-99999", command.ticketNumber);
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testChangeStorePlanValidatesReason() {
        String requestBody = """
                {
                  "newPlan": "enterprise",
                  "reason": "Short"
                }
                """;

        given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/platform/stores/" + tenant1.id + "/plan").then().statusCode(400)
                .body("detail", equalTo("Plan change reason must be at least 10 characters"));
    }

    // --- Impersonation Tests ---

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testStartImpersonation() {
        String requestBody = String.format("""
                {
                  "targetTenantId": "%s",
                  "targetUserId": "%s",
                  "reason": "Customer support investigation for payment issue",
                  "ticketNumber": "SUPPORT-12345"
                }
                """, tenant1.id, user1.id);

        Response response = given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/platform/impersonation/start").then().statusCode(200).body("sessionId", notNullValue())
                .body("targetTenantId", equalTo(tenant1.id.toString()))
                .body("targetUserId", equalTo(user1.id.toString()))
                .body("platformAdminEmail", equalTo("admin@platform.com")).body("expiresAt", notNullValue()).extract()
                .response();

        UUID sessionId = UUID.fromString(response.jsonPath().getString("sessionId"));

        // Verify session persisted
        ImpersonationSession session = entityManager.find(ImpersonationSession.class, sessionId);
        assertNotNull(session);
        assertEquals(tenant1.id, session.targetTenant.id);
        assertEquals(user1.id, session.targetUserId);
        assertEquals("SUPPORT-12345", session.ticketNumber);

        // Verify audit command logged
        List<PlatformCommand> commands = platformCommandRepository.findByAction("impersonate_start", 0, 10);
        assertEquals(1, commands.size());
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testStartImpersonationValidatesTicketNumber() {
        String requestBody = String.format("""
                {
                  "targetTenantId": "%s",
                  "reason": "Customer support investigation for payment issue"
                }
                """, tenant1.id);

        given().contentType(ContentType.JSON).body(requestBody).when().post("/api/v1/platform/impersonation/start")
                .then().statusCode(400).body("detail", equalTo("Support ticket number is required for impersonation"));
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testStopImpersonation() {
        // First start an impersonation session
        ImpersonationSession session = createImpersonationSession(adminRole.id, tenant1, user1);

        given().when().delete("/api/v1/platform/impersonation/stop").then().statusCode(200).body("message",
                equalTo("Impersonation session ended successfully"));

        // Verify session ended
        ImpersonationSession updated = entityManager.find(ImpersonationSession.class, session.id);
        assertNotNull(updated.endedAt);

        // Verify audit command logged
        List<PlatformCommand> commands = platformCommandRepository.findByAction("impersonate_stop", 0, 10);
        assertEquals(1, commands.size());
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testRenewImpersonationSession() {
        ImpersonationSession session = createImpersonationSession(adminRole.id, tenant1, user1);
        OffsetDateTime previousStart = session.startedAt;

        given().when().post("/api/v1/platform/impersonation/renew").then().statusCode(200)
                .body("sessionId", equalTo(session.id.toString())).body("expiresAt", notNullValue());

        ImpersonationSession refreshed = entityManager.find(ImpersonationSession.class, session.id);
        assertTrue(refreshed.startedAt.isAfter(previousStart));

        List<PlatformCommand> commands = platformCommandRepository.findByAction("impersonate_renew", 0, 10);
        assertEquals(1, commands.size());
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testGetCurrentImpersonation() {
        // Start a session
        ImpersonationSession session = createImpersonationSession(adminRole.id, tenant1, user1);

        Response response = given().when().get("/api/v1/platform/impersonation/current").then().statusCode(200)
                .body("sessionId", equalTo(session.id.toString()))
                .body("targetTenantId", equalTo(tenant1.id.toString())).extract().response();
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testGetCurrentImpersonationWhenNone() {
        given().when().get("/api/v1/platform/impersonation/current").then().statusCode(404).body("detail",
                equalTo("No active impersonation session"));
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testImpersonationAutoExpiresWhenPastTtl() {
        ImpersonationSession session = createImpersonationSession(adminRole.id, tenant1, user1);
        session.startedAt = session.startedAt.minusHours(3);
        entityManager.merge(session);
        entityManager.flush();

        given().when().get("/api/v1/platform/impersonation/current").then().statusCode(404).body("detail",
                equalTo("No active impersonation session"));

        ImpersonationSession expired = entityManager.find(ImpersonationSession.class, session.id);
        assertNotNull(expired.endedAt);

        List<PlatformCommand> commands = platformCommandRepository.findByAction("impersonate_expire", 0, 10);
        assertEquals(1, commands.size());
    }

    // --- Platform Metrics Tests ---

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testGetPlatformMetrics() {
        Response response = given().when().get("/api/v1/platform/metrics").then().statusCode(200)
                .body("totalStores", equalTo(2)) // Created in setUp
                .body("activeStores", notNullValue()).body("dataFreshnessTimestamp", notNullValue()).extract()
                .response();
    }

    @Test
    @TestSecurity(
            user = "admin@platform.com",
            roles = {"platform_admin"})
    void testGetPlatformMetricsWithDateRange() {
        Response response = given().queryParam("startDate", "2024-01-01").queryParam("endDate", "2024-12-31").when()
                .get("/api/v1/platform/metrics").then().statusCode(200).body("totalStores", notNullValue())
                .body("dataFreshnessTimestamp", notNullValue()).extract().response();
    }

    // --- RBAC Tests ---

    @Test
    void testUnauthorizedAccessDenied() {
        given().when().get("/api/v1/platform/stores").then().statusCode(401); // Unauthenticated
    }

    // --- Helper Methods ---

    @Transactional
    Tenant createTenant(String subdomain, String name) {
        Tenant tenant = new Tenant();
        tenant.subdomain = subdomain;
        tenant.name = name;
        tenant.status = "active";
        tenant.settings = "{\"plan\":\"basic\"}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        return tenant;
    }

    @Transactional
    User createUser(Tenant tenant, String email) {
        User user = new User();
        user.tenant = tenant;
        user.email = email;
        user.firstName = "Test";
        user.lastName = "User";
        user.status = "active";
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        entityManager.persist(user);
        entityManager.flush();
        return user;
    }

    @Transactional
    PlatformAdminRole createPlatformAdmin(String email) {
        PlatformAdminRole role = new PlatformAdminRole();
        role.email = email;
        role.role = PlatformAdminRole.ROLE_SUPER_ADMIN;
        role.status = "active";
        role.mfaEnforced = true;

        // Grant all permissions
        JsonArray permissions = new JsonArray();
        permissions.add(PlatformAdminRole.PERMISSION_VIEW_STORES);
        permissions.add(PlatformAdminRole.PERMISSION_SUSPEND_TENANT);
        permissions.add(PlatformAdminRole.PERMISSION_IMPERSONATE);
        permissions.add(PlatformAdminRole.PERMISSION_VIEW_AUDIT);
        permissions.add(PlatformAdminRole.PERMISSION_VIEW_HEALTH);
        role.permissions = permissions.encode();

        entityManager.persist(role);
        entityManager.flush();
        return role;
    }

    @Transactional
    ImpersonationSession createImpersonationSession(UUID adminId, Tenant targetTenant, User targetUser) {
        ImpersonationSession session = new ImpersonationSession();
        session.platformAdminId = adminId;
        session.platformAdminEmail = "admin@platform.com";
        session.targetTenant = targetTenant;
        session.targetUserId = targetUser != null ? targetUser.id : null;
        session.targetUserEmail = targetUser != null ? targetUser.email : null;
        session.reason = "Test impersonation session";
        session.ticketNumber = "TEST-123";
        session.startedAt = OffsetDateTime.now();
        entityManager.persist(session);
        entityManager.flush();
        return session;
    }

    @Transactional
    Map<String, Boolean> getKillSwitchStates(UUID tenantId) {
        List<FeatureFlag> flags = entityManager
                .createQuery("SELECT ff FROM FeatureFlag ff WHERE ff.tenant.id = :tenantId", FeatureFlag.class)
                .setParameter("tenantId", tenantId).getResultList();
        Map<String, Boolean> states = new HashMap<>();
        for (FeatureFlag flag : flags) {
            states.put(flag.flagKey, flag.enabled);
        }
        return states;
    }
}
