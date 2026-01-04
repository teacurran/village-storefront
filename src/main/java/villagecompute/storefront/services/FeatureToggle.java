package villagecompute.storefront.services;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.cache.CacheManager;

/**
 * Feature toggle service for configuration-driven feature flags. Supports tenant-specific overrides and platform-wide
 * defaults.
 *
 * <p>
 * <strong>Usage:</strong>
 * 
 * <pre>{@code
 * if (featureToggle.isEnabled("storefront.hero.beta")) {
 *     // Show beta hero component
 * }
 * }</pre>
 *
 * <p>
 * <strong>Resolution Strategy:</strong>
 * <ol>
 * <li>Check tenant-specific flag (tenant_id = current tenant, flag_key = key)</li>
 * <li>If not found, check global flag (tenant_id = null, flag_key = key)</li>
 * <li>If not found, return false (default disabled)</li>
 * </ol>
 *
 * <p>
 * Results are cached via Caffeine to minimize database lookups. Cache key format:
 * {@code "tenant:{tenantId}:flag:{flagKey}"} or {@code "global:flag:{flagKey}"}
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 5: Feature Flag Contracts & Context propagation)</li>
 * <li>Architecture Overview Section 1: Feature Flag Strategy</li>
 * <li>Architecture Overview Section 3.2.1: Caffeine cache configuration</li>
 * <li>Migration: V20260102__baseline_schema.sql (feature_flags table)</li>
 * </ul>
 *
 * @see TenantContext
 */
@ApplicationScoped
public class FeatureToggle {

    private static final Logger LOG = Logger.getLogger(FeatureToggle.class.getName());

    @Inject
    EntityManager entityManager;

    @Inject
    CacheManager cacheManager;

    /**
     * Check if feature flag is enabled for current tenant. Falls back to global flag if tenant-specific flag not found.
     *
     * @param flagKey
     *            feature flag key (e.g., "storefront.hero.beta")
     * @return true if enabled, false otherwise
     * @throws IllegalStateException
     *             if no tenant context set
     */
    public boolean isEnabled(String flagKey) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey cannot be null or blank");
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        return isEnabled(tenantId, flagKey);
    }

    /**
     * Check if feature flag is enabled for specific tenant. Falls back to global flag if tenant-specific flag not
     * found.
     *
     * @param tenantId
     *            tenant UUID
     * @param flagKey
     *            feature flag key
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled(UUID tenantId, String flagKey) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId cannot be null");
        }
        if (flagKey == null || flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey cannot be null or blank");
        }

        String cacheKey = buildCacheKey(tenantId, flagKey);

        Boolean enabled = cacheManager.getCache("feature-flag-cache").map(cache -> (Boolean) cache.get(cacheKey, k -> {
            // Check tenant-specific flag first
            Boolean tenantFlag = queryFlag(tenantId, flagKey);
            if (tenantFlag != null) {
                LOG.log(Level.FINE, "Tenant-specific flag {0} = {1} for tenant {2}",
                        new Object[]{flagKey, tenantFlag, tenantId});
                return tenantFlag;
            }

            // Fall back to global flag
            Boolean globalFlag = queryGlobalFlag(flagKey);
            if (globalFlag != null) {
                LOG.log(Level.FINE, "Global flag {0} = {1} (no tenant override for {2})",
                        new Object[]{flagKey, globalFlag, tenantId});
                return globalFlag;
            }

            // Default to disabled
            LOG.log(Level.FINE, "Flag {0} not found (defaulting to false) for tenant {1}",
                    new Object[]{flagKey, tenantId});
            return false;
        }).await().indefinitely()).orElseGet(() -> {
            // Fallback: query directly if cache not available
            Boolean tenantFlag = queryFlag(tenantId, flagKey);
            if (tenantFlag != null) {
                return tenantFlag;
            }
            Boolean globalFlag = queryGlobalFlag(flagKey);
            return globalFlag != null ? globalFlag : false;
        });

        return enabled != null && enabled;
    }

    /**
     * Check if global feature flag is enabled (tenant-agnostic).
     *
     * @param flagKey
     *            feature flag key
     * @return true if enabled, false otherwise
     */
    public boolean isGlobalEnabled(String flagKey) {
        if (flagKey == null || flagKey.isBlank()) {
            throw new IllegalArgumentException("flagKey cannot be null or blank");
        }

        String cacheKey = "global:flag:" + flagKey;

        Boolean enabled = cacheManager.getCache("feature-flag-cache").map(cache -> (Boolean) cache.get(cacheKey, k -> {
            Boolean globalFlag = queryGlobalFlag(flagKey);
            return globalFlag != null ? globalFlag : false;
        }).await().indefinitely()).orElseGet(() -> {
            Boolean globalFlag = queryGlobalFlag(flagKey);
            return globalFlag != null ? globalFlag : false;
        });

        return enabled != null && enabled;
    }

    /**
     * Invalidate cached flag value for tenant. Call this when flag configuration changes.
     *
     * @param tenantId
     *            tenant UUID
     * @param flagKey
     *            feature flag key
     */
    public void invalidate(UUID tenantId, String flagKey) {
        String cacheKey = buildCacheKey(tenantId, flagKey);
        cacheManager.getCache("feature-flag-cache").ifPresent(cache -> {
            cache.invalidate(cacheKey).await().indefinitely();
            LOG.log(Level.INFO, "Invalidated feature flag cache: {0}", cacheKey);
        });
    }

    /**
     * Invalidate all cached flags (use sparingly).
     */
    public void invalidateAll() {
        cacheManager.getCache("feature-flag-cache").ifPresent(cache -> {
            cache.invalidateAll().await().indefinitely();
            LOG.log(Level.INFO, "Invalidated all feature flag cache entries");
        });
    }

    /**
     * Query tenant-specific feature flag from database.
     *
     * @param tenantId
     *            tenant UUID
     * @param flagKey
     *            flag key
     * @return enabled status or null if not found
     */
    private Boolean queryFlag(UUID tenantId, String flagKey) {
        try {
            return (Boolean) entityManager
                    .createQuery("SELECT ff.enabled FROM FeatureFlag ff "
                            + "WHERE ff.tenant.id = :tenantId AND ff.flagKey = :flagKey", Boolean.class)
                    .setParameter("tenantId", tenantId).setParameter("flagKey", flagKey).setMaxResults(1)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    /**
     * Query global feature flag from database.
     *
     * @param flagKey
     *            flag key
     * @return enabled status or null if not found
     */
    private Boolean queryGlobalFlag(String flagKey) {
        try {
            return (Boolean) entityManager
                    .createQuery("SELECT ff.enabled FROM FeatureFlag ff "
                            + "WHERE ff.tenant IS NULL AND ff.flagKey = :flagKey", Boolean.class)
                    .setParameter("flagKey", flagKey).setMaxResults(1).getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    /**
     * Build cache key for tenant-specific flag.
     *
     * @param tenantId
     *            tenant UUID
     * @param flagKey
     *            flag key
     * @return cache key
     */
    private String buildCacheKey(UUID tenantId, String flagKey) {
        return "tenant:" + tenantId + ":flag:" + flagKey;
    }
}
