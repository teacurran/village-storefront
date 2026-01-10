package villagecompute.storefront.platformops.api.rest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.storefront.platformops.api.types.ImpersonationContext;
import villagecompute.storefront.platformops.api.types.ImpersonationRequest;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService.PlatformAdminPrincipal;
import villagecompute.storefront.platformops.services.ImpersonationService;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Impersonation management REST resource.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>POST /api/v1/platform/impersonate - Start impersonation</li>
 * <li>DELETE /api/v1/platform/impersonate - End impersonation</li>
 * <li>GET /api/v1/platform/impersonate/current - Get current session</li>
 * </ul>
 *
 * <p>
 * <strong>Security Requirements:</strong>
 * <ul>
 * <li>Platform admin authentication required</li>
 * <li>RBAC permission: 'impersonate'</li>
 * <li>MFA challenge required before starting session</li>
 * <li>Reason must be at least 10 characters</li>
 * <li>All actions logged to platform_commands table</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (impersonation control)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.8.1</li>
 * <li>Rationale: 05_Rationale_and_Future.md Section 4.3.7</li>
 * </ul>
 */
@Path("/api/v1/platform/impersonate")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ImpersonationResource {

    private static final Logger LOG = Logger.getLogger(ImpersonationResource.class);

    @Inject
    ImpersonationService impersonationService;

    @Inject
    PlatformAdminAuthorizationService authorizationService;

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * Start impersonation session.
     *
     * <p>
     * RBAC: Requires 'platform_admin' role + 'impersonate' permission + MFA verification.
     *
     * @param request
     *            impersonation request with target tenant/user and reason
     * @param xForwardedFor
     *            X-Forwarded-For header for IP tracking
     * @param userAgent
     *            User-Agent header
     * @return impersonation context
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response startImpersonation(ImpersonationRequest request,
            @HeaderParam("X-Forwarded-For") String xForwardedFor, @HeaderParam("User-Agent") String userAgent) {

        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_IMPERSONATE);
        LOG.infof("POST /platform/impersonate - target tenant: %s, user: %s",
                request != null ? request.targetTenantId : null, request != null ? request.targetUserId : null);

        // Validate request
        if (request == null || request.targetTenantId == null) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Target tenant ID is required");
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }

        if (request.reason == null || request.reason.trim().length() < 10) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Reason must be at least 10 characters");
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }

        if (request.ticketNumber == null || request.ticketNumber.isBlank()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Support ticket number is required");
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }

        // Extract IP address
        InetAddress ipAddress = parseIpAddress(xForwardedFor);

        try {
            ImpersonationContext context = impersonationService.startImpersonation(actor.id(), actor.email(),
                    request.targetTenantId, request.targetUserId, request.reason, request.ticketNumber, ipAddress,
                    userAgent);

            LOG.infof("Started impersonation session %s", context.sessionId);

            return Response.ok(context).build();

        } catch (IllegalArgumentException | IllegalStateException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
    }

    /**
     * End current impersonation session.
     *
     * @param xForwardedFor
     *            X-Forwarded-For header
     * @param userAgent
     *            User-Agent header
     * @return success response
     */
    @DELETE
    public Response endImpersonation(@HeaderParam("X-Forwarded-For") String xForwardedFor,
            @HeaderParam("User-Agent") String userAgent) {

        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_IMPERSONATE);
        LOG.info("DELETE /platform/impersonate");

        InetAddress ipAddress = parseIpAddress(xForwardedFor);

        try {
            impersonationService.endImpersonation(actor.id(), actor.email(), ipAddress, userAgent);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Impersonation session ended successfully");
            return Response.ok(response).build();

        } catch (IllegalStateException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).entity(error).build();
        }
    }

    /**
     * Get current impersonation context for the authenticated platform admin.
     *
     * @return impersonation context if session active, 404 otherwise
     */
    @GET
    @Path("/current")
    public Response getCurrentImpersonation() {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_IMPERSONATE);
        LOG.debug("GET /platform/impersonate/current");

        Optional<ImpersonationContext> contextOpt = impersonationService.getCurrentImpersonation(actor.id());

        if (contextOpt.isPresent()) {
            return Response.ok(contextOpt.get()).build();
        } else {
            Map<String, String> response = new HashMap<>();
            response.put("message", "No active impersonation session");
            return Response.status(Response.Status.NOT_FOUND).entity(response).build();
        }
    }

    // --- Helper Methods ---

    private InetAddress parseIpAddress(String xForwardedFor) {
        String ipString = "127.0.0.1"; // Default

        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Take first IP if multiple (load balancer chain)
            String[] ips = xForwardedFor.split(",");
            ipString = ips[0].trim();
        }

        try {
            return InetAddress.getByName(ipString);
        } catch (UnknownHostException e) {
            LOG.warnf("Failed to parse IP address: %s", ipString);
            try {
                return InetAddress.getByName("127.0.0.1");
            } catch (UnknownHostException ex) {
                throw new RuntimeException("Failed to create fallback IP address", ex);
            }
        }
    }
}
