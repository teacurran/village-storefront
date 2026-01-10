package villagecompute.storefront.security;

import java.io.IOException;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * JAX-RS response filter that adds Content-Security-Policy headers to HTTP responses.
 *
 * <p>
 * Applies different CSP policies based on request path:
 * <ul>
 * <li><strong>Storefront (Qute templates):</strong> /storefront/* paths use storefront CSP policy</li>
 * <li><strong>Admin SPA (Vue.js):</strong> /admin/* paths use admin SPA CSP policy</li>
 * <li><strong>API endpoints:</strong> No CSP headers (not applicable to JSON responses)</li>
 * </ul>
 *
 * <p>
 * <strong>Security Benefits:</strong>
 * <ul>
 * <li>Prevents XSS attacks via inline script execution</li>
 * <li>Blocks malicious scripts in uploaded SVG files</li>
 * <li>Mitigates XSS via image metadata (EXIF/IPTC injection)</li>
 * <li>Restricts resource loading to trusted origins</li>
 * </ul>
 *
 * <p>
 * <strong>CSP Policies:</strong>
 * <ul>
 * <li>Storefront: Allows Stripe.js for payment processing, restricts inline scripts</li>
 * <li>Admin: Strict policy with no inline scripts, allows API calls to same origin</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I6.T2: Security hardening - CSP header implementation</li>
 * <li>MDN: https://developer.mozilla.org/en-US/docs/Web/HTTP/CSP</li>
 * <li>Threat Model: docs/security/threat-models/media-pipeline.md (MED-002, MED-004)</li>
 * </ul>
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
public class ContentSecurityPolicyFilter implements ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(ContentSecurityPolicyFilter.class);

    @Inject
    @ConfigProperty(
            name = "csp.storefront.policy")
    String storefrontPolicy;

    @Inject
    @ConfigProperty(
            name = "csp.admin.policy")
    String adminPolicy;

    @Inject
    @ConfigProperty(
            name = "csp.report-uri",
            defaultValue = "/api/v1/csp-report")
    String reportUri;

    @Inject
    @ConfigProperty(
            name = "csp.enabled",
            defaultValue = "true")
    boolean cspEnabled;

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        if (!cspEnabled) {
            LOG.debug("CSP headers disabled via configuration");
            return;
        }

        String path = requestContext.getUriInfo().getPath();

        // Only apply CSP to HTML responses (storefront and admin UI)
        if (path.startsWith("/api/") || path.startsWith("/q/") || path.startsWith("/openapi")) {
            LOG.trace("Skipping CSP for API/internal path: " + path);
            return;
        }

        String policy = determinePolicy(path);
        if (policy != null) {
            // Add report-uri to policy
            String policyWithReporting = policy + "; report-uri " + reportUri;
            responseContext.getHeaders().putSingle("Content-Security-Policy", policyWithReporting);
            LOG.debugf("Applied CSP policy to path %s: %s", path, policy);
        }
    }

    /**
     * Determine CSP policy based on request path.
     *
     * @param path
     *            request path
     * @return CSP policy string or null if no policy should be applied
     */
    private String determinePolicy(String path) {
        if (path.startsWith("/admin")) {
            return adminPolicy;
        } else if (path.startsWith("/storefront") || path.equals("/") || path.startsWith("/products")
                || path.startsWith("/cart") || path.startsWith("/checkout")) {
            return storefrontPolicy;
        }
        // Default: no CSP for other paths
        return null;
    }
}
