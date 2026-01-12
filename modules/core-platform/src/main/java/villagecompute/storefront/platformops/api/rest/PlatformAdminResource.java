package villagecompute.storefront.platformops.api.rest;

import java.net.InetAddress;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.storefront.platformops.api.types.HealthMetricsSummary;
import villagecompute.storefront.platformops.api.types.ImpersonationContext;
import villagecompute.storefront.platformops.api.types.ImpersonationRequest;
import villagecompute.storefront.platformops.api.types.PlanChangeRequest;
import villagecompute.storefront.platformops.api.types.PlatformMetricsResponse;
import villagecompute.storefront.platformops.api.types.StoreDirectoryEntry;
import villagecompute.storefront.platformops.api.types.TenantLifecycleRequest;
import villagecompute.storefront.platformops.api.types.TenantPlanInfo;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService.PlatformAdminPrincipal;
import villagecompute.storefront.platformops.services.HealthMetricsService;
import villagecompute.storefront.platformops.services.ImpersonationService;
import villagecompute.storefront.platformops.services.PlatformAdminService;
import villagecompute.storefront.platformops.services.StoreMetricsService;
import villagecompute.storefront.util.ProblemDetailsUtil;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * Platform admin console REST resource.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>GET /api/v1/platform/stores - Store directory listing</li>
 * <li>GET /api/v1/platform/stores/{storeId} - Store details</li>
 * <li>POST /api/v1/platform/stores/{storeId}/suspend - Suspend store</li>
 * <li>POST /api/v1/platform/stores/{storeId}/reactivate - Reactivate store</li>
 * <li>GET /api/v1/platform/health - Current system health</li>
 * </ul>
 *
 * <p>
 * <strong>IMPORTANT:</strong> All endpoints require Platform Super-User RBAC scope. In production, these would be
 * protected by security annotations or filters checking platform admin permissions.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 4.0</li>
 * <li>Pattern: ReportsResource (similar structure)</li>
 * </ul>
 */
