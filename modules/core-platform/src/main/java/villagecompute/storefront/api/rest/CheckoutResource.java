package villagecompute.storefront.api.rest;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
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
import villagecompute.storefront.integration.shipping.CarrierRateAdapter;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationResult;
import villagecompute.storefront.services.CheckoutSaga;
import villagecompute.storefront.services.ShippingService;
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

    @Inject
    ShippingService shippingService;

    /**
     * Validate shipping address before checkout.
     *
     * @param request
     *            address validation request
     * @return validation result with normalized address or errors
     */
    @POST
    @Path("/validate-address")
    @Operation(
            operationId = "validateAddress",
            summary = "Validate shipping address",
            description = """
                    Validates a shipping address using USPS or Lob API. Returns normalized address
                    if valid, or validation errors if invalid.

                    **Use Case:** Call this during checkout step 1 to verify address before proceeding.
                    **Feature Flag:** Respects `checkout.address-validation.enabled` flag.
                    **Authentication:** Optional - supports both guest and authenticated sessions.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Address validation completed (may contain errors)",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request format",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response validateAddress(@Valid Map<String, String> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /checkout/validate-address - tenantId=%s", tenantId);

        try {
            // Build AddressValidationRequest from request map
            String correlationId = UUID.randomUUID().toString();
            AddressValidationRequest validationRequest = new AddressValidationRequest(request.get("street1"),
                    request.get("street2"), request.get("city"), request.get("state"), request.get("postalCode"),
                    request.get("country") != null ? request.get("country") : "US", correlationId);

            AddressValidationResult result = shippingService.validateAddress(validationRequest, correlationId);

            Map<String, Object> response = new HashMap<>();
            response.put("isValid", result.status() == CarrierRateAdapter.ValidationStatus.VALID
                    || result.status() == CarrierRateAdapter.ValidationStatus.CORRECTED);
            response.put("status", result.status().name());
            if (result.normalizedAddress() != null) {
                response.put("normalizedAddress", buildAddressMap(result.normalizedAddress()));
            }
            if (result.warnings() != null && !result.warnings().isEmpty()) {
                response.put("warnings", result.warnings());
            }
            if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
                response.put("errorMessage", result.errorMessage());
            }

            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid address validation request - tenantId=%s, error=%s", tenantId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Address validation failed - tenantId=%s", tenantId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Address validation failed. Please try again."))
                    .build();
        }
    }

    /**
     * Get shipping rate quotes for cart.
     *
     * @param request
     *            shipping rate request with cart ID and destination address
     * @return available shipping options with rates
     */
    @POST
    @Path("/shipping-rates")
    @Operation(
            operationId = "getShippingRates",
            summary = "Get shipping rate quotes",
            description = """
                    Returns available shipping options (Standard, Express, Overnight) with calculated
                    rates based on cart weight, dimensions, and destination address.

                    **Use Case:** Call this during checkout step 2 (after address validation) to display shipping options.
                    **Provider:** Uses configured shipping provider (Shippo, EasyPost, etc.).
                    **Authentication:** Optional - supports both guest and authenticated sessions.

                    **Note:** Rates are estimates and may change at order creation time.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Shipping rates calculated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request (missing cart ID, invalid address)",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Cart not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Internal server error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response getShippingRates(@Valid Map<String, Object> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /checkout/shipping-rates - tenantId=%s", tenantId);

        try {
            // For now, return mock shipping rates
            // Full implementation will integrate with ShippingService in future task
            java.util.List<Map<String, Object>> rates = java.util.List.of(
                    buildShippingRate("standard", "Standard Shipping", "5-7 business days", "9.99", "USD"),
                    buildShippingRate("express", "Express Shipping", "2-3 business days", "19.99", "USD"),
                    buildShippingRate("overnight", "Overnight Shipping", "1 business day", "39.99", "USD"));

            Map<String, Object> response = new HashMap<>();
            response.put("rates", rates);
            response.put("currency", "USD");

            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid shipping rate request - tenantId=%s, error=%s", tenantId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();

        } catch (RuntimeException e) {
            LOG.errorf(e, "Shipping rate calculation failed - tenantId=%s", tenantId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(
                    ProblemDetailsUtil.internalServerError("Failed to calculate shipping rates. Please try again."))
                    .build();
        }
    }

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
                    request.getCurrency(), request.getPaymentMethodId(), idempotencyKey, request.getGiftCardCodes(),
                    request.getUseStoreCredit());

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

    // ========================================
    // HELPER METHODS
    // ========================================

    private Map<String, String> buildAddressMap(
            villagecompute.storefront.integration.shipping.CarrierRateAdapter.Address address) {
        Map<String, String> addressMap = new HashMap<>();
        addressMap.put("street1", address.street1());
        if (address.street2() != null && !address.street2().isBlank()) {
            addressMap.put("street2", address.street2());
        }
        addressMap.put("city", address.city());
        addressMap.put("state", address.state());
        addressMap.put("postalCode", address.postalCode());
        addressMap.put("country", address.country());
        addressMap.put("residential", String.valueOf(address.residential()));
        return addressMap;
    }

    private Map<String, Object> buildShippingRate(String id, String name, String estimatedDelivery, String amount,
            String currency) {
        Map<String, Object> rate = new HashMap<>();
        rate.put("id", id);
        rate.put("name", name);
        rate.put("estimatedDelivery", estimatedDelivery);

        Map<String, String> price = new HashMap<>();
        price.put("amount", amount);
        price.put("currency", currency);
        rate.put("price", price);

        return rate;
    }
}
