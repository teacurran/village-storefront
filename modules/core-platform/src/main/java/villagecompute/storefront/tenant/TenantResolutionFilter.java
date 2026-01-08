package villagecompute.storefront.tenant;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.annotation.Priority;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import io.quarkus.cache.CacheManager;

/**
 * JAX-RS filter that resolves tenant context from HTTP Host header. Executes before authentication to populate tenant
 * context for downstream processing.
 *
 * <p>
 * <strong>Resolution Strategy:</strong>
 * <ol>
 * <li>Extract host from HTTP Host header (normalize to lowercase)</li>
 * <li>Query {@code custom_domains} table for exact domain match</li>
 * <li>If not found, extract subdomain and query {@code tenants.subdomain}</li>
 * <li>If found and active, populate {@link TenantContext} and fire {@link TenantResolved} event</li>
 * <li>If not found or suspended, fire {@link TenantMissing} event and return 404</li>
 * </ol>
 *
 * <p>
 * Results are cached via Caffeine to minimize database lookups. Cache invalidation must be triggered manually when
 * tenant/domain changes.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 2: Tenant Resolution Flow)</li>
 * <li>ADR-001 Tenancy Strategy (Section 5: Context propagation + FeatureToggle contracts)</li>
 * <li>Architecture Overview Section 3.2.1: Tenant Access Gateway</li>
 * <li>ERD: datamodel_erd.puml (tenants, custom_domains schema)</li>
 * </ul>
 *
 * @see TenantContext
 * @see TenantContextClearFilter
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 1)
public class TenantResolutionFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(TenantResolutionFilter.class.getName());

    private static final String PLATFORM_DOMAIN = "villagecompute.com";
    private static final String SUBDOMAIN_PATTERN = "^([a-z0-9][a-z0-9-]{0,61}[a-z0-9]?)\\." + PLATFORM_DOMAIN + "$";

    @Inject
    EntityManager entityManager;

    @Inject
    CacheManager cacheManager;

    @Inject
    Event<TenantResolved> tenantResolvedEvent;

    @Inject
    Event<TenantMissing> tenantMissingEvent;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String host = extractHost(requestContext);

        if (host == null || host.isBlank()) {
            LOG.warning("Missing Host header in request");
            tenantMissingEvent.fire(new TenantMissing("<missing>", "missing_host_header"));
            requestContext.abortWith(
                    Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"Missing Host header\"}").build());
            return;
        }

        // Normalize hostname to lowercase
        String normalizedHost = host.toLowerCase();

        // TODO: Add structured logging with resolution latency (Section 6 instrumentation)
        long startTime = System.currentTimeMillis();

        try {
            TenantInfo tenantInfo = resolveTenant(normalizedHost);

            if (tenantInfo == null) {
                LOG.log(Level.WARNING, "Tenant not found for host: {0}", normalizedHost);
                tenantMissingEvent.fire(new TenantMissing(normalizedHost, "tenant_not_found"));
                requestContext.abortWith(
                        Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"Store not found\"}").build());
                return;
            }

            if (tenantInfo.isSuspended()) {
                LOG.log(Level.WARNING, "Tenant suspended for host: {0}", normalizedHost);
                tenantMissingEvent.fire(new TenantMissing(normalizedHost, "tenant_suspended"));
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"error\":\"Store temporarily unavailable\"}").build());
                return;
            }

            if (!tenantInfo.isActive()) {
                LOG.log(Level.WARNING, "Tenant inactive (status={0}) for host: {1}",
                        new Object[]{tenantInfo.status(), normalizedHost});
                tenantMissingEvent.fire(new TenantMissing(normalizedHost, "tenant_inactive"));
                requestContext.abortWith(
                        Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"Store not found\"}").build());
                return;
            }

            // Populate ThreadLocal context
            TenantContext.setCurrentTenant(tenantInfo);

            // Fire CDI event for downstream subscribers
            tenantResolvedEvent.fire(new TenantResolved(tenantInfo, normalizedHost));

            // TODO: Emit metrics (counter per tenant, gauge for cache size) - Section 6
            long duration = System.currentTimeMillis() - startTime;
            LOG.log(Level.FINE, "Tenant resolved: {0} in {1}ms", new Object[]{tenantInfo.tenantId(), duration});

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error resolving tenant for host: " + normalizedHost, e);
            tenantMissingEvent.fire(new TenantMissing(normalizedHost, "resolution_error"));
            requestContext.abortWith(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Internal server error\"}").build());
        }
    }

    /**
     * Resolve tenant from hostname using cache-backed database lookup. First checks custom_domains, then falls back to
     * subdomain extraction.
     *
     * @param hostname
     *            normalized hostname
     * @return tenant info or null if not found
     */
    private TenantInfo resolveTenant(String hostname) {
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
    private TenantInfo resolveFromCustomDomain(String domain) {
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
    private TenantInfo resolveFromSubdomain(String hostname) {
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
     * Extract host from request headers, stripping port if present.
     *
     * @param requestContext
     *            JAX-RS request context
     * @return hostname without port, or null if missing
     */
    private String extractHost(ContainerRequestContext requestContext) {
        String host = requestContext.getHeaderString("Host");
        if (host == null) {
            return null;
        }

        // Strip port number if present (e.g., "example.com:8080" -> "example.com")
        int colonIndex = host.indexOf(':');
        if (colonIndex > 0) {
            return host.substring(0, colonIndex);
        }

        return host;
    }
}
