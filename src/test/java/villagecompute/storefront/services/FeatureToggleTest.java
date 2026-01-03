package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link FeatureToggle}. These tests run against the in-memory H2 database to verify
 * tenant-specific and global flag resolution plus cache invalidation semantics.
 */
@QuarkusTest
class FeatureToggleTest {

    private static final String TENANT_FLAG = "storefront.hero.beta";
    private static final String GLOBAL_FLAG = "platform.sales-banner";

    @Inject
    FeatureToggle featureToggle;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;

    @BeforeEach
    @Transactional
    void setUp() {
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM CustomDomain").executeUpdate();
        entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        Tenant tenant = new Tenant();
        tenant.subdomain = "featuretests";
        tenant.name = "Feature Toggle Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();

        tenantId = tenant.id;
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    void tenantFlagOverridesGlobal() {
        persistGlobalFlag(TENANT_FLAG, false);
        persistTenantFlag(TENANT_FLAG, true);

        assertTrue(featureToggle.isEnabled(TENANT_FLAG), "Tenant-specific flag should override global default");
        assertTrue(featureToggle.isEnabled(tenantId, TENANT_FLAG));
    }

    @Test
    @Transactional
    void fallsBackToGlobalFlagWhenNoTenantOverride() {
        persistGlobalFlag(GLOBAL_FLAG, true);

        assertTrue(featureToggle.isEnabled(GLOBAL_FLAG), "Global flag should be used when tenant override missing");
        assertTrue(featureToggle.isGlobalEnabled(GLOBAL_FLAG));
    }

    @Test
    void returnsFalseWhenFlagMissing() {
        assertFalse(featureToggle.isEnabled("nonexistent-flag"));
        assertFalse(featureToggle.isGlobalEnabled("nonexistent-flag"));
    }

    @Test
    void validatesFlagArguments() {
        assertThrows(IllegalArgumentException.class, () -> featureToggle.isEnabled(" "));
        assertThrows(IllegalArgumentException.class, () -> featureToggle.isEnabled(tenantId, null));
        assertThrows(IllegalArgumentException.class, () -> featureToggle.isGlobalEnabled(""));
    }

    @Test
    @Transactional
    void invalidateRefreshesCachedValue() {
        persistTenantFlag("feature.cache.test", false);
        assertFalse(featureToggle.isEnabled(tenantId, "feature.cache.test"));

        updateTenantFlag("feature.cache.test", true);

        featureToggle.invalidate(tenantId, "feature.cache.test");
        featureToggle.invalidateAll();
        assertTrue(featureToggle.isEnabled(tenantId, "feature.cache.test"),
                "Flag value should reflect latest database state after invalidation");
    }

    @Transactional
    void persistTenantFlag(String flagKey, boolean enabled) {
        FeatureFlag flag = new FeatureFlag();
        flag.tenant = entityManager.getReference(Tenant.class, tenantId);
        flag.flagKey = flagKey;
        flag.enabled = enabled;
        flag.config = "{}";
        flag.createdAt = OffsetDateTime.now();
        flag.updatedAt = OffsetDateTime.now();
        entityManager.persist(flag);
        entityManager.flush();
    }

    @Transactional
    void persistGlobalFlag(String flagKey, boolean enabled) {
        FeatureFlag flag = new FeatureFlag();
        flag.tenant = null;
        flag.flagKey = flagKey;
        flag.enabled = enabled;
        flag.config = "{}";
        flag.createdAt = OffsetDateTime.now();
        flag.updatedAt = OffsetDateTime.now();
        entityManager.persist(flag);
        entityManager.flush();
    }

    @Transactional
    void updateTenantFlag(String flagKey, boolean enabled) {
        FeatureFlag flag = entityManager
                .createQuery("SELECT ff FROM FeatureFlag ff WHERE ff.tenant.id = :tenantId AND ff.flagKey = :flagKey",
                        FeatureFlag.class)
                .setParameter("tenantId", tenantId).setParameter("flagKey", flagKey).setMaxResults(1).getSingleResult();
        flag.enabled = enabled;
        flag.updatedAt = OffsetDateTime.now();
        entityManager.merge(flag);
        entityManager.flush();
    }
}
