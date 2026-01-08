package villagecompute.storefront.tenant;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

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

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        try {
            // Always clear ThreadLocal to prevent context leakage
            if (TenantContext.hasContext()) {
                LOG.log(Level.FINE, "Clearing tenant context for tenant: {0}", TenantContext.getCurrentTenantId());
            }
            TenantContext.clear();
        } catch (Exception e) {
            // Log but don't throw - clearing context should never break response
            LOG.log(Level.WARNING, "Error clearing tenant context", e);
        }
    }
}
