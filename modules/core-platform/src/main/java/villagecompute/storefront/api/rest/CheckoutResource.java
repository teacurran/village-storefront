package villagecompute.storefront.api.rest;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.CheckoutCommitRequest;
import villagecompute.storefront.exceptions.CheckoutDisabledException;
import villagecompute.storefront.exceptions.IdempotencyConflictException;
import villagecompute.storefront.services.CheckoutSaga;
import villagecompute.storefront.tenant.TenantContext;

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
public class CheckoutResource {

    private static final Logger LOG = Logger.getLogger(CheckoutResource.class);

    @Inject
    CheckoutSaga checkoutSaga;

    /**
     * Complete checkout and create order.
     *
     * @param idempotencyKey
     *            X-Idempotency-Key header for safe retries
     * @param request
     *            checkout commit request
     * @return order created response
     */
    @POST
    @Path("/commit")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response commitCheckout(@HeaderParam("X-Idempotency-Key") String idempotencyKey,
            @Valid CheckoutCommitRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /checkout/commit - tenantId=%s, cartId=%s, idempotencyKey=%s", tenantId, request.getCartId(),
                idempotencyKey);

        // Validate idempotency key
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Response.status(Status.BAD_REQUEST).entity(
                    createProblemDetails("Bad Request", "X-Idempotency-Key header is required", Status.BAD_REQUEST))
                    .build();
        }

        try {
            // Build saga request from API request
            CheckoutSaga.CheckoutRequest sagaRequest = new CheckoutSaga.CheckoutRequest(
                    UUID.fromString(request.getCartId()), request.getCustomerEmail(), request.getShippingAddress(),
                    request.getBillingAddress(), request.getShippingAmount(), request.getTaxAmount(),
                    request.getCurrency(), request.getPaymentMethodId(), idempotencyKey);

            CheckoutSaga.CheckoutResult result = checkoutSaga.execute(sagaRequest);

            // Convert saga result to API response
            Map<String, Object> response = new HashMap<>();
            response.put("orderId", result.orderId());
            response.put("orderNumber", result.orderNumber());
            response.put("paymentIntentId", result.paymentIntentId());
            response.put("status", result.orderStatus());
            response.put("total",
                    Map.of("amount", result.totalAmount().toPlainString(), "currency", result.currency()));
            response.put("createdAt", result.paidAt());

            return Response.status(Status.CREATED)
                    .header("Location", String.format("/api/v1/orders/%s", result.orderId())).entity(response).build();

        } catch (IdempotencyConflictException e) {
            LOG.warnf("Idempotency conflict - tenantId=%s, key=%s", tenantId, idempotencyKey);
            return Response.status(Status.CONFLICT)
                    .entity(createProblemDetails("Conflict",
                            "A request with this idempotency key is currently being processed. Please retry later.",
                            Status.CONFLICT))
                    .build();

        } catch (CheckoutDisabledException e) {
            LOG.warnf("Checkout disabled - tenantId=%s, error=%s", tenantId, e.getMessage());
            return Response.status(Status.SERVICE_UNAVAILABLE)
                    .entity(createProblemDetails("Service Unavailable",
                            "Checkout is temporarily disabled. Please try again later.", Status.SERVICE_UNAVAILABLE))
                    .build();

        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warnf("Invalid checkout request - tenantId=%s, error=%s", tenantId, e.getMessage());
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Bad Request", e.getMessage(), Status.BAD_REQUEST)).build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Checkout failed - tenantId=%s, cartId=%s", tenantId, request.getCartId());
            return Response.status(Status.INTERNAL_SERVER_ERROR)
                    .entity(createProblemDetails("Internal Server Error",
                            "Checkout failed due to an unexpected error. Please contact support.",
                            Status.INTERNAL_SERVER_ERROR))
                    .build();
        }
    }

    /**
     * Create RFC 7807 Problem Details error response.
     *
     * @param title
     *            error title
     * @param detail
     *            error detail message
     * @param status
     *            HTTP status code
     * @return problem details object
     */
    private Map<String, Object> createProblemDetails(String title, String detail, Status status) {
        Map<String, Object> problem = new HashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status.getStatusCode());
        if (detail != null && !detail.isBlank()) {
            problem.put("detail", detail);
        }
        return problem;
    }
}
