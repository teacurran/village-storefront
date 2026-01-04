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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.AdjustStoreCreditRequest;
import villagecompute.storefront.api.types.ConvertGiftCardRequest;
import villagecompute.storefront.api.types.GiftCardTransactionDto;
import villagecompute.storefront.api.types.PaginationMetadata;
import villagecompute.storefront.api.types.RedeemStoreCreditRequest;
import villagecompute.storefront.api.types.StoreCreditAccountDto;
import villagecompute.storefront.api.types.StoreCreditTransactionDto;
import villagecompute.storefront.data.models.StoreCreditAccount;
import villagecompute.storefront.data.models.StoreCreditTransaction;
import villagecompute.storefront.giftcard.GiftCardMapper;
import villagecompute.storefront.giftcard.GiftCardService;
import villagecompute.storefront.storecredit.StoreCreditMapper;
import villagecompute.storefront.storecredit.StoreCreditService;
import villagecompute.storefront.tenant.TenantContext;

/**
 * REST resource exposing store credit account operations for checkout and admin flows.
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StoreCreditResource {

    private static final Logger LOG = Logger.getLogger(StoreCreditResource.class);

    @Inject
    StoreCreditService storeCreditService;

    @Inject
    StoreCreditMapper storeCreditMapper;

    @Inject
    GiftCardService giftCardService;

    @Inject
    GiftCardMapper giftCardMapper;

    @GET
    @Path("/store-credit/balance/{userId}")
    public Response getBalance(@PathParam("userId") UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /store-credit/balance/%s - tenantId=%s", userId, tenantId);

        return storeCreditService.findAccount(userId)
                .map(account -> Response.ok(storeCreditMapper.toDto(account)).build()).orElseGet(() -> Response
                        .status(Status.NOT_FOUND).entity(createError("Store credit account not found")).build());
    }

    @POST
    @Path("/store-credit/redeem/{userId}")
    public Response redeem(@PathParam("userId") UUID userId, @Valid RedeemStoreCreditRequest request,
            @HeaderParam("X-Idempotency-Key") String idempotencyKeyHeader) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /store-credit/redeem/%s - tenantId=%s, orderId=%s", userId, tenantId, request.orderId);

        String idempotencyKey = resolveIdempotencyKey(request.idempotencyKey, idempotencyKeyHeader);
        if (idempotencyKey == null) {
            return Response.status(Status.BAD_REQUEST).entity(createError("Idempotency key is required")).build();
        }

        try {
            StoreCreditTransaction txn = storeCreditService.redeem(userId, request.amount, request.orderId,
                    idempotencyKey, request.posDeviceId, request.offlineSyncedAt);
            StoreCreditTransactionDto dto = storeCreditMapper.toDto(txn);
            return Response.ok(dto).build();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return Response.status(Status.BAD_REQUEST).entity(createError(ex.getMessage())).build();
        }
    }

    @GET
    @Path("/store-credit/transactions/{userId}")
    public Response listTransactions(@PathParam("userId") UUID userId, @QueryParam("page") Integer page,
            @QueryParam("size") Integer size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 20;
        LOG.infof("GET /store-credit/transactions/%s - tenantId=%s, page=%d, size=%d", userId, tenantId, pageNum,
                pageSize);

        return storeCreditService.findAccount(userId).map(account -> {
            List<StoreCreditTransaction> txns = storeCreditService.listTransactions(account.id, pageNum, pageSize);
            List<StoreCreditTransactionDto> dtos = txns.stream().map(storeCreditMapper::toDto)
                    .collect(Collectors.toList());
            return Response.ok(dtos).build();
        }).orElseGet(
                () -> Response.status(Status.NOT_FOUND).entity(createError("Store credit account not found")).build());
    }

    @GET
    @Path("/admin/store-credit/accounts")
    @RolesAllowed({"admin"})
    public Response listAccounts(@QueryParam("page") Integer page, @QueryParam("size") Integer size,
            @QueryParam("status") String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        int pageNum = page != null ? page : 0;
        int pageSize = size != null ? size : 20;
        LOG.infof("GET /admin/store-credit/accounts - tenantId=%s, status=%s", tenantId, status);

        List<StoreCreditAccount> accounts = storeCreditService.listAccounts(status, pageNum, pageSize);
        List<StoreCreditAccountDto> data = accounts.stream().map(storeCreditMapper::toDto).collect(Collectors.toList());
        long total = storeCreditService.countAccounts(status);
        PaginationMetadata pagination = new PaginationMetadata(pageNum, pageSize, total);
        return Response.ok(new StoreCreditListResponse(data, pagination)).build();
    }

    @POST
    @Path("/admin/store-credit/adjust/{userId}")
    @RolesAllowed({"admin"})
    public Response adjust(@PathParam("userId") UUID userId, @Valid AdjustStoreCreditRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/store-credit/adjust/%s - tenantId=%s", userId, tenantId);

        try {
            StoreCreditTransaction transaction = storeCreditService.adjust(userId, request.amount, request.reason);
            return Response.ok(storeCreditMapper.toDto(transaction)).build();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return Response.status(Status.BAD_REQUEST).entity(createError(ex.getMessage())).build();
        }
    }

    @POST
    @Path("/admin/store-credit/convert-gift-card")
    @RolesAllowed({"admin"})
    public Response convertGiftCard(@Valid ConvertGiftCardRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/store-credit/convert-gift-card - tenantId=%s, giftCardId=%s, userId=%s", tenantId,
                request.giftCardId, request.userId);

        try {
            GiftCardService.GiftCardConversionResult result = giftCardService.convertToStoreCredit(request.giftCardId,
                    request.userId, request.reason);
            GiftCardTransactionDto giftCardTxn = giftCardMapper.toDto(result.giftCardTransaction());
            StoreCreditTransactionDto storeCreditTxn = storeCreditMapper.toDto(result.storeCreditTransaction());
            return Response.ok(new ConversionResponse(giftCardTxn, storeCreditTxn)).build();
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

    public record StoreCreditListResponse(List<StoreCreditAccountDto> data, PaginationMetadata pagination) {
    }

    public record ConversionResponse(GiftCardTransactionDto giftCardTransaction,
            StoreCreditTransactionDto storeCreditTransaction) {
    }

    public static class ErrorResponse {
        public String error;

        public ErrorResponse(String error) {
            this.error = error;
        }
    }
}
