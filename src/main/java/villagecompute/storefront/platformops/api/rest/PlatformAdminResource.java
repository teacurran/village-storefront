package villagecompute.storefront.platformops.api.rest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.storefront.platformops.api.types.HealthMetricsSummary;
import villagecompute.storefront.platformops.api.types.StoreDirectoryEntry;
import villagecompute.storefront.platformops.data.models.PlatformAdminRole;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService;
import villagecompute.storefront.platformops.security.PlatformAdminAuthorizationService.PlatformAdminPrincipal;
import villagecompute.storefront.platformops.services.HealthMetricsService;
import villagecompute.storefront.platformops.services.PlatformAdminService;

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
    public Response suspendStore(@PathParam("storeId") UUID storeId, Map<String, String> request) {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_SUSPEND_TENANT);
        LOG.infof("POST /platform/stores/%s/suspend", storeId);

        String reason = request != null ? request.get("reason") : null;
        if (reason == null || reason.isBlank()) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Suspension reason is required");
            return Response.status(Response.Status.BAD_REQUEST).entity(error).build();
        }
        try {
            platformAdminService.suspendStore(storeId, reason, actor.id(), actor.email());

            Map<String, String> response = new HashMap<>();
            response.put("message", "Store suspended successfully");
            response.put("storeId", storeId.toString());
            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).entity(error).build();
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
    public Response reactivateStore(@PathParam("storeId") UUID storeId) {
        PlatformAdminPrincipal actor = authorizationService.requirePermissions(securityIdentity,
                PlatformAdminRole.PERMISSION_SUSPEND_TENANT);
        LOG.infof("POST /platform/stores/%s/reactivate", storeId);

        try {
            platformAdminService.reactivateStore(storeId, actor.id(), actor.email());

            Map<String, String> response = new HashMap<>();
            response.put("message", "Store reactivated successfully");
            response.put("storeId", storeId.toString());
            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).entity(error).build();
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
}
