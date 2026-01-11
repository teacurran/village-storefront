package villagecompute.storefront.tenant;

import java.util.UUID;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkus.cache.CacheManager;

/**
 * Service abstraction for tenant resolution logic. Provides reusable resolution strategies for both custom domains and
 * subdomains, supporting the Tenant Access Gateway described in {@code docs/architecture/tenant_isolation.md}.
 *
 * <p>
 * This service is extracted from {@link TenantResolutionFilter} to enable isolated testing and reuse in background
 * jobs, admin impersonation, and other non-HTTP contexts.
 *
 * <p>
 * <strong>Resolution Strategy:</strong>
 * <ol>
 * <li>Check custom_domains table for exact match (verified domains only)</li>
 * <li>If not found, extract subdomain and query tenants.subdomain</li>
 * <li>Results are cached via Caffeine when cache is enabled</li>
 * </ol>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I1.T5: Tenant Access Gateway Prototype</li>
 * <li>Architecture Overview Section 3.2.1: Tenant Access Gateway</li>
 * <li>ADR-001 Tenancy Strategy (Section 2: Tenant Resolution Flow)</li>
 * </ul>
 *
 * @see TenantResolutionFilter
 * @see TenantContext
 */
@ApplicationScoped
public class TenantResolverService {

    private static final Logger LOG = Logger.getLogger(TenantResolverService.class.getName());

    private static final String PLATFORM_DOMAIN = "villagecompute.com";
    private static final String SUBDOMAIN_PATTERN = "^([a-z0-9][a-z0-9-]{0,61}[a-z0-9]?)\\." + PLATFORM_DOMAIN + "$";

    @Inject
    EntityManager entityManager;

    @Inject
    CacheManager cacheManager;

    /**
     * Resolve tenant from hostname using cache-backed database lookup. First checks custom_domains, then falls back to
     * subdomain extraction.
     *
     * @param hostname
     *            normalized hostname (lowercase, no port)
     * @param cacheEnabled
     *            whether to use Caffeine cache
     * @return tenant info or null if not found
     */
    public TenantInfo resolveTenant(String hostname, boolean cacheEnabled) {
        if (hostname == null || hostname.isBlank()) {
            return null;
        }

        if (!cacheEnabled) {
            TenantInfo fromCustomDomain = resolveFromCustomDomain(hostname);
            return fromCustomDomain != null ? fromCustomDomain : resolveFromSubdomain(hostname);
        }

        // Use Caffeine cache to avoid repeated database queries
        return cacheManager.getCache("tenant-cache").map(cache -> (TenantInfo) cache.get(hostname, h -> {
            // Strategy 1: Check custom_domains table
            TenantInfo fromCustomDomain = resolveFromCustomDomain(hostname);
            if (fromCustomDomain != null) {
                return fromCustomDomain;
            }

            // Strategy 2: Extract subdomain and check tenants table
            return resolveFromSubdomain(hostname);
        }).await().indefinitely()).orElseGet(() -> {
            // Fallback if cache not available (shouldn't happen in normal operation)
            TenantInfo fromCustomDomain = resolveFromCustomDomain(hostname);
            return fromCustomDomain != null ? fromCustomDomain : resolveFromSubdomain(hostname);
        });
    }

    /**
     * Resolve tenant from custom_domains table.
     *
     * @param domain
     *            domain name
     * @return tenant info or null if not found
     */
    public TenantInfo resolveFromCustomDomain(String domain) {
        try {
            Object[] result = (Object[]) entityManager
                    .createQuery("SELECT t.id, t.subdomain, t.name, t.status "
                            + "FROM Tenant t JOIN CustomDomain cd ON cd.tenant.id = t.id "
                            + "WHERE cd.domain = :domain AND cd.verified = true", Object[].class)
                    .setParameter("domain", domain).setMaxResults(1).getSingleResult();

            return new TenantInfo((UUID) result[0], (String) result[1], (String) result[2], (String) result[3]);
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    /**
     * Resolve tenant from subdomain pattern.
     *
     * @param hostname
     *            full hostname
     * @return tenant info or null if not found
     */
    public TenantInfo resolveFromSubdomain(String hostname) {
        // Check if hostname matches subdomain pattern
        if (!hostname.matches(SUBDOMAIN_PATTERN)) {
            return null;
        }

        // Extract subdomain (everything before .villagecompute.com)
        String subdomain = hostname.substring(0, hostname.indexOf('.'));

        try {
            Object[] result = (Object[]) entityManager
                    .createQuery("SELECT t.id, t.subdomain, t.name, t.status "
                            + "FROM Tenant t WHERE t.subdomain = :subdomain", Object[].class)
                    .setParameter("subdomain", subdomain).setMaxResults(1).getSingleResult();

            return new TenantInfo((UUID) result[0], (String) result[1], (String) result[2], (String) result[3]);
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        }
    }

    /**
     * Invalidate cached tenant resolution for a specific hostname. Call this when tenant configuration or custom
     * domains change.
     *
     * @param hostname
     *            hostname to invalidate
     */
    public void invalidateCache(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return;
        }
        cacheManager.getCache("tenant-cache").ifPresent(cache -> {
            cache.invalidate(hostname).await().indefinitely();
            LOG.info("Invalidated tenant cache for hostname: " + hostname);
        });
    }

    /**
     * Invalidate all tenant cache entries. Use sparingly (e.g., during bulk tenant migrations).
     */
    public void invalidateAllCache() {
        cacheManager.getCache("tenant-cache").ifPresent(cache -> {
            cache.invalidateAll().await().indefinitely();
            LOG.info("Invalidated all tenant cache entries");
        });
    }
}
