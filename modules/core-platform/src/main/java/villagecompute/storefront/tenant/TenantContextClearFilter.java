package villagecompute.storefront.tenant;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * JAX-RS response filter that clears tenant ThreadLocal context. CRITICAL: Prevents ThreadLocal memory leaks in Quarkus
 * worker thread pool.
 *
 * <p>
 * Executes after request processing to ensure {@link TenantContext} is cleared even if exceptions occur during request
 * handling.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 2: Tenant Resolution Flow)</li>
 * <li>Architecture Overview Section 3.2.1: ThreadLocal cleanup</li>
 * </ul>
 *
 * @see TenantContext
 * @see TenantResolutionFilter
 */
@Provider
@Priority(Priorities.USER)
public class TenantContextClearFilter implements ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(TenantContextClearFilter.class.getName());

    @Inject
    EntityManager entityManager;

    @ConfigProperty(
            name = "tenant.rls.enabled",
            defaultValue = "true")
    boolean rlsEnabled;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        try {
            // Always clear ThreadLocal to prevent context leakage
            if (TenantContext.hasContext()) {
                LOG.log(Level.FINE, "Clearing tenant context for tenant: {0}", TenantContext.getCurrentTenantId());
                if (rlsEnabled) {
                    resetTenantSessionVariable();
                }
            }
            TenantContext.clear();
        } catch (Exception e) {
            // Log but don't throw - clearing context should never break response
            LOG.log(Level.WARNING, "Error clearing tenant context", e);
        }
    }

    private void resetTenantSessionVariable() {
        try {
            entityManager.createNativeQuery("SELECT set_config('app.tenant_id', '', FALSE)").getSingleResult();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to reset tenant session variable", e);
        }
    }
}
