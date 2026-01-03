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

    private void auditPortalAccess(String action, UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String principal = securityIdentity != null && securityIdentity.getPrincipal() != null
                ? securityIdentity.getPrincipal().getName()
                : "unknown";
        LOG.infof("AUDIT vendor_portal.%s - tenantId=%s, consignorId=%s, principal=%s", action, tenantId, consignorId,
                principal);
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