@Path("/api/v1/platform")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class PlatformAdminResource {

    private static final Logger LOG = Logger.getLogger(PlatformAdminResource.class);

    @Inject
    PlatformAdminService platformAdminService;

    @Inject
    HealthMetricsService healthMetricsService;

    @Inject
    StoreMetricsService storeMetricsService;

    @Inject
    ImpersonationService impersonationService;

    @Inject
    PlatformAdminAuthorizationService authorizationService;

    @Inject
    SecurityIdentity securityIdentity;

    /**
     * Get store directory with pagination and filters.
     *
     * <p>
     * RBAC: Requires 'platform_super_user' or 'view_all_stores' permission.
     *
     * @param status
     *            optional status filter ('active', 'suspended', etc.)
     * @param search
     *            optional search query (subdomain or name)
     * @param page
     *            page number (default 0)
     * @param size
     *            page size (default 20)
     * @return paginated store directory
     */
    @GET
    @Path("/stores")
    public Response getStoreDirectory(@QueryParam("status") String status, @QueryParam("search") String search,
            @QueryParam("page") Integer page, @QueryParam("size") Integer size) {

        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 20;

        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_STORES);

        LOG.infof("GET /platform/stores - status=%s, search=%s, page=%d, size=%d", status, search, pageNum, pageSize);

        // TODO: Add RBAC check here - verify platform admin permissions
        // if (!hasPermission("view_all_stores")) { return Response.status(403).build(); }

        List<StoreDirectoryEntry> stores = platformAdminService.getStoreDirectory(status, search, pageNum, pageSize);
        long totalCount = platformAdminService.countStores(status, search);

        Map<String, Object> response = new HashMap<>();
        response.put("stores", stores);
        response.put("page", pageNum);
        response.put("size", pageSize);
        response.put("totalCount", totalCount);

        return Response.ok(response).build();
    }

    /**
     * Get detailed store information.
     *
     * @param storeId
     *            tenant UUID
     * @return store details
     */
    @GET
    @Path("/stores/{storeId}")
    public Response getStoreDetails(@PathParam("storeId") UUID storeId) {
        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_STORES);
        LOG.infof("GET /platform/stores/%s", storeId);

        try {
            StoreDirectoryEntry store = platformAdminService.getStoreDetails(storeId);
            return Response.ok(store).build();
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Store not found");
            error.put("storeId", storeId.toString());
            return Response.status(Response.Status.NOT_FOUND).entity(error).build();
        }
    }

    /**
     * Suspend a store (platform admin action).
     *
     * @param storeId
     *            tenant UUID
     * @param request
     *            suspension request with reason
     * @return success response
     */
    @POST
    @Path("/stores/{storeId}/suspend")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response suspendStore(@PathParam("storeId") UUID storeId, TenantLifecycleRequest request) {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_SUSPEND_TENANT);
        LOG.infof("POST /platform/stores/%s/suspend", storeId);

        String validationError = validateLifecycleRequest(request);
        if (validationError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(validationError))
                    .build();
        }
        try {
            platformAdminService.suspendStore(storeId, request.reason.trim(), request.ticketNumber.trim(), actor.id(),
                    actor.email());

            Map<String, String> response = new HashMap<>();
            response.put("message", "Store suspended successfully");
            response.put("storeId", storeId.toString());
            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound(e.getMessage()))
                    .build();
        }
    }

    /**
     * Reactivate a suspended store.
     *
     * @param storeId
     *            tenant UUID
     * @return success response
     */
    @POST
    @Path("/stores/{storeId}/reactivate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reactivateStore(@PathParam("storeId") UUID storeId, TenantLifecycleRequest request) {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_SUSPEND_TENANT);
        LOG.infof("POST /platform/stores/%s/reactivate", storeId);

        String validationError = validateLifecycleRequest(request);
        if (validationError != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(validationError))
                    .build();
        }

        try {
            platformAdminService.reactivateStore(storeId, request.reason.trim(), request.ticketNumber.trim(),
                    actor.id(), actor.email());

            Map<String, String> response = new HashMap<>();
            response.put("message", "Store reactivated successfully");
            response.put("storeId", storeId.toString());
            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound(e.getMessage()))
                    .build();
        }
    }

    /**
     * Get current system health metrics.
     *
     * @return health metrics summary
     */
    @GET
    @Path("/health")
    public Response getSystemHealth() {
        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_HEALTH);
        LOG.debug("GET /platform/health");

        HealthMetricsSummary health = healthMetricsService.getCurrentHealth();
        return Response.ok(health).build();
    }

    // --- Plan Management Endpoints ---

    /**
     * Get current plan for a store.
     *
     * @param storeId
     *            tenant UUID
     * @return tenant plan info
     */
    @GET
    @Path("/stores/{storeId}/plan")
    public Response getStorePlan(@PathParam("storeId") UUID storeId) {
        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_STORES);
        LOG.infof("GET /platform/stores/%s/plan", storeId);

        try {
            TenantPlanInfo planInfo = platformAdminService.getTenantPlan(storeId);
            return Response.ok(planInfo).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(ProblemDetailsUtil.notFound(e.getMessage()))
                    .build();
        }
    }

    /**
     * Change a store's subscription plan.
     *
     * @param storeId
     *            tenant UUID
     * @param request
     *            plan change request
     * @return success response
     */
    @POST
    @Path("/stores/{storeId}/plan")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response changeStorePlan(@PathParam("storeId") UUID storeId, PlanChangeRequest request) {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_SUSPEND_TENANT); // Plan changes require elevated permission
        LOG.infof("POST /platform/stores/%s/plan - newPlan=%s", storeId, request != null ? request.newPlan : null);

        if (request == null || request.newPlan == null || request.newPlan.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("New plan must be specified")).build();
        }

        if (request.reason == null || request.reason.trim().length() < 10) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("Plan change reason must be at least 10 characters")).build();
        }

        try {
            platformAdminService.changeTenantPlan(storeId, request.newPlan, request.reason, request.ticketNumber,
                    actor.id(), actor.email());

            Map<String, String> response = new HashMap<>();
            response.put("message", "Plan changed successfully");
            response.put("storeId", storeId.toString());
            response.put("newPlan", request.newPlan);
            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();
        }
    }

    // --- Impersonation Endpoints ---

    /**
     * Start an impersonation session.
     *
     * @param request
     *            impersonation request
     * @param headers
     *            HTTP headers for User-Agent extraction
     * @return impersonation context
     */
    @POST
    @Path("/impersonation/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response startImpersonation(ImpersonationRequest request, @Context HttpHeaders headers) {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_IMPERSONATE);
        LOG.infof("POST /platform/impersonation/start - targetTenantId=%s, targetUserId=%s",
                request != null ? request.targetTenantId : null, request != null ? request.targetUserId : null);

        if (request == null || request.targetTenantId == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("Target tenant must be specified")).build();
        }

        if (request.reason == null || request.reason.trim().length() < 10) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("Impersonation reason must be at least 10 characters"))
                    .build();
        }

        if (request.ticketNumber == null || request.ticketNumber.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("Support ticket number is required for impersonation"))
                    .build();
        }

        try {
            // Extract IP from X-Forwarded-For header (if behind proxy) or fallback to localhost
            InetAddress ipAddress = InetAddress.getByName("127.0.0.1"); // Simplified - production would parse headers
            String userAgent = headers.getHeaderString("User-Agent");

            ImpersonationContext context = impersonationService.startImpersonation(actor.id(), actor.email(),
                    request.targetTenantId, request.targetUserId, request.reason, request.ticketNumber, ipAddress,
                    userAgent);

            return Response.ok(context).build();

        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to start impersonation session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Failed to start impersonation session")).build();
        }
    }

    /**
     * End current impersonation session.
     *
     * @param headers
     *            HTTP headers for User-Agent extraction
     * @return success response
     */
    @DELETE
    @Path("/impersonation/stop")
    public Response stopImpersonation(@Context HttpHeaders headers) {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_IMPERSONATE);
        LOG.infof("DELETE /platform/impersonation/stop - actorId=%s", actor.id());

        try {
            InetAddress ipAddress = InetAddress.getByName("127.0.0.1"); // Simplified - production would parse headers
            String userAgent = headers.getHeaderString("User-Agent");

            impersonationService.endImpersonation(actor.id(), actor.email(), ipAddress, userAgent);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Impersonation session ended successfully");
            return Response.ok(response).build();

        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to end impersonation session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Failed to end impersonation session")).build();
        }
    }

    /**
     * Renew an active impersonation session, extending its expiration window.
     *
     * @param headers
     *            HTTP headers for User-Agent extraction
     * @return updated impersonation context
     */
    @POST
    @Path("/impersonation/renew")
    public Response renewImpersonation(@Context HttpHeaders headers) {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_IMPERSONATE);
        LOG.infof("POST /platform/impersonation/renew - actorId=%s", actor.id());

        try {
            InetAddress ipAddress = InetAddress.getByName("127.0.0.1");
            String userAgent = headers.getHeaderString("User-Agent");

            ImpersonationContext context = impersonationService.renewImpersonation(actor.id(), actor.email(), ipAddress,
                    userAgent);
            return Response.ok(context).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to renew impersonation session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Failed to renew impersonation session")).build();
        }
    }

    /**
     * Get current impersonation session.
     *
     * @return impersonation context if active, 404 otherwise
     */
    @GET
    @Path("/impersonation/current")
    public Response getCurrentImpersonation() {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_IMPERSONATE);
        LOG.debugf("GET /platform/impersonation/current - actorId=%s", actor.id());

        Optional<ImpersonationContext> context = impersonationService.getCurrentImpersonation(actor.id());

        if (context.isPresent()) {
            return Response.ok(context.get()).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ProblemDetailsUtil.notFound("No active impersonation session")).build();
        }
    }

    // --- Platform Metrics Endpoint ---

    /**
     * Get platform-wide metrics.
     *
     * @param startDate
     *            optional start date for time-bounded metrics
     * @param endDate
     *            optional end date for time-bounded metrics
     * @return platform metrics response
     */
    @GET
    @Path("/metrics")
    public Response getPlatformMetrics(@QueryParam("startDate") String startDate,
            @QueryParam("endDate") String endDate) {
        authorizationService.requirePermissions(securityIdentity, PlatformAdminRole.PERMISSION_VIEW_STORES);
        LOG.infof("GET /platform/metrics - startDate=%s, endDate=%s", startDate, endDate);

        try {
            LocalDate start = startDate != null && !startDate.isBlank() ? LocalDate.parse(startDate) : null;
            LocalDate end = endDate != null && !endDate.isBlank() ? LocalDate.parse(endDate) : null;
            if (start != null && end != null && end.isBefore(start)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ProblemDetailsUtil.badRequest("endDate must be on or after startDate")).build();
            }

            PlatformMetricsResponse metrics = storeMetricsService.getPlatformMetrics(start, end);
            return Response.ok(metrics).build();

        } catch (DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("Dates must be formatted as ISO-8601 (YYYY-MM-DD)")).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to compute platform metrics");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
                    ProblemDetailsUtil.internalServerError("Failed to compute platform metrics: " + e.getMessage()))
                    .build();
        }
    }

    private String validateLifecycleRequest(TenantLifecycleRequest request) {
        if (request == null) {
            return "Request body is required";
        }
        if (request.reason == null || request.reason.trim().length() < 10) {
            return "Reason must be at least 10 characters";
        }
        if (request.ticketNumber == null || request.ticketNumber.trim().isEmpty()) {
            return "Ticket number is required";
        }
        return null;
    }
}
