package villagecompute.storefront.api.rest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.api.types.AdjustPointsRequest;
import villagecompute.storefront.api.types.LoyaltyMemberDto;
import villagecompute.storefront.api.types.LoyaltyProgramDto;
import villagecompute.storefront.api.types.LoyaltyTransactionDto;
import villagecompute.storefront.api.types.RedeemPointsRequest;
import villagecompute.storefront.api.types.UpsertLoyaltyProgramRequest;
import villagecompute.storefront.data.models.LoyaltyMember;
import villagecompute.storefront.data.models.LoyaltyProgram;
import villagecompute.storefront.data.models.LoyaltyTransaction;
import villagecompute.storefront.loyalty.LoyaltyMapper;
import villagecompute.storefront.loyalty.LoyaltyService;
import villagecompute.storefront.tenant.TenantContext;

/**
 * REST resource for loyalty program operations.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>GET /loyalty/program - Get active loyalty program</li>
 * <li>GET /loyalty/member/{userId} - Get loyalty member details</li>
 * <li>POST /loyalty/enroll/{userId} - Enroll user in loyalty program</li>
 * <li>POST /loyalty/redeem/{userId} - Redeem points for discount</li>
 * <li>GET /loyalty/transactions/{userId} - Get transaction history</li>
 * <li>POST /admin/loyalty/adjust/{userId} - Admin: Adjust points</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped via TenantContext. Admin endpoints require appropriate roles.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty REST endpoints with admin controls</li>
 * <li>OpenAPI: /loyalty endpoints specification</li>
 * </ul>
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LoyaltyResource {

    private static final Logger LOG = Logger.getLogger(LoyaltyResource.class);

    @Inject
    LoyaltyService loyaltyService;

    @Inject
    LoyaltyMapper loyaltyMapper;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Get active loyalty program for current tenant.
     *
     * @return program DTO
     */
    @GET
    @Path("/loyalty/program")
    public Response getProgram() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /loyalty/program - tenantId=%s", tenantId);

        Optional<LoyaltyProgram> program = loyaltyService.getActiveProgram();
        if (program.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(createError("No active loyalty program found")).build();
        }

        LoyaltyProgramDto dto = loyaltyMapper.toDto(program.get());
        return Response.ok(dto).build();
    }

    /**
     * Get loyalty member details for user.
     *
     * @param userId
     *            user UUID
     * @return member DTO
     */
    @GET
    @Path("/loyalty/member/{userId}")
    public Response getMember(@PathParam("userId") UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /loyalty/member/%s - tenantId=%s", userId, tenantId);

        Optional<LoyaltyMember> member = loyaltyService.getMemberByUser(userId);
        if (member.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(createError("User not enrolled in loyalty program"))
                    .build();
        }

        LoyaltyMemberDto dto = loyaltyMapper.toDto(member.get());
        return Response.ok(dto).build();
    }

    /**
     * Enroll user in loyalty program.
     *
     * @param userId
     *            user UUID
     * @return member DTO
     */
    @POST
    @Path("/loyalty/enroll/{userId}")
    public Response enrollMember(@PathParam("userId") UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /loyalty/enroll/%s - tenantId=%s", userId, tenantId);

        try {
            LoyaltyMember member = loyaltyService.enrollMember(userId);
            LoyaltyMemberDto dto = loyaltyMapper.toDto(member);
            return Response.status(Status.CREATED).entity(dto).build();
        } catch (IllegalStateException e) {
            return Response.status(Status.BAD_REQUEST).entity(createError(e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST).entity(createError(e.getMessage())).build();
        }
    }

    /**
     * Redeem points for discount.
     *
     * @param userId
     *            user UUID
     * @param request
     *            redemption request
     * @return transaction DTO
     */
    @POST
    @Path("/loyalty/redeem/{userId}")
    public Response redeemPoints(@PathParam("userId") UUID userId, @Valid RedeemPointsRequest request,
            @HeaderParam("X-Idempotency-Key") String idempotencyKeyHeader) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /loyalty/redeem/%s - tenantId=%s, points=%d", userId, tenantId, request.pointsToRedeem);

        String idempotencyKey = resolveIdempotencyKey(request, idempotencyKeyHeader);
        if (idempotencyKey == null) {
            return Response.status(Status.BAD_REQUEST).entity(createError("Idempotency key is required")).build();
        }

        try {
            LoyaltyTransaction transaction = loyaltyService.redeemPoints(userId, request.pointsToRedeem,
                    idempotencyKey);
            LoyaltyTransactionDto dto = loyaltyMapper.toDto(transaction);
            return Response.ok(dto).build();
        } catch (IllegalStateException e) {
            return Response.status(Status.BAD_REQUEST).entity(createError(e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST).entity(createError(e.getMessage())).build();
        }
    }

    /**
     * Get transaction history for user.
     *
     * @param userId
     *            user UUID
     * @param page
     *            page number (default 0)
     * @param size
     *            page size (default 20)
     * @return list of transaction DTOs
     */
    @GET
    @Path("/loyalty/transactions/{userId}")
    public Response getTransactionHistory(@PathParam("userId") UUID userId, @QueryParam("page") Integer page,
            @QueryParam("size") Integer size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 20;
        LOG.infof("GET /loyalty/transactions/%s - tenantId=%s, page=%d, size=%d", userId, tenantId, pageNum, pageSize);

        try {
            List<LoyaltyTransaction> transactions = loyaltyService.getTransactionHistory(userId, pageNum, pageSize);
            List<LoyaltyTransactionDto> dtos = transactions.stream().map(loyaltyMapper::toDto)
                    .collect(Collectors.toList());
            return Response.ok(dtos).build();
        } catch (IllegalStateException e) {
            return Response.status(Status.BAD_REQUEST).entity(createError(e.getMessage())).build();
        }
    }

    /**
     * Admin: Adjust points for user.
     *
     * @param userId
     *            user UUID
     * @param request
     *            adjustment request
     * @return transaction DTO
     */
    @POST
    @Path("/admin/loyalty/adjust/{userId}")
    @RolesAllowed({"admin"})
    public Response adjustPoints(@PathParam("userId") UUID userId, @Valid AdjustPointsRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/loyalty/adjust/%s - tenantId=%s, points=%d", userId, tenantId, request.points);

        try {
            LoyaltyTransaction transaction = loyaltyService.adjustPoints(userId, request.points, request.reason);
            LoyaltyTransactionDto dto = loyaltyMapper.toDto(transaction);
            return Response.ok(dto).build();
        } catch (IllegalStateException e) {
            return Response.status(Status.BAD_REQUEST).entity(createError(e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST).entity(createError(e.getMessage())).build();
        }
    }

    /**
     * Admin: Get loyalty program configuration (enabled or disabled).
     */
    @GET
    @Path("/admin/loyalty/program")
    @RolesAllowed({"admin"})
    public Response getProgramAdmin() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/loyalty/program - tenantId=%s", tenantId);

        Optional<LoyaltyProgram> program = loyaltyService.getProgramForTenant();
        if (program.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(createError("Loyalty program not configured")).build();
        }

        LoyaltyProgramDto dto = loyaltyMapper.toDto(program.get());
        return Response.ok(dto).build();
    }

    /**
     * Admin: Create or update loyalty program configuration.
     */
    @PUT
    @Path("/admin/loyalty/program")
    @RolesAllowed({"admin"})
    public Response upsertProgram(@Valid UpsertLoyaltyProgramRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/loyalty/program - tenantId=%s, name=%s", tenantId, request.name);

        Optional<LoyaltyProgram> existing = loyaltyService.getProgramForTenant();
        LoyaltyProgram program = existing.orElseGet(LoyaltyProgram::new);
        boolean isNew = program.id == null;

        try {
            applyProgramConfig(program, request);
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST).entity(createError(e.getMessage())).build();
        }

        loyaltyService.saveProgram(program);
        LoyaltyProgramDto dto = loyaltyMapper.toDto(program);
        return Response.status(isNew ? Status.CREATED : Status.OK).entity(dto).build();
    }

    private ErrorResponse createError(String message) {
        return new ErrorResponse(message);
    }

    private String resolveIdempotencyKey(RedeemPointsRequest request, String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue;
        }
        if (request.idempotencyKey != null && !request.idempotencyKey.isBlank()) {
            return request.idempotencyKey;
        }
        return null;
    }

    private void applyProgramConfig(LoyaltyProgram program, UpsertLoyaltyProgramRequest request) {
        program.name = request.name;
        program.description = request.description;
        program.enabled = request.enabled != null ? request.enabled : Boolean.TRUE;
        program.pointsPerDollar = request.pointsPerDollar;
        program.redemptionValuePerPoint = request.redemptionValuePerPoint;
        program.minRedemptionPoints = request.minRedemptionPoints;
        program.maxRedemptionPoints = request.maxRedemptionPoints;
        program.pointsExpirationDays = request.pointsExpirationDays;

        try {
            program.tierConfig = objectMapper.writeValueAsString(request.tiers);
            if (request.metadata != null && !request.metadata.isEmpty()) {
                program.metadata = objectMapper.writeValueAsString(request.metadata);
            } else {
                program.metadata = null;
            }
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize loyalty program config", e);
            throw new IllegalArgumentException("Invalid tier configuration");
        }
    }

    public static class ErrorResponse {
        public String error;

        public ErrorResponse(String error) {
            this.error = error;
        }
    }
}
