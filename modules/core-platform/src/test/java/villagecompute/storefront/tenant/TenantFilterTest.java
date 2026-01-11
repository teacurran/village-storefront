package villagecompute.storefront.tenant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TenantFilterTest {

    @Inject
    EntityManager entityManager;

    private static final List<String> RLS_TABLES = List.of("users", "products", "product_variants", "carts",
            "cart_items");

    private static final boolean POSTGRES_RLS_AVAILABLE = ConfigProvider.getConfig()
            .getOptionalValue("quarkus.datasource.db-kind", String.class)
            .map(value -> "postgresql".equalsIgnoreCase(value))
            .orElse(false);

    private static boolean rlsInstalled = false;

    private UUID activeTenantId;
    private UUID suspendedTenantId;

    @BeforeAll
    @Transactional
    public void installRlsSupport() {
        if (!isPostgres()) {
            return;
        }
        if (rlsInstalled) {
            return;
        }

        installTenantFunctions();
        enableRlsPolicies();
        rlsInstalled = true;
    }

    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Clear ThreadLocal before each test
        TenantContext.clear();
        clearDatabaseTenant();

        boolean integrityDisabled = false;
        try {
            if (!isPostgres()) {
                entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
                integrityDisabled = true;
            }

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
            entityManager.createQuery("DELETE FROM Collection").executeUpdate();
            entityManager.createQuery("DELETE FROM Category").executeUpdate();
            entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
            entityManager.createQuery("DELETE FROM IdempotencyKey").executeUpdate();
            entityManager.createQuery("DELETE FROM CustomDomain").executeUpdate();
            entityManager.createQuery("DELETE FROM PaymentTender").executeUpdate();
            entityManager.createQuery("DELETE FROM StoreCreditTransaction").executeUpdate();
            entityManager.createQuery("DELETE FROM StoreCreditAccount").executeUpdate();
            entityManager.createQuery("DELETE FROM GiftCardTransaction").executeUpdate();
            entityManager.createQuery("DELETE FROM GiftCard").executeUpdate();
            entityManager.createQuery("DELETE FROM PaymentIntent").executeUpdate();
            entityManager.createQuery("DELETE FROM User").executeUpdate();
            entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
            entityManager.flush();
        } finally {
            if (integrityDisabled) {
                entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
            }
        }

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

    private void setDatabaseTenant(UUID tenantId) {
        if (!isPostgres()) {
            return;
        }
        entityManager.createNativeQuery("SELECT set_current_tenant_id(:tenantId)").setParameter("tenantId", tenantId)
                .getSingleResult();
    }

    private void clearDatabaseTenant() {
        if (!isPostgres()) {
            return;
        }
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', '', FALSE)").getSingleResult();
    }

    private void installTenantFunctions() {
        if (!isPostgres()) {
            return;
        }
        entityManager.createNativeQuery("""
                CREATE OR REPLACE FUNCTION set_current_tenant_id(p_tenant_id UUID)
                RETURNS VOID AS $$
                BEGIN
                    PERFORM set_config('app.tenant_id', p_tenant_id::TEXT, FALSE);
                    PERFORM set_config('row_security', 'on', FALSE);
                END;
                $$ LANGUAGE plpgsql SECURITY DEFINER;
                """).executeUpdate();

        entityManager.createNativeQuery("""
                CREATE OR REPLACE FUNCTION get_current_tenant_id()
                RETURNS UUID AS $$
                BEGIN
                    RETURN NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID;
                EXCEPTION
                    WHEN others THEN
                        RETURN NULL;
                END;
                $$ LANGUAGE plpgsql STABLE;
                """).executeUpdate();
    }

    private void enableRlsPolicies() {
        if (!isPostgres()) {
            return;
        }
        for (String table : RLS_TABLES) {
            String policyName = table + "_tenant_isolation_policy";
            entityManager.createNativeQuery("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY").executeUpdate();
            entityManager.createNativeQuery("DROP POLICY IF EXISTS " + policyName + " ON " + table).executeUpdate();
            entityManager.createNativeQuery(
                    "CREATE POLICY " + policyName + " ON " + table + " USING (tenant_id = get_current_tenant_id())")
                    .executeUpdate();

            Object[] status = (Object[]) entityManager
                    .createNativeQuery(
                            "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = :tableName")
                    .setParameter("tableName", table).getSingleResult();
            boolean rlsEnabled = Boolean.TRUE.equals(status[0]);
            boolean rlsForced = Boolean.TRUE.equals(status[1]);
            if (!rlsEnabled || !rlsForced) {
                throw new IllegalStateException("Failed to enable RLS for table " + table);
            }
        }
    }

    private boolean isPostgres() {
        return POSTGRES_RLS_AVAILABLE;
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
                .statusCode(Response.Status.SERVICE_UNAVAILABLE.getStatusCode())
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
        assertTrue(TenantContext.hasFeatureFlagSnapshot(), "Feature flag placeholder should be set");
        FeatureFlagSnapshot snapshot = TenantContext.getFeatureFlagSnapshot();
        assertEquals(testInfo.tenantId(), snapshot.tenantId());
        assertFalse(snapshot.hydrated(), "Snapshot should start as placeholder");

        // Clear context
        TenantContext.clear();
        assertFalse(TenantContext.hasContext());
        assertFalse(TenantContext.hasFeatureFlagSnapshot());
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

    /**
     * Test RLS enforcement prevents cross-tenant data access. Verifies that PostgreSQL Row Level Security policies
     * correctly isolate tenant data at the database layer.
     *
     * <p>
     * This test validates ADR-001 Section 3 (Row Level Security) by attempting to access another tenant's data when RLS
     * is active. The query should return empty results, not throw an exception.
     *
     * <p>
     * <strong>Note:</strong> This test relies on Quarkus Dev Services to start PostgreSQL automatically during the test
     * lifecycle.
     */
    @Test
    @Transactional
    public void testRLSEnforcement_PreventsCrossTenantAccess() {
        Assumptions.assumeTrue(isPostgres(), "RLS enforcement requires PostgreSQL");
        // Create second tenant with a user
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "othertenant";
        tenant2.name = "Other Tenant";
        tenant2.status = "active";
        tenant2.settings = "{}";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2);
        entityManager.flush();

        villagecompute.storefront.data.models.User user2 = new villagecompute.storefront.data.models.User();
        user2.tenant = tenant2;
        user2.email = "user@othertenant.com";
        user2.status = "active";
        user2.firstName = "Other";
        user2.lastName = "User";
        user2.emailVerified = true;
        user2.createdAt = OffsetDateTime.now();
        user2.updatedAt = OffsetDateTime.now();
        setDatabaseTenant(tenant2.id);
        entityManager.persist(user2);
        entityManager.flush();
        clearDatabaseTenant();

        // Set tenant context to active tenant (teststore)
        TenantContext.setCurrentTenantId(activeTenantId);

        // Execute raw SQL to set PostgreSQL session variable for RLS
        entityManager.createNativeQuery("SELECT set_current_tenant_id(:tenantId)")
                .setParameter("tenantId", activeTenantId).getSingleResult();

        // Attempt to query users - should NOT see user from tenant2 due to RLS
        long userCount = entityManager.createQuery("SELECT COUNT(u) FROM User u WHERE u.email = :email", Long.class)
                .setParameter("email", "user@othertenant.com").getSingleResult();

        assertEquals(0, userCount, "RLS should prevent cross-tenant user access");

        // Verify we can see our own tenant's data (none created for active tenant in this test)
        long ownUserCount = entityManager
                .createQuery("SELECT COUNT(u) FROM User u WHERE u.tenant.id = :tenantId", Long.class)
                .setParameter("tenantId", activeTenantId).getSingleResult();

        assertEquals(0, ownUserCount, "Should have 0 users for active tenant");

        // Clean up
        setDatabaseTenant(tenant2.id);
        entityManager.remove(user2);
        clearDatabaseTenant();
        entityManager.remove(tenant2);
        TenantContext.clear();
    }

    /**
     * Test RLS enforcement on products table. Validates that tenant isolation works correctly for catalog module
     * tables.
     *
     * <p>
     * <strong>Note:</strong> This test relies on Quarkus Dev Services to start PostgreSQL automatically during the test
     * lifecycle.
     */
    @Test
    @Transactional
    public void testRLSEnforcement_ProductsIsolation() {
        Assumptions.assumeTrue(isPostgres(), "RLS enforcement requires PostgreSQL");
        // Create product for active tenant
        villagecompute.storefront.data.models.Product product1 = new villagecompute.storefront.data.models.Product();
        product1.tenant = entityManager.find(Tenant.class, activeTenantId);
        product1.sku = "TEST-SKU-001";
        product1.name = "Test Product";
        product1.slug = "test-product";
        product1.type = "physical";
        product1.status = "active";
        product1.metadata = "{}";
        product1.createdAt = OffsetDateTime.now();
        product1.updatedAt = OffsetDateTime.now();
        setDatabaseTenant(activeTenantId);
        entityManager.persist(product1);

        // Create second tenant with a product
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "othertenant";
        tenant2.name = "Other Tenant";
        tenant2.status = "active";
        tenant2.settings = "{}";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant2);

        villagecompute.storefront.data.models.Product product2 = new villagecompute.storefront.data.models.Product();
        product2.tenant = tenant2;
        product2.sku = "TEST-SKU-002";
        product2.name = "Other Tenant Product";
        product2.slug = "other-product";
        product2.type = "physical";
        product2.status = "active";
        product2.metadata = "{}";
        product2.createdAt = OffsetDateTime.now();
        product2.updatedAt = OffsetDateTime.now();
        setDatabaseTenant(tenant2.id);
        entityManager.persist(product2);
        entityManager.flush();
        clearDatabaseTenant();

        // Set tenant context to active tenant
        TenantContext.setCurrentTenantId(activeTenantId);
        entityManager.createNativeQuery("SELECT set_current_tenant_id(:tenantId)")
                .setParameter("tenantId", activeTenantId).getSingleResult();

        // Query products - should only see product1, not product2
        long productCount = entityManager.createQuery("SELECT COUNT(p) FROM Product p", Long.class).getSingleResult();
        assertEquals(1, productCount, "RLS should only show current tenant's products");

        // Verify the product we see is the correct one
        villagecompute.storefront.data.models.Product foundProduct = entityManager
                .createQuery("SELECT p FROM Product p WHERE p.sku = :sku",
                        villagecompute.storefront.data.models.Product.class)
                .setParameter("sku", "TEST-SKU-001").getSingleResult();
        assertEquals(product1.id, foundProduct.id, "Should find product from current tenant");

        // Attempt to access other tenant's product by SKU - should find nothing
        long otherProductCount = entityManager
                .createQuery("SELECT COUNT(p) FROM Product p WHERE p.sku = :sku", Long.class)
                .setParameter("sku", "TEST-SKU-002").getSingleResult();
        assertEquals(0, otherProductCount, "RLS should prevent access to other tenant's products");

        // Clean up
        setDatabaseTenant(activeTenantId);
        entityManager.remove(product1);
        setDatabaseTenant(tenant2.id);
        entityManager.remove(product2);
        clearDatabaseTenant();
        entityManager.remove(tenant2);
        TenantContext.clear();
    }

    /**
     * Test that RLS helper functions work correctly.
     *
     * <p>
     * <strong>Note:</strong> This test relies on Quarkus Dev Services to start PostgreSQL automatically during the test
     * lifecycle.
     */
    @Test
    @Transactional
    public void testRLSHelperFunctions() {
        Assumptions.assumeTrue(isPostgres(), "RLS helper functions require PostgreSQL");
        // Set tenant context
        entityManager.createNativeQuery("SELECT set_current_tenant_id(:tenantId)")
                .setParameter("tenantId", activeTenantId).getSingleResult();

        // Verify get_current_tenant_id returns the correct value
        Object result = entityManager.createNativeQuery("SELECT get_current_tenant_id()").getSingleResult();
        assertNotNull(result, "get_current_tenant_id should return a value");
        assertEquals(activeTenantId.toString(), result.toString(), "Should return the tenant ID we set");

        // Clear the session variable
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', '', FALSE)").getSingleResult();

        // Verify get_current_tenant_id returns NULL when not set
        Object nullResult = entityManager.createNativeQuery("SELECT get_current_tenant_id()").getSingleResult();
        assertTrue(nullResult == null, "get_current_tenant_id should return NULL when session variable not set");
    }
}
