package villagecompute.storefront.api.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.storefront.security.CspViolationReport;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * REST resource for receiving CSP violation reports from browsers.
 *
 * <p>
 * Browsers send POST requests to this endpoint when Content-Security-Policy is violated (e.g., inline script blocked,
 * malicious resource loaded). Reports are logged and metrics published for monitoring.
 *
 * <p>
 * <strong>Use Cases:</strong>
 * <ul>
 * <li>Detect XSS attacks (inline scripts blocked by CSP)</li>
 * <li>Identify policy tuning needs (legitimate resources blocked by overly strict CSP)</li>
 * <li>Monitor uploaded media for malicious content (SVG scripts, EXIF XSS)</li>
 * </ul>
 *
 * <p>
 * <strong>Metrics:</strong>
 * <ul>
 * <li>csp.violation.count - Counter with tags: tenant_id, violated_directive, blocked_uri</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I6.T2: CSP violation reporting endpoint</li>
 * <li>Threat Model: docs/security/threat-models/media-pipeline.md (MED-002, MED-004)</li>
 * </ul>
 */
@Path("/api/v1/csp-report")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CspReportResource {

    private static final Logger LOG = Logger.getLogger(CspReportResource.class);

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Receive CSP violation report from browser.
     *
     * @param report
     *            CSP violation report
     * @return 204 No Content
     */
    @POST
    public Response reportViolation(CspViolationReport report) {
        if (report == null || report.getCspReport() == null) {
            LOG.warn("Received invalid CSP violation report (null payload)");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        CspViolationReport.CspReport csp = report.getCspReport();

        // Get tenant context (may be null for unauthenticated requests)
        String tenantId = TenantContext.getCurrentTenantId() != null ? TenantContext.getCurrentTenantId().toString()
                : "unknown";

        // Log violation
        LOG.warnf(
                "CSP violation - tenantId=%s, documentUri=%s, violatedDirective=%s, blockedUri=%s, sourceFile=%s:%d:%d",
                tenantId, csp.getDocumentUri(), csp.getViolatedDirective(), csp.getBlockedUri(), csp.getSourceFile(),
                csp.getLineNumber(), csp.getColumnNumber());

        // Publish metric for monitoring
        meterRegistry.counter("csp.violation.count", "tenant_id", tenantId, "violated_directive",
                csp.getViolatedDirective() != null ? csp.getViolatedDirective() : "unknown", "blocked_uri",
                sanitizeUriForMetric(csp.getBlockedUri())).increment();

        // Return 204 No Content (browser expects no response body)
        return Response.noContent().build();
    }

    /**
     * Sanitize URI for use in metric tags (limit cardinality).
     *
     * @param uri
     *            blocked URI
     * @return sanitized URI (domain only)
     */
    private String sanitizeUriForMetric(String uri) {
        if (uri == null || uri.isBlank()) {
            return "unknown";
        }
        // Extract domain from URI to limit metric cardinality
        // Example: https://evil.com/malicious.js -> evil.com
        try {
            java.net.URI parsed = java.net.URI.create(uri);
            return parsed.getHost() != null ? parsed.getHost() : uri;
        } catch (IllegalArgumentException e) {
            return "invalid-uri";
        }
    }
}
