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
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
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

    // ========================================================================
    // CACHE PRECEDENCE & OVERRIDE TESTS
    // ========================================================================

    @Test
    @Transactional
    void cachePrecedence_tenantOverrideDisablesGlobalDefault() {
        // Global flag enabled
        persistGlobalFlag("storefront.new-theme", true);
        // Tenant explicitly disables
        persistTenantFlag("storefront.new-theme", false);

        assertFalse(featureToggle.isEnabled(tenantId, "storefront.new-theme"),
                "Tenant override should disable feature even when global is enabled");
    }

    @Test
    @Transactional
    void cachePrecedence_tenantOverrideEnablesWhenGlobalMissing() {
        // No global flag
        persistTenantFlag("storefront.experimental", true);

        assertTrue(featureToggle.isEnabled(tenantId, "storefront.experimental"),
                "Tenant override should work even without global flag");
    }

    @Test
    @Transactional
    void cachePrecedence_multipleTenantsIndependentOverrides() {
        Tenant tenant2 = createTenant("tenant2", "Tenant Two");
        UUID tenant2Id = tenant2.id;

        persistGlobalFlag("checkout.express", false);
        persistTenantFlag("checkout.express", true); // tenant1 enables
        persistFlagForTenant(tenant2Id, "checkout.express", false); // tenant2 keeps disabled

        // Tenant 1 enabled
        assertTrue(featureToggle.isEnabled(tenantId, "checkout.express"));

        // Tenant 2 disabled
        assertFalse(featureToggle.isEnabled(tenant2Id, "checkout.express"));

        // Global still disabled
        assertFalse(featureToggle.isGlobalEnabled("checkout.express"));
    }

    @Test
    @Transactional
    void cachePrecedence_invalidateOnlyAffectsSingleTenant() {
        persistGlobalFlag("admin.analytics", true);
        persistTenantFlag("admin.analytics", false);

        // Cache tenant-specific flag
        assertFalse(featureToggle.isEnabled(tenantId, "admin.analytics"));

        // Update tenant flag
        updateTenantFlag("admin.analytics", true);

        // Invalidate only this tenant's cache
        featureToggle.invalidate(tenantId, "admin.analytics");

        // Tenant should see updated value
        assertTrue(featureToggle.isEnabled(tenantId, "admin.analytics"));

        // Global should still be cached and unchanged
        assertTrue(featureToggle.isGlobalEnabled("admin.analytics"));
    }

    // ========================================================================
    // EMERGENCY KILL SWITCH TESTS
    // ========================================================================

    @Test
    @Transactional
    void killSwitch_paymentsCanBeDisabledGlobally() {
        persistGlobalFlag("payments.stripe.enabled", false);

        assertFalse(featureToggle.isPaymentsEnabled(), "Payments kill switch should respect global disable");
    }

    @Test
    @Transactional
    void killSwitch_paymentsEnabledByDefault() {
        persistGlobalFlag("payments.stripe.enabled", true);

        assertTrue(featureToggle.isPaymentsEnabled(), "Payments should be enabled when flag is true");
    }

    @Test
    @Transactional
    void killSwitch_paymentsCanBeDisabledPerTenant() {
        persistGlobalFlag("payments.stripe.enabled", true);
        persistTenantFlag("payments.stripe.enabled", false);

        assertFalse(featureToggle.isPaymentsEnabled(), "Tenant-specific payment disable should override global enable");
    }

    @Test
    @Transactional
    void killSwitch_checkoutCanBeDisabledGlobally() {
        persistGlobalFlag("checkout.order-creation.enabled", false);

        assertFalse(featureToggle.isCheckoutEnabled(), "Checkout kill switch should respect global disable");
    }

    @Test
    @Transactional
    void killSwitch_checkoutTenantOverride() {
        persistGlobalFlag("checkout.order-creation.enabled", true);
        persistTenantFlag("checkout.order-creation.enabled", false);

        assertFalse(featureToggle.isCheckoutEnabled(), "Tenant can disable checkout even when globally enabled");
    }

    @Test
    @Transactional
    void killSwitch_mediaUploadCanBeDisabled() {
        persistGlobalFlag("media.uploads.enabled", false);

        assertFalse(featureToggle.isMediaUploadEnabled(), "Media upload kill switch should respect global disable");
    }

    @Test
    @Transactional
    void killSwitch_mediaProcessingCanBeDisabled() {
        persistGlobalFlag("media.processing.enabled", false);

        assertFalse(featureToggle.isMediaProcessingEnabled(),
                "Media processing kill switch should respect global disable");
    }

    @Test
    @Transactional
    void killSwitch_mediaUploadAndProcessingIndependent() {
        persistGlobalFlag("media.uploads.enabled", true);
        persistGlobalFlag("media.processing.enabled", false);

        assertTrue(featureToggle.isMediaUploadEnabled(), "Uploads can be enabled while processing is disabled");
        assertFalse(featureToggle.isMediaProcessingEnabled(), "Processing can be disabled while uploads are enabled");
    }

    @Test
    @Transactional
    void killSwitch_impersonationCanBeDisabled() {
        persistGlobalFlag("admin.impersonation.enabled", false);

        assertFalse(featureToggle.isImpersonationEnabled(), "Impersonation kill switch should respect global disable");
    }

    @Test
    @Transactional
    void killSwitch_impersonationTenantOverride() {
        persistGlobalFlag("admin.impersonation.enabled", true);
        persistTenantFlag("admin.impersonation.enabled", false);

        assertFalse(featureToggle.isImpersonationEnabled(),
                "Tenant can disable impersonation even when globally enabled");
    }

    @Test
    @Transactional
    void killSwitch_defaultsFalseWhenFlagMissing() {
        // No flags persisted
        assertFalse(featureToggle.isPaymentsEnabled(), "Missing payment flag should default to disabled");
        assertFalse(featureToggle.isCheckoutEnabled(), "Missing checkout flag should default to disabled");
        assertFalse(featureToggle.isMediaUploadEnabled(), "Missing media upload flag should default to disabled");
        assertFalse(featureToggle.isMediaProcessingEnabled(),
                "Missing media processing flag should default to disabled");
        assertFalse(featureToggle.isImpersonationEnabled(), "Missing impersonation flag should default to disabled");
    }

    @Test
    @Transactional
    void killSwitch_cacheInvalidationPropagates() {
        persistGlobalFlag("payments.stripe.enabled", false);
        assertFalse(featureToggle.isPaymentsEnabled());

        // Update flag
        updateGlobalFlag("payments.stripe.enabled", true);

        // Invalidate all caches
        featureToggle.invalidateAll();

        // Should reflect new value
        assertTrue(featureToggle.isPaymentsEnabled(),
                "Kill switch should reflect updated value after cache invalidation");
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    @Transactional
    void persistTenantFlag(String flagKey, boolean enabled) {
        FeatureFlag flag = new FeatureFlag();
        flag.tenant = entityManager.getReference(Tenant.class, tenantId);
        flag.flagKey = flagKey;
        flag.enabled = enabled;
        flag.config = "{}";
        flag.createdAt = OffsetDateTime.now();
        flag.updatedAt = OffsetDateTime.now();
        flag.owner = "feature-toggle-tests@villagecompute.dev";
        flag.lastReviewedAt = OffsetDateTime.now();
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
        flag.owner = "feature-toggle-tests@villagecompute.dev";
        flag.lastReviewedAt = OffsetDateTime.now();
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

    @Transactional
    void updateGlobalFlag(String flagKey, boolean enabled) {
        FeatureFlag flag = entityManager
                .createQuery("SELECT ff FROM FeatureFlag ff WHERE ff.tenant IS NULL AND ff.flagKey = :flagKey",
                        FeatureFlag.class)
                .setParameter("flagKey", flagKey).setMaxResults(1).getSingleResult();
        flag.enabled = enabled;
        flag.updatedAt = OffsetDateTime.now();
        entityManager.merge(flag);
        entityManager.flush();
    }

    @Transactional
    Tenant createTenant(String subdomain, String name) {
        Tenant tenant = new Tenant();
        tenant.subdomain = subdomain;
        tenant.name = name;
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        return tenant;
    }

    @Transactional
    void persistFlagForTenant(UUID targetTenantId, String flagKey, boolean enabled) {
        FeatureFlag flag = new FeatureFlag();
        flag.tenant = entityManager.getReference(Tenant.class, targetTenantId);
        flag.flagKey = flagKey;
        flag.enabled = enabled;
        flag.config = "{}";
        flag.createdAt = OffsetDateTime.now();
        flag.updatedAt = OffsetDateTime.now();
        flag.owner = "feature-toggle-tests@villagecompute.dev";
        flag.lastReviewedAt = OffsetDateTime.now();
        entityManager.persist(flag);
        entityManager.flush();
    }
}
