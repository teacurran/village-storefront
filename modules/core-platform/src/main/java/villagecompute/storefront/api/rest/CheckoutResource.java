package villagecompute.storefront.api.rest;

import java.net.URI;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.CheckoutCommitRequest;
import villagecompute.storefront.api.types.CheckoutResponse;
import villagecompute.storefront.exceptions.CheckoutDisabledException;
import villagecompute.storefront.exceptions.IdempotencyConflictException;
import villagecompute.storefront.services.CheckoutSaga;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.util.ProblemDetailsUtil;

/**
 * REST resource for checkout operations.
 *
 * <p>
 * Provides endpoints for checkout flow:
 * <ul>
 * <li>POST /checkout/commit - Complete checkout and create order</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped via TenantContext. Checkout operations use the CheckoutSaga for orchestration with
 * idempotency guarantees and compensating transaction support.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Checkout REST endpoints with ProblemDetails and idempotency</li>
 * <li>OpenAPI: /checkout endpoints specification</li>
 * <li>ADR-003: Checkout saga pattern</li>
 * </ul>
 */
@Path("/api/v1/checkout")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
        name = "Storefront",
        description = "Customer-facing checkout operations")
public class CheckoutResource {

    private static final Logger LOG = Logger.getLogger(CheckoutResource.class);

    @Inject
    CheckoutSaga checkoutSaga;

    /**
     * Complete checkout and create order.
     *
     * <p>
     * Atomically creates order, processes payment via Stripe, reduces inventory, and clears cart. Uses idempotency key
     * to prevent duplicate orders on retry.
     *
     * <p>
     * **Feature Flag:** Requires {@code checkout.order-creation.enabled} to be true. Returns 503 if disabled.
     *
     * <p>
     * **Idempotency:** Requires {@code X-Idempotency-Key} header. Duplicate requests within 60 minutes return cached
     * response with 200 OK (not 201).
     *
     * @param idempotencyKey
     *            required idempotency key (UUID v4) from header
     * @param request
     *            checkout commit request
     * @return checkout response with order details
     */
    @POST
    @Path("/commit")
    @Operation(
            summary = "Complete checkout and create order",
            description = """
                    Atomically:
                    1. Validates cart and inventory availability
                    2. Creates order record
                    3. Processes payment via Stripe
                    4. Reduces inventory
                    5. Clears cart

                    **Idempotent:** Uses X-Idempotency-Key header to prevent duplicate orders.
                    **Requires authentication:** Must be logged-in customer or use valid API key.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "201",
                    description = "Order successfully created and payment charged",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = CheckoutResponse.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request (validation errors, out of stock, etc.)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "402",
                            description = "Payment required (payment method declined)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "409",
                            description = "Duplicate idempotency key (order already created or in-flight request)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "503",
                            description = "Service unavailable (checkout feature disabled)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response commitCheckout(@Parameter(
            description = "Idempotency key (UUID v4) for safe retries",
            required = true) @HeaderParam("X-Idempotency-Key") @NotBlank(
                    message = "X-Idempotency-Key header is required") String idempotencyKey,
            @Valid CheckoutCommitRequest request) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /checkout/commit - tenantId=%s, cartId=%s, idempotencyKey=%s", tenantId, request.getCartId(),
                idempotencyKey);

        // Validate idempotency key format
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ProblemDetailsUtil.badRequest("X-Idempotency-Key must be a valid UUID")).build();
        }

        try {
            // Build saga request from API request
            CheckoutSaga.CheckoutRequest sagaRequest = new CheckoutSaga.CheckoutRequest(
                    UUID.fromString(request.getCartId()), request.getCustomerEmail(), request.getShippingAddress(),
                    request.getBillingAddress(), request.getShippingAmount(), request.getTaxAmount(),
                    request.getCurrency(), request.getPaymentMethodId(), idempotencyKey);

            CheckoutSaga.CheckoutResult result = checkoutSaga.execute(sagaRequest);

            // Build response DTO
            CheckoutResponse response = new CheckoutResponse(result.orderId(), result.orderNumber(),
                    result.paymentIntentId(), result.orderStatus(), result.totalAmount(), result.currency(),
                    result.paidAt());

            // Return 201 Created with Location header
            URI location = URI.create(String.format("/api/v1/orders/%s", result.orderId()));
            return Response.created(location).entity(response).build();

        } catch (IdempotencyConflictException e) {
            LOG.warnf("Idempotency conflict - tenantId=%s, key=%s", tenantId, idempotencyKey);
            return Response.status(Response.Status.CONFLICT)
                    .entity(ProblemDetailsUtil.conflict(
                            "A request with this idempotency key is currently being processed. Please retry later."))
                    .build();

        } catch (CheckoutDisabledException e) {
            LOG.warnf("Checkout disabled - tenantId=%s, error=%s", tenantId, e.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(
                    ProblemDetailsUtil.serviceUnavailable("Checkout is temporarily disabled. Please try again later."))
                    .build();

        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warnf("Invalid checkout request - tenantId=%s, error=%s", tenantId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Checkout failed - tenantId=%s, cartId=%s", tenantId, request.getCartId());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil
                            .internalServerError("Checkout failed due to an unexpected error. Please contact support."))
                    .build();
        }
    }
}
