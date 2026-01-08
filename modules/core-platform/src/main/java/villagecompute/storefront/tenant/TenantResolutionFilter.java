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

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
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

    @ConfigProperty(
            name = "tenant.rls.enabled",
            defaultValue = "true")
    boolean rlsEnabled;

    @ConfigProperty(
            name = "tenant.cache.enabled",
            defaultValue = "true")
    boolean cacheEnabled;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String host = extractHost(requestContext);

        if (host == null || host.isBlank()) {
            logStructured(Level.WARNING, "missing_host_header", null, null, null, 0);
            tenantMissingEvent.fire(new TenantMissing("<missing>", "missing_host_header"));
            requestContext.abortWith(
                    Response.status(Response.Status.BAD_REQUEST).entity("{\"error\":\"Missing Host header\"}").build());
            return;
        }

        // Normalize hostname to lowercase
        String normalizedHost = host.toLowerCase();

        long startTime = System.currentTimeMillis();
        Span currentSpan = Span.current();
        currentSpan.setAttribute("tenant.hostname", normalizedHost);

        try {
            TenantInfo tenantInfo = resolveTenant(normalizedHost);

            if (tenantInfo == null) {
                long duration = System.currentTimeMillis() - startTime;
                logStructured(Level.WARNING, "tenant_not_found", normalizedHost, null, null, duration);
                currentSpan.setStatus(StatusCode.ERROR, "Tenant not found");
                tenantMissingEvent.fire(new TenantMissing(normalizedHost, "tenant_not_found"));
                requestContext.abortWith(
                        Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"Store not found\"}").build());
                return;
            }

            if (tenantInfo.isSuspended()) {
                long duration = System.currentTimeMillis() - startTime;
                logStructured(Level.WARNING, "tenant_suspended", normalizedHost, tenantInfo.tenantId(),
                        tenantInfo.subdomain(), duration);
                currentSpan.setStatus(StatusCode.ERROR, "Tenant suspended");
                currentSpan.setAttribute("tenant.id", tenantInfo.tenantId().toString());
                tenantMissingEvent.fire(new TenantMissing(normalizedHost, "tenant_suspended"));
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"error\":\"Store temporarily unavailable\"}").build());
                return;
            }

            if (!tenantInfo.isActive()) {
                long duration = System.currentTimeMillis() - startTime;
                logStructured(Level.WARNING, "tenant_inactive", normalizedHost, tenantInfo.tenantId(),
                        tenantInfo.subdomain(), duration);
                currentSpan.setStatus(StatusCode.ERROR, "Tenant inactive");
                currentSpan.setAttribute("tenant.id", tenantInfo.tenantId().toString());
                currentSpan.setAttribute("tenant.status", tenantInfo.status());
                tenantMissingEvent.fire(new TenantMissing(normalizedHost, "tenant_inactive"));
                requestContext.abortWith(
                        Response.status(Response.Status.NOT_FOUND).entity("{\"error\":\"Store not found\"}").build());
                return;
            }

            // Populate ThreadLocal context
            TenantContext.setCurrentTenant(tenantInfo);
            if (rlsEnabled) {
                seedTenantSessionVariable(tenantInfo.tenantId());
            }

            // Fire CDI event for downstream subscribers (feature flag hydration, etc.)
            tenantResolvedEvent.fire(new TenantResolved(tenantInfo, normalizedHost));

            long duration = System.currentTimeMillis() - startTime;
            logStructured(Level.FINE, "tenant_resolved", normalizedHost, tenantInfo.tenantId(), tenantInfo.subdomain(),
                    duration);
            currentSpan.setAttribute("tenant.id", tenantInfo.tenantId().toString());
            currentSpan.setAttribute("tenant.subdomain", tenantInfo.subdomain());
            currentSpan.setAttribute("tenant.resolution.duration_ms", duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            logStructured(Level.SEVERE, "resolution_error", normalizedHost, null, null, duration);
            currentSpan.recordException(e);
            currentSpan.setStatus(StatusCode.ERROR, "Tenant resolution failed");
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

    /**
     * Emit structured log entry for tenant resolution events. Produces JSON-compatible log format with context fields
     * for observability tooling (Prometheus, Jaeger, Grafana).
     *
     * @param level
     *            log level
     * @param event
     *            event type (tenant_resolved, tenant_not_found, etc.)
     * @param hostname
     *            requested hostname
     * @param tenantId
     *            resolved tenant ID (nullable if not resolved)
     * @param subdomain
     *            resolved subdomain (nullable if not resolved)
     * @param durationMs
     *            resolution duration in milliseconds
     */
    private void logStructured(Level level, String event, String hostname, UUID tenantId, String subdomain,
            long durationMs) {
        String logMessage = String.format(
                "{\"event\":\"%s\",\"hostname\":\"%s\",\"tenant_id\":\"%s\",\"subdomain\":\"%s\",\"duration_ms\":%d}",
                event, hostname != null ? hostname : "null", tenantId != null ? tenantId.toString() : "null",
                subdomain != null ? subdomain : "null", durationMs);
        LOG.log(level, logMessage);
    }

    private void seedTenantSessionVariable(UUID tenantId) {
        try {
            entityManager.createNativeQuery("SELECT set_current_tenant_id(:tenantId)")
                    .setParameter("tenantId", tenantId).getSingleResult();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to seed tenant session variable for tenant " + tenantId, e);
            throw e;
        }
    }
}
