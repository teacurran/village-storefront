package villagecompute.storefront.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.CustomDomain;
import villagecompute.storefront.data.models.Tenant;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests for TenantResolverService. Verifies resolution logic for custom domains and subdomains, cache
 * invalidation, and edge cases.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I1.T5: Tenant Access Gateway Prototype</li>
 * <li>ADR-001 Tenancy Strategy (Section 2: Tenant Resolution Flow)</li>
 * </ul>
 *
 * <p>
 * <strong>Run with:</strong> {@code mvn test -Dtest=TenantResolverServiceTest}
 */
@QuarkusTest
public class TenantResolverServiceTest {

    @Inject
    TenantResolverService resolverService;

    @Inject
    EntityManager entityManager;

    private UUID testTenantId;

    @BeforeEach
    @Transactional
    public void setupTestData() {
        boolean integrityDisabled = false;
        try {
            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
            integrityDisabled = true;
        } catch (Exception e) {
            // PostgreSQL doesn't support SET REFERENTIAL_INTEGRITY, which is fine
        }

        // Clean up existing test data
        entityManager.createQuery("DELETE FROM CustomDomain").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        entityManager.flush();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "testshop";
        tenant.name = "Test Shop";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        testTenantId = tenant.id;

        // Create custom domain
        CustomDomain customDomain = new CustomDomain();
        customDomain.tenant = tenant;
        customDomain.domain = "shop.example.com";
        customDomain.verified = true;
        customDomain.verificationToken = "test-token";
        customDomain.createdAt = OffsetDateTime.now();
        customDomain.updatedAt = OffsetDateTime.now();
        entityManager.persist(customDomain);

        // Create unverified custom domain (should not resolve)
        CustomDomain unverifiedDomain = new CustomDomain();
        unverifiedDomain.tenant = tenant;
        unverifiedDomain.domain = "unverified.example.com";
        unverifiedDomain.verified = false;
        unverifiedDomain.verificationToken = "unverified-token";
        unverifiedDomain.createdAt = OffsetDateTime.now();
        unverifiedDomain.updatedAt = OffsetDateTime.now();
        entityManager.persist(unverifiedDomain);

        entityManager.flush();
    }

    /**
     * Test subdomain resolution returns correct tenant info.
     */
    @Test
    public void testResolveFromSubdomain_ValidSubdomain() {
        TenantInfo result = resolverService.resolveFromSubdomain("testshop.villagecompute.com");

        assertNotNull(result, "Should resolve valid subdomain");
        assertEquals(testTenantId, result.tenantId(), "Should return correct tenant ID");
        assertEquals("testshop", result.subdomain(), "Should return correct subdomain");
        assertEquals("Test Shop", result.name(), "Should return correct tenant name");
        assertEquals("active", result.status(), "Should return correct status");
    }

    /**
     * Test subdomain resolution returns null for unknown subdomain.
     */
    @Test
    public void testResolveFromSubdomain_UnknownSubdomain() {
        TenantInfo result = resolverService.resolveFromSubdomain("nonexistent.villagecompute.com");

        assertNull(result, "Should return null for unknown subdomain");
    }

    /**
     * Test subdomain resolution returns null for invalid domain format.
     */
    @Test
    public void testResolveFromSubdomain_InvalidFormat() {
        TenantInfo result = resolverService.resolveFromSubdomain("example.com");

        assertNull(result, "Should return null for invalid domain format");
    }

    /**
     * Test custom domain resolution returns correct tenant info.
     */
    @Test
    public void testResolveFromCustomDomain_VerifiedDomain() {
        TenantInfo result = resolverService.resolveFromCustomDomain("shop.example.com");

        assertNotNull(result, "Should resolve verified custom domain");
        assertEquals(testTenantId, result.tenantId(), "Should return correct tenant ID");
        assertEquals("testshop", result.subdomain(), "Should return correct subdomain");
    }

    /**
     * Test custom domain resolution returns null for unverified domain.
     */
    @Test
    public void testResolveFromCustomDomain_UnverifiedDomain() {
        TenantInfo result = resolverService.resolveFromCustomDomain("unverified.example.com");

        assertNull(result, "Should return null for unverified custom domain");
    }

    /**
     * Test custom domain resolution returns null for unknown domain.
     */
    @Test
    public void testResolveFromCustomDomain_UnknownDomain() {
        TenantInfo result = resolverService.resolveFromCustomDomain("unknown.example.com");

        assertNull(result, "Should return null for unknown custom domain");
    }

    /**
     * Test full resolution flow (custom domain first, then subdomain fallback).
     */
    @Test
    public void testResolveTenant_CustomDomainFirst() {
        // Custom domain should resolve
        TenantInfo result = resolverService.resolveTenant("shop.example.com", false);

        assertNotNull(result, "Should resolve via custom domain");
        assertEquals(testTenantId, result.tenantId(), "Should return correct tenant ID");
    }

    /**
     * Test full resolution flow falls back to subdomain.
     */
    @Test
    public void testResolveTenant_SubdomainFallback() {
        // Should fall back to subdomain resolution
        TenantInfo result = resolverService.resolveTenant("testshop.villagecompute.com", false);

        assertNotNull(result, "Should resolve via subdomain fallback");
        assertEquals(testTenantId, result.tenantId(), "Should return correct tenant ID");
    }

    /**
     * Test resolution returns null for blank hostname.
     */
    @Test
    public void testResolveTenant_BlankHostname() {
        TenantInfo result1 = resolverService.resolveTenant(null, false);
        TenantInfo result2 = resolverService.resolveTenant("", false);
        TenantInfo result3 = resolverService.resolveTenant("   ", false);

        assertNull(result1, "Should return null for null hostname");
        assertNull(result2, "Should return null for empty hostname");
        assertNull(result3, "Should return null for blank hostname");
    }

    /**
     * Test cache invalidation does not throw errors (cache may or may not be available in tests).
     */
    @Test
    public void testCacheInvalidation() {
        // These should not throw exceptions even if cache is disabled
        resolverService.invalidateCache("shop.example.com");
        resolverService.invalidateAllCache();
    }
}
