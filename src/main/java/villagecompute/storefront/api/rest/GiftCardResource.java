package villagecompute.storefront.api.rest;

import java.util.List;
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

import villagecompute.storefront.api.types.CheckGiftCardBalanceRequest;
import villagecompute.storefront.api.types.GiftCardDto;
import villagecompute.storefront.api.types.GiftCardTransactionDto;
import villagecompute.storefront.api.types.IssueGiftCardRequest;
import villagecompute.storefront.api.types.PaginationMetadata;
import villagecompute.storefront.api.types.RedeemGiftCardRequest;
import villagecompute.storefront.api.types.UpdateGiftCardRequest;
import villagecompute.storefront.data.models.GiftCard;
import villagecompute.storefront.data.models.GiftCardTransaction;
import villagecompute.storefront.giftcard.GiftCardMapper;
import villagecompute.storefront.giftcard.GiftCardService;
import villagecompute.storefront.giftcard.GiftCardService.GiftCardRedemptionResult;
import villagecompute.storefront.tenant.TenantContext;

/**
 * REST resource exposing gift card issuance, balance checks, and checkout redemption APIs.
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GiftCardResource {

    private static final Logger LOG = Logger.getLogger(GiftCardResource.class);

    @Inject
    GiftCardService giftCardService;

    @Inject
    GiftCardMapper giftCardMapper;

    @POST
    @Path("/gift-cards/check-balance")
    public Response checkBalance(@Valid CheckGiftCardBalanceRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /gift-cards/check-balance - tenantId=%s", tenantId);

        return giftCardService.findByCode(request.code)
                .map(card -> Response.ok(giftCardMapper.toBalanceResponse(card)).build())
                .orElseGet(() -> Response.status(Status.NOT_FOUND).entity(createError("Gift card not found")).build());
    }

    @POST
    @Path("/gift-cards/redeem")
    public Response redeem(@Valid RedeemGiftCardRequest request,
            @HeaderParam("X-Idempotency-Key") String idempotencyKeyHeader) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /gift-cards/redeem - tenantId=%s, orderId=%s", tenantId, request.orderId);

        String idempotencyKey = resolveIdempotencyKey(request.idempotencyKey, idempotencyKeyHeader);
        if (idempotencyKey == null) {
            return Response.status(Status.BAD_REQUEST).entity(createError("Idempotency key is required")).build();
        }

        try {
            GiftCardRedemptionResult result = giftCardService.redeem(request.code, request.amount, request.orderId,
                    idempotencyKey, request.posDeviceId, request.offlineSyncedAt);
            GiftCardTransactionDto dto = giftCardMapper.toDto(result.transaction());
            return Response.ok(dto).build();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return Response.status(Status.BAD_REQUEST).entity(createError(ex.getMessage())).build();
        }
    }

    @GET
    @Path("/gift-cards/transactions/{giftCardId}")
    public Response listTransactions(@PathParam("giftCardId") Long giftCardId, @QueryParam("page") Integer page,
            @QueryParam("size") Integer size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 20;
        LOG.infof("GET /gift-cards/transactions/%s - tenantId=%s, page=%d, size=%d", giftCardId, tenantId, pageNum,
                pageSize);

        try {
            // Ensure card exists within tenant context before listing ledger entries
            giftCardService.getGiftCard(giftCardId);
            List<GiftCardTransaction> transactions = giftCardService.listTransactions(giftCardId, pageNum, pageSize);
            List<GiftCardTransactionDto> dtos = transactions.stream().map(giftCardMapper::toDto)
                    .collect(Collectors.toList());
            return Response.ok(dtos).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Status.NOT_FOUND).entity(createError(ex.getMessage())).build();
        }
    }

    @GET
    @Path("/admin/gift-cards")
    @RolesAllowed({"admin"})
    public Response listGiftCards(@QueryParam("page") Integer page, @QueryParam("size") Integer size,
            @QueryParam("status") String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 20;
        LOG.infof("GET /admin/gift-cards - tenantId=%s, page=%d, size=%d, status=%s", tenantId, pageNum, pageSize,
                status);

        List<GiftCard> cards = giftCardService.listGiftCards(status, pageNum, pageSize);
        List<GiftCardDto> data = cards.stream().map(giftCardMapper::toDto).collect(Collectors.toList());
        long total = giftCardService.countGiftCards(status);
        PaginationMetadata pagination = new PaginationMetadata(pageNum, pageSize, total);

        return Response.ok(new GiftCardListResponse(data, pagination)).build();
    }

    @POST
    @Path("/admin/gift-cards")
    @RolesAllowed({"admin"})
    public Response issueGiftCard(@Valid IssueGiftCardRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/gift-cards - tenantId=%s", tenantId);

        try {
            GiftCard giftCard = giftCardService.issueGiftCard(request);
            return Response.status(Status.CREATED).entity(giftCardMapper.toDto(giftCard)).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Status.BAD_REQUEST).entity(createError(ex.getMessage())).build();
        }
    }

    @GET
    @Path("/admin/gift-cards/{giftCardId}")
    @RolesAllowed({"admin"})
    public Response getGiftCard(@PathParam("giftCardId") Long giftCardId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/gift-cards/%s - tenantId=%s", giftCardId, tenantId);

        try {
            GiftCard card = giftCardService.getGiftCard(giftCardId);
            return Response.ok(giftCardMapper.toDto(card)).build();
        } catch (IllegalArgumentException ex) {
            return Response.status(Status.NOT_FOUND).entity(createError(ex.getMessage())).build();
        }
    }

    @PUT
    @Path("/admin/gift-cards/{giftCardId}")
    @RolesAllowed({"admin"})
    public Response updateGiftCard(@PathParam("giftCardId") Long giftCardId, @Valid UpdateGiftCardRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/gift-cards/%s - tenantId=%s", giftCardId, tenantId);

        try {
            GiftCard card = giftCardService.updateGiftCard(giftCardId, request);
            return Response.ok(giftCardMapper.toDto(card)).build();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return Response.status(Status.BAD_REQUEST).entity(createError(ex.getMessage())).build();
        }
    }

    private ErrorResponse createError(String message) {
        return new ErrorResponse(message);
    }

    private String resolveIdempotencyKey(String requestValue, String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue;
        }
        if (requestValue != null && !requestValue.isBlank()) {
            return requestValue;
        }
        return null;
    }

    public record GiftCardListResponse(List<GiftCardDto> data, PaginationMetadata pagination) {
    }

    public static class ErrorResponse {
        public String error;

        public ErrorResponse(String error) {
            this.error = error;
        }
    }
}
