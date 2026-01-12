package villagecompute.storefront.api.vendor;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.ConsignmentItemDto;
import villagecompute.storefront.api.types.ConsignorDto;
import villagecompute.storefront.api.types.PayoutBatchDto;
import villagecompute.storefront.api.types.VendorDashboardDto;
import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.services.ConsignmentService;
import villagecompute.storefront.services.mappers.ConsignmentMapper;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.security.identity.SecurityIdentity;

/**
 * REST resource for vendor portal consignment operations.
 *
 * <p>
 * Provides read-only endpoints for consignors to view their inventory and payouts:
 * <ul>
 * <li>GET /vendor/portal/profile - Get consignor profile</li>
 * <li>GET /vendor/portal/items - List consignor's items</li>
 * <li>GET /vendor/portal/payouts - List consignor's payout batches</li>
 * </ul>
 *
 * <p>
 * All endpoints require vendor authentication via JWT with 'vendor' role and consignor_id claim.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Vendor portal REST endpoints</li>
 * <li>OpenAPI: /vendor/portal endpoints</li>
 * </ul>
 */
@Path("/api/v1/vendor/portal")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("vendor")
public class VendorPortalResource {

    private static final Logger LOG = Logger.getLogger(VendorPortalResource.class);

    @Inject
    ConsignmentService consignmentService;

    @Inject
    ConsignmentMapper consignmentMapper;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    Instance<JsonWebToken> jsonWebToken;

    /**
     * Get aggregated dashboard data for consignor portal.
     *
     * <p>
     * Provides comprehensive dashboard view including balances (pending/available), payout history, item summaries,
     * Stripe Express onboarding status, and notifications. Designed to minimize API round-trips for initial portal
     * load.
     *
     * <p>
     * References:
     * <ul>
     * <li>Task I4.T2: Consignment vendor portal implementation</li>
     * <li>Architecture §3.5: Consignment Experience Touchpoints</li>
     * </ul>
     *
     * @return aggregated dashboard data
     */
    @GET
    @Path("/dashboard")
    public Response getDashboard() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        UUID consignorId = resolveConsignorId();
        boolean impersonating = detectImpersonation();

        LOG.infof("GET /vendor/portal/dashboard - tenantId=%s, consignorId=%s, impersonating=%b", tenantId, consignorId,
                impersonating);
        auditPortalAccess("dashboard", consignorId, impersonating);

        VendorDashboardDto dashboard = consignmentService.buildVendorDashboard(consignorId);
        if (dashboard == null) {
            return problem(Status.NOT_FOUND, "Not Found", "Consignor dashboard not available");
        }

