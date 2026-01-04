package villagecompute.storefront.services;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.cache.CacheManager;

/**
 * Caching service for catalog search results.
 *
 * <p>
 * Uses Caffeine cache to store search query results per tenant. Cache keys are composed of tenant ID + query hash to
 * ensure tenant isolation.
 *
 * <p>
 * <strong>Cache Invalidation:</strong> Catalog/cart services should call {@link #invalidateTenantCache(UUID, String)}
 * after product or cart updates to flush stale data.
 *
 * <p>
 * <strong>Cache Configuration:</strong> Defined in application.properties:
 *
 * <pre>
 * quarkus.cache.caffeine.catalog-search-cache.maximum-size=1000
 * quarkus.cache.caffeine.catalog-search-cache.expire-after-write=5M
 * </pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Search caching with invalidation hooks</li>
 * <li>Architecture: Section 6 Safety Net (Caffeine caching, no Redis)</li>
 * <li>FeatureToggle: Similar cache usage pattern</li>
 * </ul>
 */
@ApplicationScoped
public class CatalogCacheService {

    private static final Logger LOG = Logger.getLogger(CatalogCacheService.class);

    @Inject
    CacheManager cacheManager;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Search products with caching. The loader is executed only on cache miss.
     *
     * @param tenantId
     *            tenant identifier
     * @param searchTerm
     *            search query
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @param loader
     *            supplier that performs the underlying search
     * @return cached or freshly loaded search result
     */
    public CatalogService.CatalogSearchResult getSearchResult(UUID tenantId, String searchTerm, int page, int size,
            Supplier<CatalogService.CatalogSearchResult> loader) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId cannot be null");
        }
        if (loader == null) {
            throw new IllegalArgumentException("loader cannot be null");
        }

        String cacheKey = buildCacheKey(tenantId, searchTerm, page, size);

        return cacheManager.getCache("catalog-search-cache").map(cache -> {
            AtomicBoolean cacheHit = new AtomicBoolean(true);
            CatalogService.CatalogSearchResult results = (CatalogService.CatalogSearchResult) cache.get(cacheKey, k -> {
                cacheHit.set(false);
                LOG.debugf("Cache miss - tenantId=%s, search=%s, page=%d, size=%d", tenantId, searchTerm, page, size);
                meterRegistry.counter("catalog.cache.miss", "tenant_id", tenantId.toString()).increment();
                return loader.get();
            }).await().indefinitely();

            if (cacheHit.get()) {
                meterRegistry.counter("catalog.cache.hit", "tenant_id", tenantId.toString()).increment();
            }
            return results;
        }).orElseGet(() -> {
            // Fallback: query directly if cache not available
            LOG.warnf("Cache not available, querying directly - tenantId=%s", tenantId);
            return loader.get();
        });
    }

    /**
     * Invalidate catalog cache entries for a tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @param reason
     *            human-readable reason for logs/metrics
     */
    public void invalidateTenantCache(UUID tenantId, String reason) {
        if (tenantId == null) {
            return;
        }

        cacheManager.getCache("catalog-search-cache").ifPresent(cache -> {
            // Note: Caffeine doesn't support prefix-based invalidation in Quarkus wrapper
            // Best practice: invalidate all entries (acceptable for small/medium catalogs)
            cache.invalidateAll().await().indefinitely();
            LOG.infof("Invalidated catalog cache - tenantId=%s, reason=%s", tenantId, reason);
            meterRegistry.counter("catalog.cache.invalidated", "tenant_id", tenantId.toString()).increment();
        });
    }

    public void invalidateTenantCache(UUID tenantId) {
        invalidateTenantCache(tenantId, "manual");
    }

    /**
     * Invalidate specific search query for a tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @param searchTerm
     *            search query
     * @param page
     *            page number
     * @param size
     *            page size
     */
    public void invalidateQuery(UUID tenantId, String searchTerm, int page, int size) {
        if (tenantId == null) {
            return;
        }
        String cacheKey = buildCacheKey(tenantId, searchTerm, page, size);

        cacheManager.getCache("catalog-search-cache").ifPresent(cache -> {
            cache.invalidate(cacheKey).await().indefinitely();
            LOG.debugf("Invalidated catalog cache entry - tenantId=%s, cacheKey=%s", tenantId, cacheKey);
        });
    }

    /**
     * Build cache key from tenant ID, search term, and pagination params.
     *
     * @param tenantId
     *            tenant UUID
     * @param searchTerm
     *            search query
     * @param page
     *            page number
     * @param size
     *            page size
     * @return cache key
     */
    private String buildCacheKey(UUID tenantId, String searchTerm, int page, int size) {
        // Hash search term to avoid key length issues
        String queryHash = hashString(searchTerm);
        return String.format("tenant:%s:search:%s:page:%d:size:%d", tenantId, queryHash, page, size);
    }

    /**
     * Hash string using SHA-256 and Base64 encode (URL-safe).
     *
     * @param input
     *            input string
     * @return hashed string
     */
    private String hashString(String input) {
        try {
            String normalized = input == null ? "" : input;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // Fallback: use input as-is (should never happen)
            LOG.warnf("SHA-256 not available, using plaintext cache key");
            return input == null ? "" : input;
        }
    }
}
