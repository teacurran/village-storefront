package villagecompute.storefront.tenant;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import villagecompute.storefront.data.models.CustomDomain;
import villagecompute.storefront.data.models.Tenant;

import io.opentelemetry.api.trace.Span;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheManager;

/**
 * CDI observer that invalidates tenant cache entries when tenant or domain data changes. Ensures cached tenant
 * resolution data stays consistent with database state.
 *
 * <p>
 * <strong>Cache Invalidation Strategy:</strong>
 * <ul>
 * <li>Tenant updates: Invalidate subdomain entry (subdomain.villagecompute.com)</li>
 * <li>Custom domain changes: Invalidate specific domain entry</li>
 * <li>Bulk operations: Full cache clear (rare, admin-only)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 2: Tenant Resolution Caching)</li>
 * <li>Architecture Overview Section 3.2.1: Tenant Access Gateway - Cache Coherence</li>
 * <li>Task I1.T5: Tenant Access Gateway Prototype (Caffeine caching shim)</li>
 * </ul>
 *
 * @see TenantResolutionFilter
 */
@ApplicationScoped
public class TenantCacheInvalidator {

    private static final Logger LOG = Logger.getLogger(TenantCacheInvalidator.class.getName());
    private static final String PLATFORM_DOMAIN = "villagecompute.com";

    @Inject
    CacheManager cacheManager;

    /**
     * Observes {@link TenantUpdated} events and invalidates cached tenant resolution data for the affected subdomain.
     *
     * @param event
     *            tenant update event
     */
    public void onTenantUpdated(@Observes TenantUpdated event) {
        Tenant tenant = event.tenant();
        String subdomainHost = tenant.subdomain + "." + PLATFORM_DOMAIN;

        invalidateCacheEntry(subdomainHost, "tenant_updated");

        LOG.log(Level.INFO, "Invalidated tenant cache for subdomain: {0} (tenant_id={1})",
                new Object[]{subdomainHost, tenant.id});
    }

    /**
     * Observes {@link CustomDomainVerified} events and invalidates the custom domain cache entry when a domain is
     * verified or modified.
     *
     * @param event
     *            custom domain event
     */
    public void onCustomDomainChanged(@Observes CustomDomainVerified event) {
        CustomDomain customDomain = event.customDomain();

        invalidateCacheEntry(customDomain.domain, "custom_domain_verified");

        LOG.log(Level.INFO, "Invalidated tenant cache for custom domain: {0} (tenant_id={1})",
                new Object[]{customDomain.domain, customDomain.tenant.id});
    }

    /**
     * Observes {@link CustomDomainRemoved} events and invalidates the cache entry when a domain is removed or
     * unverified.
     *
     * @param event
     *            custom domain removed event
     */
    public void onCustomDomainRemoved(@Observes CustomDomainRemoved event) {
        String domain = event.domain();

        invalidateCacheEntry(domain, "custom_domain_removed");

        LOG.log(Level.INFO, "Invalidated tenant cache for removed custom domain: {0}", domain);
    }

    /**
     * Invalidate a specific cache entry by hostname. Used by CDI observers and admin operations.
     *
     * @param hostname
     *            hostname to invalidate
     * @param reason
     *            reason for invalidation (for logging)
     */
    @CacheInvalidate(
            cacheName = "tenant-cache")
    public void invalidateCacheEntry(String hostname, String reason) {
        Span currentSpan = Span.current();
        currentSpan.setAttribute("cache.invalidation.hostname", hostname);
        currentSpan.setAttribute("cache.invalidation.reason", reason);

        Optional<io.quarkus.cache.Cache> cacheOpt = cacheManager.getCache("tenant-cache");
        cacheOpt.ifPresent(cache -> cache.invalidate(hostname).await().indefinitely());

        LOG.log(Level.FINE, "Cache invalidation: hostname={0}, reason={1}", new Object[]{hostname, reason});
    }

    /**
     * Clear entire tenant cache. Use sparingly - only for bulk tenant operations or emergency recovery.
     */
    public void clearAllTenantCache() {
        Span currentSpan = Span.current();
        currentSpan.setAttribute("cache.invalidation.scope", "full");

        Optional<io.quarkus.cache.Cache> cacheOpt = cacheManager.getCache("tenant-cache");
        cacheOpt.ifPresent(cache -> cache.invalidateAll().await().indefinitely());

        LOG.log(Level.WARNING, "Full tenant cache cleared (use this sparingly)");
    }
}