        return Response.ok(dashboard).build();
    }

    /**
     * Get consignor profile. TODO: Extract consignorId from JWT vendor token claims.
     *
     * @param consignorId
     *            consignor UUID (temporary query param, should come from JWT)
     * @return consignor profile
     */
    @GET
    @Path("/profile")
    public Response getProfile() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        UUID consignorId = resolveConsignorId();
        LOG.infof("GET /vendor/portal/profile - tenantId=%s, consignorId=%s", tenantId, consignorId);
        auditPortalAccess("profile", consignorId);

        Optional<Consignor> consignor = consignmentService.getConsignor(consignorId);
        if (consignor.isEmpty()) {
            return problem(Status.NOT_FOUND, "Not Found", "Consignor not found");
        }

        ConsignorDto dto = consignmentMapper.toDto(consignor.get());
        return Response.ok(dto).build();
    }

    /**
     * List consignor's items.
     *
     * @param consignorId
     *            consignor UUID (temporary query param, should come from JWT)
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of consignment items
     */
    @GET
    @Path("/items")
    public Response listItems(@QueryParam("page") int page, @QueryParam("size") int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        UUID consignorId = resolveConsignorId();
        LOG.infof("GET /vendor/portal/items - tenantId=%s, consignorId=%s, page=%d, size=%d", tenantId, consignorId,
                page, size);
        auditPortalAccess("items", consignorId);

        int pageSize = size > 0 ? size : 20;
        int pageNumber = Math.max(page, 0);
        List<ConsignmentItem> items = consignmentService.getConsignorItems(consignorId, pageNumber, pageSize);
        List<ConsignmentItemDto> dtos = items.stream().map(consignmentMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    /**
     * List consignor's payout batches.
     *
     * @param consignorId
     *            consignor UUID (temporary query param, should come from JWT)
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of payout batches
     */
    @GET
    @Path("/payouts")
    public Response listPayouts(@QueryParam("page") int page, @QueryParam("size") int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        UUID consignorId = resolveConsignorId();
        LOG.infof("GET /vendor/portal/payouts - tenantId=%s, consignorId=%s, page=%d, size=%d", tenantId, consignorId,
                page, size);
        auditPortalAccess("payouts", consignorId);

        int pageSize = size > 0 ? size : 20;
        int pageNumber = Math.max(page, 0);
        List<PayoutBatch> batches = consignmentService.getConsignorPayoutBatches(consignorId, pageNumber, pageSize);
        List<PayoutBatchDto> dtos = batches.stream().map(consignmentMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    private UUID resolveConsignorId() {
        String claimValue = null;
        if (jsonWebToken != null && !jsonWebToken.isUnsatisfied()) {
            JsonWebToken token = jsonWebToken.get();
            if (token != null) {
                claimValue = token.getClaim("consignor_id");
            }
        }

        if ((claimValue == null || claimValue.isBlank()) && securityIdentity != null) {
            Object attribute = securityIdentity.getAttribute("consignor_id");
            if (attribute instanceof UUID uuid) {
                return uuid;
            }
            if (attribute instanceof String attr) {
                claimValue = attr;
            }
        }

        if (claimValue == null || claimValue.isBlank()) {
            throw new WebApplicationException(
                    problem(Status.FORBIDDEN, "Forbidden", "Missing consignor authentication"));
        }

        try {
            return UUID.fromString(claimValue);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(problem(Status.FORBIDDEN, "Forbidden", "Invalid consignor claim"));
        }
    }

    /**
     * Detect if the current request is an impersonation scenario.
     *
     * <p>
     * Checks for impersonation indicator in JWT claims or security attributes. Platform admins can impersonate
     * consignors for support purposes, and all impersonation actions must be logged.
     *
     * @return true if impersonating
     */
    private boolean detectImpersonation() {
        // Check JWT claim for impersonation flag
        if (jsonWebToken != null && !jsonWebToken.isUnsatisfied()) {
            JsonWebToken token = jsonWebToken.get();
            if (token != null) {
                Object impersonatingClaim = token.getClaim("impersonating");
                if (isTruthyFlag(impersonatingClaim)) {
                    return true;
                }
            }
        }

        // Check security attribute for impersonation flag
        if (securityIdentity != null) {
            Object attribute = securityIdentity.getAttribute("impersonating");
            if (isTruthyFlag(attribute)) {
                return true;
            }
        }

        return false;
    }

    private boolean isTruthyFlag(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return false;
    }

    private void auditPortalAccess(String action, UUID consignorId) {
        auditPortalAccess(action, consignorId, false);
    }

    private void auditPortalAccess(String action, UUID consignorId, boolean impersonating) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String principal = securityIdentity != null && securityIdentity.getPrincipal() != null
                ? securityIdentity.getPrincipal().getName()
                : "unknown";

        String impersonationMarker = impersonating ? " [IMPERSONATION]" : "";
        LOG.infof("AUDIT vendor_portal.%s - tenantId=%s, consignorId=%s, principal=%s%s", action, tenantId, consignorId,
                principal, impersonationMarker);

        // Additional audit logging for impersonation
        if (impersonating) {
            LOG.warnf(
                    "SECURITY AUDIT: Platform admin impersonation detected - action=%s, targetConsignor=%s, actor=%s, tenantId=%s",
                    action, consignorId, principal, tenantId);
        }
    }

    private Response problem(Status status, String title, String detail) {
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("type", "about:blank");
        payload.put("title", title);
        payload.put("status", status.getStatusCode());
        if (detail != null && !detail.isBlank()) {
            payload.put("detail", detail);
        }
        return Response.status(status).entity(payload).build();
    }
}
